#!/usr/bin/env python3
"""Build the server using only Discord's REST API (no gateway connection).

Same result as setup_server.py, for places that can make HTTPS requests but can't hold
a websocket open, such as a sandbox whose proxy adds the bot token to requests.

    DISCORD_TOKEN=... python setup_rest.py GUILD_ID [--update] [--name] [--icon IMG]

If DISCORD_TOKEN is "proxy-injected", no Authorization header is sent and the network
proxy is expected to add it.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import discord  # only for permission maths

import layout
from common import channel_name, load_state, permissions, save_state
from setup_server import BOT_OVERWRITE, MARKER, REASON, VERIFICATION, check_layout

# Each bot's token is attached by the network proxy per host: Evade uses discord.com, Vendetta the
# legacy discordapp.com domain (API v9), so the two tokens never mix.
API = {"evade": "https://discord.com/api/v10"}.get(os.environ.get("SERVER", "evade"), "https://discordapp.com/api/v9")
TEXT, VOICE, CATEGORY, NEWS, FORUM = 0, 2, 4, 5, 15


class Discord:
    def __init__(self, token: str):
        self.headers = {"User-Agent": "DiscordBot (server-setup, 1.0)", "Content-Type": "application/json",
                        "X-Audit-Log-Reason": urllib.request.quote(REASON)}
        if token and token != "proxy-injected":
            self.headers["Authorization"] = f"Bot {token}"

    def __call__(self, method: str, path: str, body=None):
        data = None if body is None else json.dumps(body).encode()
        for attempt in range(6):
            req = urllib.request.Request(API + path, data=data, method=method, headers=self.headers)
            try:
                with urllib.request.urlopen(req, timeout=30) as r:
                    raw = r.read()
                    return json.loads(raw) if raw else None
            except urllib.error.HTTPError as e:
                raw = e.read()
                if e.code == 429:
                    wait = json.loads(raw or b"{}").get("retry_after", 2)
                    time.sleep(float(wait) + 0.25)
                    continue
                if e.code >= 500 and attempt < 5:
                    time.sleep(2 ** attempt)
                    continue
                raise RuntimeError(f"{method} {path} -> {e.code}: {raw.decode(errors='replace')[:500]}") from None
        raise RuntimeError(f"{method} {path}: gave up after retries")


def ow_json(target_id: int, kind: int, perms: dict) -> dict:
    allow, deny = discord.PermissionOverwrite(**perms).pair()
    return {"id": str(target_id), "type": kind, "allow": str(allow.value), "deny": str(deny.value)}


class Setup:
    def __init__(self, api: Discord, guild_id: int, update: bool, rename: bool, icon: Path | None):
        self.api, self.gid, self.update, self.rename, self.icon = api, guild_id, update, rename, icon
        self.state = load_state()
        if self.state.get("guild_id") not in (None, guild_id):
            sys.exit(f"state.json belongs to another server ({self.state['guild_id']}). Move it away first.")
        self.state["guild_id"] = guild_id
        self.roles: dict[str, str] = {}
        self.channels: dict[str, dict] = {}
        self.changes = 0
        self.warnings: list[str] = []

    def log(self, msg):
        print(msg, flush=True)

    def warn(self, msg):
        self.warnings.append(msg)
        self.log("! " + msg)

    def run(self):
        self.guild = self.api("GET", f"/guilds/{self.gid}")
        self.me = self.api("GET", "/users/@me")
        member = self.api("GET", f"/guilds/{self.gid}/members/{self.me['id']}")
        all_roles = {r["id"]: r for r in self.guild["roles"]}
        my_perms = 0
        for rid in member["roles"] + [str(self.gid)]:
            my_perms |= int(all_roles[rid]["permissions"]) if rid in all_roles else 0
        if not my_perms & 8 and self.guild["owner_id"] != self.me["id"]:
            sys.exit("The bot needs Administrator for setup. Re-invite it with permissions=8.")
        self.my_top = max((all_roles[r]["position"] for r in member["roles"] if r in all_roles), default=0)
        self.community = "COMMUNITY" in self.guild["features"]
        self.log(f"Setting up {self.guild['name']} ({'Community' if self.community else 'not Community yet'})")
        self.make_roles()
        self.make_channels()
        self.server_settings()
        self.automod()
        self.posts()
        save_state(self.state)
        self.log(f"\nDone: {self.changes} changes. IDs saved to state.json.")
        if self.warnings:
            self.log("\nWarnings:\n  " + "\n  ".join(self.warnings))

    # Roles
    def make_roles(self):
        existing = {r["id"]: r for r in self.api("GET", f"/guilds/{self.gid}/roles")}
        by_name = {r["name"]: r for r in existing.values()}
        for spec in layout.ROLES:
            perms = 8 if spec.get("admin") else permissions(spec.get("perms", [])).value
            body = {"name": spec["name"], "permissions": str(perms), "color": spec.get("color", 0),
                    "hoist": spec.get("hoist", False), "mentionable": False}
            role = existing.get(str(self.state["roles"].get(spec["key"]))) or by_name.get(spec["name"])
            if role is None:
                role = self.api("POST", f"/guilds/{self.gid}/roles", body)
                self.log(f"+ role @{role['name']}")
                self.changes += 1
            elif self.update and not role.get("managed"):
                if role["position"] >= self.my_top:
                    self.warn(f"left @{role['name']} alone: it's above the bot's role")
                else:
                    role = self.api("PATCH", f"/guilds/{self.gid}/roles/{role['id']}", body)
                    self.log(f"~ role @{role['name']}")
                    self.changes += 1
            self.roles[spec["key"]] = role["id"]
            self.state["roles"][spec["key"]] = int(role["id"])
        self.roles["@everyone"] = str(self.gid)

        everyone = existing[str(self.gid)]
        want = permissions(layout.EVERYONE_PERMS).value
        if int(everyone["permissions"]) != want:
            self.api("PATCH", f"/guilds/{self.gid}/roles/{self.gid}", {"permissions": str(want)})
            self.log("~ @everyone stripped (unverified users only see the gate)")
            self.changes += 1

        # Order under the bot's role, highest first. Creating roles shifts the bot's role up,
        # so re-read positions; roles already above the bot are left where they are.
        roles_now = self.api("GET", f"/guilds/{self.gid}/roles")
        current = {r["id"]: r["position"] for r in roles_now}
        member = self.api("GET", f"/guilds/{self.gid}/members/{self.me['id']}")
        self.my_top = max(current[r] for r in member["roles"])
        order = [self.roles[s["key"]] for s in layout.ROLES if current[self.roles[s["key"]]] < self.my_top]
        skipped = [s["name"] for s in layout.ROLES if current[self.roles[s["key"]]] >= self.my_top]
        if skipped:
            self.warn(f"{', '.join(skipped)} sit above the bot's role; drag them into place by hand if needed")
        target = [{"id": rid, "position": self.my_top - 1 - i} for i, rid in enumerate(order)]
        if target and all(t["position"] > 0 for t in target) and any(current[t["id"]] != t["position"] for t in target):
            self.api("PATCH", f"/guilds/{self.gid}/roles", target)
            self.log("~ role order")

    def overwrites(self, spec: dict) -> list:
        out = [ow_json(self.roles[k], 0, v) for k, v in spec.items()]
        out.append(ow_json(self.me["id"], 1, BOT_OVERWRITE))
        return out

    # Channels
    def make_channels(self):
        chans = self.api("GET", f"/guilds/{self.gid}/channels")
        by_id = {c["id"]: c for c in chans}

        def find(key, name, types, store):
            c = by_id.get(str(self.state[store].get(key)))
            if c and c["type"] in types:
                return c
            return next((c for c in chans if c["name"] == name and c["type"] in types), None)

        for pos, cat_spec in enumerate(layout.CATEGORIES):
            body = {"name": cat_spec["name"], "type": CATEGORY, "position": pos,
                    "permission_overwrites": self.overwrites(cat_spec["perms"])}
            cat = find(cat_spec["key"], cat_spec["name"], (CATEGORY,), "categories")
            if cat is None:
                cat = self.api("POST", f"/guilds/{self.gid}/channels", body)
                self.log(f"+ category {cat['name']}")
                self.changes += 1
            elif self.update:
                body.pop("type")
                cat = self.api("PATCH", f"/channels/{cat['id']}", body)
            self.state["categories"][cat_spec["key"]] = int(cat["id"])

            for i, spec in enumerate(cat_spec["channels"]):
                kind = spec.get("type", "text")
                if kind in ("news", "forum") and not self.community:
                    kind = "text"
                ctype = {"text": TEXT, "news": NEWS, "forum": FORUM, "voice": VOICE}[kind]
                name = channel_name(spec)
                types = {VOICE: (VOICE,), FORUM: (FORUM, TEXT), NEWS: (NEWS, TEXT)}.get(ctype, (TEXT, NEWS))
                ch = find(spec["key"], name, types, "channels")
                body = {"name": name, "parent_id": cat["id"], "position": i,
                        "permission_overwrites": self.overwrites(spec.get("perms", cat_spec["perms"]))}
                if ctype == VOICE:
                    body["user_limit"] = spec.get("user_limit", 0)
                else:
                    body["topic"] = spec.get("topic", "")
                    body["rate_limit_per_user"] = spec.get("slowmode", 0)
                if ctype == FORUM:
                    body["available_tags"] = [{"name": t} for t in spec.get("tags", [])][:20]
                if ch is not None and ctype == FORUM and ch["type"] == TEXT:
                    self.warn(f"{name} is a text channel; delete it and run --update to make it a forum")
                if ch is None:
                    ch = self.api("POST", f"/guilds/{self.gid}/channels", {**body, "type": ctype})
                    self.log(f"+ {kind} {name}")
                    self.changes += 1
                elif self.update:
                    if ctype == FORUM and ch["type"] == FORUM:
                        have = {t["name"] for t in ch.get("available_tags", [])}
                        body["available_tags"] = ch.get("available_tags", []) + [
                            {"name": t} for t in spec.get("tags", []) if t not in have]
                        body["available_tags"] = body["available_tags"][:20]
                    elif ctype == FORUM:
                        body.pop("available_tags")
                    if ctype == NEWS and ch["type"] == TEXT:
                        body["type"] = NEWS
                    ch = self.api("PATCH", f"/channels/{ch['id']}", body)
                    self.log(f"~ {name}")
                    self.changes += 1
                self.channels[spec["key"]] = ch
                self.state["channels"][spec["key"]] = int(ch["id"])

    # Server settings
    def server_settings(self):
        cid = lambda key: self.channels[key]["id"] if key in self.channels else None
        body = {"verification_level": VERIFICATION[layout.SECURITY["verification_level"]].value,
                "explicit_content_filter": 2, "default_message_notifications": 1,
                "system_channel_id": cid(layout.SYSTEM_CHANNEL), "afk_channel_id": cid(layout.AFK_CHANNEL),
                "afk_timeout": 300}
        if self.community:
            body["rules_channel_id"] = cid(layout.RULES_CHANNEL)
            body["public_updates_channel_id"] = cid(layout.UPDATES_CHANNEL)
        if self.rename:
            body["name"] = layout.CLIENT["name"]
        if self.icon:
            mime = "image/png" if self.icon.suffix.lower() == ".png" else "image/jpeg"
            body["icon"] = f"data:{mime};base64," + base64.b64encode(self.icon.read_bytes()).decode()
        self.api("PATCH", f"/guilds/{self.gid}", body)
        self.log(f"~ server: verification {layout.SECURITY['verification_level']}, media filter, "
                 "mentions-only notifications" + (", name" if self.rename else "") + (", icon" if self.icon else ""))

    def automod(self):
        alert = self.channels.get(layout.AUTOMOD_ALERT_CHANNEL)
        staff = [self.roles[k] for k in layout.STAFF]
        short = layout.CLIENT["short"]
        block = {"type": 1, "metadata": {"custom_message": "Blocked by AutoMod. Open a ticket if this was a mistake."}}
        notify = [{"type": 2, "metadata": {"channel_id": alert["id"]}}] if alert else []
        timeout = {"type": 3, "metadata": {"duration_seconds": 600}}
        rules = [
            (f"{short}: slurs and sexual content", 4, {"presets": [2, 3]}, [block, *notify], []),
            (f"{short}: spam", 3, {}, [block, *notify], staff),
            (f"{short}: mention raids", 5, {"mention_total_limit": 5, "mention_raid_protection_enabled": True},
             [block, *notify, timeout], staff),
            (f"{short}: scams and grabbers", 1, {"keyword_filter": layout.SCAM_KEYWORDS},
             [block, *notify, timeout], staff),
            (f"{short}: invite links", 1, {"regex_patterns": layout.INVITE_REGEX}, [block, *notify], staff),
            (f"{short}: impersonation and scam profiles", 6, {"keyword_filter": layout.PROFILE_KEYWORDS},
             [{"type": 4, "metadata": {}}, *notify], staff),
        ]
        try:
            existing = {r["name"]: r for r in self.api("GET", f"/guilds/{self.gid}/auto-moderation/rules")}
        except RuntimeError as e:
            return self.warn(f"skipped AutoMod: {e}")
        for name, trigger, meta, actions, exempt in rules:
            body = {"name": name, "event_type": 2 if trigger == 6 else 1, "trigger_type": trigger, "trigger_metadata": meta,
                    "actions": actions, "enabled": True, "exempt_roles": exempt}
            try:
                if name not in existing:
                    self.api("POST", f"/guilds/{self.gid}/auto-moderation/rules", body)
                    self.log(f"+ automod {name}")
                    self.changes += 1
                elif self.update:
                    body.pop("trigger_type")
                    self.api("PATCH", f"/guilds/{self.gid}/auto-moderation/rules/{existing[name]['id']}", body)
                    self.log(f"~ automod {name}")
            except RuntimeError as e:
                self.warn(f"AutoMod rule {name!r} failed: {e}")

    # Posts
    def mention(self, text):
        return re.sub(r"#([a-z0-9-]+)",
                      lambda m: f"<#{self.channels[m[1]]['id']}>" if m[1] in self.channels else m[0], text)

    def posts(self):
        try:
            if not layout.CLIENT.get("site"):
                raise LookupError("no website for this server")
            import site_content
            all_posts = site_content.live_posts()[0]
        except LookupError:
            all_posts = layout.POSTS
        except Exception as e:  # noqa: BLE001
            self.warn(f"couldn't read the website, using the static posts in the layout: {e}")
            all_posts = layout.POSTS
        for key, specs in all_posts.items():
            ch = self.channels.get(key)
            if not ch or ch["type"] not in (TEXT, NEWS):
                continue
            mine = {}
            for msg in self.api("GET", f"/channels/{ch['id']}/messages?limit=50"):
                foot = (msg.get("embeds") or [{}])[0].get("footer", {}).get("text", "")
                if msg["author"]["id"] == self.me["id"] and MARKER in foot:
                    mine[foot.split(MARKER, 1)[1]] = msg
            for i, spec in enumerate(specs):
                fmt = (lambda s: self.mention(s)) if spec.get("raw") else (lambda s: self.mention(s.format(**layout.CLIENT)))
                embed = {"title": fmt(spec["title"]), "description": fmt(spec["description"]),
                         "color": layout.CLIENT["color"],
                         "fields": [{"name": fmt(n), "value": fmt(v), "inline": inl} for n, v, inl in spec.get("fields", [])],
                         "footer": {"text": f"{layout.CLIENT['name']} · {MARKER}{key}:{i}"}}
                old = mine.get(f"{key}:{i}")
                if old is None:
                    old = self.api("POST", f"/channels/{ch['id']}/messages", {"embeds": [embed]})
                    self.log(f"+ post in {ch['name']}")
                    self.changes += 1
                elif self.update:
                    old = self.api("PATCH", f"/channels/{ch['id']}/messages/{old['id']}", {"embeds": [embed]})
                    self.log(f"~ post in {ch['name']}")
                if i == 0 and not old.get("pinned"):
                    self.pin(ch["id"], old["id"])

    def pin(self, channel_id, message_id):
        """Pin a post, then delete Discord's "pinned a message" notice so the channel stays tidy."""
        try:
            self.api("PUT", f"/channels/{channel_id}/pins/{message_id}")
            for m in self.api("GET", f"/channels/{channel_id}/messages?limit=5"):
                if m.get("type") == 6 and m["author"]["id"] == self.me["id"]:
                    self.api("DELETE", f"/channels/{channel_id}/messages/{m['id']}")
        except RuntimeError as e:
            self.warn(f"couldn't pin in {channel_id}: {e}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("guild_id", type=int)
    ap.add_argument("--update", action="store_true")
    ap.add_argument("--name", action="store_true")
    ap.add_argument("--icon", default=layout.ICON_PATH or None)
    args = ap.parse_args()
    errors = check_layout()
    if errors:
        sys.exit("layout.py has problems:\n  " + "\n  ".join(errors))
    icon = Path(args.icon).expanduser() if args.icon else None
    Setup(Discord(os.environ.get("DISCORD_TOKEN", "proxy-injected")), args.guild_id,
          args.update, args.name, icon).run()


if __name__ == "__main__":
    main()
