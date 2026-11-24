#!/usr/bin/env python3
"""Builds Evade's starter cosmetics: item definitions, cuboid models, textures, thumbnails and the catalog index.

Everything here is original Evade art, drawn procedurally (gradients, noise, bevels and emissive details), so the set
can be rebuilt and tweaked from this one file. Output goes to two places with identical bytes:

  src/main/resources/assets/evade/cosmetics/   bundled in the client jar (starter set, works offline)
  site-src/cosmetics/                          the shop website's 3D previews read the same files

Per item, in <id>/:
  item.json     id, name, slot, rarity, colourable, defaultColor, partner, description, fx / emote data
  model.json    the cuboid model (format "evade-cosmetic-model/1", see site-src/cosmetics/README.md); not for
                particle-only items (trail, kill and win effects) or emotes
  texture.png   box-UV texture for model.json, TEX_SCALE texels per model unit
  thumb.png     128 x 128 preview used by the Wardrobe grid and the shop cards

And index.json: every item with the sha256 of each of its files (the client checks bundled and downloaded files
against it) plus the suggested test-mode prices.

Also draws the Wardrobe sidebar icon (assets/evade/textures/gui/nav/wardrobe*.png) in the menu's icon style.

Usage: python3 scripts/build-cosmetics.py   (needs Pillow and numpy)
"""
import hashlib
import json
import math
import pathlib
import shutil
import sys

import numpy as np
from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT_RES = ROOT / "src/main/resources/assets/evade/cosmetics"
OUT_SITE = ROOT / "site-src/cosmetics"
NAV = ROOT / "src/main/resources/assets/evade/textures/gui/nav"
FORMAT = "evade-cosmetic-model/1"
TEX_SCALE = 2          # texels per model unit (pixel): textures are drawn at twice the usual Minecraft density
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


def pack(bones):
    """Shelf-packs every cube's box-UV rectangle. Mirrored copies share their original's rectangle."""
    cubes = [c for b in bones for c in b.cubes if not hasattr(c, "share")]
    for width in (32, 64, 128, 256):
        if max(c.uv_size()[0] for c in cubes) > width:
            continue
        order = sorted(cubes, key=lambda c: (-c.uv_size()[1], -c.uv_size()[0]))
        x = y = shelf = 0
        placed = []
        for c in order:
            w, h = c.uv_size()
            if x + w > width:
                x, y, shelf = 0, y + shelf, 0
            placed.append((c, x, y))
            x += w
            shelf = max(shelf, h)
        height = y + shelf
        th = 16
        while th < height:
            th *= 2
        if th <= width * 2:
            for c, px, py in placed:
                c.uv = [px, py]
            for b in bones:
                for c in b.cubes:
                    if hasattr(c, "share"):
                        c.uv = c.share.uv
            return width, th
    raise SystemExit("texture too large")


# ============================================================================================== painting

class Canvas:
    """One face of one cube on the texture, in texels: arr[row, col] RGBA floats. Painters draw it as seen from
    outside the cube, top row up (item-space models are flipped for them)."""

    def __init__(self, arr, face, cube, flip):
        self.arr, self.face, self.cube, self.flip = arr, face, cube, flip
        self.h, self.w = arr.shape[0], arr.shape[1]

    def yy(self):
        return (np.arange(self.h, dtype=np.float32)[:, None] + 0.5) / max(1, self.h) * np.ones((1, self.w), np.float32)

    def xx(self):
        return np.ones((self.h, 1), np.float32) * ((np.arange(self.w, dtype=np.float32)[None, :] + 0.5) / max(1, self.w))


FACE_RECTS = {
    # name: (u offset, v offset, width, height) in units of (d, w, h): MC's box layout (see ModelPart.Cuboid)
    "top": lambda w, h, d: (d, 0, w, d),            # MC "DOWN" quad: the face at the cube's smallest y
    "bottom": lambda w, h, d: (d + w, 0, w, d),
    "west": lambda w, h, d: (0, d, d, h),
    "north": lambda w, h, d: (d, d, w, h),
    "east": lambda w, h, d: (d + w, d, d, h),
    "south": lambda w, h, d: (d + w + d, d, w, h),
}


def paint_model(bones, tw, th, item_space):
    img = np.zeros((th * TEX_SCALE, tw * TEX_SCALE, 4), np.float32)
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
                x0, y0 = (c.uv[0] + ox) * TEX_SCALE, (c.uv[1] + oy) * TEX_SCALE
                region = img[y0:y0 + fh * TEX_SCALE, x0:x0 + fw * TEX_SCALE]
                tmp = np.zeros_like(region)
                cv = Canvas(tmp, face, c, item_space and face not in ("top", "bottom"))
                c.paint(face, cv)
                if cv.flip:
                    tmp = tmp[::-1]
                region[:] = tmp
    return img


def to_image(arr):
    a = np.clip(arr, 0, 1)
    return Image.fromarray((a * 255 + 0.5).astype(np.uint8), "RGBA")


# ---------------------------------------------------------------------------------------------- materials

