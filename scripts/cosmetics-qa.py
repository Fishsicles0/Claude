#!/usr/bin/env python3
"""Cosmetics quality test: renders every catalogue item and scores it PASS / WEAK / FAIL.

For each item it reads the BUILT files (site-src/cosmetics/<id>/, or --dir) and renders, with the generator's own
thumbnail renderer (scripts/build-cosmetics.py, imported):
  * the shipped thumbnail (first frame of a strip), on the shop's card colour
  * the item on its own (the player hidden), from the thumbnail camera, for the measurements below
  * wings only: the same wings at the fold (sneak) and at both ends of the beat, checked against the player's body

Automatic checks (thresholds in CHECKS, calibrated against the best items: hearthfire_cape, riptide_cape, ember_mantle and
the rebuilt pets):
  size      thumbnail dimensions (regular 256, pets and auras 128 or more)
  fill      how much of the frame the item fills (bounding box of the item alone, per slot)
  dim       mean brightness of the item on the card (emotes: the figure) and its contrast with the card
  busy      colour-band noise: the share of neighbouring pixels inside the item that jump in colour
  palette   distinct colours in a regular item's texture: a noisy palette (crisp pixel art keeps a few bands)
  outline   whether the silhouette edge stands out from its inside or from the card
  solidity  item area / convex-hull area, and fragments: stringy or scattered silhouettes
  texels    texture density (texels per model unit) and texels per thumbnail pixel (shimmer when too many)
  wings     arch: does the leading edge rise from the shoulder (top profile range), strips: does it read as separate
            vertical bars (alpha runs per row through the middle of each wing), clearance: no part enters the body
            over the beat, fold and sneak
  similar   items in the same slot that look alike (32 x 32 colour + mask distance)
Then the by-eye review (EYE below, judged against the reference items on the contact sheets) is merged in: an item's
verdict is the worse of the two.

Strict tier ("best in class", see the strict-tier block below): every face of every cube on the texture (no flat, default
grey, stretched or tiled faces, one texel density per item), the item alone from front, back, side, top and both
three-quarter views (views/<id>.png, views-NN.png), the thumbnail at 64 px, glow on glowing items, smooth seamless loops,
centred thumbnails, and EYE_STRICT (the by-eye verdict on the view sheets). --no-strict skips it.

Minecraft style (pets, strict tier; see mc_style()): the pets must sit naturally next to the game's own mobs:
  density   texels per model unit on the texture's own art grid: 1-2 (16-32 per block; a pet is a half-size mob and keeps
            the 16 per block of a full-size one, as the game's baby animals do). Finer than that reads as a cartoon
            sticker, not a mob
  head      the head's share of the pet's solid volume (a small head on a proper body, not a chibi head on a bean)
  outline   no drawn outline: faces whose every side is darker than the texels just inside it (the game shades by light
            and a darker bottom edge, never a rim)
Pets are also judged for flat colour at the size of their texels (views shrunk to about two pixels a texel), so a
crisp 16-per-block texel isn't counted as a flat patch.

Output (default scratchpad/qa, --out): report.md, sheet-all.png, sheet-<slot>.png, items/<id>.png (thumbnail | item
alone | wing poses). --compare DIR adds before-after.png from an older copy of the built cosmetics.

Usage: python3 scripts/cosmetics-qa.py [--out DIR] [--dir BUILT] [--only id,id] [--compare OLD_BUILT_DIR]
"""
import importlib.util
import io
import json
import math
import pathlib
import re
import sys

import numpy as np
from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "scripts/build-cosmetics.py"
CARD = (32, 33, 42)
REFERENCE = ("hearthfire_cape", "riptide_cape", "ember_mantle", "ginger_cat", "ember_fox", "panda_cub", "baby_dragon")

# By-eye review, judged on the contact sheets against the reference items. Only items with something to say are listed;
# anything not listed passes the eye test. Update this after looking at sheet-<slot>.png.
EYE = {
    # gemini concept collection (reported to its builder, not fixed here)
}

# wings shaped like an insect's (lobes on a thorax, no arm bone): the arch and strip checks don't apply
INSECT_WINGS = ("azure_butterfly_wings", "dusk_moth_wings")

# per-slot thresholds (capes' busy limit: Dragonhide's raised scales, 31%, read cleanly by eye; Tidal Weave's
# old foam rings, 40%, did not): (min fill, max busy, max distinct colours in a regular item's texture). The texture palette, not
# the lit render: the crisp pixel art keeps a few flat bands per face (the references use 60-160), a noisy painter many.
SLOT_LIMITS = {
    "CAPE": (0.30, 0.33, 240), "WINGS": (0.62, 0.30, 120), "HAT": (0.42, 0.20, 170), "MASK": (0.42, 0.20, 170),
    "BACK": (0.30, 0.24, 170), "AURA": (0.62, 0.30, 400), "PET": (0.55, 0.24, 9999), "ITEM_SKIN": (0.55, 0.20, 140),
    "EMOTE": (0.45, 1.0, 9999), "TRAIL": (0.45, 1.0, 9999), "KILL_EFFECT": (0.45, 1.0, 9999),
    "WIN_EFFECT": (0.45, 1.0, 9999),
}


def load_generator():
    spec = importlib.util.spec_from_file_location("evade_cosmetics_gen", SRC)
    bc = importlib.util.module_from_spec(spec)
    sys.modules["evade_cosmetics_gen"] = bc
    argv = sys.argv
    sys.argv = [str(SRC)]
    try:
        spec.loader.exec_module(bc)
    finally:
        sys.argv = argv
    return bc


def sections():
    """id -> section ("shipped", "mockup", "gemini", or another header's name) from the generator's section headers."""
    out, sec = {}, "shipped"
    for line in SRC.read_text().split("\n"):
        if line.startswith("# ====="):
            low = line.lower()
            if "end of" in low:
                sec = "shipped"
            elif "mockup collection" in low:
                sec = "mockup"
            elif "gemini" in low:
                sec = "gemini"
        m = re.match(r'item\(\s*"([a-z0-9_]+)"', line)
        if m:
            out[m.group(1)] = sec
    return out


def first_frame(im):
    im = im.convert("RGBA")
    return im.crop((0, 0, im.width, im.width)) if im.height > im.width else im


def on_card(im):
    bg = Image.new("RGBA", im.size, CARD + (255,))
    bg.alpha_composite(im.convert("RGBA"))
    return bg.convert("RGB")


# ---------------------------------------------------------------------------------------------- rebuilding a model from disk

FACE_NAMES = {"up": "top", "down": "bottom", "west": "west", "north": "north", "east": "east", "south": "south"}


