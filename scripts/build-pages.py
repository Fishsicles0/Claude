#!/usr/bin/env python3
"""Builds the multi-page site.

Each file in site-src/pages/*.html starts with a small header block:

    title: Features
    description: Every mod in Evade Client...
    nav: features
    ---
    <page body html>

and is wrapped in the shared layout below (head, nav, footer, toast, scripts), then written to
site/<name>.html. Cloudflare serves /features from features.html, so links use clean URLs.
"""
import html
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "site-src" / "pages"
OUT = ROOT / "site"
SITE = "https://evadeclient.com"

NAV = [("home", "/", "Home"), ("features", "/features", "Features"), ("clips", "/clips", "Clips"), ("download", "/download", "Download"),
       ("docs", "/docs", "Docs"), ("changelog", "/changelog", "Changelog"), ("showcase", "/showcase", "Showcase"),
       ("faq", "/faq", "FAQ")]

LOGO = ('<img src="/assets/logo-mark.svg" alt="" width="32" height="32">'
        '<span><span class="wordmark">EVADE</span><span class="logo-tag">CLIENT</span></span>')

LAYOUT = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<meta name="description" content="{description}">
<meta name="theme-color" content="#050506">
<link rel="canonical" href="{url}">
<meta property="og:type" content="website">
<meta property="og:site_name" content="Evade Client">
<meta property="og:title" content="{title}">
<meta property="og:description" content="{description}">
<meta property="og:url" content="{url}">
<meta property="og:image" content="{site}/assets/og.png">
<meta name="twitter:card" content="summary_large_image">
<meta name="twitter:image" content="{site}/assets/og.png">
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="icon" href="/favicon-32.png" sizes="32x32" type="image/png">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&family=Sora:wght@600;700;800&family=Chakra+Petch:ital,wght@1,700&family=JetBrains+Mono:wght@500;600&display=swap" rel="stylesheet">
<link rel="stylesheet" href="/assets/site.css?v={version}">
</head>
<body data-page="{nav}">
<a class="sr-only" href="#main">Skip to content</a>
<div class="bg-fx" aria-hidden="true"></div>

<nav class="top" id="top">
  <div class="wrap nav-in">
    <a href="/" class="logo" aria-label="Evade Client home">{logo}</a>
    <div class="nav-links">
{links}
    </div>
    <div class="nav-right">
      <span class="nav-ver">v{version}</span>
      <a href="/download" class="btn btn-red btn-sm">Download</a>
      <button class="burger" id="burger" aria-label="Open menu" aria-expanded="false" aria-controls="mobileMenu"><span></span></button>
    </div>
  </div>
  <div class="mobile-menu" id="mobileMenu">
{mobile_links}
  </div>
</nav>

<main id="main">
{body}
</main>

<footer>
  <div class="foot-big" aria-hidden="true">EVADE</div>
  <div class="wrap foot">
    <div>
      <a href="/" class="logo" aria-label="Evade Client home">{logo}</a>
      <p>A free Fabric client for Minecraft {mc}. Not affiliated with Mojang Studios or Microsoft. Minecraft is a trademark of Mojang Studios. Always follow the rules of the servers you play on.</p>
    </div>
    <div class="foot-cols">
      <div><h5>Client</h5><a href="/features">Features</a><a href="/showcase">Showcase</a><a href="/download">Download</a></div>
      <div><h5>Downloads</h5><a href="/download">Installer</a><a href="/download#pack">Texture Pack</a><a href="/download#install">Install guide</a></div>
      <div><h5>Evade Clips</h5><a href="/clips">Evade Clips</a><a href="https://clips.evadeclient.com">clips.evadeclient.com</a><a href="/downloads/Evade-Clips.exe">Download</a></div>
      <div><h5>Help</h5><a href="/faq">FAQ</a><a href="/faq#update">Updating</a><a href="/faq#safe">Allowed?</a></div>
    </div>
  </div>
  <div class="wrap foot-bottom"><span>&copy; 2026 Evade Client</span><span>v{version} &middot; Minecraft {mc} &middot; Fabric</span></div>
</footer>

