#!/usr/bin/env python3
"""Cosmetic mockups: new item ideas for the Evade shop, drawn with the real cosmetics engine.

Uses the art helpers, model format and renderer of scripts/build-cosmetics.py (imported, not changed), so every
mockup is a real item in the client's format, just not in the catalog. Nothing here touches the shipped cosmetics:
output goes to build/cosmetic-mockups/ only.

  <id>/            the item's files exactly as build-cosmetics.py would write them (item.json, model.json, ...)
  hero/<id>.webp   a large preview on the shop's preview skin (animated for special items)
  mockups.json     the list (name, slot, tier, price, description) for the gallery page

Tiers (the owner's): regular = not animated, special = animated textures, partner = not mocked up.

Usage: python3 scripts/cosmetic-mockups.py [--only id,id] [--hero 320]
       python3 scripts/cosmetic-mockups.py --sheets   (after a full run: one picture per category, in pictures/)
"""
import importlib.util
import json
import math
import pathlib
import sys

import numpy as np
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "build/cosmetic-mockups"

_spec = importlib.util.spec_from_file_location("evade_cosmetics", ROOT / "scripts/build-cosmetics.py")
bc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bc)
SHIPPED = {it["id"] for it in bc.ITEMS}
bc.ITEMS.clear()

from_bc = ("hexc mix smooth ramp pnoise hash_noise Cube Bone mirrored_bone wing_pair fill vgrad bevel grain weave "
           "emboss ao_edges clear over layer solid_paint edge_paint metal glow_paint item fshift star_poly heart_poly "
           "glint em_from em_rgb stitch_row cloth cape_bones lining trim CAPE_VIEW WING_VIEW HEAD_VIEW BACK_VIEW "
           "PET_VIEW AURA_VIEW ITEM_VIEW PET PET_ANIM pet fur_paint eyes dot with_face sigil_bones ring_sigil "
           "glass_blade gem_paint metal sword wrap_grip SWORD_DISPLAY wing_root WingDraw wing_anim feather "
           "feather_row FIRE flame_field hsv_rgb horn_paint leather blur")
for _n in from_bc.split():
    globals()[_n] = getattr(bc, _n)

ANIM = {"frames": 16, "frametime": 2, "interpolate": True}
REG, SPC = "REGULAR", "SPECIAL"


def rgba(c, a=255):
    c = hexc(c)
    return tuple(int(v * 255) for v in c[:3]) + (a,)


def draw_over(cv, L, blur=0, glow=0.0, glow_color=None):
    """Composites a layer() drawing onto the face; optionally lights the same pixels in the emissive layer."""
    src = L.done(blur)
    over(cv.arr, src)
    if glow:
        em_from(cv, src[..., 3] * glow, glow_color or src[..., :3])
    return src[..., 3]


def tail(cv):
    cloth(cv, 0.05, seed=6)
    bevel(cv, 0.05, 0.12)


# ============================================================================================== capes (regular)

