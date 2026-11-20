#!/usr/bin/env python3
"""Builds the optional Evade Texture Pack (black & red) for Minecraft 1.21.11.

The pack is generated from the game's own textures (read from the Minecraft client jar that Loom downloads),
recoloured and redrawn into Evade's look, plus our own logo art. Run:

    python3 scripts/build-pack.py            # writes site/downloads/Evade-Texture-Pack.zip

It is a separate, optional download. It is not part of the Evade client jar and the installer never adds it.
"""
import colorsys
import glob
import io
import json
import os
import sys
import zipfile

from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
OUT_ZIP = os.path.join(ROOT, "site", "downloads", "Evade-Texture-Pack.zip")
PACK_VERSION = "1.0"
PACK_FORMAT = 75  # Minecraft 1.21.11 (version.json "pack_version" resource_major)

RED = (225, 29, 46)
RED_HI = (255, 65, 83)
RED_LO = (122, 13, 24)


def find_client_jar():
    hits = glob.glob(os.path.expanduser("~/.gradle/caches/fabric-loom/1.21.11/minecraft-client.jar"))
    if not hits:
        sys.exit("Minecraft 1.21.11 client jar not found. Run ./gradlew build once so Loom downloads it.")
    return hits[0]


class Vanilla:
    def __init__(self, jar):
        self.z = zipfile.ZipFile(jar)

    def img(self, path):
        return Image.open(io.BytesIO(self.z.read("assets/minecraft/textures/" + path))).convert("RGBA")

    def raw(self, path):
        return self.z.read("assets/minecraft/textures/" + path)


def lum(r, g, b):
    return 0.299 * r + 0.587 * g + 0.114 * b


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def ramp(stops, x):
    """Piecewise-linear colour ramp; stops = [(pos 0..255, (r,g,b)), ...]."""
    for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
        if x <= p1:
            return lerp(c0, c1, 0 if p1 == p0 else (x - p0) / (p1 - p0))
    return stops[-1][1]


def recolor(im, stops, accent_above=None, accent=RED, alpha_scale=1.0):
    """Map every pixel's brightness onto a colour ramp; optionally paint the brightest pixels red."""
    out = im.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            l = lum(r, g, b)
            c = accent if accent_above is not None and l >= accent_above else ramp(stops, l)
            px[x, y] = (*c, round(a * alpha_scale))
    return out


def hue_to_red(im, min_sat=0.25):
    """Shift saturated pixels to Evade red, keeping their brightness (used for blades and pearls)."""
    out = im.copy()
    px = out.load()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
            if s < min_sat:
                continue
            nr, ng, nb = colorsys.hls_to_rgb(0.985, min(0.62, l * 0.95), min(1.0, s * 1.1))
            px[x, y] = (round(nr * 255), round(ng * 255), round(nb * 255), a)
    return out


# dark interface ramp: black outline -> deep panel -> raised edge
DARK = [(0, (4, 4, 6)), (60, (12, 12, 15)), (140, (24, 24, 29)), (200, (38, 38, 46)), (255, (66, 66, 78))]
# container panels stay mid-dark so the game's grey labels remain readable
PANEL = [(0, (8, 8, 10)), (85, (34, 34, 40)), (139, (52, 52, 60)), (198, (88, 88, 98)), (255, (128, 128, 140))]


