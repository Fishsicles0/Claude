#!/usr/bin/env python3
"""Draws the menu's line icons (white, tinted in game) into assets/evade/textures/gui/nav/, and the logo mip chain.

Icons are authored on a 96-unit grid (S) with a stroke of W units. SD scales that grid by K = 4, so each icon is
rendered on a 384 px canvas and then downsampled (premultiplied, LANCZOS) into a three-level chain:

  nav/<name>_96.png   96 x 96
  nav/<name>_48.png   48 x 48
  nav/<name>.png      24 x 24   (keeps the old name, so existing ids still resolve)

GUI textures get a single mip level, so the game picks the smallest level that is at least the icon's size in
device pixels (Icons.draw), and every file has a blur+clamp .png.mcmeta, which ReloadableTexture turns into LINEAR
filtering. Without the chain, a 24 px icon drawn at 7 to 10 px, or at GUI scale 3 and 4, drops or duplicates texels.
The icons are white masks: RGB is white under transparent texels too, so LINEAR filtering never pulls a dark fringe
into the anti-aliased edge.

New in this set: chevron_l / chevron_r (mode pickers) and settings_sm, a bolder 6-tooth gear for sizes of 10 px
or less, where the 8-tooth outline gear turns to mush.

logo_mips() derives logo_mark_64/32 and logo_word_160x48/80x24 from the existing logo_mark.png and logo_word.png
(it does not redraw them). Re-run this script whenever either logo source changes.

Usage: python3 scripts/draw-nav-icons.py   (needs Pillow)
"""
import json
import math
import pathlib
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageStat

ROOT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/assets/evade/textures/gui"
OUT = ROOT / "nav"
S, W = 96, 8          # authoring grid and stroke width, in icon units
K = 4                 # supersampling: icons render on an S*K = 384 px canvas
LEVELS = ((96, "_96"), (48, "_48"), (24, ""))
WHITE = (255, 255, 255, 255)
CLEAR = (0, 0, 0, 0)
META = json.dumps({"texture": {"blur": True, "clamp": True}})

written = []


class SD:
    """ImageDraw proxy that multiplies every coordinate, radius and width by k, so the icons below keep their
    96-unit coordinates while drawing on a k-times larger canvas. Angles and colours pass through unchanged."""

    def __init__(self, draw, k):
        self.d, self.k = draw, k

    def _xy(self, v):
        if isinstance(v, (int, float)):
            return v * self.k
        return tuple(self._xy(p) for p in v)

    def _w(self, width):
        return max(1, round(width * self.k)) if width else 0

    def line(self, xy, fill=None, width=0, joint=None):
        self.d.line(self._xy(xy), fill=fill, width=self._w(width), joint=joint)

    def ellipse(self, xy, fill=None, outline=None, width=1):
        self.d.ellipse(self._xy(xy), fill=fill, outline=outline, width=self._w(width))

    def rounded_rectangle(self, xy, radius=0, fill=None, outline=None, width=1):
        self.d.rounded_rectangle(self._xy(xy), radius=radius * self.k, fill=fill, outline=outline, width=self._w(width))

    def polygon(self, xy, fill=None, outline=None, width=1):
        self.d.polygon(self._xy(xy), fill=fill, outline=outline, width=self._w(width))

    def arc(self, xy, start, end, fill=None, width=1):
        self.d.arc(self._xy(xy), start, end, fill=fill, width=self._w(width))


def canvas():
    im = Image.new("RGBA", (S * K, S * K), CLEAR)
    return im, SD(ImageDraw.Draw(im), K)


def write(im, path):
    im.save(path, optimize=True)
    meta = path.with_name(path.name + ".mcmeta")
    meta.write_text(META + "\n")
    written.extend((path, meta))


def downsample(im, size):
    """Premultiplied LANCZOS resize: colour is weighted by coverage, so edges keep their colour."""
    return im.convert("RGBa").resize(size, Image.LANCZOS).convert("RGBA")


def save(im, name):
    """Writes name_96.png, name_48.png and name.png, each a white mask with a blur+clamp .mcmeta."""
    for px, suffix in LEVELS:
        level = downsample(im, (px, px))
        mask = Image.new("RGBA", level.size, (255, 255, 255, 0))
        mask.putalpha(level.getchannel("A"))
        write(mask, OUT / f"{name}{suffix}.png")


