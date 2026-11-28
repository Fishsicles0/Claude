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
  texture.png   box-UV (or per-face UV) texture for model.json, `textureScale` (1, 2, 4, 8 or 16; capes 16)
                texels per model unit; animated
                (special) items stack their frames top to bottom (a vertical strip)
  emissive.png  optional: the parts that glow (full bright), same layout and frames as texture.png
  thumb.png     preview used by the Wardrobe grid and the shop cards: 256 x 256 for regular items (pets and auras
                128); animated items: a strip of frames

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
THUMB_HD = 256         # regular items' thumbnails (the special items' strips are 168-256 per frame)
THUMB_SITE = 512       # the website's still thumbnails (thumb-hd.png, site-src only)
CRISP_EXEMPT = ("PET", "AURA")   # drawn in their own style by their own painters (see the pets and auras sections)
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


def pack(bones, max_h=None):
    """Shelf-packs every cube's box-UV rectangle into the smallest square-ish texture (sides multiples of 2).
    Mirrored copies share their original's rectangle. Returns (tw, th) in model units. With `max_h`, prefers a
    wider layout no taller than that (an animated strip's frames must fit the client's strip height)."""
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
        score = (max_h is not None and th > max_h, max(tw, th), tw * th)
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


def scale_for(tw, th, frames=1, cap=MAX_SCALE, max_px=MAX_FRAME_PX):
    """The largest power-of-two texel density whose frame and strip stay within the client's limits."""
    if max(tw, th) > MAX_UV:
        raise SystemExit(f"UV space {tw}x{th} too large")
    for s in (16, 8, 4, 2, 1):
        if s <= cap and max(tw, th) * s <= max_px and th * s * frames <= MAX_STRIP_PX:
            return s
    raise SystemExit(f"texture {tw}x{th} x {frames} frames too large")


# ============================================================================================== painting

PHASE = [0.0]          # the animation phase (0..1) of the frame being painted
# Regular items (not pets or auras) are drawn as crisp pixel art, like the special items: while CRISP[0] is set the
# soft helpers (grain, cloth, bevel, ao_edges, brushed metal) turn into flat, pixel-exact versions and every painted face
# goes through crispify() (flat colour bands, ordered dithering only where two bands meet, hard cut-out edges). Hand-drawn
# faces that are already pixel art (the regular capes) set cv.hand and are left as they are.
CRISP = [False]


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
                if CRISP[0] and not getattr(cv, "hand", False):
                    crispify(cv)
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
        if CRISP[0] and not getattr(cv, "hand", False):
            crispify(cv)
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
    if CRISP[0]:
        # pixel art: a one-texel (two at 16 per unit) highlight along the top and left, a shade along the bottom/right
        e = max(1, cv.s // 8)
        if cv.h <= 2 * e or cv.w <= 2 * e:
            return
        a = cv.arr
        y, x = cv.grid()
        up = (y < e) | (x < e)
        dn = ((y >= cv.h - e) | (x >= cv.w - e)) & ~up
        a[up, :3] = np.clip(a[up, :3] + light * 1.3 * (1 - a[up, :3] * 0.5), 0, 1)
        a[dn, :3] *= 1 - min(0.6, dark * 1.5)
        return
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
    if CRISP[0]:
        return                  # pixel art: no per-texel noise
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
    if CRISP[0]:
        # pixel art: a flat darker frame one texel (two at 16 per unit) wide
        e = max(1, cv.s // 8)
        y, x = cv.grid()
        m = (y < e) | (x < e) | (y >= cv.h - e) | (x >= cv.w - e)
        cv.arr[m, :3] *= 1 - amount
        return
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
        if brushed and not CRISP[0]:
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
    if CRISP[0]:
        weave(cv, 0.03)         # pixel art: the twill only, no soft folds
        return
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


# The thin edges of a cape (1 unit deep) sample the lining's bound edge 1:1 (lining_binding): the lining is stored a
# quarter turned in [16, 0, 32, 10] (cape rows run along u, its columns down v, right to left), so each side's strip is
# one unit of that rect, turned to run along the edge.
CAPE_EDGES = {"west": ([16, 9, 32, 10], 3, False), "east": ([16, 0, 32, 1], 1, False),
              "top": ([16, 0, 17, 10], 1, False), "bottom": ([31, 0, 32, 10], 3, False)}


def cape_bones_hd(outer, lining_fn):
    def p(face, cv):
        (outer if face == "north" else lining_fn)(cv)
    faces = dict({"north": ([0, 0, 16, 10], 1, True), "south": ([16, 0, 32, 10], 1, True)}, **CAPE_EDGES)
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


def lining_binding(cv, bind, thread, unit=1.0, light=None):
    """The bound edge of a cape's lining, `unit` model units wide all round: piping rounded by its light (a dark
    outline at both sides, a lit crown), with a running stitch along its middle. The cape's thin edges sample this
    band 1:1 (see CAPE_EDGES), so it also reads as the rolled hem seen edge-on."""
    y, x = cv.grid()
    w = cv.s * unit
    d = np.minimum(np.minimum(x, cv.w - 1 - x), np.minimum(y, cv.h - 1 - y))       # texels from the outer edge
    band = d < w
    t = np.clip((d + 0.5) / w, 0, 1)                                                # 0 outer .. 1 inner
    b = hexc(bind) if isinstance(bind, str) else np.asarray(bind, np.float32)
    hi = np.ones(4, np.float32) if light is None else (hexc(light) if isinstance(light, str) else np.asarray(light, np.float32))
    cols = [mix(b, np.zeros(4, np.float32), 0.62), mix(b, np.zeros(4, np.float32), 0.35), b,
            mix(b, hi, 0.3), mix(b, hi, 0.55)]
    prof = np.clip(np.sin(t * math.pi), 0, 1) ** 0.8 * 0.92 + 0.08 * (1 - t)                       # rounded piping, lit crown
    idx = np.clip(levels(prof, 5, 0.05), 0, 4)
    rgb = palette(idx, cols)
    line = (d < 1) | ((d >= w - 1) & (d < w))
    rgb[line] = mix(b, np.zeros(4, np.float32), 0.72)
    cv.arr[band] = rgb[band]
    # a running stitch down the middle of the band, following it round the corners
    along = np.where(np.minimum(x, cv.w - 1 - x) < np.minimum(y, cv.h - 1 - y), y, x)
    st = band & (np.abs(d - (w - 1) / 2) < max(0.75, cv.s / 16)) & ((along % (cv.s * 0.5)) < cv.s * 0.28)
    cv.arr[st, :3] = (hexc(thread) if isinstance(thread, str) else np.asarray(thread, np.float32))[:3]
    cv.arr[band, 3] = 1
    cv.em[band] = 0


def lining_label(cv, cx, cy, thread, ground="#efe6d2"):
    """A small woven maker's label stitched to the lining under the collar: a cream tab with an outline, corner
    stitches and an embroidered four-point star in the cape's thread colour."""
    s = cv.s
    w, h = int(s * 2.25), int(s * 1.0)
    x0, y0 = int(cx * s - w / 2), int(cy * s - h / 2)
    g = hexc(ground)
    th = hexc(thread) if isinstance(thread, str) else np.asarray(thread, np.float32)
    cv.arr[y0 - 1:y0 + h + 1, x0 - 1:x0 + w + 1, :3] = (g * 0.42)[:3]
    cv.arr[y0:y0 + h, x0:x0 + w, :3] = g[:3]
    cv.arr[y0:y0 + 1, x0:x0 + w, :3] = mix(g, np.ones(4, np.float32), 0.5)[:3]
    cv.arr[y0 + h - 1:y0 + h, x0:x0 + w, :3] = (g * 0.8)[:3]
    for ox, oy in ((2, 2), (w - 3, 2), (2, h - 3), (w - 3, h - 3)):
        cv.arr[y0 + oy, x0 + ox, :3] = (g * 0.55)[:3]
    mx, my = x0 + w // 2, y0 + h // 2
    r = max(2, h // 2 - 2)
    for k in range(-r, r + 1):
        a = 1 - abs(k) / (r + 1)
        cv.arr[my, mx + k, :3] = mix(g, th, 0.5 + 0.5 * a)[:3]
        cv.arr[my + k, mx, :3] = mix(g, th, 0.5 + 0.5 * a)[:3]
    cv.arr[my - 1:my + 2, mx - 1:mx + 2, :3] = th[:3]
    cv.arr[y0 - 1:y0 + h + 1, x0 - 1:x0 + w + 1, 3] = 1


def lining_hd(color_top, color_bottom, quilt):
    """Every cape's lining: padded satin quilted in diamonds (each pad lit at its crown and shaded into its seams, the
    seams sewn with a running stitch), a soft diagonal sheen across the satin, a bound edge all round (lining_binding,
    which the cape's thin edges sample too) and a woven maker's label under the collar. Crisp bands, no noise (it
    repeats in every frame of an animated cape)."""
    def p(cv):
        y, x = cv.units()
        W, H = cv.w / cv.s, cv.h / cv.s
        top, bot, q = hexc(color_top), hexc(color_bottom), hexc(quilt)
        if lum(top[:3]) < 0.08:                 # near-black satin: lifted towards the thread so the inside reads
            top, bot = mix(top, q, 0.22), mix(bot, q, 0.12)
        # satin: top to bottom, lifted by a broad diagonal sheen and a narrow second one
        sd = x / W * 0.55 + y / H * 0.45
        sheen = np.exp(-((sd - 0.34) / 0.11) ** 2) * 0.55 + np.exp(-((sd - 0.7) / 0.05) ** 2) * 0.3
        v = np.clip(y / H * 0.92 - sheen * 0.42 + 0.12, 0, 1)
        n = 9
        cols = [mix(mix(top, np.ones(4, np.float32), 0.16), top, k / 2) if k < 2 else mix(top, bot, (k - 2) / (n - 3))
                for k in range(n)]
        rgb = palette(levels(v, n, 0.12), cols)          # crisp bands (dither only right at a step: small strips)
        # quilted pads: lit at the crown, darker into the seams (three crisp steps)
        P = 2.5
        a = np.abs(((x + y) % P) - P / 2)
        b = np.abs(((x - y) % P) - P / 2)
        puff = np.minimum(a, b) / (P / 2)                    # 0 on a seam .. 1 at the pad's middle
        lit = (((x + y) % P) < P / 2) & (((x - y) % P) >= P / 2)
        k = levels(np.clip(puff * 1.25, 0, 1), 3, 0.05)
        shade = np.where(k == 0, 0.78, np.where(k == 1, 0.9, 1.0)) * np.where(lit & (k > 0), 1.06, 1.0)
        rgb[..., :3] = np.clip(rgb[..., :3] * shade[..., None], 0, 1)
        seam = puff < 0.05
        stitch = seam & (((x * 2.0) % 1.0) < 0.62)
        thread = mix(q, mix(top, np.ones(4, np.float32), 0.3), 0.15)
        rgb[seam, :3] = mix(bot, q, 0.35)[:3]
        rgb[stitch, :3] = thread[:3]
        knot = (a < 0.13) & (b < 0.13)
        rgb[knot, :3] = mix(q, np.ones(4, np.float32), 0.25)[:3]
        cv.arr[:] = rgb
        cv.arr[..., 3] = 1
        lining_binding(cv, mix(bot, q, 0.45), mix(q, np.ones(4, np.float32), 0.3), light=mix(q, np.ones(4, np.float32), 0.35))
        lining_label(cv, W / 2, 2.15, q)
    return p


# ---------------------------------------------------------------------------------------------- crisp pixel art (regular)
# Regular items are drawn to the special items' standard: flat colour bands with ordered dithering only where two bands
# meet, hard outlines and rim light/shade, emissive for whatever glows. Capes are painted by hand at 16 texels per unit
# (the helpers below); everything else is painted by its own painter and then cleaned up by crispify().

def _nb_max(a):
    p = np.pad(a, 1, mode="edge")
    out = a.copy()
    for dy in range(3):
        for dx in range(3):
            out = np.maximum(out, p[dy:dy + a.shape[0], dx:dx + a.shape[1]])
    return out


def _kmeans(X, K, iters=8):
    """K colour centres for the rows of X (n x c), seeded at luminance quantiles (deterministic). Returns (C, d2)."""
    lu = X[:, :3] @ np.array([0.3, 0.59, 0.11], np.float32)
    order = np.argsort(lu, kind="stable")
    C = X[order[((np.arange(K) + 0.5) / K * len(X)).astype(int)]].copy()
    for _ in range(iters):
        lab = ((X[:, None, :] - C[None]) ** 2).sum(-1).argmin(1)
        for k in range(K):
            m = lab == k
            if m.any():
                C[k] = X[m].mean(0)
    return C, ((X[:, None, :] - C[None]) ** 2).sum(-1)


def crispify(cv, edge=0.14):
    """Turns a painted face into pixel art: hard cut-out edges and alpha steps, its colours reduced to a few flat bands
    (k-means, more bands for bigger faces) with ordered dithering only right where two bands meet; the glow layer the
    same, in four steps."""
    a, h, w = cv.arr, cv.h, cv.w
    if h == 0 or w == 0:
        return
    al = np.clip(a[..., 3], 0, 1)
    nb = _nb_max(al)
    al = np.where(al >= nb * 0.5, nb, 0.0)                  # a soft fringe joins its neighbours' opacity or drops out
    al = np.where(al > 0, levels(al, 9, 0.3) / 8.0, 0.0)  # see-through parts: alpha in eighths, dithered where they step
    a[..., 3] = al
    m = al > 0
    n = int(m.sum())
    e = cv.em
    e[~m] = 0
    if n == 0:
        return
    X = np.clip(a[m][:, :3], 0, 1).astype(np.float32)
    K = 3 if n < 64 else 4 if n < 256 else 6 if n < 2048 else 8 if n < 8192 else 10
    if len(np.unique(np.round(X * 255).astype(np.int32), axis=0)) > K:
        C, d = _kmeans(X, K)
        o = np.argsort(d, 1)
        i1, i2 = o[:, 0], o[:, 1]
        d1 = np.sqrt(np.take_along_axis(d, i1[:, None], 1)[:, 0])
        d2 = np.sqrt(np.take_along_axis(d, i2[:, None], 1)[:, 0])
        f = d1 / np.maximum(d1 + d2, 1e-6)                  # 0 on a band's colour .. 0.5 halfway to the next band
        p2 = np.clip((f - (0.5 - edge)) / edge, 0, 1) * 0.5
        pick = p2 > bayer(h, w)[m] + 0.5
        a[m, :3] = np.where(pick[:, None], C[i2], C[i1])
    ea = np.clip(e[..., 3], 0, 1)
    if ea.max() > 0:
        lv = levels(ea, 5, 0.3).astype(np.float32) / 4.0
        e[..., 3] = lv
        em = lv > 0
        e[~em] = 0
        if em.any():
            Y = np.clip(e[em][:, :3], 0, 1).astype(np.float32)
            if len(np.unique(np.round(Y * 255).astype(np.int32), axis=0)) > 4:
                C, d = _kmeans(Y, 4)
                e[em, :3] = C[d.argmin(1)]


def nshift(m, dy, dx):
    """m moved down by dy and right by dx (False comes in at the edges)."""
    h, w = m.shape
    out = np.zeros_like(m)
    out[max(0, dy):h - max(0, -dy), max(0, dx):w - max(0, -dx)] = m[max(0, -dy):h - max(0, dy), max(0, -dx):w - max(0, dx)]
    return out


def grow(m, r=1, diag=True):
    out = m.copy()
    steps = ((1, 0), (-1, 0), (0, 1), (0, -1)) + (((1, 1), (1, -1), (-1, 1), (-1, -1)) if diag else ())
    for _ in range(r):
        g = out.copy()
        for dy, dx in steps:
            g |= nshift(out, dy, dx)
        out = g
    return out


def lit_rim(m, e=1):
    """The pixels of m within e of its top or left edge (where the light catches it)."""
    out = np.zeros_like(m)
    for k in range(1, e + 1):
        out |= m & (~nshift(m, k, 0) | ~nshift(m, 0, k))
    return out


def shade_rim(m, e=1):
    """The pixels of m within e of its bottom or right edge (in shade)."""
    out = np.zeros_like(m)
    for k in range(1, e + 1):
        out |= m & (~nshift(m, -k, 0) | ~nshift(m, 0, -k))
    return out & ~lit_rim(m, e)


def pmask(cv, fn):
    """A hard-edged (aliased) mask drawn at the texture's own resolution: fn(ImageDraw) draws in texels with fill 255."""
    im = Image.new("L", (cv.w, cv.h), 0)
    fn(ImageDraw.Draw(im))
    return np.asarray(im) > 127


def disc(cv, cx, cy, r, ky=1.0):
    y, x = cv.grid()
    return (x + 0.5 - cx) ** 2 + ((y + 0.5 - cy) * ky) ** 2 < r * r


def put(cv, m, col, glow=0.0):
    c = hexc(col) if isinstance(col, str) else np.asarray(col, np.float32)
    cv.arr[m, :3] = c[:3]
    cv.arr[m, 3] = 1
    if glow:
        em_from(cv, m.astype(np.float32) * glow, c)


def sprite(cv, m, fill, line=None, hi=None, lo=None, glow=0.0, e=1):
    """A flat shape with an outline outside it, rim light along its top/left and shade along its bottom/right."""
    if line is not None:
        put(cv, grow(m) & ~m, line)
    put(cv, m, fill)
    if lo is not None:
        put(cv, shade_rim(m, e), lo)
    if hi is not None:
        put(cv, lit_rim(m, e), hi)
    if glow:
        em_rgb(cv, cv.arr, m.astype(np.float32) * glow)


def bands(v, cols, edge=0.4):
    """Flat colour bands for a 0..1 field, dithered only where two bands meet."""
    return palette(levels(np.clip(v, 0, 1), len(cols), edge), cols)


def px_star(cv, x, y, r, col, core="#ffffff", glow=1.0):
    """A crisp four-point star: a plus with arms r texels long and a bright centre."""
    xi, yi = int(x), int(y)
    m = np.zeros((cv.h, cv.w), bool)
    for k in range(-r, r + 1):
        if 0 <= yi < cv.h and 0 <= xi + k < cv.w:
            m[yi, xi + k] = True
        if 0 <= yi + k < cv.h and 0 <= xi < cv.w:
            m[yi + k, xi] = True
    put(cv, m, col, glow)
    if r >= 3:
        put(cv, m & disc(cv, xi + 0.5, yi + 0.5, 1.6), core, glow)
    elif 0 <= yi < cv.h and 0 <= xi < cv.w:
        c = np.zeros_like(m)
        c[yi, xi] = True
        put(cv, c, core, glow)


# A regular cape: the special capes' hand layout (outer face and lining side by side, a quarter turn each, the thin
# edges sampling the collar trim) at 16 texels per unit; tint for colourable capes; an optional collar laid out below.
CAPE_REG = (32, 10)
CAPE_REG_COLLAR = (32, 14)


def cape_bones_reg(outer, lining_fn, tint=False, collar=None):
    def p(face, cv):
        cv.hand = True
        (outer if face == "north" else lining_fn)(cv)
        if face == "south":
            # cut-outs in the outer face (a fringe, a swallow tail, lace) go through the lining too (seen from behind)
            ref = Canvas(np.zeros_like(cv.arr), np.zeros_like(cv.em), "north", cv.cube, False, cv.s)
            outer(ref)
            cut = ref.arr[:, ::-1, 3] < 0.5
            cv.arr[cut] = 0
            cv.em[cut] = 0
    faces = dict({"north": ([0, 0, 16, 10], 1, True), "south": ([16, 0, 32, 10], 1, True)}, **CAPE_EDGES)
    bones = [Bone("cape", "body", (0, 0, 2), cubes=[Cube((-5, 0, 1), (10, 16, 1), p, faces=faces)], anim={"type": "cape"},
                  tint=tint)]
    if collar is not None:
        def pc(face, cv):
            cv.hand = True
            collar(face, cv)
        cf = {"north": ([0, 10, 11, 12], 0, True), "south": ([11, 10, 22, 12], 0, True), "west": ([22, 10, 24, 12], 0, True),
              "east": ([24, 10, 26, 12], 0, True), "top": ([0, 12, 11, 14], 0, True), "bottom": ([11, 12, 22, 14], 0, True)}
        bones.append(Bone("collar", "body", (0, 0, 2), cubes=[Cube((-5.5, -1, 0.5), (11, 2, 2), pc, faces=cf)],
                          anim={"type": "cape"}, tint=tint))
    return bones


def cape_top(cv):
    return cv.s // 2 + 2            # rows the collar trim (hd_trim) covers


# ---- Ember Mantle: cooled crust split by glowing cracks that widen into a molten hem, embers rising off it

EMBER_CRACK = ["#5a140e", "#8e200c", "#c8380c", "#f06018", "#ff9a2a", "#ffd25a", "#fff3b0"]


def ember_px(cv):
    h, w, s = cv.h, cv.w, cv.s
    t = cv.yy()
    y, x = cv.grid()
    cv.arr[:] = bands(np.clip((t - 0.2) / 0.8, 0, 1), ["#141015", "#191216", "#201418", "#291618", "#341818"], 0.5)
    cv.arr[..., 3] = 1
    cv.arr[(x + y) % 6 == 0, :3] *= 0.84                                     # wool twill
    r = np.random.default_rng(31)
    P = np.stack([r.random(64) * w, h * (0.3 + r.random(64) * 0.78)], 1)
    d = np.sqrt((x[..., None] + 0.5 - P[:, 0]) ** 2 + ((y[..., None] + 0.5 - P[:, 1]) * 1.3) ** 2)
    d.sort(-1)
    gap = d[..., 1] - d[..., 0]                                              # 0 on the border between two plates
    heat = np.clip((t - 0.38) / 0.52, 0, 1)
    width = 0.7 + 4.2 * heat ** 1.4
    crack = (gap < width) & (t > 0.36)
    inten = np.clip(heat * 1.05 - gap / np.maximum(width, 1e-3) * 0.4 + 0.12, 0, 1)
    col = palette(levels(inten, len(EMBER_CRACK), 0.3), EMBER_CRACK)
    rim = (gap < width + 2.5) & ~crack & (heat > 0.3)                        # crust rims warmed by the cracks
    cv.arr[rim, :3] = hexc("#4c1a14")[:3]
    cv.arr[crack] = col[crack]
    glow = crack * np.clip(inten * 1.2 - 0.15, 0, 1)
    # the molten hem: a jagged pool along the bottom, hottest at the very edge
    xs = (np.arange(w) + 0.5) / w
    top = h * (0.865 + 0.022 * np.sin(xs * math.tau * 3 + 1.1) + 0.012 * np.sin(xs * math.tau * 7 + 0.3))
    depth = (y + 0.5 - top[None, :]) / (h - top[None, :])
    pool = depth > 0
    pc = bands(np.clip(depth * 1.25, 0, 1), ["#c8380c", "#f06018", "#ff9a2a", "#ffd25a", "#fff3b0"], 0.35)
    cv.arr[pool] = pc[pool]
    put(cv, (grow(pool) & ~pool), "#8e200c")
    glow = np.where(pool, 0.95, glow)
    em_rgb(cv, cv.arr, glow)
    # embers drifting up off the coals
    for i in range(26):
        ex, ey = r.random() * w, h * (0.16 + r.random() * 0.56)
        f = ey / h
        c = "#fff3b0" if f > 0.55 else "#ffc24a" if f > 0.35 else "#ff7a2a"
        big = r.random() < 0.35
        spark_dot(cv, ex, ey, 1.6 if big else 1.0, c, 1.0, halo="#a8300e" if big else None)
    hd_trim(cv, cape_top(cv), s // 3, "#2e1c14", "#6a4a2a", stitch="#e0a640")


# ---- Tidal Weave: overlapping rows of wave scallops, each ring capped with a line of foam

TIDE_ROWS = [("#06284c", "#0c4e80", "#1874a8", "#2f9ccc"), ("#05223f", "#0a3e6c", "#145f94", "#2482b8"),
             ("#041a34", "#08305a", "#0f4b7c", "#1a6a9e")]


def tidal_px(cv):
    h, w, s = cv.h, cv.w, cv.s
    y, x = cv.grid()
    X, Y = x + 0.5, y + 0.5
    R = 2.2 * s                                                   # big scallops: fewer foam lines, calmer at a glance
    cv.arr[:] = hexc("#041a34")
    rows = int(h / (R * 0.5)) + 2
    for j in range(rows):
        cy = cape_top(cv) + j * R * 0.5
        off = (j % 2) * R
        dx = ((X - off + R) % (2 * R)) - R
        d = np.sqrt(dx ** 2 + (Y - cy) ** 2)
        m = d < R
        if not m.any():
            continue
        deep, c1, c2, c3 = TIDE_ROWS[min(2, int(levels(np.array([[cy / h]]), 3, 0.0)[0, 0]))]
        f = d / R
        col = np.where((f > 0.66)[..., None], hexc(c3), np.where((f > 0.47)[..., None], hexc(c1),
                       np.where((f > 0.29)[..., None], hexc(c2), hexc(deep))))
        col = np.where((d > R - 3.0)[..., None], hexc("#8adcf2"), col)
        col = np.where((d > R - 1.5)[..., None], hexc("#eefcff"), col)
        cv.arr[m] = col[m]
    cv.arr[..., 3] = 1
    r = np.random.default_rng(7)
    for i in range(9):
        j = int(r.integers(1, rows - 2))
        k = int(r.integers(0, 5))
        cx = (j % 2) * R + k * 2 * R
        cy = cape_top(cv) + j * R * 0.5 - R + 1
        if cx < w - 3 and cy > cape_top(cv) + 3:
            px_star(cv, cx, cy, 3, "#bff4ff", glow=0.8)
    hd_trim(cv, cape_top(cv), s // 3, "#b89a5a", "#f2dca0")
    xr = np.arange(w)
    rope = ((xr[None, :] + np.arange(cape_top(cv))[:, None]) % 6) < 3
    cv.arr[:cape_top(cv)][rope, :3] *= 0.78                                  # a twisted rope collar


# ---- Starlit Veil: a banded night sky, a violet nebula, a crescent moon and crisp stars

def starlit_px(cv):
    h, w, s = cv.h, cv.w, cv.s
    t, u = cv.yy(), cv.xx()
    cv.arr[:] = bands(t, ["#080720", "#0d0a2e", "#130e3c", "#1a124a", "#231758", "#2e1b66", "#3a1f70"], 0.5)
    cv.arr[..., 3] = 1
    n, n2 = pnoise(h, w, 2.5, 3, octaves=4), pnoise(h, w, 4.0, 4, octaves=3)
    band = smooth(1 - np.abs(t - 0.58 - (u - 0.5) * 0.45 - (n - 0.5) * 0.3) / 0.24)
    neb = levels(np.clip(band * smooth((n2 - 0.28) / 0.5) * 1.1, 0, 1), 5, 0.35)
    col = palette(neb, ["#000000", "#3a2680", "#6a33a6", "#a04cc4", "#e09ae6"])
    cv.arr[neb > 0] = col[neb > 0]
    blue = (neb == 0) & (levels(np.clip(band * (1 - n2) * 1.4, 0, 1), 3, 0.35) > 0)
    cv.arr[blue, :3] = hexc("#24307a")[:3]
    em_rgb(cv, cv.arr, np.clip((neb - 2) / 3.0, 0, 1) * 0.55)
    twinkle_stars(cv, 21, 110, 0.0, big=0, cols=("#ffffff", "#cfd8ff", "#ffe9c2"))
    for sx, sy, rr in [(0.2, 0.3, 4), (0.42, 0.12, 3), (0.86, 0.48, 3), (0.12, 0.72, 4), (0.62, 0.82, 3), (0.34, 0.55, 3)]:
        px_star(cv, sx * w, sy * h, rr, "#cfe0ff")
    cx, cy, R = w * 0.7, h * 0.17, w * 0.14
    moon = disc(cv, cx, cy, R) & ~disc(cv, cx + R * 0.55, cy - R * 0.22, R * 0.92)
    sprite(cv, moon, "#fff1c2", hi="#ffffff", lo="#e8c886", glow=1.0, e=2)
    crater = moon & (disc(cv, cx - R * 0.55, cy + R * 0.1, R * 0.16) | disc(cv, cx - R * 0.2, cy + R * 0.55, R * 0.12))
    put(cv, crater, "#ecd49a", 1.0)
    hd_trim(cv, cape_top(cv), s // 3, "#a8862e", "#f4dc8a", stitch="#fff1c2")


# ---- Chevron Standard (colourable): light greys so the colour shows true; three raised chevrons and a stitched border

def chevron_px(cv):
    h, w, s = cv.h, cv.w, cv.s
    t = cv.yy()
    y, x = cv.grid()
    cv.arr[:] = bands(t, ["#f4f4f4", "#e8e8e8", "#dadada", "#cccccc"], 0.5)
    cv.arr[..., 3] = 1
    for k in range(3):
        y0 = h * (0.3 + k * 0.19)
        v = y0 - np.abs(x + 0.5 - w / 2) * 0.45
        m = (y + 0.5 > v) & (y + 0.5 < v + h * 0.075)
        sprite(cv, m, "#5c5c5c", line="#a8a8a8", hi="#8c8c8c", lo="#3c3c3c", e=2)
        inner = (np.abs(y + 0.5 - (v + h * 0.0375)) < 1.0) & m
        put(cv, inner, "#6e6e6e")
    dia = (np.abs(x + 0.5 - w / 2) + np.abs(y + 0.5 - h * 0.15)) < s * 0.7
    sprite(cv, dia, "#7a7a7a", line="#b4b4b4", hi="#a4a4a4", lo="#4a4a4a", e=2)
    st = ((x == s // 2) | (x == w - 1 - s // 2)) & (y > cape_top(cv) + 3) & (y < h - s // 2) & ((y // 4) % 2 == 0)
    put(cv, st, "#9a9a9a")
    hd_trim(cv, cape_top(cv), s // 3, "#8a8a8a", "#d4d4d4", stitch="#fafafa")


# ---- Checkered Rally: a rippling chequered flag with a red pole stripe

def checker_px(cv):
    h, w, s = cv.h, cv.w, cv.s
    t, u = cv.yy(), cv.xx()
    y, x = cv.grid()
    sq = 2 * s
    chk = (((x - s) // sq) + ((y - cape_top(cv)) // sq)) % 2 == 1
    li = levels(0.5 + 0.5 * np.sin(math.tau * (u * 1.4 + t * 0.9)), 3, 0.35)
    white = palette(li, ["#c4c6cc", "#e4e6ec", "#ffffff"])
    black = palette(li, ["#0a0a0e", "#16161c", "#24242e"])
    cv.arr[:] = np.where(chk[..., None], black, white)
    cv.arr[..., 3] = 1
    stripe = x < s
    sc = bands(t, ["#ff4a4a", "#e11d2e", "#c41626", "#8e0c18"], 0.5)
    cv.arr[stripe] = sc[stripe]
    put(cv, (x >= 3) & (x < 5), "#ff8a8a")
    put(cv, x == s - 2, "#6a0810")
    put(cv, x == s - 1, "#2a2a30")
    hd_trim(cv, cape_top(cv), s // 3, "#2a2a30", "#5a5a66", stitch="#e11d2e")


# ---- Highland Plaid (colourable): a woven tartan in greys (the twill shows which thread is on top), a fringed hem

def plaid_px(cv):
    h, w, s = cv.h, cv.w, cv.s
    t = cv.yy()
    y, x = cv.grid()

    def thread(v):
        m = v % (5 * s)
        g = np.full(v.shape, 0.86, np.float32)
        g = np.where(np.abs(m - 1.2 * s) < 0.9 * s, 0.5, g)                  # the wide dark band
        g = np.where(np.abs(m - 1.2 * s) < 0.12 * s, 0.38, g)                # its dark centre line
        g = np.where(np.abs(m - 3.6 * s) < 0.3 * s, 0.66, g)                 # a medium band
        g = np.where(np.abs(m - 3.6 * s) < 1.0, 1.0, g)                      # a thin light overstripe
        return g
    gx, gy = thread(x + 0.5), thread(y + 0.5 + 0.6 * s)
    g = np.where(((x + y) % 4) < 2, gx, gy)
    g = np.where((gx < 0.7) & (gy < 0.7), np.minimum(gx, gy) - 0.06, g)      # dark over dark: darker still
    cv.arr[..., :3] = g[..., None]
    cv.arr[..., 3] = 1
    cv.arr[..., :3] *= (1 - 0.05 * levels(t, 3, 0.5))[..., None]
    hd_trim(cv, cape_top(cv), 0, "#6a6a6a", "#bcbcbc", stitch="#f0f0f0")
    fr = s // 2 + 2
    hem = (y >= h - fr - 4) & (y < h - fr)
    put(cv, hem, "#7a7a7a")
    put(cv, hem & (y == h - fr - 4), "#a8a8a8")
    put(cv, hem & (y == h - fr - 2) & ((x // 3) % 2 == 0), "#e8e8e8")
    tail = y >= h - fr
    tassel = tail & ((x % 4) < 2)
    cv.arr[tail & ~tassel, 3] = 0
    put(cv, tassel, "#9a9a9a")
    put(cv, tassel & ((x % 4) == 0), "#b8b8b8")
    put(cv, tassel & (y >= h - 2), "#6e6e6e")


# ---- Blossom Drift: a spring dusk, a dark branch of five-petalled blossom, petals drifting to the hem

def blossom_px(cv):
    h, w, s = cv.h, cv.w, cv.s
    t = cv.yy()
    cv.arr[:] = bands(t, ["#ffe6d8", "#ffd6d2", "#fbc2cc", "#eeaac6", "#d496c2", "#b484be", "#8a6aa8"], 0.45)
    cv.arr[..., 3] = 1
    pts = [(-4, h * 0.12), (w * 0.25, h * 0.2), (w * 0.5, h * 0.17), (w * 0.78, h * 0.3), (w + 4, h * 0.27)]
    twigs = [(0.25, 0.2, 0.32, 0.34), (0.5, 0.17, 0.46, 0.06), (0.62, 0.22, 0.7, 0.42), (0.78, 0.3, 0.86, 0.4)]

    def branch(d):
        for i in range(len(pts) - 1):
            d.line([pts[i], pts[i + 1]], fill=255, width=7 - i)
        for x0, y0, x1, y1 in twigs:
            d.line([(x0 * w, y0 * h), (x1 * w, y1 * h)], fill=255, width=3)
    sprite(cv, pmask(cv, branch), "#5a3440", line="#2e1a22", hi="#8a5a62", lo="#3e2430")
    r = np.random.default_rng(4)
    blooms = [(0.32, 0.34), (0.46, 0.06), (0.7, 0.42), (0.86, 0.4), (0.18, 0.16), (0.58, 0.2), (0.38, 0.22),
              (0.9, 0.24), (0.1, 0.1), (0.66, 0.3)]
    for bx, by in blooms:
        cx, cy, R = bx * w, by * h, s * (0.85 + r.random() * 0.45)
        rot = r.random() * math.tau
        pet = np.zeros((h, w), bool)
        for p in range(5):
            a = rot + p * math.tau / 5
            pet |= disc(cv, cx + math.cos(a) * R * 0.6, cy + math.sin(a) * R * 0.6, R * 0.48)
        sprite(cv, pet, "#ffd8e6", line="#b0587a", hi="#fff4f8", lo="#f2a6c2", e=2)
        put(cv, disc(cv, cx, cy, R * 0.32), "#f490b4")
        put(cv, disc(cv, cx, cy, R * 0.17 + 0.6), "#ffe27a")
        put(cv, disc(cv, cx + 1, cy + 1, 1.2) & ~disc(cv, cx, cy, 1.0), "#d89a2a")
    for _ in range(26):
        px_, py_ = r.random() * w, (0.45 + r.random() * 0.48) * h
        sz = 2 + int(r.random() * 3)
        m = disc(cv, px_, py_, sz, ky=1.7 if r.random() < 0.5 else 1.0)
        sprite(cv, m, "#ffe0ec", lo="#f0a8c4")
    hd_trim(cv, cape_top(cv), s // 3, "#7a4a6a", "#c88aa8", stitch="#ffe3ec")


# ---- Pumpkin Night: a harvest moon, bats, dark hills and a carved pumpkin whose face glows

def pumpkin_px(cv):
    h, w, s = cv.h, cv.w, cv.s
    t = cv.yy()
    y, x = cv.grid()
    cv.arr[:] = bands(t / 0.85, ["#100820", "#180b30", "#221040", "#2e144c", "#3e1850", "#521c4e"], 0.45)
    cv.arr[..., 3] = 1
    twinkle_stars(cv, 9, 40, 0.0, big=0, cols=("#ffffff", "#ffe9c2"))
    mx, my, R = w * 0.5, h * 0.3, w * 0.3
    moon = disc(cv, mx, my, R)
    lit = ((x + 0.5 - mx) * -0.55 + (y + 0.5 - my) * -0.8) / R               # facing the light: upper left
    mc = bands(np.clip(0.5 + lit * 0.6, 0, 1), ["#d47a22", "#e8902e", "#f8a63c", "#ffbe56", "#ffd47a"], 0.35)
    cv.arr[moon] = mc[moon]
    for cx, cy, cr in [(0.42, 0.24, 0.05), (0.6, 0.33, 0.07), (0.47, 0.38, 0.035), (0.62, 0.2, 0.03), (0.36, 0.33, 0.03)]:
        c = disc(cv, cx * w, cy * h, cr * w) & moon
        put(cv, c, "#d8862a")
        put(cv, shade_rim(c, 1), "#ffc46a")
    em_rgb(cv, cv.arr, moon * 0.6)
    k = 1

    def bats(d):
        for bx, by, sc_ in [(0.25, 0.14, 1.0), (0.78, 0.2, 0.8), (0.62, 0.08, 0.65), (0.18, 0.4, 0.7)]:
            cx, cy, S = bx * w, by * h, s * 1.1 * sc_ * k
            d.polygon([(cx, cy + S * 0.15), (cx - S * 0.5, cy - S * 0.35), (cx - S * 1.2, cy - S * 0.15), (cx - S * 0.9, cy + S * 0.05),
                       (cx - S * 0.7, cy + S * 0.35), (cx - S * 0.35, cy + S * 0.15), (cx, cy + S * 0.45), (cx + S * 0.35, cy + S * 0.15),
                       (cx + S * 0.7, cy + S * 0.35), (cx + S * 0.9, cy + S * 0.05), (cx + S * 1.2, cy - S * 0.15), (cx + S * 0.5, cy - S * 0.35)],
                      fill=255)
    put(cv, pmask(cv, bats), "#0c0610")
    for bx, by, sc_ in [(0.25, 0.14, 1.0), (0.78, 0.2, 0.8)]:
        for e_ in (-1, 1):
            put(cv, disc(cv, bx * w + e_ * 2.5 * sc_, by * h + 1, 0.9), "#ff5a2a", 1.0)
    hill1 = disc(cv, w * 0.2, h * 1.01, w * 0.6, ky=w * 0.6 / (h * 0.29))
    hill2 = disc(cv, w * 0.92, h * 1.04, w * 0.58, ky=w * 0.58 / (h * 0.26))
    sprite(cv, hill1, "#140a18", hi="#2e1a3a")
    sprite(cv, hill2 & ~hill1, "#1c0e22", hi="#34203e")
    px_, py_, P = w * 0.5, h * 0.84, w * 0.2
    body = np.zeros((h, w), bool)
    for off, c in ((-0.55, "#c4540a"), (0.55, "#c4540a"), (-0.25, "#e66e14"), (0.25, "#e66e14"), (0, "#f5821e")):
        m = disc(cv, px_ + off * P, py_ - P * 0.035, P * 0.45, ky=0.45 / 0.585)
        body |= m
    put(cv, grow(body) & ~body, "#3a1406")
    for off, c in ((-0.55, "#c4540a"), (0.55, "#c4540a"), (-0.25, "#e66e14"), (0.25, "#e66e14"), (0, "#f5821e")):
        m = disc(cv, px_ + off * P, py_ - P * 0.035, P * 0.45, ky=0.45 / 0.585)
        put(cv, m, c)
        put(cv, shade_rim(m, 1), "#9a3a06")
    put(cv, lit_rim(body, 1), "#ffa04a")
    stem = (np.abs(x + 0.5 - px_) < P * 0.09) & (y + 0.5 > py_ - P * 0.85) & (y + 0.5 < py_ - P * 0.5)
    sprite(cv, stem, "#4a6a1e", line="#1a2208", hi="#7a9a3a")

    def carve(d):
        for ex in (-0.32, 0.32):
            d.polygon([(px_ + ex * P - P * 0.15, py_ - P * 0.04), (px_ + ex * P + P * 0.15, py_ - P * 0.04), (px_ + ex * P, py_ - P * 0.32)], fill=255)
        d.polygon([(px_ - P * 0.46, py_ + P * 0.1), (px_ + P * 0.46, py_ + P * 0.1), (px_ + P * 0.3, py_ + P * 0.34), (px_ + P * 0.14, py_ + P * 0.22),
                   (px_, py_ + P * 0.36), (px_ - P * 0.14, py_ + P * 0.22), (px_ - P * 0.3, py_ + P * 0.34)], fill=255)
    face = pmask(cv, carve)
    sprite(cv, face, "#ffe27a", line="#6a2406", hi="#fff6c0", lo="#ffb030", glow=1.0)
    hd_trim(cv, cape_top(cv), s // 3, "#e07a1a", "#ffb050", stitch="#2a1236")


# ---- Frostfall Cloak: icy blue, frost creeping up from the hem, outlined snowflakes, a white fur collar

def frost_px(cv):
    h, w, s = cv.h, cv.w, cv.s
    t = cv.yy()
    y, x = cv.grid()
    cv.arr[:] = bands(t, ["#3080b4", "#2c74a8", "#27679a", "#21598a", "#1b4a78", "#153c66", "#0f2e54"], 0.45)
    cv.arr[..., 3] = 1
    n = pnoise(h, w, 7.0, 101, octaves=2, aspect=0.6)                      # tall streaks: frost feathers climbing up
    fl = levels(np.clip((t + (n - 0.5) * 0.42 - 0.7) / 0.26, 0, 1), 4, 0.25)
    for k_, c in ((1, "#4a8cc0"), (2, "#9ccbec"), (3, "#e4f4ff")):
        cv.arr[fl == k_, :3] = hexc(c)[:3]
    put(cv, (fl == 1) & lit_rim(fl >= 1, 1), "#6aa6d4")
    r = np.random.default_rng(12)
    flakes = []
    for i in range(9):
        cx, cy = r.random() * w, (0.1 + r.random() * 0.62) * h
        R = s * (0.7 + r.random() * 0.9)
        flakes.append((cx, cy, R))
    m = pmask(cv, lambda d: [snowflake(d, cx, cy, R, 1, 255, 2.0 if R > s * 1.1 else 1.0) for cx, cy, R in flakes])
    sprite(cv, m, "#f0faff", line="#16406e", glow=0.35)
    for _ in range(40):
        px_, py_ = int(r.random() * w), int((0.08 + r.random() * 0.7) * h)
        put(cv, (x == px_) & (y == py_), "#dff2ff", 0.4)
    hd_trim(cv, cape_top(cv), s // 3, "#a8bccc", "#ffffff")


def fur_px(face, cv):
    y, x = cv.grid()
    r = np.random.default_rng(107 + cv.w + cv.h)
    a, b = r.random(cv.w // 2 + 2), r.integers(0, 6, cv.w // 2 + 2)
    col = (x // 2).astype(int)
    v = a[col] * 0.65 + (((y + b[col]) % 6) < 2) * 0.35
    cv.arr[:] = palette(levels(v, 4, 0.2), ["#c4d0e0", "#dce6f2", "#f2f6fc", "#ffffff"])
    cv.arr[..., 3] = 1
    if face in ("north", "south", "east", "west"):
        put(cv, y >= cv.h - max(1, cv.s // 8), "#a8b8cc")
        put(cv, y < max(1, cv.s // 8), "#ffffff")


# ---- Dragonhide Cape: rows of raised emerald scales rimmed in gold, deepening to teal at the hem

DRAGON_SCALE = ["#06201c", "#0a2e28", "#0e4036", "#145644", "#1c7054", "#2a8e66", "#4cb47e", "#86dca8"]


def dragonhide_px(cv):
    h, w, s = cv.h, cv.w, cv.s
    t = cv.yy()
    hgt, edge, sid = scales_height(cv, 1.6)
    v = (np.sin(sid * 12.9898) * 43758.5453) % 1
    gy, gx = np.gradient(hgt.astype(np.float32))
    d = gx * 0.6 + gy * 0.8
    tone = levels(np.clip(hgt, 0, 1) ** 0.8, 4, 0.3) + (d < -0.04) * 0 + (d > 0.03) - (d < -0.03)
    idx = np.clip(tone + (v > 0.6) - (t > 0.55) - (t > 0.8) + 2, 0, len(DRAGON_SCALE) - 1)
    cv.arr[:] = palette(idx, DRAGON_SCALE)
    cv.arr[..., 3] = 1
    cv.arr[edge, :3] = hexc("#e2bc52")[:3]
    cv.arr[edge & (d < 0), :3] = hexc("#8a6a1e")[:3]
    cv.arr[edge & (d > 0.04), :3] = hexc("#fff0a0")[:3]
    hd_trim(cv, cape_top(cv), s // 3, "#b88a26", "#ffe9a6", stitch="#fff4c8")


# ---- Neon Horizon: a striped sun setting behind mountains over a glowing perspective grid

def horizon_px(cv):
    h, w, s = cv.h, cv.w, cv.s
    t, u = cv.yy(), cv.xx()
    y, x = cv.grid()
    hz = 0.56 * h
    cv.arr[:] = bands(np.clip(t / 0.56, 0, 1), ["#120628", "#1a0838", "#260a4a", "#3a0c5a", "#5a1268", "#821c72", "#a82a7a"], 0.45)
    cv.arr[..., 3] = 1
    twinkle_stars(cv, 33, 46, 0.0, big=0, avoid=lambda a, b: b > 0.36)
    cx, cy, R = w * 0.5, hz, w * 0.34
    rel = (y + 0.5 - (cy - R)) / R
    disc_m = disc(cv, cx, cy, R) & (y + 0.5 < cy)
    stripes = (y / h < 0.38) | (np.sin((y / h - 0.38) * 90) > -0.2 + (y / h - 0.38) * 3)
    sun = disc_m & stripes
    sc = bands(rel, ["#fff27a", "#ffd84a", "#ffb04a", "#ff8a5a", "#ff5a7a", "#ff3a8a"], 0.3)
    cv.arr[sun] = sc[sun]
    em_rgb(cv, cv.arr, sun * 0.9)
    ridge = 0.56 - 0.06 * np.abs(np.sin(u * 7.0)) * (np.abs(u - 0.5) > 0.12) - 0.03 * np.abs(np.sin(u * 19))
    mt = (t > ridge) & (y + 0.5 < hz)
    put(cv, mt, "#1a0830")
    top_edge = mt & ~nshift(mt, 1, 0)
    put(cv, top_edge, "#ff3ad2", 0.7)
    floor = y + 0.5 >= hz
    depth = np.clip((y + 0.5 - hz) / (h - hz), 0.0, 1)
    put(cv, floor, "#0a0316")
    zr = 1.0 / np.maximum(depth, 0.02)
    hl = floor & (np.floor(zr) != np.floor(nshift(zr, 1, 0) + (y == 0) * 999)) & (depth > 0.02)
    vx = ((x + 0.5) / w - 0.5) / np.maximum(depth, 0.02) * 3
    vl = floor & (np.floor(vx) != np.floor(nshift(vx, 0, 1))) & (x > 0) & (depth > 0.04)
    gm = hl | vl
    gc = bands(depth, ["#ff3ad2", "#d84af0", "#9a6aff", "#3af0ff"], 0.3)
    cv.arr[gm] = gc[gm]
    em_rgb(cv, cv.arr, gm * 0.8)
    put(cv, floor & (y == int(hz)), "#ff7ae6", 0.9)
    hd_trim(cv, cape_top(cv), s // 3, "#c42aa8", "#ff9aee", stitch="#3af0ff")


# ---- Oakheart Banner: a golden shield with a great oak on forest green, a swallow-tail hem

def oak_px(cv):
    h, w, s = cv.h, cv.w, cv.s
    t = cv.yy()
    y, x = cv.grid()
    cv.arr[:] = bands(t, ["#2e6e3c", "#29643a", "#245834", "#1e4c2e", "#184028", "#133420"], 0.5)
    cv.arr[..., 3] = 1
    put(cv, ((x + 0.5) % (2.5 * s) < 1.0) & (y > cape_top(cv)), "#3a8048")
    W, H = w, h
    sx0, sx1, sy0, sy1 = W * 0.2, W * 0.8, H * 0.18, H * 0.72
    shield = [(sx0, sy0), (sx1, sy0), (sx1, sy0 + (sy1 - sy0) * 0.55), (W * 0.5, sy1), (sx0, sy0 + (sy1 - sy0) * 0.55)]
    g = s * 0.45
    inset = [(sx0 + g, sy0 + g), (sx1 - g, sy0 + g), (sx1 - g, sy0 + (sy1 - sy0) * 0.55), (W * 0.5, sy1 - g * 1.5),
             (sx0 + g, sy0 + (sy1 - sy0) * 0.55)]
    sm = pmask(cv, lambda d: d.polygon(shield, fill=255))
    sprite(cv, sm, "#dab04a", line="#2a1e08", hi="#fff0a0", lo="#8a6a1e", e=2)
    im = pmask(cv, lambda d: d.polygon(inset, fill=255))
    put(cv, im, "#ece4cc")
    put(cv, im & (y + 0.5 > sy0 + (sy1 - sy0) * 0.62), "#ddd2b2")
    put(cv, grow(im) & ~im & sm, "#a8822e")
    tx, ty, U = W * 0.5, H * 0.58, s / 5
    trunk = pmask(cv, lambda d: (d.rectangle((tx - 2 * U, H * 0.42, tx + 2 * U, ty), fill=255),
                                 d.polygon([(tx - 5 * U, ty), (tx + 5 * U, ty), (tx + 2 * U, ty - 3 * U), (tx - 2 * U, ty - 3 * U)], fill=255)))
    sprite(cv, trunk, "#6e4624", line="#2a1a0c", hi="#9a6a3a", lo="#4a2e16")
    can = np.zeros((h, w), bool)
    for cx, cy, R in [(0.5, 0.33, 10), (0.36, 0.38, 7.5), (0.64, 0.38, 7.5), (0.4, 0.29, 7.5), (0.6, 0.29, 7.5), (0.5, 0.24, 7.5),
                      (0.5, 0.41, 7)]:
        can |= disc(cv, W * cx, H * cy, R * U)
    sprite(cv, can, "#2e7a3c", line="#123018", lo="#1e5a2c", e=2)
    for cx, cy, R in [(0.44, 0.27, 3.6), (0.58, 0.31, 3.0), (0.4, 0.36, 2.8), (0.53, 0.22, 2.6), (0.62, 0.38, 2.4)]:
        m = disc(cv, W * cx, H * cy, R * U) & can
        sprite(cv, m, "#5aa452", hi="#8acc6c")
    hd_trim(cv, cape_top(cv), 0, "#c8982e", "#fff0c0", stitch="#fff0c0")
    notch_y = h - s * 2.2
    notch = (y > notch_y) & (np.abs(x + 0.5 - w / 2) < (y - notch_y) * 0.9)
    hem = (y >= h - s // 3 - 1)
    put(cv, hem, "#c8982e")
    put(cv, hem & (y == h - s // 3 - 1), "#fff0c0")
    put(cv, grow(notch) & ~notch, "#d4a84a")
    cv.arr[notch, 3] = 0
    cv.arr[notch, :3] = 0


# ---- Sweetheart Cape: rose fading to crimson, scattered outlined hearts, a lace-scalloped hem

def sweet_px(cv):
    h, w, s = cv.h, cv.w, cv.s
    t = cv.yy()
    y, x = cv.grid()
    cv.arr[:] = bands(t, ["#ffb0c8", "#ff98b6", "#fa7ea2", "#ee628a", "#dc4672", "#c02c5a", "#a01840"], 0.45)
    cv.arr[..., 3] = 1
    r = np.random.default_rng(23)
    styles = [("#fff0f4", "#c83a62", "#ffffff"), ("#ff4a74", "#8e1032", "#ff8aa6"), ("#c8143c", "#5a0418", "#ff4a6a")]
    for i in range(16):
        cx, cy = r.random() * w, (0.08 + r.random() * 0.76) * h
        R = s * (0.5 + r.random() * 0.8)
        rot = (r.random() - 0.5) * 0.6
        if abs(cx - w / 2) < s * 2.8 and abs(cy - h * 0.42) < s * 2.8:
            continue
        m = pmask(cv, lambda d: d.polygon(heart_poly(cx, cy, R, 1, rot), fill=255))
        f, ln, hi = styles[i % 3]
        sprite(cv, m, f, line=ln, hi=hi)
    big = pmask(cv, lambda d: d.polygon(heart_poly(w * 0.5, h * 0.42, s * 2.6, 1), fill=255))
    inner = pmask(cv, lambda d: d.polygon(heart_poly(w * 0.5, h * 0.42, s * 2.05, 1), fill=255))
    sprite(cv, big, "#fff6f8", line="#8e1032", lo="#f2c6d2")
    sprite(cv, inner, "#e81e4a", line="#a01236", hi="#ff6a8a", lo="#b8123a", e=2)
    put(cv, disc(cv, w * 0.5 - s * 0.9, h * 0.42 - s * 0.85, s * 0.32, ky=0.8), "#ffd0dc")
    sc = s * 1.2
    hem = h - s * 1.2
    circle = ((x % sc) + 0.5 - sc / 2) ** 2 + (y + 0.5 - hem) ** 2 < (sc / 2) ** 2
    lace = (y >= hem - s * 0.4) & ((y < hem) | circle)
    put(cv, lace, "#fff4f8")
    put(cv, lace & (((x + y) % 3) == 0) & (y > hem - s * 0.4 + 1), "#f6c2d4")
    put(cv, grow(lace) & ~lace & (y < hem), "#c02c5a")
    cv.arr[(y >= hem) & ~circle, 3] = 0
    hd_trim(cv, cape_top(cv), 0, "#c82a5a", "#ff9ab8", stitch="#ffffff")


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


item("ember_mantle", "Ember Mantle", "CAPE", "REGULAR", 0,
     "A charcoal cape that smoulders into molten orange at the hem, with embers that glow in the dark.",
     cape_bones_reg(ember_px, lining_hd("#2a1412", "#120707", "#5a2a1a")), layout=CAPE_REG, scale_cap=16, view=CAPE_VIEW)

item("hearthfire_cape", "Hearthfire Cape", "CAPE", "SPECIAL", 0,
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


item("tidal_weave", "Tidal Weave", "CAPE", "REGULAR", 0,
     "Deep-sea blue with rolling waves of foam and a sand-gold rope collar.",
     cape_bones_reg(tidal_px, lining_hd("#0d2a40", "#061624", "#3a7a9a")), layout=CAPE_REG, scale_cap=16, view=CAPE_VIEW)

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


item("riptide_cape", "Riptide Cape", "CAPE", "SPECIAL", 0,
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


item("starlit_veil", "Starlit Veil", "CAPE", "REGULAR", 0,
     "A night sky with a violet nebula, a crescent moon and stars that shine in the dark.",
     cape_bones_reg(starlit_px, lining_hd("#1a1440", "#0b0a1e", "#5a4aa0")), layout=CAPE_REG, scale_cap=16, view=CAPE_VIEW)


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


item("galaxy_flow_cape", "Galaxy Flow Cape", "CAPE", "SPECIAL", 0,
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


item("polar_lights_cape", "Polar Lights Cape", "CAPE", "SPECIAL", 0,
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


item("chevron_standard", "Chevron Standard", "CAPE", "REGULAR", 0,
     "A clean banner cape with three raised chevrons and a stitched hem, in any colour you like.",
     cape_bones_reg(chevron_px, lining_hd("#a4a4a4", "#7c7c7c", "#c8c8c8"), tint=True), layout=CAPE_REG, scale_cap=16,
     colourable=True, default_color="#E11D2E",
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


item("checkered_rally", "Checkered Rally", "CAPE", "REGULAR", 0,
     "A rippling black-and-white chequered flag with a red stripe, for whoever crosses the line first.",
     cape_bones_reg(checker_px, lining_hd("#2a2a30", "#141418", "#e11d2e")), layout=CAPE_REG, scale_cap=16, view=CAPE_VIEW)


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


item("highland_plaid", "Highland Plaid", "CAPE", "REGULAR", 0,
     "A woven tartan cape with a fringed hem, in any colour you like.",
     cape_bones_reg(plaid_px, lining_hd("#9a9a9a", "#747474", "#c4c4c4"), tint=True), layout=CAPE_REG, scale_cap=16,
     colourable=True, default_color="#2E7D4F",
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


item("blossom_drift", "Blossom Drift", "CAPE", "REGULAR", 0,
     "A spring dusk with a branch of pink blossom and petals drifting down to the hem.",
     cape_bones_reg(blossom_px, lining_hd("#f3d2dc", "#b48aa8", "#ffffff")), layout=CAPE_REG, scale_cap=16, view=CAPE_VIEW)


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


item("pumpkin_night", "Pumpkin Night", "CAPE", "REGULAR", 0,
     "A harvest moon, a flight of bats and a carved pumpkin whose face glows in the dark.",
     cape_bones_reg(pumpkin_px, lining_hd("#2a1236", "#120818", "#e07a1a")), layout=CAPE_REG, scale_cap=16, view=CAPE_VIEW)


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


item("frostfall_cloak", "Frostfall Cloak", "CAPE", "REGULAR", 0,
     "An icy blue cloak scattered with snowflakes, frost creeping up from the hem and a thick white fur collar.",
     cape_bones_reg(frost_px, lining_hd("#cfe0ee", "#93aec6", "#ffffff"), collar=fur_px), layout=CAPE_REG_COLLAR, scale_cap=16,
     view=CAPE_VIEW)


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


item("dragonhide_cape", "Dragonhide Cape", "CAPE", "REGULAR", 0,
     "Rows of raised emerald scales edged in gold, shading down to deep teal.",
     cape_bones_reg(dragonhide_px, lining_hd("#2a3a2a", "#121a14", "#6a8a4a")), layout=CAPE_REG, scale_cap=16, view=CAPE_VIEW)


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


item("neon_horizon", "Neon Horizon", "CAPE", "REGULAR", 0,
     "A striped sunset over a glowing neon grid, straight out of a retro arcade.",
     cape_bones_reg(horizon_px, lining_hd("#1a0830", "#05020c", "#ff3ad2")), layout=CAPE_REG, scale_cap=16, view=CAPE_VIEW)


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


item("oakheart_banner", "Oakheart Banner", "CAPE", "REGULAR", 0,
     "A forest-green banner with a golden shield and a great oak, finished with a swallow-tail hem.",
     cape_bones_reg(oak_px, lining_hd("#1e4a2a", "#0e2414", "#d4a84a")), layout=CAPE_REG, scale_cap=16, view=CAPE_VIEW)


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


item("sweetheart_cape", "Sweetheart Cape", "CAPE", "REGULAR", 0,
     "Rose-pink fading to crimson, scattered with hearts and finished with a lace-scalloped hem.",
     cape_bones_reg(sweet_px, lining_hd("#ffd2de", "#d886a0", "#ffffff")), layout=CAPE_REG, scale_cap=16, view=CAPE_VIEW)



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


# -------- arched wings: one skeleton for the feathered, crystal and mechanical wings, and one for the dragons
# An arm rises from the shoulder blade (kept low until it is clear of the head) to a wrist well above the shoulder; the
# primaries fan out and down from the hand, the secondaries hang from the forearm, and rows of coverts overlap their roots
# like roof tiles, so the wing reads as one arched shape with layers, not a row of strips. The flight feathers and the
# coverts are two planes a little apart (depth), the arm is a real bone on child bones in front of them. Everything is
# hand-drawn pixel art in wing coordinates (u: units out from the wing root at x = -1.5; y: the model's own), the same
# picture on both faces: flat colour bands, a dark outline round every feather so each layer reads over the one below, a
# lit leading vane and a shaft.

AW_PIVOT = (-1.5, 2.0, 2.5)                       # the wing bone's pivot on the shoulder blade (as every wing has)
AW_ARM = [(0.4, 1.4), (4.4, -0.6), (10.4, -8.2), (15.2, -6.6)]     # shoulder, elbow, wrist, hand tip (u, y)
AW_TRAIL = [(0.6, 6.0), (2.6, 11.2), (6.0, 14.6), (10.0, 15.6), (14.0, 14.2), (17.6, 10.6), (20.0, 5.4), (21.0, -0.4),
            (20.6, -4.0)]                         # flight feather tips, root to wing tip (u, y)
AW_SPAN, AW_TOP, AW_H = 21, -10, 26               # the flight plane: u 0..21, y -10..16
AW_COV_H = 16                                     # the coverts plane: y -10..6
AW_Z = 2.9                                        # the flight plane (behind the back); coverts 0.45 further out
AW_VIEW = {"yaw": 200, "pitch": 10, "wing_open": -14, "margin": 1.03}


def aw_curve(pts, a):
    """The point a (0..1) of the way along a polyline, by length."""
    seg = [math.hypot(b[0] - p[0], b[1] - p[1]) for p, b in zip(pts, pts[1:])]
    d = a * sum(seg)
    for p, b, L in zip(pts, pts[1:], seg):
        if d <= L:
            f = 0 if L == 0 else d / L
            return (p[0] + (b[0] - p[0]) * f, p[1] + (b[1] - p[1]) * f)
        d -= L
    return pts[-1]


def aw_layout(n_flight=17, rows=((0.52, 14), (0.34, 13), (0.2, 11)), arm=AW_ARM, trail=AW_TRAIL):
    """Feathers as (base, tip, width) in wing units. Flight feathers spread evenly from the root to the hand tip, their
    tips evenly along the trailing edge; each covert row (fraction of the flight feather's length, count) sits over them."""
    flight = []
    for i in range(n_flight):
        a = (i + 0.5) / n_flight
        b, t = aw_curve(arm, a), aw_curve(trail, a)
        flight.append((b, t, 2.3 + 0.5 * math.sin(math.pi * a)))
    covs = []
    for frac, n in rows:
        row = []
        for i in range(n):
            a = (i + 0.5) / n
            b, t = aw_curve(arm, a), aw_curve(trail, a)
            tip = (b[0] + (t[0] - b[0]) * frac, b[1] + (t[1] - b[1]) * frac)
            L = math.hypot(tip[0] - b[0], tip[1] - b[1])
            row.append((b, tip, min(2.9, max(1.7, L * 0.42))))
        covs.append(row)
    return flight, covs


def aw_poly(b, t, w, shape="feather", n=12):
    """A feather's outline from base b to tip t: 'feather' a blade with a rounded point (the leading vane narrower),
    'shard' a faceted crystal, 'blade' a metal plate with a clipped tip, 'scale' a short rounded covert."""
    dx, dy = t[0] - b[0], t[1] - b[1]
    L = max(1e-3, math.hypot(dx, dy))
    ux, uy = dx / L, dy / L
    nx, ny = -uy, ux
    if shape == "shard":
        prof = [(0, 0.35), (0.12, 1.0), (0.78, 0.9), (1.0, 0.0)]
    elif shape == "blade":
        prof = [(0, 0.5), (0.1, 1.0), (0.82, 1.0), (1.0, 0.25)]
    else:
        prof = []
        for i in range(n + 1):
            s = i / n
            k = 1.0 if shape == "scale" else min(1.0, 0.45 + s / 0.4 * 0.55)
            prof.append((s, k * math.sqrt(max(0.0, min(1.0, (1 - s) / (0.45 if shape == "scale" else 0.3))))))
    left = [(b[0] + dx * s + nx * w / 2 * k * 0.8, b[1] + dy * s + ny * w / 2 * k * 0.8) for s, k in prof]
    right = [(b[0] + dx * s - nx * w / 2 * k, b[1] + dy * s - ny * w / 2 * k) for s, k in prof]
    return left + right[::-1]


def crisp_mask(W):
    return (W.done()[..., 3] > 0.5).astype(np.float32)


class AwFace:
    """A wing plane's face in wing units: south (seen from behind) has u growing to the right; north mirrors it."""

    def __init__(self, cv, top):
        self.cv, self.top, self.s = cv, top, cv.s
        self.north = cv.face == "north"
        y, x = cv.grid()
        xu = (x + 0.5) / cv.s
        self.U = (cv.w / cv.s - xu) if self.north else xu
        self.Y = top + (y + 0.5) / cv.s

    def P(self, u, y):
        x = (self.cv.w / self.s - u) if self.north else u
        return (x * self.s, (y - self.top) * self.s)

    def mask(self, pts):
        return pmask(self.cv, lambda d: d.polygon([self.P(u, y) for u, y in pts], fill=255))

    def line(self, pts, width):
        return pmask(self.cv, lambda d: d.line([self.P(u, y) for u, y in pts], fill=255, width=max(1, int(round(width * self.s))),
                                               joint="curve"))

    def frame(self, b, t):
        """Per texel: position along the feather (0 base .. 1 tip) and signed distance from its shaft (units)."""
        dx, dy = t[0] - b[0], t[1] - b[1]
        L = max(1e-3, math.hypot(dx, dy))
        along = ((self.U - b[0]) * dx + (self.Y - b[1]) * dy) / (L * L)
        side = ((self.U - b[0]) * -dy + (self.Y - b[1]) * dx) / L
        return along, side


def aw_paint_feathers(F, feathers, st, shape="feather", alpha=1.0, glow_tip=None, where=None):
    """Draws feathers back to front: an outline under each (so it shows over the ones below), flat bands from root to
    tip (darker on the trailing vane), a shaft, a lit rim; `where(a, along, side)` can colour them by place instead."""
    cv = F.cv
    line, cols, shaft = st["line"], st["cols"], st.get("shaft")
    n = len(feathers)
    for i, (b, t, w) in enumerate(feathers):
        m = F.mask(aw_poly(b, t, w, shape))
        if not m.any():
            continue
        ring = grow(m, st.get("edge", 1)) & ~m
        put(cv, ring, line)
        cv.arr[ring, 3] = max(alpha, 0.9)
        cv.em[ring] = 0
        if st.get("line_glow"):
            em_from(cv, ring * st["line_glow"], line)
        along, side = F.frame(b, t)
        if where is not None:
            col = where(i / max(1, n - 1), np.clip(along, 0, 1), side)
        else:
            col = bands(np.clip(along, 0, 1), cols, 0.45)
        dark = side < -0.05
        col = col.copy()
        col[dark, :3] *= st.get("vane", 0.84)
        if shape == "shard":
            col[~dark, :3] = np.clip(col[~dark, :3] * 1.1 + 0.04, 0, 1)
        cv.arr[m] = col[m]
        cv.arr[m, 3] = alpha
        if shaft is not None:
            sm = m & (np.abs(side) < 0.75 / F.s) & (along < 0.88) & (along > 0.04)
            put(cv, sm, shaft)
            cv.arr[sm, 3] = max(alpha, 0.9)
            if st.get("shaft_glow"):
                em_from(cv, sm * st["shaft_glow"], shaft)
        if st.get("hi") is not None:
            rim = lit_rim(m, 1) & (along > 0.08)
            put(cv, rim, st["hi"])
            cv.arr[rim, 3] = max(alpha, 0.9)
        if glow_tip is not None:
            em_from(cv, (m & (along > glow_tip[1])) * glow_tip[2], glow_tip[0])


def aw_arm_band(F, st, width=1.0):
    """The leading edge drawn into the plane: a thick band along the arm (lit along its top) with an outline."""
    cv = F.cv
    m = F.line(AW_ARM, width)
    put(cv, grow(m, 1) & ~m, st["line"])
    put(cv, m, st["arm"][1])
    put(cv, lit_rim(m, 1), st["arm"][0])
    put(cv, shade_rim(m, 1), st["arm"][2])


def aw_plane(top, painter):
    """A wing plane's painter (both big faces; the edges stay empty): painter(AwFace) draws it as pixel art."""
    def p(face, cv):
        cv.hand = True
        cv.arr[:] = 0
        if face in ("north", "south"):
            painter(AwFace(cv, top))
    return p


def aw_rod(cols, line, glow=0.0):
    """A bone or spar of the arm: lit top, shaded underside, an outline round every face."""
    def p(face, cv):
        cv.hand = True
        t = cv.yy() if face not in ("top", "bottom") else np.zeros((cv.h, cv.w), np.float32) + (0.0 if face == "top" else 1.0)
        cv.arr[:] = bands(t * 0.999, cols, 0.3)
        cv.arr[..., 3] = 1
        y, x = cv.grid()
        e = max(1, cv.s // 8)
        if face in ("west", "east"):
            edge = (y < e) | (y >= cv.h - e) | (x < e) | (x >= cv.w - e)
        else:
            edge = (y < e) | (y >= cv.h - e)
        cv.arr[edge, :3] = hexc(line)[:3]
        if glow:
            em_rgb(cv, cv.arr, (~edge) * glow)
    return p


def aw_arm_bones(root, z, paint, segs=AW_ARM[:3], thick=1, prefix="arm"):
    """Child bones of the wing, one per arm segment, each a rod turned (roll) to run from one joint to the next."""
    out = []
    px0 = AW_PIVOT[0]
    for k, ((u0, y0), (u1, y1)) in enumerate(zip(segs, segs[1:])):
        x0, x1 = px0 - u0, px0 - u1
        n = int(round(math.hypot(x1 - x0, y1 - y0) + 0.6))
        roll = math.degrees(math.atan2(-(y1 - y0), -(x1 - x0)))
        out.append(Bone(f"{prefix}{k}_r", "body", (x0, y0, z), (0, 0, roll),
                        [Cube((x0 - n + 0.3, y0 - thick / 2, z - thick / 2), (n, thick, thick), paint)], parent=root))
    return out


def aw_pair(bones):
    """Right bones plus their mirrored left copies (child bones follow their own side's parent)."""
    left = []
    for b in bones:
        mb = mirrored_bone(b, b.name.replace("_r", "_l"), dict(b.anim, side=-1) if b.anim else None)
        if b.parent:
            mb.parent = b.parent.replace("_r", "_l")
        left.append(mb)
    return list(bones) + left


def aw_wing(name, flight_painter, cov_painter, rod, anim, extra=(), alpha=1.0):
    """A pair of arched wings: the flight plane, the coverts plane (optional), extra cubes on the wing bone, the arm."""
    root = f"{name}_r"
    cubes = [Cube((AW_PIVOT[0] - AW_SPAN, AW_TOP, AW_Z), (AW_SPAN, AW_H, 0), aw_plane(AW_TOP, flight_painter))]
    if cov_painter is not None:
        cubes.append(Cube((AW_PIVOT[0] - AW_SPAN, AW_TOP, AW_Z + 0.45), (AW_SPAN, AW_COV_H, 0), aw_plane(AW_TOP, cov_painter)))
    cubes += list(extra)
    bones = [Bone(root, "body", AW_PIVOT, (0, 0, 0), cubes, anim=anim, alpha=alpha)]
    bones += aw_arm_bones(root, AW_Z + 1.25, rod)
    return aw_pair(bones)


def aw_bird(st, shape="feather", alpha=1.0, glow_tip=None, where=None):
    """(flight painter, coverts painter) for a feathered wing in style `st`: line, cols (root to tip), cov (one list of
    colours per covert row), arm (light, mid, dark), optional shaft, hi (rim light), vane (trailing vane shade), n."""
    flight, covs = aw_layout(st.get("n", 17), st.get("rows", ((0.52, 14), (0.34, 13), (0.2, 11))))
    cov_shape = {"shard": "shard", "blade": "blade"}.get(shape, "scale")

    def fl(F):
        aw_paint_feathers(F, flight[::-1], st, shape, alpha, glow_tip, where)

    def co(F):
        for k, row in enumerate(covs):
            aw_paint_feathers(F, row[::-1], dict(st, cols=st["cov"][k]), cov_shape, min(1.0, alpha + 0.12))
        aw_arm_band(F, st, st.get("arm_w", 1.1))
    return fl, co


# -------- Aurora: see-through light-feathers, cyan at the shoulder to violet at the tips, every feather edged and veined
# in glowing light, on a crystal spar

AURORA_BANDS = ["#46f5d6", "#3fd8e6", "#4ab4f6", "#5a8cff", "#7366ff", "#8a52ff"]


def aurora_colour(a, along, side):
    return bands(np.clip(a * 0.8 + along * 0.3, 0, 1), AURORA_BANDS, 0.4)


AURORA = {"line": "#c9fff4", "line_glow": 0.8, "cols": AURORA_BANDS, "shaft": "#e6fffb", "shaft_glow": 0.9,
          "cov": [["#5af0dc", "#46c8ea"], ["#62f4e0", "#50d4ee"], ["#7af8e8", "#5ae0f0"]],
          "arm": ["#f4fffd", "#9ff5e6", "#3fb8c8"], "vane": 0.86}
CRYSTAL_ROD = (["#f4fffd", "#9ff5e6", "#3fb8c8"], "#1a6a7a")

item("aurora_wings", "Aurora Wings", "WINGS", "REGULAR", 1100,
     "See-through wings that fade from cyan to violet, with glowing veins, a crystal spar and a slow, easy beat.",
     aw_wing("wing", *aw_bird(AURORA, alpha=0.72, where=aurora_colour), aw_rod(*CRYSTAL_ROD, glow=0.35),
             wing_anim(32, 12, 1.6)), view=AW_VIEW)


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


# -------- Pulse Wings (special): energy feathers banded violet to cyan; packets of light race out along every shaft from
# the shoulder to the tips, lighting each feather's edge as they pass

PULSE_FEATHER = ["#2a1066", "#341a8c", "#2c34b0", "#2058c8", "#1c84d8", "#28b4e8", "#5ae0f4"]
PULSE_LIGHT = ["#5ac8f0", "#9aeaff", "#dcfcff", "#ffffff"]


def pulse_feathers(F):
    """One plane (the animation's strip has no room for a second): the flight feathers and a row of coverts over them.
    Three pulses per loop race out along every shaft; a feather's edge and shaft light as one passes."""
    cv, ph = F.cv, F.cv.phase
    flight, covs = aw_layout(15, rows=((0.42, 12),))
    lit = np.zeros((cv.h, cv.w), np.float32)
    for row, shape, reach in ((flight[::-1], "feather", 1.0), (covs[0][::-1], "scale", 0.42)):
        for b, t, w in row:
            m = F.mask(aw_poly(b, t, w, shape))
            if not m.any():
                continue
            along, side = F.frame(b, t)
            d = np.clip(along, 0, 1) * reach                      # out from the shoulder, as a share of a flight feather
            k = np.zeros_like(d)
            for j in range(3):
                pk = ((ph + j / 3) % 1.0) * 1.3 - 0.15
                k = np.maximum(k, np.exp(-((d - pk) / 0.07) ** 2))
            kl = levels(np.clip(k, 0, 1), 4, 0.3)
            ring = grow(m, 1) & ~m
            shaft = m & (np.abs(side) < 0.75 / F.s) & (along < 0.9)
            cv.arr[m] = palette(levels(d, 7, 0.5), PULSE_FEATHER)[m]
            cv.arr[m, 3] = 0.7
            flash = m & ~shaft & (kl >= 2)                        # a soft flash inside, right at the pulse
            cv.arr[flash, :3] = hexc("#3a9ae8")[:3]
            lc = palette(kl, PULSE_LIGHT)
            for e in (ring, shaft):
                cv.arr[e] = lc[e]
                cv.arr[e, 3] = 0.95
            lit[m | ring] = 0
            lit = np.where(ring | shaft, 0.4 + 0.6 * kl / 3.0, lit)
            lit = np.where(flash, 0.3, lit)
    arm = F.line(AW_ARM, 1.1)
    put(cv, grow(arm, 1) & ~arm, CRYSTAL_ROD[1])
    put(cv, arm, CRYSTAL_ROD[0][1])
    put(cv, lit_rim(arm, 1), CRYSTAL_ROD[0][0])
    lit = np.where(arm, 0.35, lit)
    em_rgb(cv, cv.arr, lit)


def pulse_gem(face, cv):
    gem_paint("#dffcff", "#2a8fd6")(face, cv)
    b = 0.5 + 0.5 * math.cos(math.tau * cv.phase * 3)              # throbs with each pulse
    em_from(cv, np.ones((cv.h, cv.w)) * (0.4 + 0.6 * b), "#9af6ff")


item("pulse_wings", "Pulse Wings", "WINGS", "SPECIAL", None,
     "Energy wings with waves of light pulsing out from your shoulders to the tips, over and over.",
     aw_wing("wing", pulse_feathers, None, aw_rod(*CRYSTAL_ROD, glow=0.35), wing_anim(32, 12, 1.6),
             extra=[Cube((-2.5, 0.4, AW_Z + 0.6), (2, 2, 2), pulse_gem)]), view=AW_VIEW,
     animation={"frames": 16, "frametime": 1.8, "interpolate": False, "scale": 8})


# -------- feathered wings: Ashen (grey, the tips smouldering) and Seraph (white and gold)

ASHEN = {"line": "#1c1817", "cols": ["#aaa399", "#857e75", "#615a54", "#433c38", "#9a3416"], "shaft": "#cfc8bd",
         "hi": "#c9c2b7", "cov": [["#d9d3c9", "#bdb6ab", "#9c958b"], ["#e4dfd6", "#cbc4b9"], ["#ece8e0", "#d6d0c6"]],
         "arm": ["#f2eee6", "#d6d0c4", "#9c958a"], "vane": 0.82}

item("ashen_feathers", "Ashen Feathers", "WINGS", "REGULAR", 800,
     "Layered grey feathers, each one separate, darkening towards tips that smoulder like embers.",
     aw_wing("feathers", *aw_bird(ASHEN, glow_tip=("#ff7a2a", 0.87, 0.85)), aw_rod(["#f2eee6", "#d6d0c4", "#9c958a"], "#2a2420"),
             wing_anim(38, 8, 1.1)), view=AW_VIEW)

SERAPH = {"line": "#a8916a", "cols": ["#fffdf7", "#f6f0e2", "#ece1c6", "#e8c86a"], "shaft": "#fffef8", "hi": "#ffffff",
          "cov": [["#fffefa", "#f7f2e6", "#efe6d0"], ["#ffffff", "#faf6ec"], ["#ffffff", "#fdfaf2"]],
          "arm": ["#fff3c4", "#f2d27a", "#b8902e"], "vane": 0.9, "n": 19}

item("seraph_wings", "Seraph Wings", "WINGS", "REGULAR", 1000,
     "Great white feathered wings, softly gilded at the tips, that sweep slowly behind you.",
     aw_wing("feathers", *aw_bird(SERAPH, glow_tip=("#fff1b0", 0.82, 0.5)), aw_rod(["#fff3c4", "#f2d27a", "#b8902e"], "#7a5a1a"),
             wing_anim(30, 10, 0.9)), view=dict(AW_VIEW, glow="#fff1c2", glow_strength=0.25))


# -------- membrane wings: dragon and demon. The same arm, then four long fingers fan from the wrist (a claw on top) with a
# scalloped membrane between them; hand-drawn like the feathered wings.

DR_ARM = [(0.4, 1.4), (4.4, -0.6), (10.4, -7.6)]                   # shoulder, elbow, wrist (u, y)
DR_TIPS = [(21.6, -2.8), (20.6, 5.0), (16.8, 10.4), (11.4, 12.2)]   # finger tips, outermost first
DR_ROOT = (0.6, 8.4)                                                # where the membrane meets the back
DR_SPAN, DR_TOP, DR_H = 22, -9, 21                                  # the membrane plane (21 tall: Stormcaller's strip)


def dr_scallop(a, b, depth, toward, n=6):
    """The membrane's edge between two finger tips, curling in towards `toward` by up to `depth` units."""
    pts = []
    for k in range(1, n):
        f = k / n
        x, y = a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f
        dx, dy = toward[0] - x, toward[1] - y
        L = max(1e-3, math.hypot(dx, dy))
        d = depth * math.sin(math.pi * f)
        pts.append((x + dx / L * d, y + dy / L * d))
    return pts


def dr_outline():
    w = DR_ARM[-1]
    pts = list(DR_ARM) + [DR_TIPS[0]]
    for a, b in zip(DR_TIPS, DR_TIPS[1:]):
        pts += dr_scallop(a, b, 1.6 + 0.04 * math.hypot(b[0] - a[0], b[1] - a[1]), w) + [b]
    return pts + dr_scallop(DR_TIPS[-1], DR_ROOT, 2.4, w) + [DR_ROOT]


def dr_finger(F, a, b, w0, w1):
    """A finger bone from a to b, w0 thick at the wrist tapering to w1 (half widths, units)."""
    dx, dy = b[0] - a[0], b[1] - a[1]
    L = max(1e-3, math.hypot(dx, dy))
    nx, ny = -dy / L, dx / L
    return F.mask([(a[0] + nx * w0, a[1] + ny * w0), (b[0] + nx * w1, b[1] + ny * w1), (b[0] - nx * w1, b[1] - ny * w1),
                   (a[0] - nx * w0, a[1] - ny * w0)])


def dr_membrane(cols, bone, line, vein, glow=None, storm=False):
    """cols: membrane bands from the wrist down to the trailing edge; bone: (light, mid, dark) for the arm and fingers."""
    def painter(F):
        cv = F.cv
        mem = F.mask(dr_outline())
        w = DR_ARM[-1]
        d = np.sqrt((F.U - w[0]) ** 2 + (F.Y - w[1]) ** 2) / 22.0
        col = bands(np.clip(d * 0.85 + (F.Y - DR_TOP) / DR_H * 0.35 - 0.12, 0, 1), cols, 0.4)
        if storm:   # banded storm clouds, darker towards the trailing edge
            n = pnoise(cv.h, cv.w, 4.0, 400, octaves=4)
            r, t = 1 - F.U / DR_SPAN, (F.Y - DR_TOP) / DR_H
            cl = levels(np.clip(n * 1.3 - 0.15 - t * 0.35 + (1 - r) * 0.1, 0, 1), 6, 0.4)
            col = palette(cl, ["#0e1220", "#161c2e", "#1f283e", "#2b3652", "#3a4868", "#4e5f84"])
        put(cv, grow(mem, 1) & ~mem, line)
        cv.arr[mem] = col[mem]
        cv.arr[mem, 3] = 1
        # veins: from each finger a few branches run back into the membrane
        V = np.zeros_like(mem)
        rng = np.random.default_rng(7)
        for tip in DR_TIPS:
            for f in (0.35, 0.62):
                p0 = (w[0] + (tip[0] - w[0]) * f, w[1] + (tip[1] - w[1]) * f)
                q = (p0[0] - 1.8 - rng.random() * 1.5, p0[1] + 2.4 + rng.random() * 1.5)
                V |= F.line([p0, ((p0[0] + q[0]) / 2 + 0.4, (p0[1] + q[1]) / 2), q], 0.16)
        V &= mem
        put(cv, V, vein)
        if glow:
            em_from(cv, V * 0.9, glow)
        if storm:
            # bolts in the storm's own normalised coordinates (WingDraw: r 0 tip .. 1 shoulder, t 0 top .. 1 bottom)
            storm_fx(cv.face, cv, mem.astype(np.float32), V.astype(np.float32))
        # finger bones and the arm over the membrane, outlined and lit along their tops
        bones_m = F.line(DR_ARM, 1.0)
        for tip in DR_TIPS:
            bones_m |= dr_finger(F, w, tip, 0.42, 0.16)
        put(cv, grow(bones_m, 1) & ~bones_m, line)
        put(cv, bones_m, bone[1])
        put(cv, lit_rim(bones_m, 1), bone[0])
        put(cv, shade_rim(bones_m, 1), bone[2])
        cv.em[bones_m] = 0
    return painter


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


# Stormcaller's weather, frame by frame over STORM_FRAMES frames: (first frame, seed, brightness on each following frame)
STORM_FRAMES = 20
STORM_STRIKES = [(0, 3, (1.0, 0.35, 0.95, 0.5, 0.18)), (6, 11, (0.85, 0.3, 0.12)), (11, 5, (1.0, 0.4, 0.9, 0.3)),
                 (16, 17, (0.7, 0.9, 0.25))]


def storm_fx(face, cv, mask, veins_m):
    f = int(round(cv.phase * STORM_FRAMES)) % STORM_FRAMES
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


def dr_wing(name, cols, bone, line, vein, glow=None, storm=False, spikes=False, anim=None):
    """A pair of dragon wings: the membrane plane, a hooked claw on the wrist (and spikes along the forearm), the arm."""
    root = f"{name}_r"
    w = DR_ARM[-1]
    px = AW_PIVOT[0]
    cubes = [Cube((px - DR_SPAN, DR_TOP, AW_Z), (DR_SPAN, DR_H, 0), aw_plane(DR_TOP, dr_membrane(cols, bone, line, vein, glow, storm))),
             Cube((px - w[0] - 0.5, w[1] - 2.4, AW_Z + 0.75), (1, 2, 1), metal("#efe6d6", "#6a5a4a", 0.25, False))]
    if spikes:
        for f in (0.3, 0.55, 0.8):
            a = aw_curve(DR_ARM[1:], f)
            cubes.append(Cube((px - a[0] - 0.5, a[1] - 2.2, AW_Z + 0.75), (1, 2, 1), metal("#3a3036", "#120c10", 0.2, False)))
    bones = [Bone(root, "body", AW_PIVOT, (0, 0, 0), cubes, anim=anim or wing_anim(34, 14, 1.2))]
    bones += aw_arm_bones(root, AW_Z + 1.0, aw_rod(list(bone), line), segs=DR_ARM)
    return aw_pair(bones)


item("wyrmspan_wings", "Wyrmspan Wings", "WINGS", "REGULAR", 1100,
     "Broad dragon wings: a crimson membrane with veins and bony fingers, scalloped edges and a hooked claw.",
     dr_wing("wyrm", ["#d8442a", "#b8301e", "#962418", "#741a14", "#561010"], ("#f6ead2", "#e0caa6", "#9a8466"), "#2a0c0a",
             "#ff8a5a"), view=AW_VIEW)
item("infernal_wings", "Infernal Wings", "WINGS", "REGULAR", 900,
     "Spiked black demon wings whose veins glow like magma in the dark.",
     dr_wing("infernal", ["#84464f", "#6c3640", "#562a32", "#422026", "#30161c"], ("#b89aa0", "#7a5a62", "#45303a"), "#14060a",
             "#ff4a1a", glow="#ff5a1a", spikes=True), view=dict(AW_VIEW, glow="#ff4a1a", glow_strength=0.22))
item("stormcaller_wings", "Stormcaller Wings", "WINGS", "SPECIAL", None,
     "Storm-cloud wings that crackle with lightning: bolts flash across the membrane while the veins flicker.",
     dr_wing("storm", ["#4a5878"] * 5, ("#e4ecf8", "#c8d4e8", "#6a7a98"), "#0a0e18", "#7ab8ff", storm=True),
     view=dict(AW_VIEW, thumb_frame=0), animation={"frames": STORM_FRAMES, "frametime": 1.8, "interpolate": False, "scale": 8})


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
    # the lobes hinge on a thorax that stands out from the back, so the upper lobes clear the back of the head
    anim = wing_anim(24, 22, 2.2)
    right = [Bone("lobes_r", "body", (-1, 3, 4.0), (0, 0, -6), [
        Cube((-12, -4, 4.3), (11, 9, 0), butterfly_lobe(True)),
        Cube((-9, 5, 4.35), (8, 7, 0), butterfly_lobe(False))], anim=anim)]
    body = Bone("thorax", "body", (0, 3, 2.5), cubes=[Cube((-0.5, 0, 2), (1, 8, 3), solid_paint(hexc("#14141c")))])
    return wing_pair(right) + [body]


INSECT_VIEW = dict(WING_VIEW, frame=[(-16, -19), (16, 10)])

item("azure_butterfly_wings", "Azure Butterfly Wings", "WINGS", "REGULAR", 600,
     "Iridescent blue butterfly wings with black borders and white spots that flutter quickly.",
     butterfly(), view=INSECT_VIEW)


# -------- mechanical: riveted steel blades fanned from a brass arm, brass plates over their roots, glowing blue seams,
# a brass gear at the shoulder

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


CLOCK = {"line": "#1f2228", "cols": ["#eef1f5", "#c3cad4", "#959ead", "#6a7382"], "shaft": "#4a525e", "hi": "#ffffff",
         "cov": [["#ffe9a0", "#e0b04a", "#a87428"], ["#fff0b8", "#e8bc58"]], "rows": ((0.5, 9), (0.27, 8)),
         "arm": ["#fff0b8", "#d9a640", "#8a5a1a"], "vane": 0.8, "n": 13}


def clock_coverts(base):
    """The brass plates, then two rivets on each plate of the lowest row."""
    _, covs = aw_layout(CLOCK["n"], CLOCK["rows"])

    def p(F):
        base(F)
        for b, t, w in covs[0]:
            for f in (0.3, 0.62):
                x, y = F.P(b[0] + (t[0] - b[0]) * f, b[1] + (t[1] - b[1]) * f)
                m = disc(F.cv, x, y, max(1.2, F.s * 0.2))
                put(F.cv, grow(m, 1) & ~m, "#5a3a10")
                put(F.cv, m, "#fff6d8")
    return p


def clockwork():
    fl, co = aw_bird(CLOCK, shape="blade", glow_tip=("#5ad8ff", 0.8, 1.0))
    return aw_wing("blades", fl, clock_coverts(co), aw_rod(["#fff0b8", "#d9a640", "#8a5a1a"], "#3a2408"),
                   wing_anim(32, 10, 1.3), extra=[Cube((-4.5, 0.0, AW_Z + 0.9), (3, 3, 1), gear_paint)])


item("clockwork_wings", "Clockwork Wings", "WINGS", "REGULAR", 1000,
     "Riveted steel blades with brass trim and glowing blue seams, turning on a brass gear at each shoulder.",
     clockwork(), view=AW_VIEW)


# -------- ice crystal: faceted see-through shards laid like feathers, frosted glowing tips, an ice spar

GLACIER = {"line": "#1d4f8a", "cols": ["#f2fdff", "#c4efff", "#8ed6f6", "#58a8e6", "#3a7ed0"], "hi": "#ffffff",
           "cov": [["#e8fbff", "#b8eaff", "#86d0f4"], ["#f2fdff", "#c8f0ff"], ["#ffffff", "#d8f6ff"]],
           "arm": ["#ffffff", "#bff4ff", "#5ab4e0"], "vane": 0.78, "n": 13}

item("glacier_wings", "Glacier Wings", "WINGS", "REGULAR", 900,
     "A fan of see-through ice crystals with glinting facets and frosted, glowing tips.",
     aw_wing("shards", *aw_bird(GLACIER, shape="shard", alpha=0.9, glow_tip=("#d8f8ff", 0.8, 0.6)),
             aw_rod(["#ffffff", "#bff4ff", "#5ab4e0"], "#1d4f8a", glow=0.3), wing_anim(34, 8, 1.0)),
     view=dict(AW_VIEW, glow="#9fe4ff", glow_strength=0.22))

# ---------------------------------------------------------------------------------------------- hats
# Head space: the head spans x -4..4, y -8..0 (top at -8), z -4..4 (face at -4).

HEAD_VIEW = {"yaw": 28, "pitch": 18, "focus": "head"}


KNIT =["#6e6e72", "#a2a2a6", "#c6c6ca", "#e4e4e8", "#fafafc"]       # greys: the wearer's colour tints them


def knit_px(face, cv):
    """The beanie's crown as pixel art: raised rib columns of V stitches with a shadow line between ribs, darker towards
    the cuff; the top a square spiral of ribs closing in on the pom-pom."""
    cv.hand = True
    y, x = cv.grid().astype(int)
    s = cv.s
    if face in ("top", "bottom"):
        ring = np.maximum(np.abs(x + 0.5 - cv.w / 2), np.abs(y + 0.5 - cv.h / 2)).astype(int)
        lv = np.where(ring % 4 == 0, 1, np.where(ring % 4 == 1, 3, 2))
        lv = np.where(ring >= ring.max(), 0, lv)                       # the edge where the crown turns down
    else:
        cx = x % 4                                               # rib columns four texels wide
        lv = np.choose(cx, [0, 2, 3, 2])
        vee = (cx > 0) & (((y + np.abs(cx - 2)) % 4) == 0)        # a V of stitches down each rib
        lv = np.where(vee, lv + 1, lv)
        lv = np.where((y >= cv.h - s // 2) & (lv > 1), lv - 1, lv)  # in shade just above the cuff
    cv.arr[:] = palette(lv, KNIT)
    cv.arr[..., 3] = 1


def knit_cuff_px(face, cv):
    """The turned-up cuff: tighter ribs, lighter, a crisp fold line along the top and a shadow along the bottom; its
    top and bottom (the fold seen from above) in rings like the crown."""
    cv.hand = True
    y, x = cv.grid().astype(int)
    if face in ("top", "bottom"):
        ring = np.maximum(np.abs(x + 0.5 - cv.w / 2), np.abs(y + 0.5 - cv.h / 2)).astype(int)
        cv.arr[:] = palette(np.where(ring % 3 == 0, 1, np.where(ring % 3 == 1, 4, 3)), KNIT)
        cv.arr[..., 3] = 1
        return
    lv = np.choose(x % 3, [1, 3, 4])
    if face not in ("top", "bottom"):
        lv = np.where(y < 2, 4, lv)
        lv = np.where((y >= 2) & (y < 3), 0, lv)                 # the fold
        lv = np.where(y >= cv.h - 2, np.minimum(lv, 1), lv)
    cv.arr[:] = palette(lv, KNIT)
    cv.arr[..., 3] = 1


COMET = ["#3a5a9a", "#6a9ae0", "#a8d4ff", "#e4f4ff", "#ffffff"]


def pompom_px(face, cv):
    """A comet for a pom-pom: a round fluffy ball (the face's corners cut away) of icy tufts, lit on the top-left and
    shaded bottom-right, an outline round it, a white-hot core that glows and a few star sparks in the tufts."""
    cv.hand = True
    y, x = cv.grid()
    u, v = (x + 0.5 - cv.w / 2) / (cv.w / 2), (y + 0.5 - cv.h / 2) / (cv.h / 2)
    r = np.maximum(np.abs(u), np.abs(v))
    d = np.sqrt(u ** 2 + v ** 2)
    ball = (d < 1.28) & (r <= 1)
    tuft = pnoise(cv.h, cv.w, max(2.0, cv.w / 5), 55, octaves=2)
    light = 0.62 - 0.28 * (u + v) * (face not in ("bottom",)) - 0.35 * d + 0.35 * tuft
    if face == "bottom":
        light = light - 0.25
    lv = levels(np.clip(light, 0, 1), 4, 0.3)
    cv.arr[:] = palette(lv, COMET)
    core = d < 0.5
    cv.arr[core, :3] = hexc("#ffffff")[:3]
    rim = ball & ~(nshift(ball, 1, 0) & nshift(ball, -1, 0) & nshift(ball, 0, 1) & nshift(ball, 0, -1))
    cv.arr[rim, :3] = hexc(COMET[0])[:3]
    sparks = ball & ~core & ~rim & (hash_noise(cv.h, cv.w, 57) > 0.965)
    cv.arr[sparks, :3] = hexc("#fff6c8")[:3]
    cv.arr[..., 3] = ball
    em_rgb(cv, cv.arr, (core * 0.95 + (ball & (d < 0.8) & ~core) * 0.45 + sparks * 0.9).astype(np.float32))


item("comet_cap", "Comet Beanie", "HAT", "REGULAR", 200,
     "A ribbed knit beanie with a turned-up cuff and a glowing pom-pom, in any colour you like.",
     [Bone("beanie", "head", cubes=[Cube((-4, -10, -4), (8, 2, 8), knit_px, inflate=0.6),
                                    Cube((-3, -11, -3), (6, 1, 6), knit_px, inflate=0.6),
                                    Cube((-4, -8, -4), (8, 2, 8), knit_cuff_px, inflate=1.0)], tint=True),
      Bone("pompom", "head", (0, -12, 0), cubes=[Cube((-1.5, -14.4, -1.5), (3, 3, 3), pompom_px)],
           anim={"type": "bob", "amp": 0.25, "speed": 2.0})],
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


def crown_ring(mk, y=-9.5, h=2):
    """A crown's band: four slabs round the head (x, z -5..5) sitting on its top edge. mk() gives each slab's painter
    (a factory, so an animated crown can give every cube its own texture)."""
    return [Cube((-5, y, -5), (10, h, 1), mk()), Cube((-5, y, 4), (10, h, 1), mk()),
            Cube((-5, y, -4), (1, h, 8), mk()), Cube((4, y, -4), (1, h, 8), mk())]


def band_gems(front, right, back, left, y=-9.5):
    """Four big gems set into the middle of the band's sides."""
    return [Cube((-1, y, -5.4), (2, 2, 1), front), Cube((-5.4, y, -1), (1, 2, 2), right),
            Cube((-1, y, 4.4), (2, 2, 1), back), Cube((4.4, y, -1), (1, 2, 2), left)]


def regal_bones():
    """The classic: a gold band with a ruby, sapphires and an emerald, tall pearl-tipped points between short corner
    points, a red velvet dome, two gold arches crossing over it and an orb and cross on top."""
    pearl = jewel("#ffffff", "#f2eee6", "#b8b0a4")
    points = []
    for x, z in ((-0.5, -5), (-0.5, 4), (-5, -0.5), (4, -0.5)):              # tall middle points, pearl-tipped
        points += [Cube((x, -12.5, z), (1, 3, 1), gold), Cube((x, -13.5, z), (1, 1, 1), pearl)]
    for x, z in ((-5, -5), (4, -5), (-5, 4), (4, 4)):                        # short corner points
        points.append(Cube((x, -11.5, z), (1, 2, 1), gold))
    velvet = [Cube((-4, -11, -4), (8, 2, 8), velvet_paint), Cube((-3, -12, -3), (6, 1, 6), velvet_paint)]
    arches = [Cube((-4.5, -12.5, -0.5), (1, 3, 1), gold), Cube((3.5, -12.5, -0.5), (1, 3, 1), gold),
              Cube((-3.5, -13.5, -0.5), (7, 1, 1), gold),
              Cube((-0.5, -12.5, -4.5), (1, 3, 1), gold), Cube((-0.5, -12.5, 3.5), (1, 3, 1), gold),
              Cube((-0.5, -13.5, -3.5), (1, 1, 7), gold)]
    top = [Cube((-1, -15.5, -1), (2, 2, 2), gold), Cube((-0.5, -17.5, -0.5), (1, 2, 1), gold),
           Cube((-1.5, -17, -0.5), (3, 1, 1), gold)]
    ruby, sapphire = jewel("#ffd0d8", "#e8203a", "#6a0414"), jewel("#d0e0ff", "#2a5aff", "#0a1a6a")
    gems = band_gems(ruby, sapphire, jewel("#d8ffe0", "#22c46a", "#0a4a24"), sapphire)
    scroll = engraved_band(gold, "filigree")
    return [Bone("crown", "head", cubes=crown_ring(lambda: scroll) + points + velvet + arches + top + gems)]


def circuit_bones():
    """An open circlet: a gold band traced with glowing circuitry and a cluster of crystals rising at the front, tallest
    in the middle, with a crystal over each ear and at the back."""
    band = engraved_band(gold, "circuit")
    c = jewel("#e8ffff", "#5ae0ff", "#0a6a9a")
    crystals = [Cube((-1, -14, -5.3), (2, 5, 1), c), Cube((-3.5, -12, -5.2), (1, 3, 1), c), Cube((2.5, -12, -5.2), (1, 3, 1), c),
                Cube((-5.3, -12, -0.5), (1, 3, 1), c), Cube((4.3, -12, -0.5), (1, 3, 1), c), Cube((-0.5, -11.5, 4.3), (1, 2, 1), c)]
    return [Bone("crown", "head", cubes=crown_ring(lambda: band) + crystals)]


def sovereign_bones():
    """A platinum crown with tall spires at the corners and shorter ones between, four big gems in the band. Every cube
    has its own painter, so the sweep of light knows where it is."""
    spires = []
    for z in (-5, 4):
        for x, hgt in ((-5, 5), (-2, 3), (1, 3), (4, 5)):
            spires.append(Cube((x, -9.5 - hgt, z), (1, hgt, 1), swept(platinum)))
    for x in (-5, 4):
        for z in (-2, 1):
            spires.append(Cube((x, -12.5, z), (1, 3, 1), swept(platinum)))
    gems = band_gems(royal_gem("#ffd0d8", "#ff2a4a", "#6a0414"), royal_gem("#d0e0ff", "#3a6aff", "#0a1a6a"),
                     royal_gem("#f0d8ff", "#a04aff", "#3a0a6a"), royal_gem("#d8ffe0", "#22d46a", "#0a4a24"))
    return [Bone("crown", "head", cubes=crown_ring(lambda: swept(engraved_band(platinum, "filigree"))) + spires + gems)]


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
     circuit_bones(), view=HEAD_VIEW)
item("regal_crown", "Regal Crown", "HAT", "REGULAR", 400,
     "A classic gold crown with engraved scrollwork, a red velvet cap and a ruby, sapphire and emerald set in the band.",
     regal_bones(), view=HEAD_VIEW)
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
     sovereign_bones(), view=HEAD_VIEW, animation={"frames": 16, "frametime": 2.5, "interpolate": True, "scale": 8})


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
    f = felt("#6a3c94", "#2e1648", 1)
    fb = felt("#5a3280", "#24123a", 2)
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


item("witch_hat", "Witch Hat", "HAT", "REGULAR", 300,
     "A tall, crooked violet witch hat with a wide brim and a gold-buckled band.",
     witch_hat(), view=dict(HEAD_VIEW, pitch=14))


# -------- party hat

def party_paint(face, cv):
    """Pixel art: two yellow stripes spiralling up a pink cone (the same spiral on every tier, from where each texel is
    round the head), sky-blue dots on the pink, a lit top row and a shaded bottom row on every tier."""
    cv.hand = True
    y, x = cv.grid()
    if face in ("top", "bottom"):
        # the ledge round each tier seen from above: the stripes carry on round it (from the angle of each texel),
        # dots on the pink, a lit outer edge and a shaded inner one where the next tier sits
        ox, oy, oz = cv.cube.origin
        w, h, d = cv.cube.size
        wx = ox + (x + 0.5) / cv.w * w
        wz = oz + (y + 0.5) / cv.h * d
        turn = (np.arctan2(wz, wx) / math.tau * 2 - (oy if face == "top" else oy + h) * 0.22) % 1.0
        stripe = turn < 0.36
        base = np.where(stripe[..., None], hexc("#ffd23a"), hexc("#ff4f86"))
        cv.arr[:] = base if face == "top" else base * np.array([0.72, 0.72, 0.72, 1], np.float32)
        edge = (np.abs(turn - 0.36) < 0.03) | (turn < 0.03)
        cv.arr[edge, :3] = hexc("#c8285e")[:3]
        dots = ~stripe & ~edge & ((x % 6) < 2) & ((y % 6) < 2) & (((x // 6 + y // 6) % 2) == 0)
        cv.arr[dots, :3] = hexc("#5ad0ff")[:3]
        ring = np.minimum(np.minimum(x, cv.w - 1 - x), np.minimum(y, cv.h - 1 - y))
        cv.arr[ring < 1, :3] = np.clip(cv.arr[ring < 1, :3] * 1.18 + 0.06, 0, 1)
        cv.arr[(ring >= cv.s // 2) & (ring < cv.s // 2 + 1), :3] *= 0.7
        cv.arr[..., 3] = 1
        return
    wx, wz, wy = face_world(cv)
    turn = (np.arctan2(wz, wx) / math.tau * 2 - wy * 0.22) % 1.0
    stripe = turn < 0.36
    cv.arr[:] = np.where(stripe[..., None], hexc("#ffd23a"), hexc("#ff4f86"))
    edge = (np.abs(turn - 0.36) < 0.025) | (turn < 0.025)
    cv.arr[edge, :3] = hexc("#c8285e")[:3]
    dots = ~stripe & ~edge & ((x % 6) < 2) & ((y % 6) < 2) & (((x // 6 + y // 6) % 2) == 0)
    cv.arr[dots, :3] = hexc("#5ad0ff")[:3]
    cv.arr[:2, :, :3] = np.clip(cv.arr[:2, :, :3] * 1.15 + 0.06, 0, 1)
    cv.arr[-2:, :, :3] *= 0.72
    cv.arr[..., 3] = 1


def tinsel(face, cv):
    n = hash_noise(cv.h, cv.w, 9)
    if CRISP[0]:
        # pixel art: a round fluffy tuft (the corners cut away) of flat pinks, lit top-left, shaded bottom-right and
        # outlined, with scattered single-texel sparkles of tinsel
        cv.hand = True
        y, x = cv.grid()
        u, v = (x + 0.5 - cv.w / 2) / (cv.w / 2), (y + 0.5 - cv.h / 2) / (cv.h / 2)
        dd = np.sqrt(u ** 2 + v ** 2)
        ball = dd < 1.3
        tuft = pnoise(cv.h, cv.w, max(2.0, cv.w / 4), 9, octaves=2)
        lv = levels(np.clip(0.6 - 0.25 * (u + v) - 0.3 * dd + 0.4 * tuft - (0.25 if face == "bottom" else 0), 0, 1), 4, 0.3)
        cv.arr[:] = palette(lv, ["#c8467e", "#ff8ab8", "#ffc2dc", "#fff0f6"])
        rim = ball & ~(nshift(ball, 1, 0) & nshift(ball, -1, 0) & nshift(ball, 0, 1) & nshift(ball, 0, -1))
        cv.arr[rim, :3] = hexc("#9a2a5e")[:3]
        cv.arr[..., 3] = ball
        put(cv, ball & ~rim & (n > 0.93), "#ffffff", 0.6)
        return
    cv.arr[..., :3] = mix(hexc("#ffe6f0"), hexc("#ff9ac8"), n[..., None])[..., :3]
    cv.arr[..., 3] = 1
    em_from(cv, (n > 0.8) * 0.6, "#ffffff")


item("party_cone", "Party Hat", "HAT", "REGULAR", 100,
     "A striped, polka-dotted party hat worn at a jaunty angle, with a fluffy pink tuft on top.",
     [Bone("cone", "head", (0, -8, 0), (0, 0, 14), cubes=[
         Cube((-3, -10, -3), (6, 2, 6), party_paint), Cube((-2.5, -12, -2.5), (5, 2, 5), party_paint),
         Cube((-2, -14, -2), (4, 2, 4), party_paint), Cube((-1.5, -16, -1.5), (3, 2, 3), party_paint),
         Cube((-1, -17, -1), (2, 1, 2), party_paint), Cube((-1.5, -19, -1.5), (3, 2, 3), tinsel)])],
     view=HEAD_VIEW)


# -------- halo

def halo_paint(shimmer=False):
    def p(face, cv):
        t, x = cv.yy(), cv.xx()
        cv.arr[:] = ramp(t, [(0, "#fffbe0"), (0.5, "#ffe27a"), (1, "#e0a83a")])
        cv.arr[..., 3] = 0.95
        if not shimmer:
            # a twisted rope of light gold: diagonal strands (lit crowns, shaded grooves) round a bright band, the top
            # lit and the underside warmer, a fine outline at the edges
            y, xx = cv.grid()
            k = cv.s
            strand = ((xx + y * (0.9 if face not in ("top", "bottom") else 0.0)) % k) / k
            lit = np.sin(strand * math.pi)
            base = 0.55 + 0.4 * lit - 0.25 * t * (face not in ("top", "bottom")) + (0.15 if face == "top" else 0) \
                - (0.25 if face == "bottom" else 0)
            cols = ["#9a5a12", "#c8801e", "#e8a834", "#f6c858", "#ffe49a"]
            cv.arr[..., :3] = palette(levels(np.clip(base, 0, 1), 5, 0.25), cols)[..., :3]
            groove = strand < 0.12
            cv.arr[groove, :3] = hexc("#a85e14")[:3]
            edge = (y == 0) | (y == cv.h - 1)
            if face not in ("top", "bottom"):
                cv.arr[edge, :3] = mix(cv.arr[edge, :3], hexc("#8a4a10")[:3], 0.6)
            cv.arr[..., 3] = 0.96
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

HORN = ["#3a2624", "#4e2e26", "#6a3622", "#94401c", "#d0581c", "#ff8a32", "#ffd27a"]
HORN_SEGS = [((2, 3, 2), -18, -6), ((2, 2, 2), -16, -10), ((1, 2, 1), -6, -16), ((1, 2, 1), 10, -14), ((1, 1, 1), 18, -8)]


def horn_px(i, n):
    """Segment i of n: dark keratin at the root warming to a coal-bright tip, a darker ring every half unit (the
    ridges), lit on the left, the last two segments glowing."""
    def p(face, cv):
        cv.hand = True
        y, x = cv.grid()
        f = (i + 1 - cv.yy()) / n                                      # 0 at the root .. 1 at the very tip
        lv = levels(f, len(HORN) - 1, 0.4) + (0 if face in ("top", "bottom") else (x < max(1, cv.w // 4)))
        cv.arr[:] = palette(lv, HORN)
        ring = (y % max(2, cv.s // 2)) == 0
        cv.arr[ring, :3] *= 0.7
        cv.arr[..., 3] = 1
        if i >= n - 3:
            em_rgb(cv, cv.arr, np.clip(f * 1.4 - 0.6, 0, 1) * ~ring)
    return p


def horns():
    """Each horn is a chain of shrinking segments, each a child bone turned a little more than the last, so it sweeps
    out from the temple, back over the head and curls up at the tip."""
    bones, parent = [], None
    px, py, pz = -3.0, -7.5, -0.5
    for i, ((w, h, d), roll, pitch) in enumerate(HORN_SEGS):
        name = f"horn{i}_r"
        bones.append(Bone(name, "head", (px, py, pz), (pitch, 0, roll),
                          [Cube((px - w / 2, py - h, pz - d / 2), (w, h, d), horn_px(i, len(HORN_SEGS)))], parent=parent))
        parent, py = name, py - h
    return aw_pair(bones)


item("ember_horns", "Ember Horns", "HAT", "REGULAR", 300,
     "A pair of ridged dark horns that curl up from your temples, glowing like coals at the tips.",
     horns(), view=HEAD_VIEW)


# -------- ears

def ear_outer(face, cv):
    """Light fur (greys: the wearer's colour tints it): short strokes of shade in rows, a lit top-left rim, a darker
    bottom-right rim and a tuft of darker fur at the tip."""
    cv.hand = True
    y, x = cv.grid()
    n = hash_noise(cv.h, cv.w, 140 + len(face))
    lv = np.full((cv.h, cv.w), 2)
    stroke = ((x + (y // 3) * 2) % 4 == 0) & (n > 0.35)
    lv = np.where(stroke, 1, lv)
    lv = np.where(n > 0.93, 3, lv)
    e = max(1, cv.s // 8)
    lv = np.where((y < e) | (x < e), 3, lv)
    lv = np.where((y >= cv.h - e) | (x >= cv.w - e), 0, lv)
    if face not in ("top", "bottom"):
        lv = np.where((y < cv.s // 2) & (lv > 1) & (face in ("north", "south")) & (cv.cube.size[1] == 1), lv - 1, lv)
    cv.arr[:] = palette(lv, ["#9a9a9e", "#c2c2c6", "#dedee2", "#f6f6f8"])
    cv.arr[..., 3] = 1


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


item("cat_ears", "Cat Ears", "HAT", "REGULAR", 100,
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
    # an original print, kept sparse so it reads at a glance: small four-point stars in offset rows on the red
    cell = s * 4.5
    row = np.floor(y / cell)
    cx = ((x + (row % 2) * cell / 2) % cell) - cell / 2
    cy = (y % cell) - cell / 2
    star = (np.abs(cx) + np.abs(cy) * 2.2 < s * 0.9) | (np.abs(cx) * 2.2 + np.abs(cy) < s * 0.9)
    cv.arr[star, :3] = hexc("#fff2e0")[:3]
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


def fox_lacquer(cv):
    """The inside of a lacquered mask: deep red, a darker grain running down it, lit along the top and left."""
    cv.hand = True
    y, x = cv.grid()
    lv = levels(np.clip(0.85 - cv.yy() * 0.75, 0, 1), 4, 0.2)
    cv.arr[:] = palette(lv, ["#4a0a12", "#7a1420", "#a01e2a", "#c83038"])
    n = hash_noise(cv.h, cv.w, 812)
    streak = (n[:1] > 0.55) & ((y // max(1, cv.s // 2)) % 3 != 0)              # brush strokes down the lacquer
    cv.arr[np.broadcast_to(streak, (cv.h, cv.w)) & (n > 0.25), :3] *= 0.82
    gloss = (np.abs(x + 0.5 - cv.w * 0.3 - y * 0.25) < cv.s * 0.25) & (y < cv.h * 0.7)
    cv.arr[gloss, :3] = np.clip(cv.arr[gloss, :3] * 1.25 + 0.04, 0, 1)                 # a soft sheen
    e = max(1, cv.s // 8)
    cv.arr[(y < e) | (x < e), :3] = hexc("#e0545a")[:3]
    cv.arr[(y >= cv.h - e) | (x >= cv.w - e), :3] = hexc("#30060c")[:3]
    cv.arr[..., 3] = 1


def fox_face(face, cv):
    if face == "south":
        fox_lacquer(cv)
        return
    t = cv.yy()
    y, x = cv.grid()
    cv.arr[:] = bands(t, ["#fdfbf6", "#f4efe6", "#e8e0d2"], 0.4)
    cv.arr[..., 3] = 1
    if face == "north":
        W, H = cv.w, cv.h
        for sd in (-1, 1):
            cx = W / 2 + sd * W * 0.23
            # a red liner sweeping up and out from the eye towards the temple, then the dark eye slit inside it
            liner = [(cx - sd * W * 0.15, H * 0.44), (cx + sd * W * 0.05, H * 0.27), (cx + sd * W * 0.25, H * 0.13),
                     (cx + sd * W * 0.2, H * 0.3), (cx + sd * W * 0.13, H * 0.47), (cx - sd * W * 0.08, H * 0.55)]
            sprite(cv, pmask(cv, lambda d: d.polygon(liner, fill=255)), "#d6242e", line="#7a0a16", hi="#ff5a5a")
            slit = [(cx - sd * W * 0.11, H * 0.45), (cx + sd * W * 0.03, H * 0.35), (cx + sd * W * 0.13, H * 0.36),
                    (cx + sd * W * 0.02, H * 0.47)]
            put(cv, pmask(cv, lambda d: d.polygon(slit, fill=255)), "#16101a")
            for k in range(3):                                           # whisker marks on the cheeks
                y0 = H * (0.64 + k * 0.09)
                wh = [(W / 2 + sd * W * 0.28, y0), (W / 2 + sd * W * 0.46, y0 - H * 0.04), (W / 2 + sd * W * 0.46, y0 - H * 0.01),
                      (W / 2 + sd * W * 0.28, y0 + H * 0.03)]
                sprite(cv, pmask(cv, lambda d: d.polygon(wh, fill=255)), "#d6242e", line="#9a1420")
        drop = [(W / 2, H * 0.06), (W / 2 + W * 0.07, H * 0.2), (W / 2, H * 0.27), (W / 2 - W * 0.07, H * 0.2)]
        sprite(cv, pmask(cv, lambda d: d.polygon(drop, fill=255)), "#d6242e", line="#7a0a16", hi="#ff6a5a")
        put(cv, disc(cv, W / 2, H * 0.19, max(1.0, W * 0.022)), "#ffd25a")
        for sd in (-1, 1):
            put(cv, disc(cv, W / 2 + sd * W * 0.13, H * 0.1, max(1.0, W * 0.025)), "#d6242e")
    bevel(cv, 0.14, 0.24)


def fox_snout(face, cv):
    cv.arr[:] = bands(cv.yy(), ["#fdfbf6", "#f2ece2", "#e4dbcb"], 0.4)
    cv.arr[..., 3] = 1
    y, x = cv.grid()
    if face == "north":
        W, H = cv.w, cv.h
        nose = pmask(cv, lambda d: d.polygon([(W * 0.3, 0), (W * 0.7, 0), (W * 0.62, H * 0.22), (W * 0.5, H * 0.32), (W * 0.38, H * 0.22)], fill=255))
        sprite(cv, nose, "#1a1416", hi="#5a4a52")
        mouth = pmask(cv, lambda d: d.line([(W * 0.18, H * 0.62), (W * 0.32, H * 0.72), (W * 0.68, H * 0.72), (W * 0.82, H * 0.62)],
                                           fill=255, width=max(1, cv.s // 6)))
        put(cv, mouth, "#d6242e")
    if face == "top":
        put(cv, np.abs(x + 0.5 - cv.w / 2) < cv.s * 0.3, "#d6242e")
        put(cv, (np.abs(x + 0.5 - cv.w / 2) < cv.s * 0.3) & (x + 0.5 < cv.w / 2), "#ff5a5a")
    bevel(cv, 0.14, 0.24)


def fox_ear(face, cv):
    cv.arr[:] = bands(cv.yy(), ["#fdfbf6", "#f2ece2", "#e2d8c8"], 0.4)
    y, x = cv.grid()
    if face == "south":
        # the back of the ear: a red lacquered seam up the middle, like the markings on the front
        put(cv, (np.abs(x + 0.5 - cv.w / 2) < cv.s * 0.2) & (y > cv.s * 0.4), "#b81c28")
    if face == "north":
        W, H = cv.w, cv.h
        inner = pmask(cv, lambda d: d.polygon([(W * 0.5, H * 0.22), (W * 0.82, H * 1.0), (W * 0.18, H * 1.0)], fill=255))
        sprite(cv, inner, "#d6242e", hi="#ff6a5a", lo="#9a1420")
    put(cv, y < max(1, cv.s // 3), "#2a1a1a")
    bevel(cv, 0.14, 0.24)


item("kitsune_mask", "Fox Spirit Mask", "MASK", "REGULAR", 250,
     "A white fox mask with red markings, pointed ears and a short snout, worn over the face.",
     [Bone("mask", "head", cubes=[Cube((-4, -8, -5), (8, 7, 1), fox_face, inflate=0.1),
                                  Cube((-1.5, -4, -7), (3, 3, 2), fox_snout),
                                  Cube((-4, -10, -5), (2, 2, 1), fox_ear), Cube((2, -10, -5), (2, 2, 1), fox_ear),
                                  Cube((-4.5, -5.5, -4), (1, 1, 8), strap), Cube((3.5, -5.5, -4), (1, 1, 8), strap)])],
     view=dict(HEAD_VIEW, yaw=24, pitch=6), scale_cap=16)


def shades_lens(face, cv):
    t, x = cv.yy(), cv.xx()
    cv.arr[:] = ramp(t, [(0, "#ff3ad2"), (0.5, "#7a3aff"), (1, "#1ad8ff")])
    if CRISP[0]:
        # mirrored lenses: flat bands and a level shine line near the top (the same from either side of the thin lens,
        # so its front and back faces agree)
        cv.arr[:] = bands(t, ["#ff5ae0", "#e040e8", "#a03aff", "#6a4aff", "#3a8aff", "#1ad8ff"], 0.4)
        y, xx = cv.grid()
        e = max(1, cv.s // 8)
        put(cv, (y >= 2 * e) & (y < 3 * e) & (np.abs(xx + 0.5 - cv.w / 2) < cv.w * 0.36), "#ffe0fa")
        put(cv, (y >= cv.h - 3 * e) & (y < cv.h - 2 * e) & (np.abs(xx + 0.5 - cv.w / 2) < cv.w * 0.2), "#bff6ff")
        em_rgb(cv, cv.arr, np.ones_like(t) * 0.55)
        return
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


item("runeblade_sheath", "Runeblade Sheath", "BACK", "REGULAR", 400,
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
    if face == "north":
        # the back of the shield (against your back): plank seams, two riveted iron straps across and a leather grip
        seam = (x % max(1, cv.s * 2)) == 0
        cv.arr[seam, :3] *= 0.6
        knot = hash_noise(cv.h, cv.w, 171)
        grain_l = ((y + (x % (cv.s * 2)) * 3 + (x // (cv.s * 2)) * 5) % 5 == 0) & (knot > 0.4)
        cv.arr[grain_l, :3] *= 0.82
        for yc in (0.3, 0.7):
            band = np.abs(y + 0.5 - cv.h * yc) < cv.s * 0.45
            cv.arr[band, :3] = mix(hexc("#8a909c"), hexc("#3a3e48"), np.clip((y[band] + 0.5 - cv.h * yc) / (cv.s * 0.9) + 0.5, 0, 1)[..., None])[..., :3]
            riv = band & (np.abs(y + 0.5 - cv.h * yc) < cv.s * 0.15) & ((x % (cv.s * 2)) == cv.s)
            cv.arr[riv, :3] = hexc("#e8ecf2")[:3]
        grip = (np.abs(x + 0.5 - cx) < cv.s * 0.6) & (np.abs(y + 0.5 - cy) < cv.s * 2.2)
        cv.arr[grip, :3] = hexc("#5a3418")[:3]
        cv.arr[grip & ((y % max(2, cv.s // 2)) == 0), :3] = hexc("#3a2010")[:3]
    if face == "south":
        a = np.arctan2(y + 0.5 - cy, x + 0.5 - cx)
        # a painted sun: quartered blue and gold with rays
        quarter = ((a > 0) ^ (np.abs(a) > math.pi / 2))
        paint_m = r < 0.86
        cv.arr[paint_m & quarter, :3] = hexc("#2a5aa8")[:3]
        cv.arr[paint_m & ~quarter, :3] = hexc("#e8b84a")[:3]
        cv.arr[..., :3] *= (0.9 + 0.12 * n)[..., None]
        # the planks show through the paint: their seams and a little wear along the grain
        seam = paint_m & ((x % max(1, cv.s * 2)) == 0)
        cv.arr[seam, :3] *= 0.72
        wear = paint_m & (hash_noise(cv.h, cv.w, 172) > 0.9) & (((y + x // (cv.s * 2) * 3) % 4) == 0)
        cv.arr[wear, :3] = cv.arr[wear, :3] * 0.6 + hexc("#8a5a30")[:3] * 0.4
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


def ring_sigil(c_in, c_out, glyphs=12, alpha=0.8, extra=None, hue=None, star=None, opaque=False):
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
        # opaque: the drawn lines are solid (they read the same on a light stage), only their soft fringe fades
        cv.arr[..., 3] = np.where(A >= 0.45, 1.0, A * alpha) if opaque else A * alpha
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
# the thumbnail: looking down on the sigil so it fills the card, the player from the chest down standing in it
AURA_CLOSE = {"yaw": 30, "pitch": 55, "frame": [(-12, -23.5), (12, 0.5)], "margin": 1.0}

item("ember_orbit", "Ember Orbit", "AURA", "REGULAR", 400,
     "A ring of fire runes turns at your feet while three embers circle your waist.",
     sigil_bones(ring_sigil("#ffe08a", "#ff3a0a", 12, 0.78, star=6, opaque=True)) +
     [Bone("embers", "root", (0, 14, 0), cubes=[Cube((8.5, 13, -0.5), (1, 1, 1), ember_mote),
                                               Cube((-5.25, 15, 7.1), (1, 1, 1), ember_mote),
                                               Cube((-5.25, 11, -8.1), (1, 1, 1), ember_mote)],
           anim={"type": "spin", "speed": -70}, emissive=True)],
     fx={"type": "aura", "particle": "soft", "colors": ["#FFE08A", "#FF7A2A", "#8A1A0A"], "rate": 4, "size": 0.6,
         "life": 1.4, "sizeEnd": 0.3, "gravity": -0.35},
     view=AURA_CLOSE)


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
    cv.arr[..., 3] = np.where(A >= 0.45, 1.0, A * 0.72)          # solid frost lines, a soft fringe
    em_rgb(cv, col, A * 0.72)


item("frost_sigil", "Frost Sigil", "AURA", "REGULAR", 400,
     "A pale, branching frost star slowly turns beneath you, with a few drifting snowflakes.",
     sigil_bones(frost_fn, spin=-12),
     fx={"type": "aura", "particle": "snow", "colors": ["#E8FBFF"], "rate": 2, "size": 1.0},
     view=AURA_CLOSE)


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
     view=AURA_CLOSE)

item("void_rift", "Void Rift", "AURA", "REGULAR", 400,
     "A violet rune circle with a dark, starry core opens under you, shedding motes of purple light.",
     sigil_bones(ring_sigil("#ffd0ff", "#8a2aff", 12, 0.8, extra=lambda X, Z, r, a: smooth(1 - r / 0.32) * 0.45, star=8), spin=-30),
     fx={"type": "aura", "particle": "soft", "colors": ["#FF9AF0", "#B04FFF", "#3A0A6A"], "rate": 4, "size": 0.7,
         "life": 1.5, "sizeEnd": 0.3, "gravity": -0.25},
     view=AURA_CLOSE)

item("spectrum_aura", "Spectrum Aura", "AURA", "SPECIAL", None,
     "A rune circle whose colours sweep through the whole rainbow as it turns, with rainbow motes rising around you.",
     sigil_bones(ring_sigil("#ffffff", "#ffffff", 12, 0.9, hue=True, star=6), spin=20),
     fx={"type": "aura", "particle": "soft", "colors": ["#FF4A4A", "#FFD23A", "#3AFF7A", "#3AC8FF", "#B04FFF"], "rate": 5,
         "size": 0.6, "life": 1.6, "sizeEnd": 0.35, "gravity": -0.3},
     view=AURA_CLOSE, animation={"frames": 20, "frametime": 3, "interpolate": True, "scale": 16})

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


# -------- Ginger Cat: a round ginger tabby sitting up, white paws and muzzle, green eyes, a curling tail

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


item("ginger_cat", "Ginger Cat", "PET", "REGULAR", 500,
     "A round ginger tabby with big green eyes and white paws that sits on your shoulder, looks about, blinks and "
     "swishes its tail.",
     cat_pet(), view=dict(PET_VIEW, glow="#ffb45a"))


# -------- Ember Fox: bright orange, white chest and cheeks, black socks, big ears and a huge bushy tail

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
        PC(a, (-2, -8.5, -0.75), (2, 3, 0), ear_plane(FOX_FUR, "#3a2020", FOX_BLACK, apex=0.35)),
        PC(a, (0, -8.5, -0.75), (2, 3, 0), ear_plane(FOX_FUR, "#3a2020", FOX_BLACK, apex=0.65))],
        anim={"type": "pet_head", "amp": 10}, parent="fox")
    lids = blink_bone("lids", "head", a, (-1.5, -4.75, -2.27), (3, 1, 0), lid_paint(FOX_FUR, [(0, 4), (8, 4)]))
    # the tail: big and bushy, raised up behind, white-tipped; it wags from its root
    tail = Bone("tail", "root", P(a, 0, -1.25, 1.75), (30, 0, 0),
                [PC(a, (-1, -2.25, 1.5), (2, 2, 4), tail_tip(FOX_FUR, FOX_WHITE, 4, 1.25))],
                anim=wag(12, 0.8), parent="fox")
    return [body, head, lids, tail]


item("ember_fox", "Ember Fox", "PET", "REGULAR", 600,
     "A bright orange fox with a white chest, black socks and a big white-tipped tail that wags while it watches the "
     "world from your shoulder.",
     fox_pet(), view=dict(PET_VIEW, glow="#ff8a3a"))


# -------- Jungle Parrot: the classic shoulder pet, scarlet with yellow and blue wings, a white face and a hooked beak

PAR_RED, PAR_RED_D, PAR_YEL, PAR_BLUE, PAR_BLUE_D = "#e8302a", "#a01818", "#ffc828", "#2a68e0", "#183c9a"
PAR_FACE, PAR_BEAK, PAR_BEAK_D, PAR_FEET = "#f8f2e8", "#efe2c2", "#2e282e", "#6a666e"
PARROT = PERCH


def par_feathers(A, col, off=0):
    """Small overlapping feathers: a short darker tip under each one, staggered row by row, and a light glint."""
    k = (A.x + (A.y // 3) * 2 + off) % 4
    A.put((A.y % 3 == 2) & ((k == 1) | (k == 2)) & (A.y > 0), shade(col, 0.84))
    A.put((A.y % 3 == 0) & (k == 3) & (A.y > 1) & ((A.x + A.y) % 5 == 0), shade(col, 1.14))


def par_body(face, A):
    par_feathers(A, PAR_RED, 1 if face in ("east", "west") else 0)
    if face == "north":                        # 12 x 12: a brighter breast
        A.ellipse(6, 7, 4, 6, shade(PAR_RED, 1.1))
        inside = ((A.x + 0.5 - 6) / 4) ** 2 + ((A.y + 0.5 - 7) / 6) ** 2 < 0.8
        A.put(inside & (A.y % 3 == 1) & ((A.x + A.y // 3) % 3 == 0), shade(PAR_RED, 0.86))
    elif face == "south":                      # the rump: blue where the tail starts
        A.put(A.y >= A.h - 4, PAR_BLUE)
        A.put((A.y >= A.h - 4) & (A.x % 3 == 1), shade(PAR_BLUE, 0.8))


def par_head(face, A):
    par_feathers(A, PAR_RED, 2)
    if face == "north":                        # 16 x 12: white face patches with fine feather lines, golden eyes
        A.ellipse(4.5, 5.5, 3.2, 4.2, PAR_FACE)
        A.ellipse(11.5, 5.5, 3.2, 4.2, PAR_FACE)
        A.pix([(2, 9), (3, 10), (13, 9), (12, 10), (1, 7), (14, 7)], "#c8b8b0")
        eye(A, 2, 3, 4, 4, "#ffd23a", dark="#1a1010", lid=PAR_FACE)
        eye(A, 10, 3, 4, 4, "#ffd23a", dark="#1a1010", lid=PAR_FACE)
        A.rect(7, 1, 9, 3, shade(PAR_RED, 1.18))
    elif face in ("east", "west"):             # a white cheek on each side
        A.ellipse(A.w / 2 + (-2 if face == "east" else 2), 6.5, 3, 3.2, PAR_FACE)
        A.pix([(A.w // 2, 7), (A.w // 2 - 1, 8), (A.w // 2 + 1, 6)], "#c8b8b0")


def par_beak(face, A):
    """The upper mandible: pale horn with a light ridge, curving down to a hooked tip in the middle."""
    if face == "north":                        # 8 x 4: full width at the top, narrowing to a 2 px hook
        keep = ((A.y <= 1) & ~((A.y == 0) & ((A.x == 0) | (A.x == A.w - 1)))) \
            | ((A.y == 2) & (A.x >= 1) & (A.x <= A.w - 2)) | ((A.y == 3) & (A.x >= 3) & (A.x <= 4))
        A.put((A.y >= 2), shade(PAR_BEAK, 0.86))
        A.put((A.y == 3), "#a89880")
        A.pix([(3, 0), (4, 0)], "#fffaf0")
        A.clip(keep)
    elif face in ("east", "west"):             # 4 x 4: the curve of the hook at the front, open below at the back
        zs = (A.x + 0.5) / A.w if face == "west" else 1 - (A.x + 0.5) / A.w
        A.put(A.y >= 2, shade(PAR_BEAK, 0.86))
        A.clip(~((A.y == 3) & (zs > 0.5)) & ~((A.y == 0) & (zs < 0.3)))
    elif face == "top":
        A.put((A.x >= 3) & (A.x <= 4), "#fffaf0")
    elif face == "bottom":
        A.fill(shade(PAR_BEAK, 0.7))


def par_jaw(face, A):
    """The lower mandible: dark, tucked in under the hook."""
    if face == "north":
        A.pix([(1, 0), (2, 0)], "#4a444c")


def par_wing(face, A):
    """A folded wing: red coverts at the shoulder, a band of yellow, then long blue flight feathers."""
    t = (A.y + 0.5) / A.h
    if face in ("east", "west", "north", "south"):
        A.put(t >= 0.3, PAR_YEL)
        A.put((t >= 0.3) & (t < 0.42) & (A.x % 2 == 0), shade(PAR_YEL, 0.84))
        A.put(t >= 0.5, PAR_BLUE)
        A.put((t >= 0.5) & (A.x % 2 == (0 if face != "west" else 1)), shade(PAR_BLUE, 0.78))
        A.put(t >= 0.84, PAR_BLUE_D)
        A.put((t < 0.3) & ((A.x + A.y) % 3 == 0), shade(PAR_RED, 0.8))
    elif face == "top":
        A.put((A.x + A.y) % 3 == 0, shade(PAR_RED, 0.8))
    elif face == "bottom":
        A.fill(PAR_BLUE_D)


def par_tail(face, A):
    """The long tail feathers: red, a centre quill, blue towards the tip."""
    if face in ("top", "bottom"):
        A.put(A.x == A.w // 2, shade(PAR_RED, 0.75))
        A.put((A.y >= A.h * 0.55), PAR_BLUE)
        A.put((A.y >= A.h * 0.55) & (A.x == A.w // 2), shade(PAR_BLUE, 0.7))
        A.put((A.y >= A.h - 2) & ((A.x == 0) | (A.x == A.w - 1)), (0, 0, 0))
        A.a[(A.y >= A.h - 2) & ((A.x == 0) | (A.x == A.w - 1)), 3] = 0
    elif face in ("east", "west"):
        A.put((A.x >= A.w * 0.55) if face == "east" else (A.x < A.w * 0.45), PAR_BLUE)
    elif face == "south":
        A.fill(PAR_BLUE_D)


def par_foot(face, A):
    A.put(A.y >= A.h - 1, shade(PAR_FEET, 0.8))
    if face == "north":
        A.pix([(1, A.h - 1), (2, A.h - 1)], "#3a383e")


def parrot_pet():
    a = PARROT
    body = pet_body("parrot", a, [
        PC(a, (-1.5, -3, -1.25), (3, 3, 3), px(PAR_RED, par_body)),
        PC(a, (-1.25, -1, -1.5), (1, 1, 1), px(PAR_FEET, par_foot)),
        PC(a, (0.25, -1, -1.5), (1, 1, 1), px(PAR_FEET, par_foot))])
    head = Bone("head", "root", P(a, 0, -3, -0.25), cubes=[
        PC(a, (-2, -6, -1.75), (4, 3, 3), px(PAR_RED, par_head)),
        PC(a, (-1, -3.75, -2.75), (2, 1, 1), px(PAR_BEAK, par_beak)),
        PC(a, (-0.5, -3, -2.5), (1, 1, 1), px(PAR_BEAK_D, par_jaw))],
        anim={"type": "pet_head", "amp": 16}, parent="parrot")
    lids = blink_bone("lids", "head", a, (-1.5, -5.25, -1.77), (3, 1, 0), lid_paint(PAR_FACE, [(0, 4), (8, 4)], PAR_RED_D))
    wing = Bone("wing_r", "root", P(a, -1.5, -2, -0.75), cubes=[
        PC(a, (-2.25, -2.25, -1), (1, 2, 3), px(PAR_RED, par_wing))],
        anim={"type": "wing", "side": 1, "base": 0, "amp": 7, "speed": 0.6, "fold": 0}, parent="parrot")
    tail = Bone("tail", "root", P(a, 0, -1.25, 1.5), (-22, 0, 0), cubes=[
        PC(a, (-0.5, -1.75, 1.5), (1, 1, 4), px(PAR_RED, par_tail))],
        anim=wag(6, 0.5), parent="parrot")
    return [body, head, lids, tail] + pet_pair(wing, a)


item("jungle_parrot", "Jungle Parrot", "PET", "REGULAR", 500,
     "A scarlet parrot with yellow and blue wings, a white face and a hooked beak that perches on your shoulder, "
     "turns its head to look about, blinks and flicks its long tail.",
     parrot_pet(), view=dict(PET_VIEW, glow="#ff6a3a"))


# -------- Bubble Slime: a wobbly see-through jelly cube with a glowing core, a big grin and rosy cheeks

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


item("bubble_slime", "Bubble Slime", "PET", "REGULAR", 500,
     "A wobbly see-through green slime with a glowing core, rosy cheeks and a wide grin that bounces on your shoulder.",
     slime_pet(), view=dict(PET_VIEW, glow="#3ad88a"))


# -------- Soul Wisp: a little spirit of cool blue flame that floats beside you

WISP = (HOVER[0] + 0.25, HOVER[1], HOVER[2])
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
        anim=wag(16, 0.6, phase=0.3), parent="head")
    hand = Bone("hand_r", "root", P(a, -2, -3, 0), cubes=[
        PC(a, (-2.75, -3.5, -0.5), (1, 1, 1), px(WISP_MID, lambda f, A: wisp_glow(A, [(0, WISP_HI), (1, WISP_MID)], 0.4)))],
        anim={"type": "wing", "side": 1, "base": 0, "amp": 18, "speed": 1.1, "fold": 0}, parent="head")
    lids = blink_bone("lids", "head", a, (-1.5, -4.5, -2.03), (3, 1, 0), lid_paint("#b4ecff", [(0, 5), (7, 5)], "#0e1a3a"))
    return [body, head, flame, tail, lids] + pet_pair(hand, a)


item("soul_wisp", "Soul Wisp", "PET", "REGULAR", 500,
     "A small spirit of cool blue soul flame with big shiny eyes that floats beside your shoulder, its flame flickering "
     "and its little hands drifting.",
     wisp_pet(), view=dict(PET_VIEW, glow="#6cf2ff"))


# -------- Baby Dragon: a little teal dragon hovering beside you on beating violet wings

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
        PC(a, (0.5, -1.5, 0.25), (1, 1, 1), px(WYV_SKIN, wyv_leg)),
        PC(a, (-0.5, -4.5, 1.5), (1, 1, 1), px(WYV_DARK))])                                   # the wings' root
    body.anim = dict(PET_ANIM, amp=1.0, speed=1.6)
    head = Bone("head", "root", P(a, 0, -4, -0.25), cubes=[
        PC(a, (-2, -7, -2.25), (4, 3, 3), px(WYV_SKIN, wyv_head)),
        PC(a, (-1, -5, -4.25), (2, 1, 2), px(WYV_SKIN, wyv_snout))],
        anim={"type": "pet_head", "amp": 12}, parent="wyvern")
    horns = Bone("horn_r", "root", P(a, -1.25, -7, -1.0), (18, 0, -10),
                 [PC(a, (-1.75, -7.75, -1.0), (1, 1, 2), px(WYV_HORN, wyv_horn, rim=0.8))], parent="head")
    lids = blink_bone("lids", "head", a, (-1.5, -6, -2.27), (3, 1, 0), lid_paint(WYV_SKIN, [(0, 4), (8, 4)], "#14202a"))
    tail = Bone("tail", "root", P(a, 0, -1.5, 1.75), (-12, 0, 0), [
        PC(a, (-0.5, -2, 1.5), (1, 1, 3), px(WYV_SKIN, wyv_tail)),
        PC(a, (-0.5, -2, 4.25), (1, 1, 2), px(WYV_SKIN, wyv_tail)),
        PC(a, (-1, -1.5, 6.25), (2, 0, 2), px("#9a5ae8", wyv_spade, rim=0.7))],
        anim=wag(18, 0.7), parent="wyvern")
    wing = Bone("wing_r", "root", P(a, -0.5, -4, 2.25), (0, 0, 40), cubes=[PC(a, (-5.5, -7.5, 2.25), (5, 4, 0), px("#c07aff", wyv_wing, rim=0))],
                anim={"type": "wing", "side": 1, "base": 28, "amp": 17, "speed": 1.4, "ease": "flap", "lift": -10, "fold": 40},
                parent="wyvern")
    return [body, head] + pet_pair(horns, a) + [lids, tail] + pet_pair(wing, a)


item("baby_dragon", "Baby Dragon", "PET", "REGULAR", 800,
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
        PC(a, (-0.5, -3, 0.75), (1, 1, 2), prism_facets)])
    body.anim = dict(PET_ANIM, amp=1.2, speed=1.0)
    head = Bone("head", "root", P(a, 0, -3, 0), cubes=[
        PC(a, (-1.5, -6, -1.5), (3, 3, 3), prism_head),
        PC(a, (-0.5, -7, -0.5), (1, 1, 1), prism_facets)],
        anim={"type": "pet_head", "amp": 12}, parent="sprite")
    lids = blink_bone("lids", "head", a, (-1.25, -5, -1.53), (3, 1, 0),
                      lid_paint("#e8e0ff", [(0, 4), (6, 4)], "#1a1040"))
    wing = Bone("wing_r", "root", P(a, -0.5, -2.5, 2.5), (0, 0, 22), cubes=[PC(a, (-5.5, -6.5, 2.5), (5, 5, 0), prism_wing)],
                anim={"type": "wing", "side": 1, "base": 28, "amp": 16, "speed": 2.6, "lift": -8, "fold": 40},
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

def stick_px(face, cv):
    """A maple drumstick: pale wood with a fine grain down it, lit on one side, a dark tape grip at the hand end and a
    rounded lighter tip."""
    cv.hand = True
    y, x = cv.grid()
    t = cv.yy()
    g = hash_noise(cv.h, cv.w, 303)
    lv = np.where(x < max(1, cv.w // 3), 3, 2) - ((g > 0.7) & ((y // 2) % 3 == 0))
    lv = np.where(x >= cv.w - max(1, cv.w // 4), 1, lv)
    cv.arr[:] = palette(lv, ["#6a4220", "#b07a44", "#d8a868", "#f0cc90"])
    if face not in ("top", "bottom"):
        grip = t < 0.32
        cv.arr[grip, :3] = palette(np.where(((y + x) % 3 == 0)[grip], 0, 1), ["#141418", "#2c2c34"])[:, :3]
        tip = t > 0.88
        cv.arr[tip, :3] = hexc("#f8e4b8")[:3]
    elif face == "bottom":
        cv.arr[..., :3] = hexc("#f8e4b8")[:3]
    cv.arr[..., 3] = 1


def drumsticks():
    """A stick in each hand (the emote's prop, only there while it plays): held at the end, pointing on past the fist
    and tipped forward, so the hits land in front of you."""
    r = Bone("stick_r", "right_arm", (-1, 9.5, 0), (-20, 0, 0), [Cube((-1.5, 8, -0.5), (1, 9, 1), stick_px)])
    return [r, mirrored_bone(r, "stick_l")]


item("wave", "Wave", "EMOTE", "REGULAR", 250,
     "A big, cheerful wave hello.",
     emote={"length": 1.6, "loop": True, "parts": {
         "rightArm": [[0, -170, 0, 15], [0.4, -170, 0, -15], [0.8, -170, 0, 15], [1.2, -170, 0, -15], [1.6, -170, 0, 15]],
         "head": [[0, 0, 0, 0], [0.8, 0, 0, 6], [1.6, 0, 0, 0]]}},
     fx={"type": "aura", "shape": "toss", "particle": "soft", "colors": ["#FFE27A", "#FFFFFF"], "rate": 5, "height": 1.95,
         "size": 0.45, "sizeEnd": 0.1, "life": 0.8, "fade": 0.3, "gravity": -0.05},
     view={"gm": True, "pose": {"rightArm": (-168, 0, -18), "head": (0, 0, 6)}, "yaw": -20,   # the arm swung out, mid-wave
           "glow": "#ffd36a",
           "fx_pic": lambda: [((-9.5, -12.5, -3), 3.4, GM_SPARK, None), ((-4.5, -15, -3), 2.4, GM_SPARK, None),
                              ((-12, -7.5, -3), 2.0, GM_SPARK, None)]},
     thumb="emote")
item("drum_solo", "Drum Solo", "EMOTE", "REGULAR", 250,
     "Air drums: alternate hits with a head bob to the beat.",
     drumsticks(),
     emote={"length": 1.0, "loop": True, "parts": {
         "rightArm": [[0, -50, 0, 0], [0.25, -85, 0, 0], [0.5, -50, 0, 0], [0.75, -60, 0, 0], [1.0, -50, 0, 0]],
         "leftArm": [[0, -60, 0, 0], [0.25, -50, 0, 0], [0.5, -60, 0, 0], [0.75, -85, 0, 0], [1.0, -60, 0, 0]],
         "head": [[0, 10, 0, 0], [0.25, -4, 0, 0], [0.5, 10, 0, 0], [0.75, -4, 0, 0], [1.0, 10, 0, 0]]}},
     fx={"type": "aura", "shape": "rise", "particle": "note", "colors": ["#FFFFFF"], "rate": 3, "radius": 0.9, "height": 2.0},
     view={"gm": True, "pose": {"rightArm": (-85, 0, 0), "leftArm": (-50, 0, 0), "head": (-4, 0, 0)}, "yaw": -38, "pitch": 8,
           "glow": "#ff8a5a",
           "fx_pic": lambda: [((-12, -13, -2), 3.6, GM_NOTE, "#ff8a5a"), ((11, -9, -3), 3.0, GM_NOTE, "#ffd23a"),
                              ((-10, -4, 4), 2.6, GM_NOTE, "#6ae0ff")]},
     thumb="emote")
item("salute", "Salute", "EMOTE", "REGULAR", 250,
     "Stand to attention and snap off a sharp salute.",
     emote={"length": 2.0, "loop": False, "parts": {
         "rightArm": [[0, 0, 0, 0], [0.25, -145, 0, 35], [1.5, -145, 0, 35], [2.0, 0, 0, 0]],
         "head": [[0, 0, 0, 0], [0.25, -6, 0, 0], [1.5, -6, 0, 0], [2.0, 0, 0, 0]]}},
     fx={"type": "aura", "shape": "puff", "particle": "soft", "colors": ["#FFFFFF", "#BFE0FF"], "rate": 6, "height": 1.85,
         "size": 0.35, "sizeEnd": 0.05, "life": 0.5, "fade": 0.3, "gravity": 0.0, "from": 0.22, "to": 0.4},
     view={"gm": True, "pose": {"rightArm": (-145, 0, 35), "head": (-6, 0, 0)}, "yaw": -28, "glow": "#7ab8ff",
           "fx_pic": lambda: [((-6.5, -9.5, -5), 3.2, GM_SPARK, None)]},
     thumb="emote")
item("slow_clap", "Slow Clap", "EMOTE", "REGULAR", 250,
     "A long, slow, meaningful round of applause.",
     emote={"length": 1.2, "loop": True, "parts": {
         "rightArm": [[0, -72, 18, 0], [0.5, -72, -22, 0], [0.62, -72, -22, 0], [1.2, -72, 18, 0]],
         "leftArm": [[0, -72, -18, 0], [0.5, -72, 22, 0], [0.62, -72, 22, 0], [1.2, -72, -18, 0]],
         "head": [[0, 4, 0, 0], [0.5, 8, 0, 0], [1.2, 4, 0, 0]]}},
     fx={"type": "aura", "shape": "toss", "particle": "soft", "colors": ["#FFFFFF", "#C8FFD8"], "rate": 4, "height": 1.3,
         "size": 0.35, "sizeEnd": 0.05, "life": 0.6, "fade": 0.3, "gravity": 0.0},
     view={"gm": True, "pose": {"rightArm": (-72, -22, 0), "leftArm": (-72, 22, 0), "head": (8, 0, 0)}, "yaw": -30,
           "glow": "#5ae07a",
           "fx_pic": lambda: [((0.5, 4.5, -11), 3.0, GM_SPARK, None), ((-3.5, 1.5, -12), 1.8, GM_SPARK, None),
                              ((4, 2, -12), 1.6, GM_SPARK, None)]},
     thumb="emote")
item("bow", "Bow", "EMOTE", "REGULAR", 250,
     "A theatrical bow with a flourish of the hand, for when you've earned the applause.",
     emote={"length": 2.4, "loop": False, "parts": {
         "head": [[0, 0, 0, 0], [0.5, 38, 0, 0], [1.8, 38, 0, 0], [2.4, 0, 0, 0]],
         "rightArm": [[0, 0, 0, 0], [0.5, -55, -30, 30], [1.8, -55, -30, 30], [2.4, 0, 0, 0]],
         "leftArm": [[0, 0, 0, 0], [0.5, 25, 0, -55], [1.8, 25, 0, -55], [2.4, 0, 0, 0]]}},
     fx={"type": "aura", "shape": "toss", "particle": "soft", "colors": ["#E0203A", "#FF6A7A"], "rate": 6, "height": 1.2,
         "size": 0.4, "sizeEnd": 0.3, "life": 1.6, "fade": 0.3, "gravity": 0.35, "from": 0.4, "to": 1.6},
     view={"gm": True, "pose": {"head": (38, 0, 0), "rightArm": (-55, -30, 30), "leftArm": (25, 0, -55)}, "yaw": -34,
           "glow": "#ffd36a",
           "fx_pic": lambda: [((-9, -3, -12), 2.4, GM_PETAL, "matte"), ((-4, -9, -14), 2.0, GM_PETAL, "matte"),
                              ((-12, 3, -9), 1.8, GM_PETAL, "matte"), ((2, -6, -15), 1.6, GM_PETAL, "matte")]},
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


def thumb_emote(it, size=THUMB):
    pose = it["view"].get("pose") or emote_key_pose(it["emote"])
    scene = Scene()
    add_mannequin(scene, pose)
    im = render_scene(scene, it["view"].get("yaw", -24), it["view"].get("pitch", 6), size=size)
    return emote_light(im, it["view"].get("glow", "#ffd36a"))


def emote_light(im, color):
    """Lifts a posed figure off the dark card so the pose reads at a glance: the figure brighter, a crisp rim of the
    emote's colour round its silhouette, and a stronger glow behind it."""
    a = np.asarray(im, np.float32) / 255
    m = a[..., 3]
    a[..., :3] = np.where(m[..., None] > 0, np.clip(a[..., :3] * 1.25 + 0.06, 0, 1), a[..., :3])
    k = max(3, im.width // 64 + (1 - im.width // 64 % 2))
    grown = np.asarray(Image.fromarray((m * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(k)), np.float32) / 255
    rim = np.zeros_like(a)
    rim[..., :3] = hexc(color)[:3]
    rim[..., 3] = np.clip(grown - m, 0, 1) * 0.95
    out = badge(Image.new("RGBA", im.size, (0, 0, 0, 0)), color, 0.6)
    out.alpha_composite(to_image(rim, dither=False))
    out.alpha_composite(to_image(a, dither=False))
    return out


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


def thumb_particles(kind, colors, size=THUMB):
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
    out = out.convert("RGBa").resize((size, size), Image.LANCZOS).convert("RGBA")
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


def _palette_png(a, k, dither=0.0):
    """a (h, w, 4 uint8) on the k colours k-means finds for it (alpha weighted), optionally with ordered dithering of
    `dither` levels (soft glow keeps its gradient instead of banding). Returns (bytes, mean rgb error, mean alpha error)."""
    flat = a.reshape(-1, 4).view(np.uint32).ravel()
    cols, inv = np.unique(flat, return_inverse=True)
    if len(cols) > 40000:
        q4 = (a.astype(np.int32) // 4 * 4 + 2).clip(0, 255).astype(np.uint8)
        cols, inv = np.unique(q4.reshape(-1, 4).view(np.uint32).ravel(), return_inverse=True)
    w = np.array([1.0, 1.0, 1.0, 3.0], np.float32)
    X = cols.view(np.uint8).reshape(-1, 4).astype(np.float32) * w
    count = np.bincount(inv.ravel(), minlength=len(cols)).astype(np.float32)
    rng = np.random.default_rng(k)
    sub = rng.choice(len(X), size=min(len(X), 12000), replace=False, p=count / count.sum())
    S = X[sub]
    C = [S[0]]
    d = ((S - S[0]) ** 2).sum(1)
    for _ in range(min(k - 1, len(S) - 1)):
        j = rng.choice(len(S), p=d / d.sum()) if d.sum() > 0 else 0
        C.append(S[j])
        d = np.minimum(d, ((S - S[j]) ** 2).sum(1))
    C = np.array(C, np.float32)

    def nearest(P, c):
        out = np.empty(len(P), np.int64)
        for i in range(0, len(P), 4096):
            out[i:i + 4096] = ((P[i:i + 4096, None, :] - c[None, :, :]) ** 2).sum(-1).argmin(1)
        return out
    for _ in range(8):
        lab = nearest(X, C)
        tot = np.zeros_like(C)
        np.add.at(tot, lab, X * count[:, None])
        n = np.bincount(lab, weights=count, minlength=len(C))[:, None]
        C = np.where(n > 0, tot / np.maximum(n, 1e-6), C).astype(np.float32)
    h, wd = a.shape[:2]
    if dither:
        P = a.reshape(-1, 4).astype(np.float32)
        P[:, :3] += (bayer(h, wd).reshape(-1)[:, None] * dither)
        idx = nearest(P * w, C).reshape(h, wd)
    else:
        idx = nearest(X, C)[inv.reshape(h, wd)]
    pal = np.clip(C / w + 0.5, 0, 255).astype(np.uint8)
    q = Image.fromarray(idx.astype(np.uint8), "P")
    q.putpalette(pal[:, :3].ravel().tolist())
    buf = io.BytesIO()
    q.save(buf, "PNG", optimize=True, transparency=bytes(pal[:, 3].tolist()))
    raw = buf.getvalue()
    back = np.asarray(Image.open(io.BytesIO(raw)).convert("RGBA"), np.float32)
    err = np.abs(back - a.astype(np.float32))
    # the mean hides banding in a smooth gradient: judge the worst 1% of pixels too (as a colour error)
    worst = float(np.percentile(err[..., :3].max(-1)[a[..., 3] > 0], 99)) if (a[..., 3] > 0).any() else 0.0
    return raw, max(float(err[..., :3].mean()), worst / 4), float(err[..., 3].mean())


def strip_png(im, strict=False):
    """A thumbnail strip, or (strict) the website's 512 px still: the smallest encoding that stays visually the same.
    Exact palette when there are at most 256 colours; otherwise k-means palettes of 128-256 colours, kept when the mean
    colour error is under 3 levels of 255 and the worst 1% of pixels under 12 (no banding in soft gradients), else
    full RGBA. Strips keep their old encoding as the fallback; a strip still over 400 KB (a soft rainbow glow no single
    palette holds) takes 256 colours with a light ordered dither instead of banding."""
    a = np.asarray(im.convert("RGBA"))
    if strict:
        best = png_bytes(im)
        if len(np.unique(a.reshape(-1, 4).view(np.uint32))) <= 256:
            return png_bytes(im, indexed=True)
    else:
        best = png_bytes(im, indexed=True, soft_alpha=True)
    if len(best) > 60_000 or strict:
        for k in (128, 192, 256):
            cand, e, ea = _palette_png(a, k)
            if e < 3.0 and ea < 2.0 and len(cand) < len(best):
                best = cand
                break
    if not strict and len(best) > 400_000:
        cand, e, ea = _palette_png(a, 256, dither=7.0)
        if ea < 3.0 and len(cand) < len(best):
            best = cand
    return best


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
    crisp = it["rarity"] == "REGULAR" and it["slot"] not in CRISP_EXEMPT
    if it["bones"]:
        anim = it["animation"]
        tw, th = it["layout"] or pack(it["bones"], MAX_STRIP_PX // (anim.get("scale", 8) * anim["frames"]) if anim else None)
        frames = anim["frames"] if anim else 1
        if anim:
            # special items: at least the regular items' 8 texels per unit (capes 16, laid out by hand to fit)
            scale = anim.get("scale", 8)
            if scale < 8 or max(tw, th) * scale > ANIM_FRAME_PX or th * scale * frames > MAX_STRIP_PX or frames > 64:
                raise SystemExit(f"{it['id']}: {frames} frames of {tw}x{th} at {scale} px per unit exceed the client's limits")
        else:
            # crisp regular items may go up to the client's 1024 px frame (capes: 16 texels per unit)
            scale = scale_for(tw, th, frames, it["scale_cap"], max_px=ANIM_FRAME_PX if crisp else MAX_FRAME_PX)
        texs, ems = [], []
        CRISP[0] = crisp
        try:
            for f in range(frames):
                PHASE[0] = f / frames
                tex, em = paint_model(it["bones"], tw, th, it["item_space"], scale)
                texs.append(tex)
                ems.append(em)
        finally:
            CRISP[0] = False
        PHASE[0] = 0.0
        files["model.json"] = (json.dumps(model_json(it, tw, th, scale), indent=1) + "\n").encode()
        # special and crisp regular items dither their own gradients (crisp bands, see levels() and crispify()): no extra
        # noise, and palette PNGs
        flat = bool(anim) or crisp
        files["texture.png"] = png_bytes(to_image(np.concatenate(texs, 0), dither=not flat), indexed=flat)
        has_em = any(e[..., 3].max() > 0 for e in ems)
        if has_em:
            files["emissive.png"] = png_bytes(to_image(np.concatenate(ems, 0), dither=not flat), indexed=flat, soft_alpha=True)
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
            thumb = thumb_model(it, texs[k], ems[k] if has_em else None, tw, th, size=THUMB_HD if crisp else THUMB)
    elif it["emote"]:
        thumb = thumb_emote(it, size=THUMB_HD if crisp else THUMB)
    else:
        thumb = thumb_particles(it["thumb"], it["fx"]["colors"], size=THUMB_HD if crisp else THUMB)
    strip = thumb.height > thumb.width
    files["thumb.png"] = strip_png(thumb) if strip else png_bytes(thumb, indexed=bool(it["animation"]) or crisp,
                                                                    soft_alpha=True)
    if strip and len(files["thumb.png"]) > 250_000 and thumb.width > 208:
        # a heavy strip (soft glow in every frame): 208 px frames, still sharp on the cards, about a third lighter
        frames = thumb.height // thumb.width
        small = Image.new("RGBA", (208, 208 * frames), (0, 0, 0, 0))
        for k in range(frames):
            small.paste(thumb_model(it, texs[k], ems[k] if has_em else None, tw, th, k / frames, size=208), (0, k * 208))
        cand = strip_png(small)
        if len(cand) < len(files["thumb.png"]):
            files["thumb.png"] = cand
    # the shop's cards are ~500 device px wide: a 512 px still for the website only (site-src), listed in the catalogue
    # as "thumbHd" and not among the item's files, so the client (which checks every listed file against the jar)
    # keeps reading its own bundled thumbnail and never re-downloads
    if not strip:
        if it["bones"]:
            k = it["view"].get("thumb_frame", 0)
            hd = thumb_model(it, texs[k], ems[k] if has_em else None, tw, th, size=THUMB_SITE)
        elif it["emote"]:
            hd = thumb_emote(it, size=THUMB_SITE)
        else:
            hd = thumb_particles(it["thumb"], it["fx"]["colors"], size=THUMB_SITE)
        it["_site_files"] = {"thumb-hd.png": strip_png(hd, strict=True)}
    check_limits(it, files)
    if it["slot"] == "PET":
        gap = check_pet_clearance(it)
        if gap < PET_CLEARANCE:
            raise SystemExit(f"{it['id']}: the pet comes within {gap:.2f} px of the player (needs {PET_CLEARANCE})")
        clash = check_pet_parts(it)
        if clash:
            raise SystemExit(f"{it['id']}: parts of the pet pass through each other: " + "; ".join(clash[:6]))
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
        lift = a.get("lift", 0)
        return (min(-12 if fold else 0, lift, 0), max(lift, 0)), tuple(ys), tuple(sorted((0, side * fold * 0.35))), z, (1, 1)
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


def check_pet_parts(it, states=300, seed=11, inset=0.08):
    """A pet's own parts never pass through each other: in any pose its animations reach, no point of a cube is inside
    (deeper than `inset`) a cube of another bone, unless one bone moves with the other (a joint: a head on its body,
    lids on a head, a tail or wing root in the body); and an ear, horn or tuft (a cube or plane sitting on another cube
    of its own bone) only touches that cube at its base. Returns a list of problems (empty = fine)."""
    rng = np.random.default_rng(seed)
    bones = it["bones"]
    by = {b.name: b for b in bones}

    def chain(name):
        out = []
        while name:
            out.append(name)
            name = by[name].parent
        return out

    def joined(a, b):
        return a in chain(b) or b in chain(a)

    def surface(c, n=5):
        o, s = np.array(c.origin, float), np.array(c.size, float)
        g = np.linspace(0, 1, n)
        ps = []
        for axis in range(3):
            for side in (0, 1):
                u, v = np.meshgrid(g, g)
                q = np.zeros((u.size, 3))
                q[:, axis] = side
                q[:, (axis + 1) % 3] = u.ravel()
                q[:, (axis + 2) % 3] = v.ravel()
                ps.append(o + q * s)
        return np.unique(np.round(np.concatenate(ps), 4), axis=0)

    def inside(p, c):
        lo = np.array(c.origin, float) + inset
        hi = np.array(c.origin, float) + np.array(c.size, float) - inset
        return np.all((p > lo) & (p < hi), axis=-1)

    problems = set()
    # within a bone: thin parts (ears, horns, tufts: planes or cubes no more than 1 unit thick on some side) stay out of
    # the cubes they sit on (legs tucked into a body are fine: they are not thin plates)
    for b in bones:
        for i, c in enumerate(b.cubes):
            if min(c.size) > 0:
                continue
            P = surface(c)
            for j, d in enumerate(b.cubes):
                if i != j and min(d.size) > 0 and inside(P, d).any():
                    problems.add(f"{b.name}: a flat part ({c.origin}) cuts into {d.origin}")
    ranges = {b.name: _anim_ranges(b) for b in bones}

    def pick(lo_hi):
        lo, hi = lo_hi
        r = rng.random()
        return lo if r < 0.3 else hi if r < 0.6 else lo + (hi - lo) * rng.random()

    pts = {b.name: [surface(c) for c in b.cubes] for b in bones}
    pairs = [(a, b) for a in bones for b in bones if a is not b and not joined(a.name, b.name)]
    for k in range(states):
        M = {}
        for b in bones:
            (p0, p1), (y0, y1), (r0, r1), d, sc = ranges[b.name]
            if k == 0:
                pitch, yaw, roll, dy = b.rotation[0], b.rotation[1], b.rotation[2], 0.0     # at rest first
            else:
                pitch, yaw, roll, dy = b.rotation[0] + pick((p0, p1)), b.rotation[1] + pick((y0, y1)), b.rotation[2] + pick((r0, r1)), pick(d)
            R = _rz(math.radians(roll)) @ _ry(math.radians(yaw)) @ _rx(math.radians(pitch))
            pv = np.array(b.pivot, float)
            T = np.eye(4)
            T[:3, :3] = R
            T[:3, 3] = pv + np.array([0, dy, 0]) - R @ pv
            M[b.name] = T if not b.parent else M[b.parent] @ T
        for a, b in pairs:
            rel = np.linalg.inv(M[b.name]) @ M[a.name]          # a's space -> b's space
            for ca, P in zip(a.cubes, pts[a.name]):
                Q = P @ rel[:3, :3].T + rel[:3, 3]
                for cb in b.cubes:
                    if min(cb.size) > 0 and inside(Q, cb).any():
                        problems.add(f"{a.name} {ca.origin} goes into {b.name} {cb.origin}" + (" at rest" if k == 0 else ""))
    return sorted(problems)


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
        site_files = it.get("_site_files") or {}
        for name, data in site_files.items():
            (OUT_SITE / it["id"] / name).write_bytes(data)
        index["items"].append({
            "id": it["id"], "name": it["name"], "slot": it["slot"], "rarity": it["rarity"].lower(),
            "priceCents": it["price"], "currency": "USD", "partner": it["partner"], "colourable": it["colourable"],
            "defaultColor": it["default_color"], "description": it["desc"],
            "assets": {"files": {name: sha256(data) for name, data in files.items()}}}
            | ({"thumbHd": {"file": "thumb-hd.png", "size": THUMB_SITE, "sha256": sha256(site_files["thumb-hd.png"])}}
               if "thumb-hd.png" in site_files else {}))
    data = (json.dumps(index, indent=1) + "\n").encode()
    for out in (OUT_RES, OUT_SITE):
        stale = out / "index.json"
        if stale.exists():
            stale.unlink()
        (out / "catalog.json").write_bytes(data)
    wardrobe_icon()
    print(f"{len(built)} cosmetics -> {OUT_RES.relative_to(ROOT)} and {OUT_SITE.relative_to(ROOT)}", file=sys.stderr)


# ============================================================================================== mockup collection
# The owner's mockup designs that made the cut (29 of 55: no rainbow-striped designs and nothing that repeats an idea
# already in the catalogue), drawn to the shipped standard: regular capes hand-drawn pixel art at 16 texels per unit in the
# special capes' layout, special items 16-29 seamless frames with a glow layer, pets on the pet engine (looking about,
# blinking, cleared against the player). Everything below is self-contained: it only adds
# helpers (all named mc_* or after their item) and items, and extends the particle thumbnails with new pictures.

MC_SPECIAL = {"frames": 16, "frametime": 2, "interpolate": True, "scale": 8}


def mc_pal(cols):
    return np.stack([hexc(c)[:3] if isinstance(c, str) else np.asarray(c, np.float32)[:3] for c in cols]).astype(np.float32)


def mc_crisp(cv, cols, edge=0.12, em_cols=None, alpha=True):
    """Snaps a painted face to a fixed palette (the same in every frame, so animations don't shimmer): each texel takes
    its nearest palette colour, ordered dithering only right where it sits between two; alpha in crisp steps; the glow
    layer in four steps, its colours snapped to `em_cols` (default the same palette)."""
    a, h, w = cv.arr, cv.h, cv.w
    if alpha:
        al = np.clip(a[..., 3], 0, 1)
        al = np.where(al > 0.06, levels(al, 9, 0.3) / 8.0, 0.0)
        a[..., 3] = al
    m = a[..., 3] > 0
    if m.any():
        C = mc_pal(cols)
        X = np.clip(a[m][:, :3], 0, 1)
        d = ((X[:, None, :] - C[None]) ** 2).sum(-1)
        o = np.argsort(d, 1)
        i1, i2 = o[:, 0], o[:, 1] if len(C) > 1 else o[:, 0]
        d1 = np.sqrt(np.take_along_axis(d, i1[:, None], 1)[:, 0])
        d2 = np.sqrt(np.take_along_axis(d, i2[:, None], 1)[:, 0])
        f = d1 / np.maximum(d1 + d2, 1e-6)
        p2 = np.clip((f - (0.5 - edge)) / max(edge, 1e-3), 0, 1) * 0.5
        pick = p2 > bayer(h, w)[m] + 0.5
        a[m, :3] = np.where(pick[:, None], C[i2], C[i1])
    e = cv.em
    e[~m] = 0
    ea = np.clip(e[..., 3], 0, 1)
    if ea.max() > 0:
        lv = levels(ea, 5, 0.3).astype(np.float32) / 4.0
        e[..., 3] = lv
        em = lv > 0
        e[~em] = 0
        if em.any():
            C = mc_pal(em_cols or cols)
            Y = np.clip(e[em][:, :3] / np.maximum(ea[em][:, None], 1e-3) if False else e[em][:, :3], 0, 1)
            e[em, :3] = C[((Y[:, None, :] - C[None]) ** 2).sum(-1).argmin(1)]


def mc_lines(cv, polylines, width, close=False):
    """A crisp (aliased) mask of polylines in face fractions (0..1), `width` texels wide."""
    def fn(d):
        for pts in polylines:
            P = [(x * cv.w, y * cv.h) for x, y in pts]
            d.line(P, fill=255, width=max(1, int(round(width))), joint="curve")
            r = width / 2
            for x, y in P:
                d.ellipse((x - r, y - r, x + r, y + r), fill=255)
    return pmask(cv, fn)


def mc_poly(cv, pts):
    return pmask(cv, lambda d: d.polygon([(x * cv.w, y * cv.h) for x, y in pts], fill=255))


def mc_cape_trim(cv, color, light, stitch=None, hem=True):
    hd_trim(cv, cape_top(cv), cv.s // 3 if hem else 0, color, light, stitch=stitch)


# ---------------------------------------------------------------------------------------------- capes (regular)

# ---- Honeycomb Cape: golden wax cells, some full of honey and some empty, honey dripping from the collar

HONEY_PAL = ["#2a1402", "#4a2404", "#6a3606", "#8a4a0a", "#b8680c", "#e09018", "#ffb81a", "#ffd04a", "#ffe68a",
             "#fff6c8", "#5a2a04", "#3a1a04"]


def honeycomb_outer(cv):
    h, w, s = cv.h, cv.w, cv.s
    y, x = cv.grid()
    X, Y = x + 0.5, y + 0.5
    R = w / 5.0 / 1.732 * 1.0                      # hex radius: five cells across
    # axial hex coordinates (pointy top)
    q = (X * math.sqrt(3) / 3 - Y / 3) / R
    r = (Y * 2 / 3) / R
    cx, cz = q, r
    cy = -cx - cz
    rx, ry, rz = np.round(cx), np.round(cy), np.round(cz)
    dx, dy, dz = np.abs(rx - cx), np.abs(ry - cy), np.abs(rz - cz)
    fx = (dx > dy) & (dx > dz)
    fz = ~fx & (dy <= dz)
    rx = np.where(fx, -ry - rz, rx)
    rz = np.where(fz, -rx - ry, rz)
    # centre of the cell and distance to its edge (hex metric)
    ccx = R * math.sqrt(3) * (rx + rz / 2)
    ccy = R * 1.5 * rz
    ux, uy = (X - ccx) / R, (Y - ccy) / R
    hexd = np.maximum(np.abs(ux), 0.5 * np.abs(ux) + 0.866 * np.abs(uy)) / 0.866     # 1 on the cell's edge
    cell_id = (rx * 7 + rz * 13).astype(np.int64)
    rng = np.random.default_rng(4)
    fullness = rng.random(4096)
    f = fullness[(cell_id % 4096)]
    full = f > 0.32
    wall = hexd > 0.78
    wall_hi = (hexd > 0.78) & (hexd < 0.87) & (uy < 0)
    # honey cells: a glossy dome lit from the top left, darker towards the bottom right
    shade_v = np.clip(0.75 - (ux * 0.35 + uy * 0.55) * 0.8 - hexd * 0.25, 0, 1)
    honey = palette(levels(shade_v, 6, 0.35), ["#8a4a0a", "#b8680c", "#e09018", "#ffb81a", "#ffd04a", "#ffe68a"])
    empty = palette(levels(np.clip(0.55 - hexd * 0.5 + uy * 0.25, 0, 1), 4, 0.35), ["#2a1402", "#4a2404", "#6a3606", "#8a4a0a"])
    col = np.where(full[..., None], honey, empty)
    shine = full & (((ux + 0.32) ** 2 + (uy + 0.36) ** 2) < 0.022)
    col[shine] = hexc("#fff6c8")
    col[wall] = hexc("#e09018")
    col[wall_hi] = hexc("#ffd04a")
    col[(hexd > 0.95)] = hexc("#6a3606")
    cv.arr[:] = col
    cv.arr[..., 3] = 1
    em_from(cv, (full & ~wall).astype(np.float32) * 0.18, "#ffb81a")
    # honey dripping from the collar
    top = cape_top(cv)
    drip = np.zeros((h, w), bool)
    for x0, ln, wd in ((0.12, 0.14, 0.8), (0.31, 0.07, 0.6), (0.47, 0.24, 0.9), (0.66, 0.1, 0.7), (0.84, 0.18, 0.8)):
        xc = x0 * w
        L = top + ln * h
        ww = wd * s * 0.5
        stem = (np.abs(X - xc) < ww * (0.7 + 0.3 * smooth((L - Y) / (s * 2)))) & (Y < L)
        bulb = ((X - xc) / (ww * 1.45)) ** 2 + ((Y - L) / (ww * 1.6)) ** 2 < 1
        drip |= stem | bulb
    drip |= Y < top + s * 0.45
    sprite(cv, drip, "#ffb81a", line="#5a2a04", hi="#ffe68a", lo="#c87a10", e=2)
    hl = drip & (np.abs(X - np.round(X / (w / 5.0)) * (w / 5.0)) < 1)
    em_from(cv, drip * 0.35, "#ffc23a")
    mc_crisp(cv, HONEY_PAL)
    mc_cape_trim(cv, "#5a2a04", "#b8680c", stitch="#ffd04a")


item("honeycomb_cape", "Honeycomb Cape", "CAPE", "REGULAR", 0,
     "Golden honeycomb cells, some full and some empty, with honey dripping from the collar.",
     cape_bones_reg(honeycomb_outer, lining_hd("#5a2a04", "#2a1402", "#8a4a0a")), layout=CAPE_REG, scale_cap=16, view=CAPE_VIEW)


# ---- Royal Ermine Cape: crimson velvet, gold embroidery, a jewelled crown crest and a white ermine hem

ERMINE_PAL = ["#3a020a", "#5a0612", "#7a0a1a", "#980e22", "#b0142a", "#c8203a", "#7a5a12", "#b8861e", "#f0c24a",
              "#ffe89a", "#ffffff", "#ece8e0", "#d0cac0", "#a8a298", "#111111", "#3a8aff", "#e11d2e", "#3ac86a", "#cfe0ff"]


def ermine_outer(cv):
    h, w, s = cv.h, cv.w, cv.s
    y, x = cv.grid()
    X, Y = x + 0.5, y + 0.5
    t, u = cv.yy(), cv.xx()
    fold = 0.5 + 0.5 * np.sin(u * math.tau * 2.5 + 0.6)
    v = np.clip(0.85 - t * 0.55 + (fold - 0.5) * 0.25, 0, 1)
    cv.arr[:] = palette(levels(v, 6, 0.4), ["#3a020a", "#5a0612", "#7a0a1a", "#980e22", "#b0142a", "#c8203a"])
    cv.arr[..., 3] = 1
    # a faint damask diamond pattern woven into the velvet
    dm = ((np.abs(((X / s) % 2) - 1) + np.abs(((Y / s) % 2) - 1)) - 0.9) ** 2 < 0.005
    cv.arr[dm, :3] *= 1.25
    # gold embroidery down both sides: a cord with diamonds and little leaves
    gold = np.zeros((h, w), bool)
    for xc in (s * 1.0, w - s * 1.0):
        gold |= (np.abs(X - xc) < s * 0.14) & (Y > s * 1.2) & (Y < h * 0.8)
        for yc in np.arange(s * 2.0, h * 0.79, s * 1.4):
            gold |= (np.abs(X - xc) / (s * 0.42) + np.abs(Y - yc) / (s * 0.55)) < 1
            gold |= ((X - xc - s * 0.42) ** 2 + (Y - yc - s * 0.7) ** 2 < (s * 0.17) ** 2)
            gold |= ((X - xc + s * 0.42) ** 2 + (Y - yc - s * 0.7) ** 2 < (s * 0.17) ** 2)
    sprite(cv, gold, "#f0c24a", line="#3a020a", hi="#ffe89a", lo="#b8861e", e=1)
    # the crown crest
    cx, cy, r = w / 2, h * 0.38, s * 2.4
    crown = mc_poly(cv, [((cx - r) / w, (cy + r * 0.55) / h), ((cx - r) / w, (cy - r * 0.45) / h), ((cx - r * 0.5) / w, (cy - r * 0.02) / h),
                         (cx / w, (cy - r * 0.85) / h), ((cx + r * 0.5) / w, (cy - r * 0.02) / h), ((cx + r) / w, (cy - r * 0.45) / h),
                         ((cx + r) / w, (cy + r * 0.55) / h)])
    sprite(cv, crown, "#f0c24a", line="#3a020a", hi="#ffe89a", lo="#b8861e", e=2)
    band = crown & (Y > cy + r * 0.25)
    put(cv, band, "#b8861e")
    put(cv, band & (Y < cy + r * 0.25 + 2), "#ffe89a")
    for px_ in (cx - r, cx, cx + r):
        tip_y = cy - (r * 0.85 if px_ == cx else r * 0.45)
        put(cv, disc(cv, px_, tip_y - s * 0.1, s * 0.24), "#ffe89a")
    for gx, c in ((cx - r * 0.55, "#3a8aff"), (cx, "#e11d2e"), (cx + r * 0.55, "#3ac86a")):
        g = disc(cv, gx, cy + r * 0.4, s * 0.3)
        sprite(cv, g, c, line="#7a5a12")
        put(cv, disc(cv, gx - s * 0.1, cy + r * 0.4 - s * 0.1, s * 0.1), "#ffffff", 0.8)
    # the ermine hem: white fur in tufts, black tails in two staggered rows
    hem_top = h * 0.8
    wav = hem_top + s * 0.25 * np.sin(X / s * math.pi * 1.3)
    fur = Y > wav
    tone = levels(np.clip(1 - (Y - hem_top) / (h - hem_top) * 0.6 + (pnoise(h, w, 6.0, 23, octaves=2) - 0.5) * 0.5, 0, 1), 4, 0.4)
    col = palette(tone, ["#a8a298", "#d0cac0", "#ece8e0", "#ffffff"])
    cv.arr[fur] = col[fur]
    put(cv, grow(fur) & ~fur, "#d0cac0")
    for i, x0 in enumerate(np.linspace(0.1, 0.9, 6)):
        y0 = hem_top + s * (1.2 + (i % 2) * 1.1)
        xc = x0 * w
        tail = (np.abs(X - xc) < s * 0.26 * np.clip((Y - y0 + s * 0.5) / (s * 0.9), 0, 1)) & (Y > y0 - s * 0.5) & (Y < y0 + s * 0.5)
        tail |= (np.abs(X - xc) < s * 0.12) & (Y >= y0 - s * 0.9) & (Y < y0)
        put(cv, tail, "#111111")
    mc_crisp(cv, ERMINE_PAL)
    hd_trim(cv, cape_top(cv), 0, "#b8861e", "#ffe89a", stitch="#ffffff")


item("royal_ermine_cape", "Royal Ermine Cape", "CAPE", "REGULAR", 0,
     "Crimson velvet with gold embroidery, a jewelled crown crest and a white ermine hem.",
     cape_bones_reg(ermine_outer, lining_hd("#5a0612", "#2a0208", "#f0c24a")), layout=CAPE_REG, scale_cap=16, view=CAPE_VIEW)


# ---- Pixel Camo Cape (colourable): blocky four-tone camouflage in light greys so the wearer's colour shows true

CAMO_PAL = ["#5a5a5a", "#8c8c8c", "#bcbcbc", "#ececec", "#d8d8d8", "#a8a8a8", "#f8f8f8", "#3c3c3c"]


def camo_outer(cv):
    h, w, s = cv.h, cv.w, cv.s
    y, x = cv.grid()
    hb = s // 2                                     # half-unit blocks
    by, bx = (y // hb).astype(int), (x // hb).astype(int)
    a = pnoise(h // hb + 1, w // hb + 1, 2.2, 41, octaves=2)
    b = pnoise(h // hb + 1, w // hb + 1, 3.6, 42, octaves=2)
    A, B = a[by, bx], b[by, bx]
    lvl = np.select([A > 0.6, B > 0.58, A < 0.36], [0, 1, 3], 2)
    cv.arr[:] = palette(lvl, ["#5a5a5a", "#8c8c8c", "#ececec", "#bcbcbc"])
    cv.arr[..., 3] = 1
    # each block lit along its top-left edge where it borders a lighter one: a little relief
    edge = (lvl != np.roll(lvl, 1, 0)) | (lvl != np.roll(lvl, 1, 1))
    cv.arr[edge & (lvl == 0), :3] = hexc("#3c3c3c")[:3]
    mc_crisp(cv, CAMO_PAL)
    # ripstop: the fabric's reinforcing grid, a darker thread every unit both ways, with a lit thread beside it
    rip = ((x % s) == 0) | ((y % s) == 0)
    cv.arr[rip, :3] *= 0.86
    lit = (((x % s) == 1) | ((y % s) == 1)) & ~rip
    cv.arr[lit, :3] = np.clip(cv.arr[lit, :3] * 1.06, 0, 1)
    hd_trim(cv, cape_top(cv), s // 3, "#d8d8d8", "#f8f8f8", stitch="#a8a8a8")


item("pixel_camo_cape", "Pixel Camo Cape", "CAPE", "REGULAR", 0,
     "Blocky camouflage in any colour you like.",
     cape_bones_reg(camo_outer, lining_hd("#a4a4a4", "#7c7c7c", "#c8c8c8"), tint=True), layout=CAPE_REG, scale_cap=16,
     colourable=True, default_color="#6E8A4A", view=CAPE_VIEW)


# ---------------------------------------------------------------------------------------------- capes (special, animated)

def mc_cape_anim(frametime=2, interpolate=False):
    return dict(CAPE_HD_FX, frametime=frametime, interpolate=interpolate)


# ---- Code Rain Cape: columns of glyphs stream down; each column's head is white-hot, its trail fades through greens

CODE_GLYPHS = [
    ["111", "101", "101", "101", "111"], ["010", "110", "010", "010", "111"], ["111", "001", "111", "100", "111"],
    ["101", "101", "111", "001", "001"], ["111", "100", "111", "001", "111"], ["110", "001", "010", "100", "111"],
    ["111", "010", "010", "010", "010"], ["101", "010", "101", "010", "101"], ["011", "100", "110", "100", "011"],
    ["100", "110", "101", "110", "100"], ["111", "101", "111", "101", "101"], ["010", "111", "010", "111", "010"],
]
CODE_PAL = ["#010604", "#02100a", "#063a18", "#0a6a26", "#12a03a", "#2ad85a", "#7affa0", "#e8fff0", "#0a1a0e", "#3aff6a",
            "#1a3a22", "#0a3a1a"]


def code_rain_outer(cv):
    h, w, s = cv.h, cv.w, cv.s
    ph = cv.phase
    cv.arr[:] = hexc("#010604")
    cv.arr[..., 3] = 1
    cell = s // 2                                   # one glyph cell: 4 x 6 texels + gaps
    gw, gh = 3, 3                                   # texels per glyph pixel
    cw, chh = 4 * gw, 6 * gh
    cols, rows = w // cw, h // chh
    rng = np.random.default_rng(55)
    glyph = rng.integers(0, len(CODE_GLYPHS), (rows, cols))
    flip = rng.integers(1, 4, (rows, cols))       # how many times a cell changes its glyph per loop
    speed = rng.integers(1, 3, cols) * np.where(rng.random(cols) < 0.5, 1, 2)
    start = rng.random(cols)
    trail = 0.28 + rng.random(cols) * 0.35
    ox = (w - cols * cw) // 2
    for c in range(cols):
        for k in range(2 if speed[c] == 1 else 1):
            head = (start[c] + k * 0.5 + ph * speed[c]) % 1.0
            for r in range(rows):
                pos = (r + 0.5) / rows
                d = (head - pos) % 1.0
                if d > trail[c]:
                    continue
                b = 1 - d / trail[c]
                g = CODE_GLYPHS[(glyph[r, c] + int(ph * flip[r, c] * 4)) % len(CODE_GLYPHS)]
                lv = 7 if d < 0.6 / rows else int(2 + b * 3.4)
                colr = hexc(CODE_PAL[min(lv, 7)])
                for gy in range(5):
                    for gx in range(3):
                        if g[gy][gx] != "1":
                            continue
                        y0 = r * chh + gy * gh + 1
                        x0 = ox + c * cw + gx * gw + 1
                        cv.arr[y0:y0 + gh, x0:x0 + gw, :3] = colr[:3]
                        cv.em[y0:y0 + gh, x0:x0 + gw] = (colr[0], colr[1], colr[2], 0.45 + 0.55 * b)
    mc_crisp(cv, CODE_PAL, alpha=False)
    mc_cape_trim(cv, "#0a1a0e", "#1a3a22", stitch="#3aff6a")


item("code_rain_cape", "Code Rain Cape", "CAPE", "SPECIAL", 0,
     "Columns of glowing green code stream down the cape and never stop.",
     cape_bones_hd(code_rain_outer, lining_hd("#03100a", "#010402", "#0a3a1a")), layout=CAPE_HD, view=CAPE_VIEW,
     animation=mc_cape_anim(2, False))


# ---- Warp Speed Cape: stars stream outward from a violet core, longer and brighter as they near the edges

WARP_PAL = ["#0a0a22", "#120e36", "#1c1650", "#2c1e72", "#40289a", "#6040c8", "#a88aff", "#d8ccff", "#ffffff", "#9ad8ff",
            "#c8a8ff", "#0a0820", "#5a7ad8"]


def warp_outer(cv):
    h, w, s = cv.h, cv.w, cv.s
    ph = cv.phase
    t, u = cv.yy(), cv.xx()
    cx, cy = w * 0.5, h * 0.45
    y, x = cv.grid()
    rr = np.sqrt(((x + 0.5 - cx) / w) ** 2 + ((y + 0.5 - cy) / w) ** 2)
    cv.arr[:] = palette(levels(np.clip(1.15 - rr * 1.1, 0, 1), 6, 0.45), WARP_PAL[:6])     # a deep violet tunnel, not black
    cv.arr[..., 3] = 1
    core = np.clip(1 - rr / 0.12, 0, 1)
    put(cv, core > 0.45, "#a88aff", 0.9)
    put(cv, core > 0.75, "#d8ccff", 1.0)
    rng = np.random.default_rng(77)
    streak = np.zeros((h, w), np.float32)
    cols = np.zeros((h, w), np.int32)
    L = max(w, h) * 0.85
    for i in range(90):
        a = rng.random() * math.tau
        r0 = rng.random()
        rate = 1 + (i % 3 == 0)
        r = (r0 + ph * rate) % 1.0
        dist = (r ** 1.7) * L
        ln = (0.03 + r * 0.22) * L * 0.45
        x1, y1 = cx + math.cos(a) * dist, cy + math.sin(a) * dist
        x0, y0 = cx + math.cos(a) * max(0, dist - ln), cy + math.sin(a) * max(0, dist - ln)
        wd = 1 if r < 0.45 else 2 if r < 0.8 else 3
        m = pmask(cv, lambda d: d.line((x0, y0, x1, y1), fill=255, width=wd))
        b = min(1.0, r * 3.0)
        streak = np.where(m, np.maximum(streak, b), streak)
        cols = np.where(m, i % 3, cols)
    lv = levels(streak, 4, 0.3)
    for k, c in ((1, ["#5a7ad8", "#6a5ab8", "#5a7ad8"]), (2, ["#9ad8ff", "#c8a8ff", "#d8ccff"]), (3, ["#ffffff", "#ffffff", "#ffffff"])):
        for j in range(3):
            put(cv, (lv == k) & (cols == j), c[j], 0.4 + 0.2 * k)
    mc_crisp(cv, WARP_PAL, alpha=False)
    mc_cape_trim(cv, "#0a0820", "#2a1a6a", stitch="#9ad8ff")


item("warp_speed_cape", "Warp Speed Cape", "CAPE", "SPECIAL", 0,
     "Stars stream past from a violet core, like jumping to lightspeed.",
     cape_bones_hd(warp_outer, lining_hd("#0a0820", "#03030a", "#2a1a6a")), layout=CAPE_HD, view=CAPE_VIEW,
     animation=mc_cape_anim(2, False))


# ---- Bass Drop Cape: a neon equalizer, LED segments from green through yellow to red, peaks that hang then fall

EQ_PAL = ["#06040c", "#0c0816", "#14101e", "#1e1830", "#2a2240", "#3aff8a", "#8aff5a", "#ffe23a", "#ffa03a", "#ff3a4a",
          "#ff3aa0", "#ffffff", "#123a22", "#3a3a12", "#3a1218"]


def equalizer_outer(cv):
    h, w, s = cv.h, cv.w, cv.s
    ph = cv.phase
    t = cv.yy()
    y, x = cv.grid()
    cv.arr[:] = palette(levels(t, 3, 0.5), ["#14101e", "#0c0816", "#06040c"])
    cv.arr[..., 3] = 1
    grid_ = ((x % (s // 2)) == 0) | ((y % (s // 2)) == 0)
    cv.arr[grid_, :3] = hexc("#1e1830")[:3]
    rng = np.random.default_rng(3)
    bars = 8
    bw = w / bars
    seg = s // 2
    floor_ = h - s * 1.0
    nseg = int((floor_ - cape_top(cv) - s * 0.8) // seg)
    for i in range(bars):
        k1, k2 = int(rng.integers(1, 4)), int(rng.integers(2, 5))
        p1, p2 = rng.random(), rng.random()
        beat = abs(math.sin(math.pi * (ph * 4 + p1)))       # four beats per loop
        lvl = 0.15 + 0.82 * beat ** 1.2 * (0.65 + 0.35 * abs(math.sin(math.tau * (ph * k1 + p2))))
        lit = int(lvl * nseg)
        # the peak: the highest level over the last quarter beat, falling slowly
        peak = max(0.15 + 0.82 * abs(math.sin(math.pi * ((ph - d) * 4 + p1))) ** 1.2 *
                   (0.65 + 0.35 * abs(math.sin(math.tau * ((ph - d) * k1 + p2)))) - d * 2.2 for d in np.linspace(0, 0.12, 7))
        pk = min(nseg - 1, int(peak * nseg) + 1)
        x0, x1 = int(i * bw + bw * 0.14), int((i + 1) * bw - bw * 0.14)
        for j in range(nseg):
            y1 = int(floor_ - j * seg)
            y0 = y1 - seg + 2
            f = j / max(1, nseg - 1)
            c = "#3aff8a" if f < 0.4 else "#8aff5a" if f < 0.55 else "#ffe23a" if f < 0.7 else "#ffa03a" if f < 0.85 else "#ff3a4a"
            off = "#123a22" if f < 0.55 else "#3a3a12" if f < 0.85 else "#3a1218"
            sl = (slice(y0, y1), slice(x0, x1))
            if j < lit:
                cv.arr[sl][..., :3] = hexc(c)[:3]
                cv.em[sl] = np.append(hexc(c)[:3], 0.9)
                cv.arr[y0, x0:x1, :3] = np.clip(hexc(c)[:3] * 1.0 + 0.35, 0, 1)
            elif j == pk:
                cv.arr[sl][..., :3] = hexc("#ff3aa0")[:3]
                cv.em[sl] = np.append(hexc("#ff3aa0")[:3], 1.0)
            else:
                cv.arr[sl][..., :3] = hexc(off)[:3]
    # a neon baseline under the bars
    put(cv, (y >= floor_ + 2) & (y < floor_ + 4), "#ff3aa0", 1.0)
    mc_crisp(cv, EQ_PAL, alpha=False)
    mc_cape_trim(cv, "#1e1830", "#2a2240", stitch="#ff3aa0")


item("bass_drop_cape", "Bass Drop Cape", "CAPE", "SPECIAL", 0,
     "A neon equalizer that bounces to a beat on your back.",
     cape_bones_hd(equalizer_outer, lining_hd("#14101e", "#06040c", "#ff3aa0")), layout=CAPE_HD, view=CAPE_VIEW,
     animation=mc_cape_anim(2, False))


# ---------------------------------------------------------------------------------------------- wings

# ---- Dusk Moth Wings: soft dusty upper and lower wings in cream and bark brown, an eye-spot on each, a furry body

MOTH_UP = [(1.0, 0.85), (0.98, 0.1), (0.7, 0.0), (0.3, 0.02), (0.05, 0.1), (0.0, 0.3), (0.12, 0.6), (0.5, 0.9), (0.85, 1.0)]
MOTH_LO = [(1.0, 0.0), (0.4, 0.0), (0.08, 0.2), (0.0, 0.5), (0.15, 0.85), (0.45, 1.0), (0.8, 0.8), (1.0, 0.4)]


def moth_wing(upper):
    def p(face, cv):
        r, t = wing_root(face, cv), cv.yy()
        W = WingDraw(face, cv)
        W.poly(MOTH_UP if upper else MOTH_LO, (255, 255, 255, 255))
        mask = crisp_mask(W)
        n = pnoise(cv.h, cv.w, 3.0, 88 if upper else 89, octaves=4)
        col = ramp(np.clip((1 - r) * 0.7 + n * 0.4, 0, 1), [(0, "#f0e0bc"), (0.45, "#c8a474"), (0.75, "#8a6640"), (1, "#4a3420")])
        bnd = np.sin((1 - r) * 16 + n * 4) > 0.8
        col[bnd, :3] = hexc("#5a3e24")[:3]
        # a pale line across, like a dusk moth's
        line = np.abs((1 - r) - (0.42 if upper else 0.5) - (t - 0.5) * 0.3) < 0.025
        col[line, :3] = hexc("#f8ecd0")[:3]
        er, et = (0.48, 0.45) if upper else (0.42, 0.52)
        x0 = (er if face == "north" else 1 - er) * cv.w
        y0 = et * cv.h
        y, x = cv.grid()
        d = np.sqrt(((x + 0.5 - x0) / cv.w) ** 2 + ((y + 0.5 - y0) / cv.h * cv.h / cv.w * 0.72) ** 2)
        for rad, c in ((0.19, "#2a1a10"), (0.14, "#f2c45a"), (0.085, "#1e140c"), (0.035, "#ffffff")):
            col[d < rad, :3] = hexc(c)[:3]
        mb = mask > 0
        edge = mb & ~(nshift(mb, 1, 0) & nshift(mb, -1, 0) & nshift(mb, 0, 1) & nshift(mb, 0, -1))
        col[edge, :3] = hexc("#3a2614")[:3]
        col[..., 3] = mask
        cv.arr[:] = col
    return p


def moth_body(face, cv):
    vgrad(cv, hexc("#9a7a52"), hexc("#4a3420"))
    y, x = cv.grid()
    cv.arr[(y // max(1, cv.s // 2)) % 2 == 1, :3] *= 0.75
    bevel(cv, 0.2, 0.25)


def moth():
    anim = wing_anim(24, 18, 1.8)
    right = [Bone("lobes_r", "body", (-1, 3, 4.0), (0, 0, -6), [      # on a thorax standing out, clear of the head
        Cube((-12, -4, 4.3), (11, 9, 0), moth_wing(True)), Cube((-9, 5, 4.35), (8, 7, 0), moth_wing(False))], anim=anim)]
    body = Bone("thorax", "body", (0, 3, 2.5), cubes=[Cube((-1, 0, 2), (2, 8, 3), moth_body)])
    return wing_pair(right) + [body]


item("dusk_moth_wings", "Dusk Moth Wings", "WINGS", "REGULAR", 700,
     "Soft, dusty moth wings in cream and bark brown, with a golden eye-spot on each.",
     moth(), view=INSECT_VIEW)


# ---------------------------------------------------------------------------------------------- hats

# ---- Pro Headset: a padded band, big cups with glowing red rings, and a boom mic by your mouth

def hs_shell(face, cv):
    vgrad(cv, hexc("#5c5c6a"), hexc("#1e1e26"))           # matte graphite, light enough to read on a dark card
    y, x = cv.grid()
    cv.arr[(y < max(1, cv.s // 8)), :3] = hexc("#8a8a9a")[:3]
    bevel(cv, 0.2, 0.3)


def hs_cup(face, cv):
    hs_shell(face, cv)
    if face in ("east", "west"):
        y, x = cv.grid()
        r = np.sqrt((x + 0.5 - cv.w / 2) ** 2 + (y + 0.5 - cv.h / 2) ** 2) / (cv.w / 2)
        ring = (r > 0.55) & (r < 0.78)
        cv.arr[ring, :3] = hexc("#ff2a3a")[:3]
        cv.arr[(r > 0.78) & (r < 0.86), :3] = hexc("#5a0a12")[:3]
        logo = (r < 0.22)
        cv.arr[logo, :3] = hexc("#ff6a7a")[:3]
        em_from(cv, (ring | logo).astype(np.float32), "#ff2a3a")


def hs_pad(face, cv):
    """Leatherette padding: quilted in diamonds (lit crowns, dark seams), lit top-left and shaded bottom-right."""
    cv.hand = True
    y, x = cv.grid()
    k = max(4, cv.s // 2)
    a = np.abs(((x + y) % k) - k / 2)
    b = np.abs(((x - y) % k) - k / 2)
    puff = np.minimum(a, b)
    lv = np.where(puff < 1, 0, np.where(puff < k / 4, 1, 2))
    e = max(1, cv.s // 8)
    lv = np.where((y < e) | (x < e), 3, lv)
    lv = np.where((y >= cv.h - e) | (x >= cv.w - e), 0, lv)
    cv.arr[:] = palette(lv, ["#0e0e12", "#1e1e26", "#2c2c36", "#44444f"])
    cv.arr[..., 3] = 1


def hs_mic(face, cv):
    """A foam windscreen: dark foam in a fine checked weave, lit top-left, with a glowing red ring round its tip (the
    mic is live) and a red light on its end."""
    cv.hand = True
    y, x = cv.grid()
    n = hash_noise(cv.h, cv.w, 77)
    lv = np.where((x + y) % 2 == 0, 1, 0) + (n > 0.8)
    e = max(1, cv.s // 8)
    lv = np.where((y < e) | (x < e), 3, lv)
    lv = np.where((y >= cv.h - e) | (x >= cv.w - e), 0, lv)
    cv.arr[:] = palette(lv, ["#16161c", "#2a2a32", "#3e3e48", "#5a5a66"])
    cv.arr[..., 3] = 1
    if face == "west":
        r = np.maximum(np.abs(x + 0.5 - cv.w / 2) / (cv.w / 2), np.abs(y + 0.5 - cv.h / 2) / (cv.h / 2))
        put(cv, r < 0.45, "#ff3a4a", 1.0)
        put(cv, r < 0.2, "#ffc0c4", 1.0)
    elif face in ("north", "south", "top", "bottom"):
        ring = (x < cv.s // 4) if face in ("north", "top", "bottom") else (x >= cv.w - cv.s // 4)
        put(cv, ring, "#ff2a3a", 0.9)


def headset():
    band = [Cube((-4, -10, -1), (8, 1, 2), hs_shell, inflate=0.1), Cube((-5, -9.5, -1), (1, 4, 2), hs_shell),
            Cube((4, -9.5, -1), (1, 4, 2), hs_shell), Cube((-3, -9.4, -0.5), (6, 1, 1), hs_pad)]
    cups = [Cube((-6, -6, -2), (1, 4, 4), hs_cup), Cube((-5, -5.5, -1.5), (1, 3, 3), hs_pad),
            Cube((5, -6, -2), (1, 4, 4), hs_cup), Cube((4, -5.5, -1.5), (1, 3, 3), hs_pad)]
    mic = [Cube((-5.5, -3, -5), (1, 1, 3), hs_shell), Cube((-4.5, -3, -5.5), (2, 1, 1), hs_mic)]
    return [Bone("headset", "head", cubes=band + cups + mic)]


item("pro_headset", "Pro Headset", "HAT", "REGULAR", 300,
     "A gaming headset with glowing red rings on the cups and a mic by your mouth.",
     headset(), view=HEAD_VIEW)


# ---- Dapper Top Hat: tall black felt with a crimson band and a gold buckle, tipped to one side

def th_felt(face, cv):
    vgrad(cv, hexc("#4a4a5c"), hexc("#1c1c26"))          # charcoal, so the hat reads against a dark card
    y, x = cv.grid()
    cv.arr[(x % max(2, cv.s)) == 0, :3] *= 1.15
    cv.arr[y < max(1, cv.s // 8), :3] = hexc("#7a7a90")[:3]          # the felt's lit edge
    bevel(cv, 0.14, 0.24)


def th_ribbon(face, cv):
    vgrad(cv, hexc("#d01e34"), hexc("#7a0a1a"))
    y, x = cv.grid()
    cv.arr[(y % max(2, cv.s // 2)) == 0, :3] *= 1.18
    bevel(cv, 0.2, 0.2)


def th_buckle(face, cv):
    vgrad(cv, hexc("#fff0a0"), hexc("#b8781a"))
    y, x = cv.grid()
    hole = (np.abs(x + 0.5 - cv.w / 2) < cv.w * 0.22) & (np.abs(y + 0.5 - cv.h / 2) < cv.h * 0.28)
    if face in ("north", "south"):
        cv.arr[hole, :3] = hexc("#7a0a1a")[:3]
    bevel(cv, 0.25, 0.25)


def top_hat():
    return [Bone("tophat", "head", (0, -8, 0), (0, 0, -6), cubes=[
        Cube((-5, -9, -5), (10, 1, 10), th_felt), Cube((-3.5, -16, -3.5), (7, 7, 7), th_felt),
        Cube((-3.5, -10.5, -3.5), (7, 1, 7), th_ribbon, inflate=0.15),
        Cube((-1, -11, -3.9), (2, 2, 0), th_buckle)])]


item("dapper_top_hat", "Dapper Top Hat", "HAT", "REGULAR", 300,
     "A tall black top hat with a crimson band and a gold buckle, worn at a tilt.",
     top_hat(), view=HEAD_VIEW)


# ---- Raider Helm: a riveted steel cap, a bronze band, a nose guard and two curved horns

def rh_steel(face, cv):
    vgrad(cv, hexc("#eef0f4"), hexc("#6a727e"))
    y, x = cv.grid()
    rivet = ((x % (cv.s * 2)) == cv.s) & ((y % (cv.s * 2)) == cv.s // 2)
    cv.arr[grow(rivet), :3] = hexc("#8a929e")[:3]
    cv.arr[rivet, :3] = hexc("#ffffff")[:3]
    bevel(cv, 0.22, 0.3)


def rh_bronze(face, cv):
    vgrad(cv, hexc("#f4d08a"), hexc("#8a5a1a"))
    y, x = cv.grid()
    cv.arr[(x % (cv.s * 2)) == 0, :3] = hexc("#5a3a10")[:3]
    bevel(cv, 0.22, 0.3)


def rh_horn(i):
    def p(face, cv):
        c = mix(hexc("#f6eedc"), hexc("#b8a888"), i / 3)
        vgrad(cv, c, c * 0.82)
        y, x = cv.grid()
        cv.arr[(y % max(2, cv.s // 2)) == 0, :3] *= 0.85
        bevel(cv, 0.18, 0.25)
    return p


RAIDER_HORN = ["#4a3a2a", "#6e5838", "#94784e", "#b89c70", "#d6c29a", "#ece0c2", "#fbf6e8"]
RAIDER_SEGS = [((2, 2, 2), -80, 0), ((2, 2, 2), 22, -6), ((1, 2, 1), 22, -6), ((1, 2, 1), 20, -4), ((1, 1, 1), 16, 0)]


def raider_horn_px(i, n):
    """Segment i of n: ivory at the root darkening to a horn-brown tip, a ridge every half unit, lit on its upper
    side, an outline at the segment's ends."""
    def p(face, cv):
        cv.hand = True
        y, x = cv.grid()
        f = (i + 1 - cv.yy()) / n                                     # 0 at the root .. 1 at the tip
        lit = 0 if face in ("top", "bottom") else (x < max(1, cv.w // 3)).astype(int)
        lv = np.clip((len(RAIDER_HORN) - 2) - levels(f, len(RAIDER_HORN) - 1, 0.4) + lit, 0, len(RAIDER_HORN) - 1)
        cv.arr[:] = palette(lv, RAIDER_HORN)
        ring = (y % max(2, cv.s // 2)) == 0
        cv.arr[ring, :3] *= 0.72
        cv.arr[..., 3] = 1
    return p


def viking_helm():
    """The horns are chains of shrinking segments (child bones, each turned on from the last) that leave the helm's
    sides, sweep outward and curl up and forward."""
    bones, parent = [], None
    px_, py, pz = -4.4, -7.6, 0.0
    for i, ((w, h, d), roll, pitch) in enumerate(RAIDER_SEGS):
        name = f"horn{i}_r"
        bones.append(Bone(name, "head", (px_, py, pz), (pitch, 0, roll),
                          [Cube((px_ - w / 2, py - h, pz - d / 2), (w, h, d), raider_horn_px(i, len(RAIDER_SEGS)))],
                          parent=parent))
        parent, py = name, py - h
    helm = Bone("helm", "head", cubes=[Cube((-4, -9, -4), (8, 2, 8), rh_steel, inflate=0.5),
                                      Cube((-4, -7, -4), (8, 1, 8), rh_bronze, inflate=0.6),
                                      Cube((-0.5, -6.5, -5), (1, 3, 1), rh_steel)])
    return [helm] + aw_pair(bones)


item("raider_helm", "Raider Helm", "HAT", "REGULAR", 400,
     "A steel helmet with a bronze band, a nose guard and two curved horns.",
     viking_helm(), view=HEAD_VIEW)


# ---- Personal Raincloud (special): your own little storm cloud, raining nonstop, with the odd flash of lightning

CLOUD_PAL = ["#f4f6fa", "#d8dee8", "#b8c0ce", "#98a2b4", "#788298", "#5a6274", "#9ad8ff", "#cfeeff", "#ffffff", "#d8e4ff"]


def rc_flash(ph):
    return max(math.exp(-((ph - 0.5) / 0.03) ** 2), 0.7 * math.exp(-((ph - 0.6) / 0.02) ** 2))


def rc_cloud(face, cv):
    t = cv.yy()
    n = pnoise(cv.h, cv.w, 2.5, 140 + cv.w, octaves=3)
    v = np.clip(1 - t * 0.75 - (n - 0.5) * 0.4, 0, 1)
    if face == "top":
        v = np.clip(0.9 + (n - 0.5) * 0.3, 0, 1)
    elif face == "bottom":
        v = np.clip(0.25 + (n - 0.5) * 0.3, 0, 1)
    fl = rc_flash(cv.phase)
    v = np.clip(v + fl * 0.35, 0, 1)
    cv.arr[:] = palette(levels(v, 6, 0.35), CLOUD_PAL[:6][::-1])
    cv.arr[..., 3] = 1
    y, x = cv.grid()
    e = max(1, cv.s // 8)
    edge = (y < e) | (x < e) | (y >= cv.h - e) | (x >= cv.w - e)
    if face == "bottom":
        cv.arr[edge, :3] = hexc(CLOUD_PAL[5])[:3] * 0.85
    elif face == "top":
        cv.arr[edge, :3] = hexc(CLOUD_PAL[2])[:3]
    else:
        cv.arr[(y < e), :3] = hexc("#ffffff")[:3]
        cv.arr[(y >= cv.h - e), :3] = hexc(CLOUD_PAL[5])[:3]
    if fl > 0.1:
        em_from(cv, np.ones_like(t) * fl * 0.75, "#d8e4ff")
    mc_crisp(cv, CLOUD_PAL)


def rc_rain(face, cv):
    clear(cv)
    if face not in ("north", "south", "east", "west"):
        return
    rng = np.random.default_rng(int(abs(cv.cube.origin[0]) * 10 + abs(cv.cube.origin[2]) * 3 + 50 + len(face)))
    for _ in range(max(4, cv.w // 5)):
        x = int(rng.random() * cv.w)
        sp = int(rng.integers(1, 3))
        y0 = ((rng.random() + cv.phase * sp) % 1.0) * cv.h
        ln = int(cv.s * (0.7 + 0.4 * rng.random()))
        for k in range(ln):
            yy = int(y0 + k) % cv.h
            cv.arr[yy, x] = np.append(hexc("#cfeeff" if k > ln - 3 else "#9ad8ff")[:3], 1.0)
            cv.em[yy, x] = np.append(hexc("#9ad8ff")[:3], 0.75)
    fl = rc_flash(cv.phase)
    if fl > 0.3 and face in ("north", "south") and cv.cube.origin[2] < 0:
        m = mc_lines(cv, [[(0.5, 0.0), (0.36, 0.38), (0.6, 0.52), (0.44, 1.0)]], max(2, cv.s * 0.4))
        put(cv, m, "#ffffff", 1.0)
    mc_crisp(cv, CLOUD_PAL)


def storm_cloud():
    cl = [Cube((-5, -18, -4), (10, 3, 8), rc_cloud), Cube((-3, -20, -3), (6, 2, 6), rc_cloud), Cube((-6, -17, -2), (2, 2, 4), rc_cloud),
          Cube((4, -17, -3), (2, 2, 5), rc_cloud)]
    rain = [Cube((-4, -15, -2), (8, 6, 0), rc_rain), Cube((-4, -15, 2), (8, 6, 0), rc_rain),
            Cube((-2, -15, -3), (0, 6, 6), rc_rain), Cube((2, -15, -3), (0, 6, 6), rc_rain)]
    bob = {"type": "bob", "amp": 0.4, "speed": 0.6}
    return [Bone("cloud", "head", (0, -18, 0), cubes=cl, anim=bob),
            Bone("rain", "head", (0, -18, 0), cubes=rain, emissive=True, anim=dict(bob))]


item("personal_raincloud", "Personal Raincloud", "HAT", "SPECIAL", None,
     "Your own little storm cloud: it rains on you nonstop, with the odd flash of lightning.",
     storm_cloud(), view=dict(HEAD_VIEW, pitch=10), animation=dict(MC_SPECIAL, frames=16, frametime=2, interpolate=False))


# ---------------------------------------------------------------------------------------------- masks

# ---- Aviator Goggles: brass rims with amber lenses on a worn leather strap

def av_brass(face, cv):
    vgrad(cv, hexc("#ffe8a0"), hexc("#8a5a1a"))
    bevel(cv, 0.25, 0.3)


def av_lens(face, cv):
    t, x = cv.yy(), cv.xx()
    cv.arr[:] = ramp(t, [(0, "#ffd080"), (1, "#a8540a")])
    cv.arr[..., 3] = 0.92
    sh = (np.abs((x + t) - 0.62) < 0.13) & (x < 0.6)
    cv.arr[sh, :3] = hexc("#fff4d0")[:3]
    y, xx = cv.grid()
    e = max(1, cv.s // 8)
    cv.arr[(y < e) | (xx < e), :3] = hexc("#6a3a08")[:3]
    em_from(cv, sh.astype(np.float32) * 0.5, "#ffffff")


def av_strap(face, cv):
    leather(face, cv, "#6a4224")
    y, x = cv.grid()
    st = (y == max(1, cv.s // 8)) & ((x // max(1, cv.s // 4)) % 2 == 0)
    cv.arr[st, :3] = hexc("#c8a070")[:3]
    if face in ("top", "bottom"):
        # a band round the head: only its edge, the leather's thickness, is there (the middle is your head)
        ring = np.minimum(np.minimum(x, cv.w - 1 - x), np.minimum(y, cv.h - 1 - y))
        cv.arr[ring >= max(2, cv.s // 2)] = 0
        cv.arr[ring == 0, :3] = hexc("#3a2212")[:3]


def aviator_goggles():
    return [Bone("goggles", "head", cubes=[
        Cube((-4, -6.5, -4), (8, 1, 8), av_strap, inflate=0.45),
        Cube((-4, -7.5, -5), (3, 3, 1), av_brass), Cube((1, -7.5, -5), (3, 3, 1), av_brass),
        Cube((-3.5, -7, -5.05), (2, 2, 0), av_lens), Cube((1.5, -7, -5.05), (2, 2, 0), av_lens),
        Cube((-1, -6.5, -5), (2, 1, 1), av_brass)])]


item("aviator_goggles", "Aviator Goggles", "MASK", "REGULAR", 250,
     "Brass flying goggles with amber lenses on a worn leather strap.",
     aviator_goggles(), view=HEAD_VIEW)


# ---- Monocle & Mustache: a curled mustache and a gold monocle on a chain

def gk_moustache(face, cv):
    clear(cv)
    if face != "north":
        return
    W, H = cv.w, cv.h
    m = pmask(cv, lambda d: (d.chord((W * 0.1, -H * 0.3, W * 0.52, H * 1.05), 0, 180, fill=255),
                             d.chord((W * 0.48, -H * 0.3, W * 0.9, H * 1.05), 0, 180, fill=255),
                             d.ellipse((0, H * 0.0, W * 0.14, H * 0.55), fill=255),
                             d.ellipse((W * 0.86, H * 0.0, W, H * 0.55), fill=255)))
    sprite(cv, m, "#3a2418", hi="#6a4a30", lo="#1e120a")


def gk_monocle(face, cv):
    clear(cv)
    if face != "north":
        return
    y, x = cv.grid()
    r = np.sqrt((x + 0.5 - cv.w / 2) ** 2 + (y + 0.5 - cv.h / 2) ** 2) / (cv.w / 2)
    ring = (r > 0.74) & (r < 1.0)
    glass = r <= 0.74
    cv.arr[glass] = (0.82, 0.92, 1.0, 0.4)
    sh = glass & (np.abs(x - y) < cv.s * 0.35) & (x < cv.w * 0.6)
    cv.arr[sh] = (1, 1, 1, 0.8)
    cv.arr[ring] = np.append(hexc("#f0c24a")[:3], 1.0)
    cv.arr[ring & (y > cv.h * 0.6), :3] = hexc("#b8861e")[:3]


def gk_chain(face, cv):
    fill(cv, hexc("#f0c24a"))
    y, x = cv.grid()
    cv.arr[(y // max(1, cv.s // 4)) % 2 == 1, :3] = hexc("#b8861e")[:3]


def gentleman():
    return [Bone("gent", "head", cubes=[Cube((-4, -3, -4.6), (8, 2, 0), gk_moustache),
                                        Cube((0.5, -6.5, -4.6), (3, 3, 0), gk_monocle),
                                        Cube((3, -4, -4.65), (0, 4, 1), gk_chain)])]


item("monocle_mustache", "Monocle & Mustache", "MASK", "REGULAR", 250,
     "A curled mustache and a gold monocle on a fine chain.",
     gentleman(), view=dict(HEAD_VIEW, yaw=20))


# ---------------------------------------------------------------------------------------------- back items

def mc_wood(c1="#e0a860", c2="#9a5a2a", seed=9):
    def p(face, cv):
        vgrad(cv, hexc(c1), hexc(c2))
        y, x = cv.grid()
        n = pnoise(cv.h, cv.w, 3.0, seed, octaves=2, aspect=4)
        grain_ = np.sin((x / max(1, cv.s) + n * 3) * 5) > 0.55
        cv.arr[grain_, :3] *= 0.86
        bevel(cv, 0.14, 0.24)
    return p


# ---- Acoustic Guitar: a warm wooden guitar slung across your back on a black strap

def ag_body(face, cv):
    mc_wood()(face, cv)
    if face == "south":
        y, x = cv.grid()
        r = np.sqrt((x + 0.5 - cv.w / 2) ** 2 + (y + 0.5 - cv.h * 0.4) ** 2) / cv.s
        cv.arr[r < 1.1, :3] = hexc("#1a0e06")[:3]
        ring = (r > 1.25) & (r < 1.55)
        cv.arr[ring, :3] = hexc("#f4e0b0")[:3]
        bridge = (np.abs(y + 0.5 - cv.h * 0.78) < cv.s * 0.3) & (np.abs(x + 0.5 - cv.w / 2) < cv.s * 1.4)
        cv.arr[bridge, :3] = hexc("#2a1408")[:3]
        strings = (np.abs(x + 0.5 - cv.w / 2) < cv.s * 0.5) & (((x + 0.5 - cv.w / 2) % max(2, cv.s // 4)) < 1) & (y < cv.h * 0.78)
        cv.arr[strings & (r >= 1.1), :3] = hexc("#e8e8e8")[:3]


def ag_neck(face, cv):
    mc_wood("#5a3a1e", "#3a220e")(face, cv)
    if face == "south":
        y, x = cv.grid()
        fret = (y % max(2, cv.s)) == 0
        cv.arr[fret, :3] = hexc("#d8d8d8")[:3]
        cv.arr[(x == cv.w // 2) | (x == cv.w // 2 - 1), :3] = hexc("#f0f0f0")[:3]


def ag_strap(face, cv):
    leather(face, cv, "#1a1a1e")
    y, x = cv.grid()
    cv.arr[(y == 0), :3] = hexc("#3a3a44")[:3]


def guitar():
    return [Bone("guitar", "body", (0, 5, 3.5), (0, 0, -30), cubes=[
        Cube((-3, 3, 2.5), (6, 8, 2), ag_body), Cube((-2.5, 2, 2.5), (5, 1, 2), ag_body),
        Cube((-0.5, -6, 3), (1, 8, 1), ag_neck), Cube((-1, -8.5, 2.8), (2, 3, 1), mc_wood("#3a220e", "#1a0e06", 10))]),
        Bone("guitar_strap", "body", (0, 5, 0), (0, 0, -32), cubes=[Cube((-6, 5, 2.05), (12, 1, 0), ag_strap),
                                                                    Cube((-4.5, 5, -2.3), (9, 1, 0), ag_strap)])]


item("acoustic_guitar", "Acoustic Guitar", "BACK", "REGULAR", 400,
     "A warm wooden guitar slung over your back on a black strap.",
     guitar(), view=BACK_VIEW)


# ---- Turtle Shell: a big domed green shell with pale plate lines and a cream rim

def ts_shell(face, cv):
    vgrad(cv, hexc("#6ab85a"), hexc("#1e5a2a"))
    y, x = cv.grid()
    X, Y = x + 0.5, y + 0.5
    R = cv.s * 1.7
    q = (X * math.sqrt(3) / 3 - Y / 3) / R
    r = (Y * 2 / 3) / R
    cx, cz = q, r
    cy = -cx - cz
    rx, ry, rz = np.round(cx), np.round(cy), np.round(cz)
    dx, dy, dz = np.abs(rx - cx), np.abs(ry - cy), np.abs(rz - cz)
    fx = (dx > dy) & (dx > dz)
    fz = ~fx & (dy <= dz)
    rx = np.where(fx, -ry - rz, rx)
    rz = np.where(fz, -rx - ry, rz)
    ux = (X - R * math.sqrt(3) * (rx + rz / 2)) / R
    uy = (Y - R * 1.5 * rz) / R
    hexd = np.maximum(np.abs(ux), 0.5 * np.abs(ux) + 0.866 * np.abs(uy)) / 0.866
    if face in ("south", "top", "bottom", "east", "west"):
        plate = mix(hexc("#7ac86a"), hexc("#2a6a32"), np.clip(hexd * 0.6 + uy * 0.3 + 0.2, 0, 1)[..., None])
        cv.arr[..., :3] = mix(cv.arr[..., :3], plate[..., :3], 0.6)
        cv.arr[hexd > 0.84, :3] = hexc("#c8e8a0")[:3]
        cv.arr[(hexd > 0.78) & (hexd <= 0.84), :3] = hexc("#2a5a22")[:3]
    bevel(cv, 0.2, 0.3)


def ts_rim(face, cv):
    vgrad(cv, hexc("#f0e0a8"), hexc("#a8905a"))
    y, x = cv.grid()
    cv.arr[(x % max(2, cv.s)) == 0, :3] = hexc("#7a6a3a")[:3]
    bevel(cv, 0.2, 0.3)


item("turtle_shell", "Turtle Shell", "BACK", "REGULAR", 400,
     "A big green turtle shell with pale plate lines. Slow and steady.",
     [Bone("shell", "body", cubes=[Cube((-4, 0.5, 2), (8, 10, 1), ts_rim, inflate=0.35), Cube((-3.5, 1, 3), (7, 9, 1), ts_shell, inflate=0.3),
                                   Cube((-2.5, 2, 4), (5, 7, 1), ts_shell)])],
     view=BACK_VIEW)


# ---- Reactor Pack (special): a steel backpack with a glowing plasma core that swirls and pulses

REACTOR_PAL = ["#0a1a6a", "#0a2aff", "#1a6aff", "#3ad8ff", "#9af0ff", "#ffffff", "#e8ecf2", "#c8ccd4", "#9aa0ac", "#6a707c",
               "#3a3e48", "#22242a", "#2a2a30", "#ffd23a"]


def rp_case(face, cv):
    t = cv.yy()
    cv.arr[:] = palette(levels(t, 5, 0.4), ["#e8ecf2", "#c8ccd4", "#9aa0ac", "#6a707c", "#3a3e48"])
    cv.arr[..., 3] = 1
    y, x = cv.grid()
    e = max(1, cv.s // 8)
    cv.arr[(y >= cv.h - e) | (x >= cv.w - e), :3] = hexc("#22242a")[:3]
    cv.arr[(y < e) | (x < e), :3] = hexc("#ffffff")[:3]
    if cv.w >= cv.s * 3 and cv.h >= cv.s * 3:
        for (cx, cy) in ((e * 3, e * 3), (cv.w - e * 4, e * 3), (e * 3, cv.h - e * 4), (cv.w - e * 4, cv.h - e * 4)):
            cv.arr[int(cy):int(cy) + e, int(cx):int(cx) + e, :3] = hexc("#6a707c")[:3]
    warn = (face == "north") & (cv.h >= cv.s * 4)
    if warn:
        st = ((x + y) // max(1, cv.s // 2) % 2 == 0) & (y > cv.h - cv.s * 1.2) & (y < cv.h - cv.s * 0.6)
        cv.arr[st, :3] = hexc("#ffd23a")[:3]
    mc_crisp(cv, REACTOR_PAL)


def rp_strap(face, cv):
    leather(face, cv, "#2a2a30")
    mc_crisp(cv, REACTOR_PAL + ["#1a1a1e", "#3a3a44"])


def rp_core(face, cv):
    t, u = cv.yy(), cv.xx()
    n = fshift(pnoise(cv.h, cv.w, 2.0, 400, octaves=3), -cv.phase * cv.h * 2, 0)
    swirl = 0.5 + 0.5 * np.sin((t * 6 - cv.phase * math.tau * 2) + (n - 0.5) * 6 + u * 3)
    pulse = 0.5 + 0.5 * math.cos(cv.phase * math.tau * 2)
    v = np.clip(swirl * 0.75 + pulse * 0.25 + (1 - np.abs(u - 0.5) * 2) * 0.2, 0, 1)
    cv.arr[:] = palette(levels(v, 6, 0.35), ["#0a1a6a", "#0a2aff", "#1a6aff", "#3ad8ff", "#9af0ff", "#ffffff"])
    cv.arr[..., 3] = 1
    em_rgb(cv, cv.arr, np.full(t.shape, 0.6 + 0.4 * pulse, np.float32))
    mc_crisp(cv, REACTOR_PAL)


def reactor_pack():
    return [Bone("reactor", "body", cubes=[Cube((-3, 1, 2), (6, 8, 3), rp_case), Cube((-3.5, 0.5, 2.5), (7, 1, 2), rp_case),
                                           Cube((-3.5, 8.5, 2.5), (7, 1, 2), rp_case),
                                           Cube((-3.5, 0, -2.3), (1, 7, 0), rp_strap),
                                           Cube((2.5, 0, -2.3), (1, 7, 0), rp_strap)]),
            Bone("core", "body", cubes=[Cube((-1.5, 2.5, 5.05), (3, 5, 0), rp_core), Cube((-2.5, 3, 4.6), (1, 4, 1), rp_core),
                                        Cube((1.5, 3, 4.6), (1, 4, 1), rp_core)], emissive=True)]


item("reactor_pack", "Reactor Pack", "BACK", "SPECIAL", None,
     "A steel backpack with a glowing plasma core that swirls and pulses.",
     reactor_pack(), view=BACK_VIEW, animation=dict(MC_SPECIAL, frames=16, frametime=2, interpolate=True))


# ---------------------------------------------------------------------------------------------- sword skins

# ---- Candy Cane Blade: a striped peppermint blade with a gold guard and a mint gem

def cc_blade(face, cv):
    cv.arr[:] = hexc("#fffafa")
    y, x = cv.grid()
    k = max(2, cv.s)
    stripe = ((x + y) % (k * 2)) < k * 0.85
    cv.arr[stripe, :3] = hexc("#e8142a")[:3]
    thin = (((x + y) % (k * 2)) >= k * 0.85) & (((x + y) % (k * 2)) < k * 1.05)
    cv.arr[thin, :3] = hexc("#ff8a9a")[:3]
    xx = cv.xx()
    shine = np.abs(xx - 0.3) < 0.1
    cv.arr[shine, :3] = np.clip(cv.arr[shine, :3] + 0.25, 0, 1)
    cv.arr[..., 3] = 1
    bevel(cv, 0.25, 0.25)


item("candy_cane_blade", "Candy Cane Blade", "ITEM_SKIN", "REGULAR", 250,
     "Your sword as a striped peppermint candy cane with a gold guard. Shows on swords you hold, on your screen.",
     sword(cc_blade, cc_blade, metal("#ffe9a0", "#9a6a1a", 0.35), wrap_grip("#ffffff", "#e8142a"), metal("#ffe9a0", "#9a6a1a"),
           gem_paint("#d8fff0", "#3ac86a")),
     item_space=True, display=SWORD_DISPLAY, view=ITEM_VIEW)


# ---- Verdant Thorn: green glass grown into a blade, wrapped in a living vine with leaves

def vt_blade(face, cv):
    glass_blade("#e0ffc0", "#4aa83a", "#14381a", "#9aff6a")(face, cv)
    if face in ("north", "south"):
        H, W = cv.h, cv.w
        pts = [((0.5 + 0.32 * math.sin(i / 3.2)), i / 24) for i in range(25)]
        vine = mc_lines(cv, [pts], max(1, cv.s * 0.3))
        put(cv, vine, "#2a6a1a")
        for i in range(2, 24, 4):
            x, y = pts[i]
            leaf = disc(cv, x * W + (cv.s * 0.4 if math.sin(i / 3.2) < 0 else -cv.s * 0.4), y * H, cv.s * 0.35, ky=1.6)
            put(cv, leaf, "#7ae84a", 0.3)
            put(cv, leaf & lit_rim(leaf), "#c8ff9a", 0.3)


def vt_wood(face, cv):
    mc_wood("#6a4a2a", "#3a220e", 11)(face, cv)


item("verdant_thorn", "Verdant Thorn", "ITEM_SKIN", "REGULAR", 250,
     "Your sword grown from green glass, wrapped in a living vine with leaves. Shows on swords you hold, on your screen.",
     sword(vt_blade, glass_blade("#e0ffc0", "#4aa83a", "#14381a", "#9aff6a"), vt_wood, wrap_grip("#3a220e", "#7ae84a"), vt_wood,
           gem_paint("#f0ffd0", "#7ae84a")),
     item_space=True, display=SWORD_DISPLAY, view=ITEM_VIEW)


# ---- Plasma Edge (special): a humming violet energy blade with bright pulses running up the edge

PLASMA_PAL = ["#1a0640", "#3a0aff", "#6a1aff", "#9a2aff", "#c03aff", "#e08aff", "#f4d8ff", "#ffffff", "#e0e4ea", "#a8acb8",
              "#6a6e7a", "#3a3e48", "#1a1a20", "#2a2a34"]


def pe_blade(face, cv):
    t, x = cv.yy(), cv.xx()
    if face in ("north", "south"):
        core = 1 - np.abs(x - 0.5) * 2
        k = np.zeros_like(t)
        for j in range(3):
            pk = ((cv.phase + j / 3) % 1.0) * 1.2 - 0.1
            k = np.maximum(k, np.exp(-(((1 - t) - pk) / 0.06) ** 2))
        v = np.clip(core * 0.75 + k * 0.5 + 0.08, 0, 1)
        cv.arr[:] = palette(levels(v, 7, 0.3), ["#3a0aff", "#6a1aff", "#9a2aff", "#c03aff", "#e08aff", "#f4d8ff", "#ffffff"])
        cv.arr[..., 3] = 1
        em_rgb(cv, cv.arr, np.clip(0.55 + v * 0.45, 0, 1))
    else:
        # the blade's edges: a white-hot line down the middle of the edge, violet either side, the same pulses running
        # up it as on the flats (so the edge never reads as a flat bar)
        lng = t if cv.h >= cv.w else x
        acr = x if cv.h >= cv.w else t
        k = np.zeros_like(t)
        for j in range(3):
            pk = ((cv.phase + j / 3) % 1.0) * 1.2 - 0.1
            k = np.maximum(k, np.exp(-(((1 - lng) - pk) / 0.06) ** 2))
        v = np.clip((1 - np.abs(acr - 0.5) * 2) * 0.7 + k * 0.45 + 0.1, 0, 1)
        cv.arr[:] = palette(levels(v, 7, 0.3), ["#3a0aff", "#6a1aff", "#9a2aff", "#c03aff", "#e08aff", "#f4d8ff", "#ffffff"])
        cv.arr[..., 3] = 1
        em_rgb(cv, cv.arr, np.clip(0.55 + v * 0.45, 0, 1))
    mc_crisp(cv, PLASMA_PAL)


def pe_metal(face, cv):
    t = cv.yy()
    cv.arr[:] = palette(levels(t, 4, 0.4), ["#e0e4ea", "#a8acb8", "#6a6e7a", "#3a3e48"])
    cv.arr[..., 3] = 1
    mc_crisp(cv, PLASMA_PAL)


def pe_grip(face, cv):
    cv.arr[:] = hexc("#1a1a20")
    y, x = cv.grid()
    k = max(2, cv.s // 2)
    wrap = ((y + x) % (k * 2)) < k
    cv.arr[wrap, :3] = hexc("#9a2aff")[:3]
    cv.arr[..., 3] = 1
    em_from(cv, wrap * 0.5, "#9a2aff")
    mc_crisp(cv, PLASMA_PAL)


def pe_gem(face, cv):
    cv.arr[:] = hexc("#f4d8ff")
    cv.arr[..., 3] = 1
    em_from(cv, np.ones((cv.h, cv.w)) * (0.6 + 0.4 * (0.5 + 0.5 * math.cos(math.tau * cv.phase))), "#e08aff")
    mc_crisp(cv, PLASMA_PAL)


item("plasma_edge", "Plasma Edge", "ITEM_SKIN", "SPECIAL", None,
     "Your sword as a humming violet energy blade with light pulsing up the edge. Shows on swords you hold, on your screen.",
     sword(pe_blade, pe_blade, pe_metal, pe_grip, pe_metal, pe_gem),
     item_space=True, display=SWORD_DISPLAY, view=ITEM_VIEW, animation=dict(MC_SPECIAL, frames=24, frametime=1.5, interpolate=False))


# ---------------------------------------------------------------------------------------------- pets
# On the pet engine like the shipped pets (see the pets section above): a body bone with the "pet" motion, a head that
# glances about, eyelids that blink, flippers, wings or arms on their own little animations; check_pet_clearance() and
# check_pet_parts() prove none of it reaches into you or into itself.

# -------- Baby Turtle: a little green sea turtle with a patterned shell riding on your shoulder, paddling its flippers

TUR_SKIN, TUR_SKIN_D, TUR_SPOT = "#72c25a", "#3a8a3a", "#c8ee8a"
TUR_SHELL, TUR_SHELL_D, TUR_SHELL_HI, TUR_SEAM, TUR_BELLY = "#6a9a34", "#3e6a24", "#b8d25a", "#233e16", "#f2e2a2"
TURTLE = (PERCH[0] + 0.75, PERCH[1], PERCH[2] + 0.25)


def tur_spots(A):
    A.put(((A.x * 5 + A.y * 3) % 7 == 0) & ((A.x + A.y) % 2 == 0), TUR_SPOT)
    A.put(((A.x * 3 + A.y * 5) % 11 == 3), shade(TUR_SKIN, 0.84))


def tur_scutes(face, A, cells):
    """Shell plates: each cell a warm centre fading out to a darker rim, with dark seams between the cells."""
    best = np.full((A.h, A.w), 1e9)
    second = np.full((A.h, A.w), 1e9)
    for (cx, cy) in cells:
        d = np.hypot((A.x + 0.5 - cx * A.w), (A.y + 0.5 - cy * A.h))
        second = np.where(d < best, best, np.minimum(second, d))
        best = np.minimum(best, d)
    seam = (second - best) < 1.0
    A.put(best < 2.2, TUR_SHELL_HI)
    A.put((best >= 2.2) & (best < 3.4), shade(TUR_SHELL, 1.12))
    A.put(best >= 4.6, TUR_SHELL_D)
    A.put(seam, TUR_SEAM)


def tur_shell(face, A):
    if face == "top":                          # 20 x 20: a ring of plates round three down the middle
        tur_scutes(face, A, [(0.5, 0.18), (0.5, 0.5), (0.5, 0.82), (0.12, 0.3), (0.12, 0.7), (0.88, 0.3), (0.88, 0.7)])
    elif face in ("north", "south", "east", "west"):   # the rim: small marginal plates
        A.put(A.x % 4 == 0, TUR_SEAM)
        A.put((A.x % 4 == 2) & (A.y == 1), TUR_SHELL_HI)
        A.put(A.y >= A.h - 1, TUR_SHELL_D)
    elif face == "bottom":
        A.fill(TUR_BELLY)


def tur_dome(face, A):
    if face == "top":                          # 12 x 12: the raised middle row of plates
        tur_scutes(face, A, [(0.5, 0.2), (0.5, 0.52), (0.5, 0.84), (0.14, 0.5), (0.86, 0.5)])
    else:
        A.put(A.x % 3 == 1, TUR_SHELL_D)
        A.put((A.x % 3 == 2) & (A.y == 0), TUR_SHELL_HI)


def tur_belly(face, A):
    A.put((A.x % 4 == 0) | (A.y % 4 == 0), shade(TUR_BELLY, 0.86))


def tur_head(face, A):
    tur_spots(A)
    if face == "north":                        # 12 x 12: big glossy eyes, a beaky smile, rosy cheeks
        eye(A, 1, 4, 3, 4, "#3a2a1a", dark="#0e0a06")
        eye(A, 8, 4, 3, 4, "#3a2a1a", dark="#0e0a06")
        A.pix([(4, 10), (7, 10)] + [(5, 11), (6, 11)], "#1e4a1a")
        A.pix([(5, 9), (6, 9)], shade(TUR_SKIN, 0.78))
        A.rect(0, 9, 2, 10, "#ff9ab0")
        A.rect(10, 9, 12, 10, "#ff9ab0")
    elif face == "bottom":
        A.fill(shade(TUR_BELLY, 0.95))


def tur_flipper(face, A):
    A.put(A.x % 3 == 1, TUR_SKIN_D)
    A.put((A.x % 3 == 2) & (A.y == 0), TUR_SPOT)
    if face == "top":
        tur_spots(A)
        A.put(A.y == 0, TUR_SKIN_D)


def turtle_pet():
    a = TURTLE
    body = pet_body("turtle", a, [
        PC(a, (-2.5, -2, -1.75), (5, 1, 5), px(TUR_SHELL, tur_shell)),
        PC(a, (-1.5, -2.75, -0.75), (3, 1, 3), px(TUR_SHELL, tur_dome)),
        PC(a, (-2, -1, -1.25), (4, 1, 4), px(TUR_BELLY, tur_belly)),
        PC(a, (-3.5, -1, 2), (1, 0, 1), px(TUR_SKIN, tur_flipper)),
        PC(a, (2.5, -1, 2), (1, 0, 1), px(TUR_SKIN, tur_flipper)),
        PC(a, (-0.5, -1, 3.25), (1, 0, 1), px(TUR_SKIN, tur_flipper))])
    head = Bone("head", "root", P(a, 0, -1.75, -1.5), cubes=[
        PC(a, (-1.5, -4.5, -4), (3, 3, 3), px(TUR_SKIN, tur_head))],
        anim={"type": "pet_head", "amp": 12}, parent="turtle")
    lids = blink_bone("lids", "head", a, (-1.5, -3.5, -4.02), (3, 1, 0), lid_paint(TUR_SKIN, [(1, 3), (8, 3)], "#1e4a1a"))
    lids.anim["speed"] = 0.22                      # a slow, sleepy blink
    flip = Bone("flipper_r", "root", P(a, -2.25, -1, -0.75), (0, -20, 0), cubes=[
        PC(a, (-4, -1, -1.25), (2, 0, 1), px(TUR_SKIN, tur_flipper))],
        anim={"type": "wing", "side": 1, "base": 0, "amp": 14, "speed": 0.6, "fold": 0}, parent="turtle")
    return [body, head, lids] + pet_pair(flip, a)


item("sea_turtle", "Baby Turtle", "PET", "REGULAR", 600,
     "A little green sea turtle with a patterned shell that rides on your shoulder, paddling its flippers, looking "
     "about and blinking slowly.",
     turtle_pet(), view=dict(PET_VIEW, glow="#5ad8a0"))


# -------- Honey Bee: a fuzzy striped bee hovering on see-through wings, with antennae and a little stinger

BEE_Y, BEE_K, BEE_FUZZ = "#ffcc2a", "#241a10", "#ffe68a"
BEE = (HOVER[0], HOVER[1], HOVER[2] + 0.25)


def bee_abdomen(face, A):
    # stripes run round the abdomen (along z: back to front)
    if face in ("east", "west"):
        A.put((A.x // 3) % 2 == (1 if face == "east" else 0), BEE_K)
    elif face in ("top", "bottom"):
        A.put((A.y // 3) % 2 == 1, BEE_K)
    elif face == "south":
        A.ellipse(6, 6, 3, 3, BEE_K)
    A.put(((A.x * 3 + A.y * 5) % 11 == 0) & (A.a[..., 0] > 0.5), BEE_FUZZ)


def bee_head(face, A):
    if face == "north":                        # 12 x 12
        eye(A, 2, 4, 3, 4, "#4a4a9a", dark="#101018")
        eye(A, 7, 4, 3, 4, "#4a4a9a", dark="#101018")
        A.pix([(5, 10), (6, 10)], "#6a3a10")
        A.rect(0, 9, 1, 10, "#ff9a6a")
        A.rect(11, 9, 12, 10, "#ff9a6a")
    A.put(((A.x * 3 + A.y * 5) % 13 == 0), BEE_FUZZ)


def bee_antenna(apex):
    def feat(face, A):
        m = (np.abs(A.x + 0.5 - apex * A.w) < 0.8) | ((A.y < 2) & (np.abs(A.x + 0.5 - apex * A.w) < 1.6))
        if face == "south":
            m = m[:, ::-1]
        A.put(A.y < 2, "#3a2a18")
        A.clip(m)
        A.norim[:] = True
    return px(BEE_K, feat, rim=0)


def bee_wing(face, A):
    m = A.poly([(1.0, 0.75), (0.75, 0.2), (0.35, 0.02), (0.05, 0.15), (0.0, 0.5), (0.3, 0.9), (0.75, 1.0)])
    if face == "south":
        m = m[:, ::-1]
    A.a[..., :3] = rgbf("#e8f8ff")
    A.a[..., 3] = 0.7
    xs = (A.x + 0.5) / A.w if face != "south" else 1 - (A.x + 0.5) / A.w
    ys = (A.y + 0.5) / A.h
    vein = (np.abs(ys - (0.55 - (1 - xs) * 0.25)) < 0.6 / A.h) | (np.abs(ys - (0.78 - (1 - xs) * 0.1)) < 0.6 / A.h)
    A.put(vein & m, "#a8d0ec", alpha=0.85)
    A.put(m & (ys < 0.35) & (xs < 0.6) & (xs > 0.2), "#ffffff", alpha=0.7)
    A.clip(m)
    edge = A.rim(1.0)
    A.a[edge, :3] = rgbf("#8ab4d8")
    A.a[edge, 3] = 0.9


def bee_pet():
    a = BEE
    body = pet_body("bee", a, [
        PC(a, (-1.5, -4, -0.75), (3, 3, 4), px(BEE_Y, bee_abdomen)),
        PC(a, (-0.5, -3, 3.25), (1, 1, 1), px(BEE_K))])
    body.anim = dict(PET_ANIM, amp=1.0, speed=1.8)
    head = Bone("head", "root", P(a, 0, -2.5, -0.75), cubes=[
        PC(a, (-1.5, -4.5, -3.25), (3, 3, 3), px(BEE_Y, bee_head)),
        PC(a, (-1.25, -6.5, -2.25), (1, 2, 0), bee_antenna(0.3)),
        PC(a, (0.25, -6.5, -2.25), (1, 2, 0), bee_antenna(0.7))],
        anim={"type": "pet_head", "amp": 12}, parent="bee")
    lids = blink_bone("lids", "head", a, (-1.25, -3.5, -3.27), (3, 1, 0), lid_paint(BEE_Y, [(1, 3), (6, 3)], BEE_K))
    wing = Bone("wing_r", "root", P(a, -0.5, -4, 0.75), (0, 0, 30), cubes=[PC(a, (-4.5, -6.5, 0.75), (4, 3, 0), px("#e6f6ff", bee_wing, rim=0))],
                anim={"type": "wing", "side": 1, "base": 24, "amp": 22, "speed": 5.0, "lift": -6, "fold": 40}, parent="bee")
    return [body, head, lids] + pet_pair(wing, a)


item("honey_bee", "Honey Bee", "PET", "REGULAR", 500,
     "A fuzzy striped bee with see-through wings that hovers by your shoulder.",
     bee_pet(), view=dict(PET_VIEW, glow="#ffd23a"))


# -------- Swamp Frog: a wide bright green frog with golden eyes on top of its head and a big smile

FROG_G, FROG_D, FROG_BELLY = "#6ad84a", "#2f8a2a", "#e8f4a8"
FROG = PERCH


def frog_spots(A):
    A.put(((A.x * 5 + A.y * 7) % 17 == 0) | ((A.x * 3 + A.y * 11) % 19 == 0), shade(FROG_G, 0.82))


def frog_body(face, A):
    frog_spots(A)
    if face == "north":                        # 16 x 8
        A.ellipse(8, 6, 5.5, 5, FROG_BELLY)


def frog_head(face, A):
    frog_spots(A)
    if face == "north":                        # 16 x 8: a wide smile and cheeks
        A.pix([(2, 4), (13, 4)] + [(i, 5) for i in range(3, 13)], "#1e4a1a")
        A.rect(1, 6, 3, 7, "#ff9ab0")
        A.rect(13, 6, 15, 7, "#ff9ab0")
        A.pix([(6, 2), (9, 2)], "#2a6a22")


def frog_eye(face, A):
    if face == "north":                        # 4 x 4
        eye(A, 0, 0, 4, 4, "#ffc42a", dark="#1a1408")
    elif face == "top":
        A.pix([(1, 1)], shade(FROG_G, 1.25))


def frog_leg(face, A):
    A.put(A.y >= A.h - 1, shade(FROG_D, 0.9))
    if face == "north":
        A.pix([(0, A.h - 1), (2, A.h - 1)], shade(FROG_G, 1.2))


def frog_pet():
    a = FROG
    body = pet_body("frog", a, [
        PC(a, (-2, -2, -1.25), (4, 2, 3), px(FROG_G, frog_body)),
        PC(a, (-2.25, -1, 0.25), (1, 1, 2), px(FROG_D, frog_leg)),
        PC(a, (1.25, -1, 0.25), (1, 1, 2), px(FROG_D, frog_leg)),
        PC(a, (-1.5, -1, -1.75), (1, 1, 1), px(FROG_G, frog_leg)),
        PC(a, (0.5, -1, -1.75), (1, 1, 1), px(FROG_G, frog_leg))])
    head = Bone("head", "root", P(a, 0, -2, -0.5), cubes=[
        PC(a, (-2, -4, -1.75), (4, 2, 3), px(FROG_G, frog_head)),
        PC(a, (-2, -5, -1.25), (1, 1, 1), px(FROG_G, frog_eye)),
        PC(a, (1, -5, -1.25), (1, 1, 1), px(FROG_G, frog_eye))],
        anim={"type": "pet_head", "amp": 10}, parent="frog")
    lids = blink_bone("lids", "head", a, (-2, -5, -1.27), (4, 1, 0), lid_paint(FROG_G, [(0, 4), (12, 4)], "#1e4a1a"))
    return [body, head, lids]


item("swamp_frog", "Swamp Frog", "PET", "REGULAR", 500,
     "A bright green frog with golden eyes perched on your shoulder.",
     frog_pet(), view=dict(PET_VIEW, glow="#7ad84a"))


# -------- Wandering Ghost (special): a shy little ghost that flickers in and out, its tail rippling, eyes glowing

GHOST = (HOVER[0], HOVER[1] - 0.5, HOVER[2])
GHOST_HI, GHOST_LO, GHOST_EYE = "#ffffff", "#bcd0f0", "#6ad8ff"


def ghost_alpha(ph):
    return 0.5 + 0.22 * (0.5 + 0.5 * math.cos(math.tau * ph)) + 0.08 * (0.5 + 0.5 * math.cos(math.tau * ph * 3))


def ghost_fill(A, ph, lo=GHOST_LO):
    t = (A.y + 0.5) / A.h
    lv = levels(np.clip(t, 0, 1), 3, 0.4)
    A.a[..., :3] = palette(lv, [GHOST_HI, "#dce6f8", lo])[..., :3] * FACE_LIGHT[A.face]
    A.a[..., 3] = round(ghost_alpha(ph) * 8) / 8
    A.e[..., :3] = rgbf("#d8e8ff")
    A.e[..., 3] = 0.2


def ghost_head(face, cv):
    A = Art(face, cv)
    ghost_fill(A, cv.phase)
    if face == "north":                        # 16 x 16: glowing eyes, a little "o" mouth, blush
        for x0 in (3, 10):
            A.rect(x0, 6, x0 + 3, 10, "#14102a", alpha=1.0)
            A.rect(x0 + 1, 7, x0 + 2, 9, GHOST_EYE, glow=1.0, alpha=1.0)
            A.pix([(x0, 6)], "#e8fbff", glow=1.0, alpha=1.0)
        A.rect(7, 11, 9, 13, "#14102a", alpha=1.0)
        A.rect(1, 11, 3, 12, "#ffb0d0", alpha=1.0)
        A.rect(13, 11, 15, 12, "#ffb0d0", alpha=1.0)
    A.rim(0.86)
    A.done()


def ghost_tail(face, cv):
    """The ragged hem: tatters that ripple sideways round the ghost once per loop."""
    A = Art(face, cv)
    ghost_fill(A, cv.phase)
    if face in ("north", "south", "east", "west"):
        off = {"north": 0, "east": 0.25, "south": 0.5, "west": 0.75}[face]
        xs = (A.x + 0.5) / A.w
        edge = A.h * (0.55 + 0.35 * (0.5 + 0.5 * np.sin(math.tau * (xs * 2 + off - cv.phase))))
        A.clip(A.y + 0.5 < edge)
    elif face == "bottom":
        A.clip(np.zeros((A.h, A.w), bool))
    A.rim(0.86)
    A.done()


def ghost_arm(face, cv):
    A = Art(face, cv)
    ghost_fill(A, cv.phase)
    A.rim(0.86)
    A.done()


def ghost_pet():
    a = GHOST
    body = pet_body("ghost", a, [PC(a, (-1.5, -2.5, -1.5), (3, 2, 3), ghost_tail)])
    body.anim = dict(PET_ANIM, amp=1.2, speed=0.9)
    head = Bone("head", "root", P(a, 0, -2.5, 0), cubes=[PC(a, (-2, -6.5, -2), (4, 4, 4), ghost_head)],
                anim={"type": "pet_head", "amp": 12}, parent="ghost")
    lids = blink_bone("lids", "head", a, (-1.25, -5, -2.03), (3, 1, 0), lid_paint("#e8eefa", [(0, 4), (7, 4)], "#14102a"))
    arm = Bone("arm_r", "root", P(a, -2, -4, 0), cubes=[PC(a, (-2.75, -4.25, -0.5), (1, 1, 1), ghost_arm)],
               anim={"type": "wing", "side": 1, "base": 0, "amp": 20, "speed": 0.9, "fold": 0}, parent="head")
    return [body, head, lids] + pet_pair(arm, a)


item("wandering_ghost", "Wandering Ghost", "PET", "SPECIAL", None,
     "A shy little ghost that flickers in and out, with glowing eyes that blink now and then.",
     ghost_pet(), view=dict(PET_VIEW, glow="#8ad8ff"), animation=dict(MC_SPECIAL, frames=16, frametime=2.5, interpolate=True))


# ---------------------------------------------------------------------------------------------- trails, kill and win effects

item("bubble_trail", "Bubble Trail", "TRAIL", "REGULAR", 200,
     "Shiny soap bubbles float up from your footsteps and pop.",
     fx={"type": "trail", "particle": "soft", "colors": ["#FFFFFF", "#9AE8FF", "#C8A8FF"], "rate": 8, "size": 0.9, "life": 1.4,
         "fade": 0.3, "sizeEnd": 0.6, "gravity": -0.15},
     thumb="bubbles")
item("heartbreaker", "Heartbreaker", "KILL_EFFECT", "REGULAR", 200,
     "Your opponent bursts into a shower of pink and red hearts.",
     fx={"type": "burst", "particle": "dust", "colors": ["#FF4A8A", "#FF8AB8", "#FFFFFF"], "count": 40, "size": 1.1},
     thumb="hearts")
item("gold_rush", "Gold Rush", "WIN_EFFECT", "REGULAR", 200,
     "Win a game and a fountain of gold coins and sparkles bursts out around you.",
     fx={"type": "burst", "particle": "dust", "colors": ["#FFD23A", "#FFF0A0", "#C8901A"], "count": 90, "size": 1.3},
     thumb="coins")


# Pictures for the new effects. thumb_particles() above draws the shipped effects' pictures; this wraps it with three more
# kinds (drawn the same way: shapes on a 4x canvas, the same soft glow and badge) and hands every other kind back to it.
MC_THUMB_KINDS = ("bubbles", "hearts", "coins")
mc_thumb_particles_shipped = thumb_particles


def mc_thumb_particles(kind, colors, size=THUMB):
    if kind not in MC_THUMB_KINDS:
        return mc_thumb_particles_shipped(kind, colors, size)
    S = THUMB * 4
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    cols = [tuple(int(v * 255) for v in hexc(c)[:3]) for c in colors]
    r = np.random.default_rng(len(kind) * 11 + 5)

    def spark(x, y, s, col, a=255):
        d.ellipse((x - s, y - s, x + s, y + s), fill=col + (a,))
        d.line((x - s * 2.6, y, x + s * 2.6, y), fill=col + (int(a * 0.7),), width=max(1, int(s / 2)))
        d.line((x, y - s * 2.6, x, y + s * 2.6), fill=col + (int(a * 0.7),), width=max(1, int(s / 2)))

    if kind == "bubbles":
        for i in range(34):
            t = i / 34
            x = S * 0.12 + t * S * 0.76 + r.normal(0, 16)
            y = S * 0.82 - t * S * 0.55 - r.random() * S * 0.18
            s = 10 + r.random() * 20 * (0.5 + t)
            c = cols[i % len(cols)]
            d.ellipse((x - s, y - s, x + s, y + s), fill=c + (40,), outline=c + (230,), width=max(2, int(s / 7)))
            d.ellipse((x - s * 0.55, y - s * 0.6, x - s * 0.15, y - s * 0.25), fill=(255, 255, 255, 230))
        for i in range(10):
            x = S * (0.14 + i * 0.075)
            d.ellipse((x - 18, S * 0.86, x + 18, S * 0.885), fill=(154, 232, 255, 80))
    elif kind == "hearts":
        cx, cy = S / 2, S * 0.5
        for i in range(30):
            a = r.random() * math.tau
            dist = 30 + r.random() * S * 0.34
            x, y = cx + math.cos(a) * dist, cy + math.sin(a) * dist
            s = 14 + r.random() * 22
            c = cols[i % len(cols)]
            d.polygon(heart_poly(x, y, s, rot=r.normal(0, 0.4)), fill=c + (240,))
            d.ellipse((x - s * 0.45, y - s * 0.45, x - s * 0.2, y - s * 0.2), fill=(255, 255, 255, 200))
        d.polygon(heart_poly(cx, cy, 46), fill=cols[0] + (255,))
        d.line([(cx - 10, cy - 34), (cx + 6, cy - 6), (cx - 8, cy + 6), (cx + 8, cy + 36)], fill=(40, 8, 20, 255), width=7)
    elif kind == "coins":
        for i in range(46):
            x, y = S / 2 + r.normal(0, S * 0.2), S * 0.58 + r.normal(0, S * 0.18)
            w = 18 + r.random() * 12
            k = 0.25 + abs(math.cos(r.random() * math.pi)) * 0.75          # coins seen at all angles
            d.ellipse((x - w * k, y - w, x + w * k, y + w), fill=(138, 90, 16, 255))
            d.ellipse((x - w * k + 3, y - w + 3, x + w * k - 3, y + w - 3), fill=cols[0] + (255,))
            d.ellipse((x - w * k * 0.55, y - w * 0.6, x + w * k * 0.55, y + w * 0.6), outline=cols[1] + (255,), width=3)
        for i in range(18):
            spark(S / 2 + r.normal(0, S * 0.22), S * 0.2 + r.random() * S * 0.5, 3 + r.random() * 5, (255, 244, 200), 230)
    arr = np.asarray(im, np.float32) / 255
    pre = arr[..., :3] * arr[..., 3:4]
    glow = blur(pre, 10) * 0.9 + blur(pre, 28) * 0.7
    pre = pre + glow
    a = np.clip(arr[..., 3] + lum(glow) * (1 - arr[..., 3]), 0, 1)
    rgb = np.where(a[..., None] > 1e-4, pre / np.maximum(a[..., None], 1e-4), 0)
    out = to_image(np.concatenate([np.clip(rgb, 0, 1), a[..., None]], -1), dither=False)
    out = out.convert("RGBa").resize((size, size), Image.LANCZOS).convert("RGBA")
    return badge(out, colors[0], 0.3)


thumb_particles = mc_thumb_particles


# ---------------------------------------------------------------------------------------------- emotes

item("facepalm", "Facepalm", "EMOTE", "REGULAR", 250,
     "Bury your face in your hand. Sometimes it's all you can do.",
     emote={"length": 2.0, "loop": False, "parts": {
         "rightArm": [[0, 0, 0, 0], [0.3, -130, -40, 0], [1.6, -130, -40, 0], [2.0, 0, 0, 0]],
         "head": [[0, 0, 0, 0], [0.3, 22, 0, 0], [1.6, 26, 0, 0], [2.0, 0, 0, 0]]}},
     fx={"type": "aura", "shape": "puff", "particle": "soft", "colors": ["#8AD0FF", "#FFFFFF"], "rate": 3, "height": 1.75,
         "size": 0.35, "sizeEnd": 0.25, "life": 0.9, "fade": 0.3, "gravity": 0.5, "from": 0.3, "to": 1.6},
     view={"gm": True, "pose": {"rightArm": (-130, -40, 0), "head": (24, 0, 0)}, "yaw": -24, "glow": "#ffb07a",
           "fx_pic": lambda: [((6.5, -8.5, -3), 3.2, GM_DROP, "matte"), ((8.5, -4, -2), 2.0, GM_DROP, "matte")]},
     thumb="emote")
item("shrug", "Shrug", "EMOTE", "REGULAR", 250,
     "Arms out, shoulders up. Who knows?",
     emote={"length": 1.4, "loop": False, "parts": {
         "rightArm": [[0, 0, 0, 0], [0.3, -40, 0, 50], [1.0, -40, 0, 50], [1.4, 0, 0, 0]],
         "leftArm": [[0, 0, 0, 0], [0.3, -40, 0, -50], [1.0, -40, 0, -50], [1.4, 0, 0, 0]],
         "head": [[0, 0, 0, 0], [0.3, 0, 0, 12], [1.0, 0, 0, 12], [1.4, 0, 0, 0]]}},
     fx={"type": "aura", "shape": "puff", "particle": "soft", "colors": ["#D8E4F0", "#FFFFFF"], "rate": 4, "height": 1.7,
         "size": 0.45, "sizeEnd": 0.7, "life": 0.9, "fade": 0.4, "gravity": -0.1, "from": 0.3, "to": 1.0},
     view={"gm": True, "pose": {"rightArm": (-40, 0, 50), "leftArm": (-40, 0, -50), "head": (0, 0, 12)}, "yaw": -18,
           "glow": "#9ad8ff",
           "fx_pic": lambda: [((-9, -11, -2), 2.6, GM_PUFF, "matte"), ((9.5, -12, -2), 2.2, GM_PUFF, "matte"),
                              ((-11.5, -15, -2), 1.5, GM_PUFF, "matte")]},
     thumb="emote")
item("victory_flex", "Victory Flex", "EMOTE", "REGULAR", 250,
     "Both arms up and flexed: the winner's pose.",
     emote={"length": 1.6, "loop": True, "parts": {
         "rightArm": [[0, -20, 0, 100], [0.8, -20, 0, 115], [1.6, -20, 0, 100]],
         "leftArm": [[0, -20, 0, -100], [0.8, -20, 0, -115], [1.6, -20, 0, -100]],
         "head": [[0, -8, 0, 0], [0.8, -12, 0, 0], [1.6, -8, 0, 0]]}},
     fx={"type": "aura", "shape": "rise", "particle": "soft", "colors": ["#FFD23A", "#FFF4C0"], "rate": 8, "radius": 0.9,
         "height": 2.0, "size": 0.4, "sizeEnd": 0.1, "life": 1.0, "fade": 0.3, "gravity": -0.2},
     view={"gm": True, "pose": {"rightArm": (-20, 0, 112), "leftArm": (-20, 0, -112), "head": (-10, 0, 0)}, "yaw": -20,
           "glow": "#ffd36a",
           "fx_pic": lambda: [((-13, -12, -2), 3.0, GM_SPARK, None), ((13, -13, -2), 2.6, GM_SPARK, None),
                              ((-10, 6, -3), 2.0, GM_SPARK, None), ((11, 4, -3), 2.2, GM_SPARK, None),
                              ((0, -16, -1), 1.8, GM_SPARK, None)]},
     thumb="emote")


# ============================================================================================== end of mockup collection



# ============================================================================================== gemini concept collection
# ==== GEMINI: the owner's second set of concept cards (pets, capes, a helm, wings, an aura, emotes), redrawn as Evade
# items to the shipped standard. The concepts only gave the idea and the colours: every item is drawn here from scratch.
# Helpers are named gm_* or after their item. Capes are free; animated items are special (priced later).

GM_SPECIAL_CAPE = dict(CAPE_HD_FX, frametime=2, interpolate=False)


def gm_grid(cv):
    y, x = cv.grid()
    return x + 0.5, y + 0.5


def gm_fold_shade(cv, k=0.12, n=2.5, seed=0.0):
    """A few soft vertical folds, as a multiplier (crisp: 3 steps), so flat art still reads as hanging cloth."""
    u = cv.xx()
    f = 0.5 + 0.5 * np.sin(u * math.tau * n + seed)
    return 1 - k + levels(f, 3, 0.5) * (k / 2)


# ---------------------------------------------------------------------------------------------- capes (regular)

# ---- Torii Sunset Cape: a red gate standing in still water, the sun going down behind it, its reflection rippling

TORII_PAL = ["#2a1240", "#3e1a5a", "#5e2470", "#8a2e7a", "#b83c78", "#e0566e", "#f47c5a", "#ffa648", "#ffcf6a", "#fff0b0",
             "#ffffff", "#1a0a18", "#3a0c10", "#6a1216", "#a01c1a", "#d8361e", "#24102e", "#40204e", "#6a3a6a", "#c06a80",
             "#ffb070", "#2c0a0c"]


def torii_outer(cv):
    h, w, s = cv.h, cv.w, cv.s
    X, Y = gm_grid(cv)
    t = Y / h
    hz = 0.62 * h                                                  # the horizon
    sky = bands(np.clip((t - 0.06) / 0.56, 0, 1), ["#2a1240", "#3e1a5a", "#5e2470", "#8a2e7a", "#b83c78", "#e0566e",
                                                    "#f47c5a", "#ffa648"], 0.45)
    cv.arr[:] = sky
    cv.arr[..., 3] = 1
    # a few stars still out at the top
    for sx, sy in ((0.14, 0.11), (0.8, 0.08), (0.62, 0.17), (0.28, 0.21), (0.9, 0.24)):
        px_star(cv, sx * w, sy * h, 2, "#ffe8f0", glow=0.5)
    # the sun: a big disc sitting on the horizon, banded from white-gold to orange
    cx, cy, R = w * 0.5, hz - s * 0.2, s * 3.1
    d = np.sqrt((X - cx) ** 2 + (Y - cy) ** 2)
    sun = (d < R) & (Y < hz)
    cv.arr[sun] = bands(1 - d / R, ["#ffa648", "#ffcf6a", "#fff0b0", "#ffffff"], 0.4)[sun]
    em_from(cv, sun * 0.55, "#ffcf6a")
    # distant islands on the horizon
    isle = (Y < hz) & (Y > hz - s * (0.5 + 0.5 * np.clip(1 - np.abs(X - w * 0.12) / (s * 1.8), 0, 1)
                                         + 0.8 * np.clip(1 - np.abs(X - w * 0.9) / (s * 2.2), 0, 1)))
    isle &= (np.abs(X - w * 0.12) < s * 1.8) | (np.abs(X - w * 0.9) < s * 2.2)
    put(cv, isle, "#40204e")
    # the water: darker bands with ripples, the sun's path a broken golden column
    wt = np.clip((Y - hz) / (h - hz), 0, 1)
    water = Y >= hz
    cv.arr[water] = bands(1 - wt, ["#24102e", "#40204e", "#6a3a6a", "#c06a80"], 0.45)[water]
    rip = water & (((Y - hz) // max(2, s // 5)) % 3 == 0) & (np.sin(X / s * 2.1 + (Y - hz) / s * 5.0) > 0.35)
    put(cv, rip, "#c06a80")
    path = water & (np.abs(X - cx) < R * (0.9 - wt * 0.35)) & (((Y - hz) // max(2, s // 5)) % 2 == 0)
    path &= np.sin(X / s * 3.3 + (Y - hz) / s * 4.0) > -0.3
    put(cv, path, "#ffcf6a")
    em_from(cv, path * 0.35, "#ffa648")

    # the gate (and its reflection, squashed and broken by the ripples)
    def gate(base_y, k, flip):
        def yy(v):                       # v: units above the water line
            return base_y - v * s * k if not flip else base_y + v * s * k
        m = np.zeros((h, w), bool)
        top_y, beam_y = 7.4, 5.9
        for px_ in (w * 0.5 - s * 2.3, w * 0.5 + s * 2.3):
            lean = 0.18 * s
            lo, hi = sorted((yy(0), yy(top_y)))
            m |= (np.abs(X - px_) < s * 0.36 + lean * (1 - np.abs(Y - yy(top_y)) / max(1, abs(yy(0) - yy(top_y))))) & (Y >= lo) & (Y <= hi)
        # nuki: the lower tie beam, poking out past the pillars
        lo, hi = sorted((yy(beam_y - 0.35), yy(beam_y + 0.35)))
        m |= (np.abs(X - w * 0.5) < s * 3.2) & (Y >= lo) & (Y <= hi)
        # the plaque between the beams
        lo, hi = sorted((yy(beam_y + 0.3), yy(top_y - 0.2)))
        m |= (np.abs(X - w * 0.5) < s * 0.45) & (Y >= lo) & (Y <= hi)
        # kasagi: the top beam, sweeping up at both ends
        rise = ((X - w * 0.5) / (s * 4.1)) ** 2 * 0.8
        c0, c1 = top_y - 0.1 + rise, top_y + 0.7 + rise * 1.2
        lo_v, hi_v = (np.minimum(c0, c1), np.maximum(c0, c1))
        if not flip:
            m |= (np.abs(X - w * 0.5) < s * 4.1) & (Y <= yy(lo_v)) & (Y >= yy(hi_v))
        else:
            m |= (np.abs(X - w * 0.5) < s * 4.1) & (Y >= yy(lo_v)) & (Y <= yy(hi_v))
        return m
    base = hz + s * 0.6
    g = gate(base, 1.0, False)
    sprite(cv, g, "#a01c1a", line="#1a0a18", hi="#d8361e", lo="#6a1216", e=2)
    # the black cap on the top beam and the dark feet of the pillars where they stand in the water
    cap = g & (Y < base - 7.5 * s - ((X - w * 0.5) / (s * 4.1)) ** 2 * 0.8 * s)
    put(cv, cap, "#2c0a0c")
    put(cv, g & (Y > hz), "#2c0a0c")
    ref = gate(base, 0.62, True) & water & ~g
    ref &= ((Y // 2) % 3 != 0)
    put(cv, ref, "#6a1216")
    mc_crisp(cv, TORII_PAL)
    mc_cape_trim(cv, "#3a0c10", "#a01c1a", stitch="#ffcf6a")


item("torii_sunset_cape", "Torii Sunset Cape", "CAPE", "REGULAR", 0,
     "A red gate standing in still water with the sun going down behind it, its reflection rippling on the lake.",
     cape_bones_reg(torii_outer, lining_hd("#5e2470", "#2a1240", "#ffa648")), layout=CAPE_REG, scale_cap=16, view=CAPE_VIEW)


# ---- Graffiti Crown Cape: a crown sprayed in white on black cloth, the paint dripping and spattered round it

GRAFFITI_PAL = ["#0c0c0e", "#141416", "#1c1c20", "#26262b", "#34343a", "#ffffff", "#e8e8ea", "#b8b8bc", "#ff3a8a",
                "#ffd23a", "#3ad8ff", "#5a5a62"]


def graffiti_outer(cv):
    h, w, s = cv.h, cv.w, cv.s
    X, Y = gm_grid(cv)
    n = pnoise(h, w, 5.0, 61, octaves=3)
    cv.arr[:] = bands(n * gm_fold_shade(cv, 0.3), ["#0c0c0e", "#141416", "#1c1c20", "#26262b"], 0.5)
    cv.arr[..., 3] = 1
    # the crown, sprayed in thick strokes: a zigzag rim with a ball on each point, the band, a wobbly base line
    cx, cy = 0.5, 0.42
    pts = [(cx - 0.36, cy + 0.02), (cx - 0.3, cy - 0.16), (cx - 0.16, cy - 0.02), (cx, cy - 0.21), (cx + 0.16, cy - 0.02),
           (cx + 0.3, cy - 0.16), (cx + 0.36, cy + 0.02)]
    stroke = mc_lines(cv, [pts], s * 0.62)
    stroke |= mc_lines(cv, [[(cx - 0.36, cy + 0.02), (cx - 0.33, cy + 0.14), (cx + 0.33, cy + 0.15), (cx + 0.36, cy + 0.02)]], s * 0.62)
    stroke |= mc_lines(cv, [[(cx - 0.3, cy + 0.085), (cx + 0.3, cy + 0.09)]], s * 0.4)
    for bx, by in ((cx - 0.3, cy - 0.2), (cx, cy - 0.255), (cx + 0.3, cy - 0.2)):
        stroke |= disc(cv, bx * w, by * h, s * 0.55)
    # paint drips running down from the base and the points
    rng = np.random.default_rng(14)
    for dx in (-0.3, -0.19, -0.04, 0.09, 0.22, 0.31):
        x0 = (cx + dx) * w
        y0 = (cy + 0.14) * h
        L = s * (0.8 + rng.random() * 2.6)
        wd = s * (0.12 + rng.random() * 0.1)
        stroke |= (np.abs(X - x0) < wd) & (Y > y0) & (Y < y0 + L)
        stroke |= disc(cv, x0, y0 + L, wd * 1.6)
    sprite(cv, stroke, "#ffffff", hi=None, lo="#b8b8bc", e=1)
    # overspray: a fine speckle round the strokes, fading out
    near = grow(stroke, 3) & ~stroke
    speck = near & (hash_noise(h, w, 9) > 0.8)
    put(cv, speck, "#e8e8ea")
    far = grow(stroke, 7) & ~grow(stroke, 3) & (hash_noise(h, w, 10) > 0.94)
    put(cv, far, "#b8b8bc")
    # a little colour: a pink underline tag and three dots
    tag = mc_lines(cv, [[(0.2, 0.74), (0.36, 0.72), (0.5, 0.75), (0.66, 0.71), (0.8, 0.73)]], s * 0.32)
    put(cv, tag, "#ff3a8a")
    for i, (tx, col) in enumerate(((0.36, "#ffd23a"), (0.5, "#3ad8ff"), (0.64, "#ff3a8a"))):
        put(cv, disc(cv, tx * w, 0.81 * h, s * 0.3), col)
    mc_crisp(cv, GRAFFITI_PAL)
    mc_cape_trim(cv, "#0c0c0e", "#34343a", stitch="#5a5a62")


item("graffiti_crown_cape", "Graffiti Crown Cape", "CAPE", "REGULAR", 0,
     "Black cloth with a crown sprayed on in white, the paint still dripping, and a splash of colour underneath.",
     cape_bones_reg(graffiti_outer, lining_hd("#26262b", "#0c0c0e", "#ff3a8a")), layout=CAPE_REG, scale_cap=16, view=CAPE_VIEW)


# ---- Imperial Dragon Cape: a long golden dragon coiling down deep navy silk between curling clouds

IMPERIAL_PAL = ["#060a1e", "#0a1030", "#0e1640", "#142052", "#1c2c66", "#2a3c7c", "#3a0e06", "#6a3a0a", "#a8701a",
                "#d8a030", "#f0c84a", "#fff0a0", "#e11d2e", "#ffffff"]


def imperial_outer(cv):
    h, w, s = cv.h, cv.w, cv.s
    X, Y = gm_grid(cv)
    t = Y / h
    cv.arr[:] = bands(np.clip(0.75 - t * 0.6, 0, 1) * gm_fold_shade(cv, 0.15), ["#060a1e", "#0a1030", "#0e1640", "#142052"], 0.45)
    cv.arr[..., 3] = 1
    # curling clouds in a lighter navy
    for ccx, ccy, r in ((0.2, 0.2, 1.0), (0.82, 0.48, 1.1), (0.24, 0.78, 0.9)):
        for k, (ox, oy, rr) in enumerate(((0, 0, 1.0), (1.1, 0.2, 0.8), (-1.0, 0.25, 0.75))):
            c = disc(cv, (ccx * w) + ox * s * r, (ccy * h) + oy * s * r, rr * s * r)
            ring = c & ~disc(cv, (ccx * w) + ox * s * r + s * 0.25, (ccy * h) + oy * s * r - s * 0.1, rr * s * r * 0.62)
            put(cv, ring, "#1c2c66")
    # the dragon's body: a thick S coil from the top right down to the tail at the bottom left
    path = [(0.72, 0.17), (0.6, 0.27), (0.36, 0.3), (0.24, 0.4), (0.32, 0.5), (0.6, 0.52), (0.76, 0.62), (0.7, 0.74),
            (0.46, 0.78), (0.3, 0.86), (0.22, 0.95)]
    widths = s * 1.0
    body = mc_lines(cv, [path], widths)
    sprite(cv, body, "#d8a030", line="#3a0e06", hi="#fff0a0", lo="#a8701a", e=2)
    # scales: little arcs in rows along the body
    sc = body & ~grow(~body, 2) & (((X // (s * 0.45)) + (Y // (s * 0.45))) % 2 == 0) & (((Y + X * 0.5) % (s * 0.45)) < 2)
    put(cv, sc, "#a8701a")
    # the spine fin: little triangles along the coil
    fin = np.zeros((h, w), bool)
    for (x0, y0), (x1, y1) in zip(path[1:-2], path[2:-1]):
        for f in (0.25, 0.75):
            fx_, fy_ = (x0 + (x1 - x0) * f) * w, (y0 + (y1 - y0) * f) * h - s * 0.75
            fin |= mc_poly(cv, [((fx_ - s * 0.35) / w, (fy_ + s * 0.4) / h), (fx_ / w, (fy_ - s * 0.45) / h), ((fx_ + s * 0.35) / w, (fy_ + s * 0.4) / h)])
    fin &= ~body
    sprite(cv, fin, "#e11d2e", line="#3a0e06")
    # claws: two little gold feet with three toes
    for fx_, fy_ in ((0.4, 0.36), (0.66, 0.58), (0.56, 0.8)):
        foot = mc_lines(cv, [[(fx_, fy_), (fx_ - 0.04, fy_ + 0.05)], [(fx_ - 0.04, fy_ + 0.05), (fx_ - 0.09, fy_ + 0.06)],
                             [(fx_ - 0.04, fy_ + 0.05), (fx_ - 0.05, fy_ + 0.09)], [(fx_ - 0.04, fy_ + 0.05), (fx_ + 0.01, fy_ + 0.09)]], s * 0.3)
        sprite(cv, foot & ~body, "#f0c84a", line="#3a0e06")
    # the tail tuft: a flame of gold and red
    tuft = mc_poly(cv, [(0.22, 0.92), (0.1, 0.9), (0.16, 0.95), (0.06, 0.97), (0.2, 0.98), (0.27, 0.95)])
    sprite(cv, tuft & ~body, "#e11d2e", line="#3a0e06", hi="#f0c84a")
    # the head: a long snout looking left, a horn and a whisker, a red eye, a pearl in front of it
    hx, hy = 0.74 * w, 0.14 * h
    head = disc(cv, hx, hy, s * 1.15, 1.15) | ((np.abs(Y - hy - s * 0.2) < s * 0.55) & (X > hx - s * 2.4) & (X < hx))
    jaw = (np.abs(Y - hy - s * 0.85) < s * 0.28) & (X > hx - s * 2.0) & (X < hx - s * 0.2)
    sprite(cv, head | jaw, "#f0c84a", line="#3a0e06", hi="#fff0a0", lo="#a8701a", e=2)
    horn = mc_lines(cv, [[(0.76, 0.115), (0.86, 0.06), (0.9, 0.03)]], s * 0.36)
    sprite(cv, horn & ~head, "#fff0a0", line="#3a0e06")
    whisk = mc_lines(cv, [[(0.56, 0.155), (0.48, 0.17), (0.43, 0.2), (0.4, 0.24)]], s * 0.22)
    put(cv, whisk & ~head & ~jaw, "#f0c84a")
    put(cv, disc(cv, hx - s * 0.1, hy - s * 0.25, s * 0.28), "#e11d2e", 0.8)
    put(cv, disc(cv, hx - s * 0.1, hy - s * 0.25, s * 0.12), "#ffffff")
    put(cv, (np.abs(Y - hy - s * 0.55) < 1) & (X > hx - s * 2.2) & (X < hx - s * 0.6), "#3a0e06")   # the mouth
    pearl = disc(cv, 0.3 * w, 0.14 * h, s * 0.6)
    sprite(cv, pearl, "#ffffff", line="#3a0e06", lo="#f0c84a")
    em_from(cv, pearl * 0.5, "#fff0a0")
    mc_crisp(cv, IMPERIAL_PAL)
    mc_cape_trim(cv, "#6a3a0a", "#f0c84a", stitch="#fff0a0")


item("imperial_dragon_cape", "Imperial Dragon Cape", "CAPE", "REGULAR", 0,
     "Deep navy silk with a long golden dragon coiling down it between curling clouds, chasing a pearl.",
     cape_bones_reg(imperial_outer, lining_hd("#142052", "#060a1e", "#d8a030")), layout=CAPE_REG, scale_cap=16, view=CAPE_VIEW)


# ---- Ace of Spades Cape: a black playing card with a crimson double border, a big spade and corner pips

SPADES_PAL = ["#2a2a34", "#33333e", "#3c3c48", "#464654", "#3a0408", "#6a0a12", "#a8121e", "#d81e2c", "#ff5a5a",
              "#ffd0d0", "#2e2e36"]


def gm_spade(cv, cx, cy, r):
    """A spade: a heart turned upside down with a flared stem (centre and size in texels)."""
    hp = heart_poly(cx, cy, r, rot=math.pi)
    m = pmask(cv, lambda d: d.polygon(hp, fill=255))
    X, Y = gm_grid(cv)
    stem = (np.abs(X - cx) < r * 0.14 + np.clip(Y - cy - r * 0.6, 0, None) * 0.55) & (Y > cy) & (Y < cy + r * 1.25)
    return m | stem


def spades_outer(cv):
    h, w, s = cv.h, cv.w, cv.s
    X, Y = gm_grid(cv)
    cv.arr[:] = bands(np.clip(0.75 - Y / h * 0.5, 0, 1) * gm_fold_shade(cv, 0.25), ["#2a2a34", "#33333e", "#3c3c48", "#464654"], 0.5)
    cv.arr[..., 3] = 1
    top = cape_top(cv)
    # the double border: a thick crimson frame and a thin one inside it, rounded corners
    def frame(inset, width):
        x0, x1, y0, y1 = inset, w - inset, top + inset, h - inset
        rr = s * 0.8
        inside = (X > x0) & (X < x1) & (Y > y0) & (Y < y1)
        for ccx, ccy in ((x0 + rr, y0 + rr), (x1 - rr, y0 + rr), (x0 + rr, y1 - rr), (x1 - rr, y1 - rr)):
            corner = (np.abs(X - ccx) > 0) & ((X - ccx) * np.sign(ccx - w / 2) > 0) & ((Y - ccy) * np.sign(ccy - h / 2) > 0)
            inside &= ~(corner & ((X - ccx) ** 2 + (Y - ccy) ** 2 > rr * rr))
        return inside & ~(grow(~inside, width) if width else ~inside)
    outer = frame(s * 0.45, 0) & ~frame(s * 0.45 + 7, 0)
    inner = frame(s * 1.05, 0) & ~frame(s * 1.05 + 3, 0)
    put(cv, outer, "#d81e2c", 0.5)
    put(cv, lit_rim(outer), "#ff5a5a")
    put(cv, inner, "#a8121e", 0.3)
    # the big spade
    sp = gm_spade(cv, w * 0.5, h * 0.5, s * 2.9)
    sprite(cv, sp, "#d81e2c", line="#3a0408", hi="#ff5a5a", lo="#a8121e", e=2)
    put(cv, disc(cv, w * 0.41, h * 0.42, s * 0.4) & sp, "#ffd0d0")       # a shine
    em_from(cv, sp * 0.4, "#d81e2c")
    # corner pips: a small spade over a letter A, top left and (upside down) bottom right
    A = ["01110", "10001", "10001", "11111", "10001", "10001"]
    for (ax, ay, flip) in ((s * 1.7, top + s * 1.6, False), (w - s * 1.7, h - s * 1.6, True)):
        k = max(2, s // 6)
        m = np.zeros((h, w), bool)
        for r_, row in enumerate(A):
            for c_, ch in enumerate(row):
                if ch == "1":
                    yy0 = ay + (r_ - 3) * k if not flip else ay - (r_ - 2) * k
                    xx0 = ax + (c_ - 2.5) * k if not flip else ax - (c_ - 1.5) * k
                    m |= (X >= xx0) & (X < xx0 + k) & (Y >= yy0) & (Y < yy0 + k)
        put(cv, m, "#d81e2c")
        mark = gm_spade(cv, ax, ay + s * 1.7, s * 0.55)
        if flip:                                        # the bottom corner is the top one turned half round
            mark = gm_spade(cv, w - ax, h - ay + s * 1.7, s * 0.55)[::-1, ::-1]
        put(cv, mark, "#d81e2c")
    mc_crisp(cv, SPADES_PAL)
    mc_cape_trim(cv, "#2a2a34", "#464654", stitch="#d81e2c")


item("ace_of_spades_cape", "Ace of Spades Cape", "CAPE", "REGULAR", 0,
     "A black playing card with a crimson double border, a big red spade in the middle and an ace in the corners.",
     cape_bones_reg(spades_outer, lining_hd("#3a0408", "#08080a", "#a8121e")), layout=CAPE_REG, scale_cap=16, view=CAPE_VIEW)


# ---- Snow Peak Cape: a snowy mountain under a pale winter sky, mirrored in a still blue lake

PEAK_PAL = ["#cfe6f6", "#b4d4ee", "#98c0e4", "#7ea8d6", "#ffffff", "#e6eef6", "#c4d2e2", "#8a9cb6", "#5a6c8a", "#3a4a66",
            "#263450", "#4a78b0", "#2e5a92", "#1c3e6e", "#9ad0f0", "#1a2a44", "#2e4a3e", "#20362e"]


def peak_outer(cv):
    h, w, s = cv.h, cv.w, cv.s
    X, Y = gm_grid(cv)
    t = Y / h
    hz = 0.6 * h
    cv.arr[:] = bands(np.clip(1 - t / 0.6, 0, 1), ["#7ea8d6", "#98c0e4", "#b4d4ee", "#cfe6f6"], 0.45)
    cv.arr[..., 3] = 1

    def ridge(xs, base, peak, jag, seed):
        u = X / w
        hgt = np.interp(u, xs, peak)
        r = np.random.default_rng(seed).normal(0, 1, 64)
        wob = np.interp(u * 63, np.arange(64), r) * jag
        return base - (hgt + wob) * h
    # far range (blue-grey), then the main peak in front
    far = Y > ridge([0, 0.15, 0.35, 0.6, 0.85, 1], hz, [0.12, 0.2, 0.1, 0.16, 0.24, 0.1], 0.006, 3)
    far &= Y < hz
    put(cv, far, "#8a9cb6")
    put(cv, far & (Y < ridge([0, 0.15, 0.35, 0.6, 0.85, 1], hz, [0.12, 0.2, 0.1, 0.16, 0.24, 0.1], 0.006, 3) + s * 0.6), "#c4d2e2")
    top_line = ridge([0, 0.2, 0.42, 0.5, 0.62, 0.8, 1], hz, [0.06, 0.16, 0.36, 0.42, 0.33, 0.14, 0.05], 0.004, 5)
    mtn = (Y > top_line) & (Y < hz)
    # light from the left: the left faces snow-white, the right faces in blue shadow, split along the ridge spurs
    lit = X < w * 0.5 + (Y - (hz - 0.42 * h)) * 0.25 + np.sin(Y / s * 1.7) * s * 0.4
    snow = Y < top_line + (hz - top_line) * (0.55 + 0.2 * np.sin(X / s * 1.3))
    put(cv, mtn & ~snow, "#3a4a66")
    put(cv, mtn & ~snow & lit, "#5a6c8a")
    put(cv, mtn & snow & lit, "#ffffff")
    put(cv, mtn & snow & ~lit, "#c4d2e2")
    put(cv, mtn & snow & ~lit & (pnoise(h, w, 9.0, 77, octaves=2) > 0.68), "#8a9cb6")   # rock showing through
    put(cv, grow(mtn) & ~mtn & (Y < hz) & ~far, "#e6eef6")
    # a dark pine line along the shore
    trees = (Y < hz) & (Y > hz - s * 0.5 - (np.abs(((X / (s * 0.45)) % 2) - 1)) * s * 0.7)
    put(cv, trees, "#20362e")
    put(cv, trees & lit_rim(trees), "#2e4a3e")
    # the lake: the whole view mirrored, bluer and darker, with clean ripple lines
    water = Y >= hz
    mirror = np.clip(2 * hz - Y, 0, h - 1).astype(int)
    src = cv.arr.copy()
    xi = np.clip(X - 0.5, 0, w - 1).astype(int)
    refl = src[mirror, xi]
    tone = mix(refl, hexc("#1c3e6e"), 0.45)
    cv.arr[water] = tone[water]
    rip = water & (((Y - hz) // max(2, s // 4)) % 4 == 0) & (np.sin(X / s * 1.1 + Y / s * 2.3) > 0.55)
    put(cv, rip, "#9ad0f0")
    put(cv, water & (Y < hz + 2), "#e6eef6")
    mc_crisp(cv, PEAK_PAL)
    mc_cape_trim(cv, "#263450", "#7ea8d6", stitch="#ffffff")


item("snow_peak_cape", "Snow Peak Cape", "CAPE", "REGULAR", 0,
     "A snowy mountain under a pale winter sky, pines along the shore, all mirrored in a still blue lake.",
     cape_bones_reg(peak_outer, lining_hd("#4a78b0", "#1a2a44", "#cfe6f6")), layout=CAPE_REG, scale_cap=16, view=CAPE_VIEW)


# ---- Winged Crest Cape: a golden eagle with spread wings, set on black with a gold border

CREST_PAL = ["#0a0a0c", "#121216", "#1a1a20", "#24242c", "#3a2406", "#6a4a0e", "#a8781a", "#d8a830", "#f8d44a", "#fff4b0",
             "#c81e1e"]


def crest_outer(cv):
    h, w, s = cv.h, cv.w, cv.s
    X, Y = gm_grid(cv)
    cv.arr[:] = bands(np.clip(0.6 - Y / h * 0.45, 0, 1) * gm_fold_shade(cv, 0.25), ["#0a0a0c", "#121216", "#1a1a20", "#24242c"], 0.5)
    cv.arr[..., 3] = 1
    cx, cy = 0.5, 0.46
    eagle = np.zeros((h, w), bool)
    lines = []
    for sd in (-1, 1):
        # a wing: a curved leading edge up to the tip, then five long primaries fanning back down to the body
        top = [(cx + sd * 0.05, cy - 0.03), (cx + sd * 0.12, cy - 0.15), (cx + sd * 0.24, cy - 0.22), (cx + sd * 0.38, cy - 0.27),
               (cx + sd * 0.45, cy - 0.27)]
        fingers = []
        for k in range(6):
            f = k / 5
            tip = (cx + sd * (0.45 - f * 0.3), cy - 0.25 + f * 0.33)
            notch = (cx + sd * (0.4 - f * 0.3), cy - 0.2 + f * 0.31)
            fingers += [tip, notch]
            lines.append([(cx + sd * 0.1, cy - 0.06 + f * 0.04), (tip[0] - sd * 0.03, tip[1] - 0.005)])
        eagle |= mc_poly(cv, top + fingers + [(cx + sd * 0.05, cy + 0.1)])
    body = disc(cv, cx * w, (cy + 0.04) * h, s * 1.25, 0.62)
    tail = mc_poly(cv, [(cx - 0.05, cy + 0.12), (cx + 0.05, cy + 0.12), (cx + 0.13, cy + 0.25), (cx + 0.06, cy + 0.23),
                        (cx, cy + 0.27), (cx - 0.06, cy + 0.23), (cx - 0.13, cy + 0.25)])
    neck = mc_poly(cv, [(cx - 0.06, cy - 0.04), (cx + 0.06, cy - 0.04), (cx + 0.05, cy - 0.1), (cx - 0.05, cy - 0.1)])
    head = disc(cv, (cx - 0.01) * w, (cy - 0.12) * h, s * 0.85, 1.15)
    beak = mc_poly(cv, [(cx - 0.07, cy - 0.14), (cx - 0.17, cy - 0.125), (cx - 0.17, cy - 0.09), (cx - 0.13, cy - 0.1),
                        (cx - 0.07, cy - 0.095)])
    talons = mc_lines(cv, [[(cx - 0.04, cy + 0.12), (cx - 0.08, cy + 0.17)], [(cx + 0.04, cy + 0.12), (cx + 0.08, cy + 0.17)]], s * 0.32)
    eagle |= body | tail | neck | head | talons
    sprite(cv, eagle, "#d8a830", line="#3a2406", hi="#fff4b0", lo="#a8781a", e=2)
    fl = mc_lines(cv, lines, 2) & eagle & ~grow(~eagle, 2)
    put(cv, fl, "#a8781a")
    tl = mc_lines(cv, [[(cx, cy + 0.14), (cx, cy + 0.25)], [(cx - 0.03, cy + 0.14), (cx - 0.07, cy + 0.23)],
                       [(cx + 0.03, cy + 0.14), (cx + 0.07, cy + 0.23)]], 2) & tail
    put(cv, tl, "#a8781a")
    put(cv, (np.abs(Y - (cy + 0.04) * h) < 1) & body & (np.abs(X - cx * w) < s * 0.9), "#a8781a")
    put(cv, grow(head) & ~head & ~body & ~neck & ~beak & (Y > (cy - 0.1) * h), "#3a2406")
    sprite(cv, beak, "#f8d44a", line="#3a2406", lo="#a8781a")
    put(cv, disc(cv, (cx - 0.04) * w, (cy - 0.135) * h, s * 0.2), "#c81e1e", 0.6)
    put(cv, disc(cv, (cx - 0.045) * w, (cy - 0.14) * h, s * 0.07), "#fff4b0")
    em_from(cv, eagle * 0.12, "#f8d44a")
    # a thin gold border inset
    top = cape_top(cv)
    inset = s * 0.6
    fr = ((np.abs(X - inset) < 1.5) | (np.abs(X - (w - inset)) < 1.5) | (np.abs(Y - (h - inset)) < 1.5) |
          (np.abs(Y - (top + inset)) < 1.5)) & (X > inset - 2) & (X < w - inset + 2) & (Y > top + inset - 2) & (Y < h - inset + 2)
    put(cv, fr, "#a8781a")
    for ccx, ccy in ((inset, top + inset), (w - inset, top + inset), (inset, h - inset), (w - inset, h - inset)):
        put(cv, (np.abs(X - ccx) + np.abs(Y - ccy)) < s * 0.4, "#f8d44a")
    mc_crisp(cv, CREST_PAL)
    mc_cape_trim(cv, "#3a2406", "#d8a830", stitch="#fff4b0")


item("winged_crest_cape", "Winged Crest Cape", "CAPE", "REGULAR", 0,
     "A golden eagle with its wings spread wide, set on black inside a fine gold border.",
     cape_bones_reg(crest_outer, lining_hd("#24242c", "#0a0a0c", "#d8a830")), layout=CAPE_REG, scale_cap=16, view=CAPE_VIEW)


# ---- Crimson Samurai Cape: crimson with gold edging, a round gold family crest and a band of gold waves at the hem

SAMURAI_PAL = ["#3a0408", "#5a0810", "#7a0c16", "#98121c", "#b41a24", "#cc2a2a", "#4a2a06", "#8a5a10", "#c8901e",
               "#f0c04a", "#fff0a0", "#1a0204"]


def samurai_outer(cv):
    h, w, s = cv.h, cv.w, cv.s
    X, Y = gm_grid(cv)
    v = np.clip(0.9 - Y / h * 0.5, 0, 1) * gm_fold_shade(cv, 0.2, 3.0, 0.4)
    cv.arr[:] = bands(v, ["#3a0408", "#5a0810", "#7a0c16", "#98121c", "#b41a24"], 0.45)
    cv.arr[..., 3] = 1
    # gold edging down both sides with a key pattern
    for xc in (s * 0.75, w - s * 0.75):
        band = np.abs(X - xc) < s * 0.42
        put(cv, band, "#c8901e")
        key = band & (((Y // (s * 0.5)) % 2 == 0) & (np.abs(X - xc) < s * 0.18) | (((Y % (s * 0.5)) < 2) & (np.abs(X - xc) < s * 0.3)))
        put(cv, key, "#4a2a06")
        put(cv, (np.abs(np.abs(X - xc) - s * 0.42) < 1), "#f0c04a")
    # the crest: a gold ring round three swirling commas (an old family-crest shape)
    cx, cy, R = w * 0.5, h * 0.38, s * 2.6
    d = np.sqrt((X - cx) ** 2 + (Y - cy) ** 2)
    ang = np.arctan2(Y - cy, X - cx)
    ring = (d < R) & (d > R * 0.82)
    put(cv, d < R * 0.82, "#3a0408")
    commas = np.zeros((h, w), bool)
    for k in range(3):
        a0 = k * math.tau / 3
        hx, hy = cx + math.cos(a0) * R * 0.3, cy + math.sin(a0) * R * 0.3
        commas |= disc(cv, hx, hy, R * 0.22)
        # each tail sweeps on round the centre, thinning to a point
        da = (ang - a0) % math.tau
        f = da / 2.0
        commas |= (f < 1) & (np.abs(d - R * (0.3 + 0.28 * f)) < R * 0.2 * (1 - f) + 0.6) & (d < R * 0.76)
    sprite(cv, ring | commas, "#f0c04a", line="#1a0204", hi="#fff0a0", lo="#c8901e", e=2)
    em_from(cv, (ring | commas) * 0.15, "#f0c04a")
    # the hem: rows of overlapping gold wave arcs
    hem = h * 0.8
    k = s * 1.25
    wave = np.zeros((h, w), bool)
    for row in range(4):
        yc = hem + row * k * 0.55 + k
        off = (row % 2) * k
        xx = ((X + off) % (2 * k)) - k
        dd = np.sqrt(xx ** 2 + (Y - yc) ** 2)
        arc = (dd < k) & (Y < yc) & (Y > hem)
        rings = arc & ((dd // (k / 3.2)) % 2 == 0)
        wave = (wave & ~arc) | rings
    put(cv, (Y > hem), "#5a0810")
    put(cv, wave, "#c8901e")
    put(cv, wave & lit_rim(wave), "#f0c04a")
    put(cv, np.abs(Y - hem) < 1.5, "#f0c04a")
    mc_crisp(cv, SAMURAI_PAL)
    mc_cape_trim(cv, "#4a2a06", "#c8901e", stitch="#fff0a0")


item("samurai_cloak", "Crimson Samurai Cape", "CAPE", "REGULAR", 0,
     "Deep crimson with gold edging, a round gold family crest of three swirls and gold waves rolling along the hem.",
     cape_bones_reg(samurai_outer, lining_hd("#7a0c16", "#1a0204", "#c8901e")), layout=CAPE_REG, scale_cap=16, view=CAPE_VIEW)


# ---------------------------------------------------------------------------------------------- capes (special, animated)

# ---- Glitch Koi Cape: two neon koi chase each other round dark water; now and then the signal tears and the picture
# slips sideways in bands, split into pink and cyan

KOI_PAL = ["#101638", "#161e48", "#1c2658", "#24306a", "#34407e", "#ff3ab8", "#ff8ad8", "#ffd8f4", "#a01a78", "#3af0ff",
           "#9af8ff", "#e0ffff", "#0a7a9a", "#ffffff", "#5a1a6a", "#1a5a7a", "#ffb03a", "#ffe08a"]


def koi_mask(cv, cx, cy, rx, ry, ang, direction=1):
    """A koi swimming round an ellipse: head at angle `ang`, the body trailing behind along the path, tapering to a
    forked tail, two side fins. Returns (body, fins, spots)."""
    h, w, s = cv.h, cv.w, cv.s
    X, Y = gm_grid(cv)
    body = np.zeros((h, w), bool)
    pts = []
    n = 14
    for j in range(n):
        a = ang - direction * j * 0.085
        px_, py_ = cx + math.cos(a) * rx, cy + math.sin(a) * ry
        pts.append((px_, py_))
        f = j / (n - 1)
        r = s * (0.95 * (1 - f) ** 0.7 * (0.75 + 0.25 * math.sin(min(1, f * 3) * math.pi / 2)) + 0.12)
        body |= (X - px_) ** 2 + (Y - py_) ** 2 < r * r
    # the forked tail at the end of the path
    (tx, ty), (bx, by) = pts[-1], pts[-3]
    dx, dy = tx - bx, ty - by
    L = math.hypot(dx, dy) + 1e-6
    dx, dy = dx / L, dy / L
    nx_, ny_ = -dy, dx
    tail = mc_poly(cv, [(tx / w, ty / h), ((tx + dx * s * 1.6 + nx_ * s * 1.0) / w, (ty + dy * s * 1.6 + ny_ * s * 1.0) / h),
                        ((tx + dx * s * 0.9) / w, (ty + dy * s * 0.9) / h),
                        ((tx + dx * s * 1.6 - nx_ * s * 1.0) / w, (ty + dy * s * 1.6 - ny_ * s * 1.0) / h)])
    # pectoral fins just behind the head
    (fx_, fy_), (gx, gy) = pts[3], pts[4]
    dx, dy = fx_ - gx, fy_ - gy
    L = math.hypot(dx, dy) + 1e-6
    dx, dy = dx / L, dy / L
    nx_, ny_ = -dy, dx
    fins = np.zeros((h, w), bool)
    for sd in (-1, 1):
        fins |= mc_poly(cv, [((fx_ + nx_ * sd * s * 0.6) / w, (fy_ + ny_ * sd * s * 0.6) / h),
                             ((fx_ + nx_ * sd * s * 1.5 - dx * s * 0.9) / w, (fy_ + ny_ * sd * s * 1.5 - dy * s * 0.9) / h),
                             ((fx_ + nx_ * sd * s * 0.5 - dx * s * 0.8) / w, (fy_ + ny_ * sd * s * 0.5 - dy * s * 0.8) / h)])
    spots = np.zeros((h, w), bool)
    for j, rr in ((2, 0.42), (6, 0.36), (9, 0.26)):
        spots |= disc(cv, pts[j][0] + nx_ * s * 0.15, pts[j][1] + ny_ * s * 0.15, s * rr)
    head = pts[0]
    return body | tail, fins & ~body, spots & body, head, (dx, dy)


def koi_outer(cv):
    h, w, s = cv.h, cv.w, cv.s
    X, Y = gm_grid(cv)
    f = int(round(cv.phase * 24)) % 24
    ph = f / 24
    # dark water: banded depth, scanlines, slow ripple rings that grow outwards once a loop
    v = np.clip(0.75 - Y / h * 0.5, 0, 1)
    cv.arr[:] = bands(v, ["#101638", "#161e48", "#1c2658", "#24306a"], 0.5)
    cv.arr[..., 3] = 1
    cv.arr[(Y.astype(int) // 2) % 3 == 0, :3] *= 0.85
    cx, cy = w * 0.5, h * 0.5
    for k in range(3):
        rr = ((ph + k / 3) % 1) * s * 5.5
        ring = np.abs(np.sqrt((X - cx) ** 2 + ((Y - cy) * 0.8) ** 2) - rr) < 1.2
        put(cv, ring & ((X.astype(int) // 3) % 2 == 0), "#34407e")
    # two koi, half a lap apart, one full lap per loop
    fish = []
    for k, (col, lite, core, deep) in enumerate((("#ff3ab8", "#ff8ad8", "#ffd8f4", "#a01a78"),
                                                 ("#3af0ff", "#9af8ff", "#e0ffff", "#0a7a9a"))):
        ang = ph * math.tau + k * math.pi
        body, fins, spots, head, d = koi_mask(cv, cx, cy, w * 0.27, h * 0.24, ang)
        fish.append((body | fins, col))
        # neon: a dark fill, a bright outline, glowing
        put(cv, body, deep)
        put(cv, fins, deep)
        put(cv, spots, "#ffb03a" if k == 0 else "#ffffff")
        edge = (body | fins) & grow(~(body | fins), 2)
        put(cv, edge, lite, 0.9)
        put(cv, grow(body | fins) & ~(body | fins), col, 0.8)
        # eyes on either side of the head
        nx_, ny_ = -d[1], d[0]
        for sd in (-1, 1):
            put(cv, disc(cv, head[0] + nx_ * sd * s * 0.5 + d[0] * s * 0.3, head[1] + ny_ * sd * s * 0.5 + d[1] * s * 0.3, s * 0.2), core, 1.0)
    # a soft chromatic ghost of each fish (the signal's colour split), a few texels off
    for m, col in fish:
        ghost = nshift(m, 0, 3 if col == "#ff3ab8" else -3) & ~m
        cv.arr[ghost, :3] = mix(cv.arr[ghost, :3], hexc(col)[:3], 0.35)
    # the glitch: on a few frames, bands of rows slip sideways with a pink/cyan split, and a bright tear line
    rng = np.random.default_rng(900 + f)
    if f in (3, 4, 11, 17, 18, 19):
        for _ in range(3 + (f % 3)):
            y0 = int(rng.integers(cape_top(cv) + 4, h - 10))
            hh = int(rng.integers(3, s))
            off = int(rng.integers(-s, s))
            band = cv.arr[y0:y0 + hh].copy()
            eb = cv.em[y0:y0 + hh].copy()
            cv.arr[y0:y0 + hh] = np.roll(band, off, axis=1)
            cv.em[y0:y0 + hh] = np.roll(eb, off, axis=1)
            cv.arr[y0, :, :3] = mix(cv.arr[y0, :, :3], hexc("#3af0ff")[:3], 0.6)
            cv.arr[min(h - 1, y0 + hh - 1), :, :3] = mix(cv.arr[min(h - 1, y0 + hh - 1), :, :3], hexc("#ff3ab8")[:3], 0.6)
        tear = int(rng.integers(cape_top(cv) + 4, h - 2))
        put(cv, (Y.astype(int) == tear) & (hash_noise(h, w, f) > 0.3), "#ffffff", 0.8)
    # pixel noise blocks in the corners, flickering every frame
    blk = (hash_noise(h // 4 + 1, w // 4 + 1, 40 + f)[(Y.astype(int) // 4), (X.astype(int) // 4)] > 0.994)
    put(cv, blk, "#3af0ff" if f % 2 else "#ff3ab8", 0.6)
    mc_crisp(cv, KOI_PAL)
    mc_cape_trim(cv, "#101638", "#34407e", stitch="#3af0ff")


item("glitch_koi_cape", "Glitch Koi Cape", "CAPE", "SPECIAL", 0,
     "Two neon koi, one pink and one cyan, chase each other round dark water while the signal glitches and tears.",
     cape_bones_hd(koi_outer, lining_hd("#121428", "#04040a", "#ff3ab8")), layout=CAPE_HD, view=CAPE_VIEW,
     animation=GM_SPECIAL_CAPE)


# ---- Void Eye Cape: a great violet eye in a swirling void; it looks one way, then the other, and blinks

VOID_PAL = ["#1a0a3a", "#24104e", "#301662", "#3e1c7a", "#542494", "#6e2cb0", "#6a1aa0", "#9a2ac8", "#c85af0", "#f0a8ff",
            "#ffe0ff", "#ffffff", "#ff3ad8", "#14041e", "#3a0a3a"]


def void_eye_outer(cv):
    h, w, s = cv.h, cv.w, cv.s
    X, Y = gm_grid(cv)
    f = int(round(cv.phase * 24)) % 24
    ph = f / 24
    cx, cy = w * 0.5, h * 0.46
    dx, dy = X - cx, (Y - cy) * 0.75
    r = np.sqrt(dx ** 2 + dy ** 2) / w
    ang = np.arctan2(dy, dx)
    # the void: a three-armed spiral turning a third of a turn per loop (so it loops), brighter towards the eye
    sw = 0.5 + 0.5 * np.sin(3 * (ang - ph * math.tau / 3) + r * 22)
    v = np.clip(1.05 - r * 1.0, 0, 1) * (0.55 + 0.45 * sw)
    cv.arr[:] = bands(v, ["#1a0a3a", "#24104e", "#301662", "#3e1c7a", "#542494", "#6e2cb0"], 0.45)
    cv.arr[..., 3] = 1
    em_from(cv, np.clip(v - 0.4, 0, 1) * 0.8, "#6e2cb0")
    # motes drifting into the eye along the arms
    for k in range(10):
        a = k * math.tau / 10 + ph * math.tau
        rr = (1 - ((ph * 2 + k * 0.37) % 1)) * w * 0.55 + w * 0.12
        mx, my = cx + math.cos(a) * rr, cy + math.sin(a) * rr / 0.75
        put(cv, disc(cv, mx, my, s * 0.16), "#c85af0", 0.8)
    # the look: centre, glance left and hold, glance right and hold, back; a blink in the middle of the loop
    look = [0, 0, -0.9, -1, -1, -1, -0.9, 0, 0, 0, 0, 0, 0, 0, 0.9, 1, 1, 1, 0.9, 0, 0, 0, 0, 0][f]
    lid = {9: 0.55, 10: 0.0, 11: 0.55}.get(f, 1.0)
    E, H = w * 0.42, s * 2.3 * lid
    lens = np.abs(dx) < E
    lens &= np.abs(Y - cy) < H * np.clip(1 - (dx / E) ** 2, 0, 1)
    # the lids and lashes round it: dark flesh with a bright rim
    lidm = np.abs(dx) < E * 1.12
    lidm &= np.abs(Y - cy) < (s * 2.3 + s * 0.8) * np.clip(1 - (dx / (E * 1.12)) ** 2, 0, 1)
    sprite(cv, lidm & ~lens, "#3a0a3a", line="#030108", hi="#9a2ac8", e=2)
    for k in range(7):
        a = math.pi + (k + 0.5) * math.pi / 7
        x0, y0 = cx + math.cos(a) * E * 0.95, cy + math.sin(a) * (s * 2.3 + s * 0.6) * 0.95
        put(cv, mc_lines(cv, [[(x0 / w, y0 / h), ((x0 + math.cos(a) * s * 0.9) / w, (y0 + math.sin(a) * s * 1.0) / h)]], 3), "#14041e")
    if lid > 0:
        # the sclera: glowing violet, brighter in the middle
        sc = np.clip(1 - np.sqrt((dx / E) ** 2 + ((Y - cy) / (s * 2.3)) ** 2), 0, 1)
        cv.arr[lens] = bands(sc, ["#6a1aa0", "#9a2ac8", "#c85af0", "#f0a8ff"], 0.4)[lens]
        em_from(cv, lens * 0.45, "#c85af0")
        ix = cx + look * E * 0.42
        di = np.sqrt((X - ix) ** 2 + (Y - cy) ** 2)
        iris = (di < s * 1.85) & lens
        cv.arr[iris] = bands(1 - di / (s * 1.85), ["#ff3ad8", "#ff8af0", "#ffe0ff"], 0.4)[iris]
        put(cv, iris & (di > s * 1.6), "#6a1aa0")
        em_from(cv, iris * 0.8, "#ff3ad8")
        pupil = (np.abs(X - ix) < s * 0.38 * np.clip(1 - ((Y - cy) / (s * 1.7)) ** 2, 0, 1) + 0.5) & (np.abs(Y - cy) < s * 1.7) & lens
        put(cv, pupil, "#030108")
        put(cv, disc(cv, ix - s * 0.8, cy - s * 0.7, s * 0.3) & lens, "#ffffff", 1.0)
    else:
        put(cv, (np.abs(Y - cy) < 2) & (np.abs(dx) < E), "#14041e")
    put(cv, grow(lens) & ~lens & (lid > 0), "#14041e")
    mc_crisp(cv, VOID_PAL)
    mc_cape_trim(cv, "#24104e", "#6e2cb0", stitch="#c85af0")


item("void_eye_cape", "Void Eye Cape", "CAPE", "SPECIAL", 0,
     "A great glowing eye in a swirling violet void. It looks one way, then the other, and every so often it blinks.",
     cape_bones_hd(void_eye_outer, lining_hd("#1c0a40", "#030108", "#9a2ac8")), layout=CAPE_HD, view=CAPE_VIEW,
     animation=GM_SPECIAL_CAPE)


# ---------------------------------------------------------------------------------------------- wings

# ---- Origami Sakura Wings: folded paper wings, each panel creased into a light and a shaded facet, printed with blossoms

ORI_LIGHT, ORI_SHADE, ORI_DEEP, ORI_EDGE = "#ffe4ee", "#f6bcd2", "#e88aae", "#b8507a"
ORI_BLOOM, ORI_BLOOM_D, ORI_HEART = "#ff7aa6", "#d8406e", "#ffd86a"


def gm_blossom(cv, x, y, r):
    """A five-petal blossom (centre and size in texels): petal mask and the centre."""
    pet = np.zeros((cv.h, cv.w), bool)
    for k in range(5):
        a = -math.pi / 2 + k * math.tau / 5
        pet |= disc(cv, x + math.cos(a) * r * 0.55, y + math.sin(a) * r * 0.55, r * 0.48)
    return pet, disc(cv, x, y, r * 0.28)


# Built on the arched-wing skeleton (aw_*): every flight feather and covert is a creased paper panel ("shard": angular,
# the crease down its middle splitting it into a lit and a shaded facet), pale pink paper printed with blossoms.
ORIGAMI = {"line": ORI_EDGE, "cols": ["#ffe8f1", "#ffcadf", "#f6a8c6"], "vane": 0.8, "hi": "#ffffff",
           "cov": [["#fff0f6", "#ffd8e7"], ["#ffe8f1", "#ffcfe2"]], "arm": ["#ffffff", "#f8dce8", "#c890a8"], "arm_w": 1.0}
ORI_ROWS = ((0.46, 10), (0.26, 8))


def origami_prints(F, feathers, at, size):
    """Blossoms printed on every other panel, `at` of the way out along it, and a few loose petals."""
    cv = F.cv
    paper = cv.arr[..., 3] > 0.5
    for i, (b, t, w) in enumerate(feathers):
        if i % 2:
            continue
        x, y = F.P(b[0] + (t[0] - b[0]) * at, b[1] + (t[1] - b[1]) * at)
        pet, heart = gm_blossom(cv, x, y, size * F.s)
        sprite(cv, pet & paper, ORI_BLOOM, hi="#ffb0cc", lo=ORI_BLOOM_D)
        put(cv, heart & paper, ORI_HEART)
        px_, py_ = F.P(b[0] + (t[0] - b[0]) * (at + 0.2), b[1] + (t[1] - b[1]) * (at + 0.2) + 0.6)
        put(cv, disc(cv, px_, py_, 0.22 * F.s, 1.6) & paper, ORI_BLOOM)


def origami_painters():
    flight, covs = aw_layout(13, ORI_ROWS)

    def fl(F):
        aw_paint_feathers(F, flight[::-1], ORIGAMI, "shard")
        origami_prints(F, flight, 0.62, 0.8)

    def co(F):
        for k, row in enumerate(covs):
            aw_paint_feathers(F, row[::-1], dict(ORIGAMI, cols=ORIGAMI["cov"][k]), "shard")
        origami_prints(F, covs[0], 0.55, 0.6)
        aw_arm_band(F, ORIGAMI, ORIGAMI["arm_w"])
    return fl, co


item("origami_sakura_wings", "Origami Sakura Wings", "WINGS", "REGULAR", 800,
     "Wings folded from pale pink paper, every panel creased sharp and printed with cherry blossoms.",
     aw_wing("panels", *origami_painters(), aw_rod(["#ffffff", "#f8dce8", "#c890a8"], "#8a3a5a"), wing_anim(30, 12, 1.4)),
     view=AW_VIEW)


# ---------------------------------------------------------------------------------------------- hats

# ---- Shadow Samurai Helm: a black-violet lacquered helmet with a flared neck guard, turned-back side flaps and a
# crescent crest that glows violet along its edge (the helmet only: your face stays your own)

SAM_LAC = ("#3a2e52", "#120c1c")
SAM_VIOLET, SAM_GLOW, SAM_GOLD = "#7a3ac8", "#c87aff", "#d8b050"


def sam_lacquer(face, cv):
    vgrad(cv, hexc(SAM_LAC[0]), hexc(SAM_LAC[1]))
    y, x = cv.grid()
    if face not in ("top", "bottom"):
        put(cv, (y % (cv.s * 1)) == 0, "#08060c")                     # the lames' lower edges
        put(cv, ((y % cv.s) == 1), "#4a3e66")
    rivet = ((x % (cv.s * 2)) == cv.s) & ((y % cv.s) == cv.s // 2)
    put(cv, grow(rivet) & ~rivet, "#5a4a2a")
    put(cv, rivet, SAM_GOLD)
    bevel(cv, 0.18, 0.3)


def sam_bowl(face, cv):
    vgrad(cv, hexc(SAM_LAC[0]), hexc(SAM_LAC[1]))
    y, x = cv.grid()
    # raised ridges where the bowl's plates meet, two units apart, with a lit edge
    put(cv, (x % (cv.s * 2)) == 0, "#0c0814")
    put(cv, (x % (cv.s * 2)) == 1, "#5a4a7a")
    if face != "top":
        put(cv, y < 2, "#4a3e66")
    bevel(cv, 0.2, 0.3)


def sam_band(face, cv):
    fill(cv, hexc("#22183a"))
    y, x = cv.grid()
    put(cv, (y == 0) | (y == cv.h - 1), SAM_GOLD)
    put(cv, (np.abs(y - cv.h / 2 + 0.5) < 1) & ((x // 3) % 2 == 0), SAM_VIOLET, 0.6)


def sam_crest(face, cv):
    """A crescent standing up from the brow, gold at its root and violet towards the tips, its edge glowing."""
    clear(cv)
    if face not in ("north", "south"):
        return
    h, w, s = cv.h, cv.w, cv.s
    X, Y = gm_grid(cv)
    cx, cy = w / 2, h * 0.62
    outer = ((X - cx) / (w * 0.5)) ** 2 + ((Y - cy) / (h * 0.62)) ** 2 < 1
    inner = ((X - cx) / (w * 0.36)) ** 2 + ((Y - cy + h * 0.1) / (h * 0.6)) ** 2 < 1
    moon = outer & ~inner & (Y < h * 0.86)
    t = Y / h
    put(cv, moon, SAM_VIOLET)
    put(cv, moon & (t > 0.55), "#9a5ae0")
    put(cv, moon & (t > 0.72), SAM_GOLD)
    edge = moon & grow(~moon)
    put(cv, edge & (t < 0.72), SAM_GLOW, 0.9)
    put(cv, edge & (t >= 0.72), "#fff0a0")
    # the boss at its root: a gold disc with a glowing violet stone
    boss = disc(cv, cx, h * 0.86, s * 1.25)
    sprite(cv, boss, SAM_GOLD, line="#3a2a0a", hi="#fff0a0", lo="#8a6a1a")
    gem = disc(cv, cx, h * 0.86, s * 0.6)
    put(cv, gem, SAM_GLOW, 1.0)
    put(cv, disc(cv, cx - s * 0.2, h * 0.86 - s * 0.2, s * 0.2), "#ffffff", 1.0)
    cv.arr[~(moon | boss | grow(moon) & False), 3] = 0
    cv.em[~(moon | boss)] = 0


def sam_flap(face, cv):
    sam_lacquer(face, cv)
    y, x = cv.grid()
    if face in ("north", "west", "east"):
        put(cv, disc(cv, cv.w / 2, cv.h / 2, cv.s * 0.55), SAM_GOLD)
        put(cv, disc(cv, cv.w / 2, cv.h / 2, cv.s * 0.25), SAM_GLOW, 0.8)


def samurai_helm():
    cubes = [Cube((-4, -9, -4), (8, 3, 8), sam_bowl, inflate=0.6),
             Cube((-3, -10, -3), (6, 1, 6), sam_bowl, inflate=0.6),
             Cube((-0.5, -11.4, -0.5), (1, 1, 1), sam_flap),
             Cube((-4, -6, -4), (8, 1, 8), sam_band, inflate=0.7),
             Cube((-5, -5.5, -5.4), (10, 1, 1), sam_lacquer)]                 # the brim over your brow
    # the neck guard: three flared tiers round the back and sides, each wider and lower than the last
    for k in range(3):
        y = -5 + k * 1.5
        o = 0.5 + k * 0.6
        cubes += [Cube((-4.5 - o, y, 4.0 + k * 0.5), (9 + 2 * o, 1, 1), sam_lacquer),
                  Cube((-5.5 - k * 0.6, y, -1.5 + k * 0.3), (1, 1, 5.5 + k * 0.2), sam_lacquer),
                  Cube((4.5 + k * 0.6, y, -1.5 + k * 0.3), (1, 1, 5.5 + k * 0.2), sam_lacquer)]
    helm = Bone("helm", "head", cubes=cubes)
    flap = Bone("flap_r", "head", (-5, -6, -3), (0, -30, 0), cubes=[Cube((-7, -7, -3.5), (2, 3, 1), sam_flap)])
    crest = Bone("crest", "head", cubes=[Cube((-4, -15, -5.8), (8, 8, 0), sam_crest)])
    return [helm, flap, mirrored_bone(flap, "flap_l"), crest]


item("shadow_samurai_helm", "Shadow Samurai Helm", "HAT", "REGULAR", 400,
     "A black-violet lacquered samurai helmet with a flared neck guard and a crescent crest that glows violet.",
     samurai_helm(), view=dict(HEAD_VIEW, glow="#a04aff"))


# ---------------------------------------------------------------------------------------------- auras

# ---- Storm Ring (special): a ring of crackling cyan lightning circles your waist, re-striking every frame, over a
# scorched rune circle on the ground

STORM_CORE, STORM_HOT, STORM_MID, STORM_DEEP = "#ffffff", "#bff8ff", "#3ae0ff", "#1a6aff"


def storm_ring_fn(ground):
    """A quarter of a storm ring in bold flat bands: a wide deep-blue glow band, a crisp bright rim on each side and one
    thick bolt of lightning running round it, re-struck every frame (its zigzag moves; the bands stay put)."""
    def fn(cv, X, Z):
        r = np.sqrt(X * X + Z * Z)
        ang = np.arctan2(Z, X)
        f = int(round(cv.phase * 16)) % 16
        rng = np.random.default_rng(4000 + f * 7 + ground)
        px_ = 1.0 / cv.h
        A = np.zeros_like(r)
        C = np.zeros(r.shape + (3,), np.float32)

        def paint(mask, col, a):
            nonlocal A
            C[mask] = hexc(col)[:3]
            A = np.where(mask, np.maximum(A, a), A)
        lo, hi = (0.66, 0.96) if ground else (0.72, 0.94)
        paint((r > lo) & (r < hi), STORM_DEEP, 0.5)
        paint((r > lo + 3 * px_) & (r < hi - 3 * px_), "#1a4ad8", 0.42)
        paint((np.abs(r - hi) < 2.5 * px_) | (np.abs(r - lo) < 2.5 * px_), STORM_MID, 0.95)
        if ground:
            # bold rune ticks between the rims
            tick = (r > lo + 6 * px_) & (r < hi - 6 * px_) & (np.abs(((ang / math.tau * 16) % 1) - 0.5) < 0.09)
            paint(tick, STORM_MID, 0.85)
        # the bolt: a broad, slow zigzag (two sines with fresh phases each frame), thick and flat-coloured
        mid = (lo + hi) / 2
        jag = 0.05 * np.sign(np.sin(ang * 12 + rng.random() * math.tau)) * np.abs(np.sin(ang * 12 + rng.random() * math.tau)) ** 0.3
        jag += 0.02 * np.sin(ang * 5 + rng.random() * math.tau)
        d = np.abs(r - (mid + jag))
        paint(d < 4.5 * px_, STORM_MID, 1.0)
        paint(d < 2.2 * px_, STORM_HOT, 1.0)
        paint(d < 1.0 * px_, STORM_CORE, 1.0)
        A *= r < 1.0
        A = levels(A, 5, 0.1) / 4.0
        cv.arr[..., :3] = C
        cv.arr[..., 3] = A
        em_rgb(cv, C, A)
    return fn


def storm_ring_bones():
    waist = sigil_bones(storm_ring_fn(0), R=13, spin=90, y=10.5, name="ring")
    ground = sigil_bones(storm_ring_fn(1), R=9, spin=-30, y=23.6, name="ground")
    return waist + ground


item("storm_ring_aura", "Storm Ring", "AURA", "SPECIAL", None,
     "A ring of crackling cyan lightning circles your waist and strikes again and again over a scorched ring at your feet.",
     storm_ring_bones(),
     fx={"type": "aura", "particle": "soft", "colors": ["#FFFFFF", "#7AF0FF", "#1A6AFF"], "rate": 5, "size": 0.45,
         "life": 0.6, "fade": 0.15, "sizeEnd": 0.2, "gravity": 0.0},
     view=dict(AURA_CLOSE, frame=[(-14.5, -22.5), (14.5, 6.5)]),
     animation={"frames": 16, "frametime": 1.5, "interpolate": False, "scale": 8})


# ---------------------------------------------------------------------------------------------- pets
# Small shoulder and hover pets on the pet engine (see the pets section above), chibi-sized: a big head that glances
# about, eyelids that blink, little parts on their own animations; check_pet_clearance() and check_pet_parts() prove
# none of it reaches into you or into itself.

# -------- Wolf Pup: a fluffy grey wolf pup in a red collar, sitting on your shoulder, ears twitching and tail wagging

WOLF_FUR, WOLF_DARK, WOLF_LIGHT, WOLF_NOSE, WOLF_COLLAR = "#a6aab2", "#666a74", "#eef0f2", "#1e1e24", "#d42a2a"
WOLF = (PERCH[0], PERCH[1], PERCH[2] - 0.25)


def wolf_fluff(A, col=WOLF_FUR):
    A.put(((A.x * 3 + A.y * 5) % 11 == 0), shade(col, 0.88))
    A.put(((A.x * 7 + A.y * 3) % 13 == 0), shade(col, 1.1))


def wolf_body(face, A):
    wolf_fluff(A)
    if face == "north":                        # 12 x 8: a fluffy pale chest
        A.ellipse(6, 6, 3.4, 5.5, WOLF_LIGHT)
        A.pix([(4, 3), (7, 4), (5, 6)], shade(WOLF_LIGHT, 0.88))
    elif face in ("top", "south"):             # a darker saddle down the back
        A.put(np.abs(A.x + 0.5 - A.w / 2) < A.w * 0.32, WOLF_DARK)
        A.put((np.abs(A.x + 0.5 - A.w / 2) < A.w * 0.32) & ((A.x + A.y) % 4 == 0), shade(WOLF_DARK, 1.2))
    elif face in ("east", "west"):
        A.put(A.y < 2, WOLF_DARK)
        A.ellipse(A.w / 2 + (2 if face == "east" else -2), A.h - 1.5, 4.5, 3.5, shade(WOLF_FUR, 0.92))   # haunch
    elif face == "bottom":
        A.fill(WOLF_LIGHT)


def wolf_head(face, A):
    wolf_fluff(A)
    if face == "north":                        # 16 x 12: a dark brow, pale cheeks and eye marks, amber eyes
        A.put((A.y < 3) & (np.abs(A.x + 0.5 - 8) < 3), WOLF_DARK)
        A.put((A.y >= 8) & ((A.x < 5) | (A.x >= 11)), WOLF_LIGHT)
        A.pix([(0, 7), (15, 7), (1, 7), (14, 7)], WOLF_LIGHT)
        A.rect(2, 3, 6, 4, WOLF_LIGHT)
        A.rect(10, 3, 14, 4, WOLF_LIGHT)
        eye(A, 2, 4, 4, 4, "#ffb43a", dark="#1a1208")
        eye(A, 10, 4, 4, 4, "#ffb43a", dark="#1a1208")
    elif face in ("east", "west"):
        A.put(A.y >= 8, WOLF_LIGHT)
        A.put((A.y < 2), WOLF_DARK)
    elif face == "top":
        A.put(np.abs(A.x + 0.5 - A.w / 2) < 3, WOLF_DARK)
    elif face == "south":
        A.put(A.y < 4, WOLF_DARK)


def wolf_snout(face, A):
    if face == "north":                        # 8 x 4: a black nose and a little mouth
        A.rect(2, 0, 6, 2, WOLF_NOSE)
        A.pix([(2, 0), (5, 0)], WOLF_LIGHT)
        A.pix([(3, 0)], "#6a6a74")
        A.pix([(3, 2), (4, 2)], "#4a4a52")
        A.pix([(2, 3), (5, 3)], "#4a4a52")
    elif face == "top":
        A.rect(2, A.h - 1, 6, A.h, WOLF_NOSE)
        A.put(A.y == 0, shade(WOLF_LIGHT, 0.9))


def wolf_leg(face, A):
    A.put(A.y >= A.h - 2, WOLF_LIGHT)
    if face == "north":
        A.pix([(1, A.h - 1), (2, A.h - 1)], shade(WOLF_LIGHT, 0.8))


def wolf_collar(face, A):
    """A red leather collar with stitching and a gold tag at the front."""
    A.put((A.x % 3 == 1) & (A.y == 1), shade(WOLF_COLLAR, 1.25))
    if face == "north":                        # 16 x 4
        A.rect(7, 1, 9, 4, "#ffd23a")
        A.pix([(7, 1)], "#fff4b0")
        A.pix([(8, 3)], "#b8861e")


def wolf_tail(face, A):
    """A bushy tail along +z: dark along the top, pale underneath."""
    if face in ("east", "west"):
        A.put(A.y < 2, WOLF_DARK)
        A.put(A.y >= A.h - 1, WOLF_LIGHT)
    elif face == "top":
        A.put(np.abs(A.x + 0.5 - A.w / 2) < 2, WOLF_DARK)
    elif face == "bottom":
        A.fill(WOLF_LIGHT)
    wolf_fluff(A, WOLF_FUR)


def wolf_pet():
    a = WOLF
    body = pet_body("wolf", a, [
        PC(a, (-1.5, -2.5, -1), (3, 2, 3), px(WOLF_FUR, wolf_body)),
        PC(a, (-1.25, -2, -1.5), (1, 2, 1), px(WOLF_FUR, wolf_leg)),
        PC(a, (0.25, -2, -1.5), (1, 2, 1), px(WOLF_FUR, wolf_leg)),
        PC(a, (-1.5, -1, 0.75), (1, 1, 1), px(WOLF_FUR, wolf_leg)),
        PC(a, (0.5, -1, 0.75), (1, 1, 1), px(WOLF_FUR, wolf_leg)),
        PC(a, (-2, -2.5, -1.5), (4, 1, 4), px(WOLF_COLLAR, wolf_collar))])
    head = Bone("head", "root", P(a, 0, -2.5, -0.75), cubes=[
        PC(a, (-2, -5.5, -2.25), (4, 3, 3), px(WOLF_FUR, wolf_head)),
        PC(a, (-1, -3.75, -3.25), (2, 1, 1), px(WOLF_LIGHT, wolf_snout))],
        anim={"type": "pet_head", "amp": 12}, parent="wolf")
    lids = blink_bone("lids", "head", a, (-1.5, -4.5, -2.27), (3, 1, 0), lid_paint(WOLF_FUR, [(0, 4), (8, 4)], WOLF_NOSE))
    ear = Bone("ear_r", "root", P(a, -1, -5.5, -0.75), (0, 0, 10), cubes=[
        PC(a, (-2, -7.5, -0.75), (2, 2, 0), ear_plane(WOLF_FUR, "#e8b4b8", WOLF_DARK, apex=0.4))],
        anim={"type": "sway", "amp": 7, "speed": 0.45}, parent="head")
    ears = pet_pair(ear, a)
    ears[1].anim["phase"] = 0.5                    # the ears twitch out together, not both the same way
    tail = Bone("tail", "root", P(a, 0, -1.25, 2), (32, 0, 0),
                [PC(a, (-1, -2.25, 2), (2, 2, 3), tail_tip(WOLF_FUR, WOLF_DARK, 3, 1, feat=wolf_tail))],
                anim=wag(18, 1.1), parent="wolf")
    return [body, head, lids] + ears + [tail]


item("wolf_pup", "Wolf Pup", "PET", "REGULAR", 500,
     "A fluffy grey wolf pup in a red collar with a gold tag. It sits on your shoulder, looks about, twitches its "
     "ears and wags its tail.",
     wolf_pet(), view=dict(PET_VIEW, glow="#9ab8ff"))


# -------- Panda Cub: a round panda cub sitting on your shoulder, black ears and arms, big eyes in dark patches

PANDA_W, PANDA_K, PANDA_G = "#f4f2ee", "#22222a", "#d8d4cc"
PANDA = (PERCH[0], PERCH[1], PERCH[2] - 0.25)


def panda_body(face, A):
    # a black band over the shoulders and down the arms, a white tummy
    A.put(A.y < 3, PANDA_K)
    if face == "north":
        A.ellipse(A.w / 2, A.h * 0.8, A.w * 0.32, A.h * 0.45, PANDA_W)
    else:
        # fur: the black saddle dips into a point down the back, tufts of shade in the white below it
        if face == "south":
            A.put((A.y == 3) & (np.abs(A.x + 0.5 - A.w / 2) < 3), PANDA_K)
            A.put((A.y == 4) & (np.abs(A.x + 0.5 - A.w / 2) < 1.5), PANDA_K)
        A.put((A.y == 0) & (A.x % 3 == 1), "#3a3a44")
        A.put((A.y >= 5) & ((A.x * 3 + A.y * 2) % 7 == 0), PANDA_G)
        A.put((A.y >= A.h - 1), shade(PANDA_W, 0.84))


def panda_head(face, A):
    if face == "north":                        # 16 x 12: tilted eye patches, eyes, a little blush
        for cx_, tilt in ((4.5, -1), (11.5, 1)):
            m = (((A.x + 0.5 - cx_) + (A.y + 0.5 - 5) * 0.4 * tilt) / 2.3) ** 2 + ((A.y + 0.5 - 4.9) / 2.6) ** 2 <= 1
            A.put(m, PANDA_K)
        eye(A, 3, 3, 3, 3, "#4a4a66", dark="#06060a", lid=PANDA_K)
        eye(A, 10, 3, 3, 3, "#4a4a66", dark="#06060a", lid=PANDA_K)
        A.rect(1, 8, 3, 9, "#ffb0c0")
        A.rect(13, 8, 15, 9, "#ffb0c0")
    elif face == "top":
        A.put((A.y < 2) & (A.x % 5 == 2), PANDA_G)
        A.put(((A.x * 2 + A.y * 3) % 7 == 0) & (A.y >= 2), PANDA_G)
        A.put((A.y == A.h - 1), shade(PANDA_W, 0.9))
    elif face in ("south", "east", "west"):    # fur tufts on the back and sides of the head
        A.put(((A.x * 2 + A.y * 3) % 7 == 0) & (A.y > 1), PANDA_G)
        A.put(A.y >= A.h - 1, shade(PANDA_W, 0.86))


def panda_muzzle(face, A):
    if face == "north":                        # 8 x 4: a black nose and a smile
        A.rect(3, 0, 5, 1, PANDA_K)
        A.pix([(4, 1), (3, 2), (5, 2)], PANDA_K)


def panda_leg(face, A):
    if face == "north":
        A.pix([(1, A.h - 2), (2, A.h - 2)], "#4a4a52")


def panda_pet():
    a = PANDA
    body = pet_body("panda", a, [
        PC(a, (-1.5, -2, -1), (3, 2, 3), px(PANDA_W, panda_body)),
        PC(a, (-1.75, -2, -1.5), (1, 2, 1), px(PANDA_K, panda_leg)),
        PC(a, (0.75, -2, -1.5), (1, 2, 1), px(PANDA_K, panda_leg)),
        PC(a, (-1.5, -1, 1), (1, 1, 1), px(PANDA_K)),
        PC(a, (0.5, -1, 1), (1, 1, 1), px(PANDA_K))])
    head = Bone("head", "root", P(a, 0, -2, -0.5), cubes=[
        PC(a, (-2, -5, -2), (4, 3, 3), px(PANDA_W, panda_head)),
        PC(a, (-1, -3.25, -2.5), (2, 1, 1), px(PANDA_W, panda_muzzle)),
        PC(a, (-2.25, -5.75, -1), (1, 1, 1), px(PANDA_K)),
        PC(a, (1.25, -5.75, -1), (1, 1, 1), px(PANDA_K))],
        anim={"type": "pet_head", "amp": 12}, parent="panda")
    lids = blink_bone("lids", "head", a, (-1.75, -4.25, -2.02), (3, 1, 0), lid_paint(PANDA_K, [(2, 3), (9, 3)], "#8a8a96"))
    return [body, head, lids]


item("panda_cub", "Panda Cub", "PET", "REGULAR", 600,
     "A round panda cub with big eyes in dark patches that sits on your shoulder, looks about and blinks.",
     panda_pet(), view=dict(PET_VIEW, glow="#9ae07a"))


# -------- Cave Bat: a little brown bat hovering by your shoulder on fluttering wings, big ears and two tiny fangs

BAT_FUR, BAT_DARK, BAT_LIGHT, BAT_MEM, BAT_MEM_D, BAT_EAR = "#7a5640", "#4a3224", "#b08a68", "#8a5a62", "#5a3440", "#e8a0a8"
BAT = (HOVER[0] + 1.25, HOVER[1] - 0.25, HOVER[2])


def bat_fluff(A, col=BAT_FUR):
    A.put(((A.x * 3 + A.y * 5) % 11 == 0), shade(col, 0.82))
    A.put(((A.x * 5 + A.y * 2) % 13 == 0), shade(col, 1.14))


def bat_body(face, A):
    bat_fluff(A)
    if face == "north":                        # 12 x 12: a fluffy pale chest
        A.ellipse(6, 6, 3.6, 5.5, BAT_LIGHT)
        A.put((((A.x + 0.5 - 6) / 3.6) ** 2 + ((A.y + 0.5 - 6) / 5.5) ** 2 < 0.8) & ((A.x * 3 + A.y * 5) % 7 == 0),
              shade(BAT_LIGHT, 0.86))
    elif face == "bottom":
        A.fill(BAT_LIGHT)


def bat_head(face, A):
    bat_fluff(A)
    if face == "north":                        # 16 x 12: a pale face, big dark eyes, a pink nose and two fangs
        A.ellipse(8, 7.5, 6.5, 4.8, BAT_LIGHT)
        A.put((A.y < 3) & (np.abs(A.x + 0.5 - 8) < 2.5), BAT_DARK)
        eye(A, 2, 3, 4, 4, "#ff9a3a", dark="#140a08")
        eye(A, 10, 3, 4, 4, "#ff9a3a", dark="#140a08")
        A.rect(7, 7, 9, 8, "#e07a86")
        A.pix([(7, 8), (8, 8)], "#8a4a50")
        A.pix([(5, 9), (10, 9)] + [(i, 10) for i in range(6, 10)], "#3a1e1e")
        A.pix([(6, 11), (9, 11)], "#fffbf0")
        A.rect(1, 8, 3, 9, "#ff9ab0")
        A.rect(13, 8, 15, 9, "#ff9ab0")
    elif face in ("top", "south"):
        A.put((A.y < 3) & (np.abs(A.x + 0.5 - A.w / 2) < 2.5), BAT_DARK)


def bat_ear(apex):
    """A tall pointed ear (a 0-deep plane): dark fur, a pink inside with a ridge."""
    def feat(face, A):
        m = A.poly([(apex, 0.0), (1.0, 0.85), (0.75, 1.0), (0.25, 1.0), (0.0, 0.85)])
        if face == "south":
            m = m[:, ::-1]
        if face == "north":
            ax = apex * A.w
            inner = m & (A.y >= 2) & (np.abs(A.x + 0.5 - ax - (A.y - 2) * (0.5 - apex) * 0.5) <= (A.y - 1) * 0.3)
            A.put(inner, BAT_EAR)
            A.put(inner & (A.y % 3 == 0), shade(BAT_EAR, 0.82))
        A.clip(m)
        A.rim(RIM, top_light=1.0, bottom_dark=1.0)
        A.norim[:] = True
    return px(BAT_FUR, feat)


def bat_wing(face, A):
    """A bat's wing (right hand: the shoulder at the right edge of the north face): a leathery membrane with a
    scalloped trailing edge between three finger bones, and a little thumb claw at the top."""
    m = A.poly([(1.0, 0.05), (0.62, 0.0), (0.3, 0.06), (0.02, 0.2), (0.0, 0.62), (0.16, 0.5), (0.3, 0.86),
                (0.48, 0.66), (0.66, 0.98), (0.8, 0.74), (1.0, 0.9)])
    xs = (A.x + 0.5) / A.w
    if face == "south":
        m, xs = m[:, ::-1], 1 - xs
    ys = (A.y + 0.5) / A.h
    A.a[..., :3] = ramp(ys, [(0, "#a8707a"), (1, BAT_MEM_D)])[..., :3]
    A.put(((A.x * 3 + A.y * 5) % 7 == 0) & (ys > 0.2), shade(BAT_MEM, 1.12))
    finger = np.zeros((A.h, A.w), bool)
    for (ex, ey) in ((0.01, 0.62), (0.3, 0.86), (0.66, 0.98)):
        d = np.abs((ey - 0.06) * (xs - 0.95) - (ex - 0.95) * (ys - 0.06)) / math.hypot(ey - 0.06, ex - 0.95)
        finger |= (d < 0.7 / A.w) & (xs >= ex - 0.02) & (xs <= 0.97)
    A.put(finger, BAT_DARK)
    A.put((ys < 0.14) & (xs > 0.03), BAT_FUR)
    A.put((ys < 0.14) & (xs < 0.12) & (xs > 0.02), "#f0e6d0")
    A.clip(m)
    A.rim(0.6, outline="#1a0e12")


def bat_foot(face, A):
    A.put(A.y >= A.h - 1, "#2a1a14")


def bat_pet():
    a = BAT
    body = pet_body("bat", a, [
        PC(a, (-1.5, -3, -1.25), (3, 3, 3), px(BAT_FUR, bat_body)),
        PC(a, (-1.25, 0, 0.25), (1, 1, 0), px(BAT_DARK, bat_foot)),
        PC(a, (0.25, 0, 0.25), (1, 1, 0), px(BAT_DARK, bat_foot))])
    body.anim = dict(PET_ANIM, amp=1.0, speed=1.8)
    head = Bone("head", "root", P(a, 0, -3, -0.25), cubes=[
        PC(a, (-2, -6, -1.75), (4, 3, 3), px(BAT_FUR, bat_head)),
        PC(a, (-2, -9, -0.5), (2, 3, 0), bat_ear(0.3)),
        PC(a, (0, -9, -0.5), (2, 3, 0), bat_ear(0.7))],
        anim={"type": "pet_head", "amp": 12}, parent="bat")
    lids = blink_bone("lids", "head", a, (-1.5, -5.25, -1.77), (3, 1, 0), lid_paint(BAT_FUR, [(0, 4), (8, 4)], BAT_DARK))
    wing = Bone("wing_r", "root", P(a, -2, -1.75, 0.25), (0, 0, 15), cubes=[
        PC(a, (-6, -2.5, 0.25), (4, 3, 0), px(BAT_MEM, bat_wing, rim=0))],
        anim={"type": "sway", "amp": 20, "speed": 1.9}, parent="bat")
    wings = pet_pair(wing, a)
    wings[1].anim["phase"] = 0.5                   # both wings beat down together
    return [body, head, lids] + wings


item("cave_bat", "Cave Bat", "PET", "REGULAR", 700,
     "A little brown cave bat with big ears and two tiny fangs that hovers by your shoulder on fluttering leathery "
     "wings, looking about and blinking.",
     bat_pet(), view=dict(PET_VIEW, glow="#ffa04a"))


# -------- Pocket Axolotl: a small pink axolotl swimming through the air by your shoulder, frilly gills and a curled tail

AX_PINK, AX_PINK_D, AX_BELLY, AX_GILL, AX_GILL_D, AX_INK = "#ffb6ca", "#e27a9c", "#ffe4ec", "#ee4a82", "#b0285a", "#2a1422"
AXOLOTL = (HOVER[0] + 0.25, HOVER[1], HOVER[2] + 0.25)


def ax_freckles(A):
    A.put(((A.x * 5 + A.y * 3) % 13 == 0), shade(AX_PINK, 0.9))
    A.put(((A.x * 3 + A.y * 7) % 17 == 5), "#ffd4e0")


def ax_body(face, A):
    ax_freckles(A)
    if face in ("east", "west"):               # a pale belly along the bottom
        A.put(A.y >= A.h - 2, AX_BELLY)
    elif face == "bottom":
        A.fill(AX_BELLY)
    elif face == "top":                        # a darker line down the spine
        A.put(np.abs(A.x + 0.5 - A.w / 2) < 1, AX_PINK_D)


def ax_head(face, A):
    ax_freckles(A)
    if face == "north":                        # 16 x 12: small beady eyes far apart, a wide smile, blush
        eye(A, 1, 3, 3, 3, "#5a2a3a", dark=AX_INK)
        eye(A, 12, 3, 3, 3, "#5a2a3a", dark=AX_INK)
        A.pix([(4, 7), (11, 7)] + [(i, 8) for i in range(5, 11)], AX_INK)
        A.pix([(7, 9), (8, 9)], AX_GILL_D)
        A.rect(1, 7, 3, 8, "#ff7aa0")
        A.rect(13, 7, 15, 8, "#ff7aa0")
    elif face == "bottom":
        A.fill(AX_BELLY)


def ax_gills(face, A):
    """Three feathery gill stalks fanning out from the side of the head (right hand: the head at the right edge of
    the north face), each fringed with darker filaments."""
    xs = (A.x + 0.5) / A.w
    if face == "south":
        xs = 1 - xs
    ys = (A.y + 0.5) / A.h
    stalk = np.zeros((A.h, A.w), bool)
    fringe = np.zeros((A.h, A.w), bool)
    for ty in (0.1, 0.45, 0.85):                  # each stalk runs from the root (right edge, middle) out to (0, ty)
        cy = 0.5 + (ty - 0.5) * (1 - xs)
        d = np.abs(ys - cy) * A.h
        stalk |= (d < 0.9) & (xs < 0.98)
        fringe |= (d < 1.9) & (xs < 0.85) & ((A.x + A.y) % 2 == 0)
    m = stalk | fringe
    A.put(fringe, AX_GILL_D)
    A.put(stalk, AX_GILL)
    A.put(stalk & (xs < 0.25), shade(AX_GILL, 1.18))
    A.clip(m)
    A.norim[:] = True


def ax_fin(face, A):
    """The tail fin (a 0-wide plane along the tail): a soft pink fin that curls up into a spiral at its tip."""
    zs = (A.x + 0.5) / A.w if face == "east" else 1 - (A.x + 0.5) / A.w
    ys = (A.y + 0.5) / A.h
    base = (np.abs(ys - 0.62) < 0.28 * (1 - zs * 0.55)) & (zs < 0.72)
    cx, cy, r = 0.78, 0.4, 0.2
    rr = np.hypot((zs - cx) * A.w, (ys - cy) * A.h) / (r * A.w)
    ang = np.arctan2(ys - cy, zs - cx)
    curl = (rr < 1.0) & ~((rr > 0.32) & (rr < 0.62) & (np.cos(ang - 1.2) > -0.2))
    m = base | curl
    A.a[..., :3] = rgbf(shade(AX_PINK, 1.04))
    A.put((rr < 1.0) & (rr > 0.78), AX_PINK_D)
    A.put(base & (A.x % 3 == 1), shade(AX_PINK, 0.88))
    A.clip(m)
    A.rim(0.78)
    A.norim[:] = True


def ax_ridge(face, A):
    A.put(A.x % 2 == 0, shade(AX_PINK, 0.9))
    A.clip(A.y >= (A.h - 1 - ((A.x // 2) % 2)))


def ax_leg(face, A):
    A.put(A.y >= A.h - 1, AX_PINK_D)
    if face == "north":
        A.pix([(0, A.h - 1), (2, A.h - 1)], shade(AX_PINK, 1.12))


def axolotl_pet():
    a = AXOLOTL
    body = pet_body("axolotl", a, [
        PC(a, (-1.5, -3, -1), (3, 2, 4), px(AX_PINK, ax_body)),
        PC(a, (-2, -1.75, -0.75), (1, 1, 1), px(AX_PINK, ax_leg)),
        PC(a, (1, -1.75, -0.75), (1, 1, 1), px(AX_PINK, ax_leg)),
        PC(a, (-2, -1.75, 1.75), (1, 1, 1), px(AX_PINK, ax_leg)),
        PC(a, (1, -1.75, 1.75), (1, 1, 1), px(AX_PINK, ax_leg)),
        PC(a, (0, -4, 0), (0, 1, 3), px(AX_PINK, ax_ridge, rim=0))])
    body.anim = dict(PET_ANIM, amp=1.0, speed=1.1)
    head = Bone("head", "root", P(a, 0, -2.5, -1), cubes=[
        PC(a, (-2, -5, -3.75), (4, 3, 3), px(AX_PINK, ax_head))],
        anim={"type": "pet_head", "amp": 12}, parent="axolotl")
    lids = blink_bone("lids", "head", a, (-2, -4.25, -3.77), (4, 1, 0), lid_paint(AX_PINK, [(1, 3), (12, 3)], AX_INK))
    gill = Bone("gill_r", "root", P(a, -2, -3.5, -2), cubes=[
        PC(a, (-4, -5, -2), (2, 3, 0), px(AX_GILL, ax_gills, rim=0))],
        anim={"type": "sway", "amp": 10, "speed": 0.8}, parent="head")
    gills = pet_pair(gill, a)
    gills[1].anim["phase"] = 0.5                   # the gills fan out and in together
    tail = Bone("tail", "root", P(a, 0, -2, 3), cubes=[
        PC(a, (-0.5, -2.5, 3), (1, 1, 2), px(AX_PINK, ax_body)),
        PC(a, (0, -4.25, 5), (0, 3, 3), px(AX_PINK, ax_fin, rim=0))],
        anim=wag(16, 0.8), parent="axolotl")
    return [body, head, lids] + gills + [tail]


item("axolotl_pal", "Pocket Axolotl", "PET", "REGULAR", 700,
     "A small pink axolotl with frilly gills and a curled tail that swims through the air beside your shoulder, its "
     "gills fanning as it looks about and blinks.",
     axolotl_pet(), view=dict(PET_VIEW, glow="#ff8ab0", margin=1.24))


# ---------------------------------------------------------------------------------------------- emotes
# Besides the part keyframes, these emotes use three newer emote features (client 2.8.1+, see the README): "root"
# keyframes turn the whole model about the hips and "offset" keyframes ([seconds, x, y, z] px, y down) move it, for
# flips and floating; "fx" adds particles while the emote plays (EmoteFx); and an emote with a model.json shows that
# model (a prop) only while it plays. Their thumbnails are drawn by gm_thumb_emote(): the pose with the whole-model turn,
# the prop, and a picture of the particles.

GM_HIP = np.array([0.0, 12.0, 0.0])


def gm_sprite(rows, cols):
    """A tiny pixel-art sprite from strings ('.' clear, a digit = an index into cols) as an RGBA float array."""
    h, w = len(rows), len(rows[0])
    a = np.zeros((h, w, 4), np.float32)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                a[y, x] = hexc(cols[int(ch)])
    return a


GM_HEART = gm_sprite([".11...11.", "1221.1121", "1222122211"[:9], "122222221", ".1222221.", "..12221..", "...121...", "....1...."],
                     ["#000000", "#c8102e", "#ff4a6a"])
GM_HEART[2, 2:4] = hexc("#ffd0d8")
GM_NOTE = gm_sprite(["...1111", "...1..1", "...1..1", "...1..1", ".111.11", "1111111", ".11..11"[:7]], ["#000000", "#ffffff"])
GM_FLAME = gm_sprite(["...1...", "..121..", "..121..", ".12321.", ".12321.", "1233321", "1234321", ".12321."],
                     ["#000000", "#c4300e", "#ff7a1a", "#ffc23a", "#fff4c0"])
GM_PUFF = gm_sprite(["..111..", ".12221.", "1222221", "1222221", "1222221", ".12221.", "..111.."], ["#000000", "#c8ccd8", "#ffffff"])
GM_DUST = gm_sprite([".111.", "12221", "12221", ".111."], ["#000000", "#9a8a72", "#d8ccb8"])
GM_SPARK = gm_sprite(["...1...", "...2...", "..121..", "1233321", "..121..", "...2...", "...1..."],
                     ["#000000", "#ffd23a", "#fff0a0", "#ffffff"])
GM_DROP = gm_sprite(["..1..", "..1..", ".121.", "12221", "12321", ".111."], ["#000000", "#2a6ab8", "#8ad0ff", "#ffffff"])
GM_PETAL = gm_sprite([".11..", "1221.", "12221", ".1221", "..11."], ["#000000", "#a8122a", "#ff5a6e"])


def gm_rune(k, col):
    rows = RUNES[k % len(RUNES)]
    return gm_sprite([r.replace("1", "0") for r in rows], [col])


def gm_billboard(scene, view, pos, size, tex, tint=None):
    """A camera-facing sprite at pos (entity space), `size` px tall; glowing unless its tint is "matte"."""
    glow = tint != "matte"
    tint = None if tint == "matte" else tint
    right, up = view[0], view[1]
    h, w = tex.shape[:2]
    sx, sy = size * w / h / 2, size / 2
    p = np.asarray(pos, float)
    pts = [p - right * sx + up * sy, p + right * sx + up * sy, p + right * sx - up * sy, p - right * sx - up * sy]
    col = np.ones(4, np.float32) if tint is None else hexc(tint)
    scene.quads.append((np.array(pts), np.array([(0, 0), (1, 0), (1, 1), (0, 1)], float), tex, None, col, glow))


def gm_root_matrix(root, offset):
    return translate(GM_HIP + np.asarray(offset, float)) @ rotate(*root) @ translate(-GM_HIP)


def gm_thumb_emote(it, size=THUMB, prop=None):
    v = it["view"]
    pose = v.get("pose") or emote_key_pose(it["emote"])
    Mr = gm_root_matrix(v.get("root", (0, 0, 0)), v.get("offset", (0, 0, 0)))
    scene = Scene()
    mats = {}
    for part, (pivot, origin, sz) in MANNEQUIN.items():
        rx, ry, rz = pose.get(part, (0, 0, 0))
        M = Mr @ translate(pivot) @ rotate(rx, ry, rz)
        mats[part] = M
        local = [o - pv for o, pv in zip(origin, pivot)]
        scene.cube(M, local, sz, SKIN_UV[part], 0, False, 64, 64, SKIN, np.ones(4, np.float32), False)
    add_shadow(scene)
    if prop is not None:
        tex, em, tw, th = prop
        placed = {}
        for b in it["bones"]:
            base = placed[b.parent] if b.parent else mats.get(ATTACH.get(b.attach), Mr)
            M = base @ translate(b.pivot) @ rotate(*b.rotation) @ translate([-q for q in b.pivot])
            placed[b.name] = M
            if (b.anim or {}).get("type") == "blink":
                lid = v.get("prop_lids")
                if not lid:
                    continue
            for c in b.cubes:
                scene.cube(M, c.origin, c.size, c.uv, c.inflate, c.mirror, tw, th, tex, np.ones(4, np.float32), b.emissive,
                           None if b.emissive else em, c.faces)
    yaw, pitch = v.get("yaw", -24), v.get("pitch", 6)
    R = rot_matrix(pitch, 0, 0) @ rot_matrix(0, yaw, 0)
    view = np.diag([1, -1, -1]) @ R
    for pos, sz, tex, tint in v.get("fx_pic", lambda: [])():
        gm_billboard(scene, view, pos, sz, tex, tint)
    im = render_scene(scene, yaw, pitch, size=size, frame=v.get("frame"), margin=v.get("margin", 1.10))
    return emote_light(im, v.get("glow", "#ffd36a"))


gm_thumb_emote_shipped = thumb_emote
gm_thumb_model_shipped = thumb_model


def gm_thumb_emote_any(it, size=THUMB):
    return gm_thumb_emote(it, size) if it["view"].get("gm") else gm_thumb_emote_shipped(it, size)


def gm_thumb_model_any(it, tex_arr, em_arr, tw, th, phase=None, size=THUMB):
    if it["slot"] == "EMOTE" and it["view"].get("gm"):
        return gm_thumb_emote(it, size, prop=(tex_arr, em_arr, tw, th))
    return gm_thumb_model_shipped(it, tex_arr, em_arr, tw, th, phase, size)


thumb_emote = gm_thumb_emote_any
thumb_model = gm_thumb_model_any


def gm_ring(n, r, y, jitter=0.0, seed=0):
    rng = np.random.default_rng(seed)
    out = []
    for k in range(n):
        a = k / n * math.tau + rng.normal(0, jitter)
        out.append((math.cos(a) * r, y + rng.normal(0, jitter * 4), math.sin(a) * r))
    return out


# ---- Breakdance: drop down, kick up into a handstand and spin round on your hands with your legs scissoring

BREAK_SPIN = [[0.9 + k * 0.2, 0, k * 45, 180] for k in range(9)]
item("breakdance", "Breakdance", "EMOTE", "REGULAR", 250,
     "Drop down, kick up into a handstand and spin round on your hands, legs scissoring, then flip back up.",
     emote={"length": 3.4, "loop": True, "parts": {
         "root": [[0, 0, 0, 0], [0.4, 10, 0, 0], [0.9, 0, 0, 180]] + BREAK_SPIN + [[2.9, 0, 360, 180], [3.4, 0, 360, 360]],
         "offset": [[0, 0, 0, 0], [0.4, 0, 5, 0], [0.65, 0, -6, 0], [0.9, 0, -10, 0], [2.7, 0, -10, 0], [3.15, 0, -4, 0], [3.4, 0, 0, 0]],
         "rightArm": [[0, 0, 0, 0], [0.4, -60, 0, 10], [0.9, -180, 0, -22], [2.7, -180, 0, -22], [3.4, 0, 0, 0]],
         "leftArm": [[0, 0, 0, 0], [0.4, -60, 0, -10], [0.9, -180, 0, 22], [2.7, -180, 0, 22], [3.4, 0, 0, 0]],
         "rightLeg": [[0, 0, 0, 0], [0.4, -60, 0, 0], [0.9, 0, 0, 25], [1.4, -30, 0, 40], [1.9, 30, 0, 15], [2.4, -30, 0, 40],
                      [2.9, 0, 0, 20], [3.4, 0, 0, 0]],
         "leftLeg": [[0, 0, 0, 0], [0.4, -20, 0, 0], [0.9, 0, 0, -25], [1.4, 30, 0, -15], [1.9, -30, 0, -40], [2.4, 30, 0, -15],
                     [2.9, 0, 0, -20], [3.4, 0, 0, 0]],
         "head": [[0, 0, 0, 0], [0.9, -20, 0, 0], [2.7, -20, 0, 0], [3.4, 0, 0, 0]]}},
     view={"gm": True, "pose": {"rightArm": (-180, 0, -22), "leftArm": (-180, 0, 22), "rightLeg": (-30, 0, 40),
                                "leftLeg": (30, 0, -15), "head": (-20, 0, 0)},
           "root": (0, 30, 180), "offset": (0, -10, 0), "yaw": -30, "pitch": 6, "glow": "#7ad8ff",
           "fx_pic": lambda: [(p, 2.2, GM_DUST, "matte") for p in gm_ring(10, 9, 23.2, 0.15, 3)]},
     thumb="emote")


# ---- Levitate: sit cross-legged in the air, rising and falling gently, with rune glyphs circling you

item("levitate", "Levitate", "EMOTE", "REGULAR", 250,
     "Sit cross-legged and float up off the ground, bobbing gently while glowing rune glyphs circle you.",
     emote={"length": 3.0, "loop": True, "parts": {
         "offset": [[0, 0, 0, 0], [0.6, 0, -9, 0], [1.8, 0, -12, 0], [3.0, 0, -9, 0]],
         "rightLeg": [[0, 0, 0, 0], [0.5, -88, 32, 0], [3.0, -88, 32, 0]],
         "leftLeg": [[0, 0, 0, 0], [0.5, -88, -32, 0], [3.0, -88, -32, 0]],
         "rightArm": [[0, 0, 0, 0], [0.5, -42, 0, 18], [1.8, -46, 0, 20], [3.0, -42, 0, 18]],
         "leftArm": [[0, 0, 0, 0], [0.5, -42, 0, -18], [1.8, -46, 0, -20], [3.0, -42, 0, -18]],
         "head": [[0, 0, 0, 0], [0.5, 8, 0, 0], [1.8, 4, 0, 0], [3.0, 8, 0, 0]]}},
     fx={"type": "spiral", "shape": "orbit", "particle": "enchant", "colors": ["#7AF0FF", "#3AC8FF"], "rate": 10, "radius": 1.0,
         "height": 1.1, "size": 0.6, "from": 0.5},
     view={"gm": True, "pose": {"rightLeg": (-88, 32, 0), "leftLeg": (-88, -32, 0), "rightArm": (-44, 0, 19),
                                "leftArm": (-44, 0, -19), "head": (6, 0, 0)},
           "offset": (0, -8, 0), "yaw": -24, "pitch": 10, "glow": "#3ad8ff", "frame": [(-15, -9), (15, 19)],
           "fx_pic": lambda: [(p, 3.2, gm_rune(k, "#7af0ff"), None) for k, p in enumerate(gm_ring(9, 12, 6, 0.12, 5))]},
     thumb="emote")


# ---- Heart Toss: a hand to your heart, then toss a heart out to someone

item("heart_toss", "Heart Toss", "EMOTE", "REGULAR", 250,
     "Bring a hand to your heart, then toss a little heart out to someone.",
     emote={"length": 2.2, "loop": False, "parts": {
         "rightArm": [[0, 0, 0, 0], [0.45, -70, -45, 0], [0.75, -70, -45, 0], [1.05, -112, 0, -14], [1.8, -112, 0, -14], [2.2, 0, 0, 0]],
         "leftArm": [[0, 0, 0, 0], [0.45, -45, 30, 0], [1.8, -45, 30, 0], [2.2, 0, 0, 0]],
         "head": [[0, 0, 0, 0], [0.45, 10, 0, 10], [1.05, -8, 0, -4], [1.8, -8, 0, -4], [2.2, 0, 0, 0]]}},
     fx={"type": "burst", "shape": "toss", "particle": "heart", "colors": ["#FF4A6A"], "rate": 10, "height": 1.5, "from": 0.95,
         "to": 1.4},
     view={"gm": True, "pose": {"rightArm": (-112, 0, -14), "leftArm": (-45, 30, 0), "head": (-8, 0, -4)},
           "yaw": 28, "pitch": 6, "glow": "#ff5a8a",
           "fx_pic": lambda: [((-6, -8, -17), 6.5, GM_HEART, None), ((-3, -15, -20), 3.2, GM_HEART, None),
                              ((-10, -14, -19), 2.4, GM_HEART, None)]},
     thumb="emote")


# ---- Pet the Cat: kneel down and pat a little white kitten, who closes its eyes and swishes its tail (the kitten is the
# emote's prop: it is only there while the emote plays)

KIT_W, KIT_G, KIT_PINK, KIT_EYE = "#f6f4f2", "#cfc8c4", "#ffb0c0", "#4a7ad8"
KIT = (-5.5, 17.0, -9.5)          # where the kitten sits, in the root's space while you kneel (7 px lowered)


def kit_head(face, A):
    if face == "north":                        # 16 x 12: happy closed eyes (it is being patted), a pink nose, whiskers
        A.pix([(3, 5), (4, 4), (5, 4), (6, 5), (9, 5), (10, 4), (11, 4), (12, 5)], "#3a3040")
        A.pix([(7, 7), (8, 7)], KIT_PINK)
        A.pix([(6, 8), (7, 9), (8, 9), (9, 8)], "#3a3040")
        A.rect(1, 7, 3, 8, KIT_PINK)
        A.rect(13, 7, 15, 8, KIT_PINK)
    elif face == "top":
        A.pix([(7, 1), (8, 1), (7, 2), (8, 2)], KIT_G)


def kit_body(face, A):
    if face in ("east", "west"):
        A.put(A.y >= A.h - 2, KIT_G)
    elif face == "north":
        A.ellipse(A.w / 2, A.h * 0.7, A.w * 0.3, A.h * 0.45, "#ffffff")


def kit_paw(face, A):
    if face == "north":
        A.pix([(1, A.h - 1), (2, A.h - 1)], KIT_PINK)


def kit_tail(face, A):
    A.put(A.y >= A.h - 1, KIT_G)


def kitten_prop():
    a = KIT
    body = Bone("kitten", "root", P(a, 0, 0, 0), cubes=[
        PC(a, (-1.5, -3, -1), (3, 3, 4), px(KIT_W, kit_body)),
        PC(a, (-1.5, -1, -1.75), (1, 1, 1), px(KIT_W, kit_paw)),
        PC(a, (0.5, -1, -1.75), (1, 1, 1), px(KIT_W, kit_paw))])
    head = Bone("kitten_head", "root", P(a, 0, -3, -0.5), cubes=[
        PC(a, (-2, -6, -2), (4, 3, 3), px(KIT_W, kit_head)),
        PC(a, (-2, -7, -0.5), (1, 1, 0), ear_plane(KIT_W, KIT_PINK, apex=0.3)),
        PC(a, (1, -7, -0.5), (1, 1, 0), ear_plane(KIT_W, KIT_PINK, apex=0.7))],
        anim={"type": "sway", "amp": 8, "speed": 0.8}, parent="kitten")
    tail = Bone("kitten_tail", "root", P(a, 0, -1, 2.75), (60, 0, 0),
                [PC(a, (-0.5, -1.5, 2.5), (1, 1, 4), px(KIT_W, kit_tail))], anim=wag(20, 0.7), parent="kitten")
    return [body, head, tail]


item("pet_the_cat", "Pet the Cat", "EMOTE", "REGULAR", 250,
     "Kneel down and give a little white kitten a pat. It closes its eyes and swishes its tail.",
     kitten_prop(),
     emote={"length": 2.4, "loop": True, "parts": {
         "offset": [[0, 0, 0, 0], [0.4, 0, 7, 0], [2.4, 0, 7, 0]],
         "rightLeg": [[0, 0, 0, 0], [0.4, -78, 8, 0], [2.4, -78, 8, 0]],
         "leftLeg": [[0, 0, 0, 0], [0.4, 62, 0, 0], [2.4, 62, 0, 0]],
         "rightArm": [[0, 0, 0, 0], [0.4, -44, 6, 0], [0.7, -36, 6, 0], [1.0, -46, 6, 0], [1.3, -36, 6, 0], [1.6, -46, 6, 0],
                      [1.9, -36, 6, 0], [2.4, -44, 6, 0]],
         "leftArm": [[0, 0, 0, 0], [0.4, -30, 0, -6], [2.4, -30, 0, -6]],
         "head": [[0, 0, 0, 0], [0.4, 26, 10, 0], [1.2, 30, 6, 6], [2.4, 26, 10, 0]]}},
     view={"gm": True, "pose": {"rightLeg": (-78, 8, 0), "leftLeg": (62, 0, 0), "rightArm": (-42, 6, 0), "leftArm": (-30, 0, -6),
                                "head": (28, 10, 0)},
           "offset": (0, 7, 0), "yaw": -62, "pitch": 12, "glow": "#ffb0d0",
           "fx_pic": lambda: [((-3.5, 12.0, -14.0), 3.0, GM_HEART, None)]},
     thumb="emote")


# ---- Fire Ring: a power stance, one fist raised, as a ring of fire roars up round your feet

item("fire_ring", "Fire Ring", "EMOTE", "REGULAR", 250,
     "Strike a power pose with one fist raised while a ring of fire roars up around your feet.",
     emote={"length": 2.0, "loop": True, "parts": {
         "rightArm": [[0, 0, 0, 0], [0.3, -150, 0, -25], [1.0, -158, 0, -22], [2.0, -150, 0, -25]],
         "leftArm": [[0, 0, 0, 0], [0.3, 10, 0, -38], [2.0, 10, 0, -38]],
         "rightLeg": [[0, 0, 0, 0], [0.3, 0, 0, 12], [2.0, 0, 0, 12]],
         "leftLeg": [[0, 0, 0, 0], [0.3, 0, 0, -12], [2.0, 0, 0, -12]],
         "head": [[0, 0, 0, 0], [0.3, -12, 0, 0], [1.0, -16, 0, 0], [2.0, -12, 0, 0]]}},
     fx={"type": "aura", "shape": "ring", "particle": "flame", "colors": ["#FF7A1A"], "rate": 24, "radius": 1.0, "from": 0.2},
     view={"gm": True, "pose": {"rightArm": (-152, 0, -24), "leftArm": (10, 0, -38), "rightLeg": (0, 0, 12), "leftLeg": (0, 0, -12),
                                "head": (-14, 0, 0)},
           "yaw": -26, "pitch": 18, "glow": "#ff7a1a",
           "fx_pic": lambda: [(p, 3.6 + (k % 3) * 0.9, GM_FLAME, None) for k, p in enumerate(gm_ring(16, 13, 21.5, 0.06, 9))]},
     thumb="emote")


# ---- Disco: point to the sky, point to the floor, hips swinging, notes floating up

item("disco", "Disco", "EMOTE", "REGULAR", 250,
     "Point up to the sky, then down to the floor, hips swinging to the beat while music notes float up.",
     emote={"length": 1.6, "loop": True, "parts": {
         "rightArm": [[0, -150, 0, -35], [0.4, -150, 0, -35], [0.8, -20, 0, -35], [1.2, -20, 0, -35], [1.6, -150, 0, -35]],
         "leftArm": [[0, 0, 0, -30], [0.8, 0, 0, -30], [1.6, 0, 0, -30]],
         "body": [[0, 0, -8, 0], [0.4, 0, 8, 0], [0.8, 0, -8, 0], [1.2, 0, 8, 0], [1.6, 0, -8, 0]],
         "rightLeg": [[0, -6, 0, 6], [0.4, 6, 0, 2], [0.8, -6, 0, 6], [1.2, 6, 0, 2], [1.6, -6, 0, 6]],
         "leftLeg": [[0, 6, 0, -2], [0.4, -6, 0, -6], [0.8, 6, 0, -2], [1.2, -6, 0, -6], [1.6, 6, 0, -2]],
         "head": [[0, -10, 0, 8], [0.8, 10, 0, -8], [1.6, -10, 0, 8]]}},
     fx={"type": "aura", "shape": "rise", "particle": "note", "colors": ["#FFFFFF"], "rate": 4, "radius": 0.9, "height": 2.0},
     view={"gm": True, "pose": {"rightArm": (-150, 0, -35), "leftArm": (0, 0, -30), "head": (-10, 0, 8), "rightLeg": (-6, 0, 6),
                                "leftLeg": (6, 0, -2)},
           "yaw": -24, "pitch": 6, "glow": "#c86aff",
           "fx_pic": lambda: [((-13, -14, -2), 4.0, GM_NOTE, "#ff6ad8"), ((10, -8, -3), 3.4, GM_NOTE, "#6ae0ff"),
                              ((11, 4, -1), 3.0, GM_NOTE, "#ffd23a"), ((-11, 2, -2), 2.8, GM_NOTE, "#7aff8a")]},
     thumb="emote")


# ---- Bring It: feet planted wide, both hands beckoning: come on, then

item("challenge", "Bring It", "EMOTE", "REGULAR", 250,
     "Plant your feet, chin up, and beckon with both hands. Come on, then.",
     emote={"length": 1.2, "loop": True, "parts": {
         "rightArm": [[0, -78, -18, 0], [0.3, -98, -18, 0], [0.6, -78, -18, 0], [0.9, -98, -18, 0], [1.2, -78, -18, 0]],
         "leftArm": [[0, -98, 18, 0], [0.3, -78, 18, 0], [0.6, -98, 18, 0], [0.9, -78, 18, 0], [1.2, -98, 18, 0]],
         "rightLeg": [[0, 0, 0, 14], [1.2, 0, 0, 14]],
         "leftLeg": [[0, 0, 0, -14], [1.2, 0, 0, -14]],
         "head": [[0, -10, 0, 6], [0.6, -14, 0, 6], [1.2, -10, 0, 6]]}},
     view={"gm": True, "pose": {"rightArm": (-82, -18, 0), "leftArm": (-96, 18, 0), "rightLeg": (0, 0, 14), "leftLeg": (0, 0, -14),
                                "head": (-12, 0, 6)},
           "yaw": -22, "pitch": 6, "glow": "#ff6a3a"},
     thumb="emote")


# ---- Laugh: clutch your sides and shake with laughter, little puffs bursting out

item("laugh", "Laugh", "EMOTE", "REGULAR", 250,
     "Clutch your sides, throw your head back and shake with laughter.",
     emote={"length": 2.4, "loop": False, "parts": {
         "root": [[0, 0, 0, 0]] + [[0.3 + k * 0.15, -6, 0, 3 if k % 2 else -3] for k in range(12)] + [[2.4, 0, 0, 0]],
         "rightArm": [[0, 0, 0, 0], [0.3, -30, -35, 0], [2.0, -30, -35, 0], [2.4, 0, 0, 0]],
         "leftArm": [[0, 0, 0, 0], [0.3, -30, 35, 0], [2.0, -30, 35, 0], [2.4, 0, 0, 0]],
         "head": [[0, 0, 0, 0]] + [[0.3 + k * 0.15, -24 if k % 2 else -16, 0, 0] for k in range(12)] + [[2.4, 0, 0, 0]]}},
     fx={"type": "aura", "shape": "puff", "particle": "poof", "colors": ["#FFFFFF"], "rate": 6, "height": 1.7, "from": 0.3, "to": 2.0},
     view={"gm": True, "pose": {"rightArm": (-30, -35, 0), "leftArm": (-30, 35, 0), "head": (-22, 0, 0)}, "root": (-6, 0, 3),
           "yaw": -22, "pitch": 6, "glow": "#ffd23a",
           "fx_pic": lambda: [((-9, -12, -4), 3.6, GM_PUFF, "matte"), ((-12, -7, -2), 2.4, GM_PUFF, "matte"),
                              ((9, -13, -4), 3.0, GM_PUFF, "matte"), ((12, -8, -2), 2.0, GM_PUFF, "matte")]},
     thumb="emote")


# ---- Hero Landing: drop out of the air into a crouch, one knee and one fist to the ground, dust bursting out

item("superhero_landing", "Hero Landing", "EMOTE", "REGULAR", 250,
     "Drop out of the air into a hero's crouch, one knee and one fist to the ground, dust bursting out around you.",
     emote={"length": 2.6, "loop": False, "parts": {
         "offset": [[0, 0, -26, 0], [0.45, 0, 8, 0], [2.1, 0, 8, 0], [2.6, 0, 0, 0]],
         "root": [[0, 0, 0, 0], [0.45, 10, 0, 0], [2.1, 10, 0, 0], [2.6, 0, 0, 0]],
         "rightArm": [[0, -170, 0, 20], [0.45, -14, 0, 4], [2.1, -14, 0, 4], [2.6, 0, 0, 0]],
         "leftArm": [[0, -170, 0, -20], [0.45, 35, 0, -45], [2.1, 35, 0, -45], [2.6, 0, 0, 0]],
         "rightLeg": [[0, 0, 0, 0], [0.45, -82, 10, 0], [2.1, -82, 10, 0], [2.6, 0, 0, 0]],
         "leftLeg": [[0, 0, 0, 0], [0.45, 55, 0, 0], [2.1, 55, 0, 0], [2.6, 0, 0, 0]],
         "head": [[0, -20, 0, 0], [0.45, -18, 0, 0], [2.1, -18, 0, 0], [2.6, 0, 0, 0]]}},
     fx={"type": "burst", "shape": "impact", "particle": "poof", "colors": ["#D8CCB8"], "count": 24, "at": 0.45},
     view={"gm": True, "pose": {"rightArm": (-14, 0, 4), "leftArm": (35, 0, -45), "rightLeg": (-82, 10, 0), "leftLeg": (55, 0, 0),
                                "head": (-18, 0, 0)},
           "root": (10, 0, 0), "offset": (0, 8, 0), "yaw": -34, "pitch": 10, "glow": "#ffb03a",
           "fx_pic": lambda: [(p, 2.6 + (k % 2), GM_DUST, "matte") for k, p in enumerate(gm_ring(14, 11, 22.6, 0.1, 13))]},
     thumb="emote")
# ============================================================================================== end of gemini concept collection


if __name__ == "__main__":
    main()