def noise(h, w, scale=1.0, seed=None):
    r = np.random.default_rng(seed) if seed is not None else RNG
    n = r.random((h, w), dtype=np.float32)
    if scale > 1:
        small = r.random((max(2, int(h / scale)), max(2, int(w / scale))), dtype=np.float32)
        n = np.array(Image.fromarray((small * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC), np.float32) / 255
    return n


def fill(cv, color):
    cv.arr[:] = color


def vgrad(cv, top, bottom, ease=False):
    t = cv.yy()
    if ease:
        t = smooth(t)
    cv.arr[:] = mix(top, bottom, t[..., None])


def bevel(cv, light=0.16, dark=0.22, width=1):
    """Lighter top/left edge and darker bottom/right edge: crafted, slightly raised faces."""
    a = cv.arr
    wpx = max(1, width)
    a[:wpx, :, :3] = np.clip(a[:wpx, :, :3] + light, 0, 1)
    a[:, :wpx, :3] = np.clip(a[:, :wpx, :3] + light * 0.6, 0, 1)
    a[-wpx:, :, :3] *= 1 - dark
    a[:, -wpx:, :3] *= 1 - dark * 0.7


def grain(cv, amount=0.06, scale=1.0):
    n = noise(cv.h, cv.w, scale) - 0.5
    cv.arr[..., :3] = np.clip(cv.arr[..., :3] + n[..., None] * amount, 0, 1)


def weave(cv, amount=0.05):
    y = np.arange(cv.h)[:, None]
    x = np.arange(cv.w)[None, :]
    pat = ((x + y) % 4 < 2).astype(np.float32) - 0.5
    cv.arr[..., :3] = np.clip(cv.arr[..., :3] + pat[..., None] * amount, 0, 1)


def specks(cv, color, density, seed, glow=False):
    r = np.random.default_rng(seed)
    m = r.random((cv.h, cv.w)) < density
    cv.arr[m] = color
    if glow:
        # a soft halo around each speck
        halo = np.zeros((cv.h, cv.w), np.float32)
        ys, xs = np.nonzero(m)
        for y, x in zip(ys, xs):
            y0, y1, x0, x1 = max(0, y - 1), min(cv.h, y + 2), max(0, x - 1), min(cv.w, x + 2)
            halo[y0:y1, x0:x1] += 0.35
        halo = np.clip(halo, 0, 1)[..., None]
        cv.arr[..., :3] = cv.arr[..., :3] * (1 - halo * 0.5) + color[:3] * halo * 0.5


def clear(cv):
    cv.arr[:] = 0


def solid_paint(color, shade=True):
    def p(face, cv):
        fill(cv, color)
        grain(cv, 0.04)
        if shade:
            bevel(cv)
    return p


def edge_paint(color, top_face_color=None):
    """Plain sides with a lighter top: for thin rims, straps and spars."""
    def p(face, cv):
        fill(cv, top_face_color if (top_face_color is not None and face == "top") else color)
        grain(cv, 0.05)
        bevel(cv, 0.12, 0.2)
    return p


# ============================================================================================== items

ITEMS = []


def item(id, name, slot, rarity, price, desc, bones=None, colourable=False, default_color=None, fx=None,
         emote=None, view=None, item_space=False, display=None, thumb=None):
    ITEMS.append(dict(id=id, name=name, slot=slot, rarity=rarity, price=price, desc=desc, bones=bones or [],
                      colourable=colourable, default_color=default_color, fx=fx, emote=emote, view=view or {},
                      item_space=item_space, display=display, thumb=thumb))


# ---------------------------------------------------------------------------------------------- capes
# Vanilla-sized cape cuboid (10 x 16 x 1) hung from the shoulders; the "cape" animation follows the player's movement
# the way a vanilla cape does. The north face is the outer side, the south face the lining.

def cape_bones(outer, lining, glow=None, tint=False):
    def p(face, cv):
        if face == "north":
            outer(cv)
        elif face == "south":
            lining(cv)
        else:
            outer(cv)
            cv.arr[..., :3] *= 0.72
    bones = [Bone("cape", "body", (0, 0, 2), cubes=[Cube((-5, 0, 1), (10, 16, 1), p)], anim={"type": "cape"}, tint=tint)]
    if glow is not None:
        def g(face, cv):
            if face == "north":
                glow(cv)
            else:
                clear(cv)
        bones.append(Bone("cape_glow", "body", (0, 0, 2), cubes=[Cube((-5, 0, 1), (10, 16, 1), g, inflate=0.05)],
                          anim={"type": "cape"}, emissive=True))
    return bones


def lining(color_top, color_bottom):
    def p(cv):
        vgrad(cv, hexc(color_top), hexc(color_bottom))
        weave(cv, 0.035)
        bevel(cv, 0.06, 0.12)
    return p


def ember_outer(cv):
    t = cv.yy()
    n = noise(cv.h, cv.w, 3.0, seed=11)
    # charcoal at the shoulders to molten orange at the hem, with licks of flame rising from the bottom
    flame = smooth((t + (n - 0.5) * 0.55 - 0.35) / 0.65)
    base = mix(hexc("#1a1012"), hexc("#7a1a10"), smooth(t * 1.3)[..., None])
    hot = mix(hexc("#ff5a1f"), hexc("#ffd36a"), smooth((t - 0.75) / 0.25)[..., None])
    cv.arr[:] = mix(base, hot, (flame ** 1.6)[..., None])
    weave(cv, 0.03)
    # a dark trim at the hem and shoulders
    cv.arr[:2, :, :3] *= 0.55
    cv.arr[-2:, :, :3] = hexc("#2a0c08")[:3]
    bevel(cv, 0.05, 0.1)


def ember_glow(cv):
    clear(cv)
    t = cv.yy()
    r = np.random.default_rng(5)
    m = (r.random((cv.h, cv.w)) < 0.06 * (t ** 1.5) * 2.2)
    cv.arr[m] = hexc("#ffcc66", 0.95)
    m2 = (r.random((cv.h, cv.w)) < 0.025 * t)
    cv.arr[m2] = hexc("#ff7a2a", 0.8)


def tidal_outer(cv):
    t, x = cv.yy(), cv.xx()
    deep = mix(hexc("#0c3b5e"), hexc("#041526"), t[..., None])
    cv.arr[:] = deep
    # rolling waves: bands of foam on sine crests
    for i, (phase, amp, y0) in enumerate([(0.0, 0.05, 0.28), (1.7, 0.06, 0.52), (3.1, 0.05, 0.76)]):
        crest = y0 + np.sin(x * math.pi * 3 + phase) * amp
        d = t - crest
        band = smooth(1 - np.abs(d) / 0.05) * (d < 0.02)
        under = smooth(1 - np.clip(d, 0, 1) / 0.14) * (d >= 0)
        cv.arr[..., :3] = mix(cv.arr[..., :3], hexc("#1f86b8")[:3], (under * 0.6)[..., None])
        cv.arr[..., :3] = mix(cv.arr[..., :3], hexc("#d9f6ff")[:3], (band * 0.9)[..., None])
    grain(cv, 0.04)
    cv.arr[:2, :, :3] = hexc("#e8d8a8")[:3]          # sand-gold trim at the collar
    bevel(cv, 0.05, 0.12)


def starlit_outer(cv):
    t, x = cv.yy(), cv.xx()
    sky = mix(hexc("#0b0a2a"), hexc("#3a1f6e"), smooth(t)[..., None])
    # a soft nebula band across the middle
    n = noise(cv.h, cv.w, 4.0, seed=3)
    neb = smooth(1 - np.abs(t - 0.55 - (x - 0.5) * 0.4 - (n - 0.5) * 0.2) / 0.22)
    sky[..., :3] = mix(sky[..., :3], hexc("#b04fc9")[:3], (neb * 0.45)[..., None])
    cv.arr[:] = sky
    specks(cv, hexc("#8f8fd8"), 0.04, 21)
    cv.arr[-2:, :, :3] = hexc("#c9a64a")[:3]          # gold hem
    cv.arr[:1, :, :3] = hexc("#c9a64a")[:3]
    bevel(cv, 0.04, 0.1)


def starlit_glow(cv):
    clear(cv)
    r = np.random.default_rng(9)
    ys, xs = np.nonzero(r.random((cv.h, cv.w)) < 0.018)
    for y, x in zip(ys, xs):
        cv.arr[y, x] = hexc("#ffffff", 1.0)
        for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            yy, xx = y + dy, x + dx
            if 0 <= yy < cv.h and 0 <= xx < cv.w and cv.arr[yy, xx, 3] < 0.5:
                cv.arr[yy, xx] = hexc("#bfc8ff", 0.55)
    # a small crescent near the top
    cy, cx, R = int(cv.h * 0.18), int(cv.w * 0.72), max(3, cv.w // 7)
    yy, xx = np.mgrid[0:cv.h, 0:cv.w]
    disc = (yy - cy) ** 2 + (xx - cx) ** 2 <= R * R
    bite = (yy - cy + 1) ** 2 + (xx - cx - R * 0.55) ** 2 <= (R * 0.9) ** 2
    cv.arr[disc & ~bite] = hexc("#fff1c2", 1.0)


def chevron_outer(cv):
    # light greys so the player's colour (tint) shows true; darker chevrons carry the pattern
    t, x = cv.yy(), cv.xx()
    cv.arr[:] = mix(hexc("#f2f2f2"), hexc("#bdbdbd"), t[..., None])
    for k in range(3):
        y0 = 0.30 + k * 0.2
        v = y0 + np.abs(x - 0.5) * -0.45
        band = (t > v) & (t < v + 0.07)
        cv.arr[band, :3] = hexc("#5c5c5c")[:3]
    weave(cv, 0.04)
    cv.arr[-2:, :, :3] = 0.3
    bevel(cv, 0.05, 0.12)


def chevron_lining(cv):
    vgrad(cv, hexc("#9a9a9a"), hexc("#7a7a7a"))
    weave(cv, 0.03)


item("ember_mantle", "Ember Mantle", "CAPE", "EPIC", 799,
     "A charcoal cape that smoulders into molten orange at the hem, with embers that glow in the dark.",
     cape_bones(ember_outer, lining("#2a1412", "#140808"), ember_glow), view={"yaw": 200})
item("tidal_weave", "Tidal Weave", "CAPE", "RARE", 499,
     "Deep-sea blue with rolling waves of foam and a sand-gold collar.",
     cape_bones(tidal_outer, lining("#0d2a40", "#061624")), view={"yaw": 200})
item("starlit_veil", "Starlit Veil", "CAPE", "LEGENDARY", 1199,
     "A night sky with a violet nebula, a crescent moon and stars that shine in the dark.",
     cape_bones(starlit_outer, lining("#1a1440", "#0b0a1e"), starlit_glow), view={"yaw": 200})
item("chevron_standard", "Chevron Standard", "CAPE", "COMMON", 299,
     "A clean banner cape with three chevrons, in any colour you like.",
     cape_bones(chevron_outer, chevron_lining, tint=True), colourable=True, default_color="#E11D2E", view={"yaw": 200})


# ---------------------------------------------------------------------------------------------- wings
# Swept back from the shoulder blades and semi-transparent, so they never hide the player's outline.

def aurora_membrane(face, cv):
    t, x = cv.yy(), cv.xx()
    # cyan at the root to violet at the tips, see-through towards the trailing edge
    col = mix(hexc("#3ef0d0"), hexc("#7b4dff"), smooth(x * 0.9 + t * 0.3)[..., None])
    col[..., 3] = 0.62 - 0.25 * smooth(t)
    cv.arr[:] = col
    # scalloped trailing edge
    edge = 0.78 + 0.1 * np.sin(x * math.pi * 5)
    cv.arr[t > edge] = 0
    grain(cv, 0.05, 2.0)


def aurora_veins(face, cv):
    clear(cv)
    t, x = cv.yy(), cv.xx()
    for k in range(4):
        line = np.abs(t - (0.1 + k * 0.2) - x * 0.15) < 0.035
        cv.arr[line & (t < 0.78)] = hexc("#c9fff4", 0.85)


def aurora_spar(face, cv):
    vgrad(cv, hexc("#e9fffb"), hexc("#8ef5e2"))
    bevel(cv, 0.1, 0.2)


def wings_aurora():
    right = [
        Bone("wing_r", "body", (-1.5, 2, 2.5), (0, 0, 0), [Cube((-13.5, 1, 2), (12, 13, 0), aurora_membrane)],
             anim={"type": "wing", "side": 1, "base": 32, "amp": 12, "speed": 1.6}, alpha=0.9),
        Bone("wing_r_glow", "body", (-1.5, 2, 2.5), (0, 0, 0),
             [Cube((-13.5, 1, 2), (12, 13, 0), aurora_veins, inflate=0.02),
              Cube((-13.5, 0, 1.5), (12, 1, 1), aurora_spar)],
             anim={"type": "wing", "side": 1, "base": 32, "amp": 12, "speed": 1.6}, emissive=True),
    ]
    left = [mirrored_bone(right[0], "wing_l", dict(right[0].anim, side=-1)),
            mirrored_bone(right[1], "wing_l_glow", dict(right[1].anim, side=-1))]
    return right + left


def feather_paint(c_root, c_tip, alpha):
    def p(face, cv):
        t, x = cv.yy(), cv.xx()
        col = mix(hexc(c_root), hexc(c_tip), smooth(t * 1.1)[..., None])
        # individual feathers: vertical shafts with slightly darker gaps
        shaft = (np.sin(x * math.pi * cv.w / TEX_SCALE / 1.5) > 0.6)
        col[..., :3] *= np.where(shaft, 1.08, 0.9)[..., None]
        col[..., 3] = alpha
        # rounded feather tips at the bottom edge
        tip = 0.86 + 0.12 * np.abs(np.sin(x * math.pi * cv.w / TEX_SCALE / 3))
        col[t > tip] = 0
        cv.arr[:] = np.clip(col, 0, 1)
        grain(cv, 0.06)
    return p


def wings_ashen():
    anim = {"type": "wing", "side": 1, "base": 38, "amp": 8, "speed": 1.1}
    right = [Bone("feathers_r", "body", (-1.5, 2, 2.5), (0, 0, 0), [
        Cube((-12, 0, 2), (10, 6, 0), feather_paint("#d8d4cc", "#8c857a", 0.92)),
        Cube((-13, 4, 2.2), (11, 7, 0), feather_paint("#a7a096", "#5c564f", 0.88)),
        Cube((-12, 9, 2.4), (9, 6, 0), feather_paint("#6b645d", "#ff6a2a", 0.85)),
        Cube((-12.5, -0.5, 1.6), (11, 1, 1), edge_paint(hexc("#e9e4dc"))),
    ], anim=anim)]
    right.append(Bone("feathers_r_glow", "body", (-1.5, 2, 2.5), (0, 0, 0),
                      [Cube((-12, 13, 2.45), (9, 2, 0), feather_paint("#ff8a3a", "#ffd36a", 0.9), inflate=0.01)],
                      anim=anim, emissive=True))
    left = [mirrored_bone(b, b.name.replace("_r", "_l"), dict(b.anim, side=-1)) for b in right]
    return right + left


item("aurora_wings", "Aurora Wings", "WINGS", "LEGENDARY", 1499,
     "See-through wings that fade from cyan to violet, with glowing veins and a slow, easy beat.",
     wings_aurora(), view={"yaw": 205, "pitch": 12})
item("ashen_feathers", "Ashen Feathers", "WINGS", "EPIC", 999,
     "Layered grey feathers that darken towards ember-lit tips.",
     wings_ashen(), view={"yaw": 205, "pitch": 12})


# ---------------------------------------------------------------------------------------------- hats

def knit(face, cv):
    t = cv.yy()
    fill(cv, hexc("#e6e6e6"))
    y = np.arange(cv.h)[:, None]
    x = np.arange(cv.w)[None, :]
    rib = ((x // 2) % 2 == 0).astype(np.float32)
    cv.arr[..., :3] *= (0.86 + 0.14 * rib)[..., None]
    v = ((x + y) % 4 == 0) & ((x - y) % 4 == 0)
    cv.arr[v, :3] *= 0.8
    if face not in ("top", "bottom"):
        cv.arr[..., :3] *= (1 - 0.12 * t)[..., None]
    bevel(cv, 0.06, 0.12)


def knit_band(face, cv):
    fill(cv, hexc("#d4d4d4"))
    x = np.arange(cv.w)[None, :] * np.ones((cv.h, 1))
    cv.arr[(x // 2) % 2 == 0, :3] *= 0.82
    bevel(cv, 0.1, 0.2)


def pompom(face, cv):
    fill(cv, hexc("#ffffff"))
    n = noise(cv.h, cv.w)
    cv.arr[..., :3] *= (0.85 + 0.15 * n)[..., None]


item("comet_cap", "Comet Cap", "HAT", "COMMON", 249,
     "A ribbed knit beanie with a glowing pom-pom, in any colour you like.",
     [Bone("beanie", "head", cubes=[Cube((-4, -9, -4), (8, 3, 8), knit, inflate=0.8),
                                    Cube((-4, -6.5, -4), (8, 1, 8), knit_band, inflate=1.05)], tint=True),
      Bone("pompom", "head", (0, -10, 0), cubes=[Cube((-1.5, -12.6, -1.5), (3, 3, 3), pompom)], emissive=True,
           anim={"type": "bob", "amp": 0.25, "speed": 2.0}, tint=True)],
     colourable=True, default_color="#3D7BFF", view={"yaw": 25, "pitch": 18, "focus": "head"})


def gold(face, cv):
    t = cv.yy()
    vgrad(cv, hexc("#ffe28a"), hexc("#b07a1e"))
    if face == "top":
        fill(cv, hexc("#ffe9a6"))
    grain(cv, 0.05)
    bevel(cv, 0.18, 0.3)


def circuit(face, cv):
    clear(cv)
    if face in ("top", "bottom"):
        return
    y = np.arange(cv.h)[:, None] * np.ones((1, cv.w))
    x = np.ones((cv.h, 1)) * np.arange(cv.w)[None, :]
    mid = cv.h // 2
    trace = (y == mid) & (x % 7 != 3)
    cv.arr[trace] = hexc("#3ef0ff", 0.95)
    nodes = (y == mid) & (x % 7 == 0)
    cv.arr[nodes] = hexc("#ffffff", 1.0)


def gem(face, cv):
    vgrad(cv, hexc("#9ffcff"), hexc("#1aa7d6"))
    cv.arr[0, :, :3] = 1.0


def crown_bones():
    band = [Cube((-5, -9.5, -5), (10, 2, 1), gold), Cube((-5, -9.5, 4), (10, 2, 1), gold),
            Cube((-5, -9.5, -4), (1, 2, 8), gold), Cube((4, -9.5, -4), (1, 2, 8), gold)]
    spikes = [Cube((x, -11.5, -5), (1, 2, 1), gold) for x in (-4, -1, 2)] + \
             [Cube((x, -11.5, 4), (1, 2, 1), gold) for x in (-4, 2)] + \
             [Cube((-5, -11.5, z), (1, 2, 1), gold) for z in (-2, 1)] + \
             [Cube((4, -11.5, z), (1, 2, 1), gold) for z in (-2, 1)]
    glow = [Cube((-5, -9.5, -5), (10, 2, 1), circuit, inflate=0.04), Cube((-5, -9.5, 4), (10, 2, 1), circuit, inflate=0.04),
            Cube((-1, -12.5, -5.3), (2, 2, 1), gem)]
    return [Bone("crown", "head", cubes=band + spikes), Bone("crown_glow", "head", cubes=glow, emissive=True)]


item("circuit_crown", "Circuit Crown", "HAT", "EPIC", 899,
     "A gold crown traced with glowing cyan circuitry and a crystal at the front.",
     crown_bones(), view={"yaw": 25, "pitch": 18, "focus": "head"})


# ---------------------------------------------------------------------------------------------- mask

def visor_shell(face, cv):
    t = cv.yy()
    vgrad(cv, hexc("#3a3f4a"), hexc("#14161b"))
    cv.arr[..., :3] += (0.18 * (1 - smooth(t * 3)))[..., None]   # glossy top highlight
    grain(cv, 0.03)
    bevel(cv, 0.12, 0.25)


def visor_slit(face, cv):
    clear(cv)
    if face != "north":
        return
    x = cv.xx()
    glow = 0.55 + 0.45 * np.cos((x - 0.5) * math.pi)
    cv.arr[:] = mix(hexc("#ff2a4a"), hexc("#ffb0bd"), (glow ** 3)[..., None])
    cv.arr[..., 3] = 1.0


def strap(face, cv):
    fill(cv, hexc("#22242a"))
    grain(cv, 0.05)
    bevel(cv, 0.08, 0.2)


item("dusk_visor", "Dusk Visor", "MASK", "RARE", 449,
     "A sleek gunmetal visor across the eyes with a glowing red slit.",
     [Bone("visor", "head", cubes=[Cube((-4.5, -6, -5), (9, 3, 1), visor_shell, inflate=0.1),
                                   Cube((-5, -5.5, -4), (1, 1, 8), strap, inflate=0.05),
                                   Cube((4, -5.5, -4), (1, 1, 8), strap, inflate=0.05)]),
      Bone("visor_glow", "head", cubes=[Cube((-3.5, -4.8, -5.2), (7, 1, 0), visor_slit)], emissive=True)],
     view={"yaw": 20, "pitch": 8, "focus": "head"})


# ---------------------------------------------------------------------------------------------- back item

def canvas_bag(face, cv):
    vgrad(cv, hexc("#5a7a4a"), hexc("#3a5230"))
    weave(cv, 0.05)
    grain(cv, 0.04)
    if face == "south":
        # front flap and a stitched seam
        flap = cv.yy() < 0.4
        cv.arr[flap, :3] *= 1.12
        seam = np.abs(cv.yy() - 0.4) < 0.04
        cv.arr[seam, :3] = hexc("#2a3a22")[:3]
        stitch = (np.abs(cv.yy() - 0.47) < 0.03) & ((np.arange(cv.w)[None, :] % 3) == 0)
        cv.arr[stitch, :3] = hexc("#d8caa0")[:3]
    bevel(cv, 0.1, 0.25)


def leather(face, cv):
    fill(cv, hexc("#6b4428"))
    grain(cv, 0.07)
    bevel(cv, 0.08, 0.2)


def lantern_frame(face, cv):
    fill(cv, hexc("#2b2b30"))
    if face in ("north", "south", "east", "west"):
        inner = (cv.yy() > 0.2) & (cv.yy() < 0.8) & (cv.xx() > 0.25) & (cv.xx() < 0.75)
        cv.arr[inner] = 0
    bevel(cv, 0.12, 0.2)


def lantern_core(face, cv):
    t = cv.yy()
    cv.arr[:] = mix(hexc("#fff2b0"), hexc("#ff9a2a"), t[..., None])


item("lantern_pack", "Lantern Pack", "BACK", "RARE", 549,
     "A sturdy canvas backpack with leather straps and a little lantern that glows at night.",
     [Bone("pack", "body", cubes=[Cube((-3, 1, 2), (6, 7, 3), canvas_bag), Cube((-2, 0, 2), (4, 1, 2), leather),
                                  Cube((-3.5, 6, 2.5), (7, 1, 2), leather, inflate=0.1)]),
      Bone("lantern", "body", (3.5, 7, 4), cubes=[Cube((3, 7, 3.5), (2, 3, 2), lantern_frame, inflate=0.05)],
           anim={"type": "sway", "amp": 6, "speed": 2.2}),
      Bone("lantern_glow", "body", (3.5, 7, 4), cubes=[Cube((3.5, 7.8, 4), (1, 1, 1), lantern_core, inflate=0.2)],
           anim={"type": "sway", "amp": 6, "speed": 2.2}, emissive=True)],
     view={"yaw": 210, "pitch": 10})


# ---------------------------------------------------------------------------------------------- auras
# A faint sigil turning just above the ground (low alpha, emissive) plus a few motes when Particle Amount allows.

def ring_paint(c_in, c_out, spokes, alpha, seed):
    def p(face, cv):
        clear(cv)
        if face not in ("top", "bottom"):
            return
        yy, xx = np.mgrid[0:cv.h, 0:cv.w].astype(np.float32)
        cx, cy = (cv.w - 1) / 2, (cv.h - 1) / 2
        dx, dy = (xx - cx) / (cv.w / 2), (yy - cy) / (cv.h / 2)
        r = np.sqrt(dx * dx + dy * dy)
        ang = np.arctan2(dy, dx)
        ring = smooth(1 - np.abs(r - 0.86) / 0.07) + 0.7 * smooth(1 - np.abs(r - 0.62) / 0.04)
        rays = smooth(1 - np.abs(np.sin(ang * spokes / 2)) / 0.12) * ((r > 0.62) & (r < 0.86))
        runes = (np.sin(ang * spokes * 3) > 0.7) & (np.abs(r - 0.74) < 0.03)
        a = np.clip(ring + rays * 0.8 + runes * 0.9, 0, 1) * alpha
        col = mix(hexc(c_in), hexc(c_out), smooth((r - 0.5) / 0.4)[..., None])
        col[..., 3] = a
        cv.arr[:] = col
    return p


def frost_paint(face, cv):
    clear(cv)
    if face not in ("top", "bottom"):
        return
    yy, xx = np.mgrid[0:cv.h, 0:cv.w].astype(np.float32)
    cx, cy = (cv.w - 1) / 2, (cv.h - 1) / 2
    dx, dy = (xx - cx) / (cv.w / 2), (yy - cy) / (cv.h / 2)
    r = np.sqrt(dx * dx + dy * dy)
    ang = np.arctan2(dy, dx)
    # six arms with branches: a snowflake
    arm = np.abs(np.sin(ang * 3)) * r
    arms = (arm < 0.035) & (r < 0.92)
    branch = np.zeros_like(r, bool)
    for rr in (0.45, 0.7):
        a2 = np.abs(np.sin((ang) * 3 + 0.0))
        branch |= (np.abs(r - rr - a2 * 0.0) < 0.15) & (np.abs(np.sin(ang * 6 + math.pi / 2)) * r < 0.03 + (r - rr) * 0.0)
    circle = np.abs(r - 0.92) < 0.03
    a = (arms | circle).astype(np.float32) * 0.55 + branch * 0.3
    col = mix(hexc("#e8fbff"), hexc("#6cc8ff"), r[..., None])
    col[..., 3] = np.clip(a, 0, 0.6)
    cv.arr[:] = col


def ember_mote(face, cv):
    cv.arr[:] = mix(hexc("#ffe08a"), hexc("#ff5a1f"), cv.yy()[..., None])


item("ember_orbit", "Ember Orbit", "AURA", "EPIC", 799,
     "A faint ring of fire turns at your feet while three embers circle your waist.",
     [Bone("ring", "root", (0, 23.6, 0), cubes=[Cube((-11, 23.6, -11), (22, 0, 22),
                                                     ring_paint("#ffd36a", "#ff4a1a", 8, 0.42, 1))],
           anim={"type": "spin", "speed": 24}, emissive=True),
      Bone("embers", "root", (0, 14, 0), cubes=[Cube((8.5, 13, -0.5), (1, 1, 1), ember_mote),
                                               Cube((-5.25, 15, 7.1), (1, 1, 1), ember_mote),
                                               Cube((-5.25, 11, -8.1), (1, 1, 1), ember_mote)],
           anim={"type": "spin", "speed": -70}, emissive=True)],
     fx={"type": "aura", "particle": "dust", "colors": ["#FFB347", "#FF5A1F"], "rate": 3, "size": 0.7},
     view={"yaw": 30, "pitch": 32})
item("frost_sigil", "Frost Sigil", "AURA", "RARE", 549,
     "A pale snowflake sigil slowly turns beneath you, with a few drifting flakes.",
     [Bone("sigil", "root", (0, 23.6, 0), cubes=[Cube((-11, 23.6, -11), (22, 0, 22), frost_paint)],
           anim={"type": "spin", "speed": -12}, emissive=True)],
     fx={"type": "aura", "particle": "snow", "colors": ["#E8FBFF"], "rate": 2, "size": 1.0},
     view={"yaw": 30, "pitch": 32})


# ---------------------------------------------------------------------------------------------- trail, effects

item("stardust_trail", "Stardust Trail", "TRAIL", "RARE", 449,
     "Glittering violet and gold dust drifts from your feet as you move.",
     fx={"type": "trail", "particle": "dust", "colors": ["#B98CFF", "#FFD36A", "#FFFFFF"], "rate": 10, "size": 0.8},
     thumb="trail")
item("shatterburst", "Shatterburst", "KILL_EFFECT", "EPIC", 699,
     "When you get a kill, crystal shards burst outward from where they fell.",
     fx={"type": "burst", "particle": "dust", "colors": ["#9FFCFF", "#3EF0D0", "#FFFFFF"], "count": 42, "size": 1.1,
         "spark": True},
     thumb="burst")
item("victory_spiral", "Victory Spiral", "WIN_EFFECT", "EPIC", 699,
     "Win a game and two golden spirals climb around you, ending in a burst of sparks.",
     fx={"type": "spiral", "particle": "dust", "colors": ["#FFD36A", "#FFF4C2", "#FF8A3A"], "count": 80, "size": 1.0,
         "height": 3.0},
     thumb="spiral")


# ---------------------------------------------------------------------------------------------- pet
# Sits in the air above your left shoulder (like a shoulder parrot, so it never pokes out round a corner).

def wisp_body(face, cv):
    t, x = cv.yy(), cv.xx()
    cv.arr[:] = mix(hexc("#a8f0ff"), hexc("#3a7bd6"), smooth(t)[..., None])
    grain(cv, 0.04)
    if face == "north":
        # two big friendly eyes and a little mouth
        for ex in (0.28, 0.72):
            eye = ((x - ex) ** 2 / 0.012 + (t - 0.45) ** 2 / 0.03) < 1
            cv.arr[eye, :3] = hexc("#0b1a33")[:3]
            shine = ((x - ex + 0.05) ** 2 / 0.002 + (t - 0.38) ** 2 / 0.004) < 1
            cv.arr[shine, :3] = 1.0
        mouth = (np.abs(t - 0.72) < 0.05) & (np.abs(x - 0.5) < 0.12)
        cv.arr[mouth, :3] = hexc("#0b1a33")[:3]
        blush = (((x - 0.15) ** 2 + (t - 0.66) ** 2) < 0.006) | (((x - 0.85) ** 2 + (t - 0.66) ** 2) < 0.006)
        cv.arr[blush, :3] = mix(cv.arr[blush, :3], hexc("#ff8ab0")[:3], 0.6)
    bevel(cv, 0.15, 0.2)


def wisp_flame(face, cv):
    t = cv.yy()
    cv.arr[:] = mix(hexc("#ffffff"), hexc("#6cf2ff"), t[..., None])
    cv.arr[..., 3] = 0.9


item("pip_wisp", "Pip the Wisp", "PET", "EPIC", 899,
     "A tiny friendly wisp that bobs along above your shoulder, with a flickering flame on its head.",
     [Bone("wisp", "body", (7, -6, 1), cubes=[Cube((5, -8, -1), (4, 4, 4), wisp_body)],
           anim={"type": "pet", "amp": 0.8, "speed": 2.0}),
      Bone("wisp_flame", "body", (7, -6, 1), cubes=[Cube((6, -10, 0), (2, 2, 2), wisp_flame),
                                                    Cube((6.5, -11.5, 0.5), (1, 2, 1), wisp_flame)],
           anim={"type": "pet", "amp": 0.8, "speed": 2.0}, emissive=True)],
     view={"yaw": 330, "pitch": 10, "focus": "shoulder"})


# ---------------------------------------------------------------------------------------------- emotes
# Keyframes: [seconds, pitch, yaw, roll] in degrees for each model part, replacing its usual angles while it plays.

item("sunny_wave", "Sunny Wave", "EMOTE", "COMMON", 199,
     "A big, cheerful wave hello.",
     emote={"length": 1.6, "loop": True, "parts": {
         "rightArm": [[0, -170, 0, 15], [0.4, -170, 0, -15], [0.8, -170, 0, 15], [1.2, -170, 0, -15], [1.6, -170, 0, 15]],
         "head": [[0, 0, 0, 0], [0.8, 0, 0, 6], [1.6, 0, 0, 0]]}},
     thumb="emote")
item("drum_solo", "Drum Solo", "EMOTE", "RARE", 399,
     "Air drums: alternate hits with a head bob to the beat.",
     emote={"length": 1.0, "loop": True, "parts": {
         "rightArm": [[0, -50, 0, 0], [0.25, -85, 0, 0], [0.5, -50, 0, 0], [0.75, -60, 0, 0], [1.0, -50, 0, 0]],
         "leftArm": [[0, -60, 0, 0], [0.25, -50, 0, 0], [0.5, -60, 0, 0], [0.75, -85, 0, 0], [1.0, -60, 0, 0]],
         "head": [[0, 10, 0, 0], [0.25, -4, 0, 0], [0.5, 10, 0, 0], [0.75, -4, 0, 0], [1.0, 10, 0, 0]]}},
     thumb="emote")


# ---------------------------------------------------------------------------------------------- item skin
# Item space: y up, 0..16 like an item model. Drawn instead of your own sword, on your screen only.

def obsidian(face, cv):
    t = cv.yy()
    vgrad(cv, hexc("#3b2a5c"), hexc("#120c1f"))
    n = noise(cv.h, cv.w, 2.0, seed=4)
    cv.arr[..., :3] += ((n > 0.82) * 0.25)[..., None]          # glassy glints
    bevel(cv, 0.18, 0.25)


def hilt(face, cv):
    fill(cv, hexc("#2a2a30"))
    wrap = (np.arange(cv.h)[:, None] % 3 == 0) * np.ones((1, cv.w))
    cv.arr[wrap > 0, :3] = hexc("#6a5a9a")[:3]
    bevel(cv, 0.1, 0.2)


def silver(face, cv):
    vgrad(cv, hexc("#f0f0ff"), hexc("#8a8aa8"))
    bevel(cv, 0.15, 0.25)


def blade_core(face, cv):
    t = cv.yy()
    cv.arr[:] = mix(hexc("#f3d6ff"), hexc("#a34dff"), t[..., None])


SWORD_ANIM = None
item("nightglass_blade", "Nightglass Blade", "ITEM_SKIN", "LEGENDARY", 1299,
     "Your sword, re-forged in dark glass with a glowing violet core. Shows on swords you hold, on your screen.",
     [Bone("blade", "item", (8, 8, 8), (0, 0, -45), [
         Cube((7, -1, 7), (2, 2, 2), silver),                 # pommel
         Cube((7.5, 1, 7.5), (1, 4, 1), hilt, inflate=0.1),   # grip
         Cube((4.5, 5, 7), (7, 1, 2), silver),                # guard
         Cube((7, 6, 7.5), (2, 11, 1), obsidian),             # blade
         Cube((7.5, 17, 7.5), (1, 1, 1), obsidian)]),         # tip
      Bone("blade_glow", "item", (8, 8, 8), (0, 0, -45), [Cube((7.5, 6.5, 7.4), (1, 10, 1), blade_core, inflate=0.05)],
           emissive=True)],
     item_space=True,
     display={"thirdperson_righthand": {"rotation": [0, -90, 55], "translation": [0, 4.0, 0.5], "scale": [0.85, 0.85, 0.85]},
              "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]}},
     view={"yaw": 0, "pitch": 0, "focus": "item"})


# ============================================================================================== serialisation

def model_json(it, tw, th):
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
         "textureScale": TEX_SCALE, "bones": bones}
    if it["display"]:
        m["display"] = it["display"]
    return m


def item_json(it):
    j = {"id": it["id"], "name": it["name"], "slot": it["slot"], "rarity": it["rarity"], "colourable": it["colourable"],
         "defaultColor": it["default_color"], "partner": None, "description": it["desc"]}
    if it["fx"]:
        j["fx"] = it["fx"]
    if it["emote"]:
        j["emote"] = it["emote"]
    return j


# ============================================================================================== thumbnails
# A small software renderer: the same cuboid quads and UVs as ModelPart.Cuboid, orthographic, painter-sorted, with
# a grey mannequin for context.

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
        # ModelPart.Quad gives each corner its UV, then reverses the corner order when mirrored: each corner keeps
        # its UV, so the mapping (all the renderer below needs) is the same either way.
        uvs = [(u2, va), (u1, va), (u1, vb), (u2, vb)]
        out.append((np.array(verts, float), np.array([(a / tw, b / th) for a, b in uvs], float)))
    return out


class Scene:
    def __init__(self):
        self.quads = []   # (points[4,3], uv[4,2] or None, texture or None, color rgba, emissive)

    def cube(self, M, origin, size, uv, inflate, mirror, tw, th, tex, color, emissive):
        for pts, uvs in cuboid_quads(origin, size, uv or (0, 0), inflate, mirror, tw, th):
            w = (M[:3, :3] @ pts.T).T + M[:3, 3]
            self.quads.append((w, uvs if tex is not None else None, tex, color, emissive))


def translate(t):
    M = np.eye(4)
    M[:3, 3] = t
    return M


def rotate(pitch, yaw, roll):
    M = np.eye(4)
    M[:3, :3] = rot_matrix(pitch, yaw, roll)
    return M


def render_scene(scene, yaw, pitch, focus, size=THUMB, ss=3):
    S = size * ss
    img = np.zeros((S, S, 4), np.float32)
    R = rot_matrix(pitch, 0, 0) @ rot_matrix(0, yaw, 0)
    # entity space is y-down with the front at -z (as Minecraft draws it): screen x = x, up = -y, nearer = -z
    view = np.diag([1, -1, -1]) @ R
    pts_all = [(view @ q[0].T).T for q in scene.quads]
    allp = np.concatenate(pts_all) if pts_all else np.zeros((1, 3))
    if focus is not None:
        lo, hi = np.array(focus[0]), np.array(focus[1])
    else:
        lo, hi = allp[:, :2].min(0), allp[:, :2].max(0)
    span = max(hi[0] - lo[0], hi[1] - lo[1]) * 1.12 + 1e-6
    centre = (lo + hi) / 2
    scale = S / span
    light = np.array([-0.4, 0.75, -0.55])
    light /= np.linalg.norm(light)
    order = sorted(range(len(scene.quads)), key=lambda i: pts_all[i][:, 2].mean())   # far (small z) first
    for i in order:
        w, uvs, tex, color, emissive = scene.quads[i]
        p = pts_all[i]
        sp = np.stack([(p[:, 0] - centre[0]) * scale + S / 2, -(p[:, 1] - centre[1]) * scale + S / 2], 1)
        e1, e2 = p[1] - p[0], p[3] - p[0]
        nrm = np.cross(e1, e2)
        ln = np.linalg.norm(nrm)
        shade = 1.0
        if ln > 1e-9:
            nrm /= ln
            shade = 0.62 + 0.38 * abs(float(nrm @ light))
        if emissive:
            shade = 1.0
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
        inside = (s >= 0) & (s <= 1) & (t >= 0) & (t <= 1)
        if not inside.any():
            continue
        if tex is not None:
            uv = uvs[0] + (uvs[1] - uvs[0]) * s[..., None] + (uvs[3] - uvs[0]) * t[..., None]
            th_, tw_ = tex.shape[:2]
            px = np.clip((uv[..., 0] * tw_).astype(int), 0, tw_ - 1)
            py = np.clip((uv[..., 1] * th_).astype(int), 0, th_ - 1)
            col = tex[py, px].copy()
            col[..., :3] *= color[:3]
            col[..., 3] *= color[3]
        else:
            col = np.broadcast_to(color, s.shape + (4,)).copy()
        col[..., :3] *= shade
        alpha = col[..., 3] * inside
        dst = img[y0:y1, x0:x1]
        dst[..., :3] = dst[..., :3] * (1 - alpha[..., None]) + col[..., :3] * alpha[..., None]
        dst[..., 3] = dst[..., 3] * (1 - alpha) + alpha
    im = to_image(img)
    return im.convert("RGBa").resize((size, size), Image.LANCZOS).convert("RGBA")


MANNEQUIN_COLOR = np.array([0.36, 0.37, 0.42, 1.0], np.float32)


def add_mannequin(scene, pose=None, alpha=1.0, parts=None):
    mats = {}
    for part, (pivot, origin, size) in MANNEQUIN.items():
        if parts is not None and part not in parts:
            rx, ry, rz = (pose or {}).get(part, (0, 0, 0))
            mats[part] = translate(pivot) @ rotate(rx, ry, rz)
            continue
        rx, ry, rz = (pose or {}).get(part, (0, 0, 0))
        M = translate(pivot) @ rotate(rx, ry, rz)
        mats[part] = M
        local = [o - pv for o, pv in zip(origin, pivot)]
        c = MANNEQUIN_COLOR.copy()
        c[3] = alpha
        scene.cube(M, local, size, None, 0, False, 1, 1, None, c, False)
    return mats


def tint_of(it):
    if not it["default_color"]:
        return np.ones(4, np.float32)
    return hexc(it["default_color"])


def thumb_model(it, tex_arr, tw, th):
    scene = Scene()
    view = it["view"]
    focus = view.get("focus")
    parts = {"head": {"head"}, "shoulder": {"head", "body", "leftArm", "rightArm"}}.get(focus)
    mats = add_mannequin(scene, parts=parts) if focus != "item" else {}
    for b in it["bones"]:
        base = mats.get(ATTACH.get(b.attach)) if ATTACH.get(b.attach) else np.eye(4)
        # thumbnails show a gentle rest pose for the animated bones
        rx, ry, rz = b.rotation
        anim = b.anim or {}
        if anim.get("type") == "wing":
            ry += anim["side"] * anim["base"]
        M = base @ translate(b.pivot) @ rotate(rx, ry, rz) @ translate([-v for v in b.pivot])
        if anim.get("type") == "cape":
            M = base @ translate(b.pivot) @ rotate(10, 0, 0) @ rotate(0, 180, 0) @ translate([-v for v in b.pivot])
        color = tint_of(it) if b.tint else np.ones(4, np.float32)
        color = color.copy()
        color[3] = b.alpha
        for c in b.cubes:
            scene.cube(M, c.origin, c.size, c.uv, c.inflate, c.mirror, tw, th, tex_arr, color, b.emissive)
    return render_scene(scene, view.get("yaw", 30), view.get("pitch", 10), None)


def thumb_emote(it):
    parts = it["emote"]["parts"]
    pose = {p: tuple(keys[1][1:4]) for p, keys in parts.items()}
    scene = Scene()
    add_mannequin(scene, pose)
    im = render_scene(scene, 25, 8, None)
    return badge(im, "#ffd36a")


def badge(im, color):
    """A soft radial glow behind a thumbnail."""
    bg = np.zeros((THUMB, THUMB, 4), np.float32)
    yy, xx = np.mgrid[0:THUMB, 0:THUMB].astype(np.float32)
    r = np.sqrt((xx - THUMB / 2) ** 2 + (yy - THUMB / 2) ** 2) / (THUMB / 2)
    c = hexc(color)
    bg[..., :3] = c[:3]
    bg[..., 3] = np.clip(1 - r, 0, 1) ** 2 * 0.35
    out = to_image(bg)
    out.alpha_composite(im)
    return out


def thumb_particles(kind, colors):
    S = THUMB * 3
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    cols = [tuple(int(v * 255) for v in hexc(c)[:3]) for c in colors]
    r = np.random.default_rng(len(kind) * 7)

    def spark(x, y, s, col, a=255):
        d.ellipse((x - s, y - s, x + s, y + s), fill=col + (a,))
        d.line((x - s * 2.4, y, x + s * 2.4, y), fill=col + (int(a * 0.7),), width=max(1, int(s / 2)))
        d.line((x, y - s * 2.4, x, y + s * 2.4), fill=col + (int(a * 0.7),), width=max(1, int(s / 2)))

    if kind == "trail":
        for i in range(70):
            t = i / 70
            x = S * 0.12 + t * S * 0.76 + r.normal(0, 10)
            y = S * 0.7 - math.sin(t * math.pi) * S * 0.35 + r.normal(0, 14)
            spark(x, y, 3 + t * 9, cols[i % len(cols)], int(80 + 175 * t))
    elif kind == "burst":
        cx = cy = S / 2
        for i in range(48):
            a = r.random() * math.tau
            dist = 30 + r.random() * S * 0.38
            x, y = cx + math.cos(a) * dist, cy + math.sin(a) * dist
            # a shard: a thin diamond pointing outward
            ux, uy = math.cos(a), math.sin(a)
            L, W = 10 + r.random() * 16, 4 + r.random() * 4
            pts = [(x + ux * L, y + uy * L), (x - uy * W, y + ux * W), (x - ux * L * 0.6, y - uy * L * 0.6), (x + uy * W, y - ux * W)]
            d.polygon(pts, fill=cols[i % len(cols)] + (230,))
        spark(cx, cy, 16, (255, 255, 255))
    elif kind == "spiral":
        for arm in range(2):
            for i in range(60):
                t = i / 60
                a = t * math.tau * 2.2 + arm * math.pi
                x = S / 2 + math.cos(a) * S * 0.26 * (1 - t * 0.3)
                y = S * 0.88 - t * S * 0.72
                spark(x, y, 3 + 6 * t, cols[(i + arm) % len(cols)], int(120 + 135 * t))
        spark(S / 2, S * 0.14, 18, (255, 244, 194))
    im = im.resize((THUMB, THUMB), Image.LANCZOS)
    return badge(im, colors[0])


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
    import io
    buf = io.BytesIO()
    im.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def main():
    for out in (OUT_RES, OUT_SITE):
        out.mkdir(parents=True, exist_ok=True)
        for child in out.iterdir():
            if child.is_dir():
                shutil.rmtree(child)
    index = {"version": 1, "format": FORMAT, "items": []}
    for it in ITEMS:
        files = {}
        files["item.json"] = (json.dumps(item_json(it), indent=1) + "\n").encode()
        if it["bones"]:
            tw, th = pack(it["bones"])
            tex = paint_model(it["bones"], tw, th, it["item_space"])
            files["model.json"] = (json.dumps(model_json(it, tw, th), indent=1) + "\n").encode()
            files["texture.png"] = png_bytes(to_image(tex))
            thumb = thumb_model(it, tex, tw, th)
        elif it["emote"]:
            thumb = thumb_emote(it)
        else:
            thumb = thumb_particles(it["thumb"], it["fx"]["colors"])
        files["thumb.png"] = png_bytes(thumb)
        for out in (OUT_RES, OUT_SITE):
            d = out / it["id"]
            d.mkdir(parents=True, exist_ok=True)
            for name, data in files.items():
                (d / name).write_bytes(data)
        index["items"].append({
            "id": it["id"], "name": it["name"], "slot": it["slot"], "rarity": it["rarity"],
            "colourable": it["colourable"], "partner": None, "priceCents": it["price"], "currency": "USD",
            "files": {name: sha256(data) for name, data in files.items()}})
    data = (json.dumps(index, indent=1) + "\n").encode()
    (OUT_RES / "index.json").write_bytes(data)
    (OUT_SITE / "index.json").write_bytes(data)
    wardrobe_icon()
    print(f"{len(ITEMS)} cosmetics -> {OUT_RES.relative_to(ROOT)} and {OUT_SITE.relative_to(ROOT)}", file=sys.stderr)


if __name__ == "__main__":
    main()