def bones_from_json(bc, model):
    bones = []
    for bd in model["bones"]:
        cubes = []
        for cd in bd["cubes"]:
            faces = None
            if "faces" in cd:
                faces = {FACE_NAMES[f]: (v["uv"], int(v.get("rotation", 0)) // 90, True) for f, v in cd["faces"].items()}
            c = bc.Cube(cd["origin"], cd["size"], None, cd.get("inflate", 0.0), cd.get("mirror", False), faces)
            c.uv = cd.get("uv")
            cubes.append(c)
        bones.append(bc.Bone(bd["name"], bd["attach"], bd["pivot"], bd["rotation"], cubes, bd.get("anim"),
                             bd.get("emissive", False), bd.get("tint", False), bd.get("alpha", 1.0), bd.get("parent")))
    return bones


def load_tex(path, tw, th, scale):
    if not path.exists():
        return None
    a = np.asarray(Image.open(path).convert("RGBA"), np.float32) / 255
    return a[:th * scale]


class NoPlayer:
    """Swaps the generator's mannequin for an invisible one (the parts' matrices only), for item-alone renders."""

    def __init__(self, bc):
        self.bc, self.orig = bc, bc.add_mannequin

    def __enter__(self):
        orig, bc = self.orig, self.bc
        self.bc.add_mannequin = lambda scene, pose=None, alpha=1.0, parts=None, shadow=True: orig(bc.Scene(), pose, alpha, parts, False)
        return self

    def __exit__(self, *a):
        self.bc.add_mannequin = self.orig


def render_alone(bc, it, d, size=256, wing_extra=None):
    """The item on its own from the thumbnail camera (RGBA image), or None when it has no model."""
    mpath = d / "model.json"
    if it["emote"]:
        pose = it["view"].get("pose") or bc.emote_key_pose(it["emote"])
        scene = bc.Scene()
        bc.add_mannequin(scene, pose, shadow=False)
        return bc.render_scene(scene, it["view"].get("yaw", -24), it["view"].get("pitch", 6), size=size)
    if not mpath.exists():
        return None
    model = json.loads(mpath.read_text())
    tw, th = model["texture"]
    sc = model["textureScale"]
    tex = load_tex(d / "texture.png", tw, th, sc)
    em = load_tex(d / "emissive.png", tw, th, sc)
    bones = bones_from_json(bc, model)
    view = dict(it["view"])
    if wing_extra is not None:
        view["wing_open"] = wing_extra
        bones = [bc.Bone(b.name, b.attach, b.pivot, b.rotation, b.cubes, b.anim, b.emissive, b.tint, b.alpha, b.parent)
                 for b in bones]
    with NoPlayer(bc):
        im = bc.thumb_model(dict(it, bones=bones, view=view), tex, em, tw, th, size=size)
    return im


# ---------------------------------------------------------------------------------------------- image measurements

def lum(rgb):
    return rgb[..., 0] * 0.2126 + rgb[..., 1] * 0.7152 + rgb[..., 2] * 0.0722


def erode(m, r=1):
    out = m.copy()
    for _ in range(r):
        o = out.copy()
        o[1:] &= out[:-1]
        o[:-1] &= out[1:]
        o[:, 1:] &= out[:, :-1]
        o[:, :-1] &= out[:, 1:]
        out = o
    return out


def components(m, min_px):
    """Connected pieces (4-neighbour) of a mask that are at least min_px pixels."""
    h, w = m.shape
    lab = np.zeros((h, w), np.int32)
    n = 0
    sizes = []
    ys, xs = np.nonzero(m)
    for y0, x0 in zip(ys, xs):
        if lab[y0, x0]:
            continue
        n += 1
        stack = [(y0, x0)]
        lab[y0, x0] = n
        cnt = 0
        while stack:
            y, x = stack.pop()
            cnt += 1
            for yy, xx in ((y + 1, x), (y - 1, x), (y, x + 1), (y, x - 1)):
                if 0 <= yy < h and 0 <= xx < w and m[yy, xx] and not lab[yy, xx]:
                    lab[yy, xx] = n
                    stack.append((yy, xx))
        sizes.append(cnt)
    return sum(1 for s in sizes if s >= min_px)


def hull_area(m):
    ys, xs = np.nonzero(m)
    if len(xs) < 3:
        return 1.0
    pts = sorted(set(zip(xs.tolist(), ys.tolist())))
    # only each row's extremes matter for the hull
    rows = {}
    for x, y in pts:
        lo, hi = rows.get(y, (x, x))
        rows[y] = (min(lo, x), max(hi, x))
    pts = sorted({(lo, y) for y, (lo, hi) in rows.items()} | {(hi + 1, y) for y, (lo, hi) in rows.items()}
                 | {(lo, y + 1) for y, (lo, hi) in rows.items()} | {(hi + 1, y + 1) for y, (lo, hi) in rows.items()})

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    lower, upper = [], []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    hull = lower[:-1] + upper[:-1]
    a = 0.0
    for (x1, y1), (x2, y2) in zip(hull, hull[1:] + hull[:1]):
        a += x1 * y2 - x2 * y1
    return max(1.0, abs(a) / 2)


def measure(im, slot):
    a = np.asarray(im.convert("RGBA"), np.float32) / 255
    alpha = a[..., 3]
    m = alpha > 0.5
    S = im.width
    out = {}
    if not m.any():
        return {"empty": True}
    ys, xs = np.nonzero(m)
    out["fill"] = max(xs.max() - xs.min() + 1, ys.max() - ys.min() + 1) / S
    out["cover"] = float(m.mean())
    card = np.array(CARD, np.float32) / 255
    rgb = a[..., :3] * alpha[..., None] + card * (1 - alpha[..., None])
    L = lum(rgb)
    out["lum"] = float(L[m].mean())
    out["lum_hi"] = float(np.percentile(L[m], 90))
    inner = erode(m, 2)
    # colour-band noise: neighbouring pixels (both inside) whose colour jumps
    dx = np.abs(rgb[:, 1:] - rgb[:, :-1]).sum(-1)
    dy = np.abs(rgb[1:] - rgb[:-1]).sum(-1)
    mx = inner[:, 1:] & inner[:, :-1]
    my = inner[1:] & inner[:-1]
    n = mx.sum() + my.sum()
    out["busy"] = float(((dx > 0.30) & mx).sum() + ((dy > 0.30) & my).sum()) / max(1, n)
    # grain direction: colour steps across (x) vs along (y); vertical bars / curtains step mostly across
    out["aniso"] = float(((dx > 0.12) & mx).sum() + 1) / float(((dy > 0.12) & my).sum() + 1)
    q = (np.clip(rgb[m], 0, 1) * 15).astype(int)
    out["colours"] = int(len(np.unique(q[:, 0] * 256 + q[:, 1] * 16 + q[:, 2])))
    ring = m & ~erode(m, 2)
    core = erode(m, 4)
    lr = float(L[ring].mean()) if ring.any() else 0
    lc = float(L[core].mean()) if core.any() else lr
    lb = float(lum(card))
    # colour distance too: a red edge on the dark card stands out though its brightness is close to the card's
    cr = rgb[ring].mean(0) if ring.any() else card
    out["outline"] = max(abs(lr - lc), abs(lr - lb), float(np.abs(cr - card).mean()) * 0.5)
    hull = hull_area(m)
    out["solidity"] = float(m.sum()) / hull
    out["frags"] = components(m[::2, ::2], max(6, int(m.sum() / 4 * 0.004)))
    return out


def wing_shape(im):
    """Arch and strips for a pair of wings seen from behind: per half (left/right of the frame's middle)."""
    a = np.asarray(im.convert("RGBA"), np.float32)[..., 3] / 255
    m = a > 0.5
    S = m.shape[1]
    res = []
    for half in (m[:, :S // 2 - S // 16], m[:, S // 2 + S // 16:]):
        if half.sum() < 50:
            res.append((0.0, 0.0))
            continue
        ys, xs = np.nonzero(half)
        y0, y1 = ys.min(), ys.max()
        H = y1 - y0 + 1
        cols = [x for x in range(half.shape[1]) if half[:, x].any()]
        tops = np.array([np.argmax(half[:, x]) for x in cols], float)
        # leading edge: how far the top profile climbs across the wing, relative to the wing's height
        arch = (np.percentile(tops, 95) - np.percentile(tops, 5)) / H
        runs = []
        for y in range(int(y0 + H * 0.3), int(y0 + H * 0.7)):
            r = half[y].astype(int)
            runs.append(int((np.diff(r) == 1).sum() + r[0]))
        res.append((float(arch), float(np.mean(runs)) if runs else 0.0))
    return res


# ---------------------------------------------------------------------------------------------- wing clearance

BODY_BOX = ((-4.0, 0.0, -2.0), (4.0, 12.0, 2.0))
HEAD_BOX = ((-4.0, -8.0, -4.0), (4.0, 0.0, 4.0))


def wing_clearance(bc, model, alpha=None):
    """The deepest any wing cube's surface sample goes into the body or head box (model units; > 0.25 = clipping), over
    the beat (both ends), the fold (sneak) and rest. Only parts more than 3 units from the bone's pivot count (the root
    joint and its ornaments sit on the shoulder blade by design)."""
    bones = {b["name"]: b for b in model["bones"]}
    worst = 0.0

    def mat(b, state):
        if b.get("parent"):
            M = mat(bones[b["parent"]], state)
        else:
            M = np.eye(4)
        pitch, yaw, roll = b["rotation"]
        an = b.get("anim") or {}
        if an.get("type") == "wing":
            fold, w = state
            base, amp = an.get("base", 0) + an.get("fold", 28) * fold, an.get("amp", 0) * (1 - 0.75 * fold)
            side = an.get("side", 1)
            yaw += side * (base + amp * w)
            pitch += an.get("lift", 0) * max(0, w) - fold * 12
            roll += side * fold * an.get("fold", 28) * 0.35
        return M @ bc.translate(b["pivot"]) @ bc.rotate(pitch, yaw, roll) @ bc.translate([-v for v in b["pivot"]])
    for state in ((0, -1), (0, 1), (0, 0), (1, -1), (1, 1), (0.5, 0)):
        for b in model["bones"]:
            if b["attach"] != "body":
                continue
            M = mat(b, state)
            piv = np.array(b["pivot"], float)
            for c in b["cubes"]:
                o = np.array(c["origin"], float)
                s = np.array(c["size"], float)
                g = [np.linspace(o[k], o[k] + s[k], max(2, int(s[k] * 2) + 1)) for k in range(3)]
                P = np.stack(np.meshgrid(*g, indexing="ij"), -1).reshape(-1, 3)
                far = np.linalg.norm(P - piv, axis=1) > 3.0
                if alpha is not None and s.min() == 0 and "uv" in c and c["uv"] is not None:
                    # a flat plane: only its opaque texels count (north face of the box UV; mirrored cubes run backwards)
                    sc = model["textureScale"]
                    u0, v0 = c["uv"]
                    d = s[2]
                    cx = (o[0] + s[0] - P[:, 0]) if c.get("mirror") else (P[:, 0] - o[0])
                    col = np.clip(((u0 + d + cx) * sc).astype(int), 0, alpha.shape[1] - 1)
                    row = np.clip(((v0 + d + P[:, 1] - o[1]) * sc).astype(int), 0, alpha.shape[0] - 1)
                    far &= alpha[row, col] > 0.5
                W = (M[:3, :3] @ P.T).T + M[:3, 3]
                for lo, hi in (BODY_BOX, HEAD_BOX):
                    lo, hi = np.array(lo), np.array(hi)
                    depth = np.minimum(W - lo, hi - W).min(1)
                    depth = np.where(far, depth, -1)
                    worst = max(worst, float(depth.max()))
    return worst


# ---------------------------------------------------------------------------------------------- scoring

def score(it, files_dir, m, wing, clear, texinfo):
    slot = it["slot"]
    fill_min, busy_max, col_max = SLOT_LIMITS.get(slot, (0.4, 0.25, 200))
    fails, weak = [], []
    th = texinfo.get("thumb")
    if th is None:
        fails.append("not built (no thumb.png)")
        return fails, weak
    need = 128 if slot in ("PET", "AURA") or it["rarity"] != "REGULAR" else 256
    if th[0] < need:
        weak.append(f"thumbnail {th[0]} px wide (< {need})")
    if m.get("empty"):
        fails.append("renders empty")
        return fails, weak
    if m["fill"] < fill_min * 0.75:
        fails.append(f"fills {m['fill']:.0%} of the frame (needs {fill_min:.0%})")
    elif m["fill"] < fill_min:
        weak.append(f"fills {m['fill']:.0%} of the frame (needs {fill_min:.0%})")
    dim_min = 0.30 if slot == "EMOTE" else 0.16
    if m["lum"] < dim_min * 0.7 and m["lum_hi"] < 0.35:
        fails.append(f"dim: mean brightness {m['lum']:.2f}")
    elif m["lum"] < dim_min and (m["lum_hi"] < 0.5 or slot == "EMOTE"):
        weak.append(f"dim: mean brightness {m['lum']:.2f} (top {m['lum_hi']:.2f})")
    if m["busy"] > busy_max * 1.35:
        fails.append(f"noisy: {m['busy']:.0%} of neighbouring pixels jump in colour (max {busy_max:.0%})")
    elif m["busy"] > busy_max:
        weak.append(f"busy: {m['busy']:.0%} of neighbouring pixels jump in colour (max {busy_max:.0%})")
    if texinfo.get("tex_colours", 0) > col_max and it["rarity"] == "REGULAR":
        weak.append(f"noisy palette: {texinfo['tex_colours']} distinct colours in the texture (max {col_max})")
    if m["outline"] < 0.035 and slot not in ("TRAIL", "KILL_EFFECT", "WIN_EFFECT"):
        weak.append(f"silhouette edge barely stands out ({m['outline']:.3f})")
    if slot in ("HAT", "MASK", "BACK", "ITEM_SKIN", "PET") and m["frags"] > 6:
        weak.append(f"silhouette in {m['frags']} separate pieces")
    sc = texinfo.get("scale")
    if sc is not None and slot not in ("AURA", "PET"):          # pets: their density is the Minecraft-style check
        if sc < 4:
            weak.append(f"{sc} texels per unit (blocky)")
        if texinfo.get("tpp", 0) > 3.5:
            weak.append(f"{texinfo['tpp']:.1f} texels per thumbnail pixel (detail lost, shimmers)")
    if slot == "WINGS" and wing and it["id"] not in INSECT_WINGS:
        arch = sum(a for a, _ in wing) / len(wing)
        strips = max(s for _, s in wing)
        if m.get("aniso", 1) > 2.2:
            fails.append(f"feathers read as vertical bars (colour steps {m['aniso']:.1f}x more across than down)")
        elif m.get("aniso", 1) > 1.6:
            weak.append(f"vertical grain (colour steps {m['aniso']:.1f}x more across than down)")
        if arch < 0.12:
            fails.append(f"no arched leading edge (top profile climbs {arch:.0%} of the wing's height)")
        elif arch < 0.2:
            weak.append(f"leading edge barely rises (top profile {arch:.0%} of the wing's height)")
        if strips > 3.2:
            fails.append(f"reads as separate vertical strips ({strips:.1f} pieces per row through the middle)")
        elif strips > 2.2:
            weak.append(f"partly strips ({strips:.1f} pieces per row through the middle)")
    if clear is not None and clear > 0.25:
        fails.append(f"clips the player by {clear:.2f} units over the beat/fold")
    return fails, weak


def similarity_pairs(feats, limit=0.88):
    """Pairs in one slot alike in colour (histogram of the item's own pixels), colour layout (32 x 32) and edge structure
    (64 x 64 gradient correlation): the lowest of the three."""
    out = []
    keys = list(feats)
    for i, a in enumerate(keys):
        for b in keys[i + 1:]:
            fa, fb = feats[a], feats[b]
            if fa[0] != fb[0]:
                continue
            col = 1 - np.abs(fa[1] - fb[1]).mean() * 2.5
            ea, eb = fa[2] - fa[2].mean(), fb[2] - fb[2].mean()
            edge = float((ea * eb).sum() / (np.sqrt((ea * ea).sum() * (eb * eb).sum()) + 1e-9))
            hist = float(np.minimum(fa[3], fb[3]).sum())
            sim = min(col, edge, hist)
            if sim > limit:
                out.append((a, b, sim))
    return out


def feature(im):
    a = np.asarray(im.convert("RGBA").resize((32, 32), Image.BOX), np.float32) / 255
    b = np.asarray(im.convert("RGBA").resize((64, 64), Image.BOX), np.float32) / 255
    L = lum(b[..., :3] * b[..., 3:4])
    g = np.hypot(np.diff(L, axis=1)[:-1], np.diff(L, axis=0)[:, :-1])
    full = np.asarray(im.convert("RGBA"), np.float32) / 255
    px = full[full[..., 3] > 0.5][:, :3]
    q = np.clip((px * 6).astype(int), 0, 5)
    h = np.bincount(q[:, 0] * 36 + q[:, 1] * 6 + q[:, 2], minlength=216).astype(np.float32)
    return np.concatenate([a[..., :3] * a[..., 3:4], a[..., 3:4]], -1), g, h / max(1.0, h.sum())


# ---------------------------------------------------------------------------------------------- strict tier
# The "best in class" bar: every item judged against the best ones (hearthfire_cape, riptide_cape, the rebuilt pets, the
# arched wings), face by face on the texture and view by view on renders from every side, not just the thumbnail.
#
#   faces     every face of every cube, on frame 0 of the texture: no flat (one colour) or default-grey (unpainted)
#             faces, a defined edge (its outer texel ring stands out from the ring inside it) on faces big enough to
#             show one, no stretched sampling (a face's UV rect must match its size: 1 texel = 1 texel), no tiled faces
#             (the same block repeated across a face), and the same texel density on every face of an item (effective
#             density: a face painted in 2x2 or 4x4 blocks counts as half or a quarter of the stored density)
#   views     front, back, side, top and both three-quarter views of the item alone (the emote figure with its prop):
#             no view that is mostly flat colour or mostly grey, every view lit (backs and sides hide low effort)
#   read64    the silhouette still reads at 64 px: contrast with the card and edge, no crumbling into fragments
#   glow      items that are described as glowing (glow, neon, molten, plasma, lightning ...) light emissive.png
#   loop      animated specials: visible motion between frames, no frame-to-frame jump much bigger than the rest (the
#             loop seam included), in the texture strip and the thumbnail strip
#   thumb     centred (the item's centroid within 9% of the middle), not cut off at the frame, lit
# The strict verdict is the worse of these and EYE_STRICT (the honest by-eye verdict on the per-view sheets).

VIEWS = (("front", 0, 8), ("back", 180, 8), ("side", 90, 4), ("top", 0, 82), ("3/4 front", 35, 22), ("3/4 back", 215, 20))
GLOW_WORDS = re.compile(r"\b(glow\w*|neon|molten|plasma|lightning|lava|blaz\w*|lumin\w*|radiant|incandescent|"
                        r"energy|reactor|fiery|aflame|crackl\w*)\b", re.I)
# animated items whose uneven steps are the design (a lightning strike, a blink, a glitch tear, a ghost's flicker):
# their loops are judged by eye
FLICKER = ("wandering_ghost", "personal_raincloud", "glitch_koi_cape", "void_eye_cape", "storm_ring_aura", "stormcaller_wings")
# faces left open on purpose: the ghost's sheet is open at the hem (its tatters are cut from the sides)
OPEN_FACES = ("wandering_ghost:ghost#0.bottom",)
# By-eye strict review: id -> (verdict, note). Every item is listed (judged on views/<id>.png next to the references).
EYE_STRICT = {
    "ember_mantle": ("PASS", 'molten cracks and hem glow; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "hearthfire_cape": ("PASS", 'reference: layered animated flames; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "tidal_weave": ("PASS", 'crisp scallop rows with foam caps; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "riptide_cape": ("PASS", 'reference: rolling animated waves; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "starlit_veil": ("PASS", 'moon, nebula and crisp stars; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "galaxy_flow_cape": ("PASS", 'turning spiral, seamless loop; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "polar_lights_cape": ("PASS", 'rippling curtains over peaks; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "chevron_standard": ("PASS", 'colourable chevrons read true when tinted; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "checkered_rally": ("PASS", 'rippling flag with pole stripe; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "highland_plaid": ("PASS", 'twill tartan with fringe (the fringe cuts the lining too); art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "blossom_drift": ("PASS", 'blossom branch and petals; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "pumpkin_night": ("PASS", 'moon, bats and a glowing carved pumpkin; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "frostfall_cloak": ("PASS", 'frost and snowflakes, fur collar on all faces; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "dragonhide_cape": ("PASS", 'raised scales rimmed in gold; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "neon_horizon": ("PASS", 'striped sun, glowing grid; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "oakheart_banner": ("PASS", 'oak shield, swallow tail cut through the lining; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "sweetheart_cape": ("PASS", 'outlined hearts, lace hem cut through; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "aurora_wings": ("PASS", 'arched, see-through, glowing veins; both sides painted'),
    "pulse_wings": ("PASS", 'arched, pulse runs out the feathers, gem core'),
    "ashen_feathers": ("PASS", 'layered grey feathers with ember tips'),
    "seraph_wings": ("PASS", 'white and gold layered feathers, gold spar'),
    "wyrmspan_wings": ("PASS", 'membrane with scalloped trailing edge and bone fingers'),
    "infernal_wings": ("PASS", 'dark membrane with glowing veins and spikes'),
    "stormcaller_wings": ("PASS", 'membrane with lightning strikes (uneven steps are the strikes)'),
    "azure_butterfly_wings": ("PASS", 'graded blue lobes with dotted rims and a body'),
    "clockwork_wings": ("PASS", 'steel feathers with brass coverts and glowing tips'),
    "glacier_wings": ("PASS", 'ice feathers, shaded and see-through'),
    "comet_cap": ("PASS", 'knit ribs, ringed crown and cuff tops, comet pom-pom with a glowing core (was a flat white glow block)'),
    "circuit_crown": ("PASS", 'engraved band with glowing traces and crystal spires'),
    "regal_crown": ("PASS", 'gold, velvet cap, jewels on every side'),
    "sovereign_crown": ("PASS", 'platinum spires with a glint sweep and four gems'),
    "witch_hat": ("PASS", 'stepped felt cone with crooked tip, band and buckle'),
    "party_cone": ("PASS", 'spiral stripes now carry over the tier tops; round outlined tuft (were flat tops and a noise block)'),
    "angel_halo": ("PASS", 'twisted gold rope in crisp bands (was a flat glowing gradient); distinct from the orbit halo'),
    "orbit_halo": ("PASS", 'banded gold with a light racing round, spins'),
    "ember_horns": ("PASS", 'ridged horns curling up, coal-bright tips'),
    "cat_ears": ("PASS", 'fur strokes, lit and shaded rims, pink insides (backs were plain blocks)'),
    "toadstool_cap": ("PASS", 'spotted cap with gills under the brim'),
    "dusk_visor": ("PASS", 'glowing slit visor on carbon straps'),
    "outlaw_bandana": ("PASS", 'stitched bandana with knot and tails'),
    "kitsune_mask": ("PASS", 'markings unchanged; red lacquered inside and lacquer seams on the ear backs (were flat white)'),
    "neon_shades": ("PASS", 'gradient lenses in a black frame'),
    "lantern_pack": ("PASS", 'canvas pack, bedroll, glowing lantern'),
    "runeblade_sheath": ("PASS", 'rune blade with glowing runes, wrapped grip'),
    "rocket_jetpack": ("PASS", 'twin tanks, hazard stripes, thrust flames'),
    "ranger_quiver": ("PASS", 'leather quiver, fletched arrows, gold fittings'),
    "bastion_shield": ("PASS", 'painted quarters show plank seams and wear; back has straps, rivets and a grip (was plain planks)'),
    "ember_orbit": ("PASS", 'rune circle with orbiting embers'),
    "frost_sigil": ("PASS", 'snowflake sigil with crisp branches'),
    "verdant_bloom": ("PASS", 'leaf and flower ring'),
    "void_rift": ("PASS", 'glyph ring round a glowing core'),
    "spectrum_aura": ("PASS", 'quality fine; NOTE: rainbow by its own name/description (shipped v2.7) - owner rule conflict, flagged'),
    "ginger_cat": ("PASS", "Minecraft style (v3): ginger tabby standing on real cat proportions, striped back and tail, cream chest and paws, green 2-texel eyes, no outline"),
    "ember_fox": ("PASS", "Minecraft style: red fox body, white chest and cheeks, dark socks, black-tipped ears, long snout with a black nose, bushy white-tipped tail"),
    "jungle_parrot": ("PASS", "Minecraft style: upright scarlet parrot, side eyes in pale patches, hooked horn beak, folded yellow and blue wing planes, long blue tail"),
    "bubble_slime": ("PASS", "Minecraft style: see-through jelly cube, the dark core with its eyes looks about inside (no grin or cheeks)"),
    "soul_wisp": ("PASS", "Minecraft style: a soul-flame column (wide foot, face, flickering glowing tip), small eyes, drifting hands; no cheeks"),
    "baby_dragon": ("PASS", "Minecraft style: lizard body on four legs, long snout with nostrils, swept-back horn planes, scalloped membrane wings, spade tail"),
    "prism_sprite": ("PASS", "Minecraft style: crystal fairy on the pets' texel grid, faceted rainbow hues rolling (kept as the owner asked), glass wings; NOTE: shimmers through every colour by design (shipped v2.7) - owner rule conflict, flagged"),
    "stardust_trail": ("PASS", 'sparkle arc'),
    "ember_trail": ("PASS", 'embers rising off glowing footprints'),
    "petal_trail": ("PASS", 'drifting petals'),
    "shatterburst": ("PASS", 'crystal shards burst'),
    "firework_pop": ("PASS", 'multi-colour firework burst'),
    "soul_release": ("PASS", 'rising soul helix'),
    "victory_spiral": ("PASS", 'golden spiral of stars'),
    "confetti_cannon": ("PASS", 'confetti blast'),
    "starlight_fountain": ("PASS", 'starlight helix'),
    "wave": ("PASS", 'wave with sparkles off the hand (new particles in game and in the picture)'),
    "drum_solo": ("PASS", 'drumsticks in both hands (new prop) and rising notes'),
    "salute": ("PASS", 'salute with a glint at the brow (new particles)'),
    "slow_clap": ("PASS", 'clap sparkles between the hands (new particles)'),
    "bow": ("PASS", 'flourish tosses rose petals (new particles)'),
    "nightglass_blade": ("PASS", 'violet glass blade, vanilla size, glow core'),
    "emberfang_saber": ("PASS", 'molten blade with dark spine'),
    "frostbite_edge": ("PASS", 'ice blade, wrapped grip'),
    "honeycomb_cape": ("PASS", 'wax cells and drips; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "royal_ermine_cape": ("PASS", 'velvet, crown crest, ermine hem; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "pixel_camo_cape": ("PASS", 'camo now on ripstop fabric (was flat blocks); art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "code_rain_cape": ("PASS", 'streaming glyph columns; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "warp_speed_cape": ("PASS", 'stars streaming from a core; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "bass_drop_cape": ("PASS", 'LED equaliser with falling peaks; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "dusk_moth_wings": ("PASS", 'dusty wings with eye-spots, furry body'),
    "pro_headset": ("PASS", 'lighter graphite, quilted pads, foam mic with a live red ring (mic was a flat red block)'),
    "dapper_top_hat": ("PASS", 'felt with a lit edge, crimson band, gold buckle'),
    "raider_helm": ("PASS", 'horns now sweep out from the helm and curl up, ridged ivory (were stacked blocks)'),
    "personal_raincloud": ("PASS", 'lit puff tops and shaded undersides, rain and flashes'),
    "aviator_goggles": ("PASS", 'the strap is now a band, not a lid over the head'),
    "monocle_mustache": ("PASS", 'curled moustache, monocle on a chain'),
    "acoustic_guitar": ("PASS", 'wood body, sound hole, strings, strap'),
    "turtle_shell": ("PASS", 'domed scutes, plastron underside'),
    "reactor_pack": ("PASS", 'steel case, swirling plasma core'),
    "candy_cane_blade": ("PASS", 'peppermint stripes, gold guard'),
    "verdant_thorn": ("PASS", 'green glass blade wrapped in a vine'),
    "plasma_edge": ("PASS", 'blade edges now carry the pulse and a hot line (were flat 1 tx/u bars)'),
    "sea_turtle": ("PASS", "Minecraft style: plated shell rim with seams, raised centre plates, side eyes, cream plastron, paddling flipper planes"),
    "honey_bee": ("PASS", "Minecraft style: dark head with compound eyes, fuzzy thorax, striped abdomen, stinger, antennae, see-through wings"),
    "swamp_frog": ("PASS", "Minecraft style: squat moss-green frog, eye bumps with golden irises, pale throat, folded hind legs (no smile or cheeks)"),
    "wandering_ghost": ("PASS", "Minecraft style: sheet ghost, glowing eye holes, ragged hem rippling round it, flickers in and out (uneven steps are the flicker)"),
    "bubble_trail": ("PASS", 'soap bubbles'),
    "heartbreaker": ("PASS", 'heart burst'),
    "gold_rush": ("PASS", 'coin fountain'),
    "facepalm": ("PASS", 'sweat drops (new particles)'),
    "shrug": ("PASS", 'puffs beside the head (new particles)'),
    "victory_flex": ("PASS", 'gold sparkles rising (new particles)'),
    "torii_sunset_cape": ("PASS", 'gate, sunset and rippling reflection; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "graffiti_crown_cape": ("PASS", 'sprayed crown with drips; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "imperial_dragon_cape": ("PASS", 'coiling golden dragon; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "ace_of_spades_cape": ("PASS", 'card with border and pips; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "snow_peak_cape": ("PASS", 'peak mirrored in a lake; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "winged_crest_cape": ("PASS", 'golden eagle crest; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "samurai_cloak": ("PASS", 'gold crest and wave hem; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "glitch_koi_cape": ("PASS", 'neon koi with glitch tears; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "void_eye_cape": ("PASS", 'eye that looks about and blinks; art, padded quilted lining with bound edge and label, edges textured 1:1'),
    "origami_sakura_wings": ("PASS", 'folded paper facets printed with blossom'),
    "shadow_samurai_helm": ("PASS", 'lacquered helm, flared guard, crest'),
    "storm_ring_aura": ("PASS", 'crackling rings re-striking each frame'),
    "wolf_pup": ("PASS", "Minecraft style: wolf proportions, dark saddle and brow, pale chest and legs, snout with a black nose, red collar with a gold tag, twitching ears, wagging tail"),
    "panda_cub": ("PASS", "Minecraft style: stocky panda, black shoulder band and legs, tilted eye patches each with a light eye texel, black ears and nose"),
    "cave_bat": ("PASS", "Minecraft style: furry brown body with a pale chest, small head with pointed ears, scalloped membrane wings with finger bones (no fangs or cheeks)"),
    "axolotl_pal": ("PASS", "Minecraft style: flat pink axolotl with a wide head, beady eyes, fanning gill fronds, splayed legs, tail fin"),
    "breakdance": ("PASS", 'handstand spin with dust'),
    "levitate": ("PASS", 'runes now circle the waist, framed (was off centre)'),
    "heart_toss": ("PASS", 'hearts tossed'),
    "pet_the_cat": ("PASS", 'kitten prop'),
    "fire_ring": ("PASS", 'fire ring'),
    "disco": ("PASS", 'notes'),
    "challenge": ("PASS", 'beckoning stance'),
    "laugh": ("PASS", 'puffs'),
    "superhero_landing": ("PASS", 'dust ring'),
}

# items that are meant to be flat white/grey (tinted by the wearer's colour): the grey check doesn't apply to tint bones
FACE_IDS = ("top", "bottom", "west", "north", "east", "south")
JSON_FACE = {"up": "top", "down": "bottom", "west": "west", "north": "north", "east": "east", "south": "south"}


def face_rects(bc, cube, scale):
    """[(face, (x0, y0, x1, y1) texels, (geom w, geom h) units, (uv w, uv h) units)] for one cube of model.json."""
    w, h, d = cube["size"]
    out = []
    if "faces" in cube:
        for jf, f in cube["faces"].items():
            face = JSON_FACE[jf]
            gw, gh = bc.FACE_DIMS[face](w, h, d)
            u1, v1, u2, v2 = f["uv"]
            rw, rh = abs(u2 - u1), abs(v2 - v1)
            if int(f.get("rotation", 0)) // 90 % 2:
                rw, rh = rh, rw
            x0, x1 = sorted((u1 * scale, u2 * scale))
            y0, y1 = sorted((v1 * scale, v2 * scale))
            out.append((face, tuple(int(round(v)) for v in (x0, y0, x1, y1)), (gw, gh), (rw, rh)))
        return out
    u, v = cube.get("uv") or (0, 0)
    for face, rect in bc.FACE_RECTS.items():
        ox, oy, fw, fh = rect(w, h, d)
        x0, y0 = (u + ox) * scale, (v + oy) * scale
        out.append((face, (int(x0), int(y0), int(x0 + fw * scale), int(y0 + fh * scale)), (fw, fh), (fw, fh)))
    return out


def block_size(t, scale):
    """The largest k in (1, 2, 4, 8) (k <= scale) such that the face is constant on aligned k x k blocks (97%)."""
    best = 1
    h, w = t.shape[:2]
    for k in (2, 4, 8):
        if k > scale or h < k or w < k or h % k or w % k:
            break
        b = t[:h // k * k, :w // k * k].reshape(h // k, k, w // k, k, -1)
        same = (np.abs(b - b[:, :1, :, :1]).max((1, 3, 4)) < 1 / 255).mean()
        if same >= 0.97:
            best = k
        else:
            break
    return best


def face_stats(bc, it, model, tex):
    """Per-face measurements on frame 0 of the texture. Returns (rows, problems, weak)."""
    scale = model["textureScale"]
    rows, fails, weak = [], [], []
    eff = []
    seen = {}
    for b in model["bones"]:
        tint = b.get("tint", False)
        for ci, c in enumerate(b["cubes"]):
            w, h, d = c["size"]
            solid = min(w, h, d) > 0
            for face, (x0, y0, x1, y1), (gw, gh), (rw, rh) in face_rects(bc, c, scale):
                if gw == 0 or gh == 0:
                    continue
                tag = f"{b['name']}#{ci}.{face}"
                area = gw * gh
                # stretch: the UV rect against the face's size (texels per unit along each axis)
                sx, sy = rw / gw, rh / gh
                if area >= 1 and (max(sx, sy) > 1.3 or min(sx, sy) < 0.77):
                    fails.append(f"{tag}: stretched sampling ({gw:g}x{gh:g} face from a {rw:g}x{rh:g} UV rect)")
                t = tex[max(0, y0):y1, max(0, x0):x1]
                if t.size == 0:
                    continue
                op = t[..., 3] > 0.5
                key = (x0, y0, x1, y1)
                if key in seen:
                    continue
                seen[key] = tag
                n = int(op.sum())
                row = dict(tag=tag, area=area, opaque=n / max(1, op.size))
                rows.append(row)
                if n == 0:
                    if solid and area >= 4 and not b.get("emissive") and f"{it['id']}:{tag}" not in OPEN_FACES:
                        weak.append(f"{tag}: empty face on a solid cube ({gw:g}x{gh:g})")
                    continue
                px = t[op][:, :3]
                q = np.round(px * 255).astype(int)
                ncol = len(np.unique(q[:, 0] * 65536 + q[:, 1] * 256 + q[:, 2]))
                row["colours"] = ncol
                L = lum(px)
                sat = px.max(1) - px.min(1)
                if ncol == 1 and n >= 2 * scale * scale:
                    fails.append(f"{tag}: one flat colour ({gw:g}x{gh:g})")
                elif ncol <= 2 and float(L.std()) < 0.01 and n >= 4 * scale * scale:
                    weak.append(f"{tag}: almost flat ({ncol} colours, {gw:g}x{gh:g})")
                if not tint and not it["colourable"] and n >= scale * scale:
                    grey = (sat < 0.045) & (L > 0.3) & (L < 0.72)
                    if grey.mean() > 0.92 and ncol <= 4:
                        fails.append(f"{tag}: default grey, unpainted ({gw:g}x{gh:g})")
                # edge: the outer ring of texels against the ring inside it
                k = block_size(t, scale)
                if min(gw, gh) >= 2 and t.shape[0] >= 2 * k + 2 and t.shape[1] >= 2 * k + 2 and op.mean() > 0.9:
                    tl = lum(t[..., :3])          # (rings one art pixel apart: k texels)
                    outer = np.concatenate([tl[0, k:-k], tl[-1, k:-k], tl[k:-k, 0], tl[k:-k, -1]])
                    inner = np.concatenate([tl[k, k:-k], tl[-1 - k, k:-k], tl[k:-k, k], tl[k:-k, -1 - k]])
                    ed = float((np.abs(outer - inner) > 0.025).mean())
                    row["edge"] = ed
                    # a plain face (a few colours) needs its edge drawn; a detailed one reads by its art and the
                    # per-face light (the reference pets have no drawn edges)
                    if ed < 0.25 and area >= 4 and ncol <= 3:
                        weak.append(f"{tag}: plain face with no drawn edge ({ncol} colours, {ed:.0%} of its border)")
                # tiling: the face equals itself moved by a whole block, both ways (a stamped pattern)
                if gw >= 4 and gh >= 4:
                    th_, tw_ = t.shape[:2]
                    tiled = False
                    for p in range(scale, tw_ // 2 + 1, scale):
                        for q_ in range(scale, th_ // 2 + 1, scale):
                            a1 = t[q_:, p:]
                            a0 = t[:th_ - q_, :tw_ - p]
                            if (np.abs(a1 - a0).max(-1) < 1 / 255).mean() > 0.97 and ncol > 1:
                                tiled = True
                                break
                        if tiled:
                            break
                    cols_same = (np.abs(t - t[:, :1]).max((0, 2)) < 1 / 255).mean() > 0.97
                    rows_same = (np.abs(t - t[:1]).max((1, 2)) < 1 / 255).mean() > 0.97
                    if tiled:
                        weak.append(f"{tag}: the same block repeated across the face")
                    elif (cols_same or rows_same) and ncol > 1 and n >= 16 * scale * scale:
                        weak.append(f"{tag}: every {'column' if cols_same else 'row'} the same (a stretched gradient)")
                row["eff"] = scale / k
                if area >= 2:
                    eff.append((scale / k, area, tag))
    if eff:
        top = max(e for e, _, _ in eff)
        if it["slot"] == "PET":
            top = min(top, 4)          # pets: faces drawn on the pet engine's art grid (4 art pixels per unit) are the norm
        low = [(e, a, tag) for e, a, tag in eff if e * 4 <= top]
        if low:
            share = sum(a for _, a, _ in low) / sum(a for _, a, _ in eff)
            (fails if share > 0.15 else weak).append(
                f"texel density varies: {len(low)} faces at {min(e for e, _, _ in low):g} tx/u vs {top:g} "
                f"({share:.0%} of the surface), e.g. {low[0][2]}")
    return rows, fails, weak


MC_DENSITY = (1.0, 2.0)          # texels per model unit for a Minecraft-style pet (16-32 per block)
MC_HEAD = (0.42, 0.5)            # the head's share of the solid volume: weak above, fail above
MC_OUTLINE = (0.12, 0.25)        # the share of a pet's faces with a dark rim all round: weak above, fail above
# whole-body pets: the creature has no head apart from its body (none at present; the slime's head is its core)
MC_HEADLESS = ()


def mc_style(bc, model, tex, item_id=""):
    """The Minecraft-style check for a pet: (fails, weak, info) on texel density, head size and outline darkness."""
    fails, weak = [], []
    scale = model["textureScale"]
    effs, areas, outlined, judged = [], [], 0, 0
    seen = set()
    vol_head = vol_all = 0.0
    for b in model["bones"]:
        is_head = (b.get("anim") or {}).get("type") == "pet_head"
        for c in b["cubes"]:
            w, h, d = c["size"]
            if min(w, h, d) > 0:
                v = w * h * d
                vol_all += v
                vol_head += v if is_head else 0
            for face, (x0, y0, x1, y1), (gw, gh), _ in face_rects(bc, c, scale):
                if gw == 0 or gh == 0 or (x0, y0, x1, y1) in seen:
                    continue
                seen.add((x0, y0, x1, y1))
                t = tex[max(0, y0):y1, max(0, x0):x1]
                if t.size == 0 or (t[..., 3] > 0.5).mean() < 0.9:
                    continue
                k = block_size(t, scale)
                effs.append(scale / k)
                areas.append(gw * gh)
                # outline: a rim all round, every side of the art grid darker than the texels just inside it (a pale
                # patch in the middle of a face touches an edge somewhere; a drawn outline doesn't)
                g = t[::k, ::k]
                if min(g.shape[:2]) >= 3 and min(w, h, d) > 0:
                    L = lum(g[..., :3])
                    sides = ((L[0, 1:-1], L[1, 1:-1]), (L[-1, 1:-1], L[-2, 1:-1]),
                             (L[1:-1, 0], L[1:-1, 1]), (L[1:-1, -1], L[1:-1, -2]))
                    judged += 1
                    if min(float((e < i * 0.82).mean()) for e, i in sides) >= 0.6:
                        outlined += 1
    info = {}
    if effs:
        order = np.argsort(effs)
        cum = np.cumsum(np.asarray(areas, float)[order])
        dens = float(np.asarray(effs)[order][np.searchsorted(cum, cum[-1] / 2)])
        info["density"] = dens
        if dens > MC_DENSITY[1] * 1.25 or dens < MC_DENSITY[0] * 0.8:
            fails.append(f"Minecraft style: {dens:g} texels per unit ({dens * 16:g} per block; a pet wants "
                         f"{MC_DENSITY[0] * 16:g}-{MC_DENSITY[1] * 16:g})")
        elif dens > MC_DENSITY[1] or dens < MC_DENSITY[0]:
            weak.append(f"Minecraft style: {dens:g} texels per unit ({dens * 16:g} per block)")
    if vol_all and item_id not in MC_HEADLESS:
        r = vol_head / vol_all
        info["head"] = r
        if r > MC_HEAD[1]:
            fails.append(f"Minecraft style: oversized head ({r:.0%} of the pet's volume)")
        elif r > MC_HEAD[0]:
            weak.append(f"Minecraft style: big head ({r:.0%} of the pet's volume)")
    if judged:
        o = outlined / judged
        info["outline"] = o
        if o > MC_OUTLINE[1]:
            fails.append(f"Minecraft style: drawn outlines ({outlined} of {judged} faces have a dark rim all round)")
        elif o > MC_OUTLINE[0]:
            weak.append(f"Minecraft style: some drawn outlines ({outlined} of {judged} faces)")
    return fails, weak, info


def alone_scene_view(bc, it, d, yaw, pitch, size=192):
    """The item alone (the emote figure with its prop) from any angle, framed to fit."""
    if it["emote"]:
        v = dict(it["view"], yaw=yaw, pitch=pitch, frame=None, margin=1.08)
        prop = None
        if (d / "model.json").exists():
            model = json.loads((d / "model.json").read_text())
            tw, th = model["texture"]
            sc = model["textureScale"]
            prop = (load_tex(d / "texture.png", tw, th, sc), load_tex(d / "emissive.png", tw, th, sc), tw, th)
            it = dict(it, bones=bones_from_json(bc, model))
        if v.get("gm"):
            return bc.gm_thumb_emote(dict(it, view=v), size, prop=prop)
        pose = v.get("pose") or bc.emote_key_pose(it["emote"])
        scene = bc.Scene()
        bc.add_mannequin(scene, pose)
        return bc.render_scene(scene, yaw, pitch, size=size, margin=1.08)
    mpath = d / "model.json"
    if not mpath.exists():
        return None
    model = json.loads(mpath.read_text())
    tw, th = model["texture"]
    sc = model["textureScale"]
    tex = load_tex(d / "texture.png", tw, th, sc)
    em = load_tex(d / "emissive.png", tw, th, sc)
    bones = bones_from_json(bc, model)
    view = dict(it["view"], yaw=yaw, pitch=pitch, frame=None, margin=1.08)
    view.pop("glow", None)
    with NoPlayer(bc):
        return bc.gm_thumb_model_shipped(dict(it, bones=bones, view=view), tex, em, tw, th, size=size)


def view_stats(im, tinted):
    a = np.asarray(im.convert("RGBA"), np.float32) / 255
    m = a[..., 3] > 0.5
    if m.sum() < 30:
        return None
    rgb = a[..., :3]
    L = lum(rgb)
    # flat: pixels whose 5 x 5 neighbourhood (all inside the item) has no colour variation at all
    pad = 2
    Lp = np.pad(L, pad, mode="edge")
    mp = np.pad(m, pad)
    win_max = np.full_like(L, -1)
    win_min = np.full_like(L, 2)
    inside = np.ones_like(m)
    for dy in range(5):
        for dx in range(5):
            s = Lp[dy:dy + L.shape[0], dx:dx + L.shape[1]]
            win_max = np.maximum(win_max, s)
            win_min = np.minimum(win_min, s)
            inside &= mp[dy:dy + L.shape[0], dx:dx + L.shape[1]]
    flat = m & inside & ((win_max - win_min) < 0.006)
    sat = rgb.max(-1) - rgb.min(-1)
    grey = m & flat & (sat < 0.05) & (L > 0.25) & (L < 0.75)      # unpainted grey: grey AND flat (steel is not)
    card = np.array(CARD, np.float32) / 255
    comp = rgb * a[..., 3:4] + card * (1 - a[..., 3:4])
    return dict(flat=float(flat.sum() / m.sum()), grey=0.0 if tinted else float(grey.sum() / m.sum()),
                lum=float(lum(comp)[m].mean()))


def strip_frames(path, frames):
    if not path.exists():
        return None
    a = np.asarray(Image.open(path).convert("RGBA"), np.float32) / 255
    h = a.shape[0] // frames
    return [a[k * h:(k + 1) * h] for k in range(frames)]


def loop_stats(frames):
    """(motion: mean per-frame change, jump: biggest change / median, seam: last->first / median)."""
    def diff(x, y):
        m = (x[..., 3] > 0.3) | (y[..., 3] > 0.3)
        if not m.any():
            return 0.0
        return float(np.abs(x - y)[m].mean())
    ds = [diff(frames[k], frames[(k + 1) % len(frames)]) for k in range(len(frames))]
    med = float(np.median(ds)) + 1e-6
    return float(np.mean(ds)), max(ds[:-1]) / med, ds[-1] / med


def strict_checks(bc, it, d, thumb, alone, views, catalog_item):
    """Strict tier: (fails, weak, info)."""
    fails, weak, info = [], [], {}
    slot = it["slot"]
    mpath = d / "model.json"
    model = json.loads(mpath.read_text()) if mpath.exists() else None
    if model is not None:
        tw, th = model["texture"]
        sc = model["textureScale"]
        tex = load_tex(d / "texture.png", tw, th, sc)
        rows, f, w = face_stats(bc, it, model, tex)
        info["faces"] = len(rows)
        fails += f
        weak += w
        if slot == "PET":
            f, w, mc = mc_style(bc, model, tex, it["id"])
            info["mc"] = mc
            fails += f
            weak += w
    # views
    tinted = it["colourable"]
    for name, vs in views.items():
        if vs is None:
            continue
        info[f"view:{name}"] = vs
        fl_weak, fl_fail = (0.45, 0.6) if slot == "PET" else (0.40, 0.55)
        if vs["flat"] > fl_fail:
            fails.append(f"{name} view: {vs['flat']:.0%} flat colour")
        elif vs["flat"] > fl_weak:
            weak.append(f"{name} view: {vs['flat']:.0%} flat colour")
        if slot == "PET":
            vs = dict(vs, grey=0.0)          # white and grey fur, not unpainted grey (the pet painter has no default)
        if vs["grey"] > 0.3:
            fails.append(f"{name} view: {vs['grey']:.0%} flat plain grey")
        elif vs["grey"] > 0.15:
            weak.append(f"{name} view: {vs['grey']:.0%} flat plain grey")
        # a cape's lining (its front views) is dark on purpose where the art is (a night sky, black cloth): it is
        # judged on its quilting and binding by eye
        if vs["lum"] < 0.09 and not (slot == "CAPE" and "front" in name):
            weak.append(f"{name} view: dark (mean {vs['lum']:.2f})")
    # read at 64 px
    if thumb is not None and slot not in ("TRAIL", "KILL_EFFECT", "WIN_EFFECT"):
        # the thumbnail as the shop and the Wardrobe grid show it at its smallest
        small = thumb.convert("RGBa").resize((64, 64), Image.BOX).convert("RGBA")
        m64 = measure(small, slot)
        info["read64"] = {k: m64.get(k) for k in ("outline", "fill", "frags", "lum")}
        if not m64.get("empty"):
            if m64["outline"] < 0.05:
                weak.append(f"at 64 px the edge barely reads ({m64['outline']:.3f})")
            if m64["frags"] > 8 and slot in ("HAT", "MASK", "BACK", "ITEM_SKIN", "PET", "CAPE"):
                weak.append(f"at 64 px it crumbles into {m64['frags']} pieces")
    # glow
    desc = it["desc"]
    has_em = (d / "emissive.png").exists() or (model is not None and any(b.get("emissive") for b in model["bones"]))
    if model is not None and GLOW_WORDS.search(desc) and not has_em:
        fails.append(f"described as glowing ('{GLOW_WORDS.search(desc).group(0)}') but has no emissive layer")
    if has_em and (d / "emissive.png").exists():
        e = np.asarray(Image.open(d / "emissive.png").convert("RGBA"), np.float32)[..., 3]
        info["emissive"] = float((e > 0).mean())
    # loops
    anim = it["animation"]
    if anim:
        n = anim["frames"]
        fr = strip_frames(d / "texture.png", n)
        if fr:
            motion, jump, seam = loop_stats(fr)
            info["loop"] = (motion, jump, seam)
            if motion < 0.004:
                fails.append(f"animated texture barely moves ({motion:.4f} per frame)")
            if seam > 3.0 and it["id"] not in FLICKER:
                weak.append(f"loop seam: last->first frame changes {seam:.1f}x a usual step")
            if jump > 4.0 and it["id"] not in FLICKER:
                weak.append(f"uneven animation: one step {jump:.1f}x a usual step")
        tf = strip_frames(d / "thumb.png", n) if (d / "thumb.png").exists() and Image.open(d / "thumb.png").height > Image.open(d / "thumb.png").width else None
        if tf:
            motion, jump, seam = loop_stats(tf)
            info["thumb_loop"] = (motion, jump, seam)
            if motion < 0.003:
                weak.append(f"the thumbnail strip barely moves ({motion:.4f})")
    elif it["rarity"] == "SPECIAL" and model is not None:
        if not any((b.get("anim") or {}).get("type") for b in model["bones"]) and not it["fx"]:
            weak.append("special without any animation")
    # thumbnail framing
    if thumb is not None:
        # the item alone from the thumbnail camera (the player in a cape's thumbnail is cropped on purpose)
        # centred: the whole picture (player included); cut off: the item alone, from the same camera
        at = np.asarray(thumb.convert("RGBA"), np.float32)[..., 3] / 255
        a = np.asarray((alone or thumb).convert("RGBA"), np.float32) / 255
        al = a[..., 3]
        S = al.shape[0]
        solid = al > 0.6
        if solid.any() and (at > 0.6).any():
            ys, xs = np.nonzero(at > 0.6)
            St = at.shape[0]
            cx, cy = (xs.min() + xs.max()) / 2 / St - 0.5, (ys.min() + ys.max()) / 2 / St - 0.5
            info["centre"] = (float(cx), float(cy))
            if max(abs(cx), abs(cy)) > 0.09 and slot not in ("TRAIL", "KILL_EFFECT", "WIN_EFFECT"):
                weak.append(f"thumbnail off centre ({cx:+.0%}, {cy:+.0%})")
            border = np.concatenate([solid[0], solid[-1], solid[:, 0], solid[:, -1]])
            if border.mean() > 0.04:
                weak.append(f"thumbnail cut off at the frame ({border.mean():.0%} of the border)")
    return fails, weak, info


def view_sheet(path, label, ims, cell=192):
    W = Image.new("RGB", (cell * len(ims), cell + 18), (14, 14, 18))
    dd = ImageDraw.Draw(W)
    for k, (name, im) in enumerate(ims):
        if im is not None:
            W.paste(on_card(im.resize((cell, cell), Image.LANCZOS)), (k * cell, 18))
        dd.text((k * cell + 4, 3), f"{label} - {name}" if k == 0 else name, fill=(230, 230, 236))
    W.save(path)


# ---------------------------------------------------------------------------------------------- sheets

VCOL = {"PASS": (90, 200, 120), "WEAK": (240, 190, 70), "FAIL": (240, 80, 80)}


def sheet(path, cells, cols=8, cell=200):
    rows = (len(cells) + cols - 1) // cols
    W = Image.new("RGB", (cols * cell, max(1, rows) * (cell + 30)), (14, 14, 18))
    d = ImageDraw.Draw(W)
    for i, (label, verdict, im) in enumerate(cells):
        x, y = (i % cols) * cell, (i // cols) * (cell + 30)
        W.paste(on_card(im.resize((cell - 8, cell - 8), Image.LANCZOS)), (x + 4, y + 4))
        d.rectangle((x + 4, y + cell - 2, x + cell - 4, y + cell + 1), fill=VCOL.get(verdict, (120, 120, 120)))
        d.text((x + 6, y + cell + 6), f"{verdict} {label}"[:34], fill=(230, 230, 236))
    W.save(path)


def main():
    args = sys.argv[1:]

    def opt(name, default=None):
        return args[args.index(name) + 1] if name in args else default
    out = pathlib.Path(opt("--out", "/tmp/claude-0/-home-user-Claude/54a77d6d-2e05-5e76-8bfa-32b97c86ec28/scratchpad/qa"))
    built = pathlib.Path(opt("--dir", str(ROOT / "site-src/cosmetics")))
    only = set(opt("--only").split(",")) if opt("--only") else None
    compare = opt("--compare")
    (out / "items").mkdir(parents=True, exist_ok=True)
    (out / "views").mkdir(parents=True, exist_ok=True)
    no_strict = "--no-strict" in args
    bc = load_generator()
    secs = sections()
    catalog = json.loads((built / "catalog.json").read_text()) if (built / "catalog.json").exists() else {"items": []}
    in_catalog = {c["id"] for c in catalog["items"]}
    results = []
    feats = {}
    for it in bc.ITEMS:
        iid = it["id"]
        if only and iid not in only:
            continue
        d = built / iid
        texinfo = {}
        thumb = None
        if (d / "thumb.png").exists():
            raw = Image.open(d / "thumb.png")
            texinfo["thumb"] = raw.size
            thumb = first_frame(raw)
        m, wing, clear = {}, None, None
        alone = None
        poses = []
        if thumb is not None:
            if it["slot"] in ("TRAIL", "KILL_EFFECT", "WIN_EFFECT", "EMOTE"):     # the picture is the item
                alone = thumb
            else:
                alone = render_alone(bc, it, d) or thumb
            m = measure(alone, it["slot"])
            if (d / "model.json").exists():
                model = json.loads((d / "model.json").read_text())
                texinfo["scale"] = model["textureScale"]
                t0 = load_tex(d / "texture.png", *model["texture"], model["textureScale"])
                if t0 is not None:
                    px = np.round(t0[t0[..., 3] > 0][:, :3] * 255).astype(int)
                    texinfo["tex_colours"] = int(len(np.unique(px[:, 0] * 65536 + px[:, 1] * 256 + px[:, 2])))
                # texels per thumbnail pixel: model units across the frame / pixels, times the density
                fr = it["view"].get("frame")
                span = (max(fr[1][0] - fr[0][0], fr[1][1] - fr[0][1]) * it["view"].get("margin", 1.1)) if fr else None
                if span is None:
                    span = {"HAT": 17, "MASK": 15, "BACK": 34, "CAPE": 34, "PET": 16, "ITEM_SKIN": 20}.get(it["slot"], 30)
                texinfo["tpp"] = model["textureScale"] * span / thumb.width
                if it["slot"] == "WINGS":
                    wing = wing_shape(alone)
                    tex = load_tex(d / "texture.png", *model["texture"], model["textureScale"])
                    clear = wing_clearance(bc, model, None if tex is None else tex[..., 3])
                    for extra in (-40, 20):          # folded further back, and opened out
                        im = render_alone(bc, it, d, size=192, wing_extra=extra)
                        if im is not None:
                            poses.append(im)
            feats[iid] = (it["slot"],) + feature(alone)
        fails, weak = score(it, d, m, wing, clear, texinfo)
        # strict tier: every side of the item, every face of its texture
        vims, vstats = [], {}
        if thumb is not None and not no_strict:
            if it["slot"] in ("TRAIL", "KILL_EFFECT", "WIN_EFFECT") or (it["slot"] == "AURA" and not (d / "model.json").exists()):
                vims = [("thumbnail", thumb)]
            else:
                vims = [("thumbnail", thumb)]
                for name, yaw, pitch in VIEWS:
                    try:
                        im = alone_scene_view(bc, it, d, yaw, pitch)
                    except Exception as ex:          # a view that can't be drawn is a finding, not a crash
                        im = None
                        print(f"  view {name} of {iid}: {ex}", file=sys.stderr)
                    vims.append((name, im))
                    if im is not None and not it["emote"]:
                        # pets: judged at the size of their texels (a 192 px view of a pet is about 8 px a texel)
                        vim = im.convert("RGBa").resize((im.width // 4, im.height // 4), Image.BOX).convert("RGBA") \
                            if it["slot"] == "PET" else im
                        vstats[name] = view_stats(vim, it["colourable"])
            view_sheet(out / "views" / f"{iid}.png", iid, vims)
            sfails, sweak, sinfo = strict_checks(bc, it, d, thumb, alone, vstats, None)
        else:
            sfails, sweak, sinfo = [], [], {}
        sauto = "FAIL" if sfails else "WEAK" if sweak else "PASS"
        seye = EYE_STRICT.get(iid)
        sverdict = sauto
        if seye:
            sverdict = max(sauto, seye[0], key=["PASS", "WEAK", "FAIL"].index)
        elif not no_strict:
            sverdict = max(sauto, "WEAK", key=["PASS", "WEAK", "FAIL"].index)     # not yet judged by eye
        auto = "FAIL" if fails else "WEAK" if weak else "PASS"
        eye = EYE.get(iid)
        verdict = auto
        if eye:
            order = ["PASS", "WEAK", "FAIL"]
            verdict = max(auto, eye[0], key=order.index)
        results.append(dict(id=iid, name=it["name"], slot=it["slot"], rarity=it["rarity"], section=secs.get(iid, "?"),
                            listed=iid in in_catalog, verdict=verdict, auto=auto, fails=fails, weak=weak, eye=eye,
                            m=m, wing=wing, clear=clear, tex=texinfo, strict=sverdict, sauto=sauto, sfails=sfails,
                            sweak=sweak, seye=seye, sinfo=sinfo))
        # per-item picture: the shipped thumbnail | the item alone | wing poses
        if thumb is not None:
            ims = [thumb.resize((256, 256), Image.LANCZOS), (alone or thumb).resize((256, 256), Image.LANCZOS)]
            ims += [p.resize((256, 256), Image.LANCZOS) for p in poses]
            P = Image.new("RGB", (256 * len(ims), 256), CARD)
            for k, im in enumerate(ims):
                P.paste(on_card(im), (256 * k, 0))
            P.save(out / "items" / f"{iid}.png")
        print(f"{verdict:4} strict {sverdict:4} {iid}" + (f"  {'; '.join((sfails + sweak)[:3])}" if sfails or sweak else ""),
              file=sys.stderr)
    # look-alikes
    sims = similarity_pairs(feats)
    for a, b, s in sims:
        for r in results:
            if r["id"] in (a, b):
                other = b if r["id"] == a else a
                r["weak"].append(f"looks like {other} ({s:.0%} alike)")
                if r["verdict"] == "PASS":
                    r["verdict"] = "WEAK"
    # sheets
    def cells_for(rs):
        cells = []
        for r in rs:
            p = built / r["id"] / "thumb.png"
            if p.exists():
                cells.append((r["id"], r["verdict"], first_frame(Image.open(p))))
        return cells
    sheet(out / "sheet-all.png", cells_for(results), cols=10, cell=160)
    for slot in sorted({r["slot"] for r in results}):
        rs = [r for r in results if r["slot"] == slot]
        refs = [r for r in results if r["id"] in REFERENCE and r["slot"] != slot][:2]
        sheet(out / f"sheet-{slot.lower()}.png", cells_for(refs + rs), cols=6, cell=220)
    if compare:
        old = pathlib.Path(compare)
        changed = []
        for r in results:
            a, b = old / r["id"] / "thumb.png", built / r["id"] / "thumb.png"
            if a.exists() and b.exists() and a.read_bytes() != b.read_bytes():
                changed.append(r)
        if changed:
            cell = 220
            W = Image.new("RGB", (cell * 4, ((len(changed) + 1) // 2) * (cell + 24)), (14, 14, 18))
            dd = ImageDraw.Draw(W)
            for i, r in enumerate(changed):
                x, y = (i % 2) * cell * 2, (i // 2) * (cell + 24)
                for k, p in enumerate((old / r["id"] / "thumb.png", built / r["id"] / "thumb.png")):
                    W.paste(on_card(first_frame(Image.open(p)).resize((cell - 6, cell - 6), Image.LANCZOS)),
                            (x + k * cell + 3, y + 3))
                dd.text((x + 6, y + cell + 4), f"{r['id']}: before | after ({r['verdict']})", fill=(230, 230, 236))
            W.save(out / "before-after.png")
    # report
    lines = ["# Evade cosmetics quality test", "",
             f"Built files: `{built}`. Generator: `scripts/build-cosmetics.py`. Method: see the docstring of "
             "`scripts/cosmetics-qa.py` (automatic checks + the by-eye review against the reference items "
             + ", ".join(REFERENCE) + ").", ""]
    for sec in ("shipped", "mockup", "gemini"):
        rs = [r for r in results if r["section"] == sec]
        if not rs:
            continue
        counts = {v: sum(1 for r in rs if r["verdict"] == v) for v in ("PASS", "WEAK", "FAIL")}
        lines += [f"## {sec} ({len(rs)} items: {counts['PASS']} pass, {counts['WEAK']} weak, {counts['FAIL']} fail)", "",
                  "| item | slot | verdict | reasons | fill | busy | lum | tex colours | solidity | extra |",
                  "|---|---|---|---|---|---|---|---|---|---|"]
        for r in sorted(rs, key=lambda r: (["FAIL", "WEAK", "PASS"].index(r["verdict"]), r["slot"], r["id"])):
            m = r["m"]
            reasons = r["fails"] + r["weak"] + ([f"eye: {r['eye'][1]}"] if r["eye"] and r["eye"][0] != "PASS" else [])
            extra = ""
            if r["wing"]:
                extra = "arch " + "/".join(f"{a:.2f}" for a, _ in r["wing"]) + ", strips " + "/".join(f"{s:.1f}" for _, s in r["wing"])
                extra += f", clip {r['clear']:.2f}"
                extra += f", grain {m.get('aniso', 0):.1f}"
            if r["tex"].get("scale"):
                extra += f" {r['tex']['scale']} tx/u"
            f = lambda k, fmt: format(m[k], fmt) if k in m else "-"
            lines.append(f"| {r['id']} | {r['slot']} | **{r['verdict']}** | {'; '.join(reasons) or 'ok'} | {f('fill', '.0%')} | "
                         f"{f('busy', '.0%')} | {f('lum', '.2f')} | {r['tex'].get('tex_colours', '-')} | {f('solidity', '.2f')} | {extra} |")
        lines.append("")
    if not no_strict:
        counts = {v: sum(1 for r in results if r["strict"] == v) for v in ("PASS", "WEAK", "FAIL")}
        lines += [f"## Strict tier ({len(results)} items: {counts['PASS']} pass, {counts['WEAK']} weak, {counts['FAIL']} fail)",
                  "", "Criteria: see the strict-tier block in `scripts/cosmetics-qa.py`. Per-view sheets: `views/<id>.png` "
                  "(thumbnail, front, back, side, top, 3/4 front, 3/4 back).", "",
                  "| item | slot | strict | auto findings | by eye |", "|---|---|---|---|---|"]
        for r in sorted(results, key=lambda r: (["FAIL", "WEAK", "PASS"].index(r["strict"]), r["slot"], r["id"])):
            fnd = r["sfails"] + r["sweak"]
            more = f" (+{len(fnd) - 4} more)" if len(fnd) > 4 else ""
            eye = f"{r['seye'][0]}: {r['seye'][1]}" if r["seye"] else "not judged"
            lines.append(f"| {r['id']} | {r['slot']} | **{r['strict']}** | {'; '.join(fnd[:4]) + more or 'ok'} | {eye} |")
        lines.append("")
    if sims:
        lines += ["## Look-alikes (same slot)", ""] + [f"- {a} ~ {b}: {s:.0%}" for a, b, s in sims] + [""]
    (out / "report.md").write_text("\n".join(lines))
    js = [{k: v for k, v in r.items() if k not in ("eye", "seye")} | {"eye": list(r["eye"]) if r["eye"] else None,
          "seye": list(r["seye"]) if r["seye"] else None} for r in results]
    (out / "report.json").write_text(json.dumps(js, indent=1, default=float))
    if not no_strict:
        # contact sheets of every view, a few items per sheet, for the by-eye review
        vfiles = [out / "views" / f"{r['id']}.png" for r in results if (out / "views" / f"{r['id']}.png").exists()]
        per = 5
        for g in range(0, len(vfiles), per):
            ims = [Image.open(f) for f in vfiles[g:g + per]]
            W = Image.new("RGB", (max(i.width for i in ims), sum(i.height for i in ims)), (14, 14, 18))
            y = 0
            for i in ims:
                W.paste(i, (0, y))
                y += i.height
            W.save(out / f"views-{g // per:02d}.png")
        sbad = [r for r in results if r["strict"] != "PASS"]
        print(f"strict: {len(results) - len(sbad)} pass, {len(sbad)} weak/fail", file=sys.stderr)
    bad = [r for r in results if r["verdict"] != "PASS"]
    print(f"{len(results)} items: {len(results) - len(bad)} pass, {len(bad)} weak/fail -> {out / 'report.md'}", file=sys.stderr)


if __name__ == "__main__":
    main()
