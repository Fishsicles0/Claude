#!/usr/bin/env python3
"""Keep the Discord in step with evadeclient.com. Safe to run as often as you like.

    python sync_site.py            # update anything that changed
    python sync_site.py --dry-run  # say what would change, touch nothing

It compares every bot post (download, changelog, FAQ, guides...) with what it should say given
the live version.json and changelog, edits only the ones that differ, and posts an announcement
for each new release. It keeps no state of its own: what's already in Discord is the record.
Prints one line per change, or "No changes." Exits 1 if the website or Discord can't be reached.
"""

from __future__ import annotations

import re
import sys

import layout
import site_content
from common import load_state
from discord_api import MARKER, Discord

NAME = layout.CLIENT["name"]


def norm_embed(e: dict) -> tuple:
    """The parts of an embed we control, for comparing what's posted with what should be."""
    fields = tuple((f.get("name", ""), f.get("value", ""), bool(f.get("inline"))) for f in e.get("fields") or [])
    return (e.get("title", ""), e.get("description", ""), fields, (e.get("footer") or {}).get("text", ""))


def vkey(version: str) -> tuple:
    return tuple(int(x) for x in re.findall(r"\d+", version)[:3])


class Sync:
    def __init__(self, dry: bool):
        self.api = Discord("proxy-injected")
        self.dry = dry
        self.state = load_state()
        if not self.state.get("guild_id"):
            sys.exit("state.json is missing; run setup_rest.py first.")
        self.channels = {k: str(v) for k, v in self.state["channels"].items()}
        self.me = self.api("GET", "/users/@me")["id"]
        self.changes: list[str] = []

    def mention(self, text: str) -> str:
        return re.sub(r"#([a-z0-9-]+)", lambda m: f"<#{self.channels[m[1]]}>" if m[1] in self.channels else m[0], text)

    def embed(self, key: str, i: int, spec: dict) -> dict:
        # Layout text has {placeholders}; text from the website is used as-is (it may contain braces).
        fmt = (lambda s: self.mention(s)) if spec.get("raw") else (lambda s: self.mention(s.format(**layout.CLIENT)))
        return {"title": fmt(spec["title"]), "description": fmt(spec["description"]), "color": layout.CLIENT["color"],
                "fields": [{"name": fmt(n), "value": fmt(v), "inline": inl} for n, v, inl in spec.get("fields", [])],
                "footer": {"text": f"{NAME} · {MARKER}{key}:{i}"}}

    def bot_posts(self, channel_id: str) -> dict:
        out = {}
        for m in self.api("GET", f"/channels/{channel_id}/messages?limit=100"):
            foot = ((m.get("embeds") or [{}])[0].get("footer") or {}).get("text", "")
            if m["author"]["id"] == self.me and MARKER in foot:
                out[foot.split(MARKER, 1)[1]] = m
        return out

    def write(self, what: str, method: str, path: str, body=None):
        self.changes.append(what)
        if not self.dry:
            return self.api(method, path, body)

    def sync_posts(self, posts: dict) -> None:
        for key, specs in posts.items():
            ch = self.channels.get(key)
            if not ch:
                continue
            mine = self.bot_posts(ch)
            for i, spec in enumerate(specs):
                want = self.embed(key, i, spec)
                old = mine.get(f"{key}:{i}")
                if old is None:
                    msg = self.write(f"posted #{key} ({spec['title'][:40]})", "POST", f"/channels/{ch}/messages", {"embeds": [want]})
                    if msg and i == 0:
                        self.api("PUT", f"/channels/{ch}/pins/{msg['id']}")
                elif norm_embed(old["embeds"][0]) != norm_embed(want):
                    self.write(f"updated #{key} ({spec['title'][:40]})", "PATCH", f"/channels/{ch}/messages/{old['id']}", {"embeds": [want]})

    def announce(self, entries: list[dict]) -> None:
        ch = self.channels.get("announcements")
        if not ch:
            return
        posted = set(re.findall(rf"{MARKER}release:(\S+)",
                                " ".join(((m.get("embeds") or [{}])[0].get("footer") or {}).get("text", "")
                                         for m in self.api("GET", f"/channels/{ch}/messages?limit=100")
                                         if m["author"]["id"] == self.me)))
        newest_posted = max((vkey(v) for v in posted), default=None)
        if newest_posted is None:
            todo = entries[:1]   # first run: only announce the latest release, not the whole history
        else:
            todo = [e for e in entries[:5] if e["id"] not in posted and vkey(e["version"]) > newest_posted]
        for e in reversed(todo):
            spec = site_content.announcement(e)
            embed = self.embed("announcements", 0, spec)
            embed["footer"]["text"] = f"{NAME} · {MARKER}release:{e['id']}"
            embed["url"] = f"{site_content.SITE}/changelog#{e['id']}"
            body = {"embeds": [embed], "components": [{"type": 1, "components": [
                {"type": 2, "style": 5, "label": "Download", "emoji": {"name": "⬇️"}, "url": f"{site_content.SITE}/download"},
                {"type": 2, "style": 5, "label": "Full changelog", "emoji": {"name": "📝"},
                 "url": f"{site_content.SITE}/changelog#{e['id']}"}]}]}
            msg = self.write(f"announced {e['version']}", "POST", f"/channels/{ch}/messages", body)
            if msg:
                try:  # publish to following servers when #announcements is an announcement channel
                    self.api("POST", f"/channels/{ch}/messages/{msg['id']}/crosspost")
                except RuntimeError:
                    pass


def main() -> None:
    dry = "--dry-run" in sys.argv
    try:
        posts, v, entries = site_content.live_posts()
    except Exception as e:  # noqa: BLE001 - any network/parse failure means "try next hour"
        print(f"Couldn't read evadeclient.com: {e}")
        sys.exit(1)
    s = Sync(dry)
    s.sync_posts(posts)
    s.announce(entries)
    print(f"Website: v{v['version']} ({v.get('date', '?')}), latest changelog entry {entries[0]['version']}")
    if s.changes:
        print(("Would change" if dry else "Changed") + f" {len(s.changes)}:\n  " + "\n  ".join(s.changes))
    else:
        print("No changes.")


if __name__ == "__main__":
    main()
