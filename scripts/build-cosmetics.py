#!/usr/bin/env python3
"""Builds Evade's cosmetics: item definitions, cuboid models, textures (animated ones too), thumbnails and the catalog.

Everything here is original Evade art, drawn procedurally (periodic fbm noise, height-map shading, rim light, dithered
gradients and hand-placed details), so the whole set can be rebuilt and tweaked from this one file. Output goes to two
places with identical bytes:

  src/main/resources/assets/evade/cosmetics/   bundled in the client jar (works offline)
  site-src/cosmetics/                          the shop website's 3D previews read the same files

Per item, in <id>/ (format: site-src/cosmetics/README.md):
  item.json     id, name, slot, rarity, colourable, defaultColor, partner, description, fx / emote / animation data
  model.json    the cuboid model (format "evade-cosmetic-model/1"); not for particle-only items or emotes
  texture.png   box-UV texture for model.json, `textureScale` texels per model unit, at most 128 x 128 per frame;
                animated (special) items stack their frames top to bottom (a vertical strip)
  emissive.png  optional: the parts that glow (full bright), same layout and frames as texture.png
  thumb.png     128 x 128 preview used by the Wardrobe grid and the shop cards

And catalog.json: every item with the sha256 of each of its files under assets.files (the client checks bundled and
downloaded files against it) plus the prices (USD cents; null = not priced yet).

Also draws the Wardrobe sidebar icon (assets/evade/textures/gui/nav/wardrobe*.png) in the menu's icon style.

Usage: python3 scripts/build-cosmetics.py [--sheet out.png]   (needs Pillow and numpy)
"""
import hashlib
import io
import json
import math
import pathlib
import shutil
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT_RES = ROOT / "src/main/resources/assets/evade/cosmetics"
OUT_SITE = ROOT / "site-src/cosmetics"
NAV = ROOT / "src/main/resources/assets/evade/textures/gui/nav"
FORMAT = "evade-cosmetic-model/1"
MAX_TEX_PX = 128       # largest texture (per frame) in pixels, each side
MAX_SCALE = 8          # most texels per model unit
THUMB = 128
RNG = np.random.default_rng(20261124)


# ============================================================================================== colour helpers

def hexc(s, a=1.0):
    s = s.lstrip("#")
    return np.array([int(s[0:2], 16) / 255, int(s[2:4], 16) / 255, int(s[4:6], 16) / 255, a], dtype=np.float32)


def mix(a, b, t):
    return a * (1 - t) + b * t


def smooth(t):
    t = np.clip(t, 0, 1)
    return t * t * (3 - 2 * t)


def ramp(t, stops):
    """A multi-stop gradient: stops = [(pos, "#hex"), ...] sorted by pos; t any shape. Returns (..., 4)."""
    t = np.asarray(t, np.float32)
    out = np.zeros(t.shape + (4,), np.float32)
    cols = [hexc(c) if isinstance(c, str) else np.asarray(c, np.float32) for _, c in stops]
    pos = [p for p, _ in stops]
    out[:] = cols[0]
    for i in range(len(stops) - 1):
        f = np.clip((t - pos[i]) / max(1e-6, pos[i + 1] - pos[i]), 0, 1)[..., None]
        seg = (t >= pos[i])[..., None]
        out = np.where(seg, mix(cols[i], cols[i + 1], f), out)
    return out


def lum(rgb):
    return rgb[..., 0] * 0.3 + rgb[..., 1] * 0.59 + rgb[..., 2] * 0.11


# ============================================================================================== noise
# Periodic (tileable) fractal noise by spectral synthesis: white noise, band-limited in the frequency domain. Periodic in
# both axes, so an animation that slides it by a whole period loops without a seam.

_NOISE = {}


def pnoise(h, w, cells=4.0, seed=0, octaves=4, gain=0.5, aspect=None):
    """Tileable fbm in 0..1 (mean ~0.5), `cells` features across the width (rows scaled by aspect h/w by default)."""
    key = (h, w, cells, seed, octaves, gain, aspect)
    if key in _NOISE:
        return _NOISE[key]
    r = np.random.default_rng(seed + 7919)
    white = np.fft.fft2(r.standard_normal((h, w)))
    fy = np.fft.fftfreq(h)[:, None] * h
    fx = np.fft.fftfreq(w)[None, :] * w
    asp = (h / w) if aspect is None else aspect
    out = np.zeros((h, w), np.float64)
    amp = 1.0
    for o in range(octaves):
        c = cells * (2 ** o)
        sig_x, sig_y = c * 0.6, c * 0.6 * asp
        filt = np.exp(-0.5 * ((fx / max(sig_x, 1e-3)) ** 2 + (fy / max(sig_y, 1e-3)) ** 2))
        filt[0, 0] = 0
        layer = np.real(np.fft.ifft2(white * filt))
        sd = layer.std() or 1
        out += amp * layer / sd
        amp *= gain
    sd = out.std() or 1
    n = np.clip(0.5 + out / sd * 0.17, 0, 1).astype(np.float32)
    _NOISE[key] = n
    return n


def hash_noise(h, w, seed):
    return np.random.default_rng(seed).random((h, w), dtype=np.float32)


BAYER = (np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]], np.float32) + 0.5) / 16 - 0.5


