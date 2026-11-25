#!/usr/bin/env python3
"""Packages Evade Shaders (shaders/evade-shaders/) into a shader-pack zip for Iris.

Output (site/downloads/):
  "Evade Shaders v<VERSION>.zip"   versioned name players see in their shaderpacks folder
  EvadeShaders.zip                 stable name for links and the installer

The zip holds shaders/ (the programs, shaders.properties, block.properties, lang/) and LICENSE at its root, the
layout Iris expects. Zip entries get a fixed timestamp so rebuilding unchanged sources gives identical bytes.

Usage:
  python3 scripts/build-evade-shaders.py              build the zips
  python3 scripts/build-evade-shaders.py --validate   also compile every program for every quality profile with
                                                      glslangValidator (apt install glslang-tools), the way Iris
                                                      would see it (option #defines rewritten, includes expanded)
  python3 scripts/build-evade-shaders.py --validate-only
"""
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
PACK = ROOT / "shaders" / "evade-shaders"
SRC = PACK / "shaders"
OUT = ROOT / "site" / "downloads"
STAMP = (2026, 1, 1, 0, 0, 0)

# Iris' render-stage macros (values only need to be distinct for validation).
STAGES = ["NONE", "SKY", "SUNSET", "CUSTOM_SKY", "SUN", "MOON", "STARS", "VOID", "TERRAIN_SOLID",
          "TERRAIN_CUTOUT_MIPPED", "TERRAIN_CUTOUT", "ENTITIES", "BLOCK_ENTITIES", "DESTROY", "OUTLINE", "DEBUG",
          "HAND_SOLID", "TERRAIN_TRANSLUCENT", "TRIPWIRE", "PARTICLES", "CLOUDS", "RAIN_SNOW", "WORLD_BORDER",
          "HAND_TRANSLUCENT"]


def version():
    return (PACK / "VERSION").read_text().strip()


def build():
    OUT.mkdir(parents=True, exist_ok=True)
    name = f"Evade Shaders v{version()}.zip"
    target = OUT / name
    for old in OUT.glob("Evade Shaders v*.zip"):
        if old.name != name:
            old.unlink()
    files = sorted(p for p in SRC.rglob("*") if p.is_file())
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for extra in ("LICENSE",):
            info = zipfile.ZipInfo(extra, STAMP)
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, (PACK / extra).read_bytes())
        for p in files:
            info = zipfile.ZipInfo("shaders/" + p.relative_to(SRC).as_posix(), STAMP)
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, p.read_bytes())
    shutil.copyfile(target, OUT / "EvadeShaders.zip")
    print(f"{target.relative_to(ROOT)} ({target.stat().st_size // 1024} KB, {len(files)} files) + EvadeShaders.zip")


# ---------------------------------------------------------------------------------------------- validation

def profiles():
    """profile name -> {option: value} from shaders.properties (booleans as True/False)."""
    text = (SRC / "shaders.properties").read_text().replace("\\\n", " ")
    out = {}
    for line in text.splitlines():
        m = re.match(r"profile\.(\w+)\s*=\s*(.*)", line.strip())
        if not m:
            continue
        opts = {}
        for tok in m.group(2).split():
            if tok.startswith("profile."):
                opts.update(out[tok[8:]])
            elif "=" in tok:
                k, v = tok.split("=", 1)
                opts[k] = v
            elif tok.startswith("!"):
                opts[tok[1:]] = False
            else:
                opts[tok] = True
        out[m.group(1)] = opts
    return out


def apply_options(src, opts):
    """Rewrites option lines the way Iris does when a value is changed on the options screen."""
    lines = []
    for line in src.splitlines():
        m = re.match(r"^(\s*)(//)?\s*#define\s+(\w+)(\s+[^/\s]+)?(.*)$", line)
        if m and m.group(3) in opts:
            v = opts[m.group(3)]
            if v is True:
                line = f"#define {m.group(3)}"
            elif v is False:
                line = f"//#define {m.group(3)}"
            else:
                line = f"#define {m.group(3)} {v}"
        else:
            c = re.match(r"^\s*const\s+(int|float|bool)\s+(\w+)\s*=\s*([^;]+);(.*)$", line)
            if c and c.group(2) in opts:
                line = f"const {c.group(1)} {c.group(2)} = {opts[c.group(2)]};"
        lines.append(line)
    return "\n".join(lines)


def expand(path, opts, seen):
    out = []
    for line in path.read_text().splitlines():
        m = re.match(r'\s*#include\s+"([^"]+)"', line)
        if m:
            inc = m.group(1)
            target = (SRC / inc.lstrip("/")) if inc.startswith("/") else (path.parent / inc)
            out.append(expand(target, opts, seen))
        else:
            out.append(line)
    return apply_options("\n".join(out), opts)


def validate():
    exe = shutil.which("glslangValidator")
    if not exe:
        sys.exit("glslangValidator not found (apt install glslang-tools)")
    progs = sorted(p for p in SRC.glob("*.[vf]sh"))
    macros = "\n".join(f"#define MC_RENDER_STAGE_{s} {i}" for i, s in enumerate(STAGES))
    macros += "\n#define MC_VERSION 12111\n#define MC_GL_VERSION 450\n#define MC_GLSL_VERSION 450\n#define IS_IRIS\n"
    failures = 0
    with tempfile.TemporaryDirectory() as tmp:
        for prof, opts in profiles().items():
            for p in progs:
                text = expand(p, opts, set())
                first, rest = text.split("\n", 1)
                stage = "vert" if p.suffix == ".vsh" else "frag"
                f = pathlib.Path(tmp) / f"{p.stem}.{stage}"
                f.write_text(first + "\n" + macros + rest)
                r = subprocess.run([exe, "-S", stage, str(f)], capture_output=True, text=True)
                if r.returncode != 0:
                    failures += 1
                    print(f"[{prof}] {p.name}:\n" + "\n".join(
                        l for l in r.stdout.splitlines() if "ERROR" in l or "error" in l))
            print(f"[{prof}] {len(progs)} programs checked")
    if failures:
        sys.exit(f"{failures} program(s) failed to compile")
    print("all programs compile for every profile")


if __name__ == "__main__":
    if "--validate" in sys.argv or "--validate-only" in sys.argv:
        validate()
    if "--validate-only" not in sys.argv:
        build()
