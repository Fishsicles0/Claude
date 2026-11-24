#!/usr/bin/env python3
"""Makes the launcher's Home banner and news pictures from the site's own screenshots (site/assets/*.webp).

Run from the repo root after the site screenshots change:  python3 installer/tools/hero-images.py
Writes JPEGs into installer/src/main/resources/evade/hero/ (Java's ImageIO can't read WebP).
"""
import os
import sys
from PIL import Image, ImageFilter

SRC = "site/assets"
OUT = "installer/src/main/resources/evade/hero"
# name in the launcher -> site screenshot
IMAGES = {
    "menu": "menu-hero",
    "hud": "hud",
    "styles": "gui-styles",
    "clips": "clips-library",
    "settings": "menu-settings",
    "theme": "theme-modern",
}

# Home's hero backdrops: gameplay, softly blurred so the title and Play button read cleanly on top.
BACKDROPS = {
    "bg-hud": "hud",
    "bg-world": "hud-clip",
    "bg-menu": "menu-hero",
    "bg-clips": "clips-library",
}

def main():
    os.makedirs(OUT, exist_ok=True)
    for name, src in IMAGES.items():
        path = os.path.join(SRC, src + ".webp")
        if not os.path.exists(path):
            print("missing", path, file=sys.stderr)
            continue
        im = Image.open(path).convert("RGB")
        w = 1280
        if im.width > w:
            im = im.resize((w, round(im.height * w / im.width)), Image.LANCZOS)
        out = os.path.join(OUT, name + ".jpg")
        im.save(out, "JPEG", quality=84, optimize=True, progressive=False)
        print("wrote", out, os.path.getsize(out) // 1024, "KB")
    for name, src in BACKDROPS.items():
        path = os.path.join(SRC, src + ".webp")
        if not os.path.exists(path):
            print("missing", path, file=sys.stderr)
            continue
        im = Image.open(path).convert("RGB")
        w = 1100
        im = im.resize((w, round(im.height * w / im.width)), Image.LANCZOS)
        im = im.filter(ImageFilter.GaussianBlur(6))
        out = os.path.join(OUT, name + ".jpg")
        im.save(out, "JPEG", quality=82, optimize=True)
        print("wrote", out, os.path.getsize(out) // 1024, "KB")

if __name__ == "__main__":
    main()