def thunder_outer(cv, flash=False):
    t = cv.yy()
    n = pnoise(cv.h, cv.w, 3.0, 101, octaves=4)
    n2 = pnoise(cv.h, cv.w, 6.0, 102, octaves=3)
    if flash:
        n = fshift(n, 0, cv.phase * cv.w)
        n2 = fshift(n2, 0, -cv.phase * cv.w)
    cloud = smooth((n * 0.7 + n2 * 0.3 - 0.32) / 0.42)
    col = ramp(np.clip(t * 0.7 + (1 - cloud) * 0.3, 0, 1), [(0, "#3a4566"), (0.5, "#1a2036"), (1, "#080a12")])
    col[..., :3] = mix(col[..., :3], hexc("#7a88b0")[:3], (cloud * 0.45)[..., None])
    cv.arr[:] = col
    lit = 1.0
    if flash:
        p = cv.phase
        lit = max(math.exp(-((p - 0.3) / 0.03) ** 2), 0.7 * math.exp(-((p - 0.42) / 0.025) ** 2))
        cv.arr[..., :3] = np.clip(cv.arr[..., :3] + (cloud * lit * 0.35)[..., None] * hexc("#b8c8ff")[:3], 0, 1)
    L = layer(cv)
    pts = [(0.55, 0.0), (0.42, 0.22), (0.58, 0.3), (0.36, 0.55), (0.52, 0.6), (0.3, 0.92)]
    L.draw.line([L.P(*q) for q in pts], fill=rgba("#e8f0ff"), width=int(cv.s * 0.7 * L.k), joint="curve")
    L.draw.line([L.P(*q) for q in [(0.58, 0.3), (0.74, 0.44), (0.7, 0.52)]], fill=rgba("#e8f0ff"),
                width=int(cv.s * 0.45 * L.k), joint="curve")
    m = L.done()[..., 3]
    halo = blur(m[..., None], cv.s * 1.2)[..., 0]
    k = 0.15 + 0.85 * lit if flash else 1.0
    cv.arr[..., :3] = mix(cv.arr[..., :3], hexc("#f4f8ff")[:3], (m * k)[..., None])
    em_from(cv, np.clip(m + halo * 1.5, 0, 1) * k, "#a8c4ff", 0.95)
    trim(cv, cv.s // 2 + 1, 0, "#141a2a", stitch="#8aa0d0")
    tail(cv)


def honeycomb_outer(cv):
    vgrad(cv, hexc("#ffcf4a"), hexc("#b86a0a"))
    L = layer(cv)
    W, H = cv.w * L.k, cv.h * L.k
    R = W / 5.2
    rng = np.random.default_rng(4)
    for row in range(-1, int(H / (R * 1.5)) + 2):
        for col in range(-1, int(W / (R * 1.732)) + 2):
            cx = col * R * 1.732 + (R * 0.866 if row % 2 else 0)
            cy = row * R * 1.5
            pts = [(cx + math.cos(math.pi / 6 + i * math.pi / 3) * R * 0.88, cy + math.sin(math.pi / 6 + i * math.pi / 3) * R * 0.88)
                   for i in range(6)]
            shade = rng.random()
            fillc = rgba("#ffe08a", int(90 + 120 * shade)) if shade > 0.35 else rgba("#7a3a06", 150)
            L.draw.polygon(pts, fill=fillc, outline=rgba("#5a2a04"), width=int(cv.s * 0.35 * L.k))
    draw_over(cv, L)
    # honey drips from the collar
    D = layer(cv)
    for x0, ln in ((0.18, 0.16), (0.47, 0.26), (0.8, 0.12)):
        x, y = D.P(x0, 0.0)
        w = cv.s * 0.55 * D.k
        D.draw.rounded_rectangle((x - w / 2, 0, x + w / 2, ln * H), radius=w / 2, fill=rgba("#ffb81a"))
        D.draw.ellipse((x - w * 0.8, ln * H - w * 0.9, x + w * 0.8, ln * H + w * 0.7), fill=rgba("#ffb81a"))
    m = draw_over(cv, D)
    em_from(cv, m * 0.25, "#ffd060")
    trim(cv, cv.s // 2 + 1, cv.s // 2 + 1, "#6a3a08", stitch="#ffd36a")
    tail(cv)


def circuit_outer(cv, flow=False):
    vgrad(cv, hexc("#0e3a26"), hexc("#04150d"))
    grain(cv, 0.03)
    L = layer(cv)
    G = layer(cv)
    rng = np.random.default_rng(12)
    W, H = cv.w * L.k, cv.h * L.k
    step = cv.s * L.k
    paths = []
    for i in range(16):
        x, y = round(rng.random() * 9 + 0.5) * step, round(rng.random() * 15 + 0.5) * step
        pts = [(x, y)]
        for _ in range(4):
            if rng.random() < 0.5:
                x = min(max(step * 0.5, x + rng.choice([-1, 1]) * step * rng.integers(1, 4)), W - step * 0.5)
            else:
                y = min(max(step * 0.5, y + rng.choice([-1, 1]) * step * rng.integers(1, 5)), H - step * 0.5)
            pts.append((x, y))
        paths.append(pts)
        lit = i % 3 == 0
        (G if lit else L).draw.line(pts, fill=rgba("#5afff0" if lit else "#c8a24a"), width=int(step * 0.22), joint="curve")
        for px, py in (pts[0], pts[-1]):
            r = step * 0.32
            L.draw.ellipse((px - r, py - r, px + r, py + r), fill=rgba("#e8c86a"))
            L.draw.ellipse((px - r * 0.45, py - r * 0.45, px + r * 0.45, py + r * 0.45), fill=rgba("#06180f"))
    # a chip in the middle
    cx, cy = W * 0.5, H * 0.45
    L.draw.rectangle((cx - step * 1.6, cy - step * 1.6, cx + step * 1.6, cy + step * 1.6), fill=rgba("#15151a"), outline=rgba("#8a8a90"), width=int(step * 0.15))
    for k in range(-2, 3):
        for (ax, ay, bx, by) in ((cx + k * step * 0.6, cy - step * 1.6, cx + k * step * 0.6, cy - step * 2.1),
                                 (cx + k * step * 0.6, cy + step * 1.6, cx + k * step * 0.6, cy + step * 2.1)):
            L.draw.line((ax, ay, bx, by), fill=rgba("#c0c0c8"), width=int(step * 0.18))
    draw_over(cv, L)
    m = G.done()[..., 3]
    col = np.zeros_like(cv.arr)
    col[..., :3] = hexc("#5afff0")[:3]
    if flow:
        y, x = cv.grid()
        pulse = 0.25 + 0.75 * (np.sin((x + y) / cv.s * 1.2 - cv.phase * math.tau * 2) > 0.6)
    else:
        pulse = 0.85
    cv.arr[..., :3] = mix(cv.arr[..., :3], col[..., :3], (m * 0.9)[..., None])
    em_from(cv, m * pulse, "#5afff0")
    trim(cv, cv.s // 2 + 1, 0, "#1a1a1e", stitch="#5afff0")
    tail(cv)


def ermine_outer(cv):
    vgrad(cv, hexc("#b0142a"), hexc("#5a0612"))
    cloth(cv, 0.1, seed=21)
    L = layer(cv)
    W, H = cv.w * L.k, cv.h * L.k
    s = cv.s * L.k
    gold = rgba("#f0c24a")
    for x in (s * 0.9, W - s * 0.9):
        L.draw.line((x, s, x, H * 0.78), fill=gold, width=int(s * 0.3))
        for y in np.arange(s * 2, H * 0.76, s * 1.4):
            L.draw.polygon([(x, y - s * 0.5), (x + s * 0.4, y), (x, y + s * 0.5), (x - s * 0.4, y)], fill=gold)
    # a crown crest
    cx, cy, r = W / 2, H * 0.36, s * 2.2
    L.draw.polygon([(cx - r, cy + r * 0.5), (cx - r, cy - r * 0.4), (cx - r * 0.5, cy), (cx, cy - r * 0.8), (cx + r * 0.5, cy),
                    (cx + r, cy - r * 0.4), (cx + r, cy + r * 0.5)], fill=gold)
    for gx, c in ((cx - r * 0.5, "#3a8aff"), (cx, "#e11d2e"), (cx + r * 0.5, "#3ac86a")):
        L.draw.ellipse((gx - s * 0.3, cy + r * 0.1 - s * 0.3, gx + s * 0.3, cy + r * 0.1 + s * 0.3), fill=rgba(c))
    draw_over(cv, L)
    # ermine hem: white fur with black tails
    hem = int(cv.h * 0.2)
    fur = ramp(cv.yy()[-hem:], [(0, "#ffffff"), (1, "#dcd8d0")])
    n = pnoise(hem, cv.w, 3.0, 23, octaves=2)
    fur[..., :3] *= (0.92 + 0.12 * n)[..., None]
    cv.arr[-hem:] = fur
    T = layer(cv)
    for i, x0 in enumerate(np.linspace(0.12, 0.88, 5)):
        y0 = 0.84 + (i % 2) * 0.07
        x, y = T.P(x0, y0)
        T.draw.polygon([(x, y - s * 0.5), (x + s * 0.32, y + s * 0.4), (x - s * 0.32, y + s * 0.4)], fill=rgba("#111111"))
    draw_over(cv, T)
    bevel(cv, 0.05, 0.12)


def sunset_outer(cv):
    t = cv.yy()
    cv.arr[:] = ramp(t, [(0, "#1a0b3a"), (0.3, "#5a1a6a"), (0.55, "#e0406a"), (0.75, "#ff9a3a"), (1, "#ffd36a")])
    L = layer(cv)
    W, H = cv.w * L.k, cv.h * L.k
    cx, cy, r = W / 2, H * 0.72, W * 0.32
    L.draw.ellipse((cx - r, cy - r, cx + r, cy + r), fill=rgba("#fff0a0"))
    m = draw_over(cv, L)
    y, x = cv.grid()
    band = (m > 0.5) & (((y / cv.s) * 2.2) % 1 < 0.28) & (y > cv.h * 0.62)
    cv.arr[band, :3] = hexc("#ff6a5a")[:3]
    em_from(cv, m * (~band) * 0.55, "#ffd890")
    M = layer(cv)
    M.draw.polygon([(0, H), (0, H * 0.8), (W * 0.18, H * 0.7), (W * 0.33, H * 0.79), (W * 0.52, H * 0.66), (W * 0.7, H * 0.78),
                    (W * 0.86, H * 0.72), (W, H * 0.8), (W, H)], fill=rgba("#170a22"))
    draw_over(cv, M)
    S = layer(cv)
    rng = np.random.default_rng(31)
    for _ in range(14):
        sx, sy = rng.random() * W, rng.random() * H * 0.3
        rr = cv.s * L.k * (0.08 + rng.random() * 0.1)
        S.draw.ellipse((sx - rr, sy - rr, sx + rr, sy + rr), fill=rgba("#ffffff"))
    sm = draw_over(cv, S)
    em_from(cv, sm, "#ffffff")
    trim(cv, cv.s // 2 + 1, 0, "#1a0b2a", stitch="#ff9a6a")
    tail(cv)


def camo_outer(cv):
    y, x = cv.units()
    a = pnoise(cv.h, cv.w, 2.0, 41, octaves=2)
    b = pnoise(cv.h, cv.w, 3.5, 42, octaves=2)
    cell = lambda f: f[(np.floor(y) * cv.s).astype(int).clip(0, cv.h - 1), (np.floor(x) * cv.s).astype(int).clip(0, cv.w - 1)]
    A, B = cell(a), cell(b)
    level = np.select([A > 0.6, B > 0.58, A < 0.36], [0.35, 0.55, 0.95], 0.75)
    cv.arr[..., :3] = level[..., None]
    cv.arr[..., 3] = 1
    grain(cv, 0.03)
    trim(cv, cv.s // 2 + 1, cv.s // 2 + 1, "#d8d8d8")
    tail(cv)


def cape(outer, lin=("#1a1a22", "#0a0a10", None), **kw):
    return cape_bones(outer, lining(*lin), **kw)


item("thunderhead_cape", "Thunderhead Cape", "CAPE", REG, 600,
     "Rolling storm clouds over a deep night blue, split by a glowing bolt of lightning.",
     cape(thunder_outer, ("#141a2a", "#06080e", "#2a3350")), view=CAPE_VIEW)
item("honeycomb_cape", "Honeycomb Cape", "CAPE", REG, 500,
     "Golden honeycomb cells, some full and some empty, with honey dripping from the collar.",
     cape(honeycomb_outer, ("#5a2a04", "#2a1402", "#8a4a0a")), view=CAPE_VIEW)
item("circuit_cape", "Circuit Cape", "CAPE", REG, 600,
     "A circuit board: copper traces, a chip at the centre and a few lines that glow cyan.",
     cape(circuit_outer, ("#06180f", "#020805", "#0e3a26")), view=CAPE_VIEW)
item("royal_ermine_cape", "Royal Ermine Cape", "CAPE", REG, 800,
     "Crimson velvet with gold embroidery, a jewelled crown crest and a white ermine hem.",
     cape(ermine_outer, ("#5a0612", "#2a0208", "#f0c24a")), view=CAPE_VIEW)
item("sunset_drive_cape", "Sunset Drive Cape", "CAPE", REG, 500,
     "A striped retro sun sinking behind purple mountains under the first stars.",
     cape(sunset_outer, ("#1a0b3a", "#0a0418", "#5a1a6a")), view=CAPE_VIEW)
item("pixel_camo_cape", "Pixel Camo Cape", "CAPE", REG, 300,
     "Blocky camouflage in any colour you like.",
     cape(camo_outer, ("#2a2a2a", "#121212", None), tint=True), colourable=True, default_color="#6E8A4A", view=CAPE_VIEW)


# ============================================================================================== capes (special, animated)

def code_rain_outer(cv):
    cv.arr[:] = hexc("#020a04")
    y, x = cv.grid()
    cs = max(2, cv.s // 2)                       # one glyph cell
    cols, rows = cv.w // cs, cv.h // cs
    rng = np.random.default_rng(55)
    glyph = rng.random((rows * 2, cols, 2, 2)) > 0.45
    speed = rng.integers(1, 3, cols)             # whole cape heights per loop: seamless
    start = rng.random(cols)
    trail_len = 0.35 + rng.random(cols) * 0.3
    for c in range(cols):
        head = (start[c] + cv.phase * speed[c]) % 1.0
        for r in range(rows):
            pos = (r + 0.5) / rows
            d = (head - pos) % 1.0
            b = math.exp(-d / trail_len[c] * 3) if d < trail_len[c] else 0.0
            if b < 0.03:
                continue
            g = glyph[(r + int(cv.phase * 16)) % (rows * 2), c]
            for gy in range(2):
                for gx in range(2):
                    if not g[gy, gx]:
                        continue
                    py0, px0 = r * cs + gy * cs // 2, c * cs + gx * cs // 2
                    sl = (slice(py0, py0 + max(1, cs // 2)), slice(px0, px0 + max(1, cs // 2)))
                    colr = hexc("#d8ffe0") if d < 0.03 else hexc("#3aff6a")
                    cv.arr[sl][..., :3] = colr[:3] * (0.3 + 0.7 * b)
                    cv.em[sl] = (colr[0], colr[1], colr[2], b)
    trim(cv, cv.s // 2 + 1, 0, "#0a1a0e", stitch="#3aff6a")
    weave(cv, 0.02)


def lava_outer(cv):
    n = pnoise(cv.h, cv.w, 3.0, 61, octaves=4)
    n = fshift(n, cv.phase * cv.h, 0)
    n2 = pnoise(cv.h, cv.w, 6.0, 62, octaves=3)
    n2 = fshift(n2, cv.phase * cv.h * 2, 0)
    ridge = 1 - np.abs(n - 0.5) * 2
    crack = smooth((ridge - 0.78) / 0.2) * (0.7 + 0.3 * n2)
    rock = ramp(n2, [(0, "#0e0a0a"), (1, "#3a2a26")])
    cv.arr[:] = rock
    heat = np.clip(crack * 1.15, 0, 1)
    glow = ramp(heat, FIRE)
    cv.arr[..., :3] = mix(cv.arr[..., :3], glow[..., :3], heat[..., None])
    em_rgb(cv, glow, heat * 0.95)
    t = cv.yy()
    em_rgb(cv, ramp(np.clip(t * 0.8, 0, 1), FIRE), smooth((t - 0.8) / 0.2) * 0.6 * (0.8 + 0.2 * n2))
    trim(cv, cv.s // 2 + 1, 0, "#1a0e0a", stitch="#ff8a3a")
    bevel(cv, 0.05, 0.12)


def warp_outer(cv):
    t, u = cv.yy(), cv.xx()
    cv.arr[:] = ramp(np.clip(np.sqrt((u - 0.5) ** 2 + (t - 0.45) ** 2) * 1.6, 0, 1),
                     [(0, "#2a1a6a"), (0.5, "#0c0a2a"), (1, "#03030a")])
    L = layer(cv)
    W, H = cv.w * L.k, cv.h * L.k
    cx, cy = W * 0.5, H * 0.45
    rng = np.random.default_rng(77)
    for _ in range(70):
        a = rng.random() * math.tau
        r0 = rng.random()
        r = (r0 + cv.phase) % 1.0
        dist = (r ** 1.8) * max(W, H) * 0.75
        ln = (0.04 + r * 0.25) * max(W, H) * 0.4
        x1, y1 = cx + math.cos(a) * dist, cy + math.sin(a) * dist
        x0, y0 = cx + math.cos(a) * max(0, dist - ln), cy + math.sin(a) * max(0, dist - ln)
        c = ["#ffffff", "#9ad8ff", "#c8a8ff"][int(r0 * 3)]
        alpha = int(255 * min(1, r * 3))
        L.draw.line((x0, y0, x1, y1), fill=rgba(c, alpha), width=max(1, int((0.3 + r) * cv.s * 0.35 * L.k)))
    m = draw_over(cv, L)
    em_from(cv, m, "#d8ecff")
    core = np.exp(-(((u - 0.5) * 3) ** 2 + ((t - 0.45) * 2) ** 2) * 4)
    em_from(cv, core * 0.5, "#a88aff")
    trim(cv, cv.s // 2 + 1, 0, "#0a0820", stitch="#9ad8ff")
    weave(cv, 0.02)


def rainbow_outer(cv):
    t, u = cv.yy(), cv.xx()
    h = (u * 0.35 + t * 0.9 - cv.phase) % 1.0
    h = np.floor(h * 7) / 7
    col = hsv_rgb(h, 0.7, 1.0)
    cv.arr[..., :3] = col
    cv.arr[..., 3] = 1
    y, x = cv.grid()
    edge = (((u * 0.35 + t * 0.9 - cv.phase) * 7) % 1) < 0.08
    cv.arr[edge, :3] *= 0.75
    em_rgb(cv, col, np.full(t.shape, 0.35, np.float32))
    rng = np.random.default_rng(9)
    for _ in range(8):
        gx, gy = rng.random() * cv.w, rng.random() * cv.h
        tw = 0.5 + 0.5 * math.sin(cv.phase * math.tau * 2 + rng.random() * math.tau)
        glint(cv, gx, gy, cv.s * 0.9, "#ffffff", tw)
    trim(cv, cv.s // 2 + 1, cv.s // 2 + 1, "#ffffff")
    cloth(cv, 0.05, seed=8)
    bevel(cv, 0.05, 0.12)


def equalizer_outer(cv):
    t, u = cv.yy(), cv.xx()
    cv.arr[:] = ramp(t, [(0, "#14101e"), (1, "#06040c")])
    y, x = cv.grid()
    gridl = ((x % cv.s) == 0) | ((y % cv.s) == 0)
    cv.arr[gridl, :3] *= 1.6
    rng = np.random.default_rng(3)
    bars = 8
    bw = cv.w / bars
    for i in range(bars):
        k1, k2 = rng.integers(1, 4), rng.integers(1, 3)
        p1, p2 = rng.random() * math.tau, rng.random() * math.tau
        lvl = 0.25 + 0.6 * abs(math.sin(math.tau * cv.phase * k1 + p1)) * (0.7 + 0.3 * abs(math.sin(math.tau * cv.phase * k2 + p2)))
        top = cv.h * (1 - lvl) - cv.s
        x0, x1 = int(i * bw + bw * 0.15), int((i + 1) * bw - bw * 0.15)
        seg = max(2, cv.s // 2)
        for yy in range(int(cv.h - cv.s * 1.2), int(max(top, cv.s * 1.5)), -seg):
            f = 1 - yy / cv.h
            c = ramp(np.array([[min(1, f / 0.85)]], np.float32), [(0, "#3aff8a"), (0.55, "#ffe23a"), (1, "#ff3a4a")])[0, 0]
            sl = (slice(yy - seg + 1, yy), slice(x0, x1))
            cv.arr[sl][..., :3] = c[:3]
            cv.em[sl] = (c[0], c[1], c[2], 0.85)
    trim(cv, cv.s // 2 + 1, 0, "#1e1830", stitch="#ff3aa0")
    weave(cv, 0.02)


item("code_rain_cape", "Code Rain Cape", "CAPE", SPC, None,
     "Columns of glowing green code stream down the cape and never stop.",
     cape(code_rain_outer, ("#03100a", "#010402", "#0a3a1a")), view=CAPE_VIEW, animation=ANIM)
item("lava_flow_cape", "Lava Flow Cape", "CAPE", SPC, None,
     "Cracked black rock with molten lava creeping down through the cracks.",
     cape(lava_outer, ("#1a0e0a", "#080404", "#5a1a0a")), view=CAPE_VIEW, animation=ANIM)
item("warp_speed_cape", "Warp Speed Cape", "CAPE", SPC, None,
     "Stars stream past from a violet core, like jumping to lightspeed.",
     cape(warp_outer, ("#0a0820", "#03030a", "#2a1a6a")), view=CAPE_VIEW, animation=ANIM)
item("spectrum_shift_cape", "Spectrum Shift Cape", "CAPE", SPC, None,
     "Bands of colour slide across the cape forever, with sparkles that twinkle as they pass.",
     cape(rainbow_outer, ("#2a2a3a", "#101018", None)), view=CAPE_VIEW, animation=ANIM)
item("storm_surge_cape", "Storm Surge Cape", "CAPE", SPC, None,
     "Storm clouds drift across the cape and lightning flashes, lighting up the whole sky.",
     cape(lambda cv: thunder_outer(cv, True), ("#141a2a", "#06080e", "#2a3350")), view=CAPE_VIEW, animation=ANIM)
item("bass_drop_cape", "Bass Drop Cape", "CAPE", SPC, None,
     "A neon equalizer that bounces to a beat on your back.",
     cape(equalizer_outer, ("#14101e", "#06040c", "#ff3aa0")), view=CAPE_VIEW, animation=ANIM)


# ============================================================================================== wings

def membrane(shape, colors, vein_color, alpha=0.96, pulse=False, glow=None, seed=0):
    """A flat wing membrane: `shape` points in wing coords (r 0 tip..1 shoulder, t 0 top..1 bottom), ribs from the
    shoulder to the shape's points, a darker edge."""
    def p(face, cv):
        r, t = wing_root(face, cv), cv.yy()
        W = WingDraw(face, cv)
        W.poly(shape, (255, 255, 255, 255))
        mask = W.done()[..., 3]
        n = pnoise(cv.h, cv.w, 3.0, 300 + seed, octaves=3)
        col = ramp(np.clip((1 - r) * 0.8 + n * 0.3, 0, 1), colors)
        V = WingDraw(face, cv)
        root = (0.98, 0.08)
        tips = [q for q in shape if q[0] < 0.6][:6]
        for q in tips:
            V.line([root, q], (255, 255, 255, 255), 0.45)
        veins = V.done()[..., 3] * mask
        col[..., :3] = mix(col[..., :3], hexc(vein_color)[:3], (veins * 0.85)[..., None])
        edge = mask * (1 - smooth(blur(mask[..., None], 1.2)[..., 0] * 1.4 - 0.4))
        col[..., :3] *= (1 - np.clip(edge * 1.2, 0, 0.6))[..., None]
        col[..., 3] = mask * alpha
        cv.arr[:] = col
        if glow:
            k = 0.8
            if pulse:
                d = (1 - r)
                k = 0.2 + 0.8 * np.exp(-(((d - cv.phase) + 0.5) % 1 - 0.5) ** 2 / 0.01)
            em_from(cv, veins * k, glow)
            em_from(cv, mask * (1 - veins) * 0.12, glow)
    return p


BAT = [(1.0, 0.0), (0.55, 0.02), (0.0, 0.08), (0.08, 0.45), (0.2, 0.32), (0.32, 0.72), (0.48, 0.5), (0.62, 0.9),
       (0.78, 0.62), (1.0, 0.8)]


def bat_wings(colors, vein, glow=None, pulse=False, seed=0):
    anim = wing_anim(28, 14, 1.4)
    right = [Bone("wing_r", "body", (-1.5, 2, 2.5), (0, 0, -8), [
        Cube((-18, -4, 2), (17, 16, 0), membrane(BAT, colors, vein, glow=glow, pulse=pulse, seed=seed)),
        Cube((-18, -4.5, 1.5), (17, 1, 1), edge_paint(hexc("#1a1418")))], anim=anim)]
    return wing_pair(right)


def moth_lobe(upper):
    def p(face, cv):
        r, t = wing_root(face, cv), cv.yy()
        W = WingDraw(face, cv)
        pts = ([(1.0, 0.85), (0.98, 0.1), (0.7, 0.0), (0.3, 0.02), (0.05, 0.1), (0.0, 0.3), (0.12, 0.6), (0.5, 0.9), (0.85, 1.0)]
               if upper else [(1.0, 0.0), (0.4, 0.0), (0.08, 0.2), (0.0, 0.5), (0.15, 0.85), (0.45, 1.0), (0.8, 0.8), (1.0, 0.4)])
        W.poly(pts, (255, 255, 255, 255))
        mask = W.done()[..., 3]
        n = pnoise(cv.h, cv.w, 3.0, 88 if upper else 89, octaves=4)
        col = ramp(np.clip((1 - r) * 0.7 + n * 0.4, 0, 1), [(0, "#e8d6b0"), (0.5, "#a8865a"), (1, "#4a3420")])
        bands = np.sin((1 - r) * 18 + n * 4) > 0.75
        col[bands, :3] *= 0.7
        E = WingDraw(face, cv)
        er, et = (0.45, 0.45) if upper else (0.4, 0.5)
        for rad, c in ((0.2, (30, 20, 14, 255)), (0.14, (240, 200, 90, 255)), (0.08, (20, 14, 10, 255)), (0.03, (255, 255, 255, 255))):
            x0, y0 = E.P(er, et)
            R = rad * cv.w * E.k
            E.d.ellipse((x0 - R, y0 - R * cv.h / cv.w * 1.4, x0 + R, y0 + R * cv.h / cv.w * 1.4), fill=c)
        eye = E.done()
        over(col, eye * mask[..., None])
        col[..., 3] = mask
        cv.arr[:] = col
    return p


def moth():
    anim = wing_anim(24, 18, 1.8)
    right = [Bone("lobes_r", "body", (-1, 3, 2.5), (0, 0, -6), [
        Cube((-12, -4, 2), (11, 9, 0), moth_lobe(True)), Cube((-9, 5, 2.05), (8, 7, 0), moth_lobe(False))], anim=anim)]
    body = Bone("thorax", "body", (0, 3, 2.5), cubes=[Cube((-0.5, 0, 2), (1, 8, 1), fur_paint("#8a6a44", "#4a3420", seed=3))])
    return wing_pair(right) + [body]


def flame_feather(seed, animated):
    base = feather("#ffe08a", "#ff3a0a", glow_tip="#ffb03a", seed=seed)

    def p(face, cv):
        base(face, cv)
        if animated:
            heat = flame_field(cv, 200 + seed, speed=1.0)
            inside = cv.arr[..., 3]
            em_rgb(cv, ramp(heat, FIRE), heat * inside * 0.9)
    return p


def phoenix(animated=False):
    anim = wing_anim(30, 14, 1.5)
    arm = Cube((-16, -1, 1.5), (15, 1, 1), metal("#ffe9a8", "#b8781a", 0.3))
    feathers = feather_row([flame_feather(i, animated) for i in range(8)], -2.5, -16, 0, 2, lambda f: 5 + 9 * f, width=2)
    under = feather_row([flame_feather(20 + i, animated) for i in range(6)], -3, -12, 0, 2.3, lambda f: 3 + 4 * f, width=2)
    return wing_pair([Bone("wing_r", "body", (-1.5, 2, 2.5), (0, 0, -4), [arm] + feathers + under, anim=anim)])


def crystal_shard(c_hi, c_lo):
    def p(face, cv):
        t = cv.yy()
        cv.arr[:] = ramp(t, [(0, c_hi), (1, c_lo)])
        x = cv.xx()
        facet = np.abs(x - 0.5) < 0.18
        cv.arr[facet, :3] = np.clip(cv.arr[facet, :3] * 1.3, 0, 1)
        tip = (t > 0.8) & (np.abs(x - 0.5) * 2 > (1 - t) * 5)
        cv.arr[..., 3] = np.where(tip, 0, 0.88)
        em_from(cv, (1 - t) * 0.35 * (~tip), c_hi)
        bevel(cv, 0.2, 0.2)
    return p


def crystal_wings():
    anim = wing_anim(30, 10, 1.2)
    sh = crystal_shard("#f4e8ff", "#8a5aff")
    sh2 = crystal_shard("#e0fbff", "#3ab8ff")
    cubes = [Cube((-15, -1, 1.5), (14, 1, 1), metal("#ffffff", "#8a8aa8", 0.4))]
    for i, (x, L) in enumerate([(-4, 6), (-6.5, 9), (-9, 12), (-11.5, 14), (-14, 11), (-16, 8)]):
        cubes.append(Cube((x, 0, 2 + (i % 2) * 0.4), (2, L, 1), sh if i % 2 else sh2))
    return wing_pair([Bone("wing_r", "body", (-1.5, 2, 2.5), (0, 0, -6), cubes, anim=anim)])


item("nightwing_wings", "Nightwing Wings", "WINGS", REG, 900,
     "Leathery bat wings in deep violet with dark finger bones and a scalloped edge.",
     bat_wings([(0, "#6a3a8a"), (0.6, "#3a1a50"), (1, "#14081e")], "#0e0614"), view=WING_VIEW)
item("dusk_moth_wings", "Dusk Moth Wings", "WINGS", REG, 700,
     "Soft, dusty moth wings in cream and bark brown, with a golden eye-spot on each.",
     moth(), view=WING_VIEW)
item("phoenix_plume_wings", "Phoenix Plume Wings", "WINGS", REG, 1100,
     "Gold-armed wings of long feathers that burn from yellow to deep red, glowing at the tips.",
     phoenix(), view=WING_VIEW)
item("amethyst_shard_wings", "Amethyst Shard Wings", "WINGS", REG, 1000,
     "Spars of violet and ice-blue crystal fanned out from a silver arm.",
     crystal_wings(), view=WING_VIEW)
item("phoenix_rebirth_wings", "Phoenix Rebirth Wings", "WINGS", SPC, None,
     "Phoenix feathers alive with real flame: fire runs up every feather while you fly.",
     phoenix(True), view=WING_VIEW, animation=ANIM)
item("neon_pulse_wings", "Neon Pulse Wings", "WINGS", SPC, None,
     "Black bat wings whose veins light up in waves of neon pink, shoulder to tip.",
     bat_wings([(0, "#2a0a2a"), (0.6, "#12040e"), (1, "#050205")], "#ff3ac8", glow="#ff3ac8", pulse=True, seed=4),
     view=WING_VIEW, animation=ANIM)


# ============================================================================================== hats

def knit(face, cv):
    cv.arr[:] = hexc("#e8e8e8")
    y, x = cv.grid()
    rib = np.sin((x + 0.5) / cv.s * math.pi * 2) * 0.5 + 0.5
    v = ((y // max(1, cv.s // 2)) % 2) * 0.06
    cv.arr[..., :3] *= (0.82 + 0.18 * rib - v)[..., None]
    grain(cv, 0.04)
    bevel(cv, 0.1, 0.2)


def pompom(face, cv):
    cv.arr[:] = hexc("#ffffff")
    n = pnoise(cv.h, cv.w, 2.0, 5, octaves=3)
    cv.arr[..., :3] *= (0.85 + 0.2 * n)[..., None]
    ao_edges(cv, 0.2)


def beanie():
    return [Bone("beanie", "head", cubes=[Cube((-4, -9, -4), (8, 3, 8), knit, inflate=0.5), Cube((-3, -10, -3), (6, 1, 6), knit, inflate=0.3)], tint=True),
            Bone("beanie_cuff", "head", cubes=[Cube((-4, -6, -4), (8, 1, 8), knit, inflate=0.7)], tint=True),
            Bone("pompom", "head", cubes=[Cube((-1.5, -12.5, -1.5), (3, 3, 3), pompom)])]


def headset():
    shell = metal("#3a3a44", "#101014", 0.25)

    def cup(face, cv):
        shell(face, cv)
        if face in ("east", "west"):
            y, x = cv.grid()
            r = np.sqrt((x + 0.5 - cv.w / 2) ** 2 + (y + 0.5 - cv.h / 2) ** 2) / (cv.w / 2)
            ring = np.abs(r - 0.7) < 0.12
            cv.arr[ring, :3] = hexc("#ff2a3a")[:3]
            em_from(cv, ring.astype(np.float32), "#ff2a3a")
    pad = solid_paint(hexc("#1a1a20"), grain_amt=0.08)
    band = [Cube((-4, -10, -1), (8, 1, 2), shell, inflate=0.1), Cube((-5, -9, -1), (1, 3, 2), shell), Cube((4, -9, -1), (1, 3, 2), shell)]
    cups = [Cube((-6, -6, -2), (1, 4, 4), cup), Cube((-5, -5.5, -1.5), (1, 3, 3), pad),
            Cube((5, -6, -2), (1, 4, 4), cup), Cube((4, -5.5, -1.5), (1, 3, 3), pad)]
    mic = [Cube((-5.5, -3, -5), (1, 1, 4), shell), Cube((-5, -3.2, -5.6), (1, 1, 1), glow_paint("#ff8a8a", "#ff2a3a"))]
    return [Bone("headset", "head", cubes=band + cups + mic)]


def top_hat():
    felt = solid_paint(hexc("#16161c"), grain_amt=0.05)

    def ribbon(face, cv):
        cv.arr[:] = hexc("#b0142a")
        cv.arr[..., :3] *= (0.9 + 0.2 * cv.yy())[..., None]
        bevel(cv, 0.2, 0.2)
    return [Bone("tophat", "head", (0, -8, 0), (0, 0, -6), cubes=[
        Cube((-5, -9, -5), (10, 1, 10), felt), Cube((-3.5, -16, -3.5), (7, 7, 7), felt),
        Cube((-3.5, -10.5, -3.5), (7, 1, 7), ribbon, inflate=0.15),
        Cube((2.6, -11.5, -4), (1, 2, 1), solid_paint(hexc("#f0c24a")))])]


def wizard_paint(face, cv):
    vgrad(cv, hexc("#4a2a9a"), hexc("#1e0e4a"))
    grain(cv, 0.04)
    if face not in ("top", "bottom"):
        L = layer(cv)
        rng = np.random.default_rng(int(cv.w * 7 + cv.h))
        for _ in range(max(1, cv.w * cv.h // (cv.s * cv.s * 6))):
            x, y = rng.random() * cv.w * L.k, rng.random() * cv.h * L.k
            L.draw.polygon(star_poly(x, y, cv.s * 0.55 * L.k, cv.s * 0.22 * L.k), fill=rgba("#ffd84a"))
        m = draw_over(cv, L)
        em_from(cv, m * 0.6, "#ffd84a")
    bevel(cv, 0.12, 0.2)


def wizard_hat():
    p = wizard_paint
    return [Bone("wizard", "head", (0, -8, 0), (0, 0, 8), cubes=[
        Cube((-6, -9, -6), (12, 1, 12), p), Cube((-4, -12, -4), (8, 3, 8), p), Cube((-3, -15, -3), (6, 3, 6), p),
        Cube((-2, -18, -1.5), (4, 3, 4), p), Cube((-1, -20, 0), (2, 2, 2), p), Cube((0, -21, 1), (1, 1, 2), p)])]


def ear_paint(face, cv):
    fur_paint("#ffffff", "#e8e4ee", seed=31)(face, cv)
    if face == "north":
        x, t = cv.xx(), cv.yy()
        inner = (np.abs(x - 0.5) < 0.28) & (t > 0.12) & (t < 0.9)
        cv.arr[inner, :3] = mix(cv.arr[inner, :3], hexc("#ffb0c8")[:3], 0.85)


def bunny_ears():
    ear = Bone("ear_r", "head", (-2, -8, 0), (-10, 0, -12), cubes=[Cube((-3, -15, -0.5), (2, 7, 1), ear_paint, inflate=0.1)])
    return [ear, mirrored_bone(ear, "ear_l")]


def viking_helm():
    steel = metal("#e0e4ea", "#5a626e", 0.35)
    bronze = metal("#f4d08a", "#8a5a1a", 0.3)
    segs = [((-6, -8, -1), (2, 2, 2)), ((-7.5, -9.5, -1), (2, 2, 2)), ((-8, -11.5, -0.5), (1, 2, 1)), ((-8, -13, -0.5), (1, 2, 1))]
    horn = []
    for i, (o, s) in enumerate(segs):
        horn.append(Cube(o, s, (lambda i: (lambda face, cv: (setattr(cv.cube, "seg", i), horn_paint("#f4ecd8", "#b8a888")(face, cv))))(i)))
    hr = Bone("horn_r", "head", cubes=horn)
    return [Bone("helm", "head", cubes=[Cube((-4, -9, -4), (8, 2, 8), steel, inflate=0.5), Cube((-4, -7, -4), (8, 1, 8), bronze, inflate=0.6),
                                        Cube((-0.5, -6.5, -5), (1, 3, 1), steel)]), hr, mirrored_bone(hr, "horn_l")]


item("snowday_beanie", "Snowday Beanie", "HAT", REG, 200,
     "A chunky ribbed beanie with a fluffy white pompom, in any colour you like.",
     beanie(), colourable=True, default_color="#E11D2E", view=HEAD_VIEW)
item("pro_headset", "Pro Headset", "HAT", REG, 300,
     "A gaming headset with glowing red rings on the cups and a mic by your mouth.",
     headset(), view=HEAD_VIEW)
item("dapper_top_hat", "Dapper Top Hat", "HAT", REG, 300,
     "A tall black top hat with a crimson band and a gold buckle, worn at a tilt.",
     top_hat(), view=HEAD_VIEW)
item("starweaver_hat", "Starweaver Hat", "HAT", REG, 300,
     "A tall, bent wizard's hat in deep violet, covered in glowing gold stars.",
     wizard_hat(), view=HEAD_VIEW)
item("bunny_ears", "Bunny Ears", "HAT", REG, 100,
     "Tall fluffy white ears with pink insides that lean back a little.",
     bunny_ears(), view=HEAD_VIEW)
item("raider_helm", "Raider Helm", "HAT", REG, 400,
     "A steel helmet with a bronze band, a nose guard and two curved horns.",
     viking_helm(), view=HEAD_VIEW)


def crown_gold(face, cv):
    metal("#fff0a0", "#b8781a", 0.35)(face, cv)


def crown_flames(face, cv):
    clear(cv)
    if face not in ("north", "south"):
        return
    heat = flame_field(cv, 77 + int(cv.cube.origin[0] * 3 + cv.cube.origin[2]), speed=1.0)
    t = cv.yy()
    heat = heat * smooth(t / 0.2)
    col = ramp(heat, FIRE)
    cv.arr[..., :3] = col[..., :3]
    cv.arr[..., 3] = smooth((heat - 0.15) / 0.3)
    em_rgb(cv, col, cv.arr[..., 3])


def inferno_crown():
    band = [Cube((-4, -10, -4), (8, 2, 8), crown_gold, inflate=0.4)]
    spikes = []
    for x, z in ((-4, -4), (3, -4), (-4, 3), (3, 3), (-0.5, -4.4), (-0.5, 3.4), (-4.4, -0.5), (3.4, -0.5)):
        spikes.append(Cube((x, -12, z), (1, 2, 1), crown_gold))
    flames = [Cube((-4, -16, -4.3), (8, 6, 0), crown_flames), Cube((-4, -16, 4.3), (8, 6, 0), crown_flames),
              Cube((-4.3, -16, -4), (0, 6, 8), crown_flames), Cube((4.3, -16, -4), (0, 6, 8), crown_flames)]
    return [Bone("crown", "head", cubes=band + spikes), Bone("flames", "head", cubes=flames, emissive=True)]


def prism_bar(face, cv):
    y, x = cv.grid()
    idx = getattr(cv.cube, "ring_index", 0)
    pos = (idx + (x + 0.5) / cv.w) / 8
    col = hsv_rgb((pos - cv.phase) % 1.0, 0.65, 1.0)
    cv.arr[..., :3] = col
    cv.arr[..., 3] = 0.95
    em_rgb(cv, col, np.ones(col.shape[:2], np.float32))


def prism_halo():
    paints = [lambda f, c: prism_bar(f, c) for _ in range(8)]
    a = [Cube(c.origin, c.size, paints[2 * i]) for i, c in enumerate(bc.ring_bars(paints[0], -12))]
    b = [Cube(c.origin, c.size, paints[2 * i + 1]) for i, c in enumerate(bc.ring_bars(paints[0], -12))]
    for i, c in enumerate(a):
        c.ring_index = (0, 4, 6, 2)[i]
    for i, c in enumerate(b):
        c.ring_index = (1, 5, 7, 3)[i]
    spin = {"type": "spin", "speed": 60}
    return [Bone("halo_a", "head", (0, -12, 0), (0, 0, 0), a, anim=spin, emissive=True),
            Bone("halo_b", "head", (0, -12, 0), (0, 45, 0), b, anim=spin, emissive=True)]


def cloud_paint(face, cv):
    t = cv.yy()
    n = pnoise(cv.h, cv.w, 2.5, 140 + cv.w, octaves=3)
    cv.arr[:] = ramp(np.clip(t * 0.8 + n * 0.3, 0, 1), [(0, "#f4f6fa"), (0.6, "#a8b0c0"), (1, "#5a6274")])
    p = cv.phase
    flash = max(math.exp(-((p - 0.5) / 0.03) ** 2), 0.6 * math.exp(-((p - 0.62) / 0.02) ** 2))
    em_from(cv, np.ones_like(t) * flash * 0.7, "#d8e4ff")
    ao_edges(cv, 0.15)


def rain_paint(face, cv):
    clear(cv)
    if face not in ("north", "south", "east", "west"):
        return
    L = layer(cv)
    rng = np.random.default_rng(int(cv.cube.origin[0] * 10 + cv.cube.origin[2] * 3 + 50))
    H = cv.h * L.k
    for _ in range(10):
        x = rng.random() * cv.w * L.k
        y0 = ((rng.random() + cv.phase * 2) % 1.0) * H
        L.draw.line((x, y0, x, y0 + cv.s * L.k * 0.9), fill=rgba("#9ad8ff"), width=max(1, int(cv.s * 0.2 * L.k)))
    m = draw_over(cv, L)
    cv.arr[..., 3] = m * 0.85
    em_from(cv, m * 0.6, "#9ad8ff")
    p = cv.phase
    if 0.48 < p < 0.53:
        B = layer(cv)
        B.draw.line([B.P(0.5, 0.0), B.P(0.35, 0.4), B.P(0.6, 0.55), B.P(0.45, 1.0)], fill=rgba("#ffffff"), width=int(cv.s * 0.5 * B.k))
        bm = draw_over(cv, B)
        em_from(cv, bm, "#e8f0ff")
        cv.arr[..., 3] = np.maximum(cv.arr[..., 3], bm)


def storm_cloud():
    cl = [Cube((-5, -18, -4), (10, 3, 8), cloud_paint), Cube((-3, -20, -3), (6, 2, 6), cloud_paint), Cube((-6, -17, -2), (2, 2, 4), cloud_paint),
          Cube((4, -17, -3), (2, 2, 5), cloud_paint)]
    rain = [Cube((-4, -15, -2), (8, 6, 0), rain_paint), Cube((-4, -15, 2), (8, 6, 0), rain_paint),
            Cube((-2, -15, -3), (0, 6, 6), rain_paint), Cube((2, -15, -3), (0, 6, 6), rain_paint)]
    return [Bone("cloud", "head", (0, -18, 0), cubes=cl, anim={"type": "bob", "amp": 0.4, "speed": 0.6}),
            Bone("rain", "head", (0, -18, 0), cubes=rain, emissive=True, anim={"type": "bob", "amp": 0.4, "speed": 0.6})]


item("inferno_crown", "Inferno Crown", "HAT", SPC, None,
     "A spiked gold crown with real flames dancing up from the band.",
     inferno_crown(), view=dict(HEAD_VIEW, pitch=14), animation=ANIM)
item("prism_halo", "Prism Halo", "HAT", SPC, None,
     "A halo of pure colour that spins above you while the rainbow runs round the ring.",
     prism_halo(), view=dict(HEAD_VIEW, pitch=24), animation=ANIM)
item("personal_raincloud", "Personal Raincloud", "HAT", SPC, None,
     "Your own little storm cloud: it rains on you nonstop, with the odd flash of lightning.",
     storm_cloud(), view=dict(HEAD_VIEW, pitch=10), animation=dict(ANIM, frames=12))


# ============================================================================================== masks

def wrap_paint(face, cv):
    cv.arr[:] = hexc("#1a1e2e")
    cloth(cv, 0.12, freq=1.4, seed=14, shoulder=0.0)
    y, x = cv.grid()
    fold = ((y + x * 0.3) % max(2, cv.s)) < 1
    cv.arr[fold, :3] *= 0.8
    bevel(cv, 0.1, 0.2)


def ninja_wrap():
    return [Bone("wrap", "head", cubes=[Cube((-4, -4, -4), (8, 4, 8), wrap_paint, inflate=0.4)]),
            Bone("wrap_tails", "head", (0, -4, 4.5), (20, 0, 0), cubes=[Cube((-1.5, -4.5, 4.4), (1, 5, 0), wrap_paint),
                                                                         Cube((0.5, -4.5, 4.6), (1, 6, 0), wrap_paint)])]


def lens(face, cv):
    t, x = cv.yy(), cv.xx()
    cv.arr[:] = ramp(t, [(0, "#ffd080"), (1, "#a8540a")])
    cv.arr[..., 3] = 0.9
    sh = np.abs((x - t * 0.6) - 0.15) < 0.1
    cv.arr[sh, :3] = np.clip(cv.arr[sh, :3] + 0.4, 0, 1)
    em_from(cv, sh.astype(np.float32) * 0.6, "#ffffff")


def aviator_goggles():
    brass = metal("#ffe08a", "#8a5a1a", 0.35)
    strap = lambda f, cv: leather(f, cv, "#5a3a20")
    return [Bone("goggles", "head", cubes=[
        Cube((-4, -6.5, -4), (8, 1, 8), strap, inflate=0.45),
        Cube((-4, -7.5, -5), (3, 3, 1), brass), Cube((1, -7.5, -5), (3, 3, 1), brass),
        Cube((-3.5, -7, -5.3), (2, 2, 0), lens), Cube((1.5, -7, -5.3), (2, 2, 0), lens),
        Cube((-1, -6.5, -5), (2, 1, 1), brass)])]


def moustache(face, cv):
    clear(cv)
    if face != "north":
        return
    L = layer(cv)
    W, H = cv.w * L.k, cv.h * L.k
    L.draw.chord((W * 0.08, -H * 0.2, W * 0.52, H * 1.1), 0, 180, fill=rgba("#2a1a12"))
    L.draw.chord((W * 0.48, -H * 0.2, W * 0.92, H * 1.1), 0, 180, fill=rgba("#2a1a12"))
    L.draw.ellipse((0, H * 0.1, W * 0.12, H * 0.6), fill=rgba("#2a1a12"))
    L.draw.ellipse((W * 0.88, H * 0.1, W, H * 0.6), fill=rgba("#2a1a12"))
    draw_over(cv, L)


def monocle_glass(face, cv):
    clear(cv)
    if face != "north":
        return
    y, x = cv.grid()
    r = np.sqrt((x + 0.5 - cv.w / 2) ** 2 + (y + 0.5 - cv.h / 2) ** 2) / (cv.w / 2)
    ring = (r > 0.78) & (r < 1.0)
    glass = r <= 0.78
    cv.arr[ring] = hexc("#f0c24a")
    cv.arr[glass] = (0.85, 0.95, 1.0, 0.35)
    sh = glass & (np.abs(x - y) < cv.s * 0.3)
    cv.arr[sh] = (1, 1, 1, 0.7)


def gentleman():
    gold = solid_paint(hexc("#f0c24a"))
    return [Bone("gent", "head", cubes=[Cube((-4, -3, -4.6), (8, 2, 0), moustache),
                                        Cube((0.5, -6.5, -4.6), (3, 3, 0), monocle_glass),
                                        Cube((3, -4, -4.55), (0, 4, 0), gold)])]


item("shadow_wrap", "Shadow Wrap", "MASK", REG, 250,
     "A dark cloth face wrap tied at the back with two loose tails.",
     ninja_wrap(), view=dict(HEAD_VIEW, yaw=40))
item("aviator_goggles", "Aviator Goggles", "MASK", REG, 250,
     "Brass flying goggles with amber lenses on a worn leather strap.",
     aviator_goggles(), view=HEAD_VIEW)
item("gentleman_kit", "Gentleman's Kit", "MASK", REG, 200,
     "A curled moustache and a gold monocle. Very distinguished.",
     gentleman(), view=dict(HEAD_VIEW, yaw=20))


FONT = {"G": ["111", "100", "101", "101", "111"], "W": ["10001", "10001", "10101", "10101", "01010"], "P": ["111", "101", "111", "100", "100"],
        " ": ["000"] * 5, "!": ["1", "1", "1", "0", "1"], "E": ["111", "100", "110", "100", "111"], "Z": ["111", "001", "010", "100", "111"]}


def led_visor(face, cv):
    cv.arr[:] = hexc("#0a0a0e")
    bevel(cv, 0.15, 0.25)
    if face != "north":
        return
    msg = "GG WP! "
    cols = []
    for ch in msg:
        g = FONT[ch]
        for c in range(len(g[0])):
            cols.append([g[r][c] == "1" for r in range(5)])
        cols.append([False] * 5)
    total = len(cols)
    led = max(1, cv.s // 2)
    nx, ny = cv.w // led, 5
    y0 = (cv.h - ny * led) // 2
    shift = int(cv.phase * total)
    for i in range(nx):
        colbits = cols[(i + shift) % total]
        for j in range(ny):
            sl = (slice(y0 + j * led, y0 + (j + 1) * led), slice(i * led, (i + 1) * led))
            if colbits[j]:
                cv.arr[sl][..., :3] = hexc("#ff2a3a")[:3]
                cv.em[sl] = (1.0, 0.2, 0.25, 1.0)
            else:
                cv.arr[sl][..., :3] = hexc("#2a0a0c")[:3]


item("led_visor", "LED Visor", "MASK", SPC, None,
     "A black visor with a red LED message that scrolls across it: GG WP!",
     [Bone("visor", "head", cubes=[Cube((-4, -7, -4.5), (8, 3, 1), led_visor, inflate=0.3),
                                   Cube((-4.5, -6.5, -4), (1, 2, 8), solid_paint(hexc("#16161c"))),
                                   Cube((3.5, -6.5, -4), (1, 2, 8), solid_paint(hexc("#16161c")))])],
     view=dict(HEAD_VIEW, yaw=18), animation=ANIM)


# ============================================================================================== back items

def wood(face, cv, c1="#e0a860", c2="#9a5a2a"):
    vgrad(cv, hexc(c1), hexc(c2))
    y, x = cv.grid()
    n = pnoise(cv.h, cv.w, 3.0, 9, octaves=2, aspect=4)
    cv.arr[..., :3] *= (0.88 + 0.18 * np.sin((x / cv.s + n * 3) * 4) * 0.5 + 0.09)[..., None]
    bevel(cv, 0.14, 0.24)


def guitar_body(face, cv):
    wood(face, cv)
    if face == "south":
        y, x = cv.grid()
        r = np.sqrt((x + 0.5 - cv.w / 2) ** 2 + (y + 0.5 - cv.h * 0.4) ** 2) / cv.s
        cv.arr[r < 1.1, :3] = hexc("#1a0e06")[:3]
        ring = np.abs(r - 1.35) < 0.15
        cv.arr[ring, :3] = hexc("#f4e0b0")[:3]
        bridge = (np.abs(y + 0.5 - cv.h * 0.75) < cv.s * 0.3) & (np.abs(x + 0.5 - cv.w / 2) < cv.s * 1.2)
        cv.arr[bridge, :3] = hexc("#2a1408")[:3]


def neck(face, cv):
    wood(face, cv, "#5a3a1e", "#3a220e")
    if face == "south":
        y, x = cv.grid()
        fret = (y % max(2, cv.s)) == 0
        cv.arr[fret, :3] = hexc("#d8d8d8")[:3]


def guitar():
    return [Bone("guitar", "body", (0, 5, 3.5), (0, 0, -30), cubes=[
        Cube((-3, 3, 2.5), (6, 8, 2), guitar_body), Cube((-2.5, 2, 2.5), (5, 1, 2), guitar_body),
        Cube((-0.5, -6, 3), (1, 8, 1), neck), Cube((-1, -8.5, 2.8), (2, 3, 1), lambda f, cv: wood(f, cv, "#3a220e", "#1a0e06"))]),
        Bone("guitar_strap", "body", (0, 5, 0), (0, 0, -32), cubes=[Cube((-6, 5, 2.05), (12, 1, 0), lambda f, cv: leather(f, cv, "#1a1a1e")),
                                                                    Cube((-4.5, 5, -2.3), (9, 1, 0), lambda f, cv: leather(f, cv, "#1a1a1e"))])]


def pack_fabric(face, cv):
    cv.arr[:] = hexc("#e4e4e4")
    weave(cv, 0.08)
    grain(cv, 0.04)
    ao_edges(cv, 0.15)
    bevel(cv, 0.12, 0.22)


def backpack():
    roll = lambda f, cv: (vgrad(cv, hexc("#9a7a4a"), hexc("#5a3a1a")), stitch_row(cv, cv.h // 2, "#3a2410"), bevel(cv, 0.15, 0.2))
    return [Bone("pack", "body", cubes=[Cube((-3.5, 1, 2), (7, 8, 3), pack_fabric), Cube((-2.5, 5, 5), (5, 3, 1), pack_fabric)], tint=True),
            Bone("pack_extras", "body", cubes=[Cube((-4, -0.5, 2.5), (8, 2, 2), roll),
                                               Cube((-3.5, 0, -2.3), (1, 7, 0), lambda f, cv: leather(f, cv, "#3a2a1e")),
                                               Cube((2.5, 0, -2.3), (1, 7, 0), lambda f, cv: leather(f, cv, "#3a2a1e")),
                                               Cube((-0.5, 5.5, 6), (1, 1, 0), solid_paint(hexc("#f0c24a")))])]


def katana(name, roll):
    lacquer = lambda f, cv: (vgrad(cv, hexc("#1a1a20"), hexc("#050508")), bevel(cv, 0.25, 0.2))
    gold = metal("#ffe9a0", "#9a6a1a", 0.35)
    wrapg = wrap_grip("#e8e0d0", "#b0142a")
    return Bone(name, "body", (0, 5, 3.6), (0, 0, roll), cubes=[
        Cube((-0.5, -2, 3), (1, 12, 1), lacquer), Cube((-1, -2.5, 2.5), (2, 1, 2), gold),
        Cube((-0.5, -6.5, 3), (1, 4, 1), wrapg), Cube((-0.5, 10, 3), (1, 1, 1), gold)])


item("acoustic_guitar", "Acoustic Guitar", "BACK", REG, 400,
     "A warm wooden guitar slung over your back on a black strap.",
     guitar(), view=BACK_VIEW)
item("trailblazer_pack", "Trailblazer Pack", "BACK", REG, 300,
     "A sturdy hiking backpack with a bedroll on top, in any colour you like.",
     backpack(), colourable=True, default_color="#3A8AFF", view=BACK_VIEW)
item("twin_katanas", "Twin Katanas", "BACK", REG, 500,
     "Two black-lacquered blades crossed on your back, with gold guards and red-wrapped grips.",
     [katana("katana_a", 32), katana("katana_b", -32)], view=BACK_VIEW)


def shell_paint(face, cv):
    vgrad(cv, hexc("#5aa84a"), hexc("#1e5a2a"))
    if face in ("south", "top", "bottom", "east", "west"):
        L = layer(cv)
        W, H = cv.w * L.k, cv.h * L.k
        R = cv.s * 1.6 * L.k
        for row in range(0, int(H / (R * 1.5)) + 2):
            for col in range(0, int(W / (R * 1.73)) + 2):
                cx = col * R * 1.73 + (R * 0.86 if row % 2 else 0)
                cy = row * R * 1.5
                pts = [(cx + math.cos(math.pi / 6 + i * math.pi / 3) * R, cy + math.sin(math.pi / 6 + i * math.pi / 3) * R) for i in range(6)]
                L.draw.polygon(pts, outline=rgba("#a8d88a"), width=max(1, int(cv.s * 0.25 * L.k)))
        draw_over(cv, L)
    bevel(cv, 0.2, 0.3)


item("turtle_shell", "Turtle Shell", "BACK", REG, 300,
     "A big green turtle shell with pale plate lines. Slow and steady.",
     [Bone("shell", "body", cubes=[Cube((-4, 0.5, 2), (8, 10, 2), shell_paint, inflate=0.3), Cube((-3, 1.5, 4), (6, 8, 1), shell_paint)])],
     view=BACK_VIEW)


def reactor_core(face, cv):
    t, u = cv.yy(), cv.xx()
    n = pnoise(cv.h, cv.w, 2.0, 400, octaves=3)
    n = fshift(n, -cv.phase * cv.h * 2, 0)
    swirl = 0.5 + 0.5 * np.sin((t * 6 - cv.phase * math.tau * 2) + (n - 0.5) * 6)
    col = ramp(swirl, [(0, "#0a2aff"), (0.5, "#3ad8ff"), (1, "#ffffff")])
    pulse = 0.65 + 0.35 * math.sin(cv.phase * math.tau * 2)
    cv.arr[..., :3] = col[..., :3]
    cv.arr[..., 3] = 1
    em_rgb(cv, col, np.full(t.shape, pulse, np.float32))


def reactor_pack():
    case = metal("#c8ccd4", "#3a3e48", 0.3)
    return [Bone("reactor", "body", cubes=[Cube((-3, 1, 2), (6, 8, 3), case), Cube((-3.5, 0.5, 2.5), (7, 1, 2), case),
                                           Cube((-3.5, 8.5, 2.5), (7, 1, 2), case),
                                           Cube((-3.5, 0, -2.3), (1, 7, 0), lambda f, cv: leather(f, cv, "#2a2a30")),
                                           Cube((2.5, 0, -2.3), (1, 7, 0), lambda f, cv: leather(f, cv, "#2a2a30"))]),
            Bone("core", "body", cubes=[Cube((-1.5, 2.5, 5), (3, 5, 0), reactor_core), Cube((-2.5, 3, 4.6), (1, 4, 1), reactor_core),
                                        Cube((1.5, 3, 4.6), (1, 4, 1), reactor_core)], emissive=True)]


item("reactor_pack", "Reactor Pack", "BACK", SPC, None,
     "A steel backpack with a glowing plasma core that swirls and pulses.",
     reactor_pack(), view=BACK_VIEW, animation=ANIM)


# ============================================================================================== pets

def penguin():
    body = fur_paint("#2a2e3a", "#14161e", belly="#f4f4f4", seed=51, belly_frac=0.7)
    head = with_face(fur_paint("#2a2e3a", "#14161e", seed=52), lambda cv: eyes(cv, [(1.0, 1.4), (3.0, 1.4)], (0.9, 1.0), "#1a1a22"))
    beak = solid_paint(hexc("#ffa23a"))
    feet = solid_paint(hexc("#ff8a1a"))
    flip = fur_paint("#2a2e3a", "#0e1016", seed=53)
    return [pet("penguin", [Cube((5, -5, -1), (4, 5, 3), body), Cube((5, -8.5, -1), (4, 4, 3), head), Cube((6.5, -6.5, -2.2), (1, 1, 1), beak),
                            Cube((5.5, 0, -1.5), (1, 0, 1), feet), Cube((7.5, 0, -1.5), (1, 0, 1), feet),
                            Cube((4.2, -4.5, -0.5), (1, 3, 2), flip), Cube((8.8, -4.5, -0.5), (1, 3, 2), flip)])]


def bee():
    def stripes(face, cv):
        cv.arr[:] = hexc("#ffcc2a")
        y, x = cv.grid()
        coord = (x if face in ("top", "bottom", "east", "west") else y) / cv.s
        band = (coord % 2) < 0.9
        if face in ("north",):
            band = np.zeros_like(band)
        cv.arr[band, :3] = hexc("#1a140a")[:3]
        bevel(cv, 0.14, 0.2)
        if face == "north":
            eyes(cv, [(0.9, 1.3), (2.1, 1.3)], (0.8, 0.9), "#1a1a22")

    def wing(face, cv):
        t, x = cv.yy(), cv.xx()
        cv.arr[:] = (0.85, 0.95, 1.0, 0.5)
        edge = (np.abs(x - 0.5) > 0.42) | (t < 0.08) | (t > 0.92)
        cv.arr[edge, 3] = 0.8
        em_from(cv, np.ones_like(t) * 0.1, "#ffffff")
    return [pet("bee", [Cube((5, -7, -1), (3, 3, 4), stripes), Cube((6, -6, 3), (1, 1, 1), solid_paint(hexc("#1a140a"))),
                        Cube((5.5, -8.5, -1.5), (0, 1, 0), solid_paint(hexc("#1a140a")))]),
            Bone("bee_wing_r", "root", PET, (0, 0, 25), [Cube((6.5, -8.2, -0.5), (3, 0, 2), wing)], anim=dict(PET_ANIM)),
            Bone("bee_wing_l", "root", PET, (0, 0, -25), [Cube((4.5, -8.2, -0.5), (3, 0, 2), wing, mirror=True)], anim=dict(PET_ANIM))]


def frog():
    skin = fur_paint("#7ad84a", "#3a8a2a", belly="#e8f4a8", seed=61, belly_frac=0.4)
    eye_p = with_face(fur_paint("#7ad84a", "#4a9a3a", seed=62), lambda cv: eyes(cv, [(0.5, 0.5)], (0.85, 0.85), "#e8c83a"))
    leg = fur_paint("#5ab83a", "#2a7a22", seed=63)
    return [pet("frog", [Cube((4.5, -3, -1.5), (4, 3, 4), skin), Cube((4.5, -4, -1.5), (1, 1, 1), eye_p), Cube((7.5, -4, -1.5), (1, 1, 1), eye_p),
                         Cube((4, -1, 1), (1, 1, 2), leg), Cube((8, -1, 1), (1, 1, 2), leg)])]


item("pebble_penguin", "Pebble the Penguin", "PET", REG, 500,
     "A round little penguin with flippers out and a bright orange beak, riding on your shoulder.",
     penguin(), view=dict(PET_VIEW, glow="#7ac8ff"))
item("buzz_bee", "Buzz the Bee", "PET", REG, 400,
     "A fuzzy striped bee with see-through wings that hovers by your shoulder.",
     bee(), view=dict(PET_VIEW, glow="#ffd23a"))
item("lily_frog", "Lily the Frog", "PET", REG, 400,
     "A bright green frog with golden eyes perched on your shoulder.",
     frog(), view=dict(PET_VIEW, glow="#7ad84a"))


def ghost_body(face, cv):
    t = cv.yy()
    n = pnoise(cv.h, cv.w, 2.0, 70, octaves=2)
    n = fshift(n, -cv.phase * cv.h, 0)
    cv.arr[:] = ramp(t, [(0, "#ffffff"), (1, "#bcd0f0")])
    cv.arr[..., :3] *= (0.92 + 0.12 * n)[..., None]
    flick = 0.62 + 0.18 * math.sin(cv.phase * math.tau) + 0.08 * math.sin(cv.phase * math.tau * 3)
    cv.arr[..., 3] = flick
    em_from(cv, np.ones_like(t) * 0.25, "#d8e8ff")
    if face == "north":
        y, x = cv.grid()
        ux, uy = (x + 0.5) / cv.s, (y + 0.5) / cv.s
        blink = 0.15 if 0.7 < cv.phase < 0.76 else 1.0
        for cx in (1.0, 2.6):
            e = ((ux - cx) / 0.45) ** 2 + ((uy - 1.4) / (0.6 * blink)) ** 2 < 1
            cv.arr[e] = (0.1, 0.1, 0.2, 1.0)
            em_from(cv, e.astype(np.float32), "#6ad8ff")
        mouth = ((ux - 1.8) / 0.4) ** 2 + ((uy - 2.6) / 0.3) ** 2 < 1
        cv.arr[mouth] = (0.15, 0.1, 0.2, 1.0)


def ghost():
    return [pet("ghost", [Cube((4.5, -8, -1), (4, 5, 4), ghost_body), Cube((4.5, -3, -1), (1, 1, 4), ghost_body),
                          Cube((6, -3, -1), (1, 2, 4), ghost_body), Cube((7.5, -3, -1), (1, 1, 4), ghost_body)], alpha=0.85)]


def flame_spirit_body(face, cv):
    heat = flame_field(cv, 91 + cv.w, speed=1.0)
    t = cv.yy()
    heat = np.clip(heat * 0.6 + (t * 0.6), 0, 1)
    col = ramp(heat, FIRE)
    cv.arr[..., :3] = col[..., :3]
    cv.arr[..., 3] = 1
    em_rgb(cv, col, np.clip(heat * 1.1, 0, 1))
    if face == "north" and cv.h > cv.s * 3:
        eyes(cv, [(1.0, 2.0), (2.4, 2.0)], (0.6, 0.8), "#fff4c0", pupil="#3a0a04", shine=False)


def flame_spirit():
    return [pet("flame", [Cube((5, -6, -1), (3, 4, 3), flame_spirit_body), Cube((5.5, -8, -0.5), (2, 2, 2), flame_spirit_body),
                          Cube((6, -9.5, 0), (1, 2, 1), flame_spirit_body)], emissive=True)]


item("boo_ghost", "Boo the Ghost", "PET", SPC, None,
     "A shy little ghost that flickers in and out, with glowing eyes that blink now and then.",
     ghost(), view=dict(PET_VIEW, glow="#8ad8ff"), animation=ANIM)
item("cinder_spirit", "Cinder the Spirit", "PET", SPC, None,
     "A living flame with a happy face that flickers and burns by your shoulder.",
     flame_spirit(), view=dict(PET_VIEW, glow="#ff8a3a"), animation=ANIM)


# ============================================================================================== item skins

def candy_blade(face, cv):
    cv.arr[:] = hexc("#ffffff")
    y, x = cv.grid()
    stripe = (((x + y) / cv.s) % 2) < 0.9
    cv.arr[stripe, :3] = hexc("#e8142a")[:3]
    t = cv.yy()
    cv.arr[..., :3] = np.clip(cv.arr[..., :3] + (np.exp(-((cv.xx() - 0.3) / 0.12) ** 2) * 0.25)[..., None], 0, 1)
    bevel(cv, 0.25, 0.25)


def thorn_blade(face, cv):
    glass_blade("#e0ffc0", "#4aa83a", "#14381a")(face, cv)
    if face in ("north", "south"):
        L = layer(cv)
        H, W = cv.h * L.k, cv.w * L.k
        pts = [(W * (0.5 + 0.3 * math.sin(i / 3)), H * i / 24) for i in range(25)]
        L.draw.line(pts, fill=rgba("#2a6a1a"), width=max(1, int(cv.s * 0.3 * L.k)))
        for i in range(2, 24, 4):
            x, y = pts[i]
            L.draw.ellipse((x - cv.s * 0.5 * L.k, y - cv.s * 0.3 * L.k, x + cv.s * 0.2 * L.k, y + cv.s * 0.3 * L.k), fill=rgba("#7ae84a"))
        draw_over(cv, L)


def plasma_blade(face, cv):
    t, x = cv.yy(), cv.xx()
    if face in ("north", "south"):
        core = np.exp(-((x - 0.5) / 0.18) ** 2)
        bands = 0.5 + 0.5 * np.sin((t * 8 + cv.phase * 2) * math.tau)
        col = ramp(np.clip(core * (0.7 + 0.3 * bands), 0, 1), [(0, "#3a0aff"), (0.5, "#c03aff"), (1, "#ffffff")])
        cv.arr[..., :3] = col[..., :3]
        cv.arr[..., 3] = 0.95
        em_rgb(cv, col, np.clip(0.6 + core * 0.4, 0, 1))
    else:
        cv.arr[:] = hexc("#c03aff")
        em_from(cv, np.ones_like(t), "#c03aff")


item("candy_cane_blade", "Candy Cane Blade", "ITEM_SKIN", REG, 200,
     "Your sword as a striped peppermint candy cane with a gold guard. Shows on swords you hold, on your screen.",
     sword(candy_blade, candy_blade, metal("#ffe9a0", "#9a6a1a", 0.35), wrap_grip("#ffffff", "#e8142a"), metal("#ffe9a0", "#9a6a1a"),
           gem_paint("#d8fff0", "#3ac86a")),
     item_space=True, display=SWORD_DISPLAY, view=ITEM_VIEW)
item("verdant_thorn", "Verdant Thorn", "ITEM_SKIN", REG, 250,
     "Your sword grown from green glass, wrapped in a living vine with leaves. Shows on swords you hold, on your screen.",
     sword(thorn_blade, glass_blade("#e0ffc0", "#4aa83a", "#14381a"), lambda f, cv: wood(f, cv, "#6a4a2a", "#3a220e"),
           wrap_grip("#3a220e", "#7ae84a"), lambda f, cv: wood(f, cv, "#6a4a2a", "#3a220e"), gem_paint("#f0ffd0", "#7ae84a")),
     item_space=True, display=SWORD_DISPLAY, view=ITEM_VIEW)
item("plasma_edge", "Plasma Edge", "ITEM_SKIN", SPC, None,
     "Your sword as a humming violet energy blade with light pulsing up the edge. Shows on swords you hold, on your screen.",
     sword(plasma_blade, plasma_blade, metal("#e0e4ea", "#3a3e48", 0.35), wrap_grip("#1a1a20", "#c03aff"), metal("#e0e4ea", "#3a3e48"),
           gem_paint("#f4d8ff", "#c03aff")),
     item_space=True, display=SWORD_DISPLAY, view=ITEM_VIEW, animation=ANIM)


# ============================================================================================== auras

item("clover_ring", "Clover Ring", "AURA", REG, 400,
     "A ring of green leaf runes turns at your feet, with a few petals drifting up.",
     sigil_bones(ring_sigil("#e8ffc0", "#2a9a3a", 6, 0.6)),
     fx={"type": "aura", "particle": "soft", "colors": ["#E8FFC0", "#7AD84A", "#2A9A3A"], "rate": 3, "size": 0.6, "life": 1.6,
         "sizeEnd": 0.3, "gravity": -0.25},
     view=AURA_VIEW)
item("galaxy_vortex", "Galaxy Vortex", "AURA", SPC, None,
     "A ring of every colour spins at your feet while the colours race round it.",
     sigil_bones(ring_sigil("#ffffff", "#8a5aff", 10, 0.65, hue=True), spin=40),
     fx={"type": "aura", "particle": "soft", "colors": ["#FFFFFF", "#B98CFF", "#5AD8FF"], "rate": 4, "size": 0.6, "life": 1.4,
         "sizeEnd": 0.3, "gravity": -0.3},
     view=AURA_VIEW, animation=ANIM)


# ============================================================================================== trails, kill and win effects

item("bubble_trail", "Bubble Trail", "TRAIL", REG, 300,
     "Shiny soap bubbles float up from your footsteps and pop.",
     fx={"type": "trail", "particle": "soft", "colors": ["#FFFFFF", "#9AE8FF", "#C8A8FF"], "rate": 8, "size": 0.9, "life": 1.4,
         "fade": 0.3, "sizeEnd": 0.6, "gravity": -0.15},
     thumb="trail")
item("heartbreaker", "Heartbreaker", "KILL_EFFECT", REG, 400,
     "Your opponent bursts into a shower of pink and red hearts.",
     fx={"type": "burst", "particle": "dust", "colors": ["#FF4A8A", "#FF8AB8", "#FFFFFF"], "count": 40, "size": 1.1},
     thumb="burst")
item("thunderclap", "Thunderclap", "KILL_EFFECT", REG, 400,
     "A kill cracks like thunder: yellow and white sparks burst out where they fell.",
     fx={"type": "burst", "particle": "dust", "colors": ["#FFF46A", "#FFFFFF", "#9AC8FF"], "count": 50, "size": 1.0, "spark": True},
     thumb="firework")
item("gold_rush", "Gold Rush", "WIN_EFFECT", REG, 400,
     "Win a game and a fountain of gold coins and sparkles bursts out around you.",
     fx={"type": "burst", "particle": "dust", "colors": ["#FFD23A", "#FFF0A0", "#C8901A"], "count": 90, "size": 1.3},
     thumb="confetti")
item("snowfall_finale", "Snowfall Finale", "WIN_EFFECT", REG, 400,
     "Win a game and a column of snowflakes swirls up around you.",
     fx={"type": "spiral", "particle": "soft", "colors": ["#FFFFFF", "#D8F0FF", "#9AD8FF"], "count": 70, "size": 0.9, "height": 3.2,
         "life": 1.6, "fade": 0.3, "sizeEnd": 0.4},
     thumb="fountain")


# ============================================================================================== emotes

item("facepalm", "Facepalm", "EMOTE", REG, 200,
     "Bury your face in your hand. Sometimes it's all you can do.",
     emote={"length": 2.0, "loop": False, "parts": {
         "rightArm": [[0, 0, 0, 0], [0.3, -130, -40, 0], [1.6, -130, -40, 0], [2.0, 0, 0, 0]],
         "head": [[0, 0, 0, 0], [0.3, 22, 0, 0], [1.6, 26, 0, 0], [2.0, 0, 0, 0]]}},
     view={"pose": {"rightArm": (-130, -40, 0), "head": (24, 0, 0)}, "yaw": -24, "glow": "#ffb07a"},
     thumb="emote")
item("shrug", "Shrug", "EMOTE", REG, 200,
     "Arms out, shoulders up. Who knows?",
     emote={"length": 1.4, "loop": False, "parts": {
         "rightArm": [[0, 0, 0, 0], [0.3, -40, 0, 50], [1.0, -40, 0, 50], [1.4, 0, 0, 0]],
         "leftArm": [[0, 0, 0, 0], [0.3, -40, 0, -50], [1.0, -40, 0, -50], [1.4, 0, 0, 0]],
         "head": [[0, 0, 0, 0], [0.3, 0, 0, 12], [1.0, 0, 0, 12], [1.4, 0, 0, 0]]}},
     view={"pose": {"rightArm": (-40, 0, 50), "leftArm": (-40, 0, -50), "head": (0, 0, 12)}, "yaw": -18, "glow": "#9ad8ff"},
     thumb="emote")
item("victory_flex", "Victory Flex", "EMOTE", REG, 200,
     "Both arms up, show the world those gains.",
     emote={"length": 1.6, "loop": True, "parts": {
         "rightArm": [[0, -20, 0, 100], [0.8, -20, 0, 115], [1.6, -20, 0, 100]],
         "leftArm": [[0, -20, 0, -100], [0.8, -20, 0, -115], [1.6, -20, 0, -100]],
         "head": [[0, -8, 0, 0], [0.8, -12, 0, 0], [1.6, -8, 0, 0]]}},
     view={"pose": {"rightArm": (-20, 0, 112), "leftArm": (-20, 0, -112), "head": (-10, 0, 0)}, "yaw": -20, "glow": "#ffd36a"},
     thumb="emote")
item("air_guitar", "Air Guitar", "EMOTE", REG, 200,
     "Shred an invisible guitar with your head banging to the riff.",
     emote={"length": 1.0, "loop": True, "parts": {
         "rightArm": [[0, -50, 30, 0], [0.25, -30, 30, 0], [0.5, -50, 30, 0], [0.75, -30, 30, 0], [1.0, -50, 30, 0]],
         "leftArm": [[0, -70, -40, 0], [1.0, -70, -40, 0]],
         "head": [[0, 20, 0, 0], [0.25, -5, 0, 0], [0.5, 20, 0, 0], [0.75, -5, 0, 0], [1.0, 20, 0, 0]]}},
     view={"pose": {"rightArm": (-40, 30, 0), "leftArm": (-70, -40, 0), "head": (18, 0, 0)}, "yaw": -28, "glow": "#ff5a8a"},
     thumb="emote")


# ============================================================================================== output

def hero_frames(it, size):
    """Large previews: one frame, or every frame for animated items, drawn by the engine's own thumbnail renderer
    at `size` (not scaled up from the small thumbnails)."""
    import io
    files = bc.build(it)
    d = list(bc.render_scene.__defaults__)
    keep, keep_thumb = tuple(d), bc.THUMB
    d[0], d[1] = size, 3
    bc.render_scene.__defaults__ = tuple(d)
    bc.THUMB = size
    try:
        if it["bones"]:
            model = json.loads(files["model.json"])
            tw, th = model["texture"]
            load = lambda name: np.asarray(Image.open(io.BytesIO(files[name])).convert("RGBA"), np.float32) / 255
            tex = load("texture.png")
            em = load("emissive.png") if "emissive.png" in files else None
            n = it["animation"]["frames"] if it["animation"] else 1
            fh = tex.shape[0] // n
            frames = []
            for k in range(n):
                t = tex[k * fh:(k + 1) * fh]
                e = em[k * fh:(k + 1) * fh] if em is not None else None
                frames.append(bc.thumb_model(it, t, e, tw, th, k / n if n > 1 else None, size=size))
        elif it["emote"]:
            frames = [bc.thumb_emote(it)]
        else:
            frames = [bc.thumb_particles(it["thumb"], it["fx"]["colors"])]
    finally:
        bc.render_scene.__defaults__ = keep
        bc.THUMB = keep_thumb
    return files, frames


def main():
    args = sys.argv[1:]
    only = set(args[args.index("--only") + 1].split(",")) if "--only" in args else None
    size = int(args[args.index("--hero") + 1]) if "--hero" in args else 320
    clash = [it["id"] for it in bc.ITEMS if it["id"] in SHIPPED]
    if clash:
        raise SystemExit(f"mockup ids already used by shipped items: {clash}")
    (OUT / "hero").mkdir(parents=True, exist_ok=True)
    listing = []
    for it in bc.ITEMS:
        if only and it["id"] not in only:
            continue
        files, frames = hero_frames(it, size)
        d = OUT / it["id"]
        d.mkdir(parents=True, exist_ok=True)
        for name, data in files.items():
            (d / name).write_bytes(data)
        hero = OUT / "hero" / f"{it['id']}.webp"
        if len(frames) > 1:
            ms = it["animation"]["frametime"] * 50 * 2
            frames[0].save(hero, save_all=True, append_images=frames[1:], duration=ms, loop=0, quality=88, method=4)
        else:
            frames[0].save(hero, quality=90, method=4)
        listing.append({"id": it["id"], "name": it["name"], "slot": it["slot"], "tier": it["rarity"].lower(),
                        "price": it["price"], "colourable": it["colourable"], "description": it["desc"],
                        "animated": len(frames) > 1, "hero": f"hero/{it['id']}.webp"})
        print(f"  {it['rarity'][:3]} {it['slot']:<12} {it['name']}", file=sys.stderr)
    if not only:
        (OUT / "mockups.json").write_text(json.dumps(listing, indent=1) + "\n")
    print(f"{len(listing)} mockups -> {OUT.relative_to(ROOT)}", file=sys.stderr)


if __name__ == "__main__" and "--sheets" not in sys.argv:
    main()


# ============================================================================================== picture sheets

SHEETS = [("capes", "Capes", {"CAPE"}), ("wings", "Wings", {"WINGS"}), ("hats", "Hats", {"HAT"}), ("masks", "Masks", {"MASK"}),
          ("back-items", "Back Items", {"BACK"}), ("pets", "Pets", {"PET"}), ("sword-skins", "Sword Skins", {"ITEM_SKIN"}),
          ("auras-and-effects", "Auras & Effects", {"AURA", "TRAIL", "KILL_EFFECT", "WIN_EFFECT"}), ("emotes", "Emotes", {"EMOTE"})]
SLOT_NAMES = {"CAPE": "Cape", "WINGS": "Wings", "HAT": "Hat", "MASK": "Mask", "BACK": "Back item", "PET": "Pet", "ITEM_SKIN": "Sword skin",
              "AURA": "Aura", "TRAIL": "Trail", "KILL_EFFECT": "Kill effect", "WIN_EFFECT": "Win effect", "EMOTE": "Emote"}


def sheets():
    """One picture per category (still frames, animated items marked) plus every item as its own picture, in
    build/cosmetic-mockups/pictures/<category>/ (animated items as GIFs too)."""
    from PIL import ImageDraw, ImageFont
    listing = json.loads((OUT / "mockups.json").read_text())
    fonts = ROOT / "installer/src/main/resources/evade"
    F = lambda name, size: ImageFont.truetype(str(fonts / name), size)
    f_title, f_eyebrow = F("chakra-bolditalic.ttf", 64), F("inter-bold.ttf", 17)
    f_name, f_pill, f_body, f_price = F("inter-bold.ttf", 24), F("inter-bold.ttf", 14), F("inter-medium.ttf", 16), F("inter-bold.ttf", 17)
    BG, CARD, LINE = (5, 5, 6), (13, 13, 16), (29, 28, 34)
    TEXT, MUTED, FAINT, RED = (245, 245, 247), (163, 163, 173), (108, 108, 120), (225, 29, 46)
    TIER = {"regular": ((195, 199, 209), "REGULAR"), "special": ((255, 194, 61), "SPECIAL · ANIMATED")}
    pics = OUT / "pictures"
    pics.mkdir(exist_ok=True)
    IMG, PAD, COLS, GAP = 400, 22, 4, 22
    CARD_H = IMG + 196

    def stage():
        yy, xx = np.mgrid[0:IMG, 0:IMG].astype(np.float32)
        r = np.sqrt((xx - IMG / 2) ** 2 + (yy - IMG * 0.42) ** 2) / (IMG * 0.62)
        c0, c1 = np.array([42, 31, 10], np.float32), np.array([11, 10, 12], np.float32)
        a = np.clip(r, 0, 1)[..., None]
        return Image.fromarray((c0 * (1 - a) + c1 * a).astype(np.uint8), "RGB").convert("RGBA")

    STAGE = stage()

    def wrap(d, text, font, width, lines=3):
        out, cur = [], ""
        for w in text.split():
            t = (cur + " " + w).strip()
            if d.textlength(t, font=font) <= width:
                cur = t
            else:
                out.append(cur)
                cur = w
        out.append(cur)
        if len(out) > lines:
            out = out[:lines]
            while d.textlength(out[-1] + "…", font=font) > width:
                out[-1] = out[-1][:-1]
            out[-1] += "…"
        return out

    made = []
    for n, (slug, title, slots) in enumerate(SHEETS, 1):
        items = [i for i in listing if i["slot"] in slots]
        items.sort(key=lambda i: (i["tier"] != "regular", list(SLOT_NAMES).index(i["slot"]), i["name"]))
        cols = min(COLS, len(items))
        rows = (len(items) + cols - 1) // cols
        W = max(cols, 3) * (IMG + GAP) - GAP + 2 * 48
        H = 190 + rows * (CARD_H + GAP) - GAP + 60
        sheet = Image.new("RGB", (W, H), BG)
        d = ImageDraw.Draw(sheet)
        d.text((48, 44), "EVADE COSMETICS  ·  MOCKUPS", font=f_eyebrow, fill=RED)
        d.text((44, 70), title, font=f_title, fill=TEXT)
        reg = sum(i["tier"] == "regular" for i in items)
        d.text((48, 150), f"{len(items)} designs  ·  {reg} regular  ·  {len(items) - reg} special (animated)", font=f_body, fill=MUTED)
        folder = pics / f"{n:02d}-{slug}"
        folder.mkdir(exist_ok=True)
        for k, it in enumerate(items):
            x, y = 48 + (k % cols) * (IMG + GAP), 190 + (k // cols) * (CARD_H + GAP)
            d.rounded_rectangle((x, y, x + IMG - 1, y + CARD_H - 1), 16, fill=CARD, outline=LINE, width=2)
            hero = Image.open(OUT / it["hero"])
            frames = []
            try:
                while True:
                    frames.append(hero.convert("RGBA").resize((IMG, IMG), Image.LANCZOS))
                    hero.seek(hero.tell() + 1)
            except EOFError:
                pass
            still = frames[min(len(frames) - 1, len(frames) // 3)] if len(frames) > 1 else frames[0]
            card = STAGE.copy()
            card.alpha_composite(still)
            mask = Image.new("L", (IMG, IMG), 0)
            ImageDraw.Draw(mask).rounded_rectangle((0, 0, IMG - 1, IMG + 30), 16, fill=255)
            sheet.paste(card.convert("RGB"), (x, y), mask)
            col, label = TIER[it["tier"]]
            tw = d.textlength(label, font=f_pill)
            d.rounded_rectangle((x + 14, y + 14, x + 14 + tw + 20, y + 42), 8, fill=(0, 0, 0), outline=col, width=2)
            d.text((x + 24, y + 19), label, font=f_pill, fill=col)
            ty = y + IMG + 16
            d.text((x + 18, ty), it["name"], font=f_name, fill=TEXT)
            price = "Price coming soon" if it["price"] is None else f"${it['price'] / 100:.2f}"
            meta = SLOT_NAMES[it["slot"]] + ("  ·  any colour" if it["colourable"] else "")
            d.text((x + 18, ty + 36), meta, font=f_body, fill=FAINT)
            pw = d.textlength(price, font=f_price)
            d.text((x + IMG - 18 - pw, ty + 35), price, font=f_price, fill=(255, 194, 61) if it["price"] is None else TEXT)
            for j, line in enumerate(wrap(d, it["description"], f_body, IMG - 36)):
                d.text((x + 18, ty + 68 + j * 23), line, font=f_body, fill=MUTED)
            # each design on its own too
            single = STAGE.copy()
            single.alpha_composite(still)
            single.convert("RGB").save(folder / f"{it['id']}.png")
            if len(frames) > 1:
                gif = []
                for fr in frames:
                    g = STAGE.copy()
                    g.alpha_composite(fr)
                    gif.append(g.convert("RGB").resize((320, 320), Image.LANCZOS))
                gif[0].save(folder / f"{it['id']}.gif", save_all=True, append_images=gif[1:], duration=100, loop=0, optimize=True)
        path = pics / f"{n:02d}-{slug}.png"
        sheet.save(path, optimize=True)
        made.append(path)
    print(f"{len(made)} category sheets -> {pics.relative_to(ROOT)}", file=sys.stderr)
    return made


if __name__ == "__main__" and "--sheets" in sys.argv:
    sheets()
