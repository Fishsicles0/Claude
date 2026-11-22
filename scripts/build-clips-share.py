#!/usr/bin/env python3
"""Builds the pages of clips.evadeclient.com: a standalone Evade Clips site in the same design system as the
main site (its stylesheet, fonts and components), with its own header, menu and footer.

Writes clips-share/src/shell.js with:
  LANDING     the home page (clips-share/site/landing.html in the layout)
  TOP/BOTTOM  the layout around page content, for clip pages, the phone gallery and error pages
The stylesheet, scripts, logo and screenshots are served from the R2 bucket (see clips-share/sync-assets.sh);
versions, sizes and checksums come from clips-share/public/downloads/clips.json.
"""
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
SHARE = ROOT / "clips-share"
dl = json.loads((SHARE / "public" / "downloads" / "clips.json").read_text())
css_v = json.loads((ROOT / "site" / "version.json").read_text())["version"]  # cache-busts the shared stylesheet

LOGO = ('<img src="/assets/logo-mark.svg" alt="" width="32" height="32">'
        '<span><span class="wordmark">EVADE</span><span class="logo-tag">CLIPS</span></span>')
NAV = [("#features", "Features"), ("#how", "How it works"), ("#share", "Sharing"), ("#faq", "FAQ")]

HEAD = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{{TITLE}}</title>
<meta name="theme-color" content="#050506">
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="icon" href="/favicon-32.png" sizes="32x32" type="image/png">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&family=Sora:wght@600;700;800&family=Chakra+Petch:ital,wght@1,700&family=JetBrains+Mono:wght@500;600&display=swap" rel="stylesheet">
<link rel="stylesheet" href="/assets/site.css?v=CSSV">
<style>
.clips-sums{padding:22px 24px}
.clips-sums h3{margin:0 0 12px;font-family:var(--display)}
.clips-sums p{margin:0 0 10px;color:var(--muted);font-size:14px}
.clips-sums code{word-break:break-all;color:var(--faint);font-size:12px}
#faq .faq-group{max-width:860px}
.foot p{max-width:340px}
</style>
{{HEAD}}
</head>
<body data-page="clips">
<a class="sr-only" href="#main">Skip to content</a>
<nav class="top" id="top">
  <div class="wrap nav-in">
    <a href="/" class="logo" aria-label="Evade Clips home">LOGO</a>
    <div class="nav-links">
LINKS
    </div>
    <div class="nav-right">
      <span class="nav-ver">vVERSION</span>
      <a href="/#download" class="btn btn-red btn-sm">Download</a>
      <button class="burger" id="burger" aria-label="Open menu" aria-expanded="false" aria-controls="mobileMenu"><span></span></button>
    </div>
  </div>
  <div class="mobile-menu" id="mobileMenu">
MOBILE
  </div>
</nav>

<main id="main">"""

FOOT = """</main>

<footer>
  <div class="foot-big" aria-hidden="true">CLIPS</div>
  <div class="wrap foot">
    <div>
      <a href="/" class="logo" aria-label="Evade Clips home">LOGO</a>
      <p>Record, edit and share your gameplay. Free, with no watermark and no ads.</p>
    </div>
    <div class="foot-cols">
      <div><h5>Product</h5><a href="/#features">Features</a><a href="/#how">How it works</a><a href="/#share">Sharing</a></div>
      <div><h5>Download</h5><a href="/downloads/Evade-Clips.exe">Windows</a><a href="/downloads/EvadeClips.jar">macOS &amp; Linux</a><a href="/#checksums">Checksums</a></div>
      <div><h5>Help</h5><a href="/#faq">FAQ</a><a href="/#report">Report a clip</a></div>
    </div>
  </div>
  <div class="wrap foot-bottom"><span>&copy; 2026 Evade Clips</span><span>vVERSION</span></div>
</footer>
<script src="/assets/site.js?v=CSSV" defer></script>
</body>
</html>
"""


def fill(s: str) -> str:
    links = "\n".join(f'      <a href="/{h}">{t}</a>' for h, t in NAV)
    mobile = "\n".join(f'    <a href="/{h}">{t}</a>' for h, t in NAV)
    return (s.replace("LOGO", LOGO).replace("LINKS", links).replace("MOBILE", mobile)
            .replace("VERSION", dl["version"]).replace("CSSV", css_v))


top, bottom = fill(HEAD), fill(FOOT)

body = (SHARE / "site" / "landing.html").read_text()
for k, v in {"{{VERSION}}": dl["version"], "{{SIZE_EXE}}": dl["windows"]["size"], "{{SHA_EXE}}": dl["windows"]["sha256"],
             "{{SIZE_JAR}}": dl["jar"]["size"], "{{SHA_JAR}}": dl["jar"]["sha256"]}.items():
    body = body.replace(k, v)
desc = "Evade Clips: instant replay, auto-clips, voice commands, a full editor and share links that play in Discord. Free, no watermark, no ads."
landing_head = (f'<meta name="description" content="{desc}">\n<link rel="canonical" href="https://clips.evadeclient.com/">\n'
                '<meta property="og:type" content="website">\n<meta property="og:site_name" content="Evade Clips">\n'
                '<meta property="og:title" content="Evade Clips: clip it, edit it, share it">\n'
                f'<meta property="og:description" content="{desc}">\n<meta property="og:url" content="https://clips.evadeclient.com/">\n'
                '<meta property="og:image" content="https://clips.evadeclient.com/assets/clips-editor.webp">\n'
                '<meta name="twitter:card" content="summary_large_image">')
landing = (top.replace("{{TITLE}}", "Evade Clips: clip it, edit it, share it").replace("{{HEAD}}", landing_head)
           + "\n" + body + bottom)

out = SHARE / "src" / "shell.js"
out.write_text(
    "// Generated by scripts/build-clips-share.py from clips-share/site/landing.html. Do not edit by hand.\n"
    f"export const LANDING = {json.dumps(landing)};\n"
    f"export const TOP = {json.dumps(top)};\n"
    f"export const BOTTOM = {json.dumps(bottom)};\n"
)
print(f"wrote {out.relative_to(ROOT)} ({out.stat().st_size // 1024} KB)")