def build(v):
    files = {}

    def put(path, im):
        buf = io.BytesIO()
        im.save(buf, "PNG", optimize=True)
        files["assets/minecraft/textures/" + path] = buf.getvalue()

    def copy_meta(path):
        try:
            files["assets/minecraft/textures/" + path + ".mcmeta"] = v.raw(path + ".mcmeta")
        except KeyError:
            pass

    # --- buttons & sliders
    for name, acc in [("button", None), ("button_highlighted", 235), ("button_disabled", None),
                      ("slider", None), ("slider_highlighted", 235), ("slider_handle", None), ("slider_handle_highlighted", 235)]:
        p = f"gui/sprites/widget/{name}.png"
        im = recolor(v.img(p), DARK, accent_above=acc)
        if name == "button_disabled":
            im = recolor(v.img(p), [(0, (4, 4, 6)), (255, (30, 30, 34))])
        put(p, im)
        copy_meta(p)

    # --- hotbar: dark glass with a red selection frame
    hb = recolor(v.img("gui/sprites/hud/hotbar.png"), DARK, alpha_scale=0.92)
    put("gui/sprites/hud/hotbar.png", hb)
    for side in ("left", "right"):
        put(f"gui/sprites/hud/hotbar_offhand_{side}.png", recolor(v.img(f"gui/sprites/hud/hotbar_offhand_{side}.png"), DARK, alpha_scale=0.92))
    sel = v.img("gui/sprites/hud/hotbar_selection.png")
    sel_px = sel.load()
    for y in range(sel.height):
        for x in range(sel.width):
            r, g, b, a = sel_px[x, y]
            if a:
                sel_px[x, y] = (*(RED_HI if lum(r, g, b) > 200 else RED if lum(r, g, b) > 120 else RED_LO), a)
    put("gui/sprites/hud/hotbar_selection.png", sel)

    # --- crosshair: crisp white plus with a dark outline (15x15)
    ch = Image.new("RGBA", (15, 15), (0, 0, 0, 0))
    d = ImageDraw.Draw(ch)
    for (x0, y0, x1, y1) in [(6, 1, 8, 5), (6, 9, 8, 13), (1, 6, 5, 8), (9, 6, 13, 8)]:
        d.rectangle([x0, y0, x1, y1], fill=(0, 0, 0, 170))
    for (x0, y0, x1, y1) in [(7, 2, 7, 4), (7, 10, 7, 12), (2, 7, 4, 7), (10, 7, 12, 7)]:
        d.rectangle([x0, y0, x1, y1], fill=(255, 255, 255, 255))
    d.point((7, 7), fill=RED_HI + (255,))
    put("gui/sprites/hud/crosshair.png", ch)

    # --- tooltips: black glass, red frame
    put("gui/sprites/tooltip/background.png", recolor(v.img("gui/sprites/tooltip/background.png"), [(0, (6, 6, 8)), (255, (14, 14, 18))]))
    copy_meta("gui/sprites/tooltip/background.png")
    frame = v.img("gui/sprites/tooltip/frame.png")
    fp = frame.load()
    for y in range(frame.height):
        for x in range(frame.width):
            r, g, b, a = fp[x, y]
            if a:
                fp[x, y] = (*lerp(RED_LO, RED, min(1, lum(r, g, b) / 120)), a)
    put("gui/sprites/tooltip/frame.png", frame)
    copy_meta("gui/sprites/tooltip/frame.png")

    # --- container backgrounds
    for p in ["gui/container/inventory.png", "gui/container/generic_54.png", "gui/container/shulker_box.png",
              "gui/container/crafting_table.png", "gui/container/furnace.png", "gui/container/dispenser.png"]:
        try:
            put(p, recolor(v.img(p), PANEL))
        except KeyError:
            pass

    # --- low fire: clear the top part of every animation frame so fire doesn't cover your screen
    for p in ["block/fire_0.png", "block/fire_1.png", "block/soul_fire_0.png", "block/soul_fire_1.png"]:
        im = v.img(p)
        w = im.width
        px = im.load()
        for y in range(im.height):
            if (y % w) < int(w * 0.45):
                for x in range(w):
                    px[x, y] = (0, 0, 0, 0)
        put(p, im)
        copy_meta(p)

    # --- items: red blades and red pearls
    for p in ["item/diamond_sword.png", "item/netherite_sword.png", "item/ender_pearl.png"]:
        put(p, hue_to_red(v.img(p), min_sat=0.12 if "netherite" in p else 0.25))

    # --- title screen logo: Evade mark + wordmark, centred on the vanilla logo canvas
    title = v.img("gui/title/minecraft.png")
    W, H = title.size
    logo = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    mark = Image.open(os.path.join(ROOT, "src/main/resources/assets/evade/textures/gui/logo_mark.png")).convert("RGBA")
    word = Image.open(os.path.join(ROOT, "site/assets/logo-word.png")).convert("RGBA")
    mh = int(H * 0.86)
    mark = mark.resize((mh, mh), Image.LANCZOS)
    wh = int(H * 0.95)
    word = word.resize((int(word.width * wh / word.height), wh), Image.LANCZOS)
    total = mh + int(H * 0.05) + word.width
    if total > W:
        s = W / total
        mark = mark.resize((int(mark.width * s), int(mark.height * s)), Image.LANCZOS)
        word = word.resize((int(word.width * s), int(word.height * s)), Image.LANCZOS)
        total = W
    x0 = (W - total) // 2
    logo.alpha_composite(mark, (x0, (H - mark.height) // 2))
    logo.alpha_composite(word, (x0 + mark.width + int(H * 0.05), (H - word.height) // 2))
    put("gui/title/minecraft.png", logo)
    # the "Java Edition" tag would sit on top of our wordmark; hide it
    ed = v.img("gui/title/edition.png")
    put("gui/title/edition.png", Image.new("RGBA", ed.size, (0, 0, 0, 0)))

    # --- pack icon + metadata
    icon = Image.open(os.path.join(ROOT, "src/main/resources/assets/evade/icon.png")).convert("RGBA").resize((128, 128), Image.LANCZOS)
    buf = io.BytesIO()
    icon.save(buf, "PNG", optimize=True)
    files["pack.png"] = buf.getvalue()
    files["pack.mcmeta"] = json.dumps({
        "pack": {
            "description": "§c§lEvade§r §7Texture Pack v" + PACK_VERSION + "\n§8evadeclient.com",
            "pack_format": PACK_FORMAT,
            "min_format": PACK_FORMAT,
            "max_format": PACK_FORMAT,
        }
    }, indent=2).encode()
    files["README.txt"] = (
        "Evade Texture Pack v" + PACK_VERSION + " for Minecraft 1.21.11 (resource pack format " + str(PACK_FORMAT) + ").\n"
        "Optional companion to Evade Client: black & red menus, hotbar and tooltips, a sharp crosshair, low fire,\n"
        "red sword blades and pearls, and the Evade logo on the title screen.\n\n"
        "Install: Options > Resource Packs > Open Pack Folder, drop this zip in, then move it to the right-hand list.\n"
        "Made from Minecraft's own textures, recoloured. Not affiliated with Mojang or Microsoft.\n").encode()
    return files


def main():
    files = build(Vanilla(find_client_jar()))
    os.makedirs(os.path.dirname(OUT_ZIP), exist_ok=True)
    with zipfile.ZipFile(OUT_ZIP, "w", zipfile.ZIP_DEFLATED) as z:
        for name in sorted(files):
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))  # stable bytes between builds
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, files[name])
    print(f"wrote {os.path.relpath(OUT_ZIP, ROOT)} ({len(files)} files, {os.path.getsize(OUT_ZIP) // 1024} KB)")


if __name__ == "__main__":
    main()
