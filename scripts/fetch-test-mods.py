#!/usr/bin/env python3
"""Downloads Litematica + MaLiLib (1.21.11) into libs/test-mods for the in-game Printer test.

These are other people's mods: they're only used by `runClientGameTest` (via modLocalRuntime) and are never
bundled into Evade. Checksums are verified against Modrinth.
"""
import hashlib, json, os, urllib.request

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
lit = [v for v in get(f"https://api.modrinth.com/v2/project/litematica/version?{Q}") if v["version_type"] == "release"][0]
dep = [d for d in lit["dependencies"] if d["dependency_type"] == "required"][0]
mal = get(f"https://api.modrinth.com/v2/version/{dep['version_id']}") if dep.get("version_id") else \
    [v for v in get(f"https://api.modrinth.com/v2/project/{dep['project_id']}/version?{Q}") if v["version_type"] == "release"][0]
fetch(lit)
fetch(mal)
