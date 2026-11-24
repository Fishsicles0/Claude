"""Tiny Discord REST client with no third-party dependencies (standard library only).

Used by setup_rest.py, deploy_worker.py and the hourly sync_site.py, which runs in fresh
containers where installing packages isn't guaranteed.
"""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request

import layout

MARKER = "server-setup:"   # embed footer on posts the setup tools made
REASON = f"{layout.CLIENT['name']} server setup"

# Each bot's token is attached by the network proxy per host: Evade uses discord.com, Vendetta the
# legacy discordapp.com domain (API v9), so the two tokens never mix.
API = {"evade": "https://discord.com/api/v10"}.get(os.environ.get("SERVER", "evade"), "https://discordapp.com/api/v9")


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
