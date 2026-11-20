#!/usr/bin/env bash
# Builds the mod and copies the jar + version info into site/ so the download button serves the latest build.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew build --no-daemon -q
VERSION=$(grep '^mod_version=' gradle.properties | cut -d= -f2)
MC=$(grep '^minecraft_version=' gradle.properties | cut -d= -f2)
JAR="build/libs/evade-client-${VERSION}.jar"
mkdir -p site/downloads
cp "$JAR" site/downloads/evade-client-latest.jar
cp "$JAR" "site/downloads/evade-client-${VERSION}.jar"
SIZE="$(( $(stat -c%s "$JAR") / 1024 )) KB"
SHA=$(sha256sum "$JAR" | cut -d' ' -f1)
cat > site/version.json <<JSON
{ "version": "${VERSION}", "minecraft": "${MC}", "loader": "Fabric", "file": "downloads/evade-client-latest.jar", "size": "${SIZE}", "sha256": "${SHA}", "date": "$(date +%F)" }
JSON
echo "site/ updated with Evade ${VERSION} (${SIZE})"
