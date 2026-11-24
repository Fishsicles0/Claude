#!/usr/bin/env python3
"""Register the bot's slash commands in the server, and point Discord at the worker.

    DISCORD_TOKEN=... python register.py commands
    DISCORD_TOKEN=... python register.py endpoint https://mw4-dmz-bot.<you>.workers.dev
"""

from __future__ import annotations

import sys

from secure_server import GUILD, call

APP = "1574759457294913654"
MOD = str(1 << 40)            # Moderate Members: hides staff commands from members
BAN = str(1 << 2)
ADMIN = str(1 << 5)           # Manage Server

USER, STR, INT, BOOL, CHANNEL, SUB = 6, 3, 4, 5, 7, 1


def opt(type_, name, description, required=False, **kw):
    return {"type": type_, "name": name, "description": description, "required": required, **kw}


def choices(*pairs):
    return [{"name": n, "value": v} for n, v in pairs]


GUIDE_TYPES = choices(("Build / loadout", "build"), ("Blueprint", "blueprint"), ("Intel / tip", "intel"))
DURATIONS = choices(("5 minutes", "5m"), ("10 minutes", "10m"), ("30 minutes", "30m"), ("1 hour", "1h"),
                    ("6 hours", "6h"), ("12 hours", "12h"), ("1 day", "1d"), ("3 days", "3d"), ("1 week", "1w"),
                    ("Remove timeout", "off"))

COMMANDS = [
    {"name": "lfg", "description": "Post a squad card in #find-a-squad", "options": [
        opt(STR, "size", "How many players in total", True,
            choices=choices(("Duo (2)", "duo"), ("Trio (3)", "trio"), ("Squad (4)", "squad"))),
        opt(STR, "activity", "What you want to do", choices=choices(
            ("Missions / contracts", "missions"), ("Looting / farming", "loot"), ("PvP / hunting squads", "pvp"),
            ("Bosses / strongholds", "bosses"), ("Quick exfil runs", "exfil"), ("Anything goes", "anything"))),
        opt(STR, "platform", "Your platform", choices=choices(
            ("Any / crossplay", "any"), ("PC", "pc"), ("PlayStation", "playstation"), ("Xbox", "xbox"))),
        opt(BOOL, "mic", "Is a mic required?"),
        opt(STR, "note", "Anything else (e.g. your Activision ID, map, goal)", max_length=300),
    ]},
    *[{"name": kind, "description": desc, "options": [
        opt(STR, "name", "Which one (leave empty to list them all)", autocomplete=True, max_length=80)]}
      for kind, desc in (("build", "Show a saved loadout"), ("blueprint", "Show a saved blueprint"),
                         ("intel", "Show a saved DMZ tip"))],
    {"name": "guide-add", "description": "Save or update a build, blueprint or tip", "default_member_permissions": MOD,
     "options": [opt(STR, "type", "What kind", True, choices=GUIDE_TYPES)]},
    {"name": "guide-remove", "description": "Delete a saved build, blueprint or tip", "default_member_permissions": MOD,
     "options": [opt(STR, "type", "What kind", True, choices=GUIDE_TYPES),
                 opt(STR, "name", "Which one", True, autocomplete=True)]},
    {"name": "ticket", "description": "Open a private ticket with staff"},
    {"name": "ticket-add", "description": "Add someone to this ticket", "default_member_permissions": MOD,
     "options": [opt(USER, "user", "Who to add", True)]},
    {"name": "ticket-remove", "description": "Remove someone from this ticket", "default_member_permissions": MOD,
     "options": [opt(USER, "user", "Who to remove", True)]},
    {"name": "warn", "description": "Warn a member (DMs them and logs it)", "default_member_permissions": MOD,
     "options": [opt(USER, "user", "Who", True), opt(STR, "reason", "Why", True, max_length=500)]},
    {"name": "warnings", "description": "List a member's warnings", "default_member_permissions": MOD,
     "options": [opt(USER, "user", "Who", True), opt(BOOL, "clear", "Clear them all (Admins only)")]},
    {"name": "timeout", "description": "Time out a member, or remove a timeout", "default_member_permissions": MOD,
     "options": [opt(USER, "user", "Who", True), opt(STR, "duration", "How long", True, choices=DURATIONS),
                 opt(STR, "reason", "Why", max_length=500)]},
    {"name": "kick", "description": "Kick a member", "default_member_permissions": MOD,
     "options": [opt(USER, "user", "Who", True), opt(STR, "reason", "Why", max_length=500)]},
    {"name": "ban", "description": "Ban a member", "default_member_permissions": BAN,
     "options": [opt(USER, "user", "Who", True), opt(STR, "reason", "Why", max_length=500),
                 opt(STR, "delete_messages", "Delete their recent messages", choices=choices(
                     ("Don't delete", "0"), ("Last hour", "3600"), ("Last day", "86400"), ("Last 7 days", "604800")))]},
    {"name": "unban", "description": "Unban someone by user ID", "default_member_permissions": BAN,
     "options": [opt(STR, "user_id", "Their user ID", True), opt(STR, "reason", "Why", max_length=500)]},
    {"name": "purge", "description": "Delete recent messages in this channel", "default_member_permissions": MOD,
     "options": [opt(INT, "count", "How many (1-100)", True, min_value=1, max_value=100),
                 opt(USER, "user", "Only messages from this person")]},
    {"name": "slowmode", "description": "Set slowmode", "default_member_permissions": MOD,
     "options": [opt(INT, "seconds", "Delay between messages (0 turns it off)", True, min_value=0, max_value=21600),
                 opt(CHANNEL, "channel", "Which channel (default: this one)", channel_types=[0])]},
    {"name": "userinfo", "description": "Account age, join date, roles and warnings", "default_member_permissions": MOD,
     "options": [opt(USER, "user", "Who", True)]},
    {"name": "panel", "description": "Post the ticket panel or the squad-finder intro", "default_member_permissions": ADMIN,
     "options": [opt(STR, "which", "Which panel", True, choices=choices(
         ("Ticket panel (#report-issues)", "tickets"), ("Squad finder intro (#find-a-squad)", "lfg")))]},
    {"name": "help", "description": "What this bot can do"},
    {"type": 3, "name": "Report message"},
]


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in ("commands", "endpoint"):
        sys.exit(__doc__)
    if sys.argv[1] == "commands":
        for c in COMMANDS:
            c.setdefault("type", 1)
            c["contexts"] = [0]
        done = call("PUT", f"/applications/{APP}/guilds/{GUILD}/commands", COMMANDS)
        print(f"Registered {len(done)} commands:", ", ".join(c["name"] for c in done))
    else:
        url = sys.argv[2].rstrip("/") + "/interactions"
        call("PATCH", "/applications/@me", {"interactions_endpoint_url": url})
        print("Interactions endpoint set to", url)


if __name__ == "__main__":
    main()
