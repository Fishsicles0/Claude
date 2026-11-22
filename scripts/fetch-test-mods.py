#!/usr/bin/env python3
"""Downloads Simple Voice Chat (1.21.11, Fabric) into libs/test-mods so the in-game tests run Evade alongside it.

It's someone else's mod (All Rights Reserved): it's only used by `runClientGameTest` (via modLocalRuntime) and is
never bundled into Evade or hosted by us. Players get it from Modrinth through the Evade Installer.
Checksums are verified against Modrinth.
"""
import glob, hashlib, json, os, urllib.request

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
for old in glob.glob(os.path.join(OUT, "*.jar")):  # drop leftovers (e.g. the old Printer test mods)
    if "voicechat" not in os.path.basename(old):
        os.remove(old)
versions = get(f"https://api.modrinth.com/v2/project/simple-voice-chat/version?{Q}")
svc = next((v for v in versions if v["version_type"] == "release"), versions[0])
fetch(svc)
