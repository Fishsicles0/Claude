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
REFERENCE = ("hearthfire_cape", "riptide_cape", "ember_mantle", "mochi_cat", "ember_fox", "hoot_owl", "mini_wyvern")

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
    if sc is not None and slot not in ("AURA",):
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
        auto = "FAIL" if fails else "WEAK" if weak else "PASS"
        eye = EYE.get(iid)
        verdict = auto
        if eye:
            order = ["PASS", "WEAK", "FAIL"]
            verdict = max(auto, eye[0], key=order.index)
        results.append(dict(id=iid, name=it["name"], slot=it["slot"], rarity=it["rarity"], section=secs.get(iid, "?"),
                            listed=iid in in_catalog, verdict=verdict, auto=auto, fails=fails, weak=weak, eye=eye,
                            m=m, wing=wing, clear=clear, tex=texinfo))
        # per-item picture: the shipped thumbnail | the item alone | wing poses
        if thumb is not None:
            ims = [thumb.resize((256, 256), Image.LANCZOS), (alone or thumb).resize((256, 256), Image.LANCZOS)]
            ims += [p.resize((256, 256), Image.LANCZOS) for p in poses]
            P = Image.new("RGB", (256 * len(ims), 256), CARD)
            for k, im in enumerate(ims):
                P.paste(on_card(im), (256 * k, 0))
            P.save(out / "items" / f"{iid}.png")
        print(f"{verdict:4} {iid}", file=sys.stderr)
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
    if sims:
        lines += ["## Look-alikes (same slot)", ""] + [f"- {a} ~ {b}: {s:.0%}" for a, b, s in sims] + [""]
    (out / "report.md").write_text("\n".join(lines))
    js = [{k: v for k, v in r.items() if k != "eye"} | {"eye": list(r["eye"]) if r["eye"] else None} for r in results]
    (out / "report.json").write_text(json.dumps(js, indent=1, default=float))
    bad = [r for r in results if r["verdict"] != "PASS"]
    print(f"{len(results)} items: {len(results) - len(bad)} pass, {len(bad)} weak/fail -> {out / 'report.md'}", file=sys.stderr)


if __name__ == "__main__":
    main()
