#!/usr/bin/env python3
"""Draws the anti-aliased UI shape textures used by Theme into assets/evade/textures/gui/shape/.

Every texture is white RGBA (only the alpha channel carries the shape), so one texture serves any colour:
the game tints it with the vertex colour. Each PNG gets a .png.mcmeta of {"texture":{"blur":true,"clamp":true}},
which ReloadableTexture turns into LINEAR filtering and CLAMP_TO_EDGE addressing. That is what makes the
edges smooth when a texture is stretched or drawn at a fractional GUI scale. Do not move these into
textures/gui/sprites or draw them with drawGuiTexture: the GUI atlas samples NEAREST.

Outputs (27 PNGs, 27 .mcmeta files):
  disc_r1..r8    AA disc of radius r GUI px at 4 texels per GUI px (8r x 8r texels). Its four quadrants are
                 the rounded corners of a filled rect; the whole disc is a dot or a pill cap.
  ring_r2..r8    1 GUI px AA stroke on the outer edge of the disc (outer disc minus a disc inset by 4 texels).
  hair_r2..r8    The same with a 0.5 GUI px stroke (exactly 1 device px at GUI scale 2).
  shadow_window  Nine-slice soft shadow sources. pad P = ceil(3 sigma) is the blur's reach outside the shape;
  shadow_card    the straight run K = r + ceil(3 sigma) past the corner arc lets the edge strips reach the full
  shadow_hud     straight-edge falloff; slice C = P + K; logical size L = 2C + 2 (a 2 px middle strip that is
                 stretched along each edge). Knockout shadows store blur x (1 - shape), so translucent bodies
                 drawn on top do not show a darker inner ring.
  shadow_knob    Whole-image shadow for an 8 px disc (sigma 1, pad 3): 14 x 14 GUI px.
  fade_h         64 x 1 strip whose alpha ramps 0 -> 1 over 16 texels at each end (accent lines that fade out).

The shadow geometry (texels / logical / slice / pad) is printed to stdout as JSON; copy it into Theme's
Shadow records when a shadow changes. The output folder is generated: stale PNGs and .mcmeta files in it
are removed on each run.

Usage: python3 scripts/draw-ui-shapes.py   (needs Pillow)
"""
import json
import math
import pathlib
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter

OUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/assets/evade/textures/gui/shape"
D = 4    # texels per GUI px for discs, rings, hairlines and the HUD/card shadows
SS = 8   # supersampling factor for every hard-edged mask (box-downsampled afterwards)
META = json.dumps({"texture": {"blur": True, "clamp": True}})

# name: (corner radius px, sigma px, texels per px, knockout)
SHADOWS = {
    "window": (5, 6, 2, False),
    "card": (3, 2, 4, True),
    "hud": (3, 1.5, 4, True),
}

written = []


def save(im, name):
    im.save(OUT / f"{name}.png", optimize=True)
    (OUT / f"{name}.png.mcmeta").write_text(META)
    written.extend((f"{name}.png", f"{name}.png.mcmeta"))


def white(alpha):
    """White RGBA image with the given L-mode alpha. RGB stays white under transparent texels too, so LINEAR
    filtering never pulls a dark fringe into the edge."""
    im = Image.new("RGBA", alpha.size, (255, 255, 255, 0))
    im.putalpha(alpha)
    return im


def rrect_mask(n, box, radius, ss=SS):
    """n x n L-mode coverage mask of a rounded rect (box and radius in texels), drawn at ss x and box-downsampled,
    so every texel holds the exact fraction of its area the shape covers."""
    big = Image.new("L", (n * ss, n * ss), 0)
    x0, y0, x1, y1 = (v * ss for v in box)
    ImageDraw.Draw(big).rounded_rectangle((x0, y0, x1 - 1, y1 - 1), radius=radius * ss, fill=255)
    return big.resize((n, n), Image.BOX)


def disc(r):
    n = 2 * r * D
    return white(rrect_mask(n, (0, 0, n, n), n / 2))


def ring(r, stroke):
    """Outer disc minus a disc inset by the stroke width (stroke in GUI px)."""
    n = 2 * r * D
    k = stroke * D
    outer = rrect_mask(n, (0, 0, n, n), n / 2)
    inner = rrect_mask(n, (k, k, n - k, n - k), n / 2 - k)
    return white(ImageChops.subtract(outer, inner))


def shadow(radius, sigma, density, knockout):
    P = math.ceil(3 * sigma)
    K = radius + math.ceil(3 * sigma)
    C = P + K
    L = 2 * C + 2
    n = L * density
    shape = rrect_mask(n, (P * density, P * density, (L - P) * density, (L - P) * density), radius * density)
    a = shape.filter(ImageFilter.GaussianBlur(sigma * density))  # Pillow's radius is the standard deviation
    if knockout:
        a = ImageChops.multiply(a, ImageChops.invert(shape))
    return white(a), {"texels": n, "logical": L, "slice": C, "pad": P, "radius": radius, "knockout": knockout}


def disc_shadow(d, sigma, density=D):
    P = math.ceil(3 * sigma)
    L = d + 2 * P
    n = L * density
    shape = rrect_mask(n, (P * density, P * density, (P + d) * density, (P + d) * density), d * density / 2)
    return white(shape.filter(ImageFilter.GaussianBlur(sigma * density))), {"texels": n, "logical": L, "pad": P}


def fade_strip(width=64, ramp=16):
    a = Image.new("L", (width, 1))
    a.putdata([round(255 * min(1, min(i + 0.5, width - 0.5 - i) / ramp)) for i in range(width)])
    return white(a)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for r in range(1, 9):
        save(disc(r), f"disc_r{r}")
    for r in range(2, 9):
        save(ring(r, 1.0), f"ring_r{r}")
        save(ring(r, 0.5), f"hair_r{r}")
    specs = {}
    for name, (radius, sigma, density, knockout) in SHADOWS.items():
        im, spec = shadow(radius, sigma, density, knockout)
        save(im, f"shadow_{name}")
        specs[name] = spec
    im, spec = disc_shadow(8, 1.0)
    save(im, "shadow_knob")
    specs["knob"] = spec
    save(fade_strip(), "fade_h")

    keep = set(written)
    for f in sorted(OUT.iterdir()):
        if f.is_file() and f.name not in keep and (f.name.endswith(".png") or f.name.endswith(".png.mcmeta")):
            f.unlink()
            print(f"removed stale {f.name}", file=sys.stderr)

    pngs = [f for f in written if f.endswith(".png")]
    size = sum((OUT / f).stat().st_size for f in written)
    print(f"wrote {len(pngs)} PNGs + {len(written) - len(pngs)} .mcmeta ({size / 1024:.1f} KB) to {OUT}", file=sys.stderr)
    print(json.dumps(specs, indent=1))


if __name__ == "__main__":
    main()
