"""Live content from evadeclient.com for the Discord posts.

Reads version.json (versions, file names, sizes, checksums) and the changelog page, and turns
them into the #download post, the #changelog posts and release announcements, so the Discord
always matches the website. Used by sync_site.py (hourly) and setup_rest.py.
"""

from __future__ import annotations

import html
import json
import re
import urllib.request

import layout

SITE = layout.CLIENT["site"].rstrip("/")
UA = {"User-Agent": "EvadeDiscordSync/1.0"}
CHANGELOG_ENTRIES = 3   # how many releases #changelog shows


def fetch(path: str) -> str:
    req = urllib.request.Request(f"{SITE}/{path.lstrip('/')}", headers=UA)
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8")


def version_info() -> dict:
    return json.loads(fetch("version.json"))


def _md(fragment: str) -> str:
    """Changelog HTML → Discord markdown."""
    s = re.sub(r"<(b|strong)>(.*?)</\1>", r"**\2**", fragment, flags=re.S)
    s = re.sub(r"<(i|em)>(.*?)</\1>", r"*\2*", s, flags=re.S)
    s = re.sub(r"<code>(.*?)</code>", r"`\1`", s, flags=re.S)
    s = re.sub(r'<span class="mono">(.*?)</span>', r"`\1`", s, flags=re.S)
    s = re.sub(r'<(kbd)>(.*?)</\1>', r"`\2`", s, flags=re.S)

    def link(m):
        href, text = m.group(1), m.group(2)
        if href.startswith("/"):
            href = SITE + href
        return f"[{text}]({href})" if href.startswith("http") else text
    s = re.sub(r'<a [^>]*href="([^"]+)"[^>]*>(.*?)</a>', link, s, flags=re.S)
    s = re.sub(r"<[^>]+>", "", s)
    return re.sub(r"\s+", " ", html.unescape(s)).strip()


def changelog() -> list[dict]:
    """Releases on the changelog page, newest first: version, date, nice date, summary, bullets."""
    page = fetch("changelog")
    out = []
    for m in re.finditer(r'<article class="card rel[^"]*" id="([^"]+)"[^>]*>(.*?)</article>', page, re.S):
        body = m.group(2)
        h2 = re.search(r"<h2[^>]*>(.*?)</h2>", body, re.S)
        tm = re.search(r'<time datetime="([^"]+)"[^>]*>(.*?)</time>', body, re.S)
        if not h2:
            continue
        summary = re.search(r'<p class="rel-sum">(.*?)</p>', body, re.S)
        out.append({
            "id": m.group(1),
            "version": _md(h2.group(1)),
            "date": tm.group(1) if tm else "",
            "nice_date": _md(tm.group(2)) if tm else "",
            "summary": _md(summary.group(1)) if summary else "",
            "bullets": [_md(li) for li in re.findall(r"<li>(.*?)</li>", body, re.S)],
        })
    return out


def _clip(text: str, n: int) -> str:
    return text if len(text) <= n else text[: n - 1].rsplit("\n", 1)[0] + "\n…"


def release_text(entry: dict, limit: int = 3900) -> str:
    lines = [f"**{entry['summary']}**", ""] + [f"• {b}" for b in entry["bullets"]]
    return _clip("\n".join(lines), limit)


def _size(kb: str) -> str:
    try:
        return f"{int(str(kb).split()[0]) / 1024:.1f} MB"
    except (ValueError, IndexError):
        return str(kb)


def download_post(v: dict) -> dict:
    dl = SITE + "/"
    win, jar = v["installer"]["windows"], v["installer"]["jar"]
    return {
        "raw": True,
        "title": f"⬇️ Download Evade v{v['version']}",
        "description": (
            "**Evade Launcher (recommended)**\n"
            f"🪟 **Windows 10/11:** [{win['file'].split('/')[-1]}]({dl}{win['file']}) · {_size(win['size'])}\n"
            f"🍎 **macOS** / 🐧 **Linux:** [{jar['file'].split('/')[-1]}]({dl}{jar['file']}) · {_size(jar['size'])} · needs Java 17+\n\n"
            "The launcher installs Evade, keeps it up to date and starts it through the official Minecraft Launcher, "
            "which signs you in. It never sees your password. Evade Clips is built in.\n\n"
            "**Just the mod (advanced)**\n"
            f"[{v['file'].split('/')[-1]}]({dl}{v['file']}) · {_size(v['size'])}: put it in your mods folder with "
            f"Fabric API for {v['minecraft']}.\n\n"
            f"Download page: {SITE}/download · Install steps: #install-guide\n\n"
            "⚠️ Only download from evadeclient.com or this channel. If anyone sends you a file anywhere else, don't run it."),
        "fields": [
            ("Minecraft", f"Java {v['minecraft']}", True),
            ("Loader", v.get("loader", "Fabric"), True),
            ("Released", v.get("date", "?"), True),
            ("SHA-256 checksums",
             f"Launcher .exe `{win['sha256']}`\nLauncher .jar `{jar['sha256']}`\nMod jar `{v['sha256']}`", False),
        ],
    }


