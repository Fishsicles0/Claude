#!/usr/bin/env python3
"""Build your Minecraft client's Discord server from layout.py.

    python setup_server.py --dry-run              # print the plan, no connection
    python setup_server.py --invite APP_ID        # print the bot invite links
    export DISCORD_TOKEN=...                      # the bot's token (never commit it)
    python setup_server.py GUILD_ID --name        # build it (and rename the server)
    python setup_server.py GUILD_ID --update      # re-sync permissions, topics and posts

Safe to re-run: things are matched by the IDs saved in state.json (or by name), only
missing ones are created, and nothing is ever deleted.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from datetime import timedelta
from pathlib import Path

import discord

import layout
from common import (all_channels, channel_name, load_state, permissions, save_state,
                    valid_permission)

REASON = f"{layout.CLIENT['name']} server setup"
MARKER = "server-setup:"   # embed footer on posts this script made

# What bot.py needs once setup is done (Administrator is only for setup).
RUNTIME_PERMS = [
    "view_channel", "send_messages", "send_messages_in_threads", "embed_links", "attach_files",
    "read_message_history", "add_reactions", "use_external_emojis", "manage_messages",
    "manage_threads", "manage_channels", "manage_roles", "moderate_members", "kick_members",
    "view_audit_log", "connect", "ban_members",
]
# Every category gives the bot these, so it keeps working without Administrator.
BOT_OVERWRITE = dict(view_channel=True, send_messages=True, embed_links=True, attach_files=True,
                     read_message_history=True, manage_messages=True, manage_channels=True,
                     manage_roles=True, connect=True)

VERIFICATION = {"low": discord.VerificationLevel.low, "medium": discord.VerificationLevel.medium,
                "high": discord.VerificationLevel.high, "highest": discord.VerificationLevel.highest}


# ── Checks and dry run ────────────────────────────────────────────────────────

def check_layout() -> list[str]:
    errors = []
    role_keys = {r["key"] for r in layout.ROLES}
    for r in layout.ROLES:
        errors += [f"role {r['name']}: unknown permission {p!r}" for p in r.get("perms", []) if not valid_permission(p)]
    errors += [f"EVERYONE_PERMS: unknown permission {p!r}" for p in layout.EVERYONE_PERMS if not valid_permission(p)]

    seen_keys, seen_names = set(), set()
    cat_keys = {c["key"] for c in layout.CATEGORIES}
    for cat in layout.CATEGORIES:
        for owner in (cat, *cat["channels"]):
            for role, perms in owner.get("perms", {}).items():
                if role != "@everyone" and role not in role_keys:
                    errors.append(f"{owner['name']}: unknown role key {role!r}")
                errors += [f"{owner['name']}: unknown permission {p!r}" for p in perms if not valid_permission(p)]
    for _, ch in all_channels():
        if ch["key"] in seen_keys:
            errors.append(f"duplicate channel key {ch['key']!r}")
        name = channel_name(ch)
        if name in seen_names:
            errors.append(f"duplicate channel name {name!r}")
        seen_keys.add(ch["key"])
        seen_names.add(name)
    for key in [*layout.POSTS, layout.SYSTEM_CHANNEL, layout.RULES_CHANNEL, layout.UPDATES_CHANNEL,
                layout.AFK_CHANNEL, layout.AUTOMOD_ALERT_CHANNEL, "verify", "create-ticket",
                "security-alerts", "mod-log", "message-log", "member-log", "role-log", "server-log",
                "ticket-transcripts"]:
        if key not in seen_keys:
            errors.append(f"channel key {key!r} is used but not defined")
    for key in [*layout.LOCKDOWN_CATEGORIES, "tickets"]:
        if key not in cat_keys:
            errors.append(f"category key {key!r} is used but not defined")
    for t in layout.TICKET_TYPES:
        errors += [f"ticket {t['key']}: unknown role {r!r}" for r in t["staff"] if r not in role_keys]
    for k in ("member", "beta", "mod", "admin"):
        if k not in role_keys:
            errors.append(f"bot.py needs a role with key {k!r}")
    if layout.SECURITY["verification_level"] not in VERIFICATION:
        errors.append("SECURITY.verification_level must be low, medium, high or highest")
    return errors


def who_sees(perms: dict) -> str:
    if perms.get("@everyone", {}).get("view_channel"):
        return "everyone, incl. unverified"
    names = {r["key"]: r["name"] for r in layout.ROLES}
    roles = [names[k] for k, v in perms.items() if k != "@everyone" and v.get("view_channel")]
    return ", ".join(roles) or "Founder/Owner only"


def dry_run() -> None:
    print("Roles (highest first):")
    for r in layout.ROLES:
        tag = "Administrator" if r.get("admin") else f"{len(r.get('perms', []))} permissions"
        print(f"  @{r['name']:<12} {tag}")
    for cat in layout.CATEGORIES:
        print(f"\n{cat['name']}   [{who_sees(cat['perms'])}]")
        if not cat["channels"]:
            print("  (ticket channels are created here by bot.py)")
        for ch in cat["channels"]:
            kind = ch.get("type", "text")
            extra = f"   [{who_sees(ch['perms'])}]" if "perms" in ch else ""
            post = "  + post" if ch["key"] in layout.POSTS else ""
            limit = f" (max {ch['user_limit']})" if ch.get("user_limit") else ""
            print(f"  {kind:6} {channel_name(ch)}{limit}{extra}{post}")
    print("\nAutoMod: slurs/sexual-content presets, spam, mention raids, scam phrases, invite links")
    print(f"Verification level: {layout.SECURITY['verification_level']}")


# ── Setup ─────────────────────────────────────────────────────────────────────

class Setup:
    def __init__(self, guild: discord.Guild, update: bool, icon: Path | None, rename: bool):
        self.g = guild
        self.update = update
        self.icon = icon
        self.rename = rename
        self.community = "COMMUNITY" in guild.features
        self.state = load_state()
        if self.state.get("guild_id") not in (None, guild.id):
            sys.exit(f"state.json belongs to another server ({self.state['guild_id']}). Move it away first.")
        self.state["guild_id"] = guild.id
        self.roles: dict[str, discord.Role] = {}
        self.channels: dict[str, discord.abc.GuildChannel] = {}
        self.changes = 0
        self.warnings: list[str] = []

    def log(self, msg: str) -> None:
        print(msg, flush=True)

    def warn(self, msg: str) -> None:
        self.warnings.append(msg)
        self.log("! " + msg)

    async def run(self) -> None:
        if not self.g.me.guild_permissions.administrator:
            sys.exit("Setup needs Administrator. Invite the bot with the setup link from --invite.")
        self.log(f"Setting up {self.g.name} ({'Community' if self.community else 'not Community yet'})")
        await self.make_roles()
        await self.make_channels()
        await self.server_settings()
        await self.automod()
        await self.posts()
        save_state(self.state)
        self.log(f"\nDone: {self.changes} changes. IDs saved to state.json for bot.py.")
        print("\nFinish by hand (Discord only lets the server owner do these):")
        print("  1. Server Settings > Safety Setup: turn on 'Require 2FA for moderator actions'.")
        print("  2. Server Settings > Roles: drag the bot's own role to sit just below Admin.")
        print("  3. Open the runtime link from --invite to take Administrator away from the bot.")
        if not self.community:
            print("  4. Enable Community, then run again with --update for forums and announcement channels.")
        if self.warnings:
            print("\nWarnings:\n  " + "\n  ".join(self.warnings))

    # Roles ───────────────────────────────────────────────────────────────────
    async def make_roles(self) -> None:
        by_id = {r.id: r for r in self.g.roles}
        by_name = {r.name: r for r in self.g.roles}
        for spec in layout.ROLES:
            perms = discord.Permissions(administrator=True) if spec.get("admin") else permissions(spec.get("perms", []))
            kwargs = dict(permissions=perms, colour=discord.Colour(spec.get("color", 0)),
                          hoist=spec.get("hoist", False), mentionable=False)
            role = by_id.get(self.state["roles"].get(spec["key"])) or by_name.get(spec["name"])
            if role is None:
                role = await self.g.create_role(name=spec["name"], reason=REASON, **kwargs)
                self.log(f"+ role @{role.name}")
                self.changes += 1
            elif self.update:
                if role.managed or role >= self.g.me.top_role:
                    self.warn(f"left @{role.name} alone: it's above the bot's role (expected once you've moved the bot)")
                else:
                    await role.edit(name=spec["name"], reason=REASON, **kwargs)
                    self.log(f"~ role @{role.name}")
                    self.changes += 1
            self.roles[spec["key"]] = role
            self.state["roles"][spec["key"]] = role.id
        self.roles["@everyone"] = self.g.default_role

        # Strip @everyone at server level: unverified users get nothing outside the gate.
        everyone = permissions(layout.EVERYONE_PERMS)
        if self.g.default_role.permissions != everyone:
            await self.g.default_role.edit(permissions=everyone, reason=REASON)
            self.log("~ @everyone stripped (unverified users only see the gate)")
            self.changes += 1

        # Order the roles directly under the bot's own role, highest first.
        top = self.g.me.top_role
        wanted = [self.roles[s["key"]] for s in layout.ROLES]
        if all(r < top for r in wanted):
            positions = {r: top.position - 1 - i for i, r in enumerate(wanted)}
            if all(p > 0 for p in positions.values()) and any(r.position != p for r, p in positions.items()):
                await self.g.edit_role_positions(positions, reason=REASON)
                self.log("~ role order")

    def overwrites(self, spec: dict) -> dict:
        out = {self.roles[k]: discord.PermissionOverwrite(**v) for k, v in spec.items()}
        out[self.g.me] = discord.PermissionOverwrite(**BOT_OVERWRITE)
        return out

    # Channels ────────────────────────────────────────────────────────────────
    def find(self, key: str, name: str, kinds: tuple, store: str):
        ch = self.g.get_channel(self.state[store].get(key) or 0)
        if isinstance(ch, kinds):
            return ch
        return next((c for c in self.g.channels if c.name == name and isinstance(c, kinds)), None)

    async def make_channels(self) -> None:
        for pos, spec in enumerate(layout.CATEGORIES):
            ow = self.overwrites(spec["perms"])
            cat = self.find(spec["key"], spec["name"], (discord.CategoryChannel,), "categories")
            if cat is None:
                cat = await self.g.create_category(spec["name"], overwrites=ow, position=pos, reason=REASON)
                self.log(f"+ category {cat.name}")
                self.changes += 1
            elif self.update:
                await cat.edit(name=spec["name"], overwrites=ow, position=pos, reason=REASON)
                self.log(f"~ category {cat.name}")
            self.state["categories"][spec["key"]] = cat.id
            for i, ch in enumerate(spec["channels"]):
                await self.make_channel(cat, spec, ch, i)

    async def make_channel(self, cat, cat_spec, spec, position) -> None:
        kind = spec.get("type", "text")
        if kind in ("news", "forum") and not self.community:
            kind = "text"
        name = channel_name(spec)
        topic = spec.get("topic", "")
        ow = self.overwrites(spec.get("perms", cat_spec["perms"]))
        kinds = {"voice": (discord.VoiceChannel,), "forum": (discord.ForumChannel, discord.TextChannel)}.get(
            kind, (discord.TextChannel,))
        ch = self.find(spec["key"], name, kinds, "channels")
        if isinstance(ch, discord.TextChannel) and kind == "forum":
            self.warn(f"{name} is a text channel; delete it and run --update to recreate it as a forum")
        common = dict(category=cat, overwrites=ow, position=position, reason=REASON)

        if ch is None:
            if kind == "voice":
                ch = await self.g.create_voice_channel(name, user_limit=spec.get("user_limit", 0), **common)
            elif kind == "forum":
                ch = await self.g.create_forum(
                    name, topic=topic, slowmode_delay=spec.get("slowmode", 0),
                    available_tags=[discord.ForumTag(name=t) for t in spec.get("tags", [])], **common)
            else:
                ch = await self.g.create_text_channel(name, topic=topic, news=kind == "news",
                                                      slowmode_delay=spec.get("slowmode", 0), **common)
            self.log(f"+ {kind} {name}")
            self.changes += 1
        elif self.update:
            edit = dict(name=name, **common)
            if isinstance(ch, discord.VoiceChannel):
                edit["user_limit"] = spec.get("user_limit", 0)
            elif isinstance(ch, discord.ForumChannel):
                have = {t.name for t in ch.available_tags}
                tags = [*ch.available_tags, *(discord.ForumTag(name=t) for t in spec.get("tags", []) if t not in have)]
                edit.update(topic=topic, available_tags=tags[:20], slowmode_delay=spec.get("slowmode", 0))
            else:
                edit.update(topic=topic, slowmode_delay=spec.get("slowmode", 0))
                if kind == "news" and not ch.is_news():
                    edit["type"] = discord.ChannelType.news
            await ch.edit(**edit)
            self.log(f"~ {name}")
            self.changes += 1
        self.channels[spec["key"]] = ch
        self.state["channels"][spec["key"]] = ch.id

    # Server-wide settings ────────────────────────────────────────────────────
    async def server_settings(self) -> None:
        edit = dict(
            verification_level=VERIFICATION[layout.SECURITY["verification_level"]],
            explicit_content_filter=discord.ContentFilter.all_members,
            default_notifications=discord.NotificationLevel.only_mentions,
            system_channel=self.channels.get(layout.SYSTEM_CHANNEL),
            afk_channel=self.channels.get(layout.AFK_CHANNEL),
            afk_timeout=300,
        )
        if self.community:
            edit["rules_channel"] = self.channels.get(layout.RULES_CHANNEL)
            edit["public_updates_channel"] = self.channels.get(layout.UPDATES_CHANNEL)
        if self.rename:
            edit["name"] = layout.CLIENT["name"]
        if self.icon:
            edit["icon"] = self.icon.read_bytes()
        await self.g.edit(reason=REASON, **{k: v for k, v in edit.items() if v is not None})
        self.log(f"~ server: verification {layout.SECURITY['verification_level']}, media filter, "
                 "mentions-only notifications" + (", name" if self.rename else "") + (", icon" if self.icon else ""))

    async def automod(self) -> None:
        alert = self.channels.get(layout.AUTOMOD_ALERT_CHANNEL)
        staff = [self.roles[k] for k in layout.STAFF]
        short = layout.CLIENT["short"]
        T = discord.AutoModRuleTriggerType
        block = discord.AutoModRuleAction(custom_message="Blocked by AutoMod. Open a ticket if this was a mistake.")
        notify = [discord.AutoModRuleAction(channel_id=alert.id)] if alert else []
        timeout = discord.AutoModRuleAction(duration=timedelta(minutes=10))
        presets = discord.AutoModPresets.none()
        presets.slurs = True
        presets.sexual_content = True
        rules = [
            (f"{short}: slurs and sexual content",
             discord.AutoModTrigger(type=T.keyword_preset, presets=presets), [block, *notify], []),
            (f"{short}: spam", discord.AutoModTrigger(type=T.spam), [block, *notify], staff),
            (f"{short}: mention raids",
             discord.AutoModTrigger(type=T.mention_spam, mention_limit=5, mention_raid_protection=True),
             [block, *notify, timeout], staff),
            (f"{short}: scams and grabbers",
             discord.AutoModTrigger(type=T.keyword, keyword_filter=layout.SCAM_KEYWORDS),
             [block, *notify, timeout], staff),
            (f"{short}: invite links",
             discord.AutoModTrigger(type=T.keyword, regex_patterns=layout.INVITE_REGEX),
             [block, *notify], staff),
        ]
        try:
            existing = {r.name: r for r in await self.g.fetch_automod_rules()}
        except discord.HTTPException as e:
            self.warn(f"skipped AutoMod: {e}")
            return
        for name, trigger, actions, exempt in rules:
            try:
                if name not in existing:
                    await self.g.create_automod_rule(
                        name=name, event_type=discord.AutoModRuleEventType.message_send, trigger=trigger,
                        actions=actions, enabled=True, exempt_roles=exempt, reason=REASON)
                    self.log(f"+ automod {name}")
                    self.changes += 1
                elif self.update:
                    await existing[name].edit(trigger=trigger, actions=actions, exempt_roles=exempt, reason=REASON)
                    self.log(f"~ automod {name}")
            except discord.HTTPException as e:
                self.warn(f"AutoMod rule {name!r} failed: {e}")

    # Starter posts ───────────────────────────────────────────────────────────
    def mentions(self, text: str) -> str:
        def sub(m):
            ch = self.channels.get(m.group(1))
            return ch.mention if ch else m.group(0)
        return re.sub(r"#([a-z0-9-]+)", sub, text)

    def embed(self, key: str, i: int, spec: dict) -> discord.Embed:
        fmt = lambda s: self.mentions(s.format(**layout.CLIENT))
        e = discord.Embed(title=fmt(spec["title"]), description=fmt(spec["description"]), colour=layout.CLIENT["color"])
        for name, value, inline in spec.get("fields", []):
            e.add_field(name=fmt(name), value=fmt(value), inline=inline)
        e.set_footer(text=f"{layout.CLIENT['name']} · {MARKER}{key}:{i}")
        return e

    async def posts(self) -> None:
        for key, specs in layout.POSTS.items():
            ch = self.channels.get(key)
            if not isinstance(ch, discord.TextChannel):
                continue
            mine = {}
            async for msg in ch.history(limit=50):
                if msg.author == self.g.me and msg.embeds and MARKER in (msg.embeds[0].footer.text or ""):
                    mine[msg.embeds[0].footer.text.split(MARKER, 1)[1]] = msg
            for i, spec in enumerate(specs):
                e = self.embed(key, i, spec)
                old = mine.get(f"{key}:{i}")
                if old is None:
                    await ch.send(embed=e)
                    self.log(f"+ post in {ch.name}")
                    self.changes += 1
                elif self.update:
                    await old.edit(embed=e)
                    self.log(f"~ post in {ch.name}")


# ── Entry point ───────────────────────────────────────────────────────────────

def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("guild_id", nargs="?", type=int, help="server ID (right-click the server > Copy Server ID)")
    ap.add_argument("--update", action="store_true", help="also re-sync existing roles, channels and posts")
    ap.add_argument("--name", action="store_true", help="rename the server to CLIENT['name']")
    ap.add_argument("--icon", metavar="IMAGE", default=layout.ICON_PATH or None, help="server icon image")
    ap.add_argument("--dry-run", action="store_true", help="print the plan and exit")
    ap.add_argument("--invite", metavar="APP_ID", help="print the bot invite links and exit")
    args = ap.parse_args()

    errors = check_layout()
    if errors:
        sys.exit("layout.py has problems:\n  " + "\n  ".join(errors))
    if args.invite:
        base = (f"https://discord.com/oauth2/authorize?client_id={args.invite}"
                "&scope=bot+applications.commands&permissions=")
        print("1. Setup link (Administrator, only while setup_server.py runs):\n   " + base + "8")
        print("2. Runtime link (open after setup to take Administrator away):\n   "
              + base + str(permissions(RUNTIME_PERMS).value))
        return
    if args.dry_run:
        dry_run()
        return
    if not args.guild_id:
        ap.error("give your server ID, or use --dry-run")
    icon = Path(args.icon).expanduser() if args.icon else None
    if icon and not icon.is_file():
        sys.exit(f"Icon not found: {icon}")
    token = os.environ.get("DISCORD_TOKEN")
    if not token:
        sys.exit("Set DISCORD_TOKEN to your bot's token first (Developer Portal > Bot > Reset Token).")

    client = discord.Client(intents=discord.Intents(guilds=True))

    @client.event
    async def on_ready():
        try:
            guild = client.get_guild(args.guild_id)
            if guild is None:
                print(f"The bot isn't in server {args.guild_id}. Invite it with the setup link from --invite.")
                return
            await Setup(guild, args.update, icon, args.name).run()
        except discord.Forbidden as e:
            print(f"Discord refused a change: {e}\nMake sure the bot has Administrator and its role is at the top.")
        finally:
            await client.close()

    try:
        client.run(token, log_handler=None)
    except discord.LoginFailure:
        sys.exit("That token was rejected. Reset it in the Developer Portal and try again.")


if __name__ == "__main__":
    main()
