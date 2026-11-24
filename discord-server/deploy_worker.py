#!/usr/bin/env python3
"""Build and wire up the serverless bot (worker/bot.js) for Cloudflare Pages.

    python deploy_worker.py build                 # writes worker/dist/_worker.js
    python deploy_worker.py commands              # registers the slash commands in your server
    python deploy_worker.py endpoint URL          # points Discord's interactions at URL/interactions
    python deploy_worker.py panels                # posts the verify and ticket panels

Uses DISCORD_TOKEN like setup_rest.py ("proxy-injected" when a proxy adds it).
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import layout
from common import HERE, load_state
from discord_api import Discord

WORKER = HERE / "worker"
DIST = WORKER / ("dist" if os.environ.get("SERVER", "evade") == "evade" else f"dist-{os.environ['SERVER']}")

MOD_PERMS = str(1 << 40)          # moderate_members: hides staff commands from members
MANAGE_MESSAGES = str(1 << 13)
MANAGE_GUILD = str(1 << 5)


def api() -> Discord:
    return Discord(os.environ.get("DISCORD_TOKEN", "proxy-injected"))


def mention(text: str, state: dict) -> str:
    """#channel-key → clickable channel mention."""
    import re
    return re.sub(r"#([a-z0-9-]+)", lambda m: f"<#{state['channels'][m[1]]}>" if m[1] in state["channels"] else m[0], text)


def config() -> dict:
    state = load_state()
    if not state.get("guild_id"):
        sys.exit("state.json is missing. Run setup_rest.py or setup_server.py first.")
    app = api()("GET", "/applications/@me")
    s = lambda d: {k: str(v) for k, v in d.items()}
    return {
        "name": layout.CLIENT["name"], "color": layout.CLIENT["color"], "site": layout.CLIENT["site"],
        "icon": f"https://cdn.discordapp.com/app-icons/{app['id']}/{app['icon']}.png?size=128" if app.get("icon") else "",
        "app_id": app["id"], "public_key": app["verify_key"], "guild_id": str(state["guild_id"]),
        "roles": s(state["roles"]), "channels": s(state["channels"]), "categories": s(state["categories"]),
        "security": layout.SECURITY, "admins": layout.ADMINS, "ticket_staff": layout.TICKET_STAFF,
        "tickets": layout.TICKET_TYPES, "lockdown_categories": layout.LOCKDOWN_CATEGORIES,
        "welcome_lines": [mention(x, state) for x in layout.WELCOME_LINES],
        "welcome_channel": getattr(layout, "WELCOME_CHANNEL", "welcome"),
        "verify_approvers": getattr(layout, "VERIFY_APPROVERS", []),
        "help_lines": [mention(x, state) for x in layout.HELP_LINES],
        "legal_note": getattr(layout, "LEGAL_NOTE", ""),
    }


