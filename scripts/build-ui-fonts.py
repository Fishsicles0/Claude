#!/usr/bin/env python3
"""Builds the pixel-exact Inter UI fonts in assets/evade/font/: the two TrueType files and the 48 role JSONs.

How 1.21.11 rasterises a "ttf" provider (TrueTypeFont / UploadableGlyph):
  - FT_Set_Pixel_Sizes(round(size * oversample)); advance, bearing and ascent are divided by oversample.
  - The glyph top lands at draw y + 7 - ascent, so the baseline is always at draw y + 7 + shift y, at any size.
  - "shift" becomes an FT_Set_Transform delta of (x, -y) * oversample pixels, applied after hinting.
  - The atlas is sampled NEAREST.
So a font whose oversample equals the GUI scale g puts one atlas texel on one device pixel, and FreeType's
hinting snaps stems and the x-height to whole device pixels. Both TTFs carry no bytecode (no fpgm, prep or
glyph instructions, maxSizeOfInstructions 0), so FreeType runs its autohinter on both weights and their stem
weights match.

Fonts (same Inter build, Version 4.000;git-a52131595):
  inter-medium.ttf     Inter Medium    \\ CFF sources are converted to TrueType outlines (cu2qu, max error 1 unit,
  inter-semibold.ttf   Inter SemiBold  /  reversed direction, maxp 1.0) because Minecraft only accepts TrueType.
Both are subset to UNICODES with no layout features (Minecraft does not shape text, so GSUB, GPOS, GDEF and kern
are dropped). Every other code point falls back to minecraft:default, as CJK already does. The name and OS/2
tables are kept. Inter has no Reserved Font Name, so the subset keeps its name; LICENSE-Inter-OFL.txt covers both.

Sources: the Inter OTFs in --src (default /usr/share/fonts/opentype/inter). Without them the script re-subsets
the TTFs already in the font folder in place, which is idempotent, so the JSONs can always be regenerated. The
output is deterministic (head.modified is kept), so a re-run with the same sources is byte-identical.

Roles (key, weight, pixel size at oversample 1, the vanilla matrix scale the role replaces):
  title semibold 13 1.45 | strong semibold 9 1.0 | body medium 9 1.0  | label medium 8 0.87
  small medium 7 0.78    | tiny medium 6 0.68    | caps semibold 7 0.78 | caps_sm semibold 6 0.65
For each role and GUI scale g = 1..6 it writes ui_<role>_<g>.json (a ttf provider, then minecraft:default):
  oversample = g and size = S, an integer, so size x oversample is a whole pixel size at every scale.
  shift y = Q = round((7.5 x scale - 7) x g) / g. 7.5 x scale is where matrix-scaled text puts its baseline
  today (draw y + scale x (7 + 0.5)), so existing y coordinates stay valid to within half a device pixel.
  Quantising Q to 1/g keeps FreeType's shift a whole device pixel, so a baseline never blurs across two rows.
  Halves round towards +y (down), like Java's Math.round. The arithmetic is exact (fractions), so float noise
  cannot flip a tie.
The script asserts that size x g and Q x g are integers, and replays Minecraft's float32 maths to check that
the pixel size and the 26.6 shift are whole device pixels. ui.json (the older oversample-4 body font) and
icons.json are left alone; stale ui_<role>_<g>.json files from removed roles are deleted.

Usage: python3 scripts/build-ui-fonts.py [--src DIR] [--json-only]   (needs fontTools)
"""
import argparse
import io
import json
import math
import pathlib
import struct
import sys
from fractions import Fraction

from fontTools import subset
from fontTools.pens.cu2quPen import Cu2QuPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont, newTable

FONT_DIR = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/assets/evade/font"
DEFAULT_SRC = pathlib.Path("/usr/share/fonts/opentype/inter")
NAMESPACE = "evade"

# weight -> (source OTF in --src, output TTF in FONT_DIR)
WEIGHTS = {
    "medium": ("Inter-Medium.otf", "inter-medium.ttf"),
    "semibold": ("Inter-SemiBold.otf", "inter-semibold.ttf"),
}

