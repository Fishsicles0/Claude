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
from setup_rest import Discord

WORKER = HERE / "worker"
DIST = WORKER / "dist"

MOD_PERMS = str(1 << 40)          # moderate_members: hides staff commands from members
MANAGE_MESSAGES = str(1 << 13)
MANAGE_GUILD = str(1 << 5)


def api() -> Discord:
    return Discord(os.environ.get("DISCORD_TOKEN", "proxy-injected"))


def config() -> dict:
    state = load_state()
    if not state.get("guild_id"):
        sys.exit("state.json is missing. Run setup_rest.py or setup_server.py first.")
    app = api()("GET", "/applications/@me")
    s = lambda d: {k: str(v) for k, v in d.items()}
    return {
        "name": layout.CLIENT["name"], "color": layout.CLIENT["color"],
        "app_id": app["id"], "public_key": app["verify_key"], "guild_id": str(state["guild_id"]),
        "roles": s(state["roles"]), "channels": s(state["channels"]), "categories": s(state["categories"]),
        "security": layout.SECURITY, "admins": layout.ADMINS, "ticket_staff": layout.TICKET_STAFF,
        "tickets": layout.TICKET_TYPES, "lockdown_categories": layout.LOCKDOWN_CATEGORIES,
    }


def build() -> Path:
    src = (WORKER / "bot.js").read_text()
    out = src.replace("/*CONFIG*/ {}", json.dumps(config(), indent=1, ensure_ascii=False), 1)
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