PAGE = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>{title} · {name}</title><style>
:root{{color-scheme:dark;--bg:#0c0809;--card:#151012;--text:#ece4e5;--muted:#b3a3a5;--accent:#ff4552;--line:#2a1f21}}
@media (prefers-color-scheme:light){{:root:not([data-theme=dark]){{color-scheme:light;--bg:#faf7f7;--card:#fff;--text:#1d1517;
--muted:#6b5c5e;--accent:#d62835;--line:#eadfe0}}}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);color:var(--text);font:16px/1.65 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}}
header{{border-bottom:1px solid var(--line);background:linear-gradient(115deg,rgba(255,69,82,.16),transparent 60%)}}
header div,main{{max-width:760px;margin:0 auto;padding:20px 16px}}header b{{font:italic 900 22px/1 system-ui;letter-spacing:-.5px}}
header b span{{color:var(--accent)}}header nav{{float:right;font-size:14px}}header nav a{{margin-left:14px}}
a{{color:var(--accent)}}h1{{font-size:32px;margin:8px 0 4px}}h2{{font-size:20px;margin:32px 0 8px}}
table{{width:100%;border-collapse:collapse;font-size:14px;display:block;overflow-x:auto}}th,td{{text-align:left;vertical-align:top;
padding:8px 10px;border-bottom:1px solid var(--line)}}th{{color:var(--muted);font-weight:600}}code{{background:var(--card);
padding:1px 5px;border-radius:4px}}li{{margin:4px 0}}footer{{color:var(--muted);font-size:13px;padding-bottom:40px}}
</style></head><body><header><div><b>EVADE <span>CLIENT</span></b><nav><a href="/terms">Terms</a><a href="/privacy">Privacy</a></nav></div></header>
<main>{body}<footer><p>© 2026 {name}</p></footer></main></body></html>"""


def legal_pages() -> dict:
    import markdown
    pages = {}
    for slug in ("terms", "privacy"):
        md = (WORKER / "legal" / f"{slug}.md").read_text()
        title = md.splitlines()[0].lstrip("# ").strip()
        body = markdown.markdown(md, extensions=["tables"])
        pages["/" + slug] = PAGE.format(title=title, name=layout.BOT_NAME, body=body)
    return pages


def build() -> Path:
    src = (WORKER / "bot.js").read_text()
    out = src.replace("/*CONFIG*/ {}", json.dumps(config(), indent=1, ensure_ascii=False), 1)
    pages = legal_pages() if getattr(layout, "LEGAL_PAGES", False) else {}
    out = out.replace("/*PAGES*/ {}", json.dumps(pages, ensure_ascii=False), 1)
    DIST.mkdir(parents=True, exist_ok=True)
    (DIST / "_worker.js").write_text(out)
    (DIST / "index.html").write_text("<!doctype html><title>bot</title><p>Bot is running.</p>")
    print(f"built {DIST / '_worker.js'}")
    return DIST / "_worker.js"


COMMANDS = [
    {"name": "verify", "description": "Verify a member manually (skips the account-age check)",
     "default_member_permissions": MOD_PERMS, "dm_permission": False,
     "options": [{"type": 6, "name": "member", "description": "Who to verify", "required": True}]},
    {"name": "lockdown", "description": "Stop members talking in one channel, or in all member channels",
     "default_member_permissions": MOD_PERMS, "dm_permission": False,
     "options": [{"type": 7, "name": "channel", "description": "Only this channel", "required": False}]},
    {"name": "unlock", "description": "Undo /lockdown", "default_member_permissions": MOD_PERMS, "dm_permission": False,
     "options": [{"type": 7, "name": "channel", "description": "Only this channel", "required": False}]},
    {"name": "raidmode", "description": "Pause or resume verification during a raid",
     "default_member_permissions": MOD_PERMS, "dm_permission": False,
     "options": [{"type": 3, "name": "mode", "description": "on or off", "required": True,
                  "choices": [{"name": "on", "value": "on"}, {"name": "off", "value": "off"}]}]},
    {"name": "purge", "description": "Delete recent messages in this channel",
     "default_member_permissions": MANAGE_MESSAGES, "dm_permission": False,
     "options": [{"type": 4, "name": "count", "description": "How many (1-100)", "required": True, "min_value": 1, "max_value": 100},
                 {"type": 6, "name": "user", "description": "Only this user's messages", "required": False}]},
    {"name": "ticket-add", "description": "Add someone to this ticket", "dm_permission": False,
     "options": [{"type": 6, "name": "member", "description": "Who to add", "required": True}]},
    {"name": "ticket-remove", "description": "Remove someone from this ticket", "dm_permission": False,
     "options": [{"type": 6, "name": "member", "description": "Who to remove", "required": True}]},
    {"name": "panels", "description": "Re-post the verify and ticket panels",
     "default_member_permissions": MANAGE_GUILD, "dm_permission": False},
    # Everyone
    {"name": "help", "description": "What this bot can do", "dm_permission": False},
    {"name": "ticket", "description": "Open a private ticket with staff", "dm_permission": False},
    {"type": 3, "name": "Report message", "dm_permission": False},
    {"type": 2, "name": "Report user", "dm_permission": False},
    # Mods
    {"name": "warn", "description": "Warn a member (they get a DM; it's logged)",
     "default_member_permissions": MOD_PERMS, "dm_permission": False,
     "options": [{"type": 6, "name": "member", "description": "Who to warn", "required": True},
                 {"type": 3, "name": "reason", "description": "Why", "required": True, "max_length": 500}]},
    {"name": "warnings", "description": "See (or clear) a member's warnings",
     "default_member_permissions": MOD_PERMS, "dm_permission": False,
     "options": [{"type": 6, "name": "member", "description": "Whose warnings", "required": True},
                 {"type": 5, "name": "clear", "description": "Clear them all (Admins only)", "required": False}]},
    {"name": "timeout", "description": "Time out a member, or remove a timeout",
     "default_member_permissions": MOD_PERMS, "dm_permission": False,
     "options": [{"type": 6, "name": "member", "description": "Who", "required": True},
                 {"type": 4, "name": "duration", "description": "How long (leave empty to remove a timeout)", "required": False,
                  "choices": [{"name": n, "value": v} for n, v in [("1 minute", 1), ("5 minutes", 5), ("10 minutes", 10),
                              ("1 hour", 60), ("6 hours", 360), ("1 day", 1440), ("1 week", 10080)]]},
                 {"type": 3, "name": "reason", "description": "Why", "required": False, "max_length": 500}]},
    {"name": "kick", "description": "Kick a member (they get a DM; it's logged)",
     "default_member_permissions": str(1 << 1), "dm_permission": False,
     "options": [{"type": 6, "name": "member", "description": "Who", "required": True},
                 {"type": 3, "name": "reason", "description": "Why", "required": True, "max_length": 500}]},
    {"name": "userinfo", "description": "Account age, join date, roles and warnings for a member",
     "default_member_permissions": MOD_PERMS, "dm_permission": False,
     "options": [{"type": 6, "name": "member", "description": "Who", "required": True}]},
    {"name": "slowmode", "description": "Set slowmode in this channel",
     "default_member_permissions": MANAGE_MESSAGES, "dm_permission": False,
     "options": [{"type": 4, "name": "seconds", "description": "Seconds between messages (0 = off)", "required": True,
                  "min_value": 0, "max_value": 21600}]},
    # Admins
    {"name": "ban", "description": "Ban someone (Admins only)",
     "default_member_permissions": str(1 << 2), "dm_permission": False,
     "options": [{"type": 6, "name": "member", "description": "Who", "required": True},
                 {"type": 3, "name": "reason", "description": "Why", "required": True, "max_length": 500},
                 {"type": 4, "name": "delete_days", "description": "Delete their messages from the last N days", "required": False,
                  "min_value": 0, "max_value": 7}]},
    {"name": "unban", "description": "Unban someone by user ID",
     "default_member_permissions": str(1 << 2), "dm_permission": False,
     "options": [{"type": 3, "name": "user_id", "description": "Their user ID", "required": True},
                 {"type": 3, "name": "reason", "description": "Why", "required": False}]},
    {"name": "announce", "description": "Post a styled announcement",
     "default_member_permissions": MANAGE_GUILD, "dm_permission": False,
     "options": [{"type": 7, "name": "channel", "description": "Where (default: announcements)", "required": False,
                  "channel_types": [0, 5]},
                 {"type": 3, "name": "ping", "description": "Ping people?", "required": False,
                  "choices": [{"name": "No ping", "value": "none"}, {"name": "@here", "value": "here"},
                              {"name": "@everyone", "value": "everyone"}]}]},
]


def commands() -> None:
    d, state = api(), load_state()
    app = d("GET", "/applications/@me")
    res = d("PUT", f"/applications/{app['id']}/guilds/{state['guild_id']}/commands", COMMANDS)
    print("registered:", ", ".join("/" + c["name"] for c in res))


def endpoint(url: str) -> None:
    url = url.rstrip("/") + "/interactions"
    res = api()("PATCH", "/applications/@me", {"interactions_endpoint_url": url})
    print("interactions endpoint:", res.get("interactions_endpoint_url"))


def panels() -> None:
    """Post the panels defined in bot.js (rendered with node so there's one source of truth)."""
    js = build()
    code = f"import('{js.as_uri()}').then(m => console.log(JSON.stringify(m.panels())))"
    p = json.loads(subprocess.check_output(["node", "--input-type=module", "-e", code], text=True))
    d, state = api(), load_state()
    for key, chan in (("verify", "verify"), ("ticket", "create-ticket")):
        ch = state["channels"][chan]
        me = d("GET", "/users/@me")["id"]
        old = [m for m in d("GET", f"/channels/{ch}/messages?limit=50")
               if m["author"]["id"] == me and m.get("components")]
        if old:
            d("PATCH", f"/channels/{ch}/messages/{old[0]['id']}", p[key])
            print(f"updated {key} panel")
        else:
            d("POST", f"/channels/{ch}/messages", p[key])
            print(f"posted {key} panel")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "build":
        build()
    elif cmd == "commands":
        commands()
    elif cmd == "endpoint" and len(sys.argv) > 2:
        endpoint(sys.argv[2])
    elif cmd == "panels":
        panels()
    else:
        sys.exit(__doc__)