# Kept code points (inclusive ranges). Latin-1 + Latin Extended-A, Greek, Cyrillic, punctuation (dashes, quotes,
# bullet, ellipsis, per mille, guillemets), arrows and the trademark sign, plus the euro and minus signs.
UNICODES = [
    (0x0020, 0x007E), (0x00A0, 0x017F), (0x0370, 0x03FF), (0x0400, 0x04FF),
    (0x2010, 0x2027), (0x2030, 0x203A), (0x2190, 0x2193), (0x2122, 0x2122),
    (0x20AC, 0x20AC), (0x2212, 0x2212),
]

# key, weight, pixel size at oversample 1, vanilla scale (exact decimal strings, parsed as fractions)
ROLES = [
    ("title", "semibold", 13, "1.45"),
    ("strong", "semibold", 9, "1.0"),
    ("body", "medium", 9, "1.0"),
    ("label", "medium", 8, "0.87"),
    ("small", "medium", 7, "0.78"),
    ("tiny", "medium", 6, "0.68"),
    ("caps", "semibold", 7, "0.78"),
    ("caps_sm", "semibold", 6, "0.65"),
]
SCALES = range(1, 7)            # GUI scales 1..6; UiFont clamps to this range
GLYPH_TOP = 7                   # UploadableGlyph.getYMin() = 7 - ascent: the baseline sits at y + 7 + shift
VANILLA_BASELINE = Fraction(15, 2)  # matrix-scaled text today: baseline at y + scale * (7 + 0.5)

CU2QU_MAX_ERR = 1.0             # font units (of 2048 per em)


# ---------------------------------------------------------------------------------------------------------------
# CFF -> TrueType (the fontTools otf2ttf approach)

def otf_to_ttf(font):
    """Converts a CFF-flavoured font to TrueType outlines in place."""
    assert font.sfntVersion == "OTTO" and "CFF " in font, "not a CFF OpenType font"
    order = font.getGlyphOrder()
    glyph_set = font.getGlyphSet()
    glyphs = {}
    for name in order:
        tt_pen = TTGlyphPen(glyph_set)
        # CFF contours run counter-clockwise, TrueType ones clockwise: reverse while converting.
        glyph_set[name].draw(Cu2QuPen(tt_pen, CU2QU_MAX_ERR, reverse_direction=True))
        glyphs[name] = tt_pen.glyph()

    font["loca"] = newTable("loca")
    font["glyf"] = glyf = newTable("glyf")
    glyf.glyphOrder = order
    glyf.glyphs = glyphs
    del font["CFF "]
    if "VORG" in font:
        del font["VORG"]
    glyf.compile(font)

    hmtx = font["hmtx"]
    for name, glyph in glyf.glyphs.items():
        if hasattr(glyph, "xMin"):
            hmtx[name] = (hmtx[name][0], glyph.xMin)

    font["maxp"] = maxp = newTable("maxp")
    maxp.tableVersion = 0x00010000
    maxp.maxZones = 1
    maxp.maxTwilightPoints = 0
    maxp.maxStorage = 0
    maxp.maxFunctionDefs = 0
    maxp.maxInstructionDefs = 0
    maxp.maxStackElements = 0
    maxp.maxSizeOfInstructions = 0  # with no fpgm/prep either, FreeType autohints the face
    maxp.maxComponentElements = max((len(getattr(g, "components", [])) for g in glyphs.values()), default=0)
    maxp.compile(font)

    post = font["post"]
    post.formatType = 2.0
    post.extraNames = []
    post.mapping = {}
    post.glyphOrder = order
    font.sfntVersion = "\x00\x01\x00\x00"


