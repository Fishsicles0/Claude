#!/usr/bin/env python3
"""Downloads the mods the Evade Installer can add (Simple Voice Chat + the performance mods, 1.21.11 Fabric) into
libs/test-mods, so the in-game tests run Evade alongside them.

They're other people's mods: only used by `runClientGameTest` and never bundled into Evade or hosted by us.
Players get them from Modrinth through the Evade Installer.
Pass --no-perf to test with Simple Voice Chat only.
Checksums are verified against Modrinth.
"""
import glob, hashlib, json, os, sys, urllib.request

OUT = os.path.join(os.path.dirname(__file__), "..", "libs", "test-mods")
Q = "game_versions=%5B%221.21.11%22%5D&loaders=%5B%22fabric%22%5D"


def get(u):
    return json.load(urllib.request.urlopen(urllib.request.Request(u, headers={"User-Agent": "EvadeClient-dev"})))


def fetch(version):
    f = [x for x in version["files"] if x["primary"]][0]
    path = os.path.join(OUT, f["filename"])
    if os.path.exists(path):
        return
    data = urllib.request.urlopen(urllib.request.Request(f["url"], headers={"User-Agent": "EvadeClient-dev"})).read()
    assert hashlib.sha512(data).hexdigest() == f["hashes"]["sha512"], "checksum mismatch"
    open(path, "wb").write(data)
    print("downloaded", f["filename"])


os.makedirs(OUT, exist_ok=True)
PROJECTS = ["simple-voice-chat"] + ([] if "--no-perf" in sys.argv else
            ["sodium", "lithium", "ferrite-core", "immediatelyfast", "entityculling"])
for old in glob.glob(os.path.join(OUT, "*.jar")):
    os.remove(old)
for slug in PROJECTS:
    versions = get(f"https://api.modrinth.com/v2/project/{slug}/version?{Q}")
    fetch(next((v for v in versions if v["version_type"] == "release"), versions[0]))
