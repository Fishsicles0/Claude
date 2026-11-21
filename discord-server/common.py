"""Shared helpers for setup_server.py and bot.py."""

from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path

import discord

import layout

HERE = Path(__file__).resolve().parent
STATE_FILE = HERE / "state.json"   # IDs of everything setup created (not secret, but server-specific)
SEP = "︱"


def channel_name(spec: dict) -> str:
    """The name Discord will store for a channel spec."""
    if spec.get("type") == "voice":
        return f"{spec['emoji']} {spec['name']}".strip()
    return f"{spec['emoji']}{SEP}{spec['name']}".lower().replace(" ", "-")


def all_channels():
    for cat in layout.CATEGORIES:
        for ch in cat["channels"]:
            yield cat, ch


def role_spec(key: str) -> dict:
    return next(r for r in layout.ROLES if r["key"] == key)


def permissions(names) -> discord.Permissions:
    p = discord.Permissions.none()
    for n in names:
        setattr(p, n, True)
    return p


def valid_permission(name: str) -> bool:
    try:
        discord.PermissionOverwrite(**{name: True})
        return True
    except (ValueError, TypeError):
        return False


# ── State (IDs) ───────────────────────────────────────────────────────────────

def load_state() -> dict:
    try:
        return json.loads(STATE_FILE.read_text())
    except (OSError, ValueError):
        return {"roles": {}, "categories": {}, "channels": {}, "panels": {}, "ticket_counter": 0}


def save_state(state: dict) -> None:
    """Atomic write, so a crash never leaves a half-written file."""
    fd, tmp = tempfile.mkstemp(dir=HERE, prefix=".state-")
    with os.fdopen(fd, "w") as f:
        json.dump(state, f, indent=2, sort_keys=True)
    os.replace(tmp, STATE_FILE)
