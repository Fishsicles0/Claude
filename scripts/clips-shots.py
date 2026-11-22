#!/usr/bin/env python3
"""Renders the Evade Clips screenshots used on evadeclient.com and clips.evadeclient.com from real Minecraft
footage (filmed by scripts/film-footage.sh into build/footage).

  1. The app's pages (library, editor, record, settings): EvadeClips.jar --screenshots with -Dclips.shots.footage,
     so the library, exports and live preview are all that footage.
  2. The phone gallery and a shared clip page: the footage uploaded to a local copy of the share server
     (wrangler dev --local), photographed with Playwright as an iPhone.

Writes site/assets/clips-{library,editor,record,settings,phone-gallery,phone-clip}.webp.
"""
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
FOOTAGE = ROOT / "build" / "footage"
OUT = ROOT / "build" / "clips-shots"
ASSETS = ROOT / "site" / "assets"
SHARE = ROOT / "clips-share"
PORT = 8787
BASE = f"http://127.0.0.1:{PORT}"

# (file, title) in the order they're shared; the gallery lists the newest first.
SHARED = [("pearl", "Pearl escape"), ("bridge", "Bridge fight"), ("crystal", "Crystal PvP final"),
          ("sunset", "Sunset duel"), ("totem", "Totem pop chain"), ("clutch", "1v3 clutch at mid")]


def run(cmd, **kw):
    print("+", " ".join(map(str, cmd)))
    subprocess.run(cmd, check=True, **kw)


def webp(png: pathlib.Path, name: str):
    from PIL import Image
    Image.open(png).convert("RGB").save(ASSETS / f"clips-{name}.webp", "WEBP", quality=84, method=6)
    print(f"  site/assets/clips-{name}.webp")


def app_pages():
    run([str(ROOT / "gradlew"), ":clips:jar", "-q"], cwd=ROOT)
    if OUT.exists():
        shutil.rmtree(OUT)
    run(["xvfb-run", "-a", "-s", "-screen 0 1600x1000x24", "java", f"-Dclips.shots.footage={FOOTAGE}",
         "-jar", str(ROOT / "clips" / "build" / "libs" / "EvadeClips.jar"), "--screenshots", str(OUT)], cwd=ROOT)
    for n in ("library", "editor", "record", "settings"):
        webp(OUT / f"{n}.png", n)


def api(path, body=None, method="POST", auth=None, data=None, ctype="application/json"):
    req = urllib.request.Request(BASE + path, method=method,
                                 data=data if data is not None else (json.dumps(body).encode() if body is not None else None))
    req.add_header("Content-Type", ctype)
    if auth:
        req.add_header("Authorization", auth)
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read() or b"{}")


def probe(f):
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height:format=duration",
                          "-of", "json", str(f)], capture_output=True, text=True, check=True).stdout
    j = json.loads(out)
    return float(j["format"]["duration"]), j["streams"][0]["width"], j["streams"][0]["height"]


def phone_pages():
    run(["bash", "sync-assets.sh", "--local"], cwd=SHARE)
    log = open(OUT / "wrangler.log", "w")
    env = dict(os.environ, WRANGLER_SEND_METRICS="false")
    dev = subprocess.Popen(["npx", "--yes", "wrangler@3", "dev", "--local", "--port", str(PORT), "--var", "QUOTA_GB:5", "--var", "DEV:1"],
                           cwd=SHARE, stdout=log, stderr=subprocess.STDOUT, env=env)
    try:
        for _ in range(120):
            try:
                urllib.request.urlopen(BASE + "/", timeout=2)
                break
            except Exception:
                time.sleep(1)
        acc = api("/api/register", {})
        auth = f"Bearer {acc['account']}.{acc['key']}"
        tmp = pathlib.Path(tempfile.mkdtemp())
        last = None
        for name, title in SHARED:
            f = FOOTAGE / f"{name}.mp4"
            dur, w, h = probe(f)
            s = api("/api/uploads", {"title": title, "game": "Minecraft", "duration": dur, "width": w, "height": h, "size": f.stat().st_size}, auth=auth)
            part = api(f"/api/uploads/{s['clip']}/1?uploadId={urllib.parse.quote(s['uploadId'])}", method="PUT",
                       data=f.read_bytes(), auth=auth, ctype="application/octet-stream")
            api(f"/api/uploads/{s['clip']}/complete", {"uploadId": s["uploadId"], "parts": [part]}, auth=auth)
            thumb = tmp / f"{name}.jpg"
            run(["ffmpeg", "-loglevel", "error", "-y", "-ss", str(dur * 0.45), "-i", str(f), "-frames:v", "1", "-vf", "scale=640:-2", "-q:v", "4", str(thumb)])
            api(f"/api/clips/{s['clip']}/thumb", method="PUT", data=thumb.read_bytes(), auth=auth, ctype="image/jpeg")
            last = s["clip"]
        gallery = f"/g/{acc['account']}?t={acc['viewToken']}"
        js = tmp / "shots.js"
        js.write_text("""
const { chromium, devices } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ ...devices['iPhone 13'] });
  // the pages link their video and poster at the public address; serve those from this local copy
  await ctx.route(/^https?:\\/\\/clips\\.evadeclient\\.com\\//, r => r.continue({ url: r.request().url().replace(/^https?:\\/\\/clips\\.evadeclient\\.com/, '%s') }));
  const m = await ctx.newPage();
  for (const [path, file, seek] of %s) {
    await m.goto('%s' + path, { waitUntil: 'load' });
    await m.waitForTimeout(1500);
    await m.evaluate(async (seek) => {
      document.querySelectorAll('.rv').forEach(e => e.classList.add('in'));
      const v = document.querySelector('video');
      // Playwright's Chromium has no H.264, so show the clip's own thumbnail (its poster) in the player.
      if (v) { v.pause(); v.removeAttribute('src'); v.load(); }
      const card = document.querySelector('.clip-card');
      if (card) window.scrollTo({ top: card.getBoundingClientRect().top + scrollY - 96, behavior: 'instant' });
    }, seek);
    await m.waitForTimeout(800);
    await m.screenshot({ path: file });
  }
  await b.close();
})();
""" % (BASE, json.dumps([[gallery, str(OUT / "phone-gallery.png"), 0], [f"/c/{last}", str(OUT / "phone-clip.png"), 3.2]]), BASE))
        run(["node", str(js)], cwd=tmp, env=dict(os.environ, NODE_PATH=subprocess.run(["npm", "root", "-g"], capture_output=True, text=True).stdout.strip()))
        webp(OUT / "phone-gallery.png", "phone-gallery")
        webp(OUT / "phone-clip.png", "phone-clip")
    finally:
        dev.terminate()


if __name__ == "__main__":
    missing = [n for n, _ in SHARED if not (FOOTAGE / f"{n}.mp4").exists()]
    if missing:
        sys.exit(f"missing footage: {missing}; run scripts/film-footage.sh first")
    if "--phone-only" not in sys.argv:
        app_pages()
    OUT.mkdir(parents=True, exist_ok=True)
    if "--app-only" not in sys.argv:
        phone_pages()
