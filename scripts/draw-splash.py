#!/usr/bin/env python3
"""Draws the loading screen's textures into assets/evade/textures/splash/ (see gui/EvadeSplash.java, gui/SplashScene.java).

The loading screen reads these straight from the jar (the game's resources aren't loaded yet on the first load), so
they are plain PNGs without .mcmeta: EvadeSplash gives each a LINEAR, clamped sampler itself. Every texture except
the brand mark is a white mask (RGB white even under transparent texels, so LINEAR filtering never pulls a dark
fringe into an edge); the game tints it.

  mark.png        the Evade "E" mark in brand red, redrawn from site/assets/logo-mark.svg at 6 px per SVG unit,
                  with room for the motion streaks the 64-unit viewBox clips (432 x 288)
  mark_mask.png   the same shape as a white mask, its shading kept in the grey levels (monochrome mode, shine)
  word.png        the EVADE wordmark at 2x site/assets/logo-word.png (960 x 288): upscaled, then its alpha edge
                  re-sharpened, so it stays crisp on 4K screens
  glow.png        soft radial glow (Gaussian), 128 x 128
  ember.png       ember: a hot core in a soft halo, 32 x 32
  line.png        a light streak / grid line: fades out at both ends and across, 256 x 16
  vignette.png    elliptical vignette (alpha grows toward the edges), 128 x 128
  disc.png        anti-aliased disc for the progress bar's rounded ends, 64 x 64
  icon_<n>.png    window icons (16, 32, 48, 128, 256): the mark on Evade's dark rounded tile, like site/favicon.svg

Usage: python3 scripts/draw-splash.py   (needs Pillow and numpy)
"""
import math
import pathlib

import numpy as np
from PIL import Image, ImageDraw

REPO = pathlib.Path(__file__).resolve().parent.parent
OUT = REPO / "src/main/resources/assets/evade/textures/splash"
SS = 4  # supersampling for the vector shapes

# --- the mark, from site/assets/logo-mark.svg -------------------------------------------------------------------
SKEW = math.tan(math.radians(-16))
SHAPES = [  # (polygon in SVG units before the skew, fill: "eg" gradient or (rgb, opacity))
    ([(14, 10), (23, 10), (23, 54), (14, 54)], "eg"),
    ([(25, 10), (54, 10), (50, 19), (25, 19)], "eg"),
    ([(25, 45), (50, 45), (46, 54), (25, 54)], "eg"),
    ([(29, 27.5), (50, 27.5), (46.5, 36.5), (29, 36.5)], "eg"),
    ([(1, 28.5), (11, 28.5), (11, 31), (1, 31)], ((0xFF, 0x5B, 0x69), 0.75)),
    ([(-5, 33), (9, 33), (9, 35.5), (-5, 35.5)], ((0xFF, 0x5B, 0x69), 0.45)),
]
STOPS = [(0.0, (0xFF, 0x5B, 0x69)), (0.5, (0xE1, 0x1D, 0x2E)), (1.0, (0x8F, 0x0B, 0x18))]
VIEW_X0, VIEW_Y0, VIEW_W, VIEW_H = -8, 8, 72, 48   # the viewBox region we keep (the SVG's is 0..64 square)
UNIT = 6


def skew(p):
    x, y = p
    return (x + SKEW * y + 9, y)


def gradient(t):
    t = np.clip(t, 0, 1)
    out = np.zeros(t.shape + (3,))
    for (t0, c0), (t1, c1) in zip(STOPS, STOPS[1:]):
        m = (t >= t0) & (t <= t1)
        k = ((t - t0) / (t1 - t0))[..., None]
        out[m] = (np.array(c0) * (1 - k) + np.array(c1) * k)[m]
    return out


