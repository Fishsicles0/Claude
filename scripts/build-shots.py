#!/usr/bin/env python3
"""Convert the gametest screenshots into the site's webp assets.

The in-game tests (runClientGameTest) drop PNGs in build/run/clientGameTest/screenshots.
This turns the current ones into site/assets/*.webp so the website always shows the
build that is actually shipping. Run it after a gametest run, before build-pages.
"""
import glob, os, shutil, subprocess, sys, tempfile
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
    "evade-gui-list":      "gui-list.webp",
    "evade-profiles":      "gui-profiles.webp",
    "evade-zoom":          "zoom.webp",
    "evade-theme-modern":   "theme-modern.webp",
    "evade-theme-midnight": "theme-midnight.webp",
    "evade-theme-oled":     "theme-oled.webp",
    "evade-theme-classic":  "theme-classic.webp",
}

print("screenshots -> webp:")
for src, out in FULL.items():
    save(Image.open(latest(src)).convert("RGB"), out)

# hero image: the menu, resized to the index hero's box
menu = Image.open(latest("evade-menu")).convert("RGB")
save(menu.resize((1420, 799), Image.LANCZOS), "menu-hero.webp")

# decorative HUD crops for the index hero floats, cut from the 1080p HUD shot
hud = Image.open(latest("evade-hud-1080p")).convert("RGB")
# cooldowns panel, top centre (scaled up with hard pixels so it stays crisp)
cd = hud.crop((826, 82, 1094, 180))
save(cd.resize((cd.width * 2, cd.height * 2), Image.NEAREST), "crop-cooldowns.webp")
# keystrokes, bottom right
keys = hud.crop((1774, 798, 1920, 976))
save(keys.resize((keys.width * 3, keys.height * 3), Image.NEAREST), "crop-keys.webp")
p = f"{OUT}/crop-target.webp"
if os.path.exists(p):
    os.remove(p)

# printer.webp is no longer used; drop it if present
p = f"{OUT}/printer.webp"
if os.path.exists(p):
    os.remove(p)
    print("  removed printer.webp (unused)")

# the three GUI styles fanned out, front to back: List, Cards, Compact (each cropped to the menu window)
def menu_window(name):
    im = Image.open(latest(name)).convert("RGB")
    return im.crop((166, 56, 1754, 1024))  # the menu at 1080p, GUI scale 2, plus its glow
fan = Image.new("RGB", (1920, 1080), (8, 8, 10))
from PIL import ImageFilter, ImageDraw
layers = [("evade-gui-compact", (700, 330)), ("evade-gui-cards", (380, 175)), ("evade-gui-list", (60, 20))]
for name, (x, y) in layers:
    w = menu_window(name).resize((1160, 707), Image.LANCZOS)
    shadow = Image.new("L", fan.size, 0)
    ImageDraw.Draw(shadow).rectangle((x + 10, y + 24, x + w.width + 10, y + w.height + 24), fill=200)
    fan.paste((0, 0, 0), (0, 0), shadow.filter(ImageFilter.GaussianBlur(26)))
    fan.paste(w, (x, y))
save(fan, "gui-styles.webp")

# short recordings: the gametest captures each frame on a fixed 30 fps clock; stitch them into H.264 MP4s
def clip(name, out, poster_at):
    frames = sorted(glob.glob(f"{SHOTS}/*_{name}.png"))
    if not frames:
        sys.exit(f"missing clip frames: {name}")
    tmp = tempfile.mkdtemp()
    try:
        for i, f in enumerate(frames):
            os.symlink(os.path.abspath(f), f"{tmp}/f{i:04d}.png")
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-framerate", "30", "-i", f"{tmp}/f%04d.png",
                        "-vf", "scale=1280:720:flags=lanczos", "-c:v", "libx264", "-preset", "slow", "-crf", "27",
                        "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-an", f"{OUT}/{out}.mp4"], check=True)
    finally:
        shutil.rmtree(tmp)
    poster = Image.open(frames[min(poster_at, len(frames) - 1)]).convert("RGB").resize((1280, 720), Image.LANCZOS)
    save(poster, f"{out}.webp", q=80)
    print(f"  {out + '.mp4':24} {len(frames)} frames, {len(frames) / 30:.1f}s  {os.path.getsize(f'{OUT}/{out}.mp4') // 1024} KB")

print("recordings -> mp4:")
clip("clip-menu", "menu-clip", 40)
clip("clip-hud", "hud-clip", 50)
clip("clip-play", "play-clip", 60)

print("done")
