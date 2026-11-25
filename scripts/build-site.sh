#!/usr/bin/env bash
# Builds the mod and the installer, then copies them + version info into site/ so the download buttons
# serve the latest builds. The installer reads site/version.json to know what to install.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew build :installer:jar :installer:createExe :clips:jar :clips:createExe --no-daemon -q
VERSION=$(grep '^mod_version=' gradle.properties | cut -d= -f2)
MC=$(grep '^minecraft_version=' gradle.properties | cut -d= -f2)
JAR="build/libs/evade-client-${VERSION}.jar"
EXE="installer/build/launch4j/Evade-Installer.exe"
IJAR="installer/build/libs/EvadeInstaller.jar"
mkdir -p site/downloads
rm -f site/downloads/evade-client-[0-9]*.jar site/downloads/Evade-Texture-Pack.zip
cp "$JAR" site/downloads/evade-client-latest.jar
cp "$JAR" "site/downloads/evade-client-${VERSION}.jar"
cp "$EXE" site/downloads/Evade-Installer.exe
cp "$IJAR" site/downloads/EvadeInstaller.jar
# Evade Clips: on the main site (the installer downloads it from here) and on clips.evadeclient.com.
CJAR="clips/build/libs/EvadeClips.jar"
CEXE="clips/build/launch4j/Evade-Clips.exe"
CLIPS_VERSION=$(grep "^version = " clips/build.gradle | cut -d"'" -f2)
# The Evade Launcher's own version: set only in installer/build.gradle (the exe, jar and LauncherInfo are stamped from it).
LAUNCHER_VERSION=$(grep "^version = " installer/build.gradle | cut -d"'" -f2)
cp "$CJAR" site/downloads/EvadeClips.jar
cp "$CEXE" site/downloads/Evade-Clips.exe
mkdir -p clips-share/public/downloads
cp "$CJAR" clips-share/public/downloads/EvadeClips.jar
cp "$CEXE" clips-share/public/downloads/Evade-Clips.exe
# Evade Shaders: "Evade Shaders v<version>.zip" + a stable EvadeShaders.zip (fails the build if a program won't compile
# when glslangValidator is installed).
if command -v glslangValidator >/dev/null; then python3 scripts/build-evade-shaders.py --validate; else python3 scripts/build-evade-shaders.py; fi
SHADERS_VERSION=$(tr -d '[:space:]' < shaders/evade-shaders/VERSION)
SZIP="site/downloads/EvadeShaders.zip"
kb() { echo "$(( $(stat -c%s "$1") / 1024 )) KB"; }
sha() { sha256sum "$1" | cut -d' ' -f1; }
# ffmpeg for Evade Clips (Windows): ONE pinned build with its SHA-256 in version.json, so the launcher never installs a
# floating or unchecked build. To move to a newer build, change FFMPEG_URL and set FFMPEG_SHA256 to the sha256sum of
# the zip you checked. Left empty, it is computed from the pinned (immutable, versioned) URL once per build; if that
# download fails, version.json falls back to the builder's published checksum and the launcher fails closed without it.
FFMPEG_URL="https://github.com/GyanD/codexffmpeg/releases/download/7.1.1/ffmpeg-7.1.1-essentials_build.zip"
FFMPEG_SHA256=""
if [ -z "$FFMPEG_SHA256" ]; then
  FFMPEG_SHA256=$(curl -fsSL --max-time 300 "$FFMPEG_URL" | sha256sum | cut -d' ' -f1) || FFMPEG_SHA256=""
  [ "$FFMPEG_SHA256" = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" ] && FFMPEG_SHA256=""  # empty download
  [ -n "$FFMPEG_SHA256" ] && echo "ffmpeg pinned: set FFMPEG_SHA256=\"$FFMPEG_SHA256\" in scripts/build-site.sh after checking it"
fi
if [ -n "$FFMPEG_SHA256" ]; then
  FFMPEG_JSON="\"url\": \"${FFMPEG_URL}\", \"sha256\": \"${FFMPEG_SHA256}\""
else
  FFMPEG_JSON="\"url\": \"https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip\", \"sha256url\": \"https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip.sha256\""
fi
# Evade Server API plugin for Paper servers (needs repo.papermc.io; skipped if it can't build, keeping the last one).
if ./gradlew :server-plugin:build --no-daemon -q; then
  cp "$(ls -t server-plugin/build/libs/EvadeServerAPI-*.jar | head -1)" site/downloads/EvadeServerAPI.jar
fi
SERVER_API_JSON=""
if [ -f site/downloads/EvadeServerAPI.jar ]; then
  SERVER_API_JSON=$(printf '\n  "serverApi": { "file": "downloads/EvadeServerAPI.jar", "size": "%s", "sha256": "%s" },' "$(kb site/downloads/EvadeServerAPI.jar)" "$(sha site/downloads/EvadeServerAPI.jar)")
fi
cat > site/version.json <<JSON
{
  "version": "${VERSION}",
  "minecraft": "${MC}",
  "loader": "Fabric",
  "file": "downloads/evade-client-latest.jar",
  "size": "$(kb "$JAR")",
  "sha256": "$(sha "$JAR")",
  "installer": {
    "version": "${LAUNCHER_VERSION}",
    "windows": { "file": "downloads/Evade-Installer.exe", "size": "$(kb "$EXE")", "sha256": "$(sha "$EXE")" },
    "jar": { "file": "downloads/EvadeInstaller.jar", "size": "$(kb "$IJAR")", "sha256": "$(sha "$IJAR")" }
  },
  "clips": {
    "version": "${CLIPS_VERSION}",
    "windows": { "file": "downloads/Evade-Clips.exe", "size": "$(kb "$CEXE")", "sha256": "$(sha "$CEXE")" },
    "jar": { "file": "downloads/EvadeClips.jar", "size": "$(kb "$CJAR")", "sha256": "$(sha "$CJAR")" },
    "ffmpeg": { ${FFMPEG_JSON} }
  },${SERVER_API_JSON}
  "shaders": {
    "evade": { "file": "downloads/EvadeShaders.zip", "version": "${SHADERS_VERSION}", "size": "$(kb "$SZIP")", "sha256": "$(sha "$SZIP")" }
  },
  "date": "$(date +%F)"
}
JSON
# Release signature: with EVADE_SIGNING_KEY pointing at the OFFLINE Ed25519 key (never in this repo or on the server),
# sign version.json's exact bytes into version.json.sig. Launchers check it once ReleaseManifest.PUBLIC_KEY is set.
if [ -n "${EVADE_SIGNING_KEY:-}" ]; then
  openssl pkeyutl -sign -inkey "$EVADE_SIGNING_KEY" -rawin -in site/version.json | base64 -w0 > site/version.json.sig
  echo "version.json signed"
fi
# Refresh the site screenshots from the latest gametest run, if one is present.
if ls build/run/clientGameTest/screenshots/*_evade-menu.png >/dev/null 2>&1; then
  python3 scripts/build-shots.py
fi
cat > clips-share/public/downloads/clips.json <<JSON
{
  "version": "${CLIPS_VERSION}",
  "windows": { "file": "Evade-Clips.exe", "size": "$(kb "$CEXE")", "sha256": "$(sha "$CEXE")" },
  "jar": { "file": "EvadeClips.jar", "size": "$(kb "$CJAR")", "sha256": "$(sha "$CJAR")" }
}
JSON
python3 scripts/build-pages.py
echo "site/ updated: Evade ${VERSION} ($(kb "$JAR")), Evade Launcher ${LAUNCHER_VERSION}: installer exe $(kb "$EXE"), installer jar $(kb "$IJAR"), Evade Clips ${CLIPS_VERSION} ($(kb "$CEXE") exe, $(kb "$CJAR") jar)"
