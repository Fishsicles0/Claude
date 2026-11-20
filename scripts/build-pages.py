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
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "site-src" / "pages"
OUT = ROOT / "site"
SITE = "https://evadeclient.com"

NAV = [("home", "/", "Home"), ("features", "/features", "Features"), ("download", "/download", "Download"),
       ("showcase", "/showcase", "Showcase"), ("faq", "/faq", "FAQ")]

LOGO = ('<img src="/assets/logo-mark.svg" alt="" width="34" height="34">'
        '<span><span class="wordmark">EVADE</span><span class="logo-tag">CLIENT</span></span>')

LAYOUT = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<meta name="description" content="{description}">
<meta name="theme-color" content="#0a0a0c">
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
<link href="https://fonts.googleapis.com/css2?family=Sora:wght@400;600;700;800&family=Chakra+Petch:ital,wght@1,700&family=JetBrains+Mono:wght@500&display=swap" rel="stylesheet">
<link rel="stylesheet" href="/assets/site.css">
</head>
<body data-page="{nav}">
<div class="bg-fx"></div>

<nav>
  <div class="wrap nav-in">
    <a href="/" class="logo" aria-label="Evade Client home">{logo}</a>
    <div class="nav-links">
{links}
    </div>
    <div class="nav-right">
      <a href="/download" class="btn btn-red btn-sm">Download</a>
      <button class="burger" id="burger" aria-label="Open menu" aria-expanded="false" aria-controls="mobileMenu"><span></span></button>
    </div>
  </div>
  <div class="mobile-menu" id="mobileMenu">
{mobile_links}
  </div>
</nav>

<main>
{body}
</main>

<footer>
  <div class="wrap foot">
    <div>
      <a href="/" class="logo" style="margin-bottom:14px" aria-label="Evade Client home">{logo}</a>
      <p>Evade Client is not affiliated with Mojang Studios or Microsoft. Minecraft is a trademark of Mojang Studios. You are responsible for following the rules of the servers you play on.</p>
    </div>
    <div class="foot-cols">
      <div><h5>Client</h5><a href="/features">Features</a><a href="/showcase">Showcase</a></div>
      <div><h5>Get started</h5><a href="/download">Download</a><a href="/download#install">Install guide</a></div>
      <div><h5>Help</h5><a href="/faq">FAQ</a><a href="/faq#update">Updating</a></div>
    </div>
  </div>
  <div class="wrap foot-bottom"><span>&copy; 2026 Evade Client</span><span class="js-version-line">v0.3.1 &middot; Minecraft 1.21.11</span></div>
</footer>

<div class="toast" id="toast"><span id="toastText">Download started. Open it and click <b>Install</b>.</span></div>
<script src="/assets/site.js" defer></script>
</body>
</html>
"""


ACTIVE = ' class="on" aria-current="page"'


def page(name: str, meta: dict, body: str) -> str:
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
        nav=nav, logo=LOGO, links=links, mobile_links=mobile,
        body=body.strip())


def main() -> None:
    for src in sorted(SRC.glob("*.html")):
        text = src.read_text()
        head, body = text.split("\n---\n", 1)
        meta = dict(line.split(": ", 1) for line in head.strip().splitlines())
        out = OUT / src.name
        out.write_text(page(src.stem, meta, body))
        print(f"built {out.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
