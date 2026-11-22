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

print("done")
