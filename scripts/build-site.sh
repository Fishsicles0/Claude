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
cp "$CJAR" site/downloads/EvadeClips.jar
cp "$CEXE" site/downloads/Evade-Clips.exe
mkdir -p clips-share/public/downloads
cp "$CJAR" clips-share/public/downloads/EvadeClips.jar
cp "$CEXE" clips-share/public/downloads/Evade-Clips.exe
kb() { echo "$(( $(stat -c%s "$1") / 1024 )) KB"; }
sha() { sha256sum "$1" | cut -d' ' -f1; }
cat > site/version.json <<JSON
{
  "version": "${VERSION}",
  "minecraft": "${MC}",
  "loader": "Fabric",
  "file": "downloads/evade-client-latest.jar",
  "size": "$(kb "$JAR")",
  "sha256": "$(sha "$JAR")",
  "installer": {
    "windows": { "file": "downloads/Evade-Installer.exe", "size": "$(kb "$EXE")", "sha256": "$(sha "$EXE")" },
    "jar": { "file": "downloads/EvadeInstaller.jar", "size": "$(kb "$IJAR")", "sha256": "$(sha "$IJAR")" }
  },
  "clips": {
    "version": "${CLIPS_VERSION}",
    "windows": { "file": "downloads/Evade-Clips.exe", "size": "$(kb "$CEXE")", "sha256": "$(sha "$CEXE")" },
    "jar": { "file": "downloads/EvadeClips.jar", "size": "$(kb "$CJAR")", "sha256": "$(sha "$CJAR")" },
    "ffmpeg": {
      "url": "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip",
      "sha256url": "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip.sha256"
    }
  },
  "date": "$(date +%F)"
}
JSON
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
echo "site/ updated: Evade ${VERSION} ($(kb "$JAR")), installer exe $(kb "$EXE"), installer jar $(kb "$IJAR"), Evade Clips ${CLIPS_VERSION} ($(kb "$CEXE") exe, $(kb "$CJAR") jar)"