<div class="toast" id="toast" role="status" aria-live="polite"><span id="toastText">Download started. Open it and click <b>Install</b>.</span></div>
<script src="/assets/site.js?v={version}" defer></script>
</body>
</html>
"""


ACTIVE = ' class="on" aria-current="page"'


def page(name: str, meta: dict, body: str, ver: dict) -> str:
    nav = meta.get("nav", "home")
    links = "\n".join(
        f'      <a href="{href}"{ACTIVE if key == nav else ""}>{label}</a>'
        for key, href, label in NAV)
    mobile = "\n".join(
        f'    <a href="{href}"{ACTIVE if key == nav else ""}>{label}</a>'
        for key, href, label in NAV)
    path = "/" if name == "index" else f"/{name}"
    title = meta["title"] if name == "index" else f'{meta["title"]} · Evade Client'
    return LAYOUT.format(
        title=html.escape(title), description=html.escape(meta["description"]), url=SITE + path, site=SITE,
        nav=nav, logo=LOGO, links=links, mobile_links=mobile, version=ver["version"], mc=ver["minecraft"],
        body=body.strip())


CATEGORY_ORDER = ["COMBAT", "PVP", "MOVEMENT", "SERVER", "VISUALS", "WORLD", "UTILITY", "HUD", "MODS", "MISC"]
CATEGORY_LABEL = {"COMBAT": "Combat", "PVP": "PvP macros", "MOVEMENT": "Movement", "SERVER": "Server-facing",
                  "VISUALS": "Visuals", "WORLD": "World", "UTILITY": "Utility", "HUD": "HUD", "MODS": "Controls", "MISC": "Misc"}

SETTING_RE = re.compile(r'new (NumberSetting|BooleanSetting|ModeSetting|ColorSetting|KeySetting)\(\s*"([^"]+)"\s*(?:,\s*(.*?))?\)\);')
TARGET_SETTINGS = [("Players", "toggle", "On"), ("Mobs", "toggle", "Off"), ("Animals", "toggle", "Off"),
                   ("Ignore Team", "toggle", "On"), ("Ignore Invis", "toggle", "Off")]


def split_args(a: str) -> list:
    out, cur, q = [], "", False
    for ch in a:
        if ch == '"':
            q = not q
        if ch == "," and not q:
            out.append(cur.strip()); cur = ""
        else:
            cur += ch
    if cur.strip():
        out.append(cur.strip())
    return [x.strip('"') for x in out]


def fmt_num(x: str) -> str:
    v = float(x)
    return str(int(v)) if v == int(v) else f"{v:g}"


def key_name(k: str) -> str:
    k = k.replace("GLFW.GLFW_KEY_", "").replace("GLFW_KEY_", "")
    return "None" if k == "UNKNOWN" else k.replace("_", " ").title()


def inherited(src: str, java) -> list:
    """Settings every HUD element gets from its base classes (scale, background, text colour...)."""
    out = []
    if "extends TextHudModule" in src:
        out += scan_settings((java / "TextHudModule.java").read_text())
        src = "extends HudElementModule"
    if "extends HudElementModule" in src:
        out += scan_settings((java / "HudElementModule.java").read_text())
    return out


def scan_settings(src: str) -> list:
    out = []
    for m in SETTING_RE.finditer(src):
        kind, name, args = m.group(1), m.group(2), split_args(m.group(3) or "")
        try:
            if kind == "BooleanSetting":
                out.append({"name": name, "type": "toggle", "default": "On" if args[0] == "true" else "Off"})
            elif kind == "NumberSetting":
                out.append({"name": name, "type": "slider", "default": fmt_num(args[0]), "min": fmt_num(args[1]), "max": fmt_num(args[2])})
            elif kind == "ModeSetting":
                out.append({"name": name, "type": "mode", "default": args[0], "options": args[1:]})
            elif kind == "ColorSetting":
                out.append({"name": name, "type": "color", "default": "#" + args[0][-6:].upper()})
            elif kind == "KeySetting":
                out.append({"name": name, "type": "key", "default": key_name(args[0])})
        except (IndexError, ValueError):
            continue
    if "targets.addTo(" in src:
        out += [{"name": n, "type": t, "default": d} for n, t, d in TARGET_SETTINGS]
    return out


NEW_IN_LATEST = set(json.loads((ROOT / "site-src" / "new.json").read_text())["classes"]) if (ROOT / "site-src" / "new.json").exists() else set()


def scan_mods() -> list:
    """Reads every module's name, description and category straight from the client source."""
    java = ROOT / "src" / "main" / "java" / "net" / "evadeclient" / "module"
    registered = (java / "ModuleManager.java").read_text()
    mods = []
    for f in sorted(java.rglob("*.java")):
        src = f.read_text()
        m = re.search(r'super\(\s*"([^"]+)",\s*"([^"]*)"(?:,\s*(Category\.([A-Z]+)))?', src)
        if not m or "abstract class" in src:
            continue
        cls = f.stem
        if not re.search(r"register\(new (?:[\w.]+\.)?" + cls + r"\(\)\)", registered):
            continue
        cat = m.group(4) or "HUD"  # HUD elements default to the HUD category
        if cat == "CLIENT":
            continue
        mods.append({"name": m.group(1), "desc": m.group(2), "cat": CATEGORY_LABEL[cat], "slug": cat.lower(),
                     "settings": scan_settings(src) + inherited(src, java), "new": cls in NEW_IN_LATEST})
    mods.sort(key=lambda x: (CATEGORY_ORDER.index([k for k, v in CATEGORY_LABEL.items() if v == x["cat"]][0]), x["name"].lower()))
    return mods