def round_cap(d, x, y, w=W):
    """A w-diameter dot: the round cap or joint of a stroke of width w."""
    d.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2), fill=WHITE)


# ------------------------------------------------------------------ icons

def all_mods():
    im, d = canvas()
    for x, y in [(10, 10), (52, 10), (10, 52), (52, 52)]:
        d.rounded_rectangle((x, y, x + 34, y + 34), radius=8, outline=WHITE, width=W)
    save(im, "all")


def controls():  # crosshair
    im, d = canvas()
    d.ellipse((18, 18, 78, 78), outline=WHITE, width=W)
    for a, b in [((48, 4), (48, 30)), ((48, 66), (48, 92)), ((4, 48), (30, 48)), ((66, 48), (92, 48))]:
        d.line((a, b), fill=WHITE, width=W)
    d.ellipse((42, 42, 54, 54), fill=WHITE)
    save(im, "controls")


def server():  # globe
    im, d = canvas()
    d.ellipse((8, 8, 88, 88), outline=WHITE, width=W)
    d.ellipse((30, 8, 66, 88), outline=WHITE, width=W)
    d.line((10, 48, 86, 48), fill=WHITE, width=W)
    save(im, "server")


def misc():  # three dots in a rounded square
    im, d = canvas()
    d.rounded_rectangle((8, 8, 88, 88), radius=18, outline=WHITE, width=W)
    for x in (28, 48, 68):
        d.ellipse((x - 7, 41, x + 7, 55), fill=WHITE)
    save(im, "misc")


def hud():  # screen with widgets
    im, d = canvas()
    d.rounded_rectangle((6, 14, 90, 76), radius=10, outline=WHITE, width=W)
    d.rounded_rectangle((20, 28, 44, 40), radius=3, fill=WHITE)
    d.rounded_rectangle((60, 54, 78, 64), radius=3, fill=WHITE)
    d.line((34, 88, 62, 88), fill=WHITE, width=W)
    save(im, "hud")


def visuals():  # eye
    im, d = canvas()
    pts = []
    for i in range(0, 361, 6):
        t = math.radians(i)
        pts.append((48 + 42 * math.cos(t), 48 + 26 * math.sin(t) * abs(math.sin(t)) ** 0.15))
    # Run one segment past the start so the closing point is a curved joint, not two butt ends with a seam.
    d.line(pts + pts[1:2], fill=WHITE, width=W, joint="curve")
    d.ellipse((34, 34, 62, 62), outline=WHITE, width=W)
    d.ellipse((44, 44, 52, 52), fill=WHITE)
    save(im, "visuals")


def edit_hud():  # move arrows
    im, d = canvas()
    d.line((48, 10, 48, 86), fill=WHITE, width=W)
    d.line((10, 48, 86, 48), fill=WHITE, width=W)
    for tip, a, b in [((48, 6), (36, 20), (60, 20)), ((48, 90), (36, 76), (60, 76)),
                      ((6, 48), (20, 36), (20, 60)), ((90, 48), (76, 36), (76, 60))]:
        d.polygon([tip, a, b], fill=WHITE)
    save(im, "edit")


def profiles():  # person
    im, d = canvas()
    d.ellipse((30, 8, 66, 44), outline=WHITE, width=W)
    d.arc((10, 52, 86, 128), 180, 360, fill=WHITE, width=W)
    save(im, "profiles")


def gear_teeth(d, n, phase, half_w, r_in, r_out):
    """n rectangular teeth, 2*half_w wide, from radius r_in to r_out around the icon centre."""
    for k in range(n):
        a = math.radians(phase + k * 360 / n)
        ca, sa = math.cos(a), math.sin(a)
        d.polygon([(48 + r * ca - t * sa, 48 + r * sa + t * ca)
                   for r, t in ((r_in, -half_w), (r_out, -half_w), (r_out, half_w), (r_in, half_w))], fill=WHITE)


