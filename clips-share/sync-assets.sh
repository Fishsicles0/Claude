#!/usr/bin/env bash
# Copies what clips.evadeclient.com needs from the built main site and Evade Clips into the R2 bucket:
#   site/<file>  the main site's stylesheet, scripts, logo, icons and Evade Clips screenshots (served at /assets/...)
# Downloads are NOT copied: /downloads/* redirects to evadeclient.com/downloads, the one copy of every file, and
# build-clips-share.py takes sizes and checksums from site/version.json (it stops if they don't match the files).
# Run after scripts/build-site.sh. Pass --local to fill the bucket used by `wrangler dev`.
set -euo pipefail
cd "$(dirname "$0")"
python3 ../scripts/build-clips-share.py
put() { npx --yes wrangler@3 r2 object put "evade-clips/$1" --file "$2" ${LOCAL:-} >/dev/null && echo "  $1"; }
LOCAL=""; [ "${1:-}" = "--local" ] && LOCAL="--local"
for f in site.css site.js logo-mark.svg clips-library.webp clips-editor.webp clips-editor-effects.webp clips-editor-frame.webp clips-phone-gallery.webp clips-phone-clip.webp og.png; do
  put "site/$f" "../site/assets/$f"
done
for f in favicon.svg favicon-32.png apple-touch-icon.png; do put "site/$f" "../site/$f"; done
# Old copies from before downloads moved to one place: remove them so nothing can serve a stale file.
for f in clips.json EvadeClips.jar Evade-Clips.exe; do
  npx --yes wrangler@3 r2 object delete "evade-clips/dl/$f" ${LOCAL:-} >/dev/null 2>&1 && echo "  removed dl/$f" || true
done
