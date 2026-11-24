#!/usr/bin/env python3
"""Tidy and lock down The Hajin Exclusion Zone (MW4 DMZ) server. Safe to re-run.

    DISCORD_TOKEN=... python secure_server.py          # apply
    DISCORD_TOKEN=... python secure_server.py --dry    # show what it would do

What it does:
  * Verification level Medium (verified email + account older than 5 minutes) and
    explicit-media filter for all members.
  * Gives Mods access to the Staff Area (they were denied View Channel) and read-only
    access to the Logs and Tickets categories.
  * Removes "Mention @everyone" from members in #report-issues.
  * Creates AutoMod rules (spam, mention raids, slurs/sexual content, scam phrases,
    invite links) that alert in #server-logs. Staff roles are exempt.
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request

API = "https://discord.com/api/v10"
DRY = "--dry" in sys.argv

GUILD = "1564453181558235267"
ROLE = {
    "everyone": GUILD,
    # the rest are looked up by name at runtime (ROLE_NAMES)
}
ROLE_NAMES = {"member": "✅️Member", "mod": "📒Mod", "developer": "⭐Developer",
              "admin": "📕Admin", "owner": "👑Owner"}
CH = {
    "staff_cat": "1564453182640357454",
    "logs_cat": "1564453182850203659",
    "tickets_cat": "1564453182850203662",
    "report_issues": "1564453182640357453",
    "server_logs": "1564453182850203660",
}

P = {n: 1 << b for n, b in dict(
    VIEW=10, SEND=11, EMBED=14, ATTACH=15, HISTORY=16, MENTION_EVERYONE=17, CONNECT=20,
    SPEAK=21, ADD_REACTIONS=6, SEND_IN_THREADS=38, STREAM=9, VAD=25).items()}

STAFF_TALK = P["VIEW"] | P["SEND"] | P["EMBED"] | P["ATTACH"] | P["HISTORY"] | P["ADD_REACTIONS"] \
    | P["SEND_IN_THREADS"] | P["CONNECT"] | P["SPEAK"] | P["STREAM"] | P["VAD"]
READ_ONLY = P["VIEW"] | P["HISTORY"]


def call(method: str, path: str, body=None, reason: str = "MW4 DMZ server tidy-up"):
    if DRY and method != "GET":
        print(f"  [dry] {method} {path} {json.dumps(body)[:160] if body else ''}")
        return {}
    token = os.environ.get("DISCORD_TOKEN", "proxy-injected")
    headers = {"Authorization": f"Bot {token}", "User-Agent": "DiscordBot (mw4-dmz, 1.0)",
               "X-Audit-Log-Reason": reason}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    for _ in range(5):
        req = urllib.request.Request(API + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req) as r:
                raw = r.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            if e.code == 429:
                time.sleep(json.loads(e.read()).get("retry_after", 1) + 0.2)
                continue
            raise SystemExit(f"{method} {path} -> {e.code} {e.read().decode()[:300]}")
    raise SystemExit("rate limited too many times")


def overwrite(channel: str, role: str, allow: int, deny: int, why: str):
    print(f"- {why}")
    call("PUT", f"/channels/{channel}/permissions/{role}",
         {"type": 0, "allow": str(allow), "deny": str(deny)}, why)


def main():
    roles = {r["name"]: r["id"] for r in call("GET", f"/guilds/{GUILD}/roles")}
    for key, name in ROLE_NAMES.items():
        ROLE[key] = roles[name]
    staff = [ROLE[k] for k in ("mod", "developer", "admin", "owner")]

    print("- Verification level Medium, explicit-media filter for everyone")
    call("PATCH", f"/guilds/{GUILD}", {"verification_level": 2, "explicit_content_filter": 2})

    # Staff Area: category and every channel in it (some have extra overwrites, so per channel).
    channels = call("GET", f"/guilds/{GUILD}/channels")
    staff_area = [CH["staff_cat"]] + [c["id"] for c in channels if c.get("parent_id") == CH["staff_cat"]]
    for cid in staff_area:
        overwrite(cid, ROLE["mod"], STAFF_TALK, 0, "Let Mods see and use the Staff Area")

    for cat in ("logs_cat", "tickets_cat"):
        ids = [CH[cat]] + [c["id"] for c in channels if c.get("parent_id") == CH[cat]]
        for cid in ids:
            overwrite(cid, ROLE["mod"], READ_ONLY | (STAFF_TALK if cat == "tickets_cat" else 0),
                      0 if cat == "tickets_cat" else P["SEND"],
                      f"Mods {'can work in' if cat == 'tickets_cat' else 'can read'} {cat.split('_')[0]}")

    rep = next(c for c in channels if c["id"] == CH["report_issues"])
    cur = next((o for o in rep["permission_overwrites"] if o["id"] == ROLE["member"]), None)
    if cur and int(cur["allow"]) & P["MENTION_EVERYONE"]:
        overwrite(CH["report_issues"], ROLE["member"], int(cur["allow"]) & ~P["MENTION_EVERYONE"],
                  int(cur["deny"]), "Members can't ping @everyone from #report-issues")

    # AutoMod
    existing = {r["name"] for r in call("GET", f"/guilds/{GUILD}/auto-moderation/rules")}
    alert = {"type": 2, "metadata": {"channel_id": CH["server_logs"]}}
    block = lambda msg: {"type": 1, "metadata": {"custom_message": msg}}
    timeout = lambda s: {"type": 3, "metadata": {"duration_seconds": s}}
    rules = [
        {"name": "Spam", "trigger_type": 3, "actions": [block("Message blocked as spam."), alert]},
        {"name": "Mention raid", "trigger_type": 5,
         "trigger_metadata": {"mention_total_limit": 6, "mention_raid_protection_enabled": True},
         "actions": [block("Too many mentions."), alert, timeout(600)]},
        {"name": "Slurs and sexual content", "trigger_type": 4,
         "trigger_metadata": {"presets": [2, 3]}, "actions": [block("That language isn't allowed here."), alert]},
        {"name": "Scams and token grabbers", "trigger_type": 1,
         "trigger_metadata": {"keyword_filter": [
             "free nitro", "nitro giveaway", "steam gift", "*discord-gift*", "*discordgift*", "*dlscord*",
             "*discrod*", "*steamcommunlty*", "*stearncommunity*", "free cod points", "free cp generator",
             "cod points generator", "unlock tool", "unlock all tool", "*cheat* dm me", "aimbot dm",
             "selling bot lobbies", "@everyone http*"]},
         "actions": [block("Looks like a scam or cheat ad, so it was blocked."), alert, timeout(3600)]},
        {"name": "Invite links", "trigger_type": 1,
         "trigger_metadata": {"regex_patterns": [
             r"(discord\.(gg|io|me|li)|discord(app)?\.com/invite)/[a-zA-Z0-9-]+"]},
         "actions": [block("Server invites aren't allowed. Ask a mod if you want to share one."), alert]},
    ]
    for r in rules:
        if r["name"] in existing:
            print(f"- AutoMod '{r['name']}' already exists, skipping")
            continue
        print(f"- AutoMod rule: {r['name']}")
        call("POST", f"/guilds/{GUILD}/auto-moderation/rules",
             {"event_type": 1, "enabled": True, "exempt_roles": staff, **r})
    print("Done.")


if __name__ == "__main__":
    main()