def main() -> None:
    mods = scan_mods()
    (OUT / "assets" / "mods.json").write_text(json.dumps(mods, separators=(",", ":")))
    # count by enum key (COMBAT, PVP, SERVER, UTILITY...) so site tokens are {{PVP}} etc., not label text
    counts = {key: sum(1 for m in mods if m["cat"] == label) for key, label in CATEGORY_LABEL.items()}
    ver = json.loads((OUT / "version.json").read_text())
    n_settings = sum(len(m["settings"]) for m in mods)
    n_new = sum(1 for m in mods if m["new"])
    tokens = {"{{TOTAL}}": str(len(mods))} | {"{{" + k + "}}": str(v) for k, v in counts.items()}
    tokens |= {"{{SETTINGS}}": str(n_settings), "{{NEW}}": str(n_new), "{{CATS}}": str(len([c for c in counts.values() if c]))}
    tokens |= {
        "{{VERSION}}": ver["version"], "{{MC}}": ver["minecraft"], "{{DATE}}": ver["date"],
        "{{SIZE_MOD}}": ver["size"], "{{SHA_MOD}}": ver["sha256"],
        "{{SIZE_EXE}}": ver["installer"]["windows"]["size"], "{{SHA_EXE}}": ver["installer"]["windows"]["sha256"],
        "{{SIZE_IJAR}}": ver["installer"]["jar"]["size"], "{{SHA_IJAR}}": ver["installer"]["jar"]["sha256"],
        "{{PACK_VERSION}}": ver["texturePack"]["version"], "{{SIZE_PACK}}": ver["texturePack"]["size"],
        "{{SHA_PACK}}": ver["texturePack"]["sha256"],
    }
    clips = ver.get("clips") or {}
    tokens |= {
        "{{CLIPS_VERSION}}": clips.get("version", ""),
        "{{SIZE_CLIPS_EXE}}": (clips.get("windows") or {}).get("size", ""),
        "{{SIZE_CLIPS_JAR}}": (clips.get("jar") or {}).get("size", ""),
    }
    printer_mods = json.loads((ROOT / "site-src" / "printer-mods.json").read_text())["mods"]
    rows = "".join(
        f'<a class="req-mod" href="{html.escape(m["page"])}" target="_blank" rel="noopener">'
        f'<div class="req-top"><h4>{html.escape(m["name"])}</h4><span class="req-ver">v{html.escape(m["version"])}</span></div>'
        f'<p>{html.escape(m["need"])}</p>'
        f'<span class="req-get">Official download &middot; {html.escape(m["size"])} <em>&rarr;</em></span></a>'
        for m in printer_mods)
    tokens |= {"{{PRINTER_MODS}}": rows,
               "{{LITEMATICA_VER}}": printer_mods[0]["version"], "{{LITEMATICA_URL}}": printer_mods[0]["page"],
               "{{MALILIB_VER}}": printer_mods[1]["version"], "{{MALILIB_URL}}": printer_mods[1]["page"]}
    # One card per HUD element, for the Showcase page's HUD gallery (links to each element's detail view).
    hud_cards = "".join(
        f'<a class="card hud-card" href="/features#m={html.escape(m["name"].replace(" ", "%20"))}">'
        f'<h4>{html.escape(m["name"])}</h4><p>{html.escape(m["desc"])}</p>'
        f'<span class="hud-foot">{len(m["settings"])} setting{"s" if len(m["settings"]) != 1 else ""} <em>&rarr;</em></span></a>'
        for m in mods if m["slug"] == "hud")
    tokens |= {"{{HUD_LIST}}": hud_cards, "{{HUD_COUNT}}": str(sum(1 for m in mods if m["slug"] == "hud"))}
    print(f"mods.json: {len(mods)} mods, {n_settings} settings, {n_new} new: " + ", ".join(f"{k} {v}" for k, v in counts.items()))
    for src in sorted(SRC.glob("*.html")):
        text = src.read_text()
        for k, v in tokens.items():
            text = text.replace(k, v)
        head, body = text.split("\n---\n", 1)
        meta = dict(line.split(": ", 1) for line in head.strip().splitlines())
        out = OUT / src.name
        left = re.findall(r"\{\{[A-Z_]+\}\}", body)
        if left:
            raise SystemExit(f"{src.name}: unknown tokens {sorted(set(left))}")
        out.write_text(page(src.stem, meta, body, ver))
        print(f"built {out.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
