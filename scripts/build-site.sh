#!/usr/bin/env bash
# Builds the mod and the installer, then copies them + version info into site/ so the download buttons
# serve the latest builds. The installer reads site/version.json to know what to install.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew build :installer:jar :installer:createExe --no-daemon -q
VERSION=$(grep '^mod_version=' gradle.properties | cut -d= -f2)
MC=$(grep '^minecraft_version=' gradle.properties | cut -d= -f2)
JAR="build/libs/evade-client-${VERSION}.jar"
EXE="installer/build/launch4j/Evade-Installer.exe"
IJAR="installer/build/libs/EvadeInstaller.jar"
mkdir -p site/downloads
python3 scripts/build-pack.py
PACK="site/downloads/Evade-Texture-Pack.zip"
PACK_VERSION=$(grep -E '^PACK_VERSION' scripts/build-pack.py | cut -d'"' -f2)
rm -f site/downloads/evade-client-[0-9]*.jar
cp "$JAR" site/downloads/evade-client-latest.jar
cp "$JAR" "site/downloads/evade-client-${VERSION}.jar"
cp "$EXE" site/downloads/Evade-Installer.exe
cp "$IJAR" site/downloads/EvadeInstaller.jar
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
  "texturePack": { "version": "${PACK_VERSION}", "file": "downloads/Evade-Texture-Pack.zip", "size": "$(kb "$PACK")", "sha256": "$(sha "$PACK")" },
  "date": "$(date +%F)"
}
JSON
python3 scripts/build-pages.py
echo "site/ updated: Evade ${VERSION} ($(kb "$JAR")), installer exe $(kb "$EXE"), installer jar $(kb "$IJAR"), texture pack $(kb "$PACK")"
