#!/usr/bin/env bash
# Films real Minecraft gameplay (headless, frame by frame) and encodes it to build/footage/<scene>.mp4.
# Used for the Evade Clips screenshots: scripts/build-site.sh picks the clips up when they're there.
#   scripts/film-footage.sh            all scenes
#   scripts/film-footage.sh clutch     only some (clutch, crystal, bridge, pearl, totem, sunset)
#   scripts/film-footage.sh --vertical [scenes]   1080x1920 with a PvP HUD on, to build/footage-vertical/
set -euo pipefail
cd "$(dirname "$0")/.."
out=build/footage; screen=1280x720; extra=()
if [ "${1:-}" = --vertical ]; then
  shift; out=build/footage-vertical; screen=1080x1920
  extra=(-PfootageSize=1080x1920 -PfootageHud -PfootageDir="$out")
fi
only="${1:-}"
xvfb-run -a -s "-screen 0 ${screen}x24" ./gradlew runClientGameTest -Pfootage="$only" "${extra[@]}" -q
for d in "$out"/*/; do
  name=$(basename "$d")
  [ -n "$(ls "$d" 2>/dev/null)" ] || continue
  trim=""; [ "$name" = bridge ] && trim="-frames:v 120"   # ends on the knock-off
  ffmpeg -loglevel error -y -framerate 30 -i "$d/f%05d.png" \
    -f lavfi -i "anoisesrc=a=0.04:c=pink:r=48000" -shortest \
    $trim -c:v libx264 -preset slow -crf 19 -pix_fmt yuv420p -c:a aac -b:a 128k \
    -metadata:s:a:0 handler_name=Game -movflags +faststart "$out/$name.mp4"
  echo "build/footage/$name.mp4 ($(du -h "$out/$name.mp4" | cut -f1))"
done
