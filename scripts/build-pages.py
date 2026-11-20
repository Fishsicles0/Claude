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

NAV = [("home", "/", "Home"), ("features", "/features", "Features"), ("download", "/download", "Download"),
       ("showcase", "/showcase", "Showcase"), ("faq", "/faq", "FAQ")]

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
      <p>A free Fabric client for Minecraft {mc}. Not affiliated with Mojang Studios or Microsoft. Minecraft is a trademark of Mojang Studios. Most servers ban cheat clients: you are responsible for following the rules of the servers you play on.</p>
    </div>
    <div class="foot-cols">
      <div><h5>Client</h5><a href="/features">Features</a><a href="/showcase">Showcase</a><a href="/features#printer">Printer</a></div>
      <div><h5>Downloads</h5><a href="/download">Installer</a><a href="/download#pack">Texture Pack</a><a href="/download#install">Install guide</a></div>
      <div><h5>Help</h5><a href="/faq">FAQ</a><a href="/faq#update">Updating</a><a href="/faq#ban">Ban risk</a></div>
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


CATEGORY_ORDER = ["COMBAT", "MOVEMENT", "VISUALS", "WORLD", "PLAYER", "HUD", "MODS"]
CATEGORY_LABEL = {"COMBAT": "Combat", "MOVEMENT": "Movement", "VISUALS": "Visuals", "WORLD": "World",
                  "PLAYER": "Player", "HUD": "HUD", "MODS": "Mods"}


def scan_mods() -> list:
    """Reads every module's name, description and category straight from the client source."""
    java = ROOT / "src" / "main" / "java" / "net" / "evadeclient" / "module"
    risk = json.loads((ROOT / "site-src" / "risk.json").read_text())
    level = {n: "high" for n in risk["high"]} | {n: "med" for n in risk["med"]}
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
        # HUD elements only draw on your screen, so they're always low risk (the HUD "Speed" readout shares a name
        # with the Movement cheat "Speed").
        risk_level = "low" if cat == "HUD" else level.get(m.group(1), "low")
        mods.append({"name": m.group(1), "desc": m.group(2), "cat": CATEGORY_LABEL[cat], "risk": risk_level})
    mods.sort(key=lambda x: (CATEGORY_ORDER.index([k for k, v in CATEGORY_LABEL.items() if v == x["cat"]][0]), x["name"].lower()))
    return mods


def main() -> None:
    mods = scan_mods()
    (OUT / "assets" / "mods.json").write_text(json.dumps(mods, indent=1))
    counts = {c: sum(1 for m in mods if m["cat"] == c) for c in CATEGORY_LABEL.values()}
    ver = json.loads((OUT / "version.json").read_text())
    risk = {r: sum(1 for m in mods if m["risk"] == r) for r in ("low", "med", "high")}
    tokens = {"{{TOTAL}}": str(len(mods))} | {"{{" + k.upper() + "}}": str(v) for k, v in counts.items()}
    tokens |= {"{{RISK_" + k.upper() + "}}": str(v) for k, v in risk.items()}
    tokens |= {
        "{{VERSION}}": ver["version"], "{{MC}}": ver["minecraft"], "{{DATE}}": ver["date"],
        "{{SIZE_MOD}}": ver["size"], "{{SHA_MOD}}": ver["sha256"],
        "{{SIZE_EXE}}": ver["installer"]["windows"]["size"], "{{SHA_EXE}}": ver["installer"]["windows"]["sha256"],
        "{{SIZE_IJAR}}": ver["installer"]["jar"]["size"], "{{SHA_IJAR}}": ver["installer"]["jar"]["sha256"],
        "{{PACK_VERSION}}": ver["texturePack"]["version"], "{{SIZE_PACK}}": ver["texturePack"]["size"],
        "{{SHA_PACK}}": ver["texturePack"]["sha256"],
    }
    print(f"mods.json: {len(mods)} mods " + ", ".join(f"{k} {v}" for k, v in counts.items()))
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
