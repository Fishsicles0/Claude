#!/usr/bin/env bash
# Copies what clips.evadeclient.com needs from the built main site and Evade Clips into the R2 bucket:
#   site/<file>  the main site's stylesheet, scripts, logo, icons and Evade Clips screenshots (served at /assets/...)
#   dl/<file>    the Evade Clips downloads and clips.json (served at /downloads/...)
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
for f in clips.json EvadeClips.jar Evade-Clips.exe; do put "dl/$f" "public/downloads/$f"; done