def subset_font(font):
    opts = subset.Options()
    opts.unicodes = [cp for lo, hi in UNICODES for cp in range(lo, hi + 1)]
    opts.layout_features = []
    opts.layout_scripts = []
    opts.drop_tables += ["GSUB", "GPOS", "GDEF", "kern", "BASE", "JSTF", "STAT", "fvar", "gvar", "avar"]
    opts.name_IDs = ["*"]           # keep copyright, licence and URL records with the font
    opts.name_languages = ["*"]
    opts.glyph_names = False        # post format 3: Minecraft looks glyphs up by code point
    opts.notdef_outline = False     # glyph 0 is never drawn (TrueTypeFont only maps cmap entries)
    opts.hinting = False            # there is no bytecode to keep; the autohinter does the work
    opts.recalc_timestamp = False
    sub = subset.Subsetter(opts)
    sub.populate(unicodes=opts.unicodes)
    sub.subset(font)


def load_source(weight, src_dir):
    otf_name, ttf_name = WEIGHTS[weight]
    otf = src_dir / otf_name
    ttf = FONT_DIR / ttf_name
    if otf.is_file():
        font = TTFont(str(otf), recalcTimestamp=False)
        origin = str(otf)
    elif ttf.is_file():
        # Read fully into memory first: the same path is overwritten below.
        font = TTFont(io.BytesIO(ttf.read_bytes()), recalcTimestamp=False)
        origin = f"{ttf} (in place)"
    else:
        sys.exit(f"build-ui-fonts: no source for {weight}: neither {otf} nor {ttf} exists")
    if font.sfntVersion == "OTTO":
        otf_to_ttf(font)
    return font, origin


def check_font(weight, font):
    """Asserts what TrueTypeFontLoader and FreeType's autohinter path need."""
    assert font.sfntVersion == "\x00\x01\x00\x00", f"{weight}: not TrueType-flavoured"
    for tag in ("glyf", "loca", "cmap", "hmtx", "hhea", "head", "maxp", "name", "OS/2", "post"):
        assert tag in font, f"{weight}: missing {tag!r} table"
    for tag in ("CFF ", "fpgm", "prep", "cvt ", "GSUB", "GPOS", "GDEF", "kern"):
        assert tag not in font, f"{weight}: unexpected {tag!r} table"
    assert font["maxp"].tableVersion == 0x00010000, f"{weight}: maxp is not version 1.0"
    assert font["maxp"].maxSizeOfInstructions == 0, f"{weight}: maxSizeOfInstructions != 0 (no autohinting)"
    glyf = font["glyf"]
    for name in font.getGlyphOrder():
        program = getattr(glyf[name], "program", None)
        assert program is None or not program.getBytecode(), f"{weight}: glyph {name} has instructions"
    assert font.getBestCmap() is not None and any(
        st.platformID == 3 and st.platEncID in (1, 10) for st in font["cmap"].tables), f"{weight}: no Unicode cmap"
    cmap = font.getBestCmap()
    missing = [cp for cp in range(0x20, 0x7F) if cp not in cmap]
    assert not missing, f"{weight}: printable ASCII missing: {missing}"


def build_fonts(src_dir):
    built = {}
    for weight, (_, ttf_name) in WEIGHTS.items():
        font, origin = load_source(weight, src_dir)
        subset_font(font)
        check_font(weight, font)
        out = FONT_DIR / ttf_name
        font.save(str(out))
        built[weight] = TTFont(str(out))   # re-read what was written, so the checks below see the real file
        print(f"{ttf_name}: {out.stat().st_size / 1024:.1f} KiB, {len(built[weight].getGlyphOrder())} glyphs, "
              f"{len(built[weight].getBestCmap())} code points  <- {origin}")

    # Both weights must come from the same Inter build and fall back to minecraft:default for the same
    # characters, otherwise TITLE and BODY text would mix glyph sources differently.
    versions = {w: f["name"].getDebugName(5) for w, f in built.items()}
    assert len(set(versions.values())) == 1, f"weights come from different Inter builds: {versions}"
    metrics = {w: (f["head"].unitsPerEm, f["hhea"].ascent, f["hhea"].descent) for w, f in built.items()}
    assert len(set(metrics.values())) == 1, f"weights have different vertical metrics: {metrics}"
    covers = {w: set(f.getBestCmap()) for w, f in built.items()}
    assert len({frozenset(c) for c in covers.values()}) == 1, "weights cover different code points"
    print(f"both weights: {versions['medium']}, upm {metrics['medium'][0]}")
    for f in built.values():
        f.close()


