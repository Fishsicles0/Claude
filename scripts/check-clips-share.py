#!/usr/bin/env python3
"""Checks that clips.evadeclient.com is serving the current release, after `clips-share/sync-assets.sh` and the
Worker deploy. Exits non-zero (so a release script stops) when:
  - its /version.json (the BUILD the pages were made from) differs from evadeclient.com/version.json: the
    subdomain would show other sizes or checksums than the main site;
  - a download on it doesn't redirect to the same file on evadeclient.com/downloads;
  - /privacy, /robots.txt or /sitemap.xml are missing;
  - the home page still mentions games other than Minecraft.

  python3 scripts/check-clips-share.py                       # the live sites
  python3 scripts/check-clips-share.py http://127.0.0.1:8787 site/version.json   # a local `wrangler dev`
"""
import json
import sys
import urllib.error
import urllib.request

clips = (sys.argv[1] if len(sys.argv) > 1 else "https://clips.evadeclient.com").rstrip("/")
main_src = sys.argv[2] if len(sys.argv) > 2 else "https://evadeclient.com/version.json"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


opener = urllib.request.build_opener(NoRedirect)
UA = {"User-Agent": "evade-release-check", "Cache-Control": "no-cache"}


def get(url):
    try:
        with opener.open(urllib.request.Request(url, headers=UA), timeout=30) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def load_main():
    if main_src.startswith("http"):
        return json.loads(get(main_src)[2])
    with open(main_src) as f:
        return json.load(f)


problems = []
main = load_main()
st, _, body = get(clips + "/version.json")
live = json.loads(body) if st == 200 else {}
want = {"clips": {"version": main["clips"]["version"], "windows": main["clips"]["windows"]["sha256"], "jar": main["clips"]["jar"]["sha256"]},
        "installer": {"windows": main["installer"]["windows"]["sha256"], "jar": main["installer"]["jar"]["sha256"]}}
for k, v in want.items():
    if live.get(k) != v:
        problems.append(f"/version.json {k}: live {live.get(k)} != main site {v} (run sync-assets.sh and redeploy the Worker)")

for f in ("Evade-Clips.exe", "EvadeClips.jar"):
    st, h, _ = get(f"{clips}/downloads/{f}")
    loc = h.get("Location") or h.get("location") or ""
    if st not in (301, 302) or loc != f"https://evadeclient.com/downloads/{f}":
        problems.append(f"/downloads/{f}: {st} {loc!r}, want a redirect to evadeclient.com/downloads/{f}")

st, h, _ = get(clips + "/privacy")
if st not in (301, 302) or "evadeclient.com/privacy" not in (h.get("Location") or h.get("location") or ""):
    problems.append(f"/privacy: {st}, want a redirect to evadeclient.com/privacy")
st, _, body = get(clips + "/robots.txt")
if st != 200 or b"User-agent" not in body or b"Sitemap:" not in body:
    problems.append(f"/robots.txt: {st}, missing User-agent or Sitemap lines")
st, _, body = get(clips + "/sitemap.xml")
if st != 200 or b"<urlset" not in body:
    problems.append(f"/sitemap.xml: {st}")
st, _, body = get(clips + "/")
page = body.decode("utf-8", "replace")
for word in ("CS2", "League of Legends", "Valorant", "Fortnite", "any game"):
    if word in page:
        problems.append(f"home page still mentions {word!r}")
for sha in (want["clips"]["windows"], want["installer"]["windows"]):
    if sha not in page:
        problems.append(f"home page doesn't show checksum {sha[:12]}…")

if problems:
    print("clips.evadeclient.com is NOT in step with evadeclient.com:")
    for p in problems:
        print("  -", p)
    sys.exit(1)
print(f"clips.evadeclient.com OK: Evade Clips {want['clips']['version']}, same files and checksums as evadeclient.com")
