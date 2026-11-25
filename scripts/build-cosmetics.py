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
  texture.png   box-UV (or per-face UV) texture for model.json, `textureScale` (1, 2, 4 or 8; special items up to 16)
                texels per model unit; animated
                (special) items stack their frames top to bottom (a vertical strip)
  emissive.png  optional: the parts that glow (full bright), same layout and frames as texture.png
  thumb.png     128 x 128 preview used by the Wardrobe grid and the shop cards (animated items: a strip of frames)

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
MAX_UV = 256           # largest UV space (model.json "texture"), each side
MAX_FRAME_PX = 512     # largest texture frame we draw, each side (the client takes up to 1024)
ANIM_FRAME_PX = 1024   # animated (special) items: the client's own limit
MAX_STRIP_PX = 4096    # tallest texture strip the client takes (all frames)
MAX_SCALE = 8          # most texels per model unit (the client takes 1, 2, 4 or 8)
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
    def __init__(self, origin, size, paint, inflate=0.0, mirror=False, faces=None):
        self.origin = [float(v) for v in origin]
        self.size = [int(v) for v in size]
        self.paint = paint          # paint(face_name, canvas) for each of the six faces
        self.inflate = inflate
        self.mirror = mirror
        self.uv = None
        # per-face UVs (format v2), for items laid out by hand (item(..., layout=(tw, th))):
        # {face: (uv rect [u1, v1, u2, v2] in UV units, rotation 0..3 quarter turns, painted?)}. A painted face is drawn
        # as seen from outside and placed in its rect turned by `rotation` (rect = np.rot90(face, rotation)); an
        # unpainted face only samples its rect (part of another face's art, e.g. a cape's edges sampling its trim).
        self.faces = faces

    def uv_size(self):
        w, h, d = self.size
        return 2 * (w + d), d + h


class Bone:
    def __init__(self, name, attach, pivot=(0, 0, 0), rotation=(0, 0, 0), cubes=(), anim=None, emissive=False,
                 tint=False, alpha=1.0, parent=None):
        self.name, self.attach = name, attach
        self.pivot, self.rotation = list(pivot), list(rotation)
        self.cubes = list(cubes)
        self.anim, self.emissive, self.tint, self.alpha = anim, emissive, tint, alpha
        self.parent = parent        # the name of an earlier bone this one moves with (format v2), or None


def mirrored_bone(b, name, anim=None):
    """The left-hand copy of a right-hand bone: x negated, UVs mirrored, rotations about y and z negated."""
    cubes = [Cube((-(c.origin[0] + c.size[0]), c.origin[1], c.origin[2]), c.size, c.paint, c.inflate, not c.mirror)
             for c in b.cubes]
    for src, dst in zip(b.cubes, cubes):
        dst.share = src
    return Bone(name, b.attach, (-b.pivot[0], b.pivot[1], b.pivot[2]), (b.rotation[0], -b.rotation[1], -b.rotation[2]),
                cubes, anim if anim is not None else b.anim, b.emissive, b.tint, b.alpha, b.parent)


def wing_pair(right_bones, suffix_r="_r", suffix_l="_l"):
    left = [mirrored_bone(b, b.name.replace(suffix_r, suffix_l), dict(b.anim, side=-1) if b.anim else None)
            for b in right_bones]
    return list(right_bones) + left


def pack(bones):
    """Shelf-packs every cube's box-UV rectangle into the smallest square-ish texture (sides multiples of 2).
    Mirrored copies share their original's rectangle. Returns (tw, th) in model units."""
    first = {}
    for b in bones:
        for c in b.cubes:
            if hasattr(c, "share"):
                continue
            key = (id(c.paint), tuple(c.size))
            if key in first:
                c.share = first[key]          # the same painter at the same size: one net serves both
            else:
                first[key] = c
    cubes = [c for b in bones for c in b.cubes if not hasattr(c, "share")]
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
            src = c
            while hasattr(src, "share"):
                src = src.share
            c.uv = src.uv
    return max(tw, 2), max(th, 2)


def scale_for(tw, th, frames=1, cap=MAX_SCALE):
    """The largest power-of-two texel density whose frame and strip stay within the client's limits."""
    if max(tw, th) > MAX_UV:
        raise SystemExit(f"UV space {tw}x{th} too large")
    for s in (8, 4, 2, 1):
        if s <= cap and max(tw, th) * s <= MAX_FRAME_PX and th * s * frames <= MAX_STRIP_PX:
            return s
    raise SystemExit(f"texture {tw}x{th} x {frames} frames too large")


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
            if c.faces is not None:
                paint_faces(c, img, em, scale)
                continue
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


FACE_DIMS = {"top": lambda w, h, d: (w, d), "bottom": lambda w, h, d: (w, d), "west": lambda w, h, d: (d, h),
             "east": lambda w, h, d: (d, h), "north": lambda w, h, d: (w, h), "south": lambda w, h, d: (w, h)}
FACE_JSON = {"top": "up", "bottom": "down", "west": "west", "north": "north", "east": "east", "south": "south"}


def paint_faces(c, img, em, scale):
    """A per-face cube: each painted face drawn at its own size, turned by its rotation into its UV rect."""
    for face, (rect, rot, painted) in c.faces.items():
        if not painted:
            continue
        fw, fh = FACE_DIMS[face](*c.size)
        tmp = np.zeros((fh * scale, fw * scale, 4), np.float32)
        tem = np.zeros_like(tmp)
        cv = Canvas(tmp, tem, face, c, False, scale)
        c.paint(face, cv)
        a, e = np.rot90(cv.arr, rot), np.rot90(cv.em, rot)
        x0, y0 = int(round(rect[0] * scale)), int(round(rect[1] * scale))
        x1, y1 = int(round(rect[2] * scale)), int(round(rect[3] * scale))
        if (y1 - y0, x1 - x0) != a.shape[:2]:
            raise SystemExit(f"face {face}: rect {rect} doesn't fit a {fw}x{fh} face turned {rot}")
        img[y0:y1, x0:x1] = np.clip(a, 0, 1)
        em[y0:y1, x0:x1] = np.clip(e, 0, 1)


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
         scale_cap=MAX_SCALE, physics=None, layout=None):
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
                      scale_cap=scale_cap, physics=physics, layout=layout))


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
        cv.em[:top_px] = 0
    if bottom_px:
        cv.em[-bottom_px:] = 0
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


CAPE_VIEW = {"yaw": 200, "pitch": 8, "frame": [(-13, -19), (13, 10)], "margin": 1.0}


# ---------------------------------------------------------------------------------------------- special (animated) art
# Special items are drawn at 16 texels per unit (capes) or at least the regular items' 8, with 16-24 frames that loop
# seamlessly: everything that moves does so by whole cycles per loop (periodic noise slid by whole periods, sines with
# whole-number frequencies), so the last frame runs into the first without a jump. Colours are posterised into crisp
# pixel-art bands (ordered dithering only where two bands meet), hot or bright parts also go to emissive.png.

# A cape laid out by hand (per-face UVs) so 24 frames at 16 texels per unit fit the client's 4096 px strip: the outer
# face and the lining, each turned a quarter (16 x 10 units), side by side; the thin edges sample the collar trim.
CAPE_HD = (32, 10)
CAPE_HD_FX = {"frames": 24, "scale": 16}


def cape_bones_hd(outer, lining_fn):
    def p(face, cv):
        (outer if face == "north" else lining_fn)(cv)
    edge = ([0.125, 4.0, 0.375, 4.25], 0, False)
    faces = {"north": ([0, 0, 16, 10], 1, True), "south": ([16, 0, 32, 10], 1, True),
             "west": edge, "east": edge, "top": edge, "bottom": edge}
    return [Bone("cape", "body", (0, 0, 2), cubes=[Cube((-5, 0, 1), (10, 16, 1), p, faces=faces)], anim={"type": "cape"})]