# ---------------------------------------------------------------------------------------------------------------
# Role JSONs

def f32(x):
    """Rounds a Python float to IEEE single precision, like a Java float."""
    return struct.unpack("<f", struct.pack("<f", x))[0]


def java_round(x):
    """Java's Math.round(float): floor(x + 0.5)."""
    return math.floor(x + 0.5)


def fmt(v):
    """A JSON number with up to 6 decimals and at least one (13.0, 0.5, -0.333333)."""
    s = f"{float(v):.6f}".rstrip("0")
    s = s + "0" if s.endswith(".") else s
    return "0.0" if s in ("-0.0", "0.0") else s


def shift_for(scale, g):
    """Q = round((7.5 x scale - 7) x g) / g, halves rounded towards +y, in exact arithmetic."""
    target = VANILLA_BASELINE * scale - GLYPH_TOP
    k = math.floor(target * g + Fraction(1, 2))
    return Fraction(k, g)


def build_jsons():
    written = set()
    rows = []
    for key, weight, size, scale_s in ROLES:
        scale = Fraction(scale_s)
        ttf = WEIGHTS[weight][1]
        qs = []
        for g in SCALES:
            q = shift_for(scale, g)
            # The two exactness rules, on exact values ...
            assert (size * g) == int(size * g), f"{key}@{g}: size x g = {size * g} is not an integer"
            assert (q * g).denominator == 1, f"{key}@{g}: Q x g = {q * g} is not an integer"
            assert abs(GLYPH_TOP + q - VANILLA_BASELINE * scale) <= Fraction(1, 2 * g), f"{key}@{g}: baseline off"
            # ... and replayed through Minecraft's float32 maths on the numbers the JSON actually holds.
            size_txt, g_txt, q_txt = fmt(size), fmt(g), fmt(q)
            px = java_round(f32(f32(float(size_txt)) * f32(float(g_txt))))
            assert px == size * g, f"{key}@{g}: FT_Set_Pixel_Sizes gets {px}, expected {size * g}"
            dy = java_round(f32(f32(-f32(float(q_txt)) * f32(float(g_txt))) * 64.0))
            assert dy == -int(q * g) * 64, f"{key}@{g}: 26.6 shift {dy} is not {-int(q * g)} whole pixels"

            name = f"ui_{key}_{g}.json"
            text = ('{"providers":[\n'
                    f' {{"type":"ttf","file":"{NAMESPACE}:{ttf}","size":{size_txt},"oversample":{g_txt},'
                    f'"shift":[0.0,{q_txt}]}},\n'
                    ' {"type":"reference","id":"minecraft:default"}\n'
                    ']}\n')
            json.loads(text)  # well-formed
            path = FONT_DIR / name
            if not path.is_file() or path.read_text(encoding="utf-8") != text:
                path.write_text(text, encoding="utf-8")
            written.add(name)
            qs.append(q_txt)
        rows.append((key, weight, size, scale_s, qs))

    # Remove generated files of roles that no longer exist (never ui.json or icons.json).
    for path in sorted(FONT_DIR.glob("ui_*_*.json")):
        if path.name not in written and path.stem.rpartition("_")[2].isdigit():
            print(f"removing stale {path.name}")
            path.unlink()

    expected = len(ROLES) * len(SCALES)
    assert len(written) == expected, f"wrote {len(written)} JSONs, expected {expected}"
    print(f"{len(written)} role JSONs (shift y per GUI scale {SCALES.start}..{SCALES.stop - 1}):")
    for key, weight, size, scale_s, qs in rows:
        print(f"  {key:<8} {weight:<8} {size:>2}px x{scale_s:<5} " + " ".join(f"{q:>10}" for q in qs))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--src", type=pathlib.Path, default=DEFAULT_SRC, help="folder with Inter-*.otf sources")
    ap.add_argument("--json-only", action="store_true", help="only regenerate the role JSONs")
    args = ap.parse_args()
    if not args.json_only:
        build_fonts(args.src)
    build_jsons()


if __name__ == "__main__":
    main()
