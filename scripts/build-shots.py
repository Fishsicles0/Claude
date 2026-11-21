#!/usr/bin/env python3
"""Convert the gametest screenshots into the site's webp assets.

The in-game tests (runClientGameTest) drop PNGs in build/run/clientGameTest/screenshots.
This turns the current ones into site/assets/*.webp so the website always shows the
build that is actually shipping. Run it after a gametest run, before build-pages.
"""
import glob, os, sys
from PIL import Image

SHOTS = "build/run/clientGameTest/screenshots"
OUT = "site/assets"

def latest(name):
    # files are NNNN_name.png; take the newest matching that exact suffix
    matches = sorted(glob.glob(f"{SHOTS}/*_{name}.png"))
    if not matches:
        sys.exit(f"missing screenshot: {name}")
    return matches[-1]

def save(img, out, q=84):
    img.save(f"{OUT}/{out}", "WEBP", quality=q, method=6)
    print(f"  {out:24} {img.size[0]}x{img.size[1]}  {os.path.getsize(f'{OUT}/{out})')//1024 if False else os.path.getsize(f'{OUT}/{out}')//1024} KB")

# full-size assets: source screenshot -> webp name
FULL = {
    "evade-menu":          "menu.webp",
    "evade-menu-settings": "menu-settings.webp",
    "evade-hud-1080p":     "hud.webp",
    "evade-hud-editor":    "hud-editor.webp",
    "evade-gui-cards":     "gui-cards.webp",
    "evade-gui-compact":   "gui-compact.webp",
    "evade-gui-doomsday":  "gui-doomsday.webp",
    "evade-zoom":          "zoom.webp",
    "evade-pack-hud":      "pack-hud.webp",
    "evade-pack-inventory":"pack-inventory.webp",
    "evade-pack-title":    "pack-title.webp",
}

print("screenshots -> webp:")
for src, out in FULL.items():
    save(Image.open(latest(src)).convert("RGB"), out)

# hero image: the menu, resized to the index hero's box
menu = Image.open(latest("evade-menu")).convert("RGB")
save(menu.resize((1420, 799), Image.LANCZOS), "menu-hero.webp")

# decorative HUD crops for the index hero floats, cut from the 1080p HUD shot
hud = Image.open(latest("evade-hud-1080p")).convert("RGB")
# cooldown stack, top-left
save(hud.crop((6, 6, 6 + 528, 6 + 340)), "crop-cooldowns.webp")
# target HUD, lower-right
tx, ty = 1920 - 640 - 24, 1080 - 252 - 48
save(hud.crop((tx, ty, tx + 640, ty + 252)), "crop-target.webp")

# printer.webp is no longer used; drop it if present
p = f"{OUT}/printer.webp"
if os.path.exists(p):
    os.remove(p)
    print("  removed printer.webp (unused)")

print("done")
