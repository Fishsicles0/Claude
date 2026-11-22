#!/usr/bin/env python3
"""Downloads the mods the Evade Installer can add (Simple Voice Chat + the performance mods, 1.21.11 Fabric) into
libs/test-mods, so the in-game tests run Evade alongside them.

They're other people's mods: only used by `runClientGameTest` and never bundled into Evade or hosted by us.
Players get them from Modrinth through the Evade Installer.
Pass --no-perf to test with Simple Voice Chat only.

Tried and left out (benchmarked in the gametest): ScalableLux (more stutter when loading chunks), C2ME (needs
Java 22; Minecraft 1.21.11 ships Java 21), ModernFix-mVUS (overwrites the same method as Lithium).
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
            ["sodium", "lithium", "ferrite-core", "immediatelyfast", "entityculling", "moreculling", "krypton", "badoptimizations", "sodium-extra", "fast-ip-ping", "fastquit", "iris"])
for old in glob.glob(os.path.join(OUT, "*.jar")):
    os.remove(old)
FABRIC_API = "P7dR8mSH"
queue, seen = list(PROJECTS), set()
while queue:
    slug = queue.pop(0)
    versions = get(f"https://api.modrinth.com/v2/project/{slug}/version?{Q}")
    v = next((x for x in versions if x["version_type"] == "release"), versions[0])
    if v["project_id"] in seen:
        continue
    seen.add(v["project_id"])
    fetch(v)
    for d in v["dependencies"]:  # required libraries, e.g. Cloth Config for MoreCulling
        if d["dependency_type"] == "required" and d["project_id"] not in (FABRIC_API, *seen):
            queue.append(d["project_id"])