def bayer(h, w):
    return np.tile(BAYER, (h // 4 + 1, w // 4 + 1))[:h, :w]


def levels(v, n, edge=0.35):
    """0..1 -> n crisp levels (0..n-1). Flat bands; ordered dithering only across the `edge` part of each step."""
    x = np.clip(v, 0, 1) * (n - 1)
    lo = np.floor(x)
    f = x - lo
    f = np.clip((f - 0.5) / max(edge, 1e-3) + 0.5, 0, 1)        # mostly 0 or 1, a dithered ramp in between
    up = f > (bayer(*v.shape) + 0.5)
    return np.clip(lo + up, 0, n - 1).astype(np.int32)


def palette(idx, cols):
    pal = np.stack([hexc(c) if isinstance(c, str) else np.asarray(c, np.float32) for c in cols])
    return pal[np.clip(idx, 0, len(cols) - 1)]


def spark_dot(cv, x, y, r, color, a=1.0, glow=True, halo=None):
    """A crisp round dot (centre x, y in texels, radius r texels), optionally with a 1-texel softer halo colour."""
    yy, xx = cv.grid()
    d = np.sqrt((xx + 0.5 - x) ** 2 + (yy + 0.5 - y) ** 2)
    if halo is not None:
        m = (d < r + 1.2) & (d >= r)
        c = hexc(halo)
        cv.arr[m, :3] = mix(cv.arr[m, :3], c[:3], 0.7 * a)
        if glow:
            em_from(cv, m * 0.6 * a, halo)
    m = d < r
    c = hexc(color)
    cv.arr[m, :3] = mix(cv.arr[m, :3], c[:3], a)
    if glow:
        em_from(cv, m * a, color)


def hd_trim(cv, top, bottom, color, light, stitch=None):
    """Collar and hem binding for the HD capes: a bevelled band with a highlight line and optional gold stitches."""
    c, l = hexc(color), hexc(light)
    s = cv.s
    for rows, flip in ((top, False), (bottom, True)):
        if not rows:
            continue
        k = np.linspace(0, 1, rows)[:, None, None]
        band = mix(l, c, smooth(k)) if not flip else mix(c, c * 0.55, k)
        sl = slice(0, rows) if not flip else slice(cv.h - rows, cv.h)
        cv.arr[sl, :, :3] = band[..., :3]
        cv.arr[sl, :, 3] = 1
        cv.em[sl] = 0
        edge = rows if not flip else cv.h - rows - 1
        cv.arr[edge, :, :3] *= 0.45                                       # a dark seam line
    if stitch and top:
        x = np.arange(cv.w)
        m = (x % (s // 2)) < s // 4
        cv.arr[top // 2, m, :3] = hexc(stitch)[:3]


def lining_hd(color_top, color_bottom, quilt):
    """The HD capes' lining: a banded satin gradient with crisp quilting (no noise: it repeats in every frame)."""
    def p(cv):
        t = cv.yy()
        n = 7
        cv.arr[:] = palette(levels(t, n, 0.5), [mix(hexc(color_top), hexc(color_bottom), k / (n - 1)) for k in range(n)])
        y, x = cv.units()
        q = hexc(quilt)
        d = (np.abs(((x + y) % 2.5) - 1.25) < 0.07) | (np.abs(((x - y) % 2.5) - 1.25) < 0.07)
        cv.arr[d, :3] = mix(hexc(color_bottom), q, 0.55)[:3]
        knot = (np.abs(((x + y) % 2.5) - 1.25) < 0.16) & (np.abs(((x - y) % 2.5) - 1.25) < 0.16)
        cv.arr[knot, :3] = q[:3]
        cv.arr[..., 3] = 1
        e = max(1, cv.s // 8)
        for sl in (np.s_[:e, :], np.s_[-e:, :], np.s_[:, :e], np.s_[:, -e:]):
            cv.arr[sl + (np.s_[:3],)] = hexc(color_bottom)[:3] * 0.6
    return p


# ---- Hearthfire Cape: layered tongues of fire licking up from the hem, sparks drifting up out of them

FIRE_HD = ["#1b1214", "#2f1515", "#561310", "#8e1d0c", "#c9380c", "#f0601a", "#ff9128", "#ffc547", "#ffec96", "#fffbe9"]


def hearth_outer(cv):
    h, w, s = cv.h, cv.w, cv.s
    ph = cv.phase
    t, u = cv.yy(), cv.xx()
    yb = 1 - t                                                   # 0 at the hem, 1 at the collar
    big = fshift(pnoise(h, w, 2.0, 501, octaves=3, aspect=2.4), -ph * h)          # rises one cape height per loop
    fine = fshift(pnoise(h, w, 4.0, 502, octaves=2, aspect=2.4), -ph * h * 2)     # twice as fast: the flicker
    # tongues: each one's height breathes by whole cycles per loop and its tip sways
    tongues = [(0.08, 0.50, 0.08, 1, 0.1), (0.24, 0.70, 0.10, 2, 0.6), (0.40, 0.56, 0.08, 1, 0.3),
               (0.53, 0.84, 0.11, 1, 0.8), (0.67, 0.62, 0.09, 2, 0.15), (0.82, 0.74, 0.10, 1, 0.45),
               (0.95, 0.48, 0.07, 2, 0.7)]
    lean = (big - 0.5) * 0.16 * np.sqrt(yb) + (fine - 0.5) * 0.05 * yb
    xw = u + lean
    prof = np.full_like(u, 0.2)
    for cx, hgt, wid, m, p0 in tongues:
        sway = 0.025 * math.sin(math.tau * (ph * m + p0))
        H = hgt * 1.15 * (0.82 + 0.18 * math.sin(math.tau * (ph * m + p0 * 1.7)))
        narrow = wid * (1 - 0.55 * np.clip(yb / H, 0, 1))         # tongues taper to a point
        prof = np.maximum(prof, H * np.exp(-((xw - cx - sway * yb * 3) / narrow) ** 2))
    heat = 1 - yb / np.maximum(prof, 1e-3)
    heat += (big - 0.5) * 0.35 + (fine - 0.5) * 0.3
    # loose licks that break off above the tips and rise away
    lick = smooth((fine - 0.66) / 0.08) * smooth((heat + 0.35) / 0.2) * (heat < 0.05) * (yb < 0.92)
    heat = np.maximum(heat, lick * 0.28)
    lvl = levels(np.clip(heat, 0, 1) ** 1.15, 9, 0.3) + (heat > 0.0)
    lvl = np.where(heat > 0.0, np.maximum(lvl, 2), 0)
    # cloth: charcoal wool warmed by the fire below; smoke (dark, a little brown) hangs just above the flames
    warm = smooth(1 - yb / 0.95) * (0.85 + 0.3 * (big - 0.5))   # the fire's warm light on the wool, fading upward
    cloth_c = palette(levels(warm, 5, 0.6), ["#161014", "#1d1216", "#271418", "#351716", "#461a14"])
    fire = palette(lvl, FIRE_HD)
    cv.arr[:] = np.where((lvl > 0)[..., None], fire, cloth_c)
    cv.arr[..., 3] = 1
    y, x = cv.grid()
    twill = ((x + y) % 6 == 0) & (lvl == 0)                     # a crisp wool twill on the cloth
    cv.arr[twill, :3] *= 0.86
    em_rgb(cv, fire, np.clip((lvl - 2) / 4.0, 0, 1))
    # sparks: each rises the whole cape a whole number of times per loop, wobbling, cooling from yellow to red
    r = np.random.default_rng(77)
    for i in range(22):
        x0, y0, m = r.random(), r.random(), int(r.choice([1, 1, 2]))
        f = (y0 + ph * m) % 1.0                                   # 0 (just out of the flames) .. 1 (gone)
        yy = 0.35 + f * 0.62
        xx = x0 + 0.035 * math.sin(math.tau * (f * 2 + x0))
        if f > 0.9:
            continue
        col = "#fff3b0" if f < 0.3 else "#ffc24a" if f < 0.6 else "#ff7a2a"
        spark_dot(cv, xx * w, (1 - yy) * h, 1.2 if f < 0.5 else 0.9, col, 1.0, halo="#a8300e" if f < 0.6 else None)
    hd_trim(cv, s // 2 + 2, s // 3, "#2e1c14", "#6a4a2a", stitch="#e0a640")
    ao_edges(cv, 0.25, width=s // 4)


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
    heat = np.clip((f - 0.42) / 0.55, 0, 1)
    return heat * (0.72 + 0.28 * smooth((n2 - 0.35) / 0.4))


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
            ey = (ey - cv.phase * cv.h * speed) % cv.h          # a whole height per loop: seamless
        s = 0.5 + r.random() * 0.9
        m = np.exp(-((x - ex) ** 2 + (y - ey) ** 2) / (s * s))
        f = ey / cv.h
        em_from(cv, m * (0.4 + 0.6 * f) * smooth(f / 0.15) * smooth((1 - f) / 0.1), "#ffcf6a")   # fade at the wrap
    em_rgb(cv, ramp(heat, FIRE), smooth((heat - 0.6) / 0.3) * 0.75)
    trim(cv, cv.s // 2 + 1, cv.s // 2 + 1, "#3a2418", stitch="#d9a441")
    bevel(cv, 0.05, 0.12)


item("ember_mantle", "Ember Mantle", "CAPE", "REGULAR", 600,
     "A charcoal cape that smoulders into molten orange at the hem, with embers that glow in the dark.",
     cape_bones(ember_outer, lining("#2a1412", "#120707", "#5a2a1a")), view=CAPE_VIEW)

item("hearthfire_cape", "Hearthfire Cape", "CAPE", "SPECIAL", None,
     "Living flames climb and flicker up from the hem, throwing off embers that rise and glow in the dark.",
     cape_bones_hd(hearth_outer, lining_hd("#2e1512", "#100606", "#6a3420")), layout=CAPE_HD,
     view=CAPE_VIEW, animation=dict(CAPE_HD_FX, frametime=1.5, interpolate=False))


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

# ---- Riptide Cape: rows of rolling waves (sharp trochoid crests), foam caps that curl forward, lace and sparkles

WATER_HD = ["#9af0fb", "#4fd0ec", "#25a8d8", "#1680bb", "#0f5f98", "#0b4477", "#082f59", "#061f40"]


def trochoid(u, lam, A, q, shift):
    """Height (t units, negative = up) of a sharp-crested wave of wavelength lam at u, slid right by `shift`; also the
    position within the wave (-lam/2..lam/2, 0 at the crest)."""
    b = np.linspace(-math.pi, math.pi, 4096)
    xs = lam / math.tau * (b - q * np.sin(b))
    ys = -A * np.cos(b)
    uu = ((u - shift + lam / 2) % lam) - lam / 2
    return np.interp(uu, xs, ys), uu


def riptide_outer(cv):
    h, w, s = cv.h, cv.w, cv.s
    ph = cv.phase
    t, u = cv.yy(), cv.xx()
    y, x = cv.grid()
    cv.arr[:] = palette(levels(smooth(t * 1.1), 8, 0.5), WATER_HD[2:] + WATER_HD[-1:] * 2)
    foam_all = np.zeros_like(t)
    rows = [  # y0, wavelength (cape widths; x 160 px a whole number), amplitude, steepness, speed (wavelengths per loop)
        (0.075, 0.2, 0.012, 0.5, 1), (0.17, 0.25, 0.018, 0.55, 1), (0.29, 0.3125, 0.026, 0.6, 1),
        (0.43, 0.375, 0.034, 0.65, 1), (0.59, 0.5, 0.043, 0.7, 1), (0.77, 0.625, 0.05, 0.75, 1), (0.95, 0.5, 0.05, 0.75, 2)]
    for k, (y0, lam, A, q, m) in enumerate(rows):
        shift = ph * lam * m + k * 0.13
        bob = 0.006 * math.sin(math.tau * (ph + k * 0.27))
        crest, uu = trochoid(u, lam, A, q, shift)
        env = 0.7 + 0.3 * np.sin(math.tau * (u + k * 0.37))           # a still envelope: waves swell and ebb as they pass
        crest = crest * env + y0 + bob
        d = t - crest                                             # depth below this row's surface (t units)
        band = 0.08 + 0.05 * k / len(rows)
        below = d >= 0
        shade = np.clip(d / band, 0, 1) * 0.7 + 0.3 * (0.5 + 0.5 * np.sin(math.tau * uu / lam))
        body = palette(levels(shade, 6, 0.5) + (1 if k < 3 else 2), WATER_HD)
        cv.arr[below] = body[below]
        # foam cap: thick at the crest, curling forward (to the right) and down; a thin line along the rest
        lip = np.exp(-((uu - lam * 0.06) / (lam * 0.09)) ** 2)
        fw = (2.0 + 7.0 * lip * (0.5 + 0.5 * k / len(rows))) / h
        foam = below & (d < fw)
        shadow = below & (d >= fw) & (d < fw + 1.6 / h)
        lacep = hash_noise(h // 2 + 1, int(round(lam * w)) // 2, 600 + k)      # 2 x 2 texel flecks
        col = ((uu / lam + 0.5) * lacep.shape[1]).astype(int) % lacep.shape[1]
        lace = below & (d >= fw + 1.6 / h) & (d < band * 0.45) & (uu < lam * 0.25) & \
            (lacep[(y // 2).astype(int) - int(round(crest.min() * h / 2)), col] > 0.7 + 0.3 * smooth(d / (band * 0.45)))
        cv.arr[shadow, :3] = hexc("#7fd6ee")[:3]
        cv.arr[lace, :3] = hexc("#c8f4ff")[:3]
        cv.arr[foam, :3] = hexc("#f4feff")[:3]
        foam_all = np.where(below, foam * 1.0 + lace * 0.5, foam_all)
        # this row hides the rows behind it below its surface
    em_from(cv, foam_all * 0.38, "#bff6ff")
    # sparkles riding the crests, twinkling by whole cycles per loop
    r = np.random.default_rng(91)
    for i in range(26):
        k = int(r.integers(0, len(rows)))
        y0, lam, A, q, m = rows[k]
        sx = (r.random() + ph * lam * m) % 1.0
        b = 0.5 + 0.5 * math.cos(math.tau * (ph * int(r.choice([2, 3])) + r.random()))
        if b < 0.45:
            continue
        crest, _ = trochoid(np.array([[sx]]), lam, A, q, ph * lam * m + k * 0.13)
        sy = float(crest[0, 0]) + y0 + 2.0 / h
        glint(cv, sx * w, sy * h, 2.2 + 2.5 * b, "#ffffff", b)
    hd_trim(cv, s // 2 + 2, s // 4, "#b89a5a", "#f2dca0")
    y_ = np.arange(cv.w)
    rope = ((y_[None, :] + np.arange(s // 2 + 2)[:, None]) % 6) < 3
    cv.arr[:s // 2 + 2][rope, :3] *= 0.78                                # a twisted rope collar
    ao_edges(cv, 0.25, width=s // 4)


item("riptide_cape", "Riptide Cape", "CAPE", "SPECIAL", None,
     "Waves roll across the cape for real: crests rise and fall, foam glitters and the water never stops moving.",
     cape_bones_hd(riptide_outer, lining_hd("#0f3350", "#05142a", "#2a6a8a")), layout=CAPE_HD,
     view=CAPE_VIEW, animation=dict(CAPE_HD_FX, frametime=2, interpolate=True))


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


# ---- Galaxy Flow Cape: a two-armed spiral turning half round per loop (so it loops), nebulae that slowly swell and
# ebb, twinkling stars and a shooting star

GALAXY_HD = ["#090725", "#1a0f4a", "#2c1670", "#47209a", "#6d2fc4", "#9a48dc", "#cc6ee6", "#f2a8ee", "#ffe2f4", "#fffaf0"]


def twinkle_stars(cv, seed, n, ph, big=4, cols=("#ffffff", "#cfe0ff", "#ffe7c4", "#ffd0f0"), avoid=None):
    """Crisp 1-2 texel stars that twinkle by whole cycles per loop, plus a few four-point sparkles."""
    r = np.random.default_rng(seed)
    s = cv.s
    for i in range(n):
        x, y = r.random() * cv.w, r.random() * cv.h
        if avoid is not None and avoid(x / cv.w, y / cv.h):
            continue
        tw = 0.5 + 0.5 * math.cos(math.tau * (ph * int(r.choice([1, 2, 3])) + r.random()))
        c = hexc(cols[i % len(cols)])
        xi, yi = int(x), int(y)
        if i < big:
            glint(cv, x, y, s * (0.5 + 0.6 * tw), cols[i % len(cols)], 0.55 + 0.45 * tw)
            continue
        sz = 2 if r.random() < 0.3 else 1
        a = 0.35 + 0.65 * tw
        cv.arr[yi:yi + sz, xi:xi + sz, :3] = mix(cv.arr[yi:yi + sz, xi:xi + sz, :3], c[:3], a)
        em_from(cv, np.pad(np.ones((min(sz, cv.h - yi), min(sz, cv.w - xi))) * a,
                           ((yi, max(0, cv.h - yi - sz)), (xi, max(0, cv.w - xi - sz))))[:cv.h, :cv.w], c)


def galaxy_hd_outer(cv):
    h, w, s = cv.h, cv.w, cv.s
    ph = cv.phase
    y, x = cv.grid()
    t = cv.yy()
    cx, cy = w * 0.5, h * 0.42
    dx, dy = (x + 0.5 - cx) / w, (y + 0.5 - cy) / w
    r = np.sqrt(dx * dx + dy * dy) + 1e-4
    ang = np.arctan2(dy, dx)
    tilt = np.sqrt(dx * dx + (dy * 1.25) ** 2) + 1e-4                 # a slightly tipped disc
    swirl = ang - np.log(tilt) * 2.2 - ph * math.pi                    # two arms: half a turn per loop looks the same
    arms = (0.5 + 0.5 * np.cos(2 * swirl)) ** 2.2 * np.exp(-tilt / 0.32)
    dust = pnoise(h, w, 5.0, 711, octaves=3)
    lanes = (0.5 + 0.5 * np.cos(2 * swirl + 0.9)) ** 6 * np.exp(-tilt / 0.25)          # dark dust lanes trail the arms
    core = np.exp(-(tilt / 0.09) ** 2)
    # nebulae: soft clouds that breathe a little (a second field swells in and out by a whole cycle per loop)
    n1, n2 = pnoise(h, w, 1.6, 712, octaves=5), pnoise(h, w, 2.4, 713, octaves=4)
    neb = n1 + (n2 - 0.5) * 0.3 * math.cos(math.tau * ph)
    neb = smooth((neb - 0.45) / 0.4) * (0.3 + 0.7 * smooth(tilt / 0.4))
    I = arms * (0.75 + 0.9 * (dust - 0.5)) * 1.3 - lanes * 0.3 + core * 1.1 + neb * 0.3
    bg = levels(smooth(t * 1.05), 3, 0.6)                              # deep indigo at the collar, violet at the hem
    lvl = levels(np.clip(I, 0, 1) ** 0.8, len(GALAXY_HD) - 1, 0.35)
    lvl = np.maximum(lvl, bg) + (lvl > bg) * 0
    col = palette(lvl, GALAXY_HD)
    # a second, teal-blue nebula off to the side for colour contrast
    n3 = smooth((pnoise(h, w, 1.8, 714, octaves=5) - 0.5) / 0.35) * smooth((tilt - 0.25) / 0.2)
    tl = levels(n3 * 0.8, 4, 0.5)
    teal = (tl > 0) & (lvl <= 2)
    col[teal] = palette(tl, ["#000000", "#0f2a52", "#164a72", "#1f7090"])[teal]
    cv.arr[:] = col
    cv.arr[..., 3] = 1
    em_rgb(cv, col, np.clip((lvl - 2) / 4.0, 0, 1) * 0.9)
    twinkle_stars(cv, 77, 120, ph, big=6, avoid=lambda u, v: abs(u - 0.5) < 0.12 and abs(v - 0.46) < 0.06)
    # a shooting star crosses once per loop (only in part of the loop)
    f = (ph * 1.0 + 0.3) % 1.0
    if f < 0.3:
        g = f / 0.3
        hx, hy = (0.95 - g * 0.8) * w, (0.08 + g * 0.3) * h
        for j in range(int(s * 1.8)):
            a = 1 - j / (s * 1.8)
            px, py = int(hx + j * 0.9), int(hy - j * 0.38)
            if 0 <= px < w and 0 <= py < h:
                cv.arr[py, px, :3] = mix(cv.arr[py, px, :3], hexc("#fff6e0")[:3], a)
                cv.em[py, px] = np.concatenate([hexc("#fff6e0")[:3] * 1.0, [a]])
    hd_trim(cv, s // 2 + 2, s // 3, "#5a4aa8", "#c8bcff", stitch="#f0e8ff")
    ao_edges(cv, 0.25, width=s // 4)


item("galaxy_flow_cape", "Galaxy Flow Cape", "CAPE", "SPECIAL", None,
     "A whole spiral galaxy on your back: its glowing arms slowly turn, star dust drifts and the stars twinkle.",
     cape_bones_hd(galaxy_hd_outer, lining_hd("#160f3a", "#06051a", "#4a3aa0")), layout=CAPE_HD, view=CAPE_VIEW,
     animation=dict(CAPE_HD_FX, frametime=4, interpolate=True))


# ---- Polar Lights Cape: curtains of light with a bright lower hem and rays rising from it, rippling sideways over
# snowy peaks

AURORA_G = ["#0a2a3a", "#0d4a4a", "#118060", "#1fb878", "#4af09a", "#a8ffcc", "#eafff4"]
AURORA_V = ["#160f38", "#1c1450", "#3a2280", "#6a36b8", "#9a5ae0", "#d0a0ff", "#f4e8ff"]


def polar_hd_outer(cv):
    h, w, s = cv.h, cv.w, cv.s
    ph = cv.phase
    t, u = cv.yy(), cv.xx()
    y, x = cv.grid()
    sky = palette(levels(smooth(t / 0.85), 5, 0.6), ["#030915", "#06132a", "#0a1d3a", "#0e2848", "#123252"])
    cv.arr[:] = sky
    cv.arr[..., 3] = 1
    twinkle_stars(cv, 131, 70, ph, big=3)
    glow = np.zeros_like(t)
    hue = np.zeros_like(t)                                           # 0 green .. 1 violet
    curtains = [(0.42, 0.06, 1.0, 1, 0.0, 0.6), (0.56, 0.05, 1.5, -1, 0.4, 0.7), (0.3, 0.045, 2.0, 1, 0.7, 0.45)]
    for k, (y0, A, f, m, p0, hgt) in enumerate(curtains):
        base = y0 + A * np.sin(math.tau * (u * f + ph * m + p0)) + A * 0.35 * np.sin(math.tau * (u * f * 2.3 + p0 * 3 - ph * m * 2))
        up = base - t                                                # > 0 above the curtain's hem
        fall = np.exp(-np.clip(up, 0, None) / (hgt * 0.45)) * (up > -1.5 / h)
        rays = (0.5 + 0.5 * np.sin(math.tau * (u * 9 + 0.6 * np.sin(math.tau * (u * f + ph * m + p0)) + ph * 2 * m + k * 0.3))) ** 1.5
        rays = 0.4 + 0.6 * rays * (0.6 + 0.4 * np.sin(math.tau * (u * 4 - ph * m + k)))
        hem = np.exp(-(up * h / 2.5) ** 2) * (0.7 + 0.3 * np.sin(math.tau * (u * 3 + ph * m)))   # the bright lower edge
        a = np.clip(fall * rays * (0.95 - 0.15 * k) + hem * 0.4, 0, 1)
        hue = np.where(a > glow, smooth((up - 0.03) / (hgt * 0.5)), hue)
        glow = np.maximum(glow, a)
    lv = levels(glow, 7, 0.4)
    g, v = palette(lv, AURORA_G), palette(lv, AURORA_V)
    col = np.where((hue > 0.45)[..., None], v, g)
    lit = lv >= 2
    cv.arr[lit] = col[lit]
    em_rgb(cv, col, np.clip((lv - 1) / 4.0, 0, 1) * 0.9)
    # mountains: two ranges, snow caps, the near range lit green on its slopes by the aurora
    for k, (top, amp, c_rock, c_snow) in enumerate([(0.74, 0.07, "#0f2236", "#a9c6e0"), (0.83, 0.06, "#081422", "#dceeff")]):
        ridge = top + amp * (np.abs(np.sin(u * math.pi * (3 + k * 2) + k)) * 0.7 + 0.3 * np.sin(u * 17 + k * 2))
        m = t > ridge
        cv.arr[m, :3] = hexc(c_rock)[:3]
        cv.em[m] = 0
        snow = m & (t < ridge + 0.025 + 0.015 * np.sin(u * 41 + k))
        cv.arr[snow, :3] = hexc(c_snow)[:3]
        rim = m & ~snow & (t < ridge + 0.05) & (((x + y) % 3) == 0)
        cv.arr[rim, :3] = hexc("#1f5a5a")[:3]
    hd_trim(cv, s // 2 + 2, s // 3, "#7a9ab0", "#e8f6ff", stitch="#ffffff")
    ao_edges(cv, 0.25, width=s // 4)


item("polar_lights_cape", "Polar Lights Cape", "CAPE", "SPECIAL", None,
     "Curtains of green, cyan and violet light ripple over snowy peaks, glowing softly in the dark.",
     cape_bones_hd(polar_hd_outer, lining_hd("#0d2236", "#040c18", "#2a5a7a")), layout=CAPE_HD, view=CAPE_VIEW,
     animation=dict(CAPE_HD_FX, frametime=3, interpolate=True))


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
    g = cv.s * 0.4
    inset = [(sx0 + g, sy0 + g), (sx1 - g, sy0 + g), (sx1 - g, sy0 + (sy1 - sy0) * 0.55), (W * 0.5, sy1 - g * 1.5), (sx0 + g, sy0 + (sy1 - sy0) * 0.55)]
    d.polygon([(a * k, b * k) for a, b in inset], fill=(236, 228, 204, 255))
    # the oak: trunk, roots and a round canopy of lobes
    tx, ty = W * 0.5, H * 0.58
    u = cv.s / 5
    d.rectangle(((tx - 2 * u) * k, (H * 0.42) * k, (tx + 2 * u) * k, ty * k), fill=(110, 70, 36, 255))
    d.polygon([((tx - 5 * u) * k, ty * k), ((tx + 5 * u) * k, ty * k), ((tx + 2 * u) * k, (ty - 3 * u) * k), ((tx - 2 * u) * k, (ty - 3 * u) * k)],
              fill=(110, 70, 36, 255))
    u = cv.s / 5
    for cx, cy, R in [(0.5, 0.33, 10), (0.36, 0.38, 7.5), (0.64, 0.38, 7.5), (0.4, 0.29, 7.5), (0.6, 0.29, 7.5), (0.5, 0.24, 7.5),
                      (0.5, 0.41, 7)]:
        R *= u
        d.ellipse(((W * cx - R) * k, (H * cy - R) * k, (W * cx + R) * k, (H * cy + R) * k), fill=(46, 120, 60, 255))
    for cx, cy, R in [(0.44, 0.27, 3), (0.58, 0.31, 2.5), (0.4, 0.36, 2.2), (0.53, 0.22, 2)]:
        R *= u
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



# Cape cloth: how each one moves (see "physics" in the README). Heavy cloaks are stiffer, silk and flags flutter more.
CAPE_PHYSICS = {
    "ember_mantle": (0.5, 1.0, 1.0), "hearthfire_cape": (0.45, 1.1, 1.0), "tidal_weave": (0.45, 1.0, 1.1),
    "riptide_cape": (0.4, 1.1, 1.2), "starlit_veil": (0.42, 1.1, 1.1), "galaxy_flow_cape": (0.42, 1.1, 1.1),
    "polar_lights_cape": (0.45, 1.0, 1.0), "chevron_standard": (0.55, 1.0, 1.0), "checkered_rally": (0.35, 1.3, 1.3),
    "highland_plaid": (0.6, 0.9, 0.9), "blossom_drift": (0.38, 1.2, 1.2), "pumpkin_night": (0.5, 1.0, 1.0),
    "frostfall_cloak": (0.68, 0.8, 0.8), "dragonhide_cape": (0.62, 0.9, 0.85), "neon_horizon": (0.5, 1.0, 1.0),
    "oakheart_banner": (0.58, 1.0, 0.9), "sweetheart_cape": (0.42, 1.1, 1.1),
}
for _it in ITEMS:
    if _it["slot"] == "CAPE":
        s, l, w = CAPE_PHYSICS[_it["id"]]
        _it["physics"] = {"stiffness": s, "lift": l, "sway": w}

# ---------------------------------------------------------------------------------------------- wings
# Swept back from the shoulder blades. Right-hand bones are drawn here; wing_pair() adds the mirrored left wing.

WING_VIEW = {"yaw": 200, "pitch": 10, "wing_open": -14, "frame": [(-19, -21), (19, 10)], "margin": 1.0}


def wing_root(face, cv):
    """0 at the wing tip, 1 at the shoulder, whichever side of the plane this face is (see ModelPart.Cuboid UVs)."""
    return cv.xx() if face == "north" else 1 - cv.xx()


class WingDraw:
    """A supersampled drawing surface for a wing face in wing coordinates: r (0 tip .. 1 shoulder), t (0 top .. 1 bottom)."""

    def __init__(self, face, cv, ss=4):
        self.L = layer(cv, ss)
        self.d = self.L.draw
        self.face, self.cv, self.k = face, cv, ss

    def P(self, r, t):
        x = r if self.face == "north" else 1 - r
        return (x * self.cv.w * self.k, t * self.cv.h * self.k)

    def poly(self, pts, fill):
        self.d.polygon([self.P(r, t) for r, t in pts], fill=fill)

    def line(self, pts, fill, width):
        self.d.line([self.P(r, t) for r, t in pts], fill=fill, width=max(1, int(width * self.k)), joint="curve")

    def done(self, blur=0):
        return self.L.done(blur)


def wing_anim(base=32, amp=12, speed=1.6):
    return {"type": "wing", "side": 1, "base": base, "amp": amp, "speed": speed}


def feather(c_root, c_tip, glow_tip=None, alpha=1.0, edge="#000000", seed=0):
    """One feather on a flat cube: a quill down the middle, barbs angled towards the tip, a rounded tip, darker edges."""
    def p(face, cv):
        t, x = cv.yy(), cv.xx()
        half = np.abs(x - 0.5) * 2
        shape = 1 - smooth((t - 0.72) / 0.28) ** 1.6
        inside = half < np.maximum(shape, 0.0) * 0.98 + 0.02
        col = ramp(t, [(0, c_root), (1, c_tip)])
        barbs = np.sin((t * cv.h / max(1, cv.s * 0.45) - half * 3.0) * math.pi)
        col[..., :3] *= (0.92 + 0.08 * barbs)[..., None]
        col[..., :3] *= (1 - 0.28 * smooth((half - 0.55) / 0.45))[..., None]
        quill = half < 1.6 / cv.w
        col[quill, :3] = np.clip(col[quill, :3] * 1.25 + 0.08, 0, 1)
        n = pnoise(cv.h, cv.w, 2.0, 900 + seed, octaves=2)
        col[..., :3] *= (0.94 + 0.12 * n)[..., None]
        col[..., 3] = alpha * inside
        cv.arr[:] = col
        if glow_tip:
            em_from(cv, smooth((t - 0.6) / 0.35) * inside, glow_tip, 0.9)
    return p


def feather_row(painters, x_start, x_end, y, z, length, width=2, step=None):
    """Feathers hanging from a wing arm, shoulder to tip; `length` is a function of 0 (shoulder) .. 1 (tip)."""
    cubes = []
    n = len(painters)
    for i, pnt in enumerate(painters):
        f = i / max(1, n - 1)
        x = x_start + (x_end - x_start) * f
        L = int(round(length(f)))
        cubes.append(Cube((x - width, y + f * 0.0, z + (i % 2) * 0.06), (width, L, 0), pnt))
    return cubes


# -------- Aurora (upgraded): a see-through membrane with glowing veins, a crystal spar and a gem at the joint

def aurora_membrane(face, cv, pulse=None):
    r, t = wing_root(face, cv), cv.yy()
    W = WingDraw(face, cv)
    pts = [(0.02, 0.06), (1.0, 0.0), (1.0, 0.78), (0.86, 0.94), (0.7, 0.82), (0.55, 0.97), (0.38, 0.8), (0.22, 0.86),
           (0.1, 0.58), (0.0, 0.3)]
    W.poly(pts, (255, 255, 255, 255))
    mask = W.done()[..., 3]
    col = ramp(smooth(1 - r * 0.85 + t * 0.25), [(0, "#46f5d6"), (0.5, "#5aa8ff"), (1, "#8a52ff")])
    col[..., 3] = (0.78 - 0.32 * smooth(t)) * mask
    # cells between veins, like a dragonfly's wing
    y, x = cv.grid()
    n = pnoise(cv.h, cv.w, 5.0, 301, octaves=1)
    cell = np.abs(np.sin(n * 18))
    col[..., :3] = np.clip(col[..., :3] * (0.9 + 0.2 * cell)[..., None], 0, 1)
    cv.arr[:] = col
    # veins fan out from the shoulder joint
    ang = np.arctan2(t + 0.02, (1.02 - r))
    veins = np.zeros_like(t)
    for a in (0.12, 0.38, 0.66, 0.95, 1.25):
        veins = np.maximum(veins, smooth(1 - np.abs(ang - a) / 0.03))
    veins = np.maximum(veins, smooth(1 - cell / 0.08) * 0.5)
    veins *= mask * (r > 0.04)
    edge = mask * (1 - smooth(blur(mask[..., None], 1.0)[..., 0] * 1.6 - 0.6))
    v = np.clip(veins + edge, 0, 1)
    cv.arr[..., :3] = mix(cv.arr[..., :3], hexc("#d8fff6")[:3], v[..., None])
    cv.arr[..., 3] = np.maximum(cv.arr[..., 3], v * 0.95)
    k = 0.85
    if pulse is not None:
        dist = np.sqrt((1 - r) ** 2 + t ** 2)
        k = 0.25 + 0.75 * np.exp(-((((dist - cv.phase * 1.4) + 0.7) % 1.4 - 0.7) / 0.12) ** 2)
        k = np.maximum(k, 0.3)
        em_from(cv, mask * k * 0.35, pulse)
    em_from(cv, v * k, "#c9fff4")


def crystal_spar(face, cv):
    t = cv.yy()
    cv.arr[:] = ramp(t, [(0, "#f4fffd"), (0.5, "#9ff5e6"), (1, "#3fb8c8")])
    cv.arr[..., 3] = 1
    bevel(cv, 0.2, 0.3)
    em_from(cv, np.ones_like(t) * 0.35, "#bffff4")


def gem_paint(c_hi, c_lo, glow=0.6):
    def p(face, cv):
        t, x = cv.yy(), cv.xx()
        facet = (np.abs(x - 0.5) + np.abs(t - 0.5)) < 0.5
        cv.arr[:] = ramp(t, [(0, c_hi), (1, c_lo)])
        cv.arr[facet, :3] = np.clip(cv.arr[facet, :3] * 1.18, 0, 1)
        cv.arr[0, :, :3] = 1
        cv.arr[..., 3] = 1
        em_from(cv, np.ones_like(t) * glow, c_hi)
        y, xx = cv.grid()
        cv.em[(y < 2) & (xx < 2)] = (1, 1, 1, 1)
    return p


def aurora_wing_bones(membrane=aurora_membrane, anim=None, name="wing_r"):
    anim = anim or wing_anim(32, 12, 1.6)
    right = [Bone(name, "body", (-1.5, 2, 2.5), (0, 0, 0), [
        Cube((-15.5, 0, 2), (14, 15, 0), membrane),
        Cube((-15.5, -0.5, 1.5), (14, 1, 1), crystal_spar),
        Cube((-3.5, -1, 1), (2, 2, 2), gem_paint("#dffcff", "#2a8fd6")),
    ], anim=anim)]
    return wing_pair(right)


item("aurora_wings", "Aurora Wings", "WINGS", "REGULAR", 1100,
     "See-through wings that fade from cyan to violet, with glowing veins, a crystal spar and a slow, easy beat.",
     aurora_wing_bones(), view=WING_VIEW)

# -------- Pulse Wings (special): an energy membrane with crisp feather veins; packets of light race out along every
# vein from the shoulder to the tips while a soft wave of glow follows them across the membrane

PULSE_BODY = ["#3a1690", "#3424a8", "#2c3cbc", "#2458c8", "#1e74d0", "#1a92d8"]


def crisp_mask(W):
    return (W.done()[..., 3] > 0.5).astype(np.float32)


PULSE_FEATHER = ["#2a1066", "#341a8c", "#2c34b0", "#2058c8", "#1c84d8", "#28b4e8", "#5ae0f4"]


def pulse_membrane(face, cv):
    """Energy feathers fanned from the shoulder joint: each a long blade banded from deep violet at the root to cyan at
    the tip, with a bright shaft and outline. Three pulses per loop race up every shaft; the feather's edge lights as
    a pulse passes."""
    r, t = wing_root(face, cv), cv.yy()
    ph = cv.phase
    X, Y = r * cv.w, t * cv.h                                      # wing coordinates in texels (tip .. shoulder)
    J = np.array([0.97 * cv.w, 0.02 * cv.h])
    tips = [(0.0, 0.26), (0.08, 0.55), (0.2, 0.78), (0.37, 0.92), (0.56, 0.98), (0.75, 0.9), (0.9, 0.74)]
    widths = [0.12, 0.14, 0.14, 0.14, 0.13, 0.12, 0.1]
    cv.arr[:] = 0
    lit = np.zeros((cv.h, cv.w), np.float32)
    for (tr, tt), wd in reversed(list(zip(tips, widths))):         # outer feathers first, inner ones overlap them
        T = np.array([tr * cv.w, tt * cv.h])
        L = float(np.hypot(*(T - J)))
        ux, uy = (T - J) / L
        sx = ((X - J[0]) * ux + (Y - J[1]) * uy) / L               # 0 at the joint .. 1 at the tip
        pp = np.abs(-(X - J[0]) * uy + (Y - J[1]) * ux)            # texels from the shaft
        half = wd * cv.w * np.sin(math.pi * np.clip(sx, 0, 1) ** 0.75) * (0.25 + 0.75 * np.clip(sx * 3, 0, 1))
        inside = (sx > 0) & (sx < 1) & (pp < half)
        if not inside.any():
            continue
        k = np.zeros_like(sx)
        for j in range(3):
            pk = ((ph + j / 3) % 1.0) * 1.3 - 0.15
            k = np.maximum(k, np.exp(-((sx - pk) / 0.06) ** 2))
        body = palette(levels(np.clip(sx, 0, 1), 7, 0.5), PULSE_FEATHER)
        cv.arr[inside] = body[inside]
        cv.arr[inside, 3] = 0.68
        edge = inside & (pp >= half - 1.0)
        shaft = inside & (pp < 0.75)
        kl = levels(np.clip(k, 0, 1), 4, 0.3)
        lc = palette(kl, ["#5ac8f0", "#9aeaff", "#dcfcff", "#ffffff"])
        for m in (edge, shaft):
            cv.arr[m] = lc[m]
            cv.arr[m, 3] = 0.95
        lit[inside] = 0
        lit = np.where(edge | shaft, 0.4 + 0.6 * kl / 3.0, lit)
        lit = np.where(inside & ~edge & ~shaft & (kl >= 2), 0.3, lit)     # a soft flash inside, right at the pulse
        cv.arr[inside & ~edge & ~shaft & (kl >= 2), :3] = hexc("#3a9ae8")[:3]
    em_rgb(cv, cv.arr, lit)


def pulse_gem(face, cv):
    gem_paint("#dffcff", "#2a8fd6")(face, cv)
    b = 0.5 + 0.5 * math.cos(math.tau * cv.phase * 3)              # throbs with each pulse
    em_from(cv, np.ones((cv.h, cv.w)) * (0.4 + 0.6 * b), "#9af6ff")


def pulse_wing_bones():
    right = [Bone("wing_r", "body", (-1.5, 2, 2.5), (0, 0, 0), [
        Cube((-15.5, 0, 2), (14, 15, 0), pulse_membrane),
        Cube((-15.5, -0.5, 1.5), (14, 1, 1), crystal_spar),
        Cube((-3.5, -1, 1), (2, 2, 2), pulse_gem),
    ], anim=wing_anim(32, 12, 1.6))]
    return wing_pair(right)


item("pulse_wings", "Pulse Wings", "WINGS", "SPECIAL", None,
     "Energy wings with waves of light pulsing out from your shoulders to the tips, over and over.",
     pulse_wing_bones(), view=WING_VIEW,
     animation={"frames": 20, "frametime": 1.5, "interpolate": False, "scale": 8})


# -------- feathered wings: separate layered feathers on an arm

def feathered_wing(colors, glow=None, arm_color="#e9e4dc", seed=0, big=False):
    """colors: (coverts, secondaries, primaries) each (root, tip). Returns the right wing's cubes."""
    cov, sec, pri = colors
    fc = [feather(cov[0], cov[1], seed=seed + i) for i in range(2)]
    fs = [feather(sec[0], sec[1], seed=seed + 10 + i) for i in range(2)]
    fp = [feather(pri[0], pri[1], glow, seed=seed + 20 + i) for i in range(2)]
    n = 7 if big else 6
    span = 15 if big else 13
    cubes = [Cube((-1.5 - span, -0.5, 1.4), (span, 1, 1), edge_paint(hexc(arm_color)))]
    # primaries: longest towards the tip, the back layer
    cubes += feather_row([fp[i % 2] for i in range(n)], -2.5, -1.5 - span + 2, 0, 2.6,
                         lambda f: (9 if big else 7) + 7 * smooth(f * 1.2) - 3 * smooth((f - 0.8) / 0.2), width=3)
    cubes += feather_row([fs[i % 2] for i in range(n)], -3.5, -1.5 - span + 3, 0, 2.3, lambda f: 7 + 2 * f, width=3)
    cubes += feather_row([fc[i % 2] for i in range(n - 1)], -2.5, -1.5 - span + 2, -0.5, 2.0, lambda f: 4, width=3)
    return cubes


def ashen():
    anim = wing_anim(38, 8, 1.1)
    right = [Bone("feathers_r", "body", (-1.5, 2, 2.5), (0, 0, -12),
                  feathered_wing((("#e2ddd4", "#b8b0a4"), ("#a7a096", "#6a635b"), ("#6b645d", "#ff6a2a")), "#ff9a3a",
                                 seed=1), anim=anim)]
    return wing_pair(right)


item("ashen_feathers", "Ashen Feathers", "WINGS", "REGULAR", 800,
     "Layered grey feathers, each one separate, darkening towards tips that smoulder like embers.",
     ashen(), view=WING_VIEW)


def seraph():
    anim = wing_anim(30, 10, 0.9)
    right = [Bone("feathers_r", "body", (-1.5, 1, 2.5), (0, 0, -16),
                  feathered_wing((("#ffffff", "#f1ecdf"), ("#fbf8f0", "#e4dcc6"), ("#f6f1e4", "#e6c65a")), "#fff1b0",
                                 arm_color="#f2d27a", seed=3, big=True), anim=anim)]
    return wing_pair(right)


item("seraph_wings", "Seraph Wings", "WINGS", "REGULAR", 1000,
     "Great white feathered wings, softly gilded at the tips, that sweep slowly behind you.",
     seraph(), view=dict(WING_VIEW, glow="#fff1c2", glow_strength=0.25))


# -------- membrane wings: dragon and demon

DRAGON_FINGERS = [(0.02, 0.42), (0.2, 0.86), (0.46, 0.98), (0.74, 0.86), (1.0, 0.62)]


def dragon_membrane(c_top, c_bottom, bone_c, vein_c, glow=None, alpha=0.96, storm=False):
    def p(face, cv):
        r, t = wing_root(face, cv), cv.yy()
        W = WingDraw(face, cv)
        wrist = (0.05, 0.04)
        pts = [(1.0, 0.02), wrist]
        for (a, b), (c, d) in zip(DRAGON_FINGERS, DRAGON_FINGERS[1:]):
            pts.append((a, b))
            # the scalloped edge curls in between finger tips
            for s in np.linspace(0.2, 0.8, 4):
                rr, tt = a + (c - a) * s, b + (d - b) * s
                pts.append((rr, tt - math.sin(s * math.pi) * 0.16))
        pts.append(DRAGON_FINGERS[-1])
        W.poly(pts, (255, 255, 255, 255))
        mask = W.done()[..., 3]
        if storm:
            mask = (mask > 0.5).astype(np.float32)                    # special: crisp pixel edges
        col = ramp(t * 0.7 + (1 - r) * 0.3, [(0, c_top), (1, c_bottom)])
        n = pnoise(cv.h, cv.w, 4.0, 400, octaves=4)
        col[..., :3] *= (0.85 + 0.3 * n)[..., None]
        if storm:   # banded storm clouds, darker towards the trailing edge
            cl = levels(np.clip(n * 1.3 - 0.15 - t * 0.35 + (1 - r) * 0.1, 0, 1), 6, 0.4)
            col[..., :3] = palette(cl, ["#0e1220", "#161c2e", "#1f283e", "#2b3652", "#3a4868", "#4e5f84"])[..., :3]
        col[..., 3] = mask * alpha
        cv.arr[:] = col
        # veins and finger bones
        B = WingDraw(face, cv)
        for fr, ft in DRAGON_FINGERS[:-1]:
            B.line([wrist, ((wrist[0] + fr) / 2 + 0.03, (wrist[1] + ft) / 2), (fr, ft)], (255, 255, 255, 255), 1.2)
        bones_m = B.done()[..., 3] * mask
        V = WingDraw(face, cv)
        rr = np.random.default_rng(7)
        for _ in range(9):
            r0, t0 = rr.random() * 0.9 + 0.05, rr.random() * 0.6 + 0.1
            V.line([(r0, t0), (r0 + (rr.random() - 0.5) * 0.15, t0 + 0.12), (r0 + (rr.random() - 0.5) * 0.2, t0 + 0.25)], (255, 255, 255, 255), 0.5)
        veins_m = V.done()[..., 3] * mask * (1 - bones_m)
        cv.arr[..., :3] = mix(cv.arr[..., :3], hexc(vein_c)[:3], (veins_m * 0.55)[..., None])
        bc = ramp(t, [(0, bone_c), (1, "#1a1416")])
        cv.arr[..., :3] = mix(cv.arr[..., :3], bc[..., :3], bones_m[..., None])
        emboss(cv, bones_m, 1.5)
        cv.arr[..., 3] = np.maximum(cv.arr[..., 3], bones_m)
        if glow:
            em_from(cv, veins_m * 0.9, glow)
        if storm:
            storm_fx(face, cv, mask, veins_m)
    return p


def bolt_points(rng, a, b, depth, rough=0.22):
    """A jagged lightning path from a to b (wing coordinates) by midpoint displacement."""
    pts = [a, b]
    for _ in range(depth):
        out = [pts[0]]
        for p0, p1 in zip(pts, pts[1:]):
            dx, dy = p1[0] - p0[0], p1[1] - p0[1]
            L = math.hypot(dx, dy)
            off = (rng.random() - 0.5) * rough * L
            out += [((p0[0] + p1[0]) / 2 - dy / max(L, 1e-6) * off, (p0[1] + p1[1]) / 2 + dx / max(L, 1e-6) * off), p1]
        pts = out
    return pts


# Stormcaller's weather, frame by frame over 24 frames: (first frame, seed, brightness on each following frame)
STORM_STRIKES = [(0, 3, (1.0, 0.35, 0.95, 0.5, 0.18)), (7, 11, (0.85, 0.3, 0.12)), (13, 5, (1.0, 0.4, 0.9, 0.3)),
                 (19, 17, (0.7, 0.9, 0.25))]


def storm_fx(face, cv, mask, veins_m):
    f = int(round(cv.phase * 24)) % 24
    power, seed = 0.0, None
    for f0, sd, seq in STORM_STRIKES:
        if f0 <= f < f0 + len(seq):
            power, seed = seq[f - f0], sd
    flick = 0.12 + 0.1 * math.sin(cv.phase * math.tau * 3)
    em_from(cv, veins_m * (flick * 2.5 + power * 0.6), "#8ac4ff")
    if seed is None:
        return
    rng = np.random.default_rng(seed)
    r0 = 0.2 + rng.random() * 0.6
    main = bolt_points(rng, (r0, 0.03), (r0 + (rng.random() - 0.5) * 0.7, 0.8 + rng.random() * 0.15), 5)
    S = WingDraw(face, cv, ss=1)
    S.line(main, (255, 255, 255, 255), 2.0)
    for _ in range(4):                                            # forks
        i = int(rng.integers(len(main) // 6, len(main) * 3 // 4))
        a = main[i]
        b = (a[0] + (rng.random() - 0.5) * 0.6, a[1] + 0.15 + rng.random() * 0.25)
        fork = bolt_points(rng, a, b, 3, 0.35)
        S.line(fork, (255, 255, 255, 255), 1.0)
        j = len(fork) // 2
        S.line(bolt_points(rng, fork[j], (fork[j][0] + (rng.random() - 0.5) * 0.3, fork[j][1] + 0.1), 2, 0.4),
               (255, 255, 255, 255), 1.0)
    core = (np.asarray(S.L.image, np.float32)[..., 3] > 127) & (mask > 0.5)
    m = core.copy()
    halo1 = (np.roll(m, 1, 0) | np.roll(m, -1, 0) | np.roll(m, 1, 1) | np.roll(m, -1, 1)) & ~m & (mask > 0.5)
    m2 = m | halo1
    halo2 = (np.roll(m2, 1, 0) | np.roll(m2, -1, 0) | np.roll(m2, 1, 1) | np.roll(m2, -1, 1)) & ~m2 & (mask > 0.5)
    # the clouds round the strike light up, in two bands
    if power > 0.8:                                              # the whole wing flashes on the brightest frames
        cv.arr[mask > 0.5, :3] = np.clip(cv.arr[mask > 0.5, :3] * 1.3 + 0.03, 0, 1)
    near = blur(core[..., None].astype(np.float32), 7.0)[..., 0] * 7 * power
    lit = levels(np.clip(near, 0, 1), 3, 0.4)
    for k, c in ((1, "#4a5c88"), (2, "#6a80b4")):
        mm = (lit == k) & (mask > 0.5)
        cv.arr[mm, :3] = hexc(c)[:3]
        em_from(cv, mm * 0.25 * k * power, "#5a7ac8")
    if power > 0.45:
        cv.arr[halo2, :3] = hexc("#3a62c8")[:3]
        em_from(cv, halo2 * 0.5 * power, "#3a62c8")
    cv.arr[halo1, :3] = hexc("#8ec8ff")[:3]
    em_from(cv, halo1 * power, "#8ec8ff")
    cv.arr[core, :3] = hexc("#ffffff")[:3]
    cv.arr[core, 3] = 1
    em_from(cv, core * min(1.0, power + 0.2), "#f4faff")


def dragon_wing(name, colors, glow=None, spikes=False, storm=False, anim=None):
    c_top, c_bottom, bone_c, vein_c, arm_c = colors
    anim = anim or wing_anim(34, 14, 1.2)
    cubes = [Cube((-16.5, -1, 2), (15, 14, 0), dragon_membrane(c_top, c_bottom, bone_c, vein_c, glow, storm=storm)),
             Cube((-16.5, -1.5, 1.5), (15, 1, 1), edge_paint(hexc(arm_c))),
             Cube((-17.5, -3.5, 1.5), (1, 3, 1), metal("#efe6d6", "#6a5a4a", 0.25, False)),      # thumb claw
             Cube((-3.5, -2, 1), (2, 2, 2), edge_paint(hexc(arm_c)))]                           # shoulder joint
    if spikes:
        cubes += [Cube((x, -3.5, 1.5), (1, 2, 1), metal("#3a3036", "#120c10", 0.2, False)) for x in (-13.5, -10.5, -7.5)]
    return wing_pair([Bone(name, "body", (-1.5, 2, 2.5), (0, 0, -10), cubes, anim=anim)])


item("wyrmspan_wings", "Wyrmspan Wings", "WINGS", "REGULAR", 1100,
     "Broad dragon wings: a crimson membrane with veins and bony fingers, scalloped edges and a hooked claw.",
     dragon_wing("wyrm_r", ("#c2341e", "#5a0e14", "#e8d6b8", "#ff8a5a", "#5a2a20")), view=WING_VIEW)
item("infernal_wings", "Infernal Wings", "WINGS", "REGULAR", 900,
     "Spiked black demon wings whose veins glow like magma in the dark.",
     dragon_wing("infernal_r", ("#2a1418", "#0c0608", "#4a3438", "#ff4a1a", "#1a1012"), glow="#ff5a1a", spikes=True),
     view=dict(WING_VIEW, glow="#ff4a1a", glow_strength=0.22))
item("stormcaller_wings", "Stormcaller Wings", "WINGS", "SPECIAL", None,
     "Storm-cloud wings that crackle with lightning: bolts flash across the membrane while the veins flicker.",
     dragon_wing("storm_r", ("#4a5878", "#141a2a", "#c8d4e8", "#7ab8ff", "#2a3040"), storm=True), view=dict(WING_VIEW, thumb_frame=0),
     animation={"frames": 24, "frametime": 1.5, "interpolate": False, "scale": 8})


# -------- butterfly

def butterfly_lobe(upper):
    def p(face, cv):
        r, t = wing_root(face, cv), cv.yy()
        W = WingDraw(face, cv)
        if upper:
            pts = [(1.0, 0.85), (0.98, 0.1), (0.7, 0.0), (0.3, 0.02), (0.05, 0.1), (0.0, 0.3), (0.12, 0.6), (0.5, 0.9), (0.85, 1.0)]
        else:
            pts = [(1.0, 0.0), (0.4, 0.0), (0.08, 0.2), (0.0, 0.5), (0.15, 0.85), (0.45, 1.0), (0.8, 0.8), (1.0, 0.4)]
        W.poly(pts, (255, 255, 255, 255))
        mask = W.done()[..., 3]
        d = np.sqrt((1 - r) ** 2 + (t - (0.9 if upper else 0.1)) ** 2)
        irid = 0.5 + 0.5 * np.sin(d * 9 + t * 3)
        col = ramp(np.clip(d * 0.9, 0, 1), [(0, "#7ae8ff"), (0.45, "#2a7bff"), (0.8, "#1a2a9a"), (1, "#0a0c2a")])
        col[..., :3] = mix(col[..., :3], hexc("#b8f4ff")[:3], (irid * 0.25)[..., None])
        edge = mask * (1 - smooth(blur(mask[..., None], 1.6)[..., 0] * 1.4 - 0.4))
        border = np.clip(edge * 1.6, 0, 1)
        col[..., :3] = mix(col[..., :3], hexc("#07070e")[:3], border[..., None])
        V = WingDraw(face, cv)
        root = (1.0, 0.9 if upper else 0.1)
        for a in np.linspace(0.15, 0.95, 5):
            ang = a * math.pi * (1 if upper else -1) * 0.7 + (math.pi * 0.95 if upper else math.pi * 0.95)
            V.line([root, (root[0] + math.cos(ang) * 1.2, root[1] - math.sin(ang) * 1.2 * (1 if upper else -1))], (255, 255, 255, 255), 0.35)
        veins = V.done()[..., 3] * mask
        col[..., :3] = mix(col[..., :3], hexc("#0a0a18")[:3], (veins * 0.8)[..., None])
        # white spots along the dark border
        S = WingDraw(face, cv)
        rng = np.random.default_rng(3 if upper else 4)
        for i in range(len(pts) - 1):
            a, b = pts[i], pts[i + 1]
            if a[0] > 0.9 and b[0] > 0.9:
                continue
            for s in (0.3, 0.7):
                cr, ct = a[0] + (b[0] - a[0]) * s, a[1] + (b[1] - a[1]) * s
                cr, ct = cr + (0.5 - cr) * 0.06, ct + (0.5 - ct) * 0.06
                x0, y0 = S.P(cr, ct)
                rad = cv.s * 0.22 * S.k
                S.d.ellipse((x0 - rad, y0 - rad, x0 + rad, y0 + rad), fill=(255, 255, 255, 255))
        spots = S.done()[..., 3] * mask
        col[..., :3] = mix(col[..., :3], np.ones(3, np.float32), spots[..., None])
        col[..., 3] = mask * 0.97
        cv.arr[:] = col
        em_from(cv, mask * irid * (1 - border) * 0.18, "#9ae8ff")
    return p


def butterfly():
    anim = wing_anim(24, 22, 2.2)
    right = [Bone("lobes_r", "body", (-1, 3, 2.5), (0, 0, -6), [
        Cube((-12, -4, 2), (11, 9, 0), butterfly_lobe(True)),
        Cube((-9, 5, 2.05), (8, 7, 0), butterfly_lobe(False))], anim=anim)]
    body = Bone("thorax", "body", (0, 3, 2.5), cubes=[Cube((-0.5, 0, 2), (1, 8, 1), solid_paint(hexc("#14141c")))])
    return wing_pair(right) + [body]


item("azure_butterfly_wings", "Azure Butterfly Wings", "WINGS", "REGULAR", 600,
     "Iridescent blue butterfly wings with black borders and white spots that flutter quickly.",
     butterfly(), view=WING_VIEW)


# -------- mechanical

def steel_plate(c_hi="#d8dde6", c_lo="#6a7280", trim_c="#c99a3a", seam="#5ad8ff"):
    def p(face, cv):
        t, x = cv.yy(), cv.xx()
        col = ramp(t, [(0, c_hi), (0.5, c_lo), (1, "#3a404a")])
        y, xx = cv.grid()
        brushed = hash_noise(1, cv.w, 5 + cv.w)[0][None, :]
        col[..., :3] *= (0.93 + 0.1 * brushed)[..., None]
        col[..., :3] = np.clip(col[..., :3] + (np.exp(-((t - 0.2) / 0.08) ** 2) * 0.25)[..., None], 0, 1)
        cv.arr[:] = col
        if face in ("north", "south"):
            # a feather-shaped plate: brass leading edge, a raised spine, two rivets at the root, a glowing vent
            edge = xx < cv.s * 0.45
            cv.arr[edge, :3] = mix(cv.arr[edge, :3], hexc(trim_c)[:3], 0.85)
            spine = np.abs(xx + 0.5 - cv.w * 0.55) < cv.s * 0.2
            cv.arr[spine, :3] = np.clip(cv.arr[spine, :3] * 1.25 + 0.05, 0, 1)
            for rx in (cv.w * 0.3, cv.w * 0.78):
                m = ((xx + 0.5 - rx) ** 2 + (y + 0.5 - cv.s * 0.7) ** 2) < (cv.s * 0.28) ** 2
                cv.arr[m, :3] = hexc("#f4f0e0")[:3]
            vent = (np.abs(xx + 0.5 - cv.w * 0.55) < cv.s * 0.18) & (y > cv.h - cv.s * 3.2) & (y < cv.h - cv.s * 1.6)
            cv.arr[vent, :3] = hexc(seam)[:3]
            em_from(cv, vent.astype(np.float32), seam)
            tip = (y > cv.h - cv.s * 1.5) & (np.abs(xx + 0.5 - cv.w * 0.6) > (cv.h - y) * 0.9)
            cv.arr[tip, 3] = 0
            cv.em[tip] = 0
        cv.arr[..., 3] = np.where(cv.arr[..., 3] > 0, 1, 0)
        bevel(cv, 0.18, 0.28)
    return p


def gear_paint(face, cv):
    y, x = cv.grid()
    cx, cy = cv.w / 2, cv.h / 2
    dx, dy = x + 0.5 - cx, y + 0.5 - cy
    r = np.sqrt(dx * dx + dy * dy) / (cv.w / 2)
    a = np.arctan2(dy, dx)
    teeth = r < 0.78 + 0.2 * (np.cos(a * 8) > 0.2)
    col = ramp(r, [(0, "#f6d27a"), (0.6, "#c99a3a"), (1, "#6a4a1a")])
    col[..., 3] = teeth
    hole = r < 0.22
    col[hole, :3] = hexc("#2a2018")[:3]
    ring = np.abs(r - 0.5) < 0.06
    col[ring, :3] *= 0.7
    cv.arr[:] = col
    if face not in ("north", "south"):
        cv.arr[:] = hexc("#8a6a2a")
    bevel(cv, 0.15, 0.25)


def clockwork():
    anim = wing_anim(32, 10, 1.3)
    plate = steel_plate()
    cubes = [Cube((-15.5, -1, 1.5), (14, 1, 1), metal("#e8e2d0", "#7a6a4a", 0.3))]
    for i, (x, L) in enumerate([(-5.5, 7), (-8.5, 9), (-11.5, 12), (-14.5, 13), (-17, 11)]):
        cubes.append(Cube((x, -0.5 + i * 0.0, 2 + (i % 2) * 0.5), (3, L, 1), plate))
    cubes.append(Cube((-4.5, -2.5, 0.8), (4, 4, 1), gear_paint))
    return wing_pair([Bone("blades_r", "body", (-1.5, 2, 2.5), (0, 0, 0), cubes, anim=anim)])


item("clockwork_wings", "Clockwork Wings", "WINGS", "REGULAR", 1000,
     "Riveted steel blades with brass trim and glowing blue seams, turning on a brass gear at each shoulder.",
     clockwork(), view=WING_VIEW)


# -------- ice crystal

def ice_shard(face, cv):
    t, x = cv.yy(), cv.xx()
    col = ramp(t, [(0, "#f2fdff"), (0.4, "#9fe4ff"), (1, "#3a8fd6")])
    facet = (x > 0.5)
    col[facet, :3] *= 0.84
    shine = np.exp(-((x - 0.3 + t * 0.1) / 0.08) ** 2)
    col[..., :3] = np.clip(col[..., :3] + shine[..., None] * 0.3, 0, 1)
    col[..., 3] = 0.86
    if face in ("north", "south", "east", "west"):
        y, xx = cv.grid()
        tip = (y > cv.h - cv.w * 1.2) & (np.abs(xx + 0.5 - cv.w / 2) > (cv.h - y) * 0.45)
        col[tip, 3] = 0
    cv.arr[:] = col
    em_from(cv, shine * 0.6 * (col[..., 3] > 0), "#e8fbff")
    em_from(cv, (t < 0.12) * 0.4 * (col[..., 3] > 0), "#bff4ff")
    bevel(cv, 0.2, 0.2)


def glacier():
    anim = wing_anim(34, 8, 1.0)
    cubes = [Cube((-14.5, -1, 1.5), (13, 1, 1), ice_shard)]
    for i, (x, y, L) in enumerate([(-4.5, 0, 7), (-7, -1, 10), (-9.5, -2, 13), (-12, -2, 12), (-14.5, -3, 10), (-16.5, -4, 7)]):
        cubes.append(Cube((x, y, 2 + (i % 2) * 0.5), (2, L, 1), ice_shard))
    return wing_pair([Bone("shards_r", "body", (-1.5, 2, 2.5), (0, 0, 0), cubes, anim=anim, alpha=0.95)])


item("glacier_wings", "Glacier Wings", "WINGS", "REGULAR", 900,
     "A fan of see-through ice crystals with glinting facets and frosted, glowing tips.",
     glacier(), view=dict(WING_VIEW, glow="#9fe4ff", glow_strength=0.22))

# ---------------------------------------------------------------------------------------------- hats
# Head space: the head spans x -4..4, y -8..0 (top at -8), z -4..4 (face at -4).

HEAD_VIEW = {"yaw": 28, "pitch": 18, "focus": "head"}


def knit(face, cv):
    """Knitted rib: columns of V stitches, shaded between ribs."""
    y, x = cv.grid()
    s = max(2, cv.s)
    col_ph = (x % s) / s
    row_ph = (y % s) / s
    v = 1 - np.abs(col_ph - 0.5) * 2                          # rounded rib profile
    vst = np.abs(((row_ph + np.abs(col_ph - 0.5)) % 1) - 0.5)   # V stitches
    g = 0.72 + 0.2 * v + 0.1 * (vst < 0.18)
    cv.arr[..., :3] = (g * 0.97)[..., None]
    cv.arr[..., 3] = 1
    if face not in ("top", "bottom"):
        cv.arr[..., :3] *= (1 - 0.14 * cv.yy())[..., None]
    else:
        cv.arr[..., :3] *= 1.04
    grain(cv, 0.03, seed=5)
    ao_edges(cv, 0.1)


def knit_band(face, cv):
    y, x = cv.grid()
    s = max(2, cv.s)
    rib = 1 - np.abs((x % s) / s - 0.5) * 2
    cv.arr[..., :3] = (0.66 + 0.24 * rib)[..., None]
    cv.arr[..., 3] = 1
    bevel(cv, 0.12, 0.22)


def pompom(face, cv):
    n = pnoise(cv.h, cv.w, max(2.0, cv.w / 2), 55, octaves=2)
    y, x = cv.grid()
    tufts = 0.5 + 0.5 * np.sin(x * 2.1 + y * 1.3) * np.cos(y * 1.7 - x)
    cv.arr[..., :3] = (0.82 + 0.12 * n + 0.08 * tufts)[..., None]
    cv.arr[..., 3] = 1
    ao_edges(cv, 0.15)


item("comet_cap", "Comet Cap", "HAT", "REGULAR", 200,
     "A ribbed knit beanie with a turned-up cuff and a glowing pom-pom, in any colour you like.",
     [Bone("beanie", "head", cubes=[Cube((-4, -9, -4), (8, 3, 8), knit, inflate=0.8),
                                    Cube((-4, -6.5, -4), (8, 2, 8), knit_band, inflate=1.05)], tint=True),
      Bone("pompom", "head", (0, -10, 0), cubes=[Cube((-1.5, -12.6, -1.5), (3, 3, 3), pompom)], emissive=True,
           anim={"type": "bob", "amp": 0.25, "speed": 2.0}, tint=True)],
     colourable=True, default_color="#3D7BFF", view=HEAD_VIEW)


# -------- crowns

def gold(face, cv, c=("#fff0a8", "#e0b040", "#8a5a14")):
    t = cv.yy()
    cv.arr[:] = ramp(t, [(0, c[0]), (0.45, c[1]), (1, c[2])])
    if face == "top":
        cv.arr[:] = hexc(c[0])
    spec = np.exp(-((t - 0.3) / 0.12) ** 2) * 0.35
    cv.arr[..., :3] = np.clip(cv.arr[..., :3] + spec[..., None], 0, 1)
    grain(cv, 0.03, seed=8)
    bevel(cv, 0.25, 0.32)


def platinum(face, cv):
    gold(face, cv, ("#eef2f8", "#a6b0c2", "#525a6c"))      # cool and a little dark, so the sweep of light shows


def engraved_band(base, pattern="circuit", glow="#3ef0ff"):
    def p(face, cv):
        base(face, cv)
        if face in ("top", "bottom"):
            return
        y, x = cv.grid()
        mid = cv.h // 2
        if pattern == "circuit":
            trace = (np.abs(y - mid) < 0.6) & ((x // cv.s) % 4 != 2)
            node = (np.abs(y - mid) < 1.2) & (np.abs((x % (cv.s * 4)) - cv.s * 0.5) < 1.2)
            vert = (np.abs((x % (cv.s * 4)) - cv.s * 2.5) < 0.6) & (y > mid - cv.s * 0.6) & (y < mid)
            m = (trace | vert).astype(np.float32)
            cv.arr[trace | vert, :3] = hexc("#1a4a5a")[:3]
            em_from(cv, m, glow)
            em_from(cv, node.astype(np.float32), "#ffffff")
        else:   # filigree: a running scroll line
            wave = np.abs(y - mid - np.sin(x / cv.s * 1.6) * cv.h * 0.25) < 0.7
            cv.arr[wave, :3] *= 0.6
    return p


def jewel(c_hi, c_mid, c_lo, glint_at=None, n_glint=8):
    """A cut gem: a bright table, darker crown facets, a sparkle; with glint_at, it flares when the animation reaches it."""
    def p(face, cv):
        t, x = cv.yy(), cv.xx()
        d = np.maximum(np.abs(x - 0.5), np.abs(t - 0.5))
        cv.arr[:] = ramp(d * 2, [(0, c_hi), (0.6, c_mid), (1, c_lo)])
        diag = np.abs(np.abs(x - 0.5) - np.abs(t - 0.5)) < 0.06
        cv.arr[diag, :3] = cv.arr[diag, :3] * 0.8
        cv.arr[..., 3] = 1
        y, xx = cv.grid()
        cv.arr[(y < max(1, cv.s // 3)) & (xx < max(1, cv.s // 2)), :3] = 1
        em_from(cv, np.ones_like(t) * 0.45, c_mid)
        if glint_at is not None:
            f = (cv.phase * n_glint - glint_at) % n_glint
            k = max(0.0, 1 - min(f, n_glint - f) / 0.9)
            if k > 0:
                em_from(cv, np.ones_like(t) * k, c_hi)
                glint(cv, cv.w * 0.35, cv.h * 0.35, cv.w * 0.9, "#ffffff", k)
    return p


def crown_bones(metal_paint, band_paint, gems, name="crown", unique=False):
    """A crown: a band around the head, points on every side, and a gem on each face of the band and on top of the
    front points. gems: painters for the front, right, back and left gems, plus the point tips. unique: the painters
    are factories called once per cube, so every cube gets its own texture (animations that depend on where it is)."""
    mp = metal_paint if unique else (lambda: metal_paint)
    bp = band_paint if unique else (lambda: band_paint)
    band = [Cube((-5, -9.5, -5), (10, 2, 1), bp()), Cube((-5, -9.5, 4), (10, 2, 1), bp()),
            Cube((-5, -9.5, -4), (1, 2, 8), bp()), Cube((4, -9.5, -4), (1, 2, 8), bp())]
    points = [Cube((x, -11.5, -5), (1, 2, 1), mp()) for x in (-5, -2, 1, 4)] + \
             [Cube((x, -11.5, 4), (1, 2, 1), mp()) for x in (-5, -2, 1, 4)] + \
             [Cube((-5, -11.5, z), (1, 2, 1), mp()) for z in (-2, 1)] + \
             [Cube((4, -11.5, z), (1, 2, 1), mp()) for z in (-2, 1)]
    g_front, g_right, g_back, g_left, g_tip = gems
    tip = g_tip if unique else (lambda: g_tip)
    jewels = [Cube((-1, -12.5, -5.3), (2, 3, 1), g_front),
              Cube((-5.4, -9.0, -0.5), (1, 1, 1), g_right), Cube((4.4, -9.0, -0.5), (1, 1, 1), g_left),
              Cube((-0.5, -9.0, 4.4), (1, 1, 1), g_back)] + \
             [Cube((x, -12.3, -5.2), (1, 1, 1), tip()) for x in (-5, 4)]
    velvet = Cube((-4, -10.5, -4), (8, 2, 8), velvet_paint)
    return [Bone(name, "head", cubes=band + points + jewels + [velvet])]


def velvet_paint(face, cv):
    t = cv.yy()
    cv.arr[:] = hexc("#8a1030")
    n = pnoise(cv.h, cv.w, 3.0, 66, octaves=2)
    cv.arr[..., :3] *= (0.8 + 0.35 * n)[..., None]
    if face == "top":
        y, x = cv.grid()
        r = np.sqrt((x - cv.w / 2) ** 2 + (y - cv.h / 2) ** 2) / (cv.w / 2)
        cv.arr[..., :3] *= (1.15 - 0.4 * r)[..., None]
        cross = (np.abs(x + 0.5 - cv.w / 2) < 0.8) | (np.abs(y + 0.5 - cv.h / 2) < 0.8)
        cv.arr[cross, :3] = hexc("#e0b040")[:3]
    bevel(cv, 0.1, 0.2)


item("circuit_crown", "Circuit Crown", "HAT", "REGULAR", 400,
     "A gold crown traced with glowing cyan circuitry, set with crystal gems and a tall crystal at the front.",
     crown_bones(gold, engraved_band(gold, "circuit"),
                 [jewel("#e8ffff", "#5ae0ff", "#0a6a9a")] * 4 + [jewel("#e8ffff", "#5ae0ff", "#0a6a9a")]),
     view=HEAD_VIEW)
item("regal_crown", "Regal Crown", "HAT", "REGULAR", 400,
     "A classic gold crown with engraved scrollwork, a red velvet cap and a ruby, sapphire and emerald set in the band.",
     crown_bones(gold, engraved_band(gold, "filigree"),
                 [jewel("#ffd0d8", "#e8203a", "#6a0414"), jewel("#d0e0ff", "#2a5aff", "#0a1a6a"),
                  jewel("#ffd0d8", "#e8203a", "#6a0414"), jewel("#d8ffe0", "#22c46a", "#0a4a24"),
                  jewel("#ffffff", "#e8ecf4", "#9aa4b8")]),
     view=HEAD_VIEW)
# Sovereign Crown (special): a band of light sweeps round the crown once per loop, and each gem flares as it passes

SWEEP_SPAN = 0.7          # the part of the loop the sweep takes (then a rest, so it reads as a glint, not a spin)


def face_world(cv):
    """World (x, z) of each texel column of a side face (box-UV orientation, as ModelPart lays it), and y per row."""
    ox, oy, oz = cv.cube.origin
    w, h, d = cv.cube.size
    c = (np.arange(cv.w, dtype=np.float32) + 0.5) / cv.w
    x, z = {"north": (ox + c * w, oz + c * 0), "south": (ox + w - c * w, oz + d + c * 0),
            "east": (ox + w + c * 0, oz + c * d), "west": (ox + c * 0, oz + d - c * d)}.get(
        cv.face, (ox + w / 2 + c * 0, oz + d / 2 + c * 0))
    y = oy + (np.arange(cv.h, dtype=np.float32) + 0.5) / cv.h * h
    return x[None, :] * np.ones((cv.h, 1)), z[None, :] * np.ones((cv.h, 1)), y[:, None] * np.ones((1, cv.w))


def sweep_at(ang, y, phase):
    """0..1 brightness of the sweep at angle `ang` round the head (radians), tipped a little with height."""
    head = (phase / SWEEP_SPAN) * (math.tau + 1.6) - 0.8              # runs a little past both ends, then rests
    a = (ang + math.pi + (y + 10) * 0.12) % math.tau
    return np.exp(-((a - head) / 0.42) ** 2) if phase < SWEEP_SPAN + 0.1 else np.zeros_like(a)


def swept(base):
    """A metal painter with the sweep of light added (crisp: two bands, a white core and a pale rim)."""
    def p(face, cv):
        base(face, cv)
        x, z, y = face_world(cv)
        k = sweep_at(np.arctan2(z, x), y, cv.phase)
        if face in ("top", "bottom"):
            k = k * 0.6
        lv = levels(np.clip(k, 0, 1), 3, 0.3)
        cv.arr[lv == 1, :3] = np.clip(cv.arr[lv == 1, :3] * 1.18 + 0.08, 0, 1)
        cv.arr[lv == 2, :3] = hexc("#ffffff")[:3]
        em_from(cv, (lv == 2) * 0.8 + (lv == 1) * 0.25, "#f4f8ff")
    return p


def royal_gem(c_hi, c_mid, c_lo):
    """A cut gem that flares (glow and a four-point glint) when the sweep passes its place on the crown."""
    plain = jewel(c_hi, c_mid, c_lo)

    def p(face, cv):
        plain(face, cv)
        ox, oy, oz = cv.cube.origin
        w, h, d = cv.cube.size
        k = float(sweep_at(np.array(math.atan2(oz + d / 2, ox + w / 2)), np.array(oy + h / 2), cv.phase))
        if k > 0.05:
            em_from(cv, np.ones((cv.h, cv.w)) * k * 0.8, c_hi)
            glint(cv, cv.w * 0.4, cv.h * 0.4, cv.w * 0.8, "#ffffff", min(1.0, k * 1.3))
    return p


item("sovereign_crown", "Sovereign Crown", "HAT", "SPECIAL", None,
     "A platinum crown whose ruby, sapphire, emerald and amethyst catch the light one after another, round and round.",
     crown_bones(lambda: swept(platinum), lambda: swept(engraved_band(platinum, "filigree")),
                 [royal_gem("#ffd0d8", "#ff2a4a", "#6a0414"), royal_gem("#d0e0ff", "#3a6aff", "#0a1a6a"),
                  royal_gem("#f0d8ff", "#a04aff", "#3a0a6a"), royal_gem("#d8ffe0", "#22d46a", "#0a4a24"),
                  lambda: royal_gem("#ffffff", "#e8ecf4", "#9aa4b8")], unique=True),
     view=HEAD_VIEW, animation={"frames": 16, "frametime": 2.5, "interpolate": True, "scale": 8})


# -------- witch hat

def felt(c_top, c_bot, seed=0):
    def p(face, cv):
        cv.arr[:] = ramp(cv.yy(), [(0, c_top), (1, c_bot)])
        n = pnoise(cv.h, cv.w, 3.0, 120 + seed, octaves=3)
        cv.arr[..., :3] *= (0.86 + 0.26 * n)[..., None]
        grain(cv, 0.03, seed=seed)
        bevel(cv, 0.1, 0.22)
    return p


def hat_band(color, buckle=None):
    def p(face, cv):
        cv.arr[:] = ramp(cv.yy(), [(0, color), (1, "#000000")]) * 0.6 + hexc(color) * 0.4
        cv.arr[..., 3] = 1
        if buckle and face == "north":
            y, x = cv.grid()
            cx = cv.w / 2
            outer = (np.abs(x + 0.5 - cx) < cv.s * 1.1) & (y >= 0)
            inner = (np.abs(x + 0.5 - cx) < cv.s * 0.6) & (y > cv.s * 0.3) & (y < cv.h - cv.s * 0.3)
            cv.arr[outer & ~inner, :3] = hexc(buckle)[:3]
            cv.arr[outer & ~inner & (y < cv.h / 2), :3] = np.clip(hexc(buckle)[:3] * 1.25, 0, 1)
        bevel(cv, 0.12, 0.25)
    return p


def witch_hat():
    f = felt("#4a2a6a", "#1a0c2a", 1)
    fb = felt("#3a1f54", "#140a22", 2)
    cubes = [
        Cube((-7, -8.5, -7), (14, 1, 4), fb), Cube((-7, -8.5, 3), (14, 1, 4), fb),          # brim (front, back)
        Cube((-7, -8.5, -3), (4, 1, 6), fb), Cube((3, -8.5, -3), (4, 1, 6), fb),            # brim (sides)
        Cube((-4.5, -11, -4.5), (9, 3, 9), f),
        Cube((-3.5, -14, -3), (7, 3, 7), f),
        Cube((-2.5, -17, -1.5), (5, 3, 5), f),
        Cube((-1.5, -19, 0), (3, 2, 3), f),
        Cube((-1, -20, 2), (2, 2, 2), f),
        Cube((-0.5, -20.5, 4), (1, 1, 2), f),
        Cube((-4.5, -10.5, -4.5), (9, 2, 9), hat_band("#c8a040", buckle="#ffe9a6"), inflate=0.15),
    ]
    return [Bone("witch_hat", "head", cubes=cubes)]


item("witch_hat", "Hollow Witch Hat", "HAT", "REGULAR", 300,
     "A tall, crooked violet witch hat with a wide brim and a gold-buckled band.",
     witch_hat(), view=dict(HEAD_VIEW, pitch=14))


# -------- party hat

def party_paint(face, cv):
    y, x = cv.grid()
    t = cv.yy()
    stripes = ((x + y * 0.9) // max(2, cv.s * 1.2)) % 3
    pal = np.array([hexc("#ff4a7a")[:3], hexc("#ffd23a")[:3], hexc("#3ac8ff")[:3]])
    cv.arr[..., :3] = pal[stripes.astype(int)]
    cv.arr[..., 3] = 1
    dots = ((x % (cv.s * 2)) - cv.s) ** 2 + ((y % (cv.s * 2)) - cv.s) ** 2 < (cv.s * 0.28) ** 2
    cv.arr[dots, :3] = 1
    cv.arr[..., :3] *= (1 - 0.15 * t)[..., None]
    bevel(cv, 0.16, 0.26)


def tinsel(face, cv):
    n = hash_noise(cv.h, cv.w, 9)
    cv.arr[..., :3] = mix(hexc("#ffe6f0"), hexc("#ff9ac8"), n[..., None])[..., :3]
    cv.arr[..., 3] = 1
    em_from(cv, (n > 0.8) * 0.6, "#ffffff")


item("party_cone", "Party Cone", "HAT", "REGULAR", 100,
     "A striped, polka-dotted party hat worn at a jaunty angle, with a fluffy pink tuft on top.",
     [Bone("cone", "head", (0, -8, 0), (0, 0, 14), cubes=[
         Cube((-3, -11, -3), (6, 3, 6), party_paint), Cube((-2, -14, -2), (4, 3, 4), party_paint),
         Cube((-1, -16, -1), (2, 2, 2), party_paint), Cube((-1.5, -17.5, -1.5), (3, 2, 3), tinsel)])],
     view=HEAD_VIEW)


# -------- halo

def halo_paint(shimmer=False):
    def p(face, cv):
        t, x = cv.yy(), cv.xx()
        cv.arr[:] = ramp(t, [(0, "#fffbe0"), (0.5, "#ffe27a"), (1, "#e0a83a")])
        cv.arr[..., 3] = 0.95
        if shimmer:
            # special: crisp bands of gold, and a comet of light with a fading tail runs round the ring once per loop
            # (the bars tile one after another round the octagon)
            cv.arr[..., :3] = palette(levels(t, 4, 0.02), ["#ffe596", "#f5bd44", "#de9426", "#b06a18"])[..., :3]
            y, xx = cv.grid()
            idx = getattr(cv.cube, "ring_index", 0)
            pos = (idx + (xx + 0.5) / cv.w) / 8
            d = ((pos - cv.phase) + 0.5) % 1 - 0.5                      # < 0 behind the head of the comet
            k = np.where(d > 0, np.exp(-(d / 0.015) ** 2), np.exp(d / 0.12))
            lv = levels(np.clip(k, 0, 1), 4, 0.02)
            for n, c in ((1, "#fff0b0"), (2, "#fffbe8"), (3, "#ffffff")):
                cv.arr[lv == n, :3] = hexc(c)[:3]
            cv.arr[..., 3] = np.where(lv > 0, 1.0, 0.92)
    return p


def ring_bars(paint, y, R=4.5, L=4):
    """Four bars of length L, R units from the centre; two of these, a quarter turn apart, make an octagon."""
    h = L / 2
    return [Cube((-h, y, -R - 0.5), (L, 1, 1), paint), Cube((-h, y, R - 0.5), (L, 1, 1), paint),
            Cube((-R - 0.5, y, -h), (1, 1, L), paint), Cube((R - 0.5, y, -h), (1, 1, L), paint)]


def halo(shimmer=False):
    anim = {"type": "bob", "amp": 0.5, "speed": 0.8}
    if shimmer:
        paints = [halo_paint(True) for _ in range(8)]       # one painter per bar: each gets its own place in the sweep
        a = [Cube(c.origin, c.size, paints[2 * i]) for i, c in enumerate(ring_bars(paints[0], -12))]
        b = [Cube(c.origin, c.size, paints[2 * i + 1]) for i, c in enumerate(ring_bars(paints[0], -12))]
        for i, c in enumerate(a):
            c.ring_index = (0, 4, 6, 2)[i]
        for i, c in enumerate(b):
            c.ring_index = (1, 5, 7, 3)[i]
        spin = {"type": "spin", "speed": 90}
        return [Bone("halo_a", "head", (0, -12, 0), (0, 0, 0), a, anim=spin, emissive=True),
                Bone("halo_b", "head", (0, -12, 0), (0, 45, 0), b, anim=spin, emissive=True)]
    p = halo_paint()
    return [Bone("halo_a", "head", (0, -12, 0), (0, 0, 0), ring_bars(p, -12), anim=anim, emissive=True),
            Bone("halo_b", "head", (0, -12, 0), (0, 45, 0), ring_bars(p, -12), anim=anim, emissive=True)]


item("angel_halo", "Angel Halo", "HAT", "REGULAR", 300,
     "A softly glowing golden halo that floats above your head and bobs gently.",
     halo(), view=dict(HEAD_VIEW, pitch=24))
item("orbit_halo", "Orbit Halo", "HAT", "SPECIAL", None,
     "A radiant halo that spins above your head while a band of light races round the ring.",
     halo(True), view=dict(HEAD_VIEW, pitch=24, thumb_frame=4),
     animation={"frames": 24, "frametime": 1.5, "interpolate": True, "scale": 8})


# -------- horns

def horn_paint(c_base, c_tip, glow=None):
    def p(face, cv):
        t = cv.yy()
        y0 = getattr(cv.cube, "seg", 0)
        f = (y0 + (1 - t)) / 4
        cv.arr[:] = ramp(f, [(0, c_base), (1, c_tip)])
        y, x = cv.grid()
        ridge = ((y // max(1, cv.s // 2)) % 2 == 0)
        cv.arr[ridge, :3] *= 0.86
        cv.arr[..., 3] = 1
        bevel(cv, 0.2, 0.3)
        if glow and y0 >= 2:
            em_from(cv, np.ones_like(t) * (0.3 + 0.35 * (y0 - 2)), glow)
    return p


def horns():
    """Each horn climbs in four shrinking segments; each segment's painter knows its place so the glow builds upward."""
    segs = [((-5, -9, -1), (2, 2, 2)), ((-6, -10.5, -0.5), (2, 2, 2)), ((-6.5, -12, 0), (1, 2, 1)), ((-6.5, -13.5, 0.5), (1, 2, 1))]
    out = []
    for i, (o, s) in enumerate(segs):
        c = Cube(o, s, (lambda i: (lambda face, cv: (setattr(cv.cube, "seg", i), horn_paint("#2a1a1a", "#ff7a3a", "#ff6a2a")(face, cv))))(i))
        out.append(c)
    right = Bone("horn_r", "head", cubes=out)
    return [right, mirrored_bone(right, "horn_l")]


item("ember_horns", "Ember Horns", "HAT", "REGULAR", 300,
     "A pair of ridged dark horns that curl up from your temples, glowing like coals at the tips.",
     horns(), view=HEAD_VIEW)


# -------- ears

def ear_outer(face, cv):
    n = pnoise(cv.h, cv.w, max(2.0, cv.w / 2), 140, octaves=2)
    cv.arr[..., :3] = (0.82 + 0.14 * n)[..., None]
    cv.arr[..., 3] = 1
    bevel(cv, 0.14, 0.24)


def ear_inner(face, cv):
    cv.arr[:] = ramp(cv.yy(), [(0, "#ffd0dc"), (1, "#f49ab4")])
    n = pnoise(cv.h, cv.w, max(2.0, cv.w / 2), 141, octaves=1)
    cv.arr[..., :3] *= (0.9 + 0.15 * n)[..., None]
    bevel(cv, 0.1, 0.2)


def cat_ears():
    outer = [Cube((-4, -10, -1), (3, 2, 1), ear_outer), Cube((-3.5, -11, -1), (2, 1, 1), ear_outer),
             Cube((-3, -12, -1), (1, 1, 1), ear_outer)]
    inner = [Cube((-3.5, -10, -1.3), (2, 2, 0), ear_inner), Cube((-3, -11, -1.3), (1, 1, 0), ear_inner)]
    r_out = Bone("ear_r", "head", (-2.5, -9, -0.5), (0, 0, -10), outer, tint=True)
    r_in = Bone("ear_r_in", "head", (-2.5, -9, -0.5), (0, 0, -10), inner)
    return [r_out, r_in, mirrored_bone(r_out, "ear_l"), mirrored_bone(r_in, "ear_l_in")]


item("kitty_ears", "Kitty Ears", "HAT", "REGULAR", 100,
     "Soft pointed cat ears with pink insides, in any colour you like.",
     cat_ears(), colourable=True, default_color="#F2A65A", view=dict(HEAD_VIEW, yaw=22, pitch=10))


# -------- toadstool

def cap_paint(face, cv):
    t = cv.yy()
    cv.arr[:] = ramp(t, [(0, "#ff5a4a"), (0.7, "#d81e2a"), (1, "#8a0c16")])
    y, x = cv.grid()
    r = np.random.default_rng(len(face) * 7 + cv.w)
    for _ in range(max(2, cv.w * cv.h // (cv.s * cv.s * 9))):
        cx, cy, R = r.random() * cv.w, r.random() * cv.h, cv.s * (0.5 + r.random() * 0.5)
        m = (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 < R * R
        cv.arr[m, :3] = mix(hexc("#ffffff"), hexc("#e8dcc8"), ((y[m] + 0.5 - cy) / R * 0.5 + 0.5)[..., None])[..., :3]
    grain(cv, 0.03)
    bevel(cv, 0.16, 0.26)


def gills(face, cv):
    y, x = cv.grid()
    cv.arr[:] = hexc("#f0e2c8")
    if face == "bottom":
        r = np.sqrt((x + 0.5 - cv.w / 2) ** 2 + (y + 0.5 - cv.h / 2) ** 2)
        a = np.arctan2(y + 0.5 - cv.h / 2, x + 0.5 - cv.w / 2)
        cv.arr[np.sin(a * 24) > 0.4, :3] *= 0.82
    bevel(cv, 0.1, 0.2)


item("toadstool_cap", "Toadstool Cap", "HAT", "REGULAR", 200,
     "A round red mushroom cap with white spots and a frilled underside.",
     [Bone("cap", "head", cubes=[Cube((-6, -9.5, -6), (12, 2, 12), cap_paint), Cube((-5, -11.5, -5), (10, 2, 10), cap_paint),
                                 Cube((-3, -12.5, -3), (6, 1, 6), cap_paint), Cube((-5.5, -8, -5.5), (11, 1, 11), gills)])],
     view=dict(HEAD_VIEW, pitch=14))


# ---------------------------------------------------------------------------------------------- masks

def visor_shell(face, cv):
    t = cv.yy()
    cv.arr[:] = ramp(t, [(0, "#4a505c"), (0.5, "#262a32"), (1, "#121418")])
    cv.arr[..., :3] = np.clip(cv.arr[..., :3] + (0.22 * np.exp(-((t - 0.18) / 0.12) ** 2))[..., None], 0, 1)
    y, x = cv.grid()
    vent = (face == "north") & (y > cv.h * 0.7) & ((x // max(1, cv.s // 2)) % 2 == 0) & (np.abs(x - cv.w / 2) > cv.w * 0.3)
    cv.arr[vent, :3] *= 0.6
    grain(cv, 0.02)
    bevel(cv, 0.2, 0.3)


def visor_slit(face, cv):
    clear(cv)
    if face != "north":
        return
    x = cv.xx()
    t = cv.yy()
    glow = 0.55 + 0.45 * np.cos((x - 0.5) * math.pi)
    cv.arr[:] = mix(hexc("#ff1a3a"), hexc("#ffd0d8"), (glow ** 4)[..., None])
    cv.arr[..., 3] = 1
    scan = (np.arange(cv.h)[:, None] % 2 == 0) * np.ones((1, cv.w))
    cv.arr[..., :3] *= (1 - 0.15 * scan)[..., None]
    em_rgb(cv, cv.arr, np.ones_like(t))


def strap(face, cv):
    fill(cv, hexc("#22242a"))
    y, x = cv.grid()
    cv.arr[(y + x) % 3 == 0, :3] *= 0.8
    bevel(cv, 0.08, 0.2)


item("dusk_visor", "Dusk Visor", "MASK", "REGULAR", 250,
     "A sleek gunmetal visor across the eyes with a glowing red scanner slit and a woven strap.",
     [Bone("visor", "head", cubes=[Cube((-4.5, -6, -5), (9, 3, 1), visor_shell, inflate=0.1),
                                   Cube((-5, -5.5, -4), (1, 1, 8), strap, inflate=0.05),
                                   Cube((4, -5.5, -4), (1, 1, 8), strap, inflate=0.05),
                                   Cube((-3.5, -4.8, -5.2), (7, 1, 0), visor_slit)])],
     view=dict(HEAD_VIEW, yaw=22, pitch=8))


def bandana_cloth(face, cv):
    t = cv.yy()
    cv.arr[:] = ramp(t, [(0, "#d42a2a"), (1, "#8a1218")])
    y, x = cv.grid()
    s = cv.s * 0.6
    # an original print: little four-petal flowers in offset rows, with dots between them
    cell = s * 3
    row = np.floor(y / cell)
    cx = ((x + (row % 2) * cell / 2) % cell) - cell / 2
    cy = (y % cell) - cell / 2
    r = np.sqrt(cx ** 2 + cy ** 2)
    a = np.arctan2(cy, cx)
    petal = r < s * (0.55 + 0.4 * np.abs(np.cos(a * 2)))
    centre = r < s * 0.28
    dots = (np.abs(np.abs(cx) - cell / 2) < s * 0.25) & (np.abs(np.abs(cy) - cell / 2) < s * 0.25)
    cv.arr[petal, :3] = hexc("#fff2e0")[:3]
    cv.arr[centre, :3] = hexc("#1a1a22")[:3]
    cv.arr[dots, :3] = hexc("#fff2e0")[:3]
    if face == "north":
        border = (x < s * 0.5) | (x >= cv.w - s * 0.5) | (y >= cv.h - s * 0.5)
        cv.arr[border, :3] = hexc("#1a1a22")[:3]
        # the cloth gathers into a point below the chin
        tip = (y > cv.h * 0.55) & (np.abs(x + 0.5 - cv.w / 2) > (cv.h - y) * 1.6)
        cv.arr[tip, 3] = 0
    cloth(cv, 0.08, freq=3, seed=33, shoulder=0)
    bevel(cv, 0.1, 0.18)


item("outlaw_bandana", "Outlaw Bandana", "MASK", "REGULAR", 250,
     "A red printed bandana tied over the nose and mouth, knotted at the back with two loose tails.",
     [Bone("bandana", "head", cubes=[Cube((-4.5, -4, -4.6), (9, 5, 1), bandana_cloth, inflate=0.05),
                                     Cube((-4.6, -4, -4), (1, 3, 8), bandana_cloth, inflate=0.05),
                                     Cube((3.6, -4, -4), (1, 3, 8), bandana_cloth, inflate=0.05),
                                     Cube((-4, -4, 3.6), (8, 3, 1), bandana_cloth, inflate=0.05),
                                     Cube((-1, -4.5, 4.4), (2, 2, 1), bandana_cloth),
                                     Cube((-1.5, -2.5, 4.6), (1, 4, 0), bandana_cloth),
                                     Cube((0.5, -2.5, 4.8), (1, 3, 0), bandana_cloth)])],
     view=dict(HEAD_VIEW, yaw=30, pitch=6))


def fox_face(face, cv):
    t, x = cv.yy(), cv.xx()
    cv.arr[:] = ramp(t, [(0, "#ffffff"), (1, "#e8e2d6")])
    y, xx = cv.grid()
    if face == "north":
        L = layer(cv)
        k = L.k
        W, H = cv.w, cv.h
        red = (214, 36, 46, 255)
        # eye markings sweeping up, brow dots, a nose tip
        for s in (-1, 1):
            cx = W / 2 + s * W * 0.25
            L.draw.polygon([((cx - s * W * 0.12) * k, H * 0.5 * k), ((cx + s * W * 0.2) * k, H * 0.3 * k), ((cx + s * W * 0.18) * k, H * 0.42 * k),
                            ((cx - s * W * 0.06) * k, H * 0.58 * k)], fill=red)
            L.draw.ellipse(((cx - W * 0.03) * k, H * 0.12 * k, (cx + W * 0.05) * k, H * 0.2 * k), fill=red)
        L.draw.line([(W / 2 * k, H * 0.08 * k), (W / 2 * k, H * 0.3 * k)], fill=(214, 36, 46, 255), width=int(cv.s * 0.5 * k))
        L.draw.polygon([((W / 2 - W * 0.08) * k, H * 0.82 * k), ((W / 2 + W * 0.08) * k, H * 0.82 * k), (W / 2 * k, H * 0.95 * k)], fill=(26, 20, 22, 255))
        art = L.done()
        over(cv.arr, art)
        # eye holes: dark slits
        for s in (-1, 1):
            cx = W / 2 + s * W * 0.22
            hole = (np.abs(xx + 0.5 - cx) < W * 0.09) & (np.abs(y + 0.5 - H * 0.47 + s * 0 - (xx + 0.5 - cx) * s * 0.25) < H * 0.05)
            cv.arr[hole, :3] = hexc("#14101a")[:3]
    bevel(cv, 0.14, 0.24)


def fox_snout(face, cv):
    cv.arr[:] = ramp(cv.yy(), [(0, "#ffffff"), (1, "#e2dacb")])
    y, x = cv.grid()
    if face == "north":
        nose = (y < cv.h * 0.32) & (np.abs(x + 0.5 - cv.w / 2) < cv.w * (0.2 - y / cv.h * 0.25))
        cv.arr[nose, :3] = hexc("#1a1416")[:3]
        mouth = (np.abs(y + 0.5 - cv.h * 0.72) < cv.s * 0.12) & (np.abs(x + 0.5 - cv.w / 2) < cv.w * 0.3)
        cv.arr[mouth, :3] = hexc("#d6242e")[:3]
    if face == "top":
        stripe = np.abs(x + 0.5 - cv.w / 2) < cv.s * 0.3
        cv.arr[stripe, :3] = hexc("#d6242e")[:3]
    bevel(cv, 0.14, 0.24)


def fox_ear(face, cv):
    cv.arr[:] = hexc("#ffffff")
    if face == "north":
        y, x = cv.grid()
        inner = (np.abs(x + 0.5 - cv.w / 2) < cv.w * 0.25) & (y > cv.h * 0.3)
        cv.arr[inner, :3] = hexc("#d6242e")[:3]
    cv.arr[0:max(1, cv.s // 2), :, :3] = hexc("#2a1a1a")[:3]
    bevel(cv, 0.14, 0.24)


item("kitsune_mask", "Fox Spirit Mask", "MASK", "REGULAR", 250,
     "A white fox mask with red markings, pointed ears and a short snout, worn over the face.",
     [Bone("mask", "head", cubes=[Cube((-4, -8, -5), (8, 7, 1), fox_face, inflate=0.1),
                                  Cube((-1.5, -4, -7), (3, 3, 2), fox_snout),
                                  Cube((-4, -10, -5), (2, 2, 1), fox_ear), Cube((2, -10, -5), (2, 2, 1), fox_ear),
                                  Cube((-4.5, -5.5, -4), (1, 1, 8), strap), Cube((3.5, -5.5, -4), (1, 1, 8), strap)])],
     view=dict(HEAD_VIEW, yaw=24, pitch=6))


def shades_lens(face, cv):
    t, x = cv.yy(), cv.xx()
    cv.arr[:] = ramp(t, [(0, "#ff3ad2"), (0.5, "#7a3aff"), (1, "#1ad8ff")])
    refl = np.exp(-(((x - t * 0.6) - 0.15) / 0.06) ** 2) + np.exp(-(((x - t * 0.6) - 0.35) / 0.03) ** 2) * 0.7
    cv.arr[..., :3] = np.clip(cv.arr[..., :3] + refl[..., None] * 0.6, 0, 1)
    cv.arr[..., 3] = 1
    em_rgb(cv, cv.arr, np.ones_like(t) * 0.55)
    bevel(cv, 0.15, 0.2)


def shades_frame(face, cv):
    cv.arr[:] = ramp(cv.yy(), [(0, "#3a3a44"), (1, "#0c0c10")])
    bevel(cv, 0.25, 0.3)


item("neon_shades", "Neon Shades", "MASK", "REGULAR", 250,
     "Wide sunglasses with a black frame and mirrored lenses that fade from pink to cyan and glow faintly.",
     [Bone("shades", "head", cubes=[Cube((-4.5, -6, -4.8), (9, 1, 1), shades_frame, inflate=0.05),
                                    Cube((-4, -5, -4.7), (3, 2, 0), shades_lens), Cube((1, -5, -4.7), (3, 2, 0), shades_lens),
                                    Cube((-0.5, -5, -4.8), (1, 1, 1), shades_frame),
                                    Cube((-4.8, -6, -4), (1, 1, 6), shades_frame), Cube((3.8, -6, -4), (1, 1, 6), shades_frame)])],
     view=dict(HEAD_VIEW, yaw=24, pitch=6))

# ---------------------------------------------------------------------------------------------- back items
# Body space: the body spans x -4..4, y 0..12, z -2..2 (back at +2).

BACK_VIEW = {"yaw": 212, "pitch": 12, "frame": [(-14, -19), (14, 10)], "margin": 1.0}


def canvas_bag(c_hi="#6a8a52", c_lo="#3a5230"):
    def p(face, cv):
        cv.arr[:] = ramp(cv.yy(), [(0, c_hi), (1, c_lo)])
        weave(cv, 0.06)
        grain(cv, 0.03, seed=4)
        y, x = cv.grid()
        if face == "south":
            # the front flap, a stitched seam and a pocket
            flap = y < cv.h * 0.4
            cv.arr[flap, :3] *= 1.12
            seam = np.abs(y - cv.h * 0.4) < cv.s * 0.25
            cv.arr[seam, :3] = hexc("#2a3a22")[:3]
            stitch_row(cv, int(cv.h * 0.4) + cv.s // 2 + 1, "#e0d2a8")
            pocket = (y > cv.h * 0.55) & (y < cv.h * 0.9) & (np.abs(x + 0.5 - cv.w / 2) < cv.w * 0.32)
            cv.arr[pocket, :3] *= 0.9
            pe = pocket & ((np.abs(y - cv.h * 0.55) < 0.8) | (np.abs(np.abs(x + 0.5 - cv.w / 2) - cv.w * 0.32) < 0.8))
            cv.arr[pe, :3] = hexc("#2a3a22")[:3]
        ao_edges(cv, 0.16)
        bevel(cv, 0.12, 0.25)
    return p


def leather(face, cv, c="#7a4e2c"):
    cv.arr[:] = ramp(cv.yy(), [(0, c), (1, "#3a2414")]) * 0.4 + hexc(c) * 0.6
    n = pnoise(cv.h, cv.w, 3.0, 150, octaves=3)
    cv.arr[..., :3] *= (0.85 + 0.28 * n)[..., None]
    cv.arr[..., 3] = 1
    stitch_row(cv, max(0, cv.s // 3), "#e8d2a8", alpha=0.6)
    bevel(cv, 0.12, 0.24)


def buckle(face, cv):
    metal("#fff0b0", "#9a6a1a", 0.3, False)(face, cv)
    if face in ("south", "north"):
        y, x = cv.grid()
        inner = (np.abs(x + 0.5 - cv.w / 2) < cv.w * 0.25) & (np.abs(y + 0.5 - cv.h / 2) < cv.h * 0.25)
        cv.arr[inner, :3] = hexc("#5a3a1a")[:3]


def bedroll(face, cv):
    cv.arr[:] = hexc("#b8443a")
    y, x = cv.grid()
    if face in ("east", "west"):
        r = np.sqrt((x + 0.5 - cv.w / 2) ** 2 + (y + 0.5 - cv.h / 2) ** 2) / (cv.w / 2)
        cv.arr[..., :3] *= (0.75 + 0.25 * (np.sin(r * 9) > 0))[..., None]
    else:
        band = (x // max(1, cv.s)) % 4 == 0
        cv.arr[band, :3] = hexc("#e8d6a8")[:3]
        cv.arr[..., :3] *= (1.1 - 0.35 * np.abs(cv.yy() - 0.35) * 2)[..., None]
    weave(cv, 0.05)
    bevel(cv, 0.08, 0.2)


def lantern_frame(face, cv):
    metal("#4a4a52", "#16161a", 0.2, False)(face, cv)
    if face in ("north", "south", "east", "west"):
        inner = (cv.yy() > 0.22) & (cv.yy() < 0.82) & (cv.xx() > 0.22) & (cv.xx() < 0.78)
        cv.arr[inner] = 0


def lantern_core(face, cv):
    t = cv.yy()
    cv.arr[:] = mix(hexc("#fff6c8"), hexc("#ffa23a"), t[..., None])


item("lantern_pack", "Lantern Pack", "BACK", "REGULAR", 400,
     "A sturdy canvas backpack with a front pocket, leather straps, brass buckles, a rolled blanket and a little "
     "lantern that glows at night.",
     [Bone("pack", "body", cubes=[Cube((-3, 1, 2), (6, 8, 3), canvas_bag()),
                                  Cube((-3.5, 0, 2.5), (7, 2, 2), bedroll),
                                  Cube((-2.5, 2, 2), (1, 7, 1), leather, inflate=0.12), Cube((1.5, 2, 2), (1, 7, 1), leather, inflate=0.12),
                                  Cube((-2.5, 5.5, 4.6), (1, 1, 0), buckle), Cube((1.5, 5.5, 4.6), (1, 1, 0), buckle),
                                  Cube((-3.5, 0, -2.3), (1, 7, 0), leather), Cube((2.5, 0, -2.3), (1, 7, 0), leather)]),
      Bone("lantern", "body", (3.5, 7, 4), cubes=[Cube((3, 7, 3.5), (2, 3, 2), lantern_frame, inflate=0.05)],
           anim={"type": "sway", "amp": 6, "speed": 2.2}),
      Bone("lantern_glow", "body", (3.5, 7, 4), cubes=[Cube((3.5, 7.8, 4), (1, 1, 1), lantern_core, inflate=0.25)],
           anim={"type": "sway", "amp": 6, "speed": 2.2}, emissive=True)],
     view=BACK_VIEW)


# -------- sword on the back

def rune_blade(face, cv):
    t, x = cv.yy(), cv.xx()
    cv.arr[:] = ramp(x if face in ("north", "south") else t * 0 + 0.5, [(0, "#eef2f8"), (0.5, "#aab4c4"), (1, "#5a6474")])
    fuller = (np.abs(x - 0.5) < 0.14) & (face in ("north", "south"))
    cv.arr[fuller, :3] *= 0.75
    cv.arr[..., 3] = 1
    if face in ("north", "south"):
        y, xx = cv.grid()
        runes = fuller & (((y // max(1, cv.s // 2)) % 3) != 0) & (((y // max(1, cv.s // 2) * 7 + xx) % 3) != 1)
        em_from(cv, runes.astype(np.float32), "#5ae8ff")
        # the blade comes to a point at the bottom (the sword hangs point-down)
        tip = (y > cv.h - cv.w) & (np.abs(xx + 0.5 - cv.w / 2) > (cv.h - y) * 0.5)
        cv.arr[tip, 3] = 0
        cv.em[tip] = 0
    bevel(cv, 0.25, 0.3)


def grip_wrap(face, cv):
    cv.arr[:] = hexc("#2a1c18")
    y, x = cv.grid()
    wrap = ((y + x) // max(1, cv.s // 2)) % 2 == 0
    cv.arr[wrap, :3] = hexc("#6a3a24")[:3]
    bevel(cv, 0.12, 0.25)


def sword_back():
    gold_p = lambda f, cv: gold(f, cv)
    cubes = [Cube((-1, -5, 2.5), (2, 1, 2), gold_p),                        # pommel
             Cube((-0.5, -4, 3), (1, 4, 1), grip_wrap, inflate=0.1),        # grip
             Cube((-3, 0, 2.5), (6, 1, 2), gold_p),                         # guard
             Cube((-1, 1, 3), (2, 16, 1), rune_blade)]                      # blade
    strap_c = [Cube((-6, 5, 2.05), (12, 1, 0), leather), Cube((-4.5, 5, -2.3), (9, 1, 0), leather)]
    return [Bone("sword", "body", (0, 6, 3), (0, 0, 38), cubes),
            Bone("sword_strap", "body", (0, 6, 0), (0, 0, 38), strap_c)]


item("runeblade_sheath", "Runeblade", "BACK", "REGULAR", 400,
     "A broad steel sword slung across your back, its fuller carved with runes that glow cyan.",
     sword_back(), view=BACK_VIEW)


# -------- jetpack

def tank_paint(face, cv):
    t, x = cv.yy(), cv.xx()
    cyl = 1 - np.abs(x - 0.4) * 1.6 if face in ("north", "south", "east", "west") else 0.6 + 0 * x
    cv.arr[:] = ramp(t, [(0, "#e84a3a"), (0.6, "#b82418"), (1, "#6a0e0a")])
    cv.arr[..., :3] = np.clip(cv.arr[..., :3] * (0.6 + 0.6 * np.clip(cyl, 0, 1))[..., None] + (np.exp(-((x - 0.3) / 0.08) ** 2) * 0.35)[..., None], 0, 1)
    y, xx = cv.grid()
    band = (np.abs(t - 0.2) < 0.05) | (np.abs(t - 0.8) < 0.05)
    cv.arr[band, :3] = hexc("#d8dce4")[:3] * (0.7 + 0.4 * np.clip(cyl, 0, 1))[band][..., None]
    if face == "south":
        warn = (t > 0.38) & (t < 0.6) & (((xx + y) // max(1, cv.s // 2)) % 2 == 0)
        cv.arr[warn, :3] = hexc("#ffd23a")[:3]
    cv.arr[..., 3] = 1
    bevel(cv, 0.2, 0.3)


def nozzle(face, cv):
    metal("#c8ccd4", "#3a3e46", 0.3, False)(face, cv)
    if face == "bottom":
        y, x = cv.grid()
        inner = (np.abs(x + 0.5 - cv.w / 2) < cv.w * 0.3) & (np.abs(y + 0.5 - cv.h / 2) < cv.h * 0.3)
        cv.arr[inner, :3] = hexc("#ffb03a")[:3]
        em_from(cv, inner.astype(np.float32), "#ffd06a")


def thrust(face, cv):
    t = cv.yy()
    cv.arr[:] = ramp(t, [(0, "#ffffff"), (0.25, "#9ae8ff"), (0.6, "#3a8aff"), (1, "#1a2aff")])
    cv.arr[..., 3] = 0.9 * (1 - smooth((t - 0.55) / 0.45))
    x = cv.xx()
    cv.arr[..., 3] *= 1 - smooth((np.abs(x - 0.5) * 2 - 0.4 - (1 - t) * 0.4) / 0.3)


def jetpack():
    housing = metal("#5a606c", "#1a1c22", 0.25)
    cubes = [Cube((-3.5, 1, 2), (3, 8, 3), tank_paint), Cube((0.5, 1, 2), (3, 8, 3), tank_paint),
             Cube((-1, 2, 2.2), (2, 6, 2), housing),
             Cube((-3, 9, 2.5), (2, 1, 2), nozzle), Cube((1, 9, 2.5), (2, 1, 2), nozzle),
             Cube((-3.5, 0.5, 2.5), (3, 1, 2), housing), Cube((0.5, 0.5, 2.5), (3, 1, 2), housing),
             Cube((-3.5, 0, -2.3), (1, 7, 0), strap), Cube((2.5, 0, -2.3), (1, 7, 0), strap)]
    flames = [Cube((-2.5, 10, 3), (1, 4, 1), thrust, inflate=0.2), Cube((1.5, 10, 3), (1, 4, 1), thrust, inflate=0.2)]
    return [Bone("jetpack", "body", cubes=cubes),
            Bone("thrust", "body", (0, 10, 3.5), cubes=flames, emissive=True, anim={"type": "bob", "amp": 0.35, "speed": 6})]


item("rocket_jetpack", "Rocket Jetpack", "BACK", "REGULAR", 400,
     "Twin red fuel tanks with hazard stripes and chrome nozzles that burn with a blue flame.",
     jetpack(), view=BACK_VIEW)


# -------- quiver

def fletching(color):
    def p(face, cv):
        t = cv.yy()
        cv.arr[:] = ramp(t, [(0, color), (1, "#ffffff")])
        y, x = cv.grid()
        cv.arr[(y % max(2, cv.s // 2)) == 0, :3] *= 0.85
        bevel(cv, 0.14, 0.2)
    return p


def shaft(face, cv):
    cv.arr[:] = hexc("#c89a5a")
    grain(cv, 0.05)
    bevel(cv, 0.1, 0.2)


def quiver():
    lp = lambda f, cv: leather(f, cv, "#8a5a30")
    cubes = [Cube((-1.5, 0, 2.2), (3, 10, 3), lp),
             Cube((-2, 0.5, 2), (4, 1, 4), buckle), Cube((-2, 8.5, 2), (4, 1, 4), buckle)]
    arrows = []
    for i, (x, z, c) in enumerate([(-1, 2.6, "#e84a3a"), (0.2, 3.4, "#3a8aff"), (-0.4, 4.1, "#ffd23a"), (0.6, 2.8, "#3ac86a")]):
        arrows += [Cube((x, -3 - (i % 2), z), (1, 3, 0), fletching(c)), Cube((x + 0.25, -3.5 - (i % 2), z - 0.25), (0, 4, 0), shaft)]
    arrows = [a for a in arrows if a.size[0] > 0]
    strap_c = [Cube((-6, 5, 2.05), (12, 1, 0), leather), Cube((-4.5, 5, -2.3), (9, 1, 0), leather)]
    return [Bone("quiver", "body", (0, 5, 3.5), (0, 0, -28), cubes + arrows),
            Bone("quiver_strap", "body", (0, 6, 0), (0, 0, -32), strap_c)]


item("ranger_quiver", "Ranger's Quiver", "BACK", "REGULAR", 400,
     "A tooled leather quiver with brass bands, slung across the back and stuffed with bright-fletched arrows.",
     quiver(), view=BACK_VIEW)


# -------- shield

def shield_face(face, cv):
    y, x = cv.grid()
    cx, cy = cv.w / 2, cv.h / 2
    r = np.sqrt((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2) / (cv.w / 2)
    planks = (x // max(1, cv.s * 2)) % 2
    cv.arr[:] = hexc("#8a5a30")
    cv.arr[planks == 1, :3] *= 0.88
    n = pnoise(cv.h, cv.w, 3.0, 170, octaves=3, aspect=3)
    cv.arr[..., :3] *= (0.85 + 0.3 * n)[..., None]
    if face == "south":
        a = np.arctan2(y + 0.5 - cy, x + 0.5 - cx)
        # a painted sun: quartered blue and gold with rays
        quarter = ((a > 0) ^ (np.abs(a) > math.pi / 2))
        paint_m = r < 0.86
        cv.arr[paint_m & quarter, :3] = hexc("#2a5aa8")[:3]
        cv.arr[paint_m & ~quarter, :3] = hexc("#e8b84a")[:3]
        cv.arr[..., :3] *= (0.9 + 0.12 * n)[..., None]
        rim = r > 0.86
        cv.arr[rim, :3] = mix(hexc("#d8dce4"), hexc("#6a7080"), cv.yy()[rim][..., None])[..., :3]
        rivets = (np.abs(r - 0.93) < 0.04) & (np.cos(a * 12) > 0.9)
        cv.arr[rivets, :3] = 1
        boss = r < 0.25
        cv.arr[boss, :3] = mix(hexc("#ffffff"), hexc("#7a808a"), (r[boss] / 0.25)[..., None])[..., :3]
        emboss(cv, (r < 0.25) * (1 - r * 3) + (r > 0.86) * 0.6, 2.0)
    # round the corners off
    cv.arr[r > 1.0, 3] = 0 if face in ("south", "north") else 1
    bevel(cv, 0.1, 0.22)


item("bastion_shield", "Bastion Shield", "BACK", "REGULAR", 400,
     "A round wooden shield on your back, painted in blue and gold quarters with a steel rim and boss.",
     [Bone("shield", "body", (0, 6, 3), (0, 0, 8), [Cube((-5, 1, 2.5), (10, 10, 1), shield_face),
                                                     Cube((-1.5, 4.5, 3.5), (3, 3, 1), metal("#ffffff", "#6a7080", 0.4, False))])],
     view=BACK_VIEW)


# ---------------------------------------------------------------------------------------------- auras
# A sigil turning flat on the ground, plus a few motes when Particle Amount allows. The sigil is four quarter-plates,
# each a quarter turn round from the last, sharing one texture: twice the detail of one big plate in the same space.

def sigil_bones(fn, R=11, spin=24, y=23.6, name="sigil"):
    """fn(cv, X, Z) paints a quarter: X, Z in -1..1 ground coordinates (only the quarter x >= 0, z <= 0 is asked for)."""
    def p(face, cv):
        clear(cv)
        if face not in ("north", "south"):
            return
        u, v = cv.xx(), cv.yy()
        if face == "south":
            X, Z = 1 - u, -(1 - v)
        else:
            X, Z = u, -(1 - v)
        fn(cv, X, Z)
    bones = []
    for k in range(4):
        bones.append(Bone(f"{name}_{k}", "root", (0, y, 0), (90, k * 90, 0), [Cube((0, y - R, 0), (R, R, 0), p)],
                          anim={"type": "spin", "speed": spin}, emissive=True))
    return bones


# Evade's own rune glyphs (5 x 7 bitmaps, original shapes), set round the sigil rings
RUNES = [
    ["1...1", "11.11", "1.1.1", "1...1", "1...1", "1...1", "1...1"],
    ["..1..", ".1.1.", "1...1", "..1..", "..1..", "..1..", "..1.."],
    ["11111", "1....", "1....", "11111", "....1", "....1", "11111"],
    ["1...1", ".1.1.", "..1..", "..1..", "..1..", ".1.1.", "1...1"],
    ["..1..", "..1..", "11111", "..1..", ".1.1.", "1...1", "1...1"],
    ["1.1.1", "1.1.1", ".111.", "..1..", ".111.", "1.1.1", "1.1.1"],
]


def rune_field(X, Z, r, ang, n, r_mid, height, offset=0.0):
    """n rune glyphs evenly round a ring of radius r_mid (each `height` tall, radially), crisp: 1 inside a glyph's
    strokes, 0 elsewhere; plus the glyph index (for colouring)."""
    step = math.tau / n
    k = np.floor((ang - offset) / step + 0.5)
    da = (ang - offset) - k * step                       # angle from the nearest glyph's centre
    gw = height * 5 / 7                                   # a glyph's width for square cells
    col = np.floor((da * r_mid) / gw * 5 + 2.5).astype(int)
    row = np.floor((r_mid + height / 2 - r) / height * 7).astype(int)
    inside = (col >= 0) & (col < 5) & (row >= 0) & (row < 7)
    out = np.zeros(r.shape, np.float32)
    idx = (k.astype(int) % n) % len(RUNES)
    for g, glyph in enumerate(RUNES):
        bm = np.array([[c == "1" for c in line] for line in glyph])
        m = inside & (idx == g)
        out[m] = bm[np.clip(row[m], 0, 6), np.clip(col[m], 0, 4)]
    return out


def ring_sigil(c_in, c_out, glyphs=12, alpha=0.8, extra=None, hue=None, star=None):
    """A bold rune circle: a thick outer ring with crisp edges, a thinner inner ring, evenly spaced rune glyphs between
    them with diamond studs between the glyphs, tick marks round the rim, a soft glow filling the middle and an
    optional inner star. Crisp pixel shapes (no thin hairlines), so it reads from a distance and at a low angle."""
    def fn(cv, X, Z):
        r = np.sqrt(X * X + Z * Z)
        ang = np.arctan2(Z, X)
        px_ = 1.0 / cv.h                                  # one texel, in radius units (the quarter is R x R)
        band = lambda a, b: ((r >= a) & (r < b)).astype(np.float32)
        outer = band(0.86, 0.97)
        outer_edge = band(0.86, 0.86 + 2.5 * px_) + band(0.97 - 2.5 * px_, 0.97)
        inner = band(0.55, 0.6)
        ticks = band(0.975, 1.0) * (np.abs(((ang / math.tau * glyphs * 4) % 1) - 0.5) < 0.18)
        runes = rune_field(X, Z, r, ang, glyphs, 0.73, 0.2)
        step = math.tau / glyphs
        da = ((ang + step / 2) % step) - step / 2
        studs = (np.abs(np.abs(da) * 0.73 - step / 2 * 0.73) + np.abs(r - 0.73) < 0.045).astype(np.float32)
        studs *= (np.abs(np.abs(da) - step / 2) < step * 0.2)
        glow = np.clip(1 - r / 0.55, 0, 1) ** 0.6 * 0.22 * (r < 0.55)
        A = np.clip(outer * 0.85 + outer_edge + inner + runes + studs + ticks * 0.9, 0, 1)
        if star:
            k = star
            a2 = (ang % (math.tau / k)) - math.pi / k
            st = (np.abs(r * np.cos(a2) - 0.36) < 1.8 * px_ * 1.5) & (r < 0.5)
            A = np.maximum(A, st * 0.9)
        A = np.maximum(A, glow)
        if extra:
            A = np.clip(A + extra(X, Z, r, ang), 0, 1)
        col = ramp(smooth((r - 0.3) / 0.65), [(0, c_in), (1, c_out)])
        bright = (outer_edge + runes + studs) > 0
        if hue is not None:
            # special: the hue sweeps round the circle and rolls with the loop, in 24 crisp steps
            h = (ang * 4 / math.tau + cv.phase) % 1
            col[..., :3] = hsv_rgb(np.floor(h * 24) / 24, 0.75, 1.0)
            col[bright, :3] = hsv_rgb(np.floor(((h[bright] + 0.04) % 1) * 24) / 24, 0.25, 1.0)
            A = levels(A, 5, 0.2) / 4.0
        else:
            col[bright, :3] = mix(col[bright, :3], np.ones(3), 0.45)
        A *= (r < 1.0)
        cv.arr[..., :3] = col[..., :3]
        cv.arr[..., 3] = A * alpha
        em_rgb(cv, col, A * alpha)
    return fn


def hsv_rgb(h, s, v):
    h = np.asarray(h, np.float32)
    i = np.floor(h * 6) % 6
    f = h * 6 - np.floor(h * 6)
    p, q, t = v * (1 - s), v * (1 - f * s), v * (1 - (1 - f) * s)
    v = np.full_like(h, v)
    p, q, t = np.broadcast_to(p, h.shape), q, t
    r = np.select([i == 0, i == 1, i == 2, i == 3, i == 4, i == 5], [v, q, p, p, t, v])
    g = np.select([i == 0, i == 1, i == 2, i == 3, i == 4, i == 5], [t, v, v, q, p, p])
    b = np.select([i == 0, i == 1, i == 2, i == 3, i == 4, i == 5], [p, p, t, v, v, q])
    return np.stack([r, g, b], -1).astype(np.float32)


def ember_mote(face, cv):
    cv.arr[:] = mix(hexc("#fff0a8"), hexc("#ff5a1f"), cv.yy()[..., None])


AURA_VIEW = {"yaw": 30, "pitch": 40, "frame": [(-15.5, -30.5), (15.5, 12.5)], "margin": 1.0}

item("ember_orbit", "Ember Orbit", "AURA", "REGULAR", 400,
     "A ring of fire runes turns at your feet while three embers circle your waist.",
     sigil_bones(ring_sigil("#ffe08a", "#ff3a0a", 12, 0.78, star=6)) +
     [Bone("embers", "root", (0, 14, 0), cubes=[Cube((8.5, 13, -0.5), (1, 1, 1), ember_mote),
                                               Cube((-5.25, 15, 7.1), (1, 1, 1), ember_mote),
                                               Cube((-5.25, 11, -8.1), (1, 1, 1), ember_mote)],
           anim={"type": "spin", "speed": -70}, emissive=True)],
     fx={"type": "aura", "particle": "soft", "colors": ["#FFE08A", "#FF7A2A", "#8A1A0A"], "rate": 4, "size": 0.6,
         "life": 1.4, "sizeEnd": 0.3, "gravity": -0.35},
     view=AURA_VIEW)


def frost_fn(cv, X, Z):
    r = np.sqrt(X * X + Z * Z)
    ang = np.arctan2(Z, X)
    # an eight-armed frost star with branches, inside a double ring (eight arms: the four quarter-plates repeat it)
    A = np.zeros_like(r)
    for k in range(8):
        a0 = k * math.pi / 4
        ux, uz = math.cos(a0), math.sin(a0)
        along = X * ux + Z * uz
        perp = np.abs(-X * uz + Z * ux)
        arm = smooth(1 - perp / 0.042) * (along > 0) * (along < 0.86)
        A = np.maximum(A, arm)
        for f, L in ((0.4, 0.13), (0.64, 0.1)):
            for s in (-1, 1):
                b = a0 + s * 0.85
                bx, bz = math.cos(b), math.sin(b)
                rx, rz = X - ux * f, Z - uz * f
                al = rx * bx + rz * bz
                pp = np.abs(-rx * bz + rz * bx)
                A = np.maximum(A, smooth(1 - pp / 0.034) * (al > 0) * (al < L))
        tip = np.sqrt((X - ux * 0.86) ** 2 + (Z - uz * 0.86) ** 2)
        A = np.maximum(A, smooth(1 - tip / 0.04))
    A = np.maximum(A, smooth(1 - np.abs(r - 0.94) / 0.04) + 0.8 * smooth(1 - np.abs(r - 0.84) / 0.022))
    A = np.maximum(A, smooth(1 - r / 0.08))
    A *= r < 1
    col = ramp(r, [(0, "#ffffff"), (0.6, "#bfeaff"), (1, "#5ab8ff")])
    cv.arr[..., :3] = col[..., :3]
    A = np.clip(A * 1.25, 0, 1)
    cv.arr[..., 3] = A * 0.72
    em_rgb(cv, col, A * 0.72)


item("frost_sigil", "Frost Sigil", "AURA", "REGULAR", 400,
     "A pale, branching frost star slowly turns beneath you, with a few drifting snowflakes.",
     sigil_bones(frost_fn, spin=-12),
     fx={"type": "aura", "particle": "snow", "colors": ["#E8FBFF"], "rate": 2, "size": 1.0},
     view=AURA_VIEW)


def bloom_extra(X, Z, r, ang):
    petals = smooth(1 - np.abs(r - (0.5 + 0.1 * np.cos(ang * 10))) / 0.03) * (r > 0.35) * (r < 0.66)
    return petals * 0.8


def verdant_fn(cv, X, Z):
    r = np.sqrt(X * X + Z * Z)
    ang = np.arctan2(Z, X)
    # a wreath of leaves: pointed ovals round the ring, with flowers between
    A = np.zeros_like(r)
    col = np.zeros(r.shape + (4,), np.float32)
    for k in range(16):
        a0 = k * math.tau / 16
        cx, cz = math.cos(a0) * 0.78, math.sin(a0) * 0.78
        t = a0 + math.pi / 2 + 0.5
        ux, uz = math.cos(t), math.sin(t)
        lx, lz = X - cx, Z - cz
        al, pp = lx * ux + lz * uz, -lx * uz + lz * ux
        leaf = (al / 0.13) ** 2 + (pp / 0.05) ** 2 < 1
        vein = leaf & (np.abs(pp) < 0.008)
        A = np.maximum(A, leaf * 0.85)
        col[leaf] = mix(hexc("#a8ff7a"), hexc("#2aa84a"), np.clip((al[leaf] + 0.13) / 0.26, 0, 1)[..., None])
        col[vein] = hexc("#e8ffd0")
    for k in range(8):
        a0 = (k + 0.5) * math.tau / 8
        cx, cz = math.cos(a0) * 0.78, math.sin(a0) * 0.78
        d = np.sqrt((X - cx) ** 2 + (Z - cz) ** 2)
        fa = np.arctan2(Z - cz, X - cx)
        petal = d < 0.06 + 0.025 * np.cos(fa * 5)
        A = np.maximum(A, petal * 1.0)
        col[petal] = hexc("#ffb0d8")
        col[d < 0.025] = hexc("#ffe66a")
    ring = smooth(1 - np.abs(r - 0.94) / 0.038) + smooth(1 - np.abs(r - 0.6) / 0.026) * 0.85
    col[(ring > 0.3) & (A < 0.3)] = hexc("#c8ff9a")
    A = np.maximum(A, ring)
    A *= r < 1
    cv.arr[..., :3] = col[..., :3]
    cv.arr[..., 3] = A * 0.75
    em_rgb(cv, col, A * 0.55)


item("verdant_bloom", "Verdant Bloom", "AURA", "REGULAR", 400,
     "A wreath of glowing leaves and pink flowers turns slowly round your feet, with green motes in the air.",
     sigil_bones(verdant_fn, spin=10),
     fx={"type": "aura", "particle": "soft", "colors": ["#E8FFC8", "#8CFF6A", "#2A8A3A"], "rate": 3, "size": 0.6,
         "life": 1.8, "sizeEnd": 0.4, "gravity": -0.1},
     view=AURA_VIEW)

item("void_rift", "Void Rift", "AURA", "REGULAR", 400,
     "A violet rune circle with a dark, starry core opens under you, shedding motes of purple light.",
     sigil_bones(ring_sigil("#ffd0ff", "#8a2aff", 12, 0.8, extra=lambda X, Z, r, a: smooth(1 - r / 0.32) * 0.45, star=8), spin=-30),
     fx={"type": "aura", "particle": "soft", "colors": ["#FF9AF0", "#B04FFF", "#3A0A6A"], "rate": 4, "size": 0.7,
         "life": 1.5, "sizeEnd": 0.3, "gravity": -0.25},
     view=AURA_VIEW)

item("spectrum_aura", "Spectrum Aura", "AURA", "SPECIAL", None,
     "A rune circle whose colours sweep through the whole rainbow as it turns, with rainbow motes rising around you.",
     sigil_bones(ring_sigil("#ffffff", "#ffffff", 12, 0.9, hue=True, star=6), spin=20),
     fx={"type": "aura", "particle": "soft", "colors": ["#FF4A4A", "#FFD23A", "#3AFF7A", "#3AC8FF", "#B04FFF"], "rate": 5,
         "size": 0.6, "life": 1.6, "sizeEnd": 0.35, "gravity": -0.3},
     view=AURA_VIEW, animation={"frames": 20, "frametime": 3, "interpolate": True, "scale": 16})

# ---------------------------------------------------------------------------------------------- pets
# Little creatures that ride beside your head: the perching ones sit on your left shoulder (like a shoulder parrot),
# the flying ones hover just outside it. The pet's body bone is attached to the model root with the "pet" animation;
# the engine turns it about your vertical axis together with your head (so it always stays beside the head, never in
# it), trails your turns by a few degrees and lowers it with your shoulders when you sneak. Head, eyelids, tail and wings
# are child bones (format v2 "parent") with their own small animations: the head glances about ("pet_head"), the lids
# blink ("blink"), tails sway or wag and wings beat ("sway" / "wing"). check_pet_clearance() proves at build time that
# no pose of the pet (every animation at its extremes) reaches into your head, body or arms, whatever you do.
#
# Art: flat-shaded pixel art on a 4-per-unit grid (each art pixel is a crisp block of texels at the regular items'
# 8 texels per unit), a darker rim round every face as an outline, a light top row and a shaded bottom row, and big
# shiny eyes.

PET_VIEW = {"yaw": 325, "pitch": 12, "focus": "pet", "margin": 1.12}
PERCH = (8.25, -1.75, 0.0)   # perching pets: the middle of the seat over your left shoulder (a little above it, clear
                             # of the arm's top edge as it swings)
HOVER = (10.75, -2.5, 0.25)  # flying pets: under their body, just outside your left shoulder
PET_ANIM = {"type": "pet", "amp": 0.6, "speed": 1.6}
ART = 4                      # art pixels per model unit
PET_LAG = 6                  # the most the engine lets a pet trail your turn (CosmeticMotion.PET_LAG), degrees
PET_CLEARANCE = 0.1          # the closest a pet may come to your head, body or arms in any pose (px)


def P(anchor, x, y, z):
    return (anchor[0] + x, anchor[1] + y, anchor[2] + z)


def PC(anchor, origin, size, paint, **kw):
    """A cube placed relative to the pet's anchor."""
    return Cube(P(anchor, *origin), size, paint, **kw)


def rgbf(c):
    return hexc(c)[:3] if isinstance(c, str) else np.asarray(c, np.float32)[..., :3]


def shade(c, k):
    return np.clip(rgbf(c) * k, 0, 1)


class Art:
    """One face drawn as pixel art on a coarse grid (ART pixels per unit), blown up to the face's texels."""

    def __init__(self, face, cv, per_unit=ART):
        self.face, self.cv = face, cv
        self.k = max(1, cv.s // per_unit)
        self.h, self.w = max(1, cv.h // self.k), max(1, cv.w // self.k)
        self.a = np.zeros((self.h, self.w, 4), np.float32)
        self.e = np.zeros_like(self.a)
        self.norim = np.zeros((self.h, self.w), bool)
        self.y, self.x = np.mgrid[0:self.h, 0:self.w]
        self.per = per_unit

    def u(self, units):
        return int(round(units * self.per))

    def fill(self, color, alpha=1.0):
        self.a[..., :3] = rgbf(color)
        self.a[..., 3] = alpha

    def put(self, mask, color, glow=0.0, alpha=1.0):
        self.a[mask, :3] = rgbf(color)
        self.a[mask, 3] = alpha
        if glow:
            self.e[mask, :3] = rgbf(color)
            self.e[mask, 3] = glow
        return mask

    def rect(self, x0, y0, x1, y1, color, **kw):
        """Art pixels [x0, x1) x [y0, y1)."""
        return self.put((self.x >= x0) & (self.x < x1) & (self.y >= y0) & (self.y < y1), color, **kw)

    def pix(self, pts, color, **kw):
        m = np.zeros((self.h, self.w), bool)
        for x, y in pts:
            if 0 <= x < self.w and 0 <= y < self.h:
                m[y, x] = True
        return self.put(m, color, **kw)

    def ellipse(self, cx, cy, rx, ry, color, **kw):
        """An ellipse in art pixels (centre may be fractional; pixel centres are at +0.5)."""
        m = ((self.x + 0.5 - cx) / rx) ** 2 + ((self.y + 0.5 - cy) / ry) ** 2 <= 1.0
        return self.put(m, color, **kw)

    def poly(self, pts):
        """A crisp polygon mask; pts in face units (0..1 across, 0..1 down)."""
        X, Y = (self.x + 0.5) / self.w, (self.y + 0.5) / self.h
        inside = np.zeros((self.h, self.w), bool)
        n = len(pts)
        for i in range(n):
            (x1, y1), (x2, y2) = pts[i], pts[(i + 1) % n]
            cross = ((y1 > Y) != (y2 > Y)) & (X < (x2 - x1) * (Y - y1) / ((y2 - y1) or 1e-9) + x1)
            inside ^= cross
        return inside

    def clip(self, mask):
        """Makes everything outside `mask` see-through (cut-out shapes: ears, wings, flames)."""
        self.a[~mask, 3] = 0
        self.e[~mask, 3] = 0

    def rim(self, k=0.66, top_light=1.14, bottom_dark=0.86, outline=None):
        """A darker rim round the face's opaque shape (the outline), a lit top row and a shaded bottom row."""
        op = self.a[..., 3] > 0
        pad = np.pad(op, 1)
        inner = pad[:-2, 1:-1] & pad[2:, 1:-1] & pad[1:-1, :-2] & pad[1:-1, 2:]
        edge = op & ~inner & ~self.norim
        if self.face not in ("top", "bottom"):
            up = op & ~edge & np.pad(edge, ((1, 0), (0, 0)))[:-1] & ~self.norim
            self.a[up, :3] = np.clip(self.a[up, :3] * top_light, 0, 1)
            down = op & ~edge & np.pad(edge, ((0, 1), (0, 0)))[1:] & ~self.norim
            self.a[down, :3] *= bottom_dark
        if outline is not None:
            self.a[edge, :3] = mix(self.a[edge, :3], rgbf(outline), 0.75)
        else:
            self.a[edge, :3] *= k
        return edge

    def over(self):
        """Draws only this layer's opaque pixels over what the canvas already has (no glow under them)."""
        k = self.k
        big = np.kron(self.a, np.ones((k, k, 1), np.float32))[:self.cv.h, :self.cv.w]
        m = big[..., 3] > 0
        self.cv.arr[:big.shape[0], :big.shape[1]][m] = big[m]
        self.cv.em[:big.shape[0], :big.shape[1]][m] = 0

    def done(self):
        k = self.k
        big = np.kron(self.a, np.ones((k, k, 1), np.float32))
        bige = np.kron(self.e, np.ones((k, k, 1), np.float32))
        h, w = self.cv.h, self.cv.w
        self.cv.arr[:] = 0
        self.cv.arr[:big.shape[0], :big.shape[1]] = big[:h, :w]
        bige = bige[:h, :w]
        bige[..., :3] *= bige[..., 3:4]            # glow colours are stored pre-multiplied (as em_rgb does)
        self.cv.em[:big.shape[0], :big.shape[1]] = bige


FACE_LIGHT = {"top": 1.08, "north": 1.0, "east": 0.95, "west": 0.95, "south": 0.92, "bottom": 0.8}
RIM = 0.74


def px(base, feat=None, rim=RIM, outline=None, glow=0.0, light=True, alpha=1.0):
    """A pixel-art painter: the base colour (a colour or fn(face) -> colour), lit per face, then feat(face, art) on
    top, then the rim. Returns paint(face, cv)."""
    def p(face, cv):
        A = Art(face, cv)
        c = base(face) if callable(base) else base
        A.fill(shade(c, FACE_LIGHT[face] if light else 1.0), alpha)
        if glow:
            A.e[..., :3] = A.a[..., :3]
            A.e[..., 3] = glow
        if feat:
            feat(face, A)
        if rim:
            A.rim(rim, outline=outline)
        A.done()
    return p


def eye(A, x, y, w=4, h=4, iris="#3a8a3a", dark=None, lid=None):
    """A big glossy cartoon eye in art pixels, top-left at (x, y): a dark eye tinted with its iris colour, the iris
    glowing in its lower half, a white glint top-left and a small light one lower right; rounded when 4 x 4 or more."""
    D = rgbf(dark) if dark is not None else mix(rgbf(iris), rgbf("#08060c"), 0.84)
    I = rgbf(iris)
    around = A.a[y, max(0, x - 1)].copy() if lid is None else np.append(rgbf(lid), 1.0)
    A.rect(x, y, x + w, y + h, D)
    lo = y + h // 2
    A.rect(x + (1 if w >= 4 else 0), lo, x + w - (1 if w >= 4 else 0), y + h - (1 if h >= 4 else 0), mix(I, D, 0.25))
    if h >= 5:
        A.rect(x + 1, y + h - 2, x + w - 1, y + h - 1, mix(I, D, 0.05))
    gx, gy = (x + 1, y + 1) if (w >= 4 and h >= 4) else (x, y)
    A.pix([(gx, gy)], "#ffffff")
    if w >= 5:
        A.pix([(gx + 1, gy), (gx, gy + 1)], "#ffffff")
    if w >= 3 and h >= 4:
        A.pix([(x + w - 2, y + h - 2)], mix(I, np.ones(3), 0.55))
    m = (A.x >= x) & (A.x < x + w) & (A.y >= y) & (A.y < y + h)
    A.norim |= m
    if w >= 4 and h >= 4:
        for cx, cy in ((x, y), (x + w - 1, y), (x, y + h - 1), (x + w - 1, y + h - 1)):
            A.a[cy, cx] = around
            A.norim[cy, cx] = False


def stripes(A, color, rows):
    """Tabby stripes: art-pixel rows across the face."""
    for yy in rows:
        A.rect(0, yy, A.w, yy + 1, color)


def ear_plane(fill, inner=None, tip=None, apex=0.5, base=(0.0, 1.0)):
    """A flat triangular ear (a 0-deep plane, both sides drawn): fill, an inner colour and a dark tip."""
    def feat(face, A):
        m = A.poly([(apex, 0.0), (base[1], 1.0), (base[0], 1.0)])
        if face == "south":
            m = m[:, ::-1]
        if tip:
            A.put(A.y < max(1, A.h // 3), tip)
        if inner and face == "north":
            ax = apex * A.w
            A.put(m & (A.y >= A.h // 3) & (np.abs(A.x + 0.5 - ax) <= (A.y - A.h // 3 + 0.5) * 0.42), inner)
        A.clip(m)
        A.rim(RIM, top_light=1.0, bottom_dark=1.0)
        A.norim[:] = True
    return px(fill, feat)


def lid_paint(fur, eyes_at, line="#1a1222"):
    """Eyelids for a face: fur colour, with a happy closed-eye curve where each eye is (art px relative to the lid)."""
    def feat(face, A):
        if face != "north":
            A.clip(np.zeros((A.h, A.w), bool))
            return
        keep = np.zeros((A.h, A.w), bool)
        for (x, w) in eyes_at:
            keep |= (A.x >= x) & (A.x < x + w)
            A.pix([(x, A.h - 2), (x + w - 1, A.h - 2)] + [(i, A.h - 1) for i in range(x + 1, x + w - 1)], line)
        A.clip(keep)
    return px(fur, feat, rim=0)


def blink_bone(name, parent, anchor, origin, size, paint, phase=0.0):
    """Eyelid plates just in front of the eyes, hinged at their top edge."""
    o = P(anchor, *origin)
    return Bone(name, "root", (o[0] + size[0] / 2, o[1], o[2]), cubes=[Cube(o, size, paint)],
                anim={"type": "blink", "speed": 0.27, "phase": phase}, parent=parent)


def pet_body(name, anchor, cubes, **kw):
    return Bone(name, "root", (anchor[0], anchor[1] - 2, anchor[2] + 0.5), cubes=cubes, anim=dict(PET_ANIM), **kw)


def pet_pair(right, anchor):
    """The right-hand bone (toward your head) and its mirror image about the pet's own middle (not about you)."""
    cx = anchor[0]
    cubes = [Cube((2 * cx - (c.origin[0] + c.size[0]), c.origin[1], c.origin[2]), c.size, c.paint, c.inflate, not c.mirror)
             for c in right.cubes]
    for src, dst in zip(right.cubes, cubes):
        dst.share = src
    anim = dict(right.anim, side=-right.anim.get("side", 1)) if right.anim else None
    left = Bone(right.name.replace("_r", "_l"), right.attach, (2 * cx - right.pivot[0], right.pivot[1], right.pivot[2]),
                (right.rotation[0], -right.rotation[1], -right.rotation[2]), cubes, anim, right.emissive, right.tint,
                right.alpha, right.parent)
    return [right, left]


def tail_tip(fill, tip, length, tip_len=1, feat=None):
    """A tail box along +z (`length` long) whose last `tip_len` units are `tip` coloured."""
    def f(face, A):
        if feat:
            feat(face, A)
        n = A.u(tip_len)
        if face == "south":
            A.fill(tip)
        elif face == "east":
            A.put(A.x >= A.w - n, tip)
        elif face == "west":
            A.put(A.x < n, tip)
        elif face == "top":
            A.put(A.y < n, tip)
        elif face == "bottom":
            A.put(A.y >= A.h - n, tip)
    return px(fill, f)


def wag(amp, speed, base=0.0, phase=0.0):
    """A tail wag: the wing animation's sideways sweep, without the wing's fold."""
    return {"type": "wing", "side": 1, "base": base, "amp": amp, "speed": speed, "phase": phase, "fold": 0}


# -------- Mochi the cat: a round ginger tabby sitting up, white paws and muzzle, green eyes, a curling tail

CAT_FUR, CAT_DARK, CAT_CREAM, CAT_PINK = "#f7a54c", "#c8632a", "#fff1dc", "#ff9ab0"
CAT = PERCH


def cat_head(face, A):
    if face == "north":                       # 16 x 12 art px
        A.pix([(7, 1), (8, 1), (7, 2), (8, 2), (5, 1), (10, 1), (6, 0), (9, 0)], CAT_DARK)      # forehead "M"
        eye(A, 2, 4, 4, 4, "#62d84a")
        eye(A, 10, 4, 4, 4, "#62d84a")
        A.rect(1, 9, 3, 10, CAT_PINK)
        A.rect(13, 9, 15, 10, CAT_PINK)
    elif face in ("east", "west", "south"):
        stripes(A, CAT_DARK, (2, 5, 8))
    elif face == "top":
        stripes(A, CAT_DARK, (2, 5, 8))


def cat_body(face, A):
    if face == "north":                       # 12 x 8: a cream bib
        A.ellipse(6, 7, 3.2, 6, CAT_CREAM)
    elif face in ("east", "west"):
        A.ellipse(A.w / 2 + (2 if face == "east" else -2), A.h - 1.5, 4.5, 3.5, shade(CAT_FUR, 0.9))      # haunch
        stripes(A, CAT_DARK, (2,))
    elif face in ("top", "south"):
        stripes(A, CAT_DARK, (2, 6, 10))


def cat_leg(face, A):
    A.rect(0, A.h - 2, A.w, A.h, CAT_CREAM)
    if face == "north":
        A.pix([(1, A.h - 1), (2, A.h - 1)], shade(CAT_CREAM, 0.82))


def cat_muzzle(face, A):
    if face == "north":                       # 8 x 4
        A.rect(3, 0, 5, 1, "#ff6a8e")
        A.pix([(3, 1), (4, 1)], "#a84060")
        A.pix([(2, 2), (5, 2), (3, 3), (4, 3)], "#6a3020")
    if face == "top":
        A.rect(3, A.h - 1, 5, A.h, "#ff6a8e")


def cat_tail(face, A):
    if face in ("east", "west"):
        for xx in (3, 7, 11):
            A.rect(xx, 0, xx + 1, A.h, CAT_DARK)
    elif face in ("top", "bottom"):
        for yy in (3, 7, 11):
            A.rect(0, yy, A.w, yy + 1, CAT_DARK)


def cat_pet():
    a = CAT
    body = pet_body("cat", a, [
        PC(a, (-1.5, -2, -1), (3, 2, 3), px(CAT_FUR, cat_body)),
        PC(a, (-1.25, -2, -1.5), (1, 2, 1), px(CAT_FUR, cat_leg)),
        PC(a, (0.25, -2, -1.5), (1, 2, 1), px(CAT_FUR, cat_leg))])
    head = Bone("head", "root", P(a, 0, -2, -0.75), cubes=[
        PC(a, (-2, -5, -2.25), (4, 3, 3), px(CAT_FUR, cat_head)),
        PC(a, (-1, -3, -2.75), (2, 1, 1), px(CAT_CREAM, cat_muzzle)),
        PC(a, (-2, -7, -0.75), (2, 2, 0), ear_plane(CAT_FUR, CAT_PINK, apex=0.3)),
        PC(a, (0, -7, -0.75), (2, 2, 0), ear_plane(CAT_FUR, CAT_PINK, apex=0.7))],
        anim={"type": "pet_head", "amp": 10}, parent="cat")
    lids = blink_bone("lids", "head", a, (-1.5, -4, -2.27), (3, 1, 0), lid_paint(CAT_FUR, [(0, 4), (8, 4)]))
    tail = Bone("tail", "root", P(a, 0, -0.75, 1.5), (58, 0, 0),
                [PC(a, (-0.5, -1.25, 1.25), (1, 1, 4), tail_tip(CAT_FUR, CAT_DARK, 4, feat=cat_tail))],
                anim=wag(14, 0.5), parent="cat")
    return [body, head, lids, tail]


item("mochi_cat", "Mochi the Cat", "PET", "REGULAR", 500,
     "A round ginger tabby with big green eyes and white paws that sits on your shoulder, looks about, blinks and "
     "swishes its tail.",
     cat_pet(), view=dict(PET_VIEW, glow="#ffb45a"))


# -------- Ember the fox: bright orange, white chest and cheeks, black socks, big ears and a huge bushy tail

FOX_FUR, FOX_DARK, FOX_WHITE, FOX_BLACK = "#ff8a34", "#d85a18", "#fff4e6", "#2a1a1a"
FOX = (PERCH[0], PERCH[1], PERCH[2] - 0.25)


def fox_head(face, A):
    if face == "north":                        # 16 x 12
        A.put((A.y >= 7) & ((A.x < 5) | (A.x >= 11)), FOX_WHITE)                  # cheeks
        A.pix([(0, 6), (15, 6)], FOX_WHITE)
        eye(A, 2, 3, 4, 4, "#ffb020")
        eye(A, 10, 3, 4, 4, "#ffb020")
    elif face in ("east", "west"):
        A.put(A.y >= 8, FOX_WHITE)


def fox_snout(face, A):
    if face in ("east", "west", "north"):
        A.put(A.y >= A.h - 1, FOX_WHITE)
    if face == "north":                        # 8 x 4
        A.rect(2, 0, 6, 2, FOX_BLACK)
        A.pix([(3, 0)], "#7a6a6a")
        A.pix([(3, 3), (4, 3)], "#5a2a20")
    if face == "top":
        A.rect(2, A.h - 2, 6, A.h, FOX_BLACK)
    if face == "bottom":
        A.fill(FOX_WHITE)


def fox_body(face, A):
    if face == "north":
        A.ellipse(6, 5, 3.2, 6, FOX_WHITE)


def fox_leg(face, A):
    A.put(A.y >= 2, FOX_BLACK)


def fox_pet():
    a = FOX
    body = pet_body("fox", a, [
        PC(a, (-1.5, -2.5, -1), (3, 2, 3), px(FOX_FUR, fox_body)),
        PC(a, (-1.25, -2, -1.5), (1, 2, 1), px(FOX_FUR, fox_leg)),
        PC(a, (0.25, -2, -1.5), (1, 2, 1), px(FOX_FUR, fox_leg)),
        PC(a, (-1.5, -1, 0.5), (1, 1, 1), px(FOX_BLACK)), PC(a, (0.5, -1, 0.5), (1, 1, 1), px(FOX_BLACK))])
    head = Bone("head", "root", P(a, 0, -2.5, -0.75), cubes=[
        PC(a, (-2, -5.5, -2.25), (4, 3, 3), px(FOX_FUR, fox_head)),
        PC(a, (-1, -3.5, -3.75), (2, 1, 2), px(FOX_FUR, fox_snout)),
        PC(a, (-2, -8, -0.75), (2, 3, 0), ear_plane(FOX_FUR, "#3a2020", FOX_BLACK, apex=0.35)),
        PC(a, (0, -8, -0.75), (2, 3, 0), ear_plane(FOX_FUR, "#3a2020", FOX_BLACK, apex=0.65))],
        anim={"type": "pet_head", "amp": 10}, parent="fox")
    lids = blink_bone("lids", "head", a, (-1.5, -4.75, -2.27), (3, 1, 0), lid_paint(FOX_FUR, [(0, 4), (8, 4)]))
    # the tail: big and bushy, raised up behind, white-tipped; it wags from its root
    tail = Bone("tail", "root", P(a, 0, -1.25, 1.75), (30, 0, 0),
                [PC(a, (-1, -2.25, 1.5), (2, 2, 4), tail_tip(FOX_FUR, FOX_WHITE, 4, 1.25))],
                anim=wag(12, 0.8), parent="fox")
    return [body, head, lids, tail]


item("ember_fox", "Ember the Fox", "PET", "REGULAR", 600,
     "A bright orange fox with a white chest, black socks and a big white-tipped tail that wags while it watches the "
     "world from your shoulder.",
     fox_pet(), view=dict(PET_VIEW, glow="#ff8a3a"))


# -------- Hoot the owl: a round brown owl, a pale face disc with huge amber eyes, ear tufts and folded wings

OWL_FUR, OWL_DARK, OWL_FACE, OWL_CHEST = "#9a6a42", "#5a3a22", "#f4e2c2", "#ecd4aa"
OWL = PERCH


def owl_speckle(A):
    A.put(((A.x + 2 * (A.y // 2)) % 4 == 1) & (A.y % 2 == 1), shade(OWL_FUR, 0.78))


def owl_head(face, A):
    if face == "north":                        # 16 x 12
        A.ellipse(4.5, 6, 4.4, 5.6, OWL_FACE)
        A.ellipse(11.5, 6, 4.4, 5.6, OWL_FACE)
        eye(A, 2, 3, 5, 4, "#ffa418")
        eye(A, 9, 3, 5, 4, "#ffa418")
        A.pix([(7, 1), (8, 1), (7, 2), (8, 2)], OWL_DARK)
    else:
        owl_speckle(A)


def owl_body(face, A):
    if face == "north":                        # 16 x 12 chest with little chevrons
        A.ellipse(8, 7.5, 5.5, 7, OWL_CHEST)
        inside = ((A.x + 0.5 - 8) / 5.5) ** 2 + ((A.y + 0.5 - 7.5) / 7) ** 2 < 0.8
        A.put(inside & (A.y % 3 == 1) & ((A.x + A.y // 3) % 3 == 0), "#b08a5a")
    else:
        owl_speckle(A)


def owl_beak(face, A):
    if face == "north":
        A.put(A.y >= A.h - 1, "#c07a1a")


def owl_wing(face, A):
    # layered feathers: each row's tips a little darker, offset row to row, the long flight feathers at the bottom
    for yy in range(1, A.h, 3):
        A.put((A.y == yy + 1) & ((A.x + (yy // 3) * 2) % 4 < 3), shade(OWL_FUR, 0.72))
        A.put((A.y == yy + 1) & ((A.x + (yy // 3) * 2) % 4 == 3), shade(OWL_FUR, 1.15))
    A.put(A.y >= A.h - 3, OWL_DARK)
    A.put((A.y >= A.h - 3) & (A.x % 2 == 0), shade(OWL_DARK, 1.25))


def owl_foot(face, A):
    if face == "north":
        A.pix([(0, A.h - 1), (2, A.h - 1)], "#c07a1a")


def owl_pet():
    a = OWL
    body = pet_body("owl", a, [
        PC(a, (-2, -3, -1.25), (4, 3, 3), px(OWL_FUR, owl_body)),
        PC(a, (-1.25, -1, -1.5), (1, 1, 1), px("#f2b23a", owl_foot)),
        PC(a, (0.25, -1, -1.5), (1, 1, 1), px("#f2b23a", owl_foot)),
        PC(a, (-1, -1.5, 1.5), (2, 1, 1), px(OWL_DARK))])
    head = Bone("head", "root", P(a, 0, -3, 0), cubes=[
        PC(a, (-2, -6, -1.75), (4, 3, 3), px(OWL_FUR, owl_head)),
        PC(a, (-0.5, -4.25, -2.5), (1, 1, 1), px("#f2b23a", owl_beak)),
        PC(a, (-2, -7, -0.5), (1, 1, 0), ear_plane(OWL_DARK, apex=0.0, base=(0.0, 1.0))),
        PC(a, (1, -7, -0.5), (1, 1, 0), ear_plane(OWL_DARK, apex=1.0, base=(0.0, 1.0)))],
        anim={"type": "pet_head", "amp": 11}, parent="owl")
    lids = blink_bone("lids", "head", a, (-1.5, -5.25, -1.77), (3, 1, 0), lid_paint(OWL_FACE, [(0, 5), (7, 5)], OWL_DARK))
    wing = Bone("wing_r", "root", P(a, -2, -3, -0.75), cubes=[
        PC(a, (-2.25, -3, -0.75), (1, 3, 3), px(shade(OWL_FUR, 0.92), owl_wing))],
        anim={"type": "wing", "side": 1, "base": 0, "amp": 6, "speed": 0.5, "fold": 0}, parent="owl")
    return [body, head, lids] + pet_pair(wing, a)


item("hoot_owl", "Hoot the Owl", "PET", "REGULAR", 500,
     "A round brown owl with huge amber eyes, ear tufts and a speckled cream chest; it perches on your shoulder, "
     "blinks slowly and turns its head to look around.",
     owl_pet(), view=dict(PET_VIEW, glow="#ffb020"))


# -------- Bubble the slime: a wobbly see-through jelly cube with a glowing core, a big grin and rosy cheeks

SLIME = (PERCH[0], PERCH[1] - 0.75, PERCH[2])     # a little higher: the whole jelly turns as it looks about
SLIME_HI, SLIME_LO, SLIME_INK = "#b6ffcc", "#36c878", "#0e3a22"


def slime_paint(face, cv):
    A = Art(face, cv)
    t = (A.y + 0.5) / A.h
    A.a[..., :3] = mix(rgbf(SLIME_HI), rgbf(SLIME_LO), t[..., None]) * FACE_LIGHT[face]
    A.a[..., 3] = 0.62
    if face == "north":                        # 16 x 16
        eye(A, 3, 5, 3, 4, "#3ab870", dark=SLIME_INK)
        eye(A, 10, 5, 3, 4, "#3ab870", dark=SLIME_INK)
        A.pix([(6, 11), (9, 11)] + [(i, 12) for i in range(7, 9)], SLIME_INK)
        A.rect(1, 10, 3, 11, "#ff8aa8")
        A.rect(13, 10, 15, 11, "#ff8aa8")
    if face in ("north", "top"):
        A.pix([(2, 2), (3, 2), (2, 3)], "#ffffff", alpha=0.9)
    edge = A.rim(0.7)
    A.a[edge, 3] = 0.9
    A.done()


def slime_core(face, A):
    A.e[..., :3] = rgbf("#8affb8")
    A.e[..., 3] = 0.3
    A.pix([(1, 1)], "#eaffef")


def slime_pet():
    a = SLIME
    core = pet_body("slime", a, [PC(a, (-1, -2.5, -0.5), (2, 2, 2), px("#4ad884", slime_core))])
    core.anim = dict(PET_ANIM, amp=0.8, speed=2.0)
    # the jelly is the slime's whole face: it glances about on its own, wobbling over the core
    jelly = Bone("jelly", "root", P(a, 0, -2, 0), cubes=[PC(a, (-2, -4, -2), (4, 4, 4), slime_paint)], parent="slime",
                 alpha=0.97, anim={"type": "pet_head", "amp": 8})
    lids = blink_bone("lids", "jelly", a, (-1.25, -2.75, -2.03), (3, 1, 0),
                      lid_paint("#86eaa8", [(0, 3), (7, 3)], SLIME_INK))
    return [core, jelly, lids]


item("bubble_slime", "Bubble the Slime", "PET", "REGULAR", 500,
     "A wobbly see-through green slime with a glowing core, rosy cheeks and a goofy grin that bounces on your shoulder.",
     slime_pet(), view=dict(PET_VIEW, glow="#3ad88a"))


# -------- Pip the wisp: a little spirit of cool blue flame that floats beside you

WISP = (HOVER[0] - 0.25, HOVER[1], HOVER[2])
WISP_HI, WISP_MID, WISP_LO = "#e6fcff", "#86dcff", "#3a8ae6"


def wisp_glow(A, stops, g=0.3):
    t = (A.y + 0.5) / A.h
    A.a[..., :3] = ramp(t, stops)[..., :3] * FACE_LIGHT[A.face]
    A.e[..., :3] = A.a[..., :3]
    A.e[..., 3] = g


def wisp_head(face, A):
    wisp_glow(A, [(0, WISP_HI), (0.55, WISP_MID), (1, WISP_LO)])
    if face == "north":                       # 16 x 16
        eye(A, 2, 6, 5, 4, "#4a8ae8", dark="#0e1a3a")
        eye(A, 9, 6, 5, 4, "#4a8ae8", dark="#0e1a3a")
        A.pix([(7, 12), (8, 12)], "#0e1a3a")
        A.rect(1, 11, 3, 12, "#ff9ac0", glow=0.4)
        A.rect(13, 11, 15, 12, "#ff9ac0", glow=0.4)


def wisp_flame(face, A):
    wisp_glow(A, [(0, "#ffffff"), (0.55, "#c8f8ff"), (1, "#6ae0ff")], 0.9)
    if face in ("north", "south", "east", "west"):
        A.clip(A.poly([(0.5, 0.0), (0.82, 0.45), (1.0, 1.0), (0.0, 1.0), (0.18, 0.45)]))
    elif face == "top":
        A.clip(np.zeros((A.h, A.w), bool))


def wisp_tail(face, A):
    wisp_glow(A, [(0, WISP_LO), (1, WISP_MID)], 0.5)
    if face in ("north", "south", "east", "west"):
        A.clip(A.poly([(0.0, 0.0), (1.0, 0.0), (0.72, 0.6), (0.5, 1.0), (0.28, 0.6)]))


def wisp_pet():
    a = WISP
    body = pet_body("wisp", a, [
        PC(a, (-1.5, -2, -1.5), (3, 1, 3), px(WISP_LO, lambda f, A: wisp_glow(A, [(0, WISP_LO), (1, "#2a6ad0")], 0.4)))])
    body.anim = dict(PET_ANIM, amp=1.0, speed=1.2)
    head = Bone("head", "root", P(a, 0, -4, 0), cubes=[PC(a, (-2, -6, -2), (4, 4, 4), px(WISP_MID, wisp_head))],
                anim={"type": "pet_head", "amp": 12}, parent="wisp")
    flame = Bone("flame", "root", P(a, 0, -6, 0), cubes=[
        PC(a, (-1.5, -8, -1.5), (3, 2, 3), px("#c8f8ff", wisp_flame, rim=0.88)),
        PC(a, (-0.5, -10, -0.5), (1, 2, 1), px("#ffffff", wisp_flame, rim=0.92))],
        anim={"type": "sway", "amp": 9, "speed": 1.3}, parent="head", emissive=True)
    tail = Bone("tail", "root", P(a, 0, -1.5, 1), (-20, 0, 0), cubes=[
        PC(a, (-1, -2, 0.5), (2, 1, 3), px(WISP_LO, lambda f, A: wisp_glow(A, [(0, WISP_MID), (1, WISP_LO)], 0.45), rim=0.82)),
        PC(a, (-0.5, -1.75, 3.5), (1, 1, 2), px(WISP_LO, lambda f, A: wisp_glow(A, [(0, WISP_MID), (1, WISP_LO)], 0.45), rim=0.82))],
        anim=wag(16, 0.6, phase=0.3), parent="wisp")
    hand = Bone("hand_r", "root", P(a, -2, -3, 0), cubes=[
        PC(a, (-2.75, -3.5, -0.5), (1, 1, 1), px(WISP_MID, lambda f, A: wisp_glow(A, [(0, WISP_HI), (1, WISP_MID)], 0.4)))],
        anim={"type": "wing", "side": 1, "base": 0, "amp": 18, "speed": 1.1, "fold": 0}, parent="wisp")
    lids = blink_bone("lids", "head", a, (-1.5, -4.5, -2.03), (3, 1, 0), lid_paint("#b4ecff", [(0, 5), (7, 5)], "#0e1a3a"))
    return [body, head, flame, tail, lids] + pet_pair(hand, a)


item("pip_wisp", "Pip the Wisp", "PET", "REGULAR", 500,
     "A tiny friendly wisp of cool blue flame with big shiny eyes that floats beside your shoulder, waving its little "
     "hands, its flame flickering.",
     wisp_pet(), view=dict(PET_VIEW, glow="#6cf2ff"))


# -------- Mini wyvern: a little teal dragon hovering beside you on beating violet wings

WYV = (HOVER[0], HOVER[1], HOVER[2])
WYV_SKIN, WYV_DARK, WYV_BELLY, WYV_HORN = "#38c8b0", "#1a7a78", "#ffe3a8", "#f6ecd0"


def wyv_scales(face, A):
    A.put(((A.x + (A.y // 2) * 2) % 4 == 0) & (A.y % 2 == 1), shade(WYV_SKIN, 0.86))


def wyv_head(face, A):
    wyv_scales(face, A)
    if face == "north":                         # 16 x 12
        eye(A, 2, 4, 4, 4, "#ffc42a", dark="#14202a")
        eye(A, 10, 4, 4, 4, "#ffc42a", dark="#14202a")
        A.rect(1, 3, 6, 4, WYV_DARK)
        A.rect(10, 3, 15, 4, WYV_DARK)
    if face in ("top", "south"):
        A.put((A.x == A.w // 2) | (A.x == A.w // 2 - 1), WYV_DARK)


def wyv_snout(face, A):
    wyv_scales(face, A)
    if face in ("east", "west", "north"):
        A.put(A.y >= A.h - 1, WYV_BELLY)
    if face == "north":                         # 8 x 4
        A.pix([(2, 1), (5, 1)], "#0e2a2a")
        A.pix([(1, 3), (6, 3)], "#ffffff")
    if face == "top":
        A.pix([(2, A.h - 2), (5, A.h - 2)], "#0e2a2a")
    if face == "bottom":
        A.fill(WYV_BELLY)


def wyv_body(face, A):
    if face == "north":                         # 12 x 12, a striped cream belly
        A.ellipse(6, 6.5, 3.4, 7, WYV_BELLY)
        A.put((A.y % 3 == 2) & (((A.x + 0.5 - 6) / 3.4) ** 2 + ((A.y + 0.5 - 6.5) / 7) ** 2 < 1), shade(WYV_BELLY, 0.86))
    else:
        wyv_scales(face, A)
    if face in ("top", "south"):
        A.put((A.x == A.w // 2) | (A.x == A.w // 2 - 1), WYV_DARK)            # a row of spines


def wyv_horn(face, A):
    """A horn along +z: pale at the root, darker towards the tip at the back."""
    t = (A.x + 0.5) / A.w if face == "east" else 1 - (A.x + 0.5) / A.w if face == "west" else 1 - (A.y + 0.5) / A.h
    if face in ("north", "south"):
        t = np.full_like(t, 0.0 if face == "north" else 1.0)
    A.a[..., :3] = ramp(np.clip(t, 0, 1), [(0, "#fff6e0"), (1, "#b89a6a")])[..., :3] * FACE_LIGHT[face]


def wyv_tail(face, A):
    wyv_scales(face, A)
    if face == "top":
        A.put((A.x == A.w // 2) | (A.x == A.w // 2 - 1), WYV_DARK)


def wyv_spade(face, A):
    A.fill("#9a5ae8")
    if face in ("top", "bottom"):
        A.clip(A.poly([(0.5, 0.0), (1.0, 0.45), (0.5, 1.0), (0.0, 0.45)]))


def wyv_wing(face, A):
    """A bat wing (right hand: the shoulder at the right edge of the north face), violet membrane, cream fingers."""
    m = A.poly([(1.0, 0.0), (0.0, 0.05), (0.08, 0.55), (0.3, 0.45), (0.42, 0.85), (0.62, 0.62), (0.8, 1.0), (1.0, 0.55)])
    xs = (A.x + 0.5) / A.w
    if face == "south":
        m, xs = m[:, ::-1], 1 - xs
    ys = (A.y + 0.5) / A.h
    A.a[..., :3] = ramp(ys, [(0, "#c47aff"), (1, "#6a32c0")])[..., :3]
    finger = np.zeros((A.h, A.w), bool)
    for (ex, ey) in ((0.04, 0.3), (0.36, 0.65), (0.7, 0.8)):
        d = np.abs((ey - 0.06) * (xs - 0.95) - (ex - 0.95) * (ys - 0.06)) / math.hypot(ey - 0.06, ex - 0.95)
        finger |= (d < 0.6 / A.w) & (xs >= min(ex, 0.95) - 0.02) & (xs <= 0.97)
    A.put(finger, "#4a2290")
    A.put((ys < 0.12) & (xs > 0.04), WYV_HORN)
    A.clip(m)
    A.rim(0.6, outline="#2a1050")


def wyv_leg(face, A):
    A.put(A.y >= A.h - 1, WYV_HORN)


def wyvern_pet():
    a = WYV
    body = pet_body("wyvern", a, [
        PC(a, (-1.5, -4, -1.25), (3, 3, 3), px(WYV_SKIN, wyv_body)),
        PC(a, (-1.25, -2, -1.5), (1, 2, 1), px(WYV_SKIN, wyv_leg)),
        PC(a, (0.25, -2, -1.5), (1, 2, 1), px(WYV_SKIN, wyv_leg)),
        PC(a, (-1.5, -1.5, 0.25), (1, 1, 1), px(WYV_SKIN, wyv_leg)),
        PC(a, (0.5, -1.5, 0.25), (1, 1, 1), px(WYV_SKIN, wyv_leg))])
    body.anim = dict(PET_ANIM, amp=1.0, speed=1.6)
    head = Bone("head", "root", P(a, 0, -4, -0.25), cubes=[
        PC(a, (-2, -7, -2.25), (4, 3, 3), px(WYV_SKIN, wyv_head)),
        PC(a, (-1, -5, -4.25), (2, 1, 2), px(WYV_SKIN, wyv_snout))],
        anim={"type": "pet_head", "amp": 12}, parent="wyvern")
    horns = Bone("horn_r", "root", P(a, -1.25, -7, -0.5), (18, 0, -10),
                 [PC(a, (-1.75, -7.75, -0.5), (1, 1, 3), px(WYV_HORN, wyv_horn, rim=0.8))], parent="head")
    lids = blink_bone("lids", "head", a, (-1.5, -6, -2.27), (3, 1, 0), lid_paint(WYV_SKIN, [(0, 4), (8, 4)], "#14202a"))
    tail = Bone("tail", "root", P(a, 0, -1.5, 1.75), (-12, 0, 0), [
        PC(a, (-0.5, -2, 1.5), (1, 1, 3), px(WYV_SKIN, wyv_tail)),
        PC(a, (-0.5, -2, 4.25), (1, 1, 2), px(WYV_SKIN, wyv_tail)),
        PC(a, (-1, -1.5, 6.0), (2, 0, 2), px("#9a5ae8", wyv_spade, rim=0.7))],
        anim=wag(18, 0.7), parent="wyvern")
    wing = Bone("wing_r", "root", P(a, -1, -4, 1.0), (0, 0, 40), cubes=[PC(a, (-6, -7.5, 1.0), (5, 4, 0), px("#c07aff", wyv_wing, rim=0))],
                anim={"type": "wing", "side": 1, "base": 18, "amp": 24, "speed": 1.4, "ease": "flap", "lift": 12, "fold": 40},
                parent="wyvern")
    return [body, head] + pet_pair(horns, a) + [lids, tail] + pet_pair(wing, a)


item("mini_wyvern", "Mini Wyvern", "PET", "REGULAR", 800,
     "A little teal dragon with violet wings, golden eyes and cream horns that hovers beside your shoulder, wings "
     "beating and tail swishing.",
     wyvern_pet(), view=dict(PET_VIEW, glow="#3ad8c0"))


# -------- Prism Sprite (special): a crystal fairy whose facets and wings shimmer through every colour

SPRITE = (HOVER[0], HOVER[1] - 0.5, HOVER[2])


def prism_facets(face, cv, plain=False):
    """Cut crystal: four facets per face in their own hues (posterised), crisp facet edges, a darker rim, and a band of
    white light that slides across each face once per loop; the hues roll round the colour wheel. `plain`: one soft
    facet (the face, so the eyes read)."""
    t, x = cv.yy(), cv.xx()
    y, xx = cv.grid()
    ph = cv.phase
    seedf = {"north": 0.0, "east": 0.25, "south": 0.5, "west": 0.75, "top": 0.1, "bottom": 0.6}[face]
    facet = np.zeros_like(t) if plain else ((x > t) * 1 + (x > 1 - t) * 2).astype(np.float32)
    h = (seedf + facet * 0.11 + ph + (t * 0.12 if plain else 0)) % 1
    val = np.array([0.97, 0.8, 1.0, 0.88], np.float32)[facet.astype(int)]
    col = hsv_rgb(np.floor(h * 24) / 24, 0.32 if plain else 0.55, 1.0) * val[..., None]
    k = max(1, cv.s // 4)
    if not plain:
        edge = (np.abs(x - t) < 0.7 * k / cv.w) | (np.abs(x - (1 - t)) < 0.7 * k / cv.w)
        col[edge] = col[edge] * 0.72
    rim = (y < k) | (xx < k) | (y >= cv.h - k) | (xx >= cv.w - k)
    col[rim] = hsv_rgb((seedf + ph + 0.5) % 1, 0.4, 0.62)
    band = ((x + t) * 0.5 - ((ph * 1.0 + seedf) % 1.0) * 1.6 + 0.3)
    core, halo = np.abs(band) < 0.05, np.abs(band) < 0.11
    col[halo & ~core & ~rim] = np.clip(col[halo & ~core & ~rim] * 0.5 + 0.55, 0, 1)
    col[core & ~rim] = 1.0
    cv.arr[..., :3] = col
    cv.arr[..., 3] = 1
    em_rgb(cv, np.concatenate([col, np.ones_like(t)[..., None]], -1), np.ones_like(t) * 0.45 + core * 0.5)


def prism_head(face, cv):
    prism_facets(face, cv, plain=face == "north")
    if face != "north":
        return
    A = Art(face, cv, per_unit=4)                 # the face, drawn over the crystal in 12 x 12 art px
    eye(A, 1, 4, 4, 4, "#8a5af0", dark="#1a1040", lid="#e8e0ff")
    eye(A, 7, 4, 4, 4, "#8a5af0", dark="#1a1040", lid="#e8e0ff")
    A.pix([(5, 9), (6, 9)], "#1a1040")
    A.rect(0, 8, 1, 9, "#ff9ad8")
    A.rect(11, 8, 12, 9, "#ff9ad8")
    A.over()


def prism_wing(face, cv):
    """A dragonfly-like crystal wing (right hand, shoulder at the right edge of the north face): a crisp light outline,
    two veins and a rainbow that rolls along it."""
    A = Art(face, cv, per_unit=8)
    m = A.poly([(1.0, 0.25), (0.55, 0.0), (0.12, 0.02), (0.0, 0.3), (0.2, 0.62), (0.62, 0.66), (1.0, 0.55)])
    lower = A.poly([(1.0, 0.6), (0.6, 0.66), (0.3, 0.78), (0.32, 1.0), (0.7, 0.96), (1.0, 0.72)])
    xs = (A.x + 0.5) / A.w
    if face == "south":
        m, lower, xs = m[:, ::-1], lower[:, ::-1], 1 - xs
    m |= lower
    ys = (A.y + 0.5) / A.h
    h = (xs * 0.5 + ys * 0.35 + cv.phase) % 1
    col = hsv_rgb(np.floor(h * 24) / 24, 0.45, 1.0)
    A.a[..., :3] = col
    A.a[..., 3] = 0.6
    vein = (np.abs(ys - (0.3 + (1 - xs) * 0.05)) < 0.5 / A.h) | (np.abs(ys - 0.8) < 0.5 / A.h)
    A.put(vein & m, mix(col[vein & m], np.ones(3), 0.6), alpha=0.9)
    A.clip(m)
    edge = A.rim(1.0)
    A.a[edge, :3] = mix(A.a[edge, :3], np.ones(3), 0.75)
    A.a[edge, 3] = 0.95
    A.e[..., :3] = A.a[..., :3]
    A.e[..., 3] = (A.a[..., 3] > 0) * (0.45 + 0.4 * edge)
    A.done()


def prism_pet():
    a = SPRITE
    body = pet_body("sprite", a, [
        PC(a, (-1, -3, -1), (2, 2, 2), prism_facets),
        PC(a, (-0.5, -1, -0.5), (1, 1, 1), prism_facets),
        PC(a, (-0.5, -3, 0.75), (1, 1, 1), prism_facets)])
    body.anim = dict(PET_ANIM, amp=1.2, speed=1.0)
    head = Bone("head", "root", P(a, 0, -3, 0), cubes=[
        PC(a, (-1.5, -6, -1.5), (3, 3, 3), prism_head),
        PC(a, (-0.5, -7, -0.5), (1, 1, 1), prism_facets)],
        anim={"type": "pet_head", "amp": 12}, parent="sprite")
    lids = blink_bone("lids", "head", a, (-1.25, -5, -1.53), (3, 1, 0),
                      lid_paint("#e8e0ff", [(0, 4), (6, 4)], "#1a1040"))
    wing = Bone("wing_r", "root", P(a, -0.5, -2.5, 1.6), (0, 0, 22), cubes=[PC(a, (-5.5, -6.5, 1.6), (5, 5, 0), prism_wing)],
                anim={"type": "wing", "side": 1, "base": 22, "amp": 22, "speed": 2.6, "lift": 8, "fold": 40},
                parent="sprite")
    return [body, head, lids] + pet_pair(wing, a)


item("prism_sprite", "Prism Sprite", "PET", "SPECIAL", None,
     "A tiny crystal fairy whose facets and wings shimmer through every colour while it flutters beside your shoulder.",
     prism_pet(), view=dict(PET_VIEW, glow="#c8a0ff"), animation={"frames": 16, "frametime": 2, "interpolate": True, "scale": 16})


# ---------------------------------------------------------------------------------------------- trails, kill and win effects
# Particles only (spawned by the viewer's own client); the thumbnail is a picture of the effect.

item("stardust_trail", "Stardust Trail", "TRAIL", "REGULAR", 200,
     "Glittering violet and gold dust drifts from your feet as you move.",
     fx={"type": "trail", "particle": "soft", "colors": ["#FFFFFF", "#FFD36A", "#B98CFF"], "rate": 12, "size": 0.8,
         "life": 1.1, "fade": 0.3, "sizeEnd": 0.3, "gravity": 0.05},
     thumb="trail")
item("ember_trail", "Ember Trail", "TRAIL", "REGULAR", 200,
     "Hot orange embers flicker up from your footsteps and fade to smoke.",
     fx={"type": "trail", "particle": "soft", "colors": ["#FFF0A8", "#FF7A2A", "#C0200A", "#3A1A14"], "rate": 12,
         "size": 0.7, "life": 1.0, "fade": 0.2, "sizeEnd": 0.25, "gravity": -0.5},
     thumb="embers")
item("petal_trail", "Petal Trail", "TRAIL", "REGULAR", 200,
     "Soft pink and white blossom petals tumble along behind you.",
     fx={"type": "trail", "particle": "dust", "colors": ["#FFC0D8", "#FF8AB8", "#FFF4F8"], "rate": 8, "size": 0.95},
     thumb="petals")
item("shatterburst", "Shatterburst", "KILL_EFFECT", "REGULAR", 200,
     "When you get a kill, crystal shards burst outward from where they fell.",
     fx={"type": "burst", "particle": "dust", "colors": ["#9FFCFF", "#3EF0D0", "#FFFFFF"], "count": 42, "size": 1.1,
         "spark": True},
     thumb="burst")
item("firework_pop", "Firework Pop", "KILL_EFFECT", "REGULAR", 200,
     "A kill sets off a bright firework of red, gold and blue sparks where they fell.",
     fx={"type": "burst", "particle": "dust", "colors": ["#FF4A5A", "#FFD23A", "#4A8AFF", "#FFFFFF"], "count": 56, "size": 1.0,
         "spark": True},
     thumb="firework")
item("soul_release", "Soul Release", "KILL_EFFECT", "REGULAR", 200,
     "Pale blue wisps spiral up from where your opponent fell and drift away.",
     fx={"type": "spiral", "particle": "soft", "colors": ["#E8FFFF", "#9AF0FF", "#3A7AFF"], "count": 50, "size": 0.9,
         "height": 2.4, "life": 1.6, "fade": 0.3, "sizeEnd": 0.3, "gravity": -0.6},
     thumb="soul")
item("victory_spiral", "Victory Spiral", "WIN_EFFECT", "REGULAR", 200,
     "Win a game and two golden spirals climb around you, ending in a burst of sparks.",
     fx={"type": "spiral", "particle": "soft", "colors": ["#FFF4C2", "#FFD36A", "#FF8A3A"], "count": 80, "size": 1.0,
         "height": 3.0, "life": 1.4, "fade": 0.25, "sizeEnd": 0.35},
     thumb="spiral")
item("confetti_cannon", "Confetti Cannon", "WIN_EFFECT", "REGULAR", 200,
     "Win a game and a cloud of rainbow confetti bursts out around you.",
     fx={"type": "burst", "particle": "dust", "colors": ["#FF4A8A", "#FFD23A", "#3AC8FF", "#5AE07A", "#B07AFF"], "count": 90,
         "size": 1.3},
     thumb="confetti")
item("starlight_fountain", "Starlight Fountain", "WIN_EFFECT", "REGULAR", 200,
     "Win a game and a tall column of white starlight spirals up around you.",
     fx={"type": "spiral", "particle": "end_rod", "colors": ["#FFFFFF", "#D8E4FF"], "count": 60, "size": 1.0, "height": 3.4},
     thumb="fountain")


# ---------------------------------------------------------------------------------------------- emotes
# Keyframes: [seconds, pitch, yaw, roll] in degrees for each model part, replacing its usual angles while it plays.

item("sunny_wave", "Sunny Wave", "EMOTE", "REGULAR", 250,
     "A big, cheerful wave hello.",
     emote={"length": 1.6, "loop": True, "parts": {
         "rightArm": [[0, -170, 0, 15], [0.4, -170, 0, -15], [0.8, -170, 0, 15], [1.2, -170, 0, -15], [1.6, -170, 0, 15]],
         "head": [[0, 0, 0, 0], [0.8, 0, 0, 6], [1.6, 0, 0, 0]]}},
     view={"pose": {"rightArm": (-168, 0, -18), "head": (0, 0, 6)}, "yaw": -20},   # the arm swung out, mid-wave
     thumb="emote")
item("drum_solo", "Drum Solo", "EMOTE", "REGULAR", 250,
     "Air drums: alternate hits with a head bob to the beat.",
     emote={"length": 1.0, "loop": True, "parts": {
         "rightArm": [[0, -50, 0, 0], [0.25, -85, 0, 0], [0.5, -50, 0, 0], [0.75, -60, 0, 0], [1.0, -50, 0, 0]],
         "leftArm": [[0, -60, 0, 0], [0.25, -50, 0, 0], [0.5, -60, 0, 0], [0.75, -85, 0, 0], [1.0, -60, 0, 0]],
         "head": [[0, 10, 0, 0], [0.25, -4, 0, 0], [0.5, 10, 0, 0], [0.75, -4, 0, 0], [1.0, 10, 0, 0]]}},
     view={"glow": "#ff8a5a"},
     thumb="emote")
item("crisp_salute", "Crisp Salute", "EMOTE", "REGULAR", 250,
     "Stand to attention and snap off a sharp salute.",
     emote={"length": 2.0, "loop": False, "parts": {
         "rightArm": [[0, 0, 0, 0], [0.25, -145, 0, 35], [1.5, -145, 0, 35], [2.0, 0, 0, 0]],
         "head": [[0, 0, 0, 0], [0.25, -6, 0, 0], [1.5, -6, 0, 0], [2.0, 0, 0, 0]]}},
     view={"pose": {"rightArm": (-145, 0, 35), "head": (-6, 0, 0)}, "yaw": -28, "glow": "#7ab8ff"},
     thumb="emote")
item("slow_clap", "Slow Clap", "EMOTE", "REGULAR", 250,
     "A long, slow, meaningful round of applause.",
     emote={"length": 1.2, "loop": True, "parts": {
         "rightArm": [[0, -72, 18, 0], [0.5, -72, -22, 0], [0.62, -72, -22, 0], [1.2, -72, 18, 0]],
         "leftArm": [[0, -72, -18, 0], [0.5, -72, 22, 0], [0.62, -72, 22, 0], [1.2, -72, -18, 0]],
         "head": [[0, 4, 0, 0], [0.5, 8, 0, 0], [1.2, 4, 0, 0]]}},
     view={"pose": {"rightArm": (-72, -22, 0), "leftArm": (-72, 22, 0), "head": (8, 0, 0)}, "yaw": -30, "glow": "#5ae07a"},
     thumb="emote")
item("grand_bow", "Grand Bow", "EMOTE", "REGULAR", 250,
     "A theatrical bow with a flourish of the hand, for when you've earned the applause.",
     emote={"length": 2.4, "loop": False, "parts": {
         "head": [[0, 0, 0, 0], [0.5, 38, 0, 0], [1.8, 38, 0, 0], [2.4, 0, 0, 0]],
         "rightArm": [[0, 0, 0, 0], [0.5, -55, -30, 30], [1.8, -55, -30, 30], [2.4, 0, 0, 0]],
         "leftArm": [[0, 0, 0, 0], [0.5, 25, 0, -55], [1.8, 25, 0, -55], [2.4, 0, 0, 0]]}},
     view={"pose": {"head": (38, 0, 0), "rightArm": (-55, -30, 30), "leftArm": (25, 0, -55)}, "yaw": -34, "glow": "#ffd36a"},
     thumb="emote")


# ---------------------------------------------------------------------------------------------- item skins
# Item space: y up, 0..16 like an item model. Drawn instead of your own sword, on your screen only.

def glass_blade(c_edge, c_mid, c_dark, core=None):
    def p(face, cv):
        t, x = cv.yy(), cv.xx()
        if face in ("north", "south"):
            # a faceted blade: bright edges, a dark body, a light ridge down the middle
            # a bevelled blade lit from one side: a light half, a dark half, a bright ridge and bright edges
            d = np.abs(x - 0.5) * 2
            side = smooth((x - 0.5) / 0.04 + 0.5)
            cv.arr[:] = mix(hexc(c_mid), hexc(c_dark), side[..., None])
            cv.arr[..., :3] = np.clip(cv.arr[..., :3] * (1.15 - 0.3 * t)[..., None], 0, 1)
            ridge = np.exp(-((x - 0.5) * cv.w / max(0.8, cv.s * 0.18)) ** 2)
            edge = smooth((d - 0.82) / 0.12)
            cv.arr[..., :3] = mix(cv.arr[..., :3], hexc(c_edge)[:3], np.clip(ridge * 0.8 + edge, 0, 1)[..., None])
            n = pnoise(cv.h, cv.w, 2.0, 700, octaves=3, aspect=4)
            cv.arr[..., :3] *= (0.92 + 0.16 * n)[..., None]
            if core:
                em_from(cv, np.clip(ridge * 0.9 + smooth(1 - d / 0.35) * 0.35, 0, 1), core)
        else:
            cv.arr[:] = hexc(c_edge)
        bevel(cv, 0.22, 0.26)
    return p


def wrap_grip(c1, c2):
    def p(face, cv):
        cv.arr[:] = hexc(c1)
        y, x = cv.grid()
        wrap = ((y + x * 0.5) // max(1, cv.s // 2)) % 2 == 0
        cv.arr[wrap, :3] = hexc(c2)[:3]
        bevel(cv, 0.15, 0.25)
    return p


SWORD_DISPLAY = {"thirdperson_righthand": {"rotation": [0, -90, 55], "translation": [0, 4.0, 0.5], "scale": [0.85, 0.85, 0.85]},
                 "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.6, 1.13], "scale": [0.6, 0.6, 0.6]}}
ITEM_VIEW = {"yaw": 0, "pitch": 0, "focus": "item", "margin": 1.04}


def sword(blade_paint, tip_paint, guard_paint, grip_paint, pommel_paint, gem_p, blade_w=2, extra=()):
    bx = 8 - blade_w / 2
    return [Bone("blade", "item", (8, 8, 8), (0, 0, -45), [
        Cube((7, -1, 7), (2, 2, 2), pommel_paint),
        Cube((7.5, 1, 7.5), (1, 4, 1), grip_paint, inflate=0.1),
        Cube((4.5, 5, 7), (7, 1, 2), guard_paint),
        Cube((7.25, 4.75, 6.6), (1, 1, 0), gem_p),
        Cube((bx, 6, 7.5), (blade_w, 11, 1), blade_paint),
        Cube((7.5, 17, 7.5), (1, 1, 1), tip_paint)] + list(extra))]


item("nightglass_blade", "Nightglass Blade", "ITEM_SKIN", "REGULAR", 250,
     "Your sword, re-forged in faceted dark glass with a glowing violet core and a silver guard. Shows on swords you "
     "hold, on your screen.",
     sword(glass_blade("#e8d8ff", "#6a4aa8", "#1a1030", "#c07aff"), glass_blade("#e8d8ff", "#6a4aa8", "#1a1030", "#c07aff"),
           metal("#ffffff", "#7a7a98", 0.35), wrap_grip("#22222a", "#6a5a9a"), metal("#ffffff", "#7a7a98", 0.35),
           gem_paint("#f4d8ff", "#9a3aff")),
     item_space=True, display=SWORD_DISPLAY, view=ITEM_VIEW)


def ember_saber_blade(face, cv):
    glass_blade("#ffe8a8", "#d83a1a", "#3a0c08")(face, cv)
    if face in ("north", "south"):
        t, x = cv.yy(), cv.xx()
        heat = smooth(np.abs(x - 0.5) * 2 - 0.5) * (0.6 + 0.4 * pnoise(cv.h, cv.w, 2.0, 711, octaves=2))
        em_rgb(cv, ramp(heat, FIRE), heat * 0.9)
        y, xx = cv.grid()
        teeth = (xx >= cv.w - cv.s // 2) & (((y // max(1, cv.s)) % 2) == 0)
        cv.arr[teeth, 3] = 0
        cv.em[teeth] = 0


item("emberfang_saber", "Emberfang Saber", "ITEM_SKIN", "REGULAR", 250,
     "Your sword as a broad, notched saber whose edges glow like a forge, with a black iron guard and a ruby. Shows on "
     "swords you hold, on your screen.",
     sword(ember_saber_blade, glass_blade("#ffe8a8", "#d83a1a", "#3a0c08", "#ff8a3a"), metal("#5a5a62", "#141418", 0.25),
           wrap_grip("#2a1410", "#8a3a1a"), metal("#5a5a62", "#141418", 0.25), gem_paint("#ffd0d0", "#ff2a2a"), blade_w=3),
     item_space=True, display=SWORD_DISPLAY, view=ITEM_VIEW)


def ice_blade(face, cv):
    glass_blade("#ffffff", "#9fe4ff", "#3a8ad6", "#bff4ff")(face, cv)
    cv.arr[..., 3] = 0.9


item("frostbite_edge", "Frostbite Edge", "ITEM_SKIN", "REGULAR", 250,
     "Your sword carved from clear blue ice, frosted along the edges, with crystal shards on the guard. Shows on swords "
     "you hold, on your screen.",
     sword(ice_blade, ice_blade, metal("#ffffff", "#7ab8e0", 0.4), wrap_grip("#e8f4ff", "#6aaad8"), metal("#ffffff", "#7ab8e0", 0.4),
           gem_paint("#ffffff", "#5ad8ff")),
     item_space=True, display=SWORD_DISPLAY, view=ITEM_VIEW)


# ============================================================================================== serialisation

def model_json(it, tw, th, scale):
    bones = []
    for b in it["bones"]:
        cubes = []
        for c in b.cubes:
            cd = {"origin": c.origin, "size": c.size, "uv": c.uv}
            if c.faces is not None:
                cd = {"origin": c.origin, "size": c.size, "faces": {
                    FACE_JSON[f]: dict({"uv": [float(v) for v in rect]}, **({"rotation": rot * 90} if rot else {}))
                    for f, (rect, rot, _) in c.faces.items()}}
            if c.inflate:
                cd["inflate"] = c.inflate
            if c.mirror:
                cd["mirror"] = True
            cubes.append(cd)
        bd = {"name": b.name, "attach": b.attach, "pivot": b.pivot, "rotation": b.rotation, "cubes": cubes}
        if b.parent:
            bd["parent"] = b.parent
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
    if it["physics"]:
        j["physics"] = it["physics"]
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


def cuboid_quads(origin, size, uv, inflate, mirror, tw, th, faces=None):
    """The six quads of ModelPart.Cuboid: 4 corner positions and their UVs (normalised). With per-face UVs (`faces`),
    only the listed faces, each corner turned like CosmeticModel.faceCuboid."""
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
    quads = [((v6, v5, v1, v2), (k, p, l, q)), ((v3, v4, v8, v7), (l, q, m, p)), ((v1, v5, v8, v4), (j, q, k, r)),
             ((v2, v1, v4, v3), (k, q, l, r)), ((v6, v2, v3, v7), (l, q, n, r)), ((v5, v6, v7, v8), (n, q, o, r))]
    out = []
    per = faces
    for fi, (verts, (u1, va, u2, vb)) in enumerate(quads):
        rot = 0
        if per is not None:
            f = per.get(("top", "bottom", "west", "north", "east", "south")[fi])
            if f is None:
                continue
            (u1, va, u2, vb), rot = f[0], f[1]
        uvs = [(u2, va), (u1, va), (u1, vb), (u2, vb)]
        uvs = [uvs[(i + rot) % 4] for i in range(4)]
        out.append((np.array(verts, float), np.array([(a / tw, b / th) for a, b in uvs], float)))
    return out


class Scene:
    def __init__(self):
        self.quads = []   # (points[4,3], uv[4,2] or None, texture or None, emissive texture or None, color, emissive)

    def cube(self, M, origin, size, uv, inflate, mirror, tw, th, tex, color, emissive, em=None, faces=None):
        for pts, uvs in cuboid_quads(origin, size, uv or (0, 0), inflate, mirror, tw, th, faces):
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
        g1 = blur(glow, 2.2 * ss * size / THUMB)        # the same glow at any thumbnail size
        g2 = blur(glow, 7 * ss * size / THUMB)
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


def thumb_model(it, tex_arr, em_arr, tw, th, phase=None, size=THUMB):
    scene = Scene()
    view = it["view"]
    focus = view.get("focus")
    parts = {"head": {"head"}}.get(focus)
    mats = add_mannequin(scene, parts=parts, shadow=focus not in ("head",)) if focus not in ("item", "pet") else {}
    placed = {}
    for b in it["bones"]:
        base = placed[b.parent] if b.parent else mats.get(ATTACH.get(b.attach), np.eye(4))
        rx, ry, rz = b.rotation
        anim = b.anim or {}
        if anim.get("type") == "wing":
            ry += anim["side"] * (anim["base"] + view.get("wing_open", 0))
        M = base @ translate(b.pivot) @ rotate(rx, ry, rz) @ translate([-v for v in b.pivot])
        if anim.get("type") == "cape":
            M = base @ translate(b.pivot) @ rotate(10, 0, 0) @ rotate(0, 180, 0) @ translate([-v for v in b.pivot])
        if anim.get("type") == "spin" and (view.get("spin") is not None or phase is not None):
            turn = view.get("spin", 0) + (phase or 0) * view.get("spin_per_loop", 0)
            M = base @ translate(b.pivot) @ rotate(rx, ry + turn, rz) @ translate([-v for v in b.pivot])
        placed[b.name] = M
        if anim.get("type") == "blink":
            continue                 # eyelids: open (flat) in a still picture
        color = tint_of(it) if b.tint else np.ones(4, np.float32)
        color = color.copy()
        color[3] = b.alpha
        for c in b.cubes:
            scene.cube(M, c.origin, c.size, c.uv, c.inflate, c.mirror, tw, th, tex_arr, color, b.emissive,
                       None if b.emissive else em_arr, c.faces)
    frame = view.get("frame")
    if focus == "head" and frame is None:
        frame = None
    im = render_scene(scene, view.get("yaw", 30), view.get("pitch", 10), size=size, yup=focus == "item", frame=frame,
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
    S = im.width
    bg = np.zeros((S, S, 4), np.float32)
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32)
    r = np.sqrt((xx - S / 2) ** 2 + (yy - S / 2) ** 2) / (S / 2)
    c = hexc(color)
    bg[..., :3] = c[:3]
    bg[..., 3] = np.clip(1 - r, 0, 1) ** 2 * strength
    out = to_image(bg)
    out.alpha_composite(im)
    return out


def thumb_particles(kind, colors):
    """A picture of a particle effect: shapes on a supersampled canvas, then a soft glow from the same shapes."""
    S = THUMB * 4
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    cols = [tuple(int(v * 255) for v in hexc(c)[:3]) for c in colors]
    r = np.random.default_rng(len(kind) * 7 + 3)

    def spark(x, y, s, col, a=255):
        d.ellipse((x - s, y - s, x + s, y + s), fill=col + (a,))
        d.line((x - s * 2.6, y, x + s * 2.6, y), fill=col + (int(a * 0.7),), width=max(1, int(s / 2)))
        d.line((x, y - s * 2.6, x, y + s * 2.6), fill=col + (int(a * 0.7),), width=max(1, int(s / 2)))

    def blob(x, y, s, col, a=255):
        d.ellipse((x - s, y - s, x + s, y + s), fill=col + (a,))

    if kind == "trail":
        for i in range(80):
            t = i / 80
            x = S * 0.1 + t * S * 0.8 + r.normal(0, 12)
            y = S * 0.72 - math.sin(t * math.pi) * S * 0.36 + r.normal(0, 16)
            spark(x, y, 3 + t * 11, cols[i % len(cols)], int(70 + 185 * t))
    elif kind == "embers":
        for i in range(90):
            t = i / 90
            x = S * 0.12 + t * S * 0.76 + r.normal(0, 14)
            y = S * 0.82 - r.random() * S * 0.55 * (0.4 + t)
            s = 3 + r.random() * 8 * (0.5 + t)
            col = cols[min(len(cols) - 1, int((1 - (S * 0.85 - y) / (S * 0.6)) * len(cols)))]
            blob(x, y, s, col, int(90 + 165 * t))
        for i in range(10):
            x = S * (0.15 + i * 0.075)
            d.ellipse((x - 20, S * 0.83, x + 20, S * 0.86), fill=(255, 120, 40, 90))
    elif kind == "petals":
        for i in range(55):
            t = i / 55
            x = S * 0.1 + t * S * 0.8 + r.normal(0, 18)
            y = S * 0.75 - math.sin(t * math.pi) * S * 0.3 + r.normal(0, 30)
            s = 8 + t * 14
            pet = Image.new("RGBA", (int(s * 3), int(s * 3)), (0, 0, 0, 0))
            pd = ImageDraw.Draw(pet)
            c = cols[i % len(cols)]
            pd.ellipse((s * 0.5, s, s * 2.5, s * 2), fill=c + (int(110 + 145 * t),))
            pd.ellipse((s * 1.1, s * 1.25, s * 1.9, s * 1.75), fill=(255, 255, 255, int(60 + 80 * t)))
            pet = pet.rotate(r.random() * 360, resample=Image.BICUBIC)
            im.alpha_composite(pet, (int(x - pet.width / 2), int(y - pet.height / 2)))
    elif kind == "burst":
        cx = cy = S / 2
        for i in range(52):
            a = r.random() * math.tau
            dist = 40 + r.random() * S * 0.36
            x, y = cx + math.cos(a) * dist, cy + math.sin(a) * dist
            ux, uy = math.cos(a), math.sin(a)
            L, W = 14 + r.random() * 22, 5 + r.random() * 5
            pts = [(x + ux * L, y + uy * L), (x - uy * W, y + ux * W), (x - ux * L * 0.6, y - uy * L * 0.6), (x + uy * W, y - ux * W)]
            d.polygon(pts, fill=cols[i % len(cols)] + (235,))
            d.line([(x + ux * L, y + uy * L), (x - ux * L * 0.6, y - uy * L * 0.6)], fill=(255, 255, 255, 160), width=2)
        spark(cx, cy, 22, (255, 255, 255))
    elif kind == "firework":
        cx, cy = S / 2, S * 0.45
        for i in range(64):
            a = i / 64 * math.tau + r.normal(0, 0.03)
            L = S * (0.26 + r.random() * 0.12)
            col = cols[i % (len(cols) - 1)]
            for k in range(10):
                f = k / 10
                x, y = cx + math.cos(a) * L * f, cy + math.sin(a) * L * f + (f ** 2) * 30
                blob(x, y, 2 + f * 6, col, int(40 + 215 * f))
            spark(cx + math.cos(a) * L, cy + math.sin(a) * L + 30, 6, col)
        spark(cx, cy, 20, (255, 255, 255))
        d.line([(cx, S * 0.98), (cx + 6, cy + 30)], fill=(255, 220, 150, 120), width=5)
    elif kind == "soul":
        for arm in range(2):
            for i in range(50):
                t = i / 50
                a = t * math.tau * 1.6 + arm * math.pi
                x = S / 2 + math.cos(a) * S * 0.2 * (1 - t * 0.4)
                y = S * 0.9 - t * S * 0.78
                blob(x, y, 5 + 9 * (1 - t), cols[(i + arm) % len(cols)], int(230 - 170 * t))
        for i in range(26):
            spark(S / 2 + r.normal(0, S * 0.16), S * (0.08 + r.random() * 0.4), 2 + r.random() * 4, cols[i % len(cols)], 220)
    elif kind == "spiral":
        for arm in range(2):
            for i in range(64):
                t = i / 64
                a = t * math.tau * 2.2 + arm * math.pi
                x = S / 2 + math.cos(a) * S * 0.27 * (1 - t * 0.3)
                y = S * 0.9 - t * S * 0.74
                spark(x, y, 4 + 7 * t, cols[(i + arm) % len(cols)], int(120 + 135 * t))
        spark(S / 2, S * 0.13, 24, (255, 244, 194))
    elif kind == "confetti":
        for i in range(140):
            x, y = S / 2 + r.normal(0, S * 0.22), S * 0.55 + r.normal(0, S * 0.2)
            w, h = 10 + r.random() * 12, 5 + r.random() * 6
            piece = Image.new("RGBA", (int(w * 2), int(w * 2)), (0, 0, 0, 0))
            ImageDraw.Draw(piece).rectangle((w - w / 2, w - h / 2, w + w / 2, w + h / 2), fill=cols[i % len(cols)] + (240,))
            piece = piece.rotate(r.random() * 360, resample=Image.BICUBIC)
            im.alpha_composite(piece, (int(x - w), int(y - w)))
        for i in range(12):
            a = -math.pi / 2 + (i - 5.5) * 0.18
            d.line([(S / 2, S * 0.95), (S / 2 + math.cos(a) * S * 0.25, S * 0.95 + math.sin(a) * S * 0.25)], fill=(255, 255, 255, 60), width=4)
    elif kind == "fountain":
        for arm in range(3):
            for i in range(50):
                t = i / 50
                a = t * math.tau * 1.8 + arm * math.tau / 3
                x = S / 2 + math.cos(a) * S * 0.22 * (0.6 + t * 0.4)
                y = S * 0.92 - t * S * 0.8
                spark(x, y, 3 + 5 * t, cols[i % len(cols)], int(110 + 145 * t))
        for i in range(30):
            spark(S / 2 + r.normal(0, S * 0.18), S * 0.12 + r.random() * S * 0.3, 3 + r.random() * 4, (255, 255, 255), 200)
    arr = np.asarray(im, np.float32) / 255
    pre = arr[..., :3] * arr[..., 3:4]
    glow = blur(pre, 10) * 0.9 + blur(pre, 28) * 0.7
    pre = pre + glow
    a = np.clip(arr[..., 3] + lum(glow) * (1 - arr[..., 3]), 0, 1)
    rgb = np.where(a[..., None] > 1e-4, pre / np.maximum(a[..., None], 1e-4), 0)
    out = to_image(np.concatenate([np.clip(rgb, 0, 1), a[..., None]], -1), dither=False)
    out = out.convert("RGBa").resize((THUMB, THUMB), Image.LANCZOS).convert("RGBA")
    return badge(out, colors[0], 0.3)


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


def quantize256(a, cols, inv):
    """The 256 colours that best cover an RGBA picture (weighted k-means, k-means++ start, over its distinct colours;
    alpha weighted heavily so see-through levels stay apart), as a palette image. No dithering: flat areas stay flat."""
    if len(cols) > 40000:                       # renders with soft glow: merge near-identical shades first
        q4 = (a.astype(np.int32) // 4 * 4 + 2).clip(0, 255).astype(np.uint8)
        cols, inv = np.unique(q4.reshape(-1, 4).view(np.uint32).ravel(), return_inverse=True)
    w = np.array([1.0, 1.0, 1.0, 3.0], np.float32)
    X = cols.view(np.uint8).reshape(-1, 4).astype(np.float32) * w
    count = np.bincount(inv.ravel(), minlength=len(cols)).astype(np.float32)
    rng = np.random.default_rng(256)
    sub = rng.choice(len(X), size=min(len(X), 12000), replace=False, p=count / count.sum())
    S = X[sub]
    centres = [S[0]]
    d = ((S - S[0]) ** 2).sum(1)
    for _ in range(min(255, len(S) - 1)):
        j = rng.choice(len(S), p=d / d.sum()) if d.sum() > 0 else 0
        centres.append(S[j])
        d = np.minimum(d, ((S - S[j]) ** 2).sum(1))
    centres = np.array(centres, np.float32)

    def nearest(c):
        out = np.empty(len(X), np.int64)
        for i in range(0, len(X), 4096):
            out[i:i + 4096] = ((X[i:i + 4096, None, :] - c[None, :, :]) ** 2).sum(-1).argmin(1)
        return out
    for _ in range(8):
        lab = nearest(centres)
        tot = np.zeros_like(centres)
        np.add.at(tot, lab, X * count[:, None])
        n = np.bincount(lab, weights=count, minlength=len(centres))[:, None]
        centres = np.where(n > 0, tot / np.maximum(n, 1e-6), centres).astype(np.float32)
    lab = nearest(centres)
    pal = np.clip(centres / w + 0.5, 0, 255).astype(np.uint8)
    q = Image.fromarray(lab[inv.reshape(a.shape[:2])].astype(np.uint8), "P")
    q.putpalette(pal[:, :3].ravel().tolist())
    q.info["transparency"] = bytes(pal[:, 3].tolist())
    return q


def png_bytes(im, indexed=False, soft_alpha=False):
    """PNG bytes. indexed: when the picture has at most 256 distinct colours, store it as a palette PNG (lossless,
    much smaller for the special items' banded pixel art; the client and browsers read it as RGBA all the same)."""
    buf = io.BytesIO()
    if indexed:
        a = np.asarray(im.convert("RGBA"))
        flat = a.reshape(-1, 4).view(np.uint32).ravel()
        cols, inv = np.unique(flat, return_inverse=True)
        if len(cols) <= 256:
            pal = cols.view(np.uint8).reshape(-1, 4)
            p = Image.fromarray(inv.reshape(a.shape[:2]).astype(np.uint8), "P")
            p.putpalette(pal[:, :3].ravel().tolist())
            p.info["transparency"] = bytes(pal[:, 3].tolist())
            p.save(buf, "PNG", optimize=True, transparency=bytes(pal[:, 3].tolist()))
            raw = buf.getvalue()
            check = np.asarray(Image.open(io.BytesIO(raw)).convert("RGBA"))
            if np.array_equal(check, a):
                return raw
            buf = io.BytesIO()
        else:
            # more colours (glints, soft glow, renders): the nearest 256-colour palette, kept only when it's visually
            # the same (tiny mean error, alpha all but exact so cut-out edges don't move)
            q = quantize256(a, cols, inv)
            q.save(buf, "PNG", optimize=True, transparency=q.info["transparency"])
            raw = buf.getvalue()
            back = np.asarray(Image.open(io.BytesIO(raw)).convert("RGBA"), np.float32)
            err = np.abs(back - a.astype(np.float32))
            alpha_ok = err[..., 3].mean() < 3.0 if soft_alpha else err[..., 3].max() <= 12   # thumbs: soft glow edges
            if err[..., :3].mean() < (4.5 if soft_alpha else 3.0) and alpha_ok:
                return raw
            buf = io.BytesIO()
    im.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def build(it):
    """All files of one item: {name: bytes}."""
    files = {"item.json": (json.dumps(item_json(it), indent=1) + "\n").encode()}
    if it["bones"]:
        tw, th = it["layout"] or pack(it["bones"])
        anim = it["animation"]
        frames = anim["frames"] if anim else 1
        if anim:
            # special items: at least the regular items' 8 texels per unit (capes 16, laid out by hand to fit)
            scale = anim.get("scale", 8)
            if scale < 8 or max(tw, th) * scale > ANIM_FRAME_PX or th * scale * frames > MAX_STRIP_PX or frames > 64:
                raise SystemExit(f"{it['id']}: {frames} frames of {tw}x{th} at {scale} px per unit exceed the client's limits")
        else:
            scale = scale_for(tw, th, frames, it["scale_cap"])
        texs, ems = [], []
        for f in range(frames):
            PHASE[0] = f / frames
            tex, em = paint_model(it["bones"], tw, th, it["item_space"], scale)
            texs.append(tex)
            ems.append(em)
        PHASE[0] = 0.0
        files["model.json"] = (json.dumps(model_json(it, tw, th, scale), indent=1) + "\n").encode()
        # special items dither their own gradients (crisp bands, see levels()): no extra noise, so the strips stay small
        files["texture.png"] = png_bytes(to_image(np.concatenate(texs, 0), dither=not anim), indexed=bool(anim))
        has_em = any(e[..., 3].max() > 0 for e in ems)
        if has_em:
            files["emissive.png"] = png_bytes(to_image(np.concatenate(ems, 0), dither=not anim), indexed=bool(anim), soft_alpha=True)
        if anim and frames <= 32:
            # an animated item's thumbnail is a strip too (one square per texture frame), so it plays in the Wardrobe
            # grid and on the shop cards; as large as the 4096 px strip allows (up to 256), so the cards stay sharp
            ts = min(256, MAX_STRIP_PX // frames // 8 * 8)
            strip = [thumb_model(it, texs[k], ems[k] if has_em else None, tw, th, k / frames, size=ts) for k in range(frames)]
            thumb = Image.new("RGBA", (ts, ts * frames), (0, 0, 0, 0))
            for k, im in enumerate(strip):
                thumb.paste(im, (0, k * ts))
        else:
            k = it["view"].get("thumb_frame", 0)
            thumb = thumb_model(it, texs[k], ems[k] if has_em else None, tw, th)
    elif it["emote"]:
        thumb = thumb_emote(it)
    else:
        thumb = thumb_particles(it["thumb"], it["fx"]["colors"])
    files["thumb.png"] = png_bytes(thumb, indexed=bool(it["animation"]), soft_alpha=True)
    check_limits(it, files)
    if it["slot"] == "PET":
        gap = check_pet_clearance(it)
        if gap < PET_CLEARANCE:
            raise SystemExit(f"{it['id']}: the pet comes within {gap:.2f} px of the player (needs {PET_CLEARANCE})")
    return files


def check_limits(it, files):
    """The limits the client enforces (README): refuse to write an item it would reject."""
    bones = it["bones"]
    cubes = [c for b in bones for c in b.cubes]
    problems = []
    if len(bones) > 32 or len(cubes) > 256:
        problems.append(f"{len(bones)} bones / {len(cubes)} cubes")
    for c in cubes:
        for v in c.origin + [o + s for o, s in zip(c.origin, c.size)]:
            if abs(v) > 64:
                problems.append(f"coordinate {v} outside +-64")
    for name, data in files.items():
        if len(data) > 2 * 1024 * 1024:
            problems.append(f"{name} is {len(data)} bytes")
    if problems:
        raise SystemExit(f"{it['id']}: " + "; ".join(problems))


# The player model's parts the pet must keep clear of (entity space, y down), with the outer skin layer: (pivot, box
# min, box max) in the part's own space. The head turns with the pet, so it is checked in the head's turned frame.
PLAYER_HEAD = ((-4.5, -8.5, -4.5), (4.5, 0.5, 4.5))
PLAYER_BODY = ((-4.25, -0.25, -2.25), (4.25, 12.25, 2.25))
PLAYER_ARM_L = ((-1.25, -2.25, -2.25), (2.75, 10.25, 2.25))      # about its pivot (5, 2, 0); the right arm mirrors it


def _rx(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def _ry(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def _rz(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def _box_distance(p, lo, hi):
    """Signed distance of points to a box: positive outside, negative inside."""
    lo, hi = np.array(lo), np.array(hi)
    c, h = (lo + hi) / 2, (hi - lo) / 2
    q = np.abs(p - c) - h
    out = np.linalg.norm(np.maximum(q, 0), axis=-1)
    inside = np.minimum(np.max(q, axis=-1), 0)
    return out + inside


def _anim_ranges(b):
    """(pitch, yaw, roll, dy, scale y) ranges a bone's animation can reach in the client (see CosmeticDraw.bone)."""
    a = b.anim or {}
    t = a.get("type")
    z = (0.0, 0.0)
    if t == "pet":
        amp = a.get("amp", 0.5)
        return (-6, 6), (-5, 5), (-5, 5), (-amp * 1.4 - 2.5, 0), (1, 1)
    if t == "pet_head":
        amp = a.get("amp", 0) or 14
        return (-14, 14), (-amp, amp), (-7, 7), z, (1, 1)
    if t == "wing":
        side, base, amp, fold = a.get("side", 1), a.get("base", 0), a.get("amp", 0) * 1.3, a.get("fold", 28)
        ys = sorted((side * (base - amp), side * (base + amp + fold)))
        return (min(-12 if fold else 0, 0), max(a.get("lift", 0), 0)), tuple(ys), tuple(sorted((0, side * fold * 0.35))), z, (1, 1)
    if t == "sway":
        return z, z, (-a.get("amp", 0), a.get("amp", 0)), z, (1, 1)
    if t == "bob":
        return z, z, z, (-a.get("amp", 0.5), a.get("amp", 0.5)), (1, 1)
    if t == "blink":
        return z, z, z, z, (1, 1)
    return z, z, z, z, (1, 1)


def check_pet_clearance(it, states=2500, seed=7, detail=None):
    """Poses the pet a few thousand times (every animation anywhere in its range, often at its extremes, trailing your
    turn, with you sneaking or not, arms swinging as when sprinting) and returns the closest any point of it comes to
    your head (at any look up or down), body or arms, in pixels. Negative = it would clip."""
    rng = np.random.default_rng(seed)
    bones = it["bones"]
    by = {b.name: b for b in bones}
    pts = {}
    for b in bones:
        ps = []
        for c in b.cubes:
            o, s = np.array(c.origin, float), np.array(c.size, float)
            g = np.linspace(0, 1, 4)
            for axis in range(3):
                for side in (0, 1):
                    u, v = np.meshgrid(g, g)
                    q = np.zeros((u.size, 3))
                    q[:, axis] = side
                    q[:, (axis + 1) % 3] = u.ravel()
                    q[:, (axis + 2) % 3] = v.ravel()
                    ps.append(o + q * s)
        pts[b.name] = np.concatenate(ps) if ps else np.zeros((0, 3))
    ranges = {b.name: _anim_ranges(b) for b in bones}
    worst = 1e9

    def pick(lo_hi):
        lo, hi = lo_hi
        r = rng.random()
        return lo if r < 0.3 else hi if r < 0.6 else lo + (hi - lo) * rng.random()

    for _ in range(states):
        local = {}
        for b in bones:
            (p0, p1), (y0, y1), (r0, r1), d, sc = ranges[b.name]
            pitch = b.rotation[0] + pick((p0, p1))
            yaw = b.rotation[1] + pick((y0, y1))
            roll = b.rotation[2] + pick((r0, r1))
            dy = pick(d)
            R = _rz(math.radians(roll)) @ _ry(math.radians(yaw)) @ _rx(math.radians(pitch))
            pv = np.array(b.pivot, float)
            M = np.eye(4)
            M[:3, :3] = R
            M[:3, 3] = pv + np.array([0, dy, 0]) - R @ pv
            local[b.name] = M if not b.parent else local[b.parent] @ M
        P_all, owner = [], []
        for b in bones:
            M = local[b.name]
            P_all.append(pts[b.name] @ M[:3, :3].T + M[:3, 3])
            owner += [b.name] * len(pts[b.name])
        Pp = np.concatenate(P_all)
        owner = np.array(owner)

        def note(part, dist):
            if detail is not None:
                for name in set(owner):
                    m = owner == name
                    k = (part, name)
                    detail[k] = min(detail.get(k, 1e9), float(dist[m].min()))
        lag = math.radians(pick((-PET_LAG, PET_LAG)))
        sneak = rng.random() < 0.35
        drop = 3.2 if sneak else 0.0
        Pp = Pp @ _ry(-lag).T + np.array([0, drop, 0])         # in your head's turned frame
        # head: any look up or down, about its pivot (4.2 lower when sneaking)
        hp = np.array([0, 4.2 if sneak else 0.0, 0])
        for pitch in (-90, -45, 0, 45, 90, pick((-90, 90))):
            q = (Pp - hp) @ _rx(math.radians(pitch))            # = Rx(-pitch) applied to each point
            dist = _box_distance(q, *PLAYER_HEAD)
            note("head", dist)
            worst = min(worst, dist.min())
        # body and arms: your head may be turned up to 60 degrees from your body
        hy = math.radians(pick((-60, 60)))
        Wb = Pp @ _ry(hy).T
        bp = np.array([0, 3.2 if sneak else 0.0, 0])
        q = (Wb - bp) @ _rx(0.5 if sneak else 0.0)
        dist = _box_distance(q, *PLAYER_BODY)
        note("body", dist)
        worst = min(worst, dist.min())
        for side in (1, -1):
            ap = np.array([5 * side, 2 + (3.2 if sneak else 0.0), 0])
            apitch = pick((-1.0, 1.0)) + (0.4 if sneak else 0.0)
            aroll = -side * pick((0.0, 0.1))
            Ra = _rz(aroll) @ _ry(0) @ _rx(apitch)
            q = (Wb - ap) @ Ra                                   # = Ra^-1 applied to each point
            if side == -1:
                q = q * np.array([-1, 1, 1])
            dist = _box_distance(q, *PLAYER_ARM_L)
            note("arm", dist)
            worst = min(worst, dist.min())
    return worst


def contact_sheet(path, built):
    cols = 8
    cell = 150
    rows = (len(built) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * cell, rows * (cell + 26)), (18, 18, 24))
    d = ImageDraw.Draw(sheet)
    for i, (it, files) in enumerate(built):
        im = Image.open(io.BytesIO(files["thumb.png"])).convert("RGBA")
        im = im.crop((0, 0, im.width, im.width)).resize((THUMB, THUMB), Image.LANCZOS)
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
    index = {"version": "2.7-1", "format": FORMAT, "items": []}
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