def settings():  # gear, drawn as an outline like the others
    im, d = canvas()
    for k in range(8):
        a = math.radians(k * 45 + 22.5)
        cx, cy = 48 + 34 * math.cos(a), 48 + 34 * math.sin(a)
        r = 7
        pts = [(cx + dx * math.cos(a) - dy * math.sin(a), cy + dx * math.sin(a) + dy * math.cos(a))
               for dx, dy in [(-r, -r), (r, -r), (r, r), (-r, r)]]
        d.polygon(pts, fill=WHITE)
    d.ellipse((18, 18, 78, 78), fill=WHITE)
    d.ellipse((26, 26, 70, 70), fill=CLEAR)
    d.ellipse((38, 38, 58, 58), outline=WHITE, width=W)
    save(im, "settings")


def settings_sm():  # bolder 6-tooth gear for 10 px and below
    # A ring of stroke 12 (outer radius 30) with 6 teeth 16 wide reaching radius 44, one pointing straight up.
    # No separate hub: at 7 to 10 px a hub ring or dot merges with the rim, so the open centre is the hub hole.
    im, d = canvas()
    gear_teeth(d, 6, 90, 8, 24, 44)
    d.ellipse((18, 18, 78, 78), fill=WHITE)
    d.ellipse((30, 30, 66, 66), fill=CLEAR)
    save(im, "settings_sm")


def search():  # magnifier
    im, d = canvas()
    d.ellipse((10, 10, 66, 66), outline=WHITE, width=W)
    d.line((60, 60, 86, 86), fill=WHITE, width=W + 2)
    save(im, "search")


def close():  # x
    im, d = canvas()
    d.line((22, 22, 74, 74), fill=WHITE, width=W)
    d.line((74, 22, 22, 74), fill=WHITE, width=W)
    save(im, "close")


def reset():  # circular arrow
    im, d = canvas()
    d.arc((14, 14, 82, 82), 40, 330, fill=WHITE, width=W)
    d.polygon([(80, 10), (82, 44), (50, 34)], fill=WHITE)
    save(im, "reset")


def chevron(name, pts):
    """An open polyline with round caps at both ends and a round joint."""
    im, d = canvas()
    d.line(pts, fill=WHITE, width=W)
    for x, y in pts:
        round_cap(d, x, y)
    save(im, name)


def chevron_l():
    chevron("chevron_l", [(60, 20), (34, 48), (60, 76)])


def chevron_r():
    chevron("chevron_r", [(S - x, y) for x, y in [(60, 20), (34, 48), (60, 76)]])


ICONS = (all_mods, controls, server, misc, hud, visuals, edit_hud, profiles, settings, settings_sm, search, close,
         reset, chevron_l, chevron_r)


# ------------------------------------------------------------------ logo

def bleed(im):
    """Gives fully transparent texels the logo's colour, so LINEAR filtering blends that colour into the edge
    instead of black. Texels next to the artwork take the local average colour; the rest take the global mean."""
    a = im.getchannel("A")
    if not a.getbbox():
        return im
    sums = ImageStat.Stat(im.convert("RGBa")).sum  # premultiplied channel sums, then the alpha sum
    mean = tuple(min(255, round(c * 255 / sums[3])) for c in sums[:3])
    near = im.convert("RGBa").filter(ImageFilter.BoxBlur(2)).convert("RGBA")  # coverage-weighted local average
    rgb = Image.new("RGB", im.size, mean)
    rgb.paste(near.convert("RGB"), mask=near.getchannel("A").point(lambda v: 255 if v >= 32 else 0))
    rgb.paste(im.convert("RGB"), mask=a.point(lambda v: 255 if v else 0))
    return Image.merge("RGBA", (*rgb.split(), a))


def logo_mips():
    """Downsamples the logo sources (not redrawn) into the sizes Logo.java picks from by device pixels."""
    for name, sizes in (("logo_mark", ((64, 64), (32, 32))), ("logo_word", ((160, 48), (80, 24)))):
        src = Image.open(ROOT / f"{name}.png").convert("RGBA")
        for size in sizes:
            write(bleed(downsample(src, size)), ROOT / f"{name}_{size[0]}.png")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for f in ICONS:
        f()
    logo_mips()
    pngs = [p for p in written if p.suffix == ".png"]
    size = sum(p.stat().st_size for p in written)
    print(f"wrote {len(pngs)} PNGs + {len(written) - len(pngs)} .mcmeta ({size / 1024:.1f} KB): "
          f"{len(ICONS)} icons x {len(LEVELS)} levels -> {OUT}, 4 logo mips -> {ROOT}", file=sys.stderr)


if __name__ == "__main__":
    main()
