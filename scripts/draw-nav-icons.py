#!/usr/bin/env python3
"""Draws the menu sidebar's line icons (white, tinted in game) into assets/evade/textures/gui/nav/.

Each icon is drawn at 4x and downsampled to 24x24, shown at 12x12 GUI pixels (crisp at GUI scale 2).
"""
import math, pathlib
from PIL import Image, ImageDraw

OUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/assets/evade/textures/gui/nav"
S, W = 96, 8          # canvas and stroke width at 4x
WHITE = (255, 255, 255, 255)

def canvas():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    return im, ImageDraw.Draw(im)

def save(im, name):
    im.resize((24, 24), Image.LANCZOS).save(OUT / f"{name}.png")

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
    d.line(pts, fill=WHITE, width=W, joint="curve")
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
    d.ellipse((26, 26, 70, 70), fill=(0, 0, 0, 0))
    d.ellipse((38, 38, 58, 58), outline=WHITE, width=W)
    save(im, "settings")

if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for f in (all_mods, controls, server, misc, hud, visuals, edit_hud, profiles, settings):
        f()
    print("icons ->", OUT)