def draw_mark():
    k = UNIT * SS
    w, h = VIEW_W * k, VIEW_H * k
    acc = np.zeros((h, w, 4))  # premultiplied RGBA, 0..1
    ys, xs = np.mgrid[0:h, 0:w]
    ux = (xs + 0.5) / k + VIEW_X0  # SVG user space (after the skew)
    uy = (ys + 0.5) / k + VIEW_Y0
    for poly, fill in SHAPES:
        pts = [((skew(p)[0] - VIEW_X0) * k, (skew(p)[1] - VIEW_Y0) * k) for p in poly]
        m = Image.new("L", (w, h), 0)
        ImageDraw.Draw(m).polygon(pts, fill=255)
        a = np.asarray(m, dtype=np.float64) / 255
        if fill == "eg":
            bx0 = min(p[0] for p in poly); bx1 = max(p[0] for p in poly)
            by0 = min(p[1] for p in poly); by1 = max(p[1] for p in poly)
            lx = ux - SKEW * uy - 9  # back to the shape's own (pre-skew) space, where its bounding box lives
            t = ((lx - bx0) / (bx1 - bx0) + (uy - by0) / (by1 - by0)) / 2
            rgb = gradient(t) / 255
        else:
            rgb = np.broadcast_to(np.array(fill[0]) / 255, (h, w, 3))
            a = a * fill[1]
        acc[..., :3] = rgb * a[..., None] + acc[..., :3] * (1 - a[..., None])
        acc[..., 3] = a + acc[..., 3] * (1 - a)
    return downsample(acc, SS)


def downsample(premul, ss):
    h, w = premul.shape[0] // ss, premul.shape[1] // ss
    d = premul.reshape(h, ss, w, ss, 4).mean(axis=(1, 3))
    return d


def to_image(premul, white_rgb=False):
    a = premul[..., 3]
    rgb = np.where(a[..., None] > 1e-6, premul[..., :3] / np.maximum(a[..., None], 1e-6), 1.0)
    if white_rgb:
        rgb = np.ones_like(rgb)
    arr = np.dstack([np.clip(rgb, 0, 1), np.clip(a, 0, 1)[..., None]])
    return Image.fromarray(np.round(arr * 255).astype(np.uint8), "RGBA")


def bleed(img):
    """Under fully transparent texels, copy the colour of the nearest opaque-ish neighbour (a few passes), so LINEAR
    filtering at the edge doesn't mix in black."""
    a = np.asarray(img, dtype=np.float64)
    rgb, al = a[..., :3].copy(), a[..., 3]
    known = al > 0
    for _ in range(4):
        acc = np.zeros_like(rgb); cnt = np.zeros(al.shape)
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                sh_k = np.roll(np.roll(known, dy, 0), dx, 1)
                sh_c = np.roll(np.roll(rgb, dy, 0), dx, 1)
                acc += sh_c * sh_k[..., None]; cnt += sh_k
        fill = (~known) & (cnt > 0)
        rgb[fill] = acc[fill] / cnt[fill][..., None]
        known = known | fill
    rgb[~known] = 255
    return Image.fromarray(np.dstack([rgb, al]).round().astype(np.uint8), "RGBA")


def mark_mask(premul):
    """White mask whose grey levels keep the mark's shading: luminance relative to the brightest texel."""
    a = premul[..., 3]
    rgb = np.where(a[..., None] > 1e-6, premul[..., :3] / np.maximum(a[..., None], 1e-6), 0)
    lum = rgb @ np.array([0.2126, 0.7152, 0.0722])
    lum = lum / max(lum.max(), 1e-6)
    g = 0.62 + 0.38 * lum  # keep it light: the mask is tinted (grey in monochrome mode, white for the shine)
    g = np.where(a > 1e-6, g, 1.0)
    arr = np.dstack([g, g, g, a])
    return Image.fromarray(np.round(np.clip(arr, 0, 1) * 255).astype(np.uint8), "RGBA")


# --- the wordmark --------------------------------------------------------------------------------------------------
def draw_word():
    src = Image.open(REPO / "site/assets/logo-word.png").convert("RGBA")
    w, h = src.size
    a = np.asarray(src.getchannel("A").resize((w * 2, h * 2), Image.BICUBIC), dtype=np.float64) / 255
    a = np.clip((a - 0.5) * 2.2 + 0.5, 0, 1)  # re-sharpen: about a one-texel anti-aliased edge at the new size
    # The site wordmark is white fading to a light grey at the bottom: keep that, as grey levels.
    ys = np.linspace(0, 1, h * 2)[:, None]
    letters = np.where(a > 0)[0]
    top, bot = (letters.min(), letters.max()) if letters.size else (0, h * 2)
    t = np.clip((np.arange(h * 2)[:, None] - top) / max(1, bot - top), 0, 1)
    g = 1.0 - 0.16 * t ** 1.6
    g = np.broadcast_to(g, a.shape)
    arr = np.dstack([g, g, g, a])
    return Image.fromarray(np.round(arr * 255).astype(np.uint8), "RGBA")