def changelog_posts(entries: list[dict]) -> list[dict]:
    posts = [{"raw": True, "title": "📝 Changelog",
              "description": f"The latest releases, newest at the bottom. The full history is at {SITE}/changelog.",
              "fields": []}]
    for e in reversed(entries[:CHANGELOG_ENTRIES]):
        posts.append({"raw": True, "title": f"{e['version']} · {e['nice_date']}", "description": release_text(e, 3000), "fields": []})
    return posts


def announcement(entry: dict) -> dict:
    return {
        "raw": True,
        "title": f"🚀 Evade {entry['version']} is out!",
        "description": release_text(entry) + f"\n\n**Update:** open the Evade Launcher, it updates itself.\n"
                                             f"**New here?** {SITE}/download · Full notes in #changelog",
        "fields": [],
    }


SLOTS = [  # catalogue slot, label, emoji (in display order)
    ("CAPE", "capes", "🧥"), ("WINGS", "wings", "🪽"), ("HAT", "hats", "🎩"), ("PET", "pets", "🐾"),
    ("AURA", "auras", "✨"), ("BACK", "back items", "🎒"), ("MASK", "masks", "🎭"), ("EMOTE", "emotes", "💃"),
    ("TRAIL", "trails", "🌠"), ("KILL_EFFECT", "kill effects", "⚔️"), ("WIN_EFFECT", "win effects", "🏆"),
    ("ITEM_SKIN", "sword skins", "🗡️"),
]


def cosmetics_catalog() -> list[dict]:
    return json.loads(fetch("cosmetics/catalog.json"))["items"]


def cosmetics_post(items: list[dict]) -> dict:
    """The pinned #cosmetics post, built from the shop's live catalogue."""
    counts = {}
    for it in items:
        counts[it.get("slot")] = counts.get(it.get("slot"), 0) + 1
    kinds = " · ".join(f"{emoji} {counts[slot]} {label}" for slot, label, emoji in SLOTS if counts.get(slot))
    special = [it["name"] for it in items if it.get("rarity") == "special"]
    examples = ", ".join(special[:3])
    return {
        "raw": True,
        "title": "👕 Cosmetics: Coming Soon",
        "description": (
            f"The **Evade Shop** is almost here: **{len(items)} cosmetics** you can already preview in 3D at "
            f"{SITE}/shop. Checkout opens soon, and it'll be announced in #announcements.\n\n"
            f"**What's coming**\n{kinds}\n\n"
            "**Rarities**\n"
            f"Regular · **Special**: {len(special)} animated items"
            + (f" like {examples}" if examples else "") + " · **Partner**: branded items for partner servers\n\n"
            "**How it'll work**\n"
            "1. In Evade, open **Wardrobe → Link the shop** to get a 6-character code.\n"
            "2. Enter it on the shop and pick your items, or gift them using a friend's code.\n"
            "3. Wear them from the Wardrobe and switch them on or off any time.\n\n"
            "**Cosmetic only:** nothing changes hitboxes, reach or speed. Only Evade players see them, "
            "and you can hide everyone's cosmetics if you'd rather not see them.\n\n"
            f"⚠️ Cosmetics are only sold at {SITE}/shop, and staff will never ask for your password. "
            "Anyone selling Evade cosmetics anywhere else is a scammer. Report them in #create-ticket."),
        "fields": [],
    }


def live_posts() -> tuple[dict, dict, list[dict]]:
    """(posts with live data, version.json, changelog entries). Raises if the site can't be read."""
    v = version_info()
    entries = changelog()
    if not entries:
        raise RuntimeError("couldn't read any releases from the changelog page")
    layout.CLIENT["version"] = v["version"]
    layout.CLIENT["minecraft"] = v.get("minecraft", layout.CLIENT["minecraft"])
    posts = dict(layout.POSTS)
    posts["download"] = [download_post(v)]
    posts["changelog"] = changelog_posts(entries)
    try:
        posts["cosmetics"] = [cosmetics_post(cosmetics_catalog())]
    except Exception:  # noqa: BLE001 - the shop catalogue is optional; keep the layout's post
        pass
    return posts, v, entries