def to_image(arr, dither=True):
    """Float RGBA to an 8-bit image, with ordered dithering on the colour so smooth gradients don't band."""
    a = np.clip(arr, 0, 1).copy()
    if dither:
        h, w = a.shape[:2]
        d = np.tile(BAYER, (h // 4 + 1, w // 4 + 1))[:h, :w]
        a[..., :3] = a[..., :3] + d[..., None] / 255.0
    return Image.fromarray(np.clip(a * 255 + 0.5, 0, 255).astype(np.uint8), "RGBA")


# ============================================================================================== geometry

class Cube:
    def __init__(self, origin, size, paint, inflate=0.0, mirror=False):
        self.origin = [float(v) for v in origin]
        self.size = [int(v) for v in size]
        self.paint = paint          # paint(face_name, canvas) for each of the six faces
        self.inflate = inflate
        self.mirror = mirror
        self.uv = None

    def uv_size(self):
        w, h, d = self.size
        return 2 * (w + d), d + h


class Bone:
    def __init__(self, name, attach, pivot=(0, 0, 0), rotation=(0, 0, 0), cubes=(), anim=None, emissive=False,
                 tint=False, alpha=1.0):
        self.name, self.attach = name, attach
        self.pivot, self.rotation = list(pivot), list(rotation)
        self.cubes = list(cubes)
        self.anim, self.emissive, self.tint, self.alpha = anim, emissive, tint, alpha


def mirrored_bone(b, name, anim=None):
    """The left-hand copy of a right-hand bone: x negated, UVs mirrored, rotations about y and z negated."""
    cubes = [Cube((-(c.origin[0] + c.size[0]), c.origin[1], c.origin[2]), c.size, c.paint, c.inflate, not c.mirror)
             for c in b.cubes]
    for src, dst in zip(b.cubes, cubes):
        dst.share = src
    return Bone(name, b.attach, (-b.pivot[0], b.pivot[1], b.pivot[2]), (b.rotation[0], -b.rotation[1], -b.rotation[2]),
                cubes, anim if anim is not None else b.anim, b.emissive, b.tint, b.alpha)


def wing_pair(right_bones, suffix_r="_r", suffix_l="_l"):
    left = [mirrored_bone(b, b.name.replace(suffix_r, suffix_l), dict(b.anim, side=-1) if b.anim else None)
            for b in right_bones]
    return list(right_bones) + left


def pack(bones):
    """Shelf-packs every cube's box-UV rectangle into the smallest square-ish texture (sides multiples of 2).
    Mirrored copies share their original's rectangle. Returns (tw, th) in model units."""
    cubes = [c for b in bones for c in b.cubes if not hasattr(c, "share")]
    # de-duplicate identical shared painters? no: every cube gets its own net (different painters / positions)
    order = sorted(cubes, key=lambda c: (-c.uv_size()[1], -c.uv_size()[0]))
    maxw = max(c.uv_size()[0] for c in cubes)
    area = sum(c.uv_size()[0] * c.uv_size()[1] for c in cubes)
    best = None
    for width in range(maxw, max(maxw, int(area ** 0.5 * 2.2)) + 1):
        x = y = shelf = 0
        placed = []
        for c in order:
            w, h = c.uv_size()
            if x + w > width:
                x, y, shelf = 0, y + shelf, 0
            placed.append((c, x, y))
            x += w
            shelf = max(shelf, h)
        tw = width + (width % 2)
        th = y + shelf
        th += th % 2
        score = (max(tw, th), tw * th)
        if best is None or score < best[0]:
            best = (score, tw, th, placed)
    _, tw, th, placed = best
    for c, px, py in placed:
        c.uv = [px, py]
    for b in bones:
        for c in b.cubes:
            if hasattr(c, "share"):
                c.uv = c.share.uv
    return max(tw, 2), max(th, 2)


def scale_for(tw, th, cap=MAX_SCALE):
    s = min(cap, MAX_TEX_PX // max(tw, th))
    if s < 1:
        raise SystemExit(f"texture {tw}x{th} too large")
    return s


# ============================================================================================== painting

PHASE = [0.0]          # the animation phase (0..1) of the frame being painted


class Canvas:
    """One face of one cube on the texture, in texels: arr[row, col] RGBA floats (albedo) and em (emissive, glows).
    Painters draw it as seen from outside the cube, top row up (item-space models are flipped for them)."""

    def __init__(self, arr, em, face, cube, flip, scale):
        self.arr, self.em, self.face, self.cube, self.flip, self.s = arr, em, face, cube, flip, scale
        self.h, self.w = arr.shape[0], arr.shape[1]
        self.phase = PHASE[0]

    def yy(self):
        return (np.arange(self.h, dtype=np.float32)[:, None] + 0.5) / max(1, self.h) * np.ones((1, self.w), np.float32)

    def xx(self):
        return np.ones((self.h, 1), np.float32) * ((np.arange(self.w, dtype=np.float32)[None, :] + 0.5) / max(1, self.w))

    def grid(self):
        """Pixel row/col index grids."""
        return np.mgrid[0:self.h, 0:self.w].astype(np.float32)

    def units(self):
        """Row/col in model units (texel centre)."""
        y, x = self.grid()
        return (y + 0.5) / self.s, (x + 0.5) / self.s


FACE_RECTS = {
    # name: (u offset, v offset, width, height) in units of (d, w, h): MC's box layout (see ModelPart.Cuboid)
    "top": lambda w, h, d: (d, 0, w, d),            # MC "DOWN" quad: the face at the cube's smallest y
    "bottom": lambda w, h, d: (d + w, 0, w, d),
    "west": lambda w, h, d: (0, d, d, h),
    "north": lambda w, h, d: (d, d, w, h),
    "east": lambda w, h, d: (d + w, d, d, h),
    "south": lambda w, h, d: (d + w + d, d, w, h),
}


def paint_model(bones, tw, th, item_space, scale):
    img = np.zeros((th * scale, tw * scale, 4), np.float32)
    em = np.zeros_like(img)
    done = set()
    for b in bones:
        for c in b.cubes:
            if hasattr(c, "share") or id(c) in done:
                continue
            done.add(id(c))
            w, h, d = c.size
            for face, rect in FACE_RECTS.items():
                ox, oy, fw, fh = rect(w, h, d)
                if fw == 0 or fh == 0:
                    continue
                x0, y0 = (c.uv[0] + ox) * scale, (c.uv[1] + oy) * scale
                sl = (slice(y0, y0 + fh * scale), slice(x0, x0 + fw * scale))
                tmp = np.zeros((fh * scale, fw * scale, 4), np.float32)
                tem = np.zeros_like(tmp)
                cv = Canvas(tmp, tem, face, c, item_space and face not in ("top", "bottom"), scale)
                c.paint(face, cv)
                a, e = cv.arr, cv.em
                if cv.flip:
                    a, e = a[::-1], e[::-1]
                img[sl] = np.clip(a, 0, 1)
                em[sl] = np.clip(e, 0, 1)
    return img, em


# ---------------------------------------------------------------------------------------------- materials

def noise(h, w, scale=1.0, seed=None):
    r = np.random.default_rng(seed) if seed is not None else RNG
    if scale > 1:
        return pnoise(h, w, max(1.0, w / scale), seed if seed is not None else int(r.integers(1 << 30)), octaves=2)
    return r.random((h, w), dtype=np.float32)


def fill(cv, color):
    cv.arr[:] = color


def vgrad(cv, top, bottom, ease=False):
    t = cv.yy()
    if ease:
        t = smooth(t)
    cv.arr[:] = mix(top, bottom, t[..., None])


def bevel(cv, light=0.16, dark=0.22, width=None):
    """A soft raised edge: light along the top/left, shade along the bottom/right, fading over `width` texels."""
    wpx = width if width is not None else max(1, cv.s // 3)
    y, x = cv.grid()
    h, w = cv.h, cv.w
    top = np.clip(1 - y / wpx, 0, 1)
    left = np.clip(1 - x / wpx, 0, 1)
    bot = np.clip(1 - (h - 1 - y) / wpx, 0, 1)
    right = np.clip(1 - (w - 1 - x) / wpx, 0, 1)
    up = np.maximum(top, left * 0.6)
    dn = np.maximum(bot, right * 0.7)
    a = cv.arr
    a[..., :3] = np.clip(a[..., :3] + (up * light)[..., None] * (1 - a[..., :3] * 0.5), 0, 1)
    a[..., :3] *= (1 - dn * dark)[..., None]


def grain(cv, amount=0.06, scale=1.0, seed=None):
    if scale > 1:
        n = pnoise(cv.h, cv.w, max(1.0, cv.w / scale / max(1, cv.s / 2)), seed if seed is not None else 3, octaves=2) - 0.5
        n *= 2.4
    else:
        n = (np.random.default_rng(seed).random((cv.h, cv.w), dtype=np.float32) if seed is not None
             else RNG.random((cv.h, cv.w), dtype=np.float32)) - 0.5
    cv.arr[..., :3] = np.clip(cv.arr[..., :3] + n[..., None] * amount, 0, 1)


def weave(cv, amount=0.05):
    """Fine cloth twill, one thread per texel."""
    y, x = cv.grid()
    pat = (((x + y) % 4) < 2).astype(np.float32) - 0.5
    cv.arr[..., :3] = np.clip(cv.arr[..., :3] * (1 + pat[..., None] * amount * 1.6), 0, 1)


def emboss(cv, height, strength=1.0, light=(-0.6, -0.8)):
    """Lights the face as if `height` (0..1 per texel) were raised: highlights facing the light, shade away from it."""
    gy, gx = np.gradient(height.astype(np.float32))
    d = -(gx * light[0] + gy * light[1]) * strength
    cv.arr[..., :3] = np.clip(cv.arr[..., :3] * (1 + np.clip(d, -0.6, 0.6))[..., None] + np.clip(d, 0, 1)[..., None] * 0.25, 0, 1)


def ao_edges(cv, amount=0.18, width=None):
    """Soft ambient occlusion towards the face's edges."""
    wpx = width if width is not None else max(2, cv.s)
    y, x = cv.grid()
    dist = np.minimum(np.minimum(y + 0.5, cv.h - 0.5 - y), np.minimum(x + 0.5, cv.w - 0.5 - x))
    k = smooth(1 - dist / wpx)
    cv.arr[..., :3] *= (1 - k * amount)[..., None]


def specks(cv, color, density, seed, glow=False):
    r = np.random.default_rng(seed)
    m = r.random((cv.h, cv.w)) < density
    cv.arr[m] = color
    if glow:
        cv.em[m] = color


def clear(cv):
    cv.arr[:] = 0


def over(dst, src):
    """Alpha-composite src (h,w,4) over dst (h,w,4) in place."""
    a = src[..., 3:4]
    dst[..., :3] = dst[..., :3] * (1 - a) + src[..., :3] * a
    dst[..., 3:4] = dst[..., 3:4] * (1 - a) + a


def layer(cv, ss=4):
    """A supersampled PIL drawing surface for this face. Draw in face pixels * ss; call .done() to get float RGBA."""
    im = Image.new("RGBA", (cv.w * ss, cv.h * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)

    class L:
        draw, image, k = d, im, ss

        @staticmethod
        def P(x, y):           # normalised (0..1) face coords -> drawing coords
            return (x * cv.w * ss, y * cv.h * ss)

        @staticmethod
        def done(blur=0):
            out = im
            if blur:
                out = out.filter(ImageFilter.GaussianBlur(blur * ss))
            small = out.convert("RGBa").resize((cv.w, cv.h), Image.LANCZOS).convert("RGBA")
            return np.asarray(small, np.float32) / 255
    return L


def rgb255(c, a=1.0):
    c = hexc(c) if isinstance(c, str) else c
    return tuple(int(round(v * 255)) for v in c[:3]) + (int(round(a * 255)),)


def solid_paint(color, shade=True, grain_amt=0.04):
    def p(face, cv):
        fill(cv, color)
        grain(cv, grain_amt)
        if shade:
            bevel(cv)
    return p


def edge_paint(color, top_face_color=None):
    """Plain sides with a lighter top: for thin rims, straps and spars."""
    def p(face, cv):
        fill(cv, top_face_color if (top_face_color is not None and face == "top") else color)
        grain(cv, 0.05)
        bevel(cv, 0.14, 0.24)
    return p


def metal(c_hi, c_lo, spec=0.35, brushed=True):
    """Polished metal: a vertical gradient with a bright specular band and a soft reflection."""
    def p(face, cv):
        t = cv.yy()
        col = mix(hexc(c_hi), hexc(c_lo), smooth(t)[..., None])
        band = np.exp(-((t - 0.28) / 0.12) ** 2) * spec
        if face == "top":
            band = band * 0 + spec * 0.8
        col[..., :3] = np.clip(col[..., :3] + band[..., None], 0, 1)
        cv.arr[:] = col
        if brushed:
            y, x = cv.grid()
            n = hash_noise(1, cv.w, 77 + cv.w)[0][None, :]
            cv.arr[..., :3] *= (0.95 + 0.1 * n)[..., None]
        bevel(cv, 0.22, 0.3)
    return p


def glow_paint(c_core, c_edge, alpha=1.0):
    """Bright emissive core fading to its edge colour (for emissive bones)."""
    def p(face, cv):
        y, x = cv.yy(), cv.xx()
        r = np.sqrt(((x - 0.5) * 2) ** 2 + ((y - 0.5) * 2) ** 2) / 1.42
        cv.arr[:] = mix(hexc(c_core), hexc(c_edge), smooth(r)[..., None])
        cv.arr[..., 3] = alpha
    return p


# ============================================================================================== items

ITEMS = []

# Rarities (the owner's three tiers):
#   REGULAR  not animated. Normal cape sway, wing beats, pet bobbing and particle effects don't count as "animated".
#   SPECIAL  animated items (animated textures and the like). Prices are announced later: an unpriced (None) special
#            item shows "Price coming soon" in the shop and can't be bought.
#   PARTNER  partner-server items; may be unpriced (granted by the partner server only). Give them partner={id, name}.
RARITIES = ("REGULAR", "SPECIAL", "PARTNER")


def item(id, name, slot, rarity, price, desc, bones=None, colourable=False, default_color=None, fx=None,
         emote=None, view=None, item_space=False, display=None, thumb=None, partner=None, animation=None,
         scale_cap=MAX_SCALE):
    if rarity not in RARITIES:
        raise SystemExit(f"{id}: rarity must be one of {RARITIES}")
    if price is None and rarity == "REGULAR":
        raise SystemExit(f"{id}: regular items need a price")
    if animation and rarity != "SPECIAL":
        raise SystemExit(f"{id}: animated textures make an item special")
    if any(i["id"] == id for i in ITEMS):
        raise SystemExit(f"{id}: duplicate id")
    ITEMS.append(dict(id=id, name=name, slot=slot, rarity=rarity, price=price, desc=desc, bones=bones or [],
                      colourable=colourable, default_color=default_color, fx=fx, emote=emote, view=view or {},
                      item_space=item_space, display=display, thumb=thumb, partner=partner, animation=animation,
                      scale_cap=scale_cap))


# ---------------------------------------------------------------------------------------------- shared art helpers

def fshift(n, dy, dx=0.0):
    """Shifts a periodic field by a fractional number of texels (exact for tileable noise), wrapping around."""
    h, w = n.shape
    fy = np.fft.fftfreq(h)[:, None]
    fx = np.fft.fftfreq(w)[None, :]
    return np.real(np.fft.ifft2(np.fft.fft2(n) * np.exp(-2j * math.pi * (fy * dy + fx * dx)))).astype(np.float32)


def heart_poly(cx, cy, r, k=1.0, rot=0.0):
    pts = []
    for i in range(48):
        t = i / 48 * math.tau
        x = 16 * math.sin(t) ** 3
        y = -(13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t))
        x, y = x / 17 * r, y / 17 * r
        ca, sa = math.cos(rot), math.sin(rot)
        pts.append(((cx + x * ca - y * sa) * k, (cy + x * sa + y * ca) * k))
    return pts


def star_poly(cx, cy, r_out, r_in, n=5, rot=-math.pi / 2, k=1.0):
    pts = []
    for i in range(n * 2):
        r = r_out if i % 2 == 0 else r_in
        a = rot + i * math.pi / n
        pts.append(((cx + math.cos(a) * r) * k, (cy + math.sin(a) * r) * k))
    return pts


def glint(cv, x, y, size, color, strength=1.0):
    """A four-point star of light (centre x, y in texels) into the emissive layer, with a small bright core."""
    yy, xx = cv.grid()
    dx, dy = np.abs(xx - x), np.abs(yy - y)
    core = np.exp(-(dx ** 2 + dy ** 2) / max(0.3, size * 0.18) ** 2)
    rays = np.exp(-dy ** 2 / 0.35) * np.clip(1 - dx / (size * 1.2), 0, 1) + np.exp(-dx ** 2 / 0.35) * np.clip(1 - dy / (size * 1.2), 0, 1)
    a = np.clip(core + rays * 0.6, 0, 1) * strength
    c = hexc(color) if isinstance(color, str) else color
    e = cv.em
    e[..., :3] = e[..., :3] * (1 - a[..., None]) + c[:3] * a[..., None]
    e[..., 3] = np.maximum(e[..., 3], a)
    cv.arr[..., :3] = cv.arr[..., :3] * (1 - a[..., None] * 0.8) + c[:3] * a[..., None] * 0.8


def em_from(cv, mask, color, alpha=1.0):
    c = hexc(color) if isinstance(color, str) else color
    a = np.clip(mask, 0, 1) * alpha
    e = cv.em
    e[..., :3] = e[..., :3] * (1 - a[..., None]) + np.broadcast_to(c[:3], e[..., :3].shape) * a[..., None]
    e[..., 3] = np.maximum(e[..., 3], a)


def em_rgb(cv, rgb, a):
    e = cv.em
    a = np.clip(a, 0, 1)
    e[..., :3] = e[..., :3] * (1 - a[..., None]) + rgb[..., :3] * a[..., None]
    e[..., 3] = np.maximum(e[..., 3], a)


def stitch_row(cv, yrow, color, every=None, alpha=0.9):
    """A row of small stitches (dashes) at pixel row yrow."""
    every = every or max(2, cv.s)
    x = np.arange(cv.w)
    m = (x % every) < max(1, every // 2)
    c = hexc(color)
    if 0 <= yrow < cv.h:
        cv.arr[yrow, m, :3] = cv.arr[yrow, m, :3] * (1 - alpha) + c[:3] * alpha


def cloth(cv, folds=0.08, freq=2.6, seed=1, shoulder=0.22):
    """Fabric light: soft vertical folds that deepen towards the hem, a shadow under the shoulders, fine weave."""
    t, u = cv.yy(), cv.xx()
    n = pnoise(cv.h, cv.w, 2.0, seed, octaves=2)
    f = np.sin(math.tau * (u * freq + 0.25 * np.sin(t * 3.1) + (n - 0.5) * 0.5))
    k = 1 + folds * f * (0.35 + 0.65 * t)
    k *= 1 - shoulder * np.exp(-t * 18)
    cv.arr[..., :3] = np.clip(cv.arr[..., :3] * k[..., None], 0, 1)
    weave(cv, 0.025)


def cape_bones(outer, lining_fn, tint=False, collar=None):
    """A vanilla-sized cape (10 x 16 x 1) hung from the shoulders, swinging like a vanilla cape. `outer` paints the
    side people see (and may light the emissive layer); edges take the outer's own edge colours, darkened."""
    def p(face, cv):
        if face == "north":
            outer(cv)
        elif face == "south":
            lining_fn(cv)
        else:
            ref = Canvas(np.zeros((16 * cv.s, 10 * cv.s, 4), np.float32), np.zeros((16 * cv.s, 10 * cv.s, 4), np.float32),
                         "north", cv.cube, False, cv.s)
            outer(ref)
            if face in ("east", "west"):
                colm = ref.arr[:, :1] if face == "west" else ref.arr[:, -1:]
                cv.arr[:] = np.repeat(colm, cv.w, 1)[:cv.h]
            else:
                row = ref.arr[:1] if face == "top" else ref.arr[-1:]
                cv.arr[:] = np.repeat(row, cv.h, 0)[:, :cv.w]
            cv.arr[..., :3] *= 0.7
            cv.arr[..., 3] = 1
    cubes = [Cube((-5, 0, 1), (10, 16, 1), p)]
    bones = [Bone("cape", "body", (0, 0, 2), cubes=cubes, anim={"type": "cape"}, tint=tint)]
    if collar is not None:
        bones.append(Bone("collar", "body", (0, 0, 2), cubes=[Cube((-5.5, -1, 0.5), (11, 2, 2), collar)],
                          anim={"type": "cape"}, tint=tint))
    return bones


def lining(color_top, color_bottom, quilt=None):
    def p(cv):
        vgrad(cv, hexc(color_top), hexc(color_bottom))
        if quilt:
            y, x = cv.units()
            d = (np.abs(((x + y) % 3) - 1.5) < 0.12) | (np.abs(((x - y) % 3) - 1.5) < 0.12)
            cv.arr[d, :3] = cv.arr[d, :3] * 0.8 + hexc(quilt)[:3] * 0.2
        weave(cv, 0.04)
        cloth(cv, 0.05, seed=9, shoulder=0.1)
        bevel(cv, 0.06, 0.14)
    return p


def trim(cv, top_px, bottom_px, color, stitch=None):
    c = hexc(color)
    if top_px:
        g = mix(c, c * 0.62, np.linspace(0, 1, top_px)[:, None, None])
        cv.arr[:top_px, :, :3] = g[..., :3]
    if bottom_px:
        g = mix(c * 1.1, c * 0.55, np.linspace(0, 1, bottom_px)[:, None, None])
        cv.arr[-bottom_px:, :, :3] = np.clip(g[..., :3], 0, 1)
    if stitch:
        if top_px:
            stitch_row(cv, top_px + 1, stitch)
        if bottom_px:
            stitch_row(cv, cv.h - bottom_px - 2, stitch)


CAPE_VIEW = {"yaw": 198, "pitch": 8}


# ---------------------------------------------------------------------------------------------- capes: flame

def flame_field(cv, seed, speed=0.0):
    """0..1 heat rising from the hem; with speed, the noise scrolls upward (a whole number of periods per loop)."""
    t = cv.yy()
    n1 = pnoise(cv.h, cv.w, 2.5, seed, octaves=4)
    n2 = pnoise(cv.h, cv.w, 5.0, seed + 1, octaves=3)
    if speed:
        n1 = fshift(n1, -cv.phase * cv.h * speed)
        n2 = fshift(n2, -cv.phase * cv.h * speed * 2)
    f = t + (n1 - 0.5) * 0.75 + (n2 - 0.5) * 0.3
    return np.clip((f - 0.42) / 0.55, 0, 1)


FIRE = [(0.0, "#1a1214"), (0.18, "#2c1214"), (0.36, "#6a160f"), (0.55, "#c4300e"), (0.72, "#ff6a1a"),
        (0.86, "#ffb23a"), (1.0, "#fff1b8")]


def ember_outer(cv, speed=0.0, seed=11):
    heat = flame_field(cv, seed, speed)
    cv.arr[:] = ramp(heat, FIRE)
    cloth(cv, 0.06, seed=2)
    # embers drifting above the flames
    r = np.random.default_rng(5)
    y, x = cv.grid()
    for _ in range(26):
        ex, ey = r.random() * cv.w, (0.25 + r.random() * 0.6) * cv.h
        if speed:
            ey = (ey - cv.phase * cv.h * 0.5 * speed) % cv.h
        s = 0.5 + r.random() * 0.9
        m = np.exp(-((x - ex) ** 2 + (y - ey) ** 2) / (s * s))
        em_from(cv, m * (0.4 + 0.6 * (ey / cv.h)), "#ffcf6a")
    em_from(cv, smooth((heat - 0.62) / 0.3), "#ff9a3a", 0.85)
    em_rgb(cv, ramp(heat, FIRE), smooth((heat - 0.72) / 0.2) * 0.9)
    trim(cv, cv.s // 2 + 1, cv.s // 2 + 1, "#3a2418", stitch="#d9a441")
    bevel(cv, 0.05, 0.12)


item("ember_mantle", "Ember Mantle", "CAPE", "REGULAR", 600,
     "A charcoal cape that smoulders into molten orange at the hem, with embers that glow in the dark.",
     cape_bones(ember_outer, lining("#2a1412", "#120707", "#5a2a1a")), view=CAPE_VIEW)

item("hearthfire_cape", "Hearthfire Cape", "CAPE", "SPECIAL", None,
     "Living flames climb and flicker up from the hem, throwing off embers that rise and glow in the dark.",
     cape_bones(lambda cv: ember_outer(cv, speed=1.0, seed=31), lining("#2a1412", "#120707", "#5a2a1a")),
     view=CAPE_VIEW, animation={"frames": 16, "frametime": 2, "interpolate": True})


# ---------------------------------------------------------------------------------------------- capes: water

def tidal_outer(cv, flow=0.0):
    t, u = cv.yy(), cv.xx()
    ph = cv.phase * math.tau * flow
    cv.arr[:] = ramp(t, [(0, "#0e4a72"), (0.45, "#0a3254"), (1, "#041628")])
    n = pnoise(cv.h, cv.w, 3.0, 41, octaves=3)
    if flow:
        n = fshift(n, 0, cv.phase * cv.w * flow)
    foam_em = np.zeros_like(t)
    for i, (y0, amp, fq, p0) in enumerate([(0.22, 0.035, 1.5, 0.0), (0.42, 0.045, 1.2, 1.7), (0.62, 0.05, 1.0, 3.1),
                                            (0.82, 0.055, 0.85, 4.4)]):
        crest = y0 + np.sin(u * math.tau * fq + p0 + ph * (1 if i % 2 else -1)) * amp + (n - 0.5) * 0.04
        d = t - crest
        body = smooth(1 - np.clip(d, 0, 1) / 0.16) * (d >= 0)
        cv.arr[..., :3] = mix(cv.arr[..., :3], hexc("#1f8fc0")[:3], (body * 0.55)[..., None])
        cv.arr[..., :3] = mix(cv.arr[..., :3], hexc("#4fc6e6")[:3], (body * smooth(1 - d / 0.05) * (d >= 0) * 0.5)[..., None])
        foam = np.exp(-(d / 0.018) ** 2) * (0.6 + 0.8 * (n > 0.45))
        curl = np.exp(-((d + 0.025) / 0.012) ** 2) * (np.sin(u * math.tau * fq * 5 + p0 + ph * 3) > 0.55)
        f = np.clip(foam + curl * 0.8, 0, 1)
        cv.arr[..., :3] = mix(cv.arr[..., :3], hexc("#e8fbff")[:3], (f * 0.92)[..., None])
        foam_em = np.maximum(foam_em, f)
    # spray and glints
    r = np.random.default_rng(7)
    y, x = cv.grid()
    for _ in range(18):
        gx, gy = r.random() * cv.w, r.random() * cv.h
        a = np.exp(-((x - gx) ** 2 + (y - gy) ** 2) / 0.5) * 0.7
        cv.arr[..., :3] = mix(cv.arr[..., :3], np.ones(3, np.float32), a[..., None])
    if flow:
        em_from(cv, foam_em * 0.45, "#bff4ff")
    cloth(cv, 0.06, seed=3)
    trim(cv, cv.s, 0, "#e3cf98")
    # rope twist on the collar
    yy, xx = cv.grid()
    rope = (((xx + yy) % 4) < 2) & (yy < cv.s)
    cv.arr[rope, :3] *= 0.82
    bevel(cv, 0.05, 0.12)


item("tidal_weave", "Tidal Weave", "CAPE", "REGULAR", 500,
     "Deep-sea blue with rolling waves of foam and a sand-gold rope collar.",
     cape_bones(tidal_outer, lining("#0d2a40", "#061624", "#1a4a66")), view=CAPE_VIEW)

item("riptide_cape", "Riptide Cape", "CAPE", "SPECIAL", None,
     "Waves roll across the cape for real: crests rise and fall, foam glitters and the water never stops moving.",
     cape_bones(lambda cv: tidal_outer(cv, flow=1.0), lining("#0d2a40", "#061624", "#1a4a66")),
     view=CAPE_VIEW, animation={"frames": 16, "frametime": 2, "interpolate": True})


# ---------------------------------------------------------------------------------------------- capes: night sky

def star_field(cv, seed, density=0.012, twinkle=0.0, colors=("#ffffff", "#cfd8ff", "#ffe9c2")):
    r = np.random.default_rng(seed)
    n = int(cv.w * cv.h * density)
    y, x = cv.grid()
    for i in range(n):
        sx, sy = r.random() * cv.w, r.random() * cv.h
        size = r.choice([0.5, 0.6, 0.8, 1.2, 1.9], p=[0.4, 0.25, 0.18, 0.12, 0.05])
        ph = r.random()
        b = 1.0
        if twinkle:
            b = 0.35 + 0.65 * (0.5 + 0.5 * math.cos(math.tau * (cv.phase * twinkle + ph)))
        col = colors[i % len(colors)]
        if size > 1.5:
            glint(cv, sx, sy, size * 2.2, col, b)
        else:
            m = np.exp(-((x - sx) ** 2 + (y - sy) ** 2) / (size * size)) * b
            em_from(cv, m, col)
            cv.arr[..., :3] = mix(cv.arr[..., :3], hexc(col)[:3], np.clip(m, 0, 1)[..., None] * 0.8)


def starlit_outer(cv):
    t, u = cv.yy(), cv.xx()
    cv.arr[:] = ramp(t, [(0, "#090825"), (0.5, "#1c1450"), (1, "#3a1f6e")])
    n = pnoise(cv.h, cv.w, 2.5, 3, octaves=5)
    n2 = pnoise(cv.h, cv.w, 4.0, 4, octaves=4)
    band = smooth(1 - np.abs(t - 0.58 - (u - 0.5) * 0.45 - (n - 0.5) * 0.35) / 0.25)
    neb = band * smooth((n2 - 0.3) / 0.5)
    cv.arr[..., :3] = mix(cv.arr[..., :3], hexc("#b04fc9")[:3], (neb * 0.55)[..., None])
    cv.arr[..., :3] = mix(cv.arr[..., :3], hexc("#4f7bff")[:3], (band * (1 - n2) * 0.35)[..., None])
    cv.arr[..., :3] = mix(cv.arr[..., :3], hexc("#ffd2f4")[:3], (smooth((neb - 0.6) / 0.4) * 0.5)[..., None])
    cloth(cv, 0.05, seed=4)
    star_field(cv, 21, 0.012)
    # a crescent moon
    L = layer(cv)
    k = L.k
    cx, cy, R = cv.w * 0.7, cv.h * 0.16, cv.w * 0.13
    L.draw.ellipse(((cx - R) * k, (cy - R) * k, (cx + R) * k, (cy + R) * k), fill=(255, 241, 194, 255))
    L.draw.ellipse(((cx - R + R * 0.55) * k, (cy - R - R * 0.2) * k, (cx + R + R * 0.55) * k, (cy + R - R * 0.2) * k), fill=(0, 0, 0, 0))
    moon = L.done()
    over(cv.arr, moon)
    em_rgb(cv, moon, moon[..., 3])
    trim(cv, cv.s // 2, cv.s, "#c9a64a", stitch="#fff1c2")
    bevel(cv, 0.04, 0.1)


item("starlit_veil", "Starlit Veil", "CAPE", "REGULAR", 800,
     "A night sky with a violet nebula, a crescent moon and stars that shine in the dark.",
     cape_bones(starlit_outer, lining("#1a1440", "#0b0a1e", "#3a2a7a")), view=CAPE_VIEW)


def galaxy_outer(cv):
    """A spiral galaxy whose arms turn and whose dust drifts; stars twinkle."""
    h, w = cv.h, cv.w
    y, x = cv.grid()
    cx, cy = w * 0.5, h * 0.52
    dx, dy = (x - cx) / w, (y - cy) / w
    r = np.sqrt(dx * dx + dy * dy)
    ang = np.arctan2(dy, dx)
    n = pnoise(h, w, 3.0, 61, octaves=5)
    n = fshift(n, cv.phase * h, cv.phase * w)
    swirl = ang - r * 9.0 + cv.phase * math.tau / 2           # two arms: a half turn per loop looks the same
    arms = (0.5 + 0.5 * np.cos(swirl * 2)) ** 2.2 * np.exp(-r * 2.8)
    dust = arms * (0.55 + 0.9 * (n - 0.5))
    base = ramp(y / h, [(0, "#05041a"), (0.5, "#0d0a2e"), (1, "#1a0c3a")])
    col = base.copy()
    col[..., :3] = mix(col[..., :3], hexc("#6a3cff")[:3], np.clip(dust * 1.6, 0, 1)[..., None] * 0.8)
    col[..., :3] = mix(col[..., :3], hexc("#ff5fd2")[:3], np.clip((dust - 0.25) * 2.2, 0, 1)[..., None] * 0.6)
    core = np.exp(-(r / 0.09) ** 2)
    col[..., :3] = mix(col[..., :3], hexc("#fff0d8")[:3], core[..., None])
    cv.arr[:] = col
    em_rgb(cv, col, np.clip(dust * 1.4 + core, 0, 1) * 0.85)
    cloth(cv, 0.04, seed=6)
    star_field(cv, 77, 0.016, twinkle=1.0)
    trim(cv, cv.s // 2, cv.s // 2 + 1, "#8f7ad8", stitch="#e9e2ff")
    bevel(cv, 0.04, 0.1)


item("galaxy_flow_cape", "Galaxy Flow Cape", "CAPE", "SPECIAL", None,
     "A whole spiral galaxy on your back: its glowing arms slowly turn, star dust drifts and the stars twinkle.",
     cape_bones(galaxy_outer, lining("#120c30", "#06051a", "#2a1f66")), view=CAPE_VIEW,
     animation={"frames": 24, "frametime": 2, "interpolate": True})


def aurora_outer(cv):
    """Night over snowy peaks, with curtains of green and violet light rippling across the sky."""
    h, w = cv.h, cv.w
    t, u = cv.yy(), cv.xx()
    cv.arr[:] = ramp(t, [(0, "#020a18"), (0.55, "#06203a"), (1, "#0b2c44")])
    ph = cv.phase * math.tau
    n = pnoise(h, w, 2.0, 91, octaves=3)
    total = np.zeros((h, w), np.float32)
    col = np.zeros((h, w, 3), np.float32)
    for k, (y0, c, sp) in enumerate([(0.28, "#38ff9c", 1), (0.42, "#2fd6ff", -1), (0.2, "#b56bff", 2)]):
        wave = y0 + 0.07 * np.sin(u * math.tau * (1.2 + k * 0.4) + ph * sp + k) + (n - 0.5) * 0.08
        d = t - wave
        curtain = np.exp(-(np.clip(d, 0, None) / 0.22)) * (d > -0.015) * smooth((d + 0.015) / 0.02)
        rays = 0.55 + 0.45 * np.sin(u * math.tau * 9 + np.sin(u * 7 + ph * sp) * 2 + k * 2)
        a = curtain * rays * (0.85 - k * 0.15)
        total = np.maximum(total, a)
        col += hexc(c)[:3] * a[..., None]
    col = np.clip(col, 0, 1)
    cv.arr[..., :3] = np.clip(cv.arr[..., :3] + col * 0.9, 0, 1)
    em_rgb(cv, np.concatenate([col / np.maximum(total[..., None], 1e-3), np.ones((h, w, 1), np.float32)], -1), total * 0.8)
    star_field(cv, 131, 0.006, twinkle=1.0)
    # mountains with snowy caps
    ridge = 0.78 + 0.08 * np.sin(u * 9.0) * np.cos(u * 3.3) + 0.04 * np.sin(u * 23)
    m = t > ridge
    snow = m & (t < ridge + 0.035 + 0.02 * np.sin(u * 31))
    cv.arr[m, :3] = mix(hexc("#0c1a2a"), hexc("#16324a"), 0.5)[:3]
    cv.arr[snow, :3] = hexc("#cfe9ff")[:3]
    cloth(cv, 0.05, seed=8)
    trim(cv, cv.s // 2, cv.s // 2 + 1, "#a9c7d8", stitch="#e8fbff")
    bevel(cv, 0.04, 0.1)


item("polar_lights_cape", "Polar Lights Cape", "CAPE", "SPECIAL", None,
     "Curtains of green, cyan and violet light ripple over snowy peaks, glowing softly in the dark.",
     cape_bones(aurora_outer, lining("#0b1e30", "#040c18", "#1a3a5a")), view=CAPE_VIEW,
     animation={"frames": 24, "frametime": 2, "interpolate": True})


# ---------------------------------------------------------------------------------------------- capes: patterns

def chevron_outer(cv):
    # light greys so the player's colour (tint) shows true; darker chevrons carry the pattern
    t, x = cv.yy(), cv.xx()
    cv.arr[:] = ramp(t, [(0, "#f4f4f4"), (1, "#c4c4c4")])
    height = np.zeros_like(t)
    for k in range(3):
        y0 = 0.30 + k * 0.19
        v = y0 - np.abs(x - 0.5) * 0.45
        band = smooth((t - v) / 0.012) * smooth((v + 0.075 - t) / 0.012)
        height = np.maximum(height, band)
        cv.arr[..., :3] = mix(cv.arr[..., :3], hexc("#5a5a5a")[:3], band[..., None])
    emboss(cv, height, 1.4)
    cloth(cv, 0.07, seed=5)
    trim(cv, cv.s // 2, cv.s, "#7a7a7a", stitch="#f0f0f0")
    bevel(cv, 0.05, 0.12)


item("chevron_standard", "Chevron Standard", "CAPE", "REGULAR", 400,
     "A clean banner cape with three raised chevrons and a stitched hem, in any colour you like.",
     cape_bones(chevron_outer, lining("#a4a4a4", "#7c7c7c"), tint=True), colourable=True, default_color="#E11D2E",
     view=CAPE_VIEW)


def checker_outer(cv):
    y, x = cv.units()
    sq = 2.0
    chk = ((np.floor(x / sq) + np.floor((y - 1) / sq)) % 2).astype(np.float32)
    t, u = cv.yy(), cv.xx()
    cv.arr[:] = mix(hexc("#f2f2f2"), hexc("#141418"), chk[..., None])
    # the flag ripples: diagonal light and shade bands
    rip = np.sin(math.tau * (u * 1.4 + t * 0.9)) * 0.13
    cv.arr[..., :3] = np.clip(cv.arr[..., :3] * (1 + rip)[..., None] + (np.clip(rip, 0, 1) * 0.15)[..., None], 0, 1)
    # a red pole stripe down the left and a top band
    stripe = x < 1.0
    cv.arr[stripe, :3] = mix(hexc("#e11d2e"), hexc("#7a0c16"), t[stripe][..., None] * 0.8)[..., :3]
    cloth(cv, 0.05, seed=12)
    trim(cv, cv.s // 2, cv.s // 2, "#2a2a30")
    bevel(cv, 0.05, 0.12)


item("checkered_rally", "Checkered Rally", "CAPE", "REGULAR", 400,
     "A rippling black-and-white chequered flag with a red stripe, for whoever crosses the line first.",
     cape_bones(checker_outer, lining("#2a2a30", "#141418")), view=CAPE_VIEW)


def plaid_outer(cv):
    y, x = cv.units()
    t = cv.yy()

    def bands(v):
        m = v % 5.0
        return 0.55 * (np.abs(m - 1.2) < 0.9) + 0.35 * (np.abs(m - 3.6) < 0.25)
    hx, hy = bands(x), bands(y + 0.6)
    g = 0.95 - 0.45 * np.clip(hx + hy, 0, 1.2) / 1.2
    # the twill: cross threads alternate which stripe shows
    yy, xx = cv.grid()
    tw = ((xx + yy) % 4 < 2)
    g = np.where(tw, 0.95 - 0.45 * np.clip(hx * 1.2 + hy * 0.8, 0, 1.2) / 1.2, g)
    thin = (np.abs((x % 5.0) - 3.6) < 0.08) | (np.abs(((y + 0.6) % 5.0) - 3.6) < 0.08)
    g = np.where(thin, 0.98, g)
    cv.arr[..., :3] = g[..., None]
    cv.arr[..., 3] = 1
    cv.arr[..., :3] *= (1 - 0.12 * t)[..., None]
    cloth(cv, 0.06, seed=13)
    trim(cv, 0, cv.s // 2 + 1, "#6a6a6a")
    # fringe at the hem
    fr = (yy >= cv.h - cv.s // 2) & ((xx % 2) == 1)
    cv.arr[fr, 3] = 0
    bevel(cv, 0.05, 0.1)


item("highland_plaid", "Highland Plaid", "CAPE", "REGULAR", 400,
     "A woven tartan cape with a fringed hem, in any colour you like.",
     cape_bones(plaid_outer, lining("#9a9a9a", "#747474"), tint=True), colourable=True, default_color="#2E7D4F",
     view=CAPE_VIEW)


def blossom_outer(cv):
    t, u = cv.yy(), cv.xx()
    cv.arr[:] = ramp(t, [(0, "#ffe3d6"), (0.45, "#f6b8c8"), (1, "#8a6aa8")])
    n = pnoise(cv.h, cv.w, 2.0, 71, octaves=3)
    cv.arr[..., :3] = np.clip(cv.arr[..., :3] + ((n - 0.5) * 0.08)[..., None], 0, 1)
    L = layer(cv)
    k = L.k
    d = L.draw
    # a dark branch from the upper left with twigs
    pts = [(-2, cv.h * 0.12), (cv.w * 0.25, cv.h * 0.2), (cv.w * 0.5, cv.h * 0.17), (cv.w * 0.78, cv.h * 0.3), (cv.w + 2, cv.h * 0.27)]
    for i in range(len(pts) - 1):
        wd = max(1, int((3.2 - i * 0.6) * k))
        d.line([(pts[i][0] * k, pts[i][1] * k), (pts[i + 1][0] * k, pts[i + 1][1] * k)], fill=(58, 34, 40, 255), width=wd)
    for (x0, y0, x1, y1) in [(0.25, 0.2, 0.32, 0.34), (0.5, 0.17, 0.46, 0.06), (0.62, 0.22, 0.7, 0.42), (0.78, 0.3, 0.86, 0.4)]:
        d.line([(x0 * cv.w * k, y0 * cv.h * k), (x1 * cv.w * k, y1 * cv.h * k)], fill=(58, 34, 40, 255), width=max(1, int(1.2 * k)))
    r = np.random.default_rng(4)
    blooms = [(0.32, 0.34), (0.46, 0.06), (0.7, 0.42), (0.86, 0.4), (0.18, 0.16), (0.58, 0.2), (0.38, 0.22),
              (0.9, 0.24), (0.1, 0.1), (0.66, 0.3)]
    for bx, by in blooms:
        cx, cy, R = bx * cv.w, by * cv.h, cv.s * (0.9 + r.random() * 0.5)
        rot = r.random() * math.tau
        for p in range(5):
            a = rot + p * math.tau / 5
            px, py = cx + math.cos(a) * R * 0.75, cy + math.sin(a) * R * 0.75
            d.ellipse(((px - R * 0.55) * k, (py - R * 0.55) * k, (px + R * 0.55) * k, (py + R * 0.55) * k), fill=(255, 214, 228, 255))
        for p in range(5):
            a = rot + p * math.tau / 5
            px, py = cx + math.cos(a) * R * 0.45, cy + math.sin(a) * R * 0.45
            d.ellipse(((px - R * 0.3) * k, (py - R * 0.3) * k, (px + R * 0.3) * k, (py + R * 0.3) * k), fill=(245, 160, 190, 255))
        d.ellipse(((cx - R * 0.22) * k, (cy - R * 0.22) * k, (cx + R * 0.22) * k, (cy + R * 0.22) * k), fill=(255, 236, 150, 255))
    # drifting petals lower down
    for _ in range(22):
        px, py = r.random() * cv.w, (0.45 + r.random() * 0.5) * cv.h
        s = cv.s * (0.35 + r.random() * 0.3)
        a = r.random() * 180
        d.ellipse(((px - s) * k, (py - s * 0.55) * k, (px + s) * k, (py + s * 0.55) * k), fill=(255, 224, 236, 230))
    art = L.done()
    over(cv.arr, art)
    cloth(cv, 0.06, seed=14)
    trim(cv, cv.s // 2, cv.s // 2 + 1, "#7a4a6a", stitch="#ffe3ec")
    bevel(cv, 0.05, 0.1)


item("blossom_drift", "Blossom Drift", "CAPE", "REGULAR", 600,
     "A spring dusk with a branch of pink blossom and petals drifting down to the hem.",
     cape_bones(blossom_outer, lining("#f3d2dc", "#b48aa8", "#ffffff")), view=CAPE_VIEW)


def pumpkin_outer(cv):
    t, u = cv.yy(), cv.xx()
    cv.arr[:] = ramp(t, [(0, "#120a24"), (0.5, "#2c1248"), (0.75, "#5a1e4a"), (1, "#1a0c14")])
    L = layer(cv)
    k = L.k
    d = L.draw
    # a big harvest moon
    mx, my, R = cv.w * 0.5, cv.h * 0.3, cv.w * 0.3
    d.ellipse(((mx - R) * k, (my - R) * k, (mx + R) * k, (my + R) * k), fill=(255, 176, 66, 255))
    moon = L.done(blur=0)
    # craters
    y, x = cv.grid()
    n = pnoise(cv.h, cv.w, 4.0, 81, octaves=3)
    moon[..., :3] *= (0.85 + 0.25 * n)[..., None]
    over(cv.arr, moon)
    em_rgb(cv, moon, moon[..., 3] * 0.75)
    # bats
    L2 = layer(cv)
    d2 = L2.draw
    for bx, by, s in [(0.25, 0.14, 1.0), (0.78, 0.2, 0.8), (0.62, 0.08, 0.65), (0.18, 0.4, 0.7)]:
        cx, cy, S = bx * cv.w * k, by * cv.h * k, cv.s * 1.1 * s * k
        d2.polygon([(cx, cy + S * 0.15), (cx - S * 0.5, cy - S * 0.35), (cx - S * 1.2, cy - S * 0.15), (cx - S * 0.9, cy + S * 0.05),
                    (cx - S * 0.7, cy + S * 0.35), (cx - S * 0.35, cy + S * 0.15), (cx, cy + S * 0.45), (cx + S * 0.35, cy + S * 0.15),
                    (cx + S * 0.7, cy + S * 0.35), (cx + S * 0.9, cy + S * 0.05), (cx + S * 1.2, cy - S * 0.15), (cx + S * 0.5, cy - S * 0.35)],
                   fill=(14, 8, 18, 255))
    # hills and a pumpkin
    d2.ellipse((-cv.w * 0.4 * k, cv.h * 0.72 * k, cv.w * 0.8 * k, cv.h * 1.3 * k), fill=(20, 12, 22, 255))
    d2.ellipse((cv.w * 0.35 * k, cv.h * 0.78 * k, cv.w * 1.5 * k, cv.h * 1.3 * k), fill=(28, 14, 30, 255))
    px, py, P = cv.w * 0.5, cv.h * 0.84, cv.w * 0.2
    for off, shade in ((-0.55, (196, 84, 10)), (0.55, (196, 84, 10)), (-0.25, (230, 110, 20)), (0.25, (230, 110, 20)), (0, (245, 130, 30))):
        d2.ellipse(((px + off * P - P * 0.45) * k, (py - P * 0.62) * k, (px + off * P + P * 0.45) * k, (py + P * 0.55) * k), fill=shade + (255,))
    d2.rectangle(((px - P * 0.08) * k, (py - P * 0.85) * k, (px + P * 0.08) * k, (py - P * 0.5) * k), fill=(70, 90, 30, 255))
    art = L2.done()
    over(cv.arr, art)
    # the carved face glows
    L3 = layer(cv)
    d3 = L3.draw
    for ex in (-0.32, 0.32):
        d3.polygon([((px + ex * P - P * 0.14) * k, (py - P * 0.05) * k), ((px + ex * P + P * 0.14) * k, (py - P * 0.05) * k),
                    ((px + ex * P) * k, (py - P * 0.3) * k)], fill=(255, 226, 120, 255))
    d3.polygon([((px - P * 0.45) * k, (py + P * 0.12) * k), ((px + P * 0.45) * k, (py + P * 0.12) * k), ((px + P * 0.3) * k, (py + P * 0.32) * k),
                ((px + P * 0.12) * k, (py + P * 0.22) * k), ((px) * k, (py + P * 0.34) * k), ((px - P * 0.12) * k, (py + P * 0.22) * k),
                ((px - P * 0.3) * k, (py + P * 0.32) * k)], fill=(255, 210, 90, 255))
    face = L3.done()
    over(cv.arr, face)
    em_rgb(cv, face, face[..., 3])
    star_field(cv, 9, 0.004)
    cloth(cv, 0.05, seed=15)
    trim(cv, cv.s // 2, cv.s // 2 + 1, "#e07a1a", stitch="#2a1236")
    bevel(cv, 0.05, 0.1)


item("pumpkin_night", "Pumpkin Night", "CAPE", "REGULAR", 500,
     "A harvest moon, a flight of bats and a carved pumpkin whose face glows in the dark.",
     cape_bones(pumpkin_outer, lining("#2a1236", "#120818", "#e07a1a")), view=CAPE_VIEW)


def snowflake(d, cx, cy, R, k, color, width=1.0):
    for a in range(6):
        ang = a * math.pi / 3
        ex, ey = cx + math.cos(ang) * R, cy + math.sin(ang) * R
        d.line([(cx * k, cy * k), (ex * k, ey * k)], fill=color, width=max(1, int(width * k)))
        for f in (0.45, 0.72):
            bx, by = cx + math.cos(ang) * R * f, cy + math.sin(ang) * R * f
            for s in (-1, 1):
                a2 = ang + s * 0.8
                d.line([(bx * k, by * k), ((bx + math.cos(a2) * R * 0.3) * k, (by + math.sin(a2) * R * 0.3) * k)], fill=color,
                       width=max(1, int(width * 0.8 * k)))


def frost_outer(cv):
    t, u = cv.yy(), cv.xx()
    cv.arr[:] = ramp(t, [(0, "#2a6a9a"), (0.5, "#1c4a7a"), (1, "#0e2448")])
    n = pnoise(cv.h, cv.w, 3.0, 101, octaves=4)
    # frost creeping up from the hem
    frost = smooth((t + (n - 0.5) * 0.5 - 0.62) / 0.3)
    cv.arr[..., :3] = mix(cv.arr[..., :3], hexc("#cfeaff")[:3], (frost * 0.75)[..., None])
    L = layer(cv)
    r = np.random.default_rng(12)
    for i in range(9):
        cx, cy = r.random() * cv.w, (0.1 + r.random() * 0.65) * cv.h
        R = cv.s * (0.9 + r.random() * 1.4)
        snowflake(L.draw, cx, cy, R, L.k, (232, 248, 255, 235), 0.55 if R < cv.s * 1.5 else 0.8)
    flakes = L.done()
    over(cv.arr, flakes)
    em_rgb(cv, flakes, flakes[..., 3] * 0.35)
    cloth(cv, 0.06, seed=16)
    trim(cv, 0, cv.s // 2 + 1, "#c9d6e2", stitch="#ffffff")
    bevel(cv, 0.05, 0.1)


def fur(face, cv):
    t = cv.yy()
    n = pnoise(cv.h, cv.w, max(2.0, cv.w / 3), 107, octaves=2, aspect=4)
    strands = pnoise(cv.h, cv.w, max(2.0, cv.w / 1.5), 108, octaves=1, aspect=0.25)
    v = 0.78 + 0.22 * n + 0.12 * (strands - 0.5)
    cv.arr[..., :3] = (v[..., None] * hexc("#f4f8ff")[:3])
    cv.arr[..., 3] = 1
    if face not in ("top", "bottom"):
        cv.arr[..., :3] *= (1 - 0.25 * t)[..., None]
    ao_edges(cv, 0.12)


item("frostfall_cloak", "Frostfall Cloak", "CAPE", "REGULAR", 700,
     "An icy blue cloak scattered with snowflakes, frost creeping up from the hem and a thick white fur collar.",
     cape_bones(frost_outer, lining("#cfe0ee", "#93aec6", "#ffffff"), collar=fur), view=CAPE_VIEW)


def scales_height(cv, size=1.6):
    """Overlapping scales: rows offset by half a scale, each a rounded dome. Returns (height 0..1, edge mask, scale id)."""
    y, x = cv.units()
    row = np.floor(y / (size * 0.7))
    xo = x + (row % 2) * size * 0.5
    col = np.floor(xo / size)
    fx = (xo - col * size) / size - 0.5
    fy = (y - row * size * 0.7) / (size * 0.7)
    dist = np.sqrt(fx ** 2 * 4 + (fy - 0.05) ** 2 * 1.3)
    hgt = np.clip(1 - dist, 0, 1)
    edge = (dist > 0.82) & (dist < 1.05)
    return hgt, edge, row * 31 + col


def dragonhide_outer(cv):
    t, u = cv.yy(), cv.xx()
    hgt, edge, sid = scales_height(cv, 1.6)
    v = (np.sin(sid * 12.9898) * 43758.5453) % 1
    base = ramp(t, [(0, "#1f7a5a"), (0.6, "#0f4a44"), (1, "#0a2a30")])
    base[..., :3] *= (0.85 + 0.25 * v)[..., None]
    cv.arr[:] = base
    cv.arr[..., :3] = mix(cv.arr[..., :3], hexc("#6fe0a8")[:3], (hgt ** 3 * 0.45)[..., None])
    emboss(cv, hgt, 2.2)
    cv.arr[edge, :3] = mix(cv.arr[edge, :3], hexc("#d9b44a")[:3], 0.55)
    cv.arr[..., :3] *= (1 - 0.25 * smooth(t))[..., None]
    trim(cv, cv.s // 2, cv.s, "#c99a2e", stitch="#ffe9a6")
    bevel(cv, 0.06, 0.12)


item("dragonhide_cape", "Dragonhide Cape", "CAPE", "REGULAR", 700,
     "Rows of raised emerald scales edged in gold, shading down to deep teal.",
     cape_bones(dragonhide_outer, lining("#2a3a2a", "#121a14", "#4a6a3a")), view=CAPE_VIEW)


def horizon_outer(cv):
    t, u = cv.yy(), cv.xx()
    cv.arr[:] = ramp(t, [(0, "#120628"), (0.35, "#3a0c5a"), (0.55, "#a02a7a"), (0.56, "#0c0418"), (1, "#05020c")])
    star_field(cv, 33, 0.008)
    # the striped sun on the horizon
    y, x = cv.grid()
    cx, cy, R = cv.w * 0.5, cv.h * 0.55, cv.w * 0.34
    d = np.sqrt((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2)
    disc = smooth((R - d) / 0.8) * (y + 0.5 < cy)
    stripes = ((y / cv.h) < 0.38) | (np.sin((y / cv.h - 0.38) * 90) > -0.2 + (y / cv.h - 0.38) * 3)
    sun = disc * stripes
    sc = ramp(np.clip((y - (cy - R)) / R, 0, 1), [(0, "#ffe66a"), (0.5, "#ff9a4a"), (1, "#ff3a8a")])
    cv.arr[..., :3] = mix(cv.arr[..., :3], sc[..., :3], sun[..., None])
    em_rgb(cv, sc, sun * 0.8)
    # neon perspective grid on the floor
    fy = (y + 0.5) / cv.h
    floor = fy > 0.56
    depth = (fy - 0.56) / 0.44
    zl = 1 / np.maximum(depth, 0.02)
    hl = np.abs(((zl * 1.0) % 1) - 0.5) > 0.5 - 0.06 * zl
    vx = ((x + 0.5) / cv.w - 0.5) / np.maximum(depth, 0.02)
    vl = np.abs((vx * 3) % 1 - 0.5) > 0.47 - 0.02 / np.maximum(depth, 0.05)
    grid_m = floor & (hl | vl)
    gcol = mix(hexc("#ff3ad2"), hexc("#3af0ff"), depth[..., None])
    cv.arr[grid_m, :3] = gcol[grid_m][..., :3]
    em_rgb(cv, gcol, grid_m * (0.4 + 0.6 * depth))
    # mountain silhouettes on the horizon
    ridge = 0.56 - 0.06 * np.abs(np.sin(u * 7.0)) * (np.abs(u - 0.5) > 0.12) - 0.03 * np.abs(np.sin(u * 19))
    mt = (t > ridge) & (t < 0.56)
    cv.arr[mt, :3] = hexc("#1a0830")[:3]
    cloth(cv, 0.04, seed=17)
    trim(cv, cv.s // 2, cv.s // 2, "#ff3ad2")
    bevel(cv, 0.04, 0.1)


item("neon_horizon", "Neon Horizon", "CAPE", "REGULAR", 600,
     "A striped sunset over a glowing neon grid, straight out of a retro arcade.",
     cape_bones(horizon_outer, lining("#1a0830", "#05020c", "#ff3ad2")), view=CAPE_VIEW)


def oak_outer(cv):
    t, u = cv.yy(), cv.xx()
    cv.arr[:] = ramp(t, [(0, "#2c6a3a"), (1, "#123a20")])
    y, x = cv.grid()
    # vertical pinstripes in the field
    ps = (np.abs(((x + 0.5) / cv.s) % 2.5 - 1.25) < 0.08)
    cv.arr[ps, :3] *= 1.12
    L = layer(cv)
    k = L.k
    d = L.draw
    W, H = cv.w, cv.h
    # a shield with a gold rim
    sx0, sx1, sy0, sy1 = W * 0.2, W * 0.8, H * 0.18, H * 0.72
    shield = [(sx0, sy0), (sx1, sy0), (sx1, sy0 + (sy1 - sy0) * 0.55), (W * 0.5, sy1), (sx0, sy0 + (sy1 - sy0) * 0.55)]
    d.polygon([(a * k, b * k) for a, b in shield], fill=(218, 176, 74, 255))
    inset = [(sx0 + 2, sy0 + 2), (sx1 - 2, sy0 + 2), (sx1 - 2, sy0 + (sy1 - sy0) * 0.55), (W * 0.5, sy1 - 3), (sx0 + 2, sy0 + (sy1 - sy0) * 0.55)]
    d.polygon([(a * k, b * k) for a, b in inset], fill=(236, 228, 204, 255))
    # the oak: trunk, roots and a round canopy of lobes
    tx, ty = W * 0.5, H * 0.58
    d.rectangle(((tx - 2) * k, (H * 0.42) * k, (tx + 2) * k, ty * k), fill=(110, 70, 36, 255))
    d.polygon([((tx - 5) * k, ty * k), ((tx + 5) * k, ty * k), ((tx + 2) * k, (ty - 3) * k), ((tx - 2) * k, (ty - 3) * k)], fill=(110, 70, 36, 255))
    for cx, cy, R in [(0.5, 0.34, 9), (0.38, 0.38, 7), (0.62, 0.38, 7), (0.43, 0.29, 6.5), (0.57, 0.29, 6.5)]:
        d.ellipse(((W * cx - R) * k, (H * cy - R) * k, (W * cx + R) * k, (H * cy + R) * k), fill=(46, 120, 60, 255))
    for cx, cy, R in [(0.45, 0.3, 3), (0.58, 0.34, 2.5), (0.4, 0.38, 2.2)]:
        d.ellipse(((W * cx - R) * k, (H * cy - R) * k, (W * cx + R) * k, (H * cy + R) * k), fill=(108, 186, 96, 255))
    art = L.done()
    over(cv.arr, art)
    hg = art[..., 3]
    emboss(cv, hg, 1.5)
    cloth(cv, 0.06, seed=18)
    trim(cv, cv.s // 2 + 1, cv.s, "#d4a84a", stitch="#fff0c0")
    # a notched hem: swallow-tail
    yy, xx = cv.grid()
    notch = (yy > cv.h - cv.s * 2.2) & (np.abs(xx + 0.5 - cv.w / 2) < (yy - (cv.h - cv.s * 2.2)) * 0.9)
    cv.arr[notch, 3] = 0
    bevel(cv, 0.05, 0.1)


item("oakheart_banner", "Oakheart Banner", "CAPE", "REGULAR", 500,
     "A forest-green banner with a golden shield and a great oak, finished with a swallow-tail hem.",
     cape_bones(oak_outer, lining("#1e4a2a", "#0e2414", "#d4a84a")), view=CAPE_VIEW)


def sweetheart_outer(cv):
    t, u = cv.yy(), cv.xx()
    cv.arr[:] = ramp(t, [(0, "#ff9ab8"), (0.6, "#e8466e"), (1, "#a01840")])
    L = layer(cv)
    k = L.k
    r = np.random.default_rng(23)
    for i in range(16):
        cx, cy = r.random() * cv.w, (0.06 + r.random() * 0.78) * cv.h
        R = cv.s * (0.5 + r.random() * 0.9)
        col = [(255, 232, 240, 235), (255, 70, 110, 255), (200, 20, 60, 255)][i % 3]
        L.draw.polygon(heart_poly(cx, cy, R, L.k, (r.random() - 0.5) * 0.6), fill=col)
    L.draw.polygon(heart_poly(cv.w * 0.5, cv.h * 0.42, cv.s * 2.6, L.k), fill=(255, 250, 252, 255))
    L.draw.polygon(heart_poly(cv.w * 0.5, cv.h * 0.42, cv.s * 2.1, L.k), fill=(232, 30, 74, 255))
    art = L.done()
    over(cv.arr, art)
    emboss(cv, art[..., 3], 1.2)
    cloth(cv, 0.06, seed=19)
    # lace scallops at the hem
    yy, xx = cv.grid()
    sc = cv.s * 1.2
    hem = cv.h - cv.s * 1.2
    dy = yy - hem
    circle = ((xx % sc) - sc / 2) ** 2 + (dy) ** 2 < (sc / 2) ** 2
    lace = (yy >= hem - cv.s * 0.4) & ((yy < hem) | circle)
    cv.arr[lace, :3] = hexc("#fff4f8")[:3]
    holes = lace & (((xx + yy) % 3) == 0)
    cv.arr[holes, :3] = hexc("#f6c2d4")[:3]
    cv.arr[(yy >= hem) & ~circle, 3] = 0
    bevel(cv, 0.05, 0.1)


item("sweetheart_cape", "Sweetheart Cape", "CAPE", "REGULAR", 400,
     "Rose-pink fading to crimson, scattered with hearts and finished with a lace-scalloped hem.",
     cape_bones(sweetheart_outer, lining("#ffd2de", "#d886a0", "#ffffff")), view=CAPE_VIEW)


# ============================================================================================== serialisation

def model_json(it, tw, th, scale):
    bones = []
    for b in it["bones"]:
        cubes = []
        for c in b.cubes:
            cd = {"origin": c.origin, "size": c.size, "uv": c.uv}
            if c.inflate:
                cd["inflate"] = c.inflate
            if c.mirror:
                cd["mirror"] = True
            cubes.append(cd)
        bd = {"name": b.name, "attach": b.attach, "pivot": b.pivot, "rotation": b.rotation, "cubes": cubes}
        if b.anim:
            bd["anim"] = b.anim
        if b.emissive:
            bd["emissive"] = True
        if b.tint:
            bd["tint"] = True
        if b.alpha != 1.0:
            bd["alpha"] = b.alpha
        bones.append(bd)
    m = {"format": FORMAT, "space": "item" if it["item_space"] else "entity", "texture": [tw, th],
         "textureScale": scale, "bones": bones}
    if it["display"]:
        m["display"] = it["display"]
    return m


def item_json(it):
    j = {"id": it["id"], "name": it["name"], "slot": it["slot"], "rarity": it["rarity"], "colourable": it["colourable"],
         "defaultColor": it["default_color"], "partner": it["partner"], "description": it["desc"]}
    if it["fx"]:
        j["fx"] = it["fx"]
    if it["emote"]:
        j["emote"] = it["emote"]
    if it["animation"]:
        a = it["animation"]
        j["animation"] = {"frames": a["frames"], "frametime": a.get("frametime", 2),
                          "interpolate": a.get("interpolate", True)}
        if a.get("order"):
            j["animation"]["order"] = a["order"]
    return j


# ============================================================================================== thumbnails
# A small software renderer: the same cuboid quads and UVs as ModelPart.Cuboid, orthographic, z-buffered (opaque first,
# then see-through texels back to front), lit by a key light with a cool fill and a rim, and with a soft bloom around
# everything that glows.

MANNEQUIN = {  # part: (pivot, cube origin, size) in entity model space (y down), like the vanilla biped
    "head": ((0, 0, 0), (-4, -8, -4), (8, 8, 8)),
    "body": ((0, 0, 0), (-4, 0, -2), (8, 12, 4)),
    "rightArm": ((-5, 2, 0), (-8, 0, -2), (4, 12, 4)),
    "leftArm": ((5, 2, 0), (4, 0, -2), (4, 12, 4)),
    "rightLeg": ((-1.9, 12, 0), (-3.9, 12, -2), (4, 12, 4)),
    "leftLeg": ((1.9, 12, 0), (-0.1, 12, -2), (4, 12, 4)),
}
ATTACH = {"body": "body", "head": "head", "root": None, "item": None, "right_arm": "rightArm", "left_arm": "leftArm"}


def rot_matrix(pitch, yaw, roll):
    """Like Quaternionf.rotationZYX(roll, yaw, pitch): R = Rz * Ry * Rx (degrees)."""
    p, y, r = (math.radians(v) for v in (pitch, yaw, roll))
    rx = np.array([[1, 0, 0], [0, math.cos(p), -math.sin(p)], [0, math.sin(p), math.cos(p)]])
    ry = np.array([[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]])
    rz = np.array([[math.cos(r), -math.sin(r), 0], [math.sin(r), math.cos(r), 0], [0, 0, 1]])
    return rz @ ry @ rx


def cuboid_quads(origin, size, uv, inflate, mirror, tw, th):
    """The six quads of ModelPart.Cuboid: 4 corner positions and their UVs (normalised)."""
    x, y, z = origin
    sx, sy, sz = size
    f, g, h = x + sx, y + sy, z + sz
    x, y, z, f, g, h = x - inflate, y - inflate, z - inflate, f + inflate, g + inflate, h + inflate
    if mirror:
        x, f = f, x
    v1, v2, v3, v4 = (x, y, z), (f, y, z), (f, g, z), (x, g, z)
    v5, v6, v7, v8 = (x, y, h), (f, y, h), (f, g, h), (x, g, h)
    u, v = uv
    j, k, l, m, n, o = u, u + sz, u + sz + sx, u + sz + sx + sx, u + sz + sx + sz, u + sz + sx + sz + sx
    p, q, r = v, v + sz, v + sz + sy
    faces = [((v6, v5, v1, v2), (k, p, l, q)), ((v3, v4, v8, v7), (l, q, m, p)), ((v1, v5, v8, v4), (j, q, k, r)),
             ((v2, v1, v4, v3), (k, q, l, r)), ((v6, v2, v3, v7), (l, q, n, r)), ((v5, v6, v7, v8), (n, q, o, r))]
    out = []
    for verts, (u1, va, u2, vb) in faces:
        uvs = [(u2, va), (u1, va), (u1, vb), (u2, vb)]
        out.append((np.array(verts, float), np.array([(a / tw, b / th) for a, b in uvs], float)))
    return out


class Scene:
    def __init__(self):
        self.quads = []   # (points[4,3], uv[4,2] or None, texture or None, emissive texture or None, color, emissive)

    def cube(self, M, origin, size, uv, inflate, mirror, tw, th, tex, color, emissive, em=None):
        for pts, uvs in cuboid_quads(origin, size, uv or (0, 0), inflate, mirror, tw, th):
            w = (M[:3, :3] @ pts.T).T + M[:3, 3]
            self.quads.append((w, uvs if tex is not None else None, tex, em, color, emissive))

    def quad(self, pts, uvs, tex, color=None):
        self.quads.append((np.array(pts, float), np.array(uvs, float), tex, None,
                           np.ones(4, np.float32) if color is None else color, False))


def translate(t):
    M = np.eye(4)
    M[:3, 3] = t
    return M


def rotate(pitch, yaw, roll):
    M = np.eye(4)
    M[:3, :3] = rot_matrix(pitch, yaw, roll)
    return M


def blur(arr, sigma):
    """Gaussian blur of an (h, w, c) float array (FFT, with padding so nothing wraps around)."""
    h, w, _ = arr.shape
    pad = int(sigma * 3) + 1
    H, W = h + 2 * pad, w + 2 * pad
    big = np.zeros((H, W, arr.shape[2]), np.float32)
    big[pad:pad + h, pad:pad + w] = arr
    fy = np.fft.fftfreq(H)[:, None]
    fx = np.fft.rfftfreq(W)[None, :]
    g = np.exp(-2 * (math.pi * sigma) ** 2 * (fy ** 2 + fx ** 2))
    out = np.fft.irfft2(np.fft.rfft2(big, axes=(0, 1)) * g[..., None], s=(H, W), axes=(0, 1))
    return out[pad:pad + h, pad:pad + w].astype(np.float32)


def render_scene(scene, yaw, pitch, size=THUMB, ss=4, yup=False, bloom=0.9, frame=None, margin=1.10):
    S = size * ss
    col = np.zeros((S, S, 3), np.float32)
    alp = np.zeros((S, S), np.float32)
    zb = np.full((S, S), -1e9, np.float32)
    glow = np.zeros((S, S, 3), np.float32)
    R = rot_matrix(pitch, 0, 0) @ rot_matrix(0, yaw, 0)
    # entity space is y-down with the front at -z (as Minecraft draws it): screen x = x, up = -y, nearer = +z here
    view = np.diag([1, 1 if yup else -1, -1]) @ R
    pts_all = [(view @ q[0].T).T for q in scene.quads]
    allp = np.concatenate(pts_all) if pts_all else np.zeros((1, 3))
    if frame is not None:
        lo, hi = np.array(frame[0], float), np.array(frame[1], float)
    else:
        lo, hi = allp[:, :2].min(0), allp[:, :2].max(0)
    span = max(hi[0] - lo[0], hi[1] - lo[1]) * margin + 1e-6
    centre = (lo + hi) / 2
    scale = S / span
    key = np.array([-0.45, 0.7, 0.55])        # from upper left, towards the viewer
    key /= np.linalg.norm(key)
    fill_l = np.array([0.6, -0.1, 0.4])
    fill_l /= np.linalg.norm(fill_l)
    key_c = np.array([1.04, 1.0, 0.95], np.float32)
    fill_c = np.array([0.55, 0.62, 0.78], np.float32)
    raster = []
    for i, (w, uvs, tex, emt, color, emissive) in enumerate(scene.quads):
        p = pts_all[i]
        sp = np.stack([(p[:, 0] - centre[0]) * scale + S / 2, -(p[:, 1] - centre[1]) * scale + S / 2], 1)
        e1, e2 = p[1] - p[0], p[3] - p[0]
        nrm = np.cross(e1, e2)
        ln = np.linalg.norm(nrm)
        light = np.ones(3, np.float32)
        if ln > 1e-9:
            nrm = nrm / ln
            if nrm[2] < 0:
                nrm = -nrm                      # two-sided: light the side we see
            k = max(0.0, float(nrm @ key))
            f = max(0.0, float(nrm @ fill_l))
            rim = (1 - abs(float(nrm[2]))) ** 3 * 0.25
            light = 0.42 + key_c * (0.62 * k) + fill_c * (0.22 * f) + rim
        if emissive:
            light = np.ones(3, np.float32)
        x0, y0 = np.floor(sp.min(0)).astype(int)
        x1, y1 = np.ceil(sp.max(0)).astype(int)
        x0, y0, x1, y1 = max(0, x0), max(0, y0), min(S, x1), min(S, y1)
        if x1 <= x0 or y1 <= y0:
            continue
        a, b, d = sp[0], sp[1], sp[3]
        A = np.array([[b[0] - a[0], d[0] - a[0]], [b[1] - a[1], d[1] - a[1]]])
        if abs(np.linalg.det(A)) < 1e-6:
            continue
        Ai = np.linalg.inv(A)
        yy, xx = np.mgrid[y0:y1, x0:x1].astype(np.float32) + 0.5
        rel = np.stack([xx - a[0], yy - a[1]], -1)
        st = rel @ Ai.T
        s, t = st[..., 0], st[..., 1]
        inside = (s >= -1e-4) & (s <= 1 + 1e-4) & (t >= -1e-4) & (t <= 1 + 1e-4)
        if not inside.any():
            continue
        z = p[0, 2] + (p[1, 2] - p[0, 2]) * s + (p[3, 2] - p[0, 2]) * t
        e = None
        if tex is not None:
            uv = uvs[0] + (uvs[1] - uvs[0]) * s[..., None] + (uvs[3] - uvs[0]) * t[..., None]
            th_, tw_ = tex.shape[:2]
            px = np.clip((uv[..., 0] * tw_).astype(int), 0, tw_ - 1)
            py = np.clip((uv[..., 1] * th_).astype(int), 0, th_ - 1)
            c = tex[py, px].copy()
            c[..., :3] *= color[:3]
            c[..., 3] *= color[3]
            if emt is not None:
                e = emt[py, px].copy()
                if not e[..., 3].any():
                    e = None
        else:
            c = np.broadcast_to(color, s.shape + (4,)).copy()
        c[..., 3] *= inside
        if e is not None:
            e[..., 3] *= inside
        lit = c[..., :3] * light
        raster.append((float(p[:, 2].mean()), (slice(y0, y1), slice(x0, x1)), lit, c[..., 3], z, e, emissive))
    eps = 0.02 * scale / ss + 0.01

    def put(sl, lit, a, z, e, emissive, opaque):
        if opaque:
            m = (a >= 0.9) & (z > zb[sl])
            dst = col[sl]
            dst[m] = lit[m]
            alp[sl][m] = 1
            zb[sl][m] = z[m]
            g = glow[sl]
            g[m] = 0
            if emissive:
                g[m] = lit[m] * a[m, None]
            if e is not None:
                ea = e[..., 3] * m
                dst[:] = dst * (1 - ea[..., None]) + e[..., :3] * ea[..., None]
                g[:] = g + e[..., :3] * ea[..., None]
        else:
            m = (a > 0.003) & (a < 0.9) & (z >= zb[sl] - eps)
            aa = a * m
            dst = col[sl]
            dst[:] = dst * (1 - aa[..., None]) + lit * aa[..., None]
            alp[sl] = alp[sl] * (1 - aa) + aa
            g = glow[sl]
            if emissive:
                g[:] = g * (1 - aa[..., None] * 0.3) + lit * aa[..., None]
            else:
                g[:] = g * (1 - aa[..., None])
            if e is not None:
                ea = e[..., 3] * m
                dst[:] = dst * (1 - ea[..., None]) + e[..., :3] * ea[..., None]
                g[:] = g + e[..., :3] * ea[..., None]
    for _, sl, lit, a, z, e, emissive in raster:
        put(sl, lit, a, z, e, emissive, True)
    for _, sl, lit, a, z, e, emissive in sorted(raster, key=lambda r: r[0]):
        put(sl, lit, a, z, e, emissive, False)
    # premultiplied composite with bloom
    pre = col * alp[..., None]
    if bloom and glow.any():
        g1 = blur(glow, 2.2 * ss)
        g2 = blur(glow, 7 * ss)
        add = (g1 * 0.55 + g2 * 0.55) * bloom
        pre = pre + add
        alp = np.clip(alp + np.clip(lum(add), 0, 1) * (1 - alp), 0, 1)
    rgb = np.where(alp[..., None] > 1e-4, pre / np.maximum(alp[..., None], 1e-4), 0)
    out = np.concatenate([np.clip(rgb, 0, 1), alp[..., None]], -1)
    im = to_image(out, dither=False)
    return im.convert("RGBa").resize((size, size), Image.LANCZOS).convert("RGBA")


SKIN_UV = {"head": (0, 0), "body": (16, 16), "rightArm": (40, 16), "leftArm": (32, 48), "rightLeg": (0, 16),
           "leftLeg": (16, 48)}


def preview_skin():
    """The shop's preview skin (the same drawing as defaultSkin() in site/assets/shop.js), 64 x 64 classic layout:
    an original dark hoodie with Evade-red trim. Thumbnails that show the player use it, so they match the 3D
    previews on the shop."""
    im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)

    def rect(x, y, w, h, col):
        d.rectangle((x, y, x + w - 1, y + h - 1), fill=col)

    def box(u, v, w, h, dd, col):
        rect(u + dd, v, w * 2, dd, col)
        rect(u, v + dd, (w + dd) * 2, h, col)

    box(0, 0, 8, 8, 8, "#c99a76")                                                       # head
    for r in ((8, 0, 16, 8), (0, 8, 32, 2), (24, 8, 8, 8), (0, 8, 2, 6), (16, 8, 2, 5)):  # hair
        rect(*r, "#2a1c16")
    rect(9, 12, 2, 1, "#ffffff"); rect(13, 12, 2, 1, "#ffffff")                          # eyes
    rect(10, 12, 1, 1, "#1d3b6e"); rect(13, 12, 1, 1, "#1d3b6e")
    rect(11, 14, 2, 1, "#a8775a")                                                       # mouth shadow
    box(16, 16, 8, 12, 4, "#17171c")                                                    # body: dark hoodie
    rect(20, 20, 8, 1, "#e11d2e"); rect(23, 21, 2, 4, "#e11d2e")                         # red trim + zip
    rect(20, 28, 8, 4, "#24242b")
    box(40, 16, 4, 12, 4, "#17171c"); box(32, 48, 4, 12, 4, "#17171c")                  # arms
    for r in ((40, 28, 16, 4), (32, 60, 16, 4), (44, 16, 4, 4), (36, 48, 4, 4)):
        rect(*r, "#c99a76")
    rect(40, 26, 16, 1, "#e11d2e"); rect(32, 58, 16, 1, "#e11d2e")
    box(0, 16, 4, 12, 4, "#2c2f3a"); box(16, 48, 4, 12, 4, "#2c2f3a")                   # legs
    for r in ((0, 29, 16, 3), (16, 61, 16, 3), (8, 16, 4, 4), (24, 48, 4, 4)):           # shoes
        rect(*r, "#0d0d10")
    arr = np.asarray(im, np.float32) / 255
    # lift the very dark hoodie a little so the figure reads against the dark cards
    arr[..., :3] = arr[..., :3] * 0.88 + 0.07
    return arr


SKIN = preview_skin()


def shadow_tex():
    yy, xx = np.mgrid[0:32, 0:32].astype(np.float32)
    r = np.sqrt((xx - 15.5) ** 2 + (yy - 15.5) ** 2) / 16
    t = np.zeros((32, 32, 4), np.float32)
    t[..., 3] = np.clip(1 - r, 0, 1) ** 1.6 * 0.42
    return t


SHADOW = shadow_tex()


def add_shadow(scene, rx=7.0, rz=5.0, y=24.0):
    scene.quad([(-rx, y, -rz), (rx, y, -rz), (rx, y, rz), (-rx, y, rz)], [(0, 0), (1, 0), (1, 1), (0, 1)], SHADOW)


def add_mannequin(scene, pose=None, alpha=1.0, parts=None, shadow=True):
    """The player for context, in the preview skin."""
    mats = {}
    for part, (pivot, origin, size) in MANNEQUIN.items():
        rx, ry, rz = (pose or {}).get(part, (0, 0, 0))
        M = translate(pivot) @ rotate(rx, ry, rz)
        mats[part] = M
        if parts is not None and part not in parts:
            continue
        local = [o - pv for o, pv in zip(origin, pivot)]
        c = np.ones(4, np.float32)
        c[3] = alpha
        scene.cube(M, local, size, SKIN_UV[part], 0, False, 64, 64, SKIN, c, False)
    if shadow and parts is None:
        add_shadow(scene)
    return mats


def tint_of(it):
    if not it["default_color"]:
        return np.ones(4, np.float32)
    return hexc(it["default_color"])


def thumb_model(it, tex_arr, em_arr, tw, th):
    scene = Scene()
    view = it["view"]
    focus = view.get("focus")
    parts = {"head": {"head"}}.get(focus)
    mats = add_mannequin(scene, parts=parts, shadow=focus not in ("head",)) if focus not in ("item", "pet") else {}
    for b in it["bones"]:
        base = mats.get(ATTACH.get(b.attach), np.eye(4))
        rx, ry, rz = b.rotation
        anim = b.anim or {}
        if anim.get("type") == "wing":
            ry += anim["side"] * (anim["base"] + view.get("wing_open", 0))
        M = base @ translate(b.pivot) @ rotate(rx, ry, rz) @ translate([-v for v in b.pivot])
        if anim.get("type") == "cape":
            M = base @ translate(b.pivot) @ rotate(10, 0, 0) @ rotate(0, 180, 0) @ translate([-v for v in b.pivot])
        if anim.get("type") == "spin" and view.get("spin") is not None:
            M = base @ translate(b.pivot) @ rotate(rx, ry + view["spin"], rz) @ translate([-v for v in b.pivot])
        color = tint_of(it) if b.tint else np.ones(4, np.float32)
        color = color.copy()
        color[3] = b.alpha
        for c in b.cubes:
            scene.cube(M, c.origin, c.size, c.uv, c.inflate, c.mirror, tw, th, tex_arr, color, b.emissive,
                       None if b.emissive else em_arr)
    frame = view.get("frame")
    if focus == "head" and frame is None:
        frame = None
    im = render_scene(scene, view.get("yaw", 30), view.get("pitch", 10), yup=focus == "item", frame=frame,
                      margin=view.get("margin", 1.10))
    if focus == "pet":
        return badge(im, view.get("glow", "#6cf2ff"), 0.5)
    if view.get("glow"):
        return badge(im, view["glow"], view.get("glow_strength", 0.3))
    return im


def emote_key_pose(emote):
    """The emote's most expressive moment: the keyframe time where the parts are turned furthest from standing."""
    parts = emote["parts"]
    times = sorted({k[0] for keys in parts.values() for k in keys})

    def at(keys, t):
        for a, b in zip(keys, keys[1:]):
            if a[0] <= t <= b[0]:
                f = 0 if b[0] == a[0] else (t - a[0]) / (b[0] - a[0])
                return tuple(a[i] + (b[i] - a[i]) * f for i in (1, 2, 3))
        return tuple(keys[-1][1:4])

    best = max(times, key=lambda t: sum(sum(abs(v) for v in at(keys, t)) for keys in parts.values()))
    return {p: at(keys, best) for p, keys in parts.items()}


def thumb_emote(it):
    pose = it["view"].get("pose") or emote_key_pose(it["emote"])
    scene = Scene()
    add_mannequin(scene, pose)
    im = render_scene(scene, it["view"].get("yaw", -24), it["view"].get("pitch", 6))
    return badge(im, it["view"].get("glow", "#ffd36a"), 0.4)


def badge(im, color, strength=0.35):
    """A soft radial glow behind a thumbnail."""
    bg = np.zeros((THUMB, THUMB, 4), np.float32)
    yy, xx = np.mgrid[0:THUMB, 0:THUMB].astype(np.float32)
    r = np.sqrt((xx - THUMB / 2) ** 2 + (yy - THUMB / 2) ** 2) / (THUMB / 2)
    c = hexc(color)
    bg[..., :3] = c[:3]
    bg[..., 3] = np.clip(1 - r, 0, 1) ** 2 * strength
    out = to_image(bg)
    out.alpha_composite(im)
    return out


def thumb_particles(kind, colors):
    raise SystemExit('no particle thumbs')


# ============================================================================================== wardrobe nav icon

def wardrobe_icon():
    """A coat hanger, in the same white-mask style and mip chain as scripts/draw-nav-icons.py."""
    K, S, W = 4, 96, 8
    im = Image.new("RGBA", (S * K, S * K), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)

    def s(v):
        return [c * K for c in v]

    white = (255, 255, 255, 255)
    d.arc(s((36, 6, 60, 30)), 180, 400, fill=white, width=W * K)         # hook
    d.line(s((48, 30, 48, 40)), fill=white, width=W * K)
    d.line(s((48, 38, 8, 72)), fill=white, width=W * K)                  # shoulders
    d.line(s((48, 38, 88, 72)), fill=white, width=W * K)
    d.rounded_rectangle(s((4, 68, 92, 80)), radius=6 * K, fill=white)     # bar
    for px, suffix in ((96, "_96"), (48, "_48"), (24, "")):
        level = im.convert("RGBa").resize((px, px), Image.LANCZOS).convert("RGBA")
        mask = Image.new("RGBA", level.size, (255, 255, 255, 0))
        mask.putalpha(level.getchannel("A"))
        path = NAV / f"wardrobe{suffix}.png"
        mask.save(path, optimize=True)
        (NAV / f"wardrobe{suffix}.png.mcmeta").write_text(json.dumps({"texture": {"blur": True, "clamp": True}}) + "\n")


# ============================================================================================== main

def sha256(b):
    return hashlib.sha256(b).hexdigest()


def png_bytes(im):
    buf = io.BytesIO()
    im.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def build(it):
    """All files of one item: {name: bytes}."""
    files = {"item.json": (json.dumps(item_json(it), indent=1) + "\n").encode()}
    if it["bones"]:
        tw, th = pack(it["bones"])
        scale = scale_for(tw, th, it["scale_cap"])
        anim = it["animation"]
        frames = anim["frames"] if anim else 1
        texs, ems = [], []
        for f in range(frames):
            PHASE[0] = f / frames
            tex, em = paint_model(it["bones"], tw, th, it["item_space"], scale)
            texs.append(tex)
            ems.append(em)
        PHASE[0] = 0.0
        files["model.json"] = (json.dumps(model_json(it, tw, th, scale), indent=1) + "\n").encode()
        files["texture.png"] = png_bytes(to_image(np.concatenate(texs, 0)))
        has_em = any(e[..., 3].max() > 0 for e in ems)
        if has_em:
            files["emissive.png"] = png_bytes(to_image(np.concatenate(ems, 0)))
        k = it["view"].get("thumb_frame", 0)
        thumb = thumb_model(it, texs[k], ems[k] if has_em else None, tw, th)
    elif it["emote"]:
        thumb = thumb_emote(it)
    else:
        thumb = thumb_particles(it["thumb"], it["fx"]["colors"])
    files["thumb.png"] = png_bytes(thumb)
    return files


def contact_sheet(path, built):
    cols = 8
    cell = 150
    rows = (len(built) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * cell, rows * (cell + 26)), (18, 18, 24))
    d = ImageDraw.Draw(sheet)
    for i, (it, files) in enumerate(built):
        im = Image.open(io.BytesIO(files["thumb.png"])).convert("RGBA")
        bg = Image.new("RGBA", (THUMB, THUMB), (32, 33, 42, 255))
        bg.alpha_composite(im)
        x, y = (i % cols) * cell + 11, (i // cols) * (cell + 26) + 6
        sheet.paste(bg.convert("RGB"), (x, y))
        price = "special" if it["price"] is None else f"${it['price'] / 100:.2f}"
        d.text((x, y + THUMB + 2), f"{it['name'][:20]}", fill=(230, 230, 235))
        d.text((x, y + THUMB + 13), f"{it['slot'][:9]} {price}", fill=(150, 150, 165))
    sheet.save(path)


def main():
    only = None
    sheet = None
    args = sys.argv[1:]
    if "--sheet" in args:
        sheet = args[args.index("--sheet") + 1]
    if "--only" in args:
        only = set(args[args.index("--only") + 1].split(","))
    built = []
    for it in ITEMS:
        if only and it["id"] not in only:
            continue
        built.append((it, build(it)))
    if sheet:
        contact_sheet(sheet, built)
    if only:
        print(f"built {len(built)} (preview only, nothing written)", file=sys.stderr)
        return
    for out in (OUT_RES, OUT_SITE):
        out.mkdir(parents=True, exist_ok=True)
        for child in out.iterdir():
            if child.is_dir():
                shutil.rmtree(child)
    index = {"version": "v2.7", "format": FORMAT, "items": []}
    for it, files in built:
        for out in (OUT_RES, OUT_SITE):
            d = out / it["id"]
            d.mkdir(parents=True, exist_ok=True)
            for name, data in files.items():
                (d / name).write_bytes(data)
        index["items"].append({
            "id": it["id"], "name": it["name"], "slot": it["slot"], "rarity": it["rarity"].lower(),
            "priceCents": it["price"], "currency": "USD", "partner": it["partner"], "colourable": it["colourable"],
            "defaultColor": it["default_color"], "description": it["desc"],
            "assets": {"files": {name: sha256(data) for name, data in files.items()}}})
    data = (json.dumps(index, indent=1) + "\n").encode()
    for out in (OUT_RES, OUT_SITE):
        stale = out / "index.json"
        if stale.exists():
            stale.unlink()
        (out / "catalog.json").write_bytes(data)
    wardrobe_icon()
    print(f"{len(built)} cosmetics -> {OUT_RES.relative_to(ROOT)} and {OUT_SITE.relative_to(ROOT)}", file=sys.stderr)


if __name__ == "__main__":
    main()