# --- soft shapes -------------------------------------------------------------------------------------------------
def white(alpha):
    alpha = np.clip(alpha, 0, 1)
    arr = np.dstack([np.ones_like(alpha)] * 3 + [alpha])
    return Image.fromarray(np.round(arr * 255).astype(np.uint8), "RGBA")


def grid(n, m=None):
    m = m or n
    ys, xs = np.mgrid[0:m, 0:n]
    return (xs + 0.5) / n * 2 - 1, (ys + 0.5) / m * 2 - 1


def glow():
    x, y = grid(128)
    r2 = x * x + y * y
    a = np.exp(-r2 * 4.2) - math.exp(-4.2)  # zero at the edge circle
    return white(np.where(r2 < 1, a / (1 - math.exp(-4.2)), 0))


def ember():
    x, y = grid(32)
    r2 = x * x + y * y
    a = 0.55 * np.exp(-r2 * 7) + 0.9 * np.exp(-r2 * 60)
    a = a * np.clip(1 - r2, 0, 1) ** 0.5
    return white(a / a.max())


def line():
    x, y = grid(256, 16)
    along = np.clip(1 - np.abs(x), 0, 1) ** 1.6 * (3 - 2 * np.clip(1 - np.abs(x), 0, 1))  # long soft ends
    across = np.exp(-(y * 2.6) ** 2)
    return white(along / along.max() * across)


def vignette():
    x, y = grid(128)
    r = np.sqrt(x * x + y * y) / math.sqrt(2) * 1.18
    t = np.clip((r - 0.42) / (1.0 - 0.42), 0, 1)
    return white(t * t * (3 - 2 * t))


def disc():
    n = 64 * SS
    m = Image.new("L", (n, n), 0)
    ImageDraw.Draw(m).ellipse((SS, SS, n - SS, n - SS), fill=255)
    a = np.asarray(m.resize((64, 64), Image.BOX), dtype=np.float64) / 255
    return white(a)


def icons(mark_premul):
    mark = bleed(to_image(mark_premul))
    for size in (16, 32, 48, 128, 256):
        n = size * SS
        tile = Image.new("RGBA", (n, n), (0, 0, 0, 0))
        r = round(n * 14 / 64)
        ImageDraw.Draw(tile).rounded_rectangle((0, 0, n - 1, n - 1), radius=r, fill=(0x0B, 0x0B, 0x0E, 255))
        # The favicon's mark: its 64-unit box scaled by .84 about (5, 5); our mark texture starts at SVG (-8, 8).
        scale = n / 64 * 0.84
        mw, mh = round(VIEW_W * scale), round(VIEW_H * scale)
        mx, my = round((5 + VIEW_X0 * 0.84) * n / 64), round((5 + VIEW_Y0 * 0.84) * n / 64)
        m = mark.resize((mw, mh), Image.LANCZOS)
        layer = Image.new("RGBA", (n, n), (0, 0, 0, 0))
        layer.paste(m, (mx, my))
        clip = Image.new("L", (n, n), 0)
        ImageDraw.Draw(clip).rounded_rectangle((0, 0, n - 1, n - 1), radius=r, fill=255)
        layer.putalpha(Image.fromarray((np.asarray(layer.getchannel("A"), dtype=np.uint16)
                                        * np.asarray(clip, dtype=np.uint16) // 255).astype(np.uint8)))
        tile.alpha_composite(layer)
        save(tile.resize((size, size), Image.LANCZOS), f"icon_{size}.png")


def save(img, name):
    p = OUT / name
    img.save(p, optimize=True)
    print(f"{name:16} {img.size[0]:4} x {img.size[1]:<4} {p.stat().st_size / 1024:6.1f} KB")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    m = draw_mark()
    save(bleed(to_image(m)), "mark.png")
    save(mark_mask(m), "mark_mask.png")
    save(draw_word(), "word.png")
    save(glow(), "glow.png")
    save(ember(), "ember.png")
    save(line(), "line.png")
    save(vignette(), "vignette.png")
    save(disc(), "disc.png")
    icons(m)
    total = sum(f.stat().st_size for f in OUT.glob("*.png"))
    print(f"total {total / 1024:.1f} KB")


if __name__ == "__main__":
    main()
