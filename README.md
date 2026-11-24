# Evade Client

A free, server-safe quality-of-life client for **Minecraft Java 1.21.11** (Fabric, Java 21). Evade gives you a
customisable HUD, visual and camera tweaks, a built-in FPS boost, instant-replay clips and shareable profiles,
all from one menu. Get it at [evadeclient.com](https://evadeclient.com).

## Server-safe by design

Evade changes what you see and how your own controls feel, and nothing else:

- No combat or movement advantages: nothing clicks, aims, builds or plays for you.
- Nothing hidden is revealed: no seeing through walls, no x-ray and no tracking of other players.
- Evade never changes, delays or cancels the packets your game sends.
- The few mods that send anything to the server for you, like saying gg after a match or rejoining after a
  disconnect, are grouped on their own *Server-facing* page in the menu and start switched off.

Every server still sets its own rules, so check them before using something like Freelook.

## Installing

The easiest way is **Evade Installer** from [evadeclient.com](https://evadeclient.com) (see [Installer](#installer)).
To install by hand, put the Evade jar and [Fabric API](https://modrinth.com/mod/fabric-api) into `.minecraft/mods`
and launch Minecraft 1.21.11 with Fabric Loader 0.20.1 or newer.

## Building

```bash
./gradlew build          # jar lands in build/libs/evade-client-<version>.jar
./gradlew runClient      # launches a dev Minecraft with the client loaded
```

### In-game tests

```bash
python3 scripts/fetch-test-mods.py     # once: the mods the installer can add (Sodium, Iris, Simple Voice Chat...)
xvfb-run ./gradlew runClientGameTest
```

This launches the real game next to those mods, builds a test world and checks that each mod does what it says.
ToggleSprint holds sprint through the movement input, Time Changer sets the time you see, Zoom narrows the FOV,
Freelook turns the camera but not the player, and Fullbright lights the world without touching your Brightness
setting. Chat commands, keybinds, hidden settings and modules switched on before a world loads are covered too.
Every text HUD element shows a real reading, the default layout fits a small screen without overlaps, and the
whole HUD costs less than 4 ms a frame with every element on. Particles, TNT Timer, Chunk Borders, Clear Water,
NoFog, Nick Hider, Hit Color, ShinyPots, EnchantGlint and BetterTooltips do their job and undo it when switched
off. Performance culls far entities (never map art, holograms or name tags), applies and restores its video
settings, trims particles without losing gameplay cues or fireworks, and idles when you're AFK. Clips saves a
playable MP4, Evade Icons marks Evade users in the tab list, and the Voice Chat shortcut opens Simple Voice Chat.
Profiles keep separate settings, switch for linked servers and back, and their share codes round-trip. The menu's
search, styles and animations, the HUD editor's drag, resize and grid, and theme presets work. The run ends by
checking that no module crashed along the way. Screenshots land in `build/run/clientGameTest/screenshots/`.

`./gradlew runClientGameTest -PmenuShot` skips the tests and only screenshots the menu (each GUI style) and the
HUD at several screen sizes, for a quick look at GUI changes.

## Installer

`installer/` is **Evade Installer**, a standalone Swing app (no Minecraft dependencies) that players download
from evadeclient.com. It installs Fabric Loader, Fabric API and Evade into `.minecraft/evade` and adds an
"Evade Client" profile (with icon) to the official Minecraft Launcher, which handles login and launching.
Optional extras are Sodium and other performance mods, Simple Voice Chat, Evade Clips, and shaders (Iris with a
shader pack). All of them come from Modrinth or evadeclient.com.

```bash
./gradlew :installer:jar :installer:createExe   # EvadeInstaller.jar + Evade-Installer.exe (Launch4j)
java -jar installer/build/libs/EvadeInstaller.jar --cli [--dir PATH] [--memory GB] [--no-copy-settings] \
    [--no-performance-mods] [--no-voice-chat] [--no-clips] [--shaders]
java -jar installer/build/libs/EvadeInstaller.jar --screenshots OUTDIR   # render every screen to PNG
```

It reads `https://evadeclient.com/version.json` for the current Evade version and checksum, gets the newest
stable Fabric Loader from meta.fabricmc.net and Fabric API from Modrinth, and verifies every download.
`scripts/build-site.sh` builds the client, the installer and Evade Clips and publishes them to `site/downloads/`.

## Evade Clips

`clips/` is **Evade Clips**, a desktop app that records, stores and edits Minecraft clips; it works only with
Evade Client. It has
free instant replays, recording while the replay buffer keeps running, automatic highlight detection, a
timeline editor, and Discord/Shorts/GIF exports. Evade Client's **Clips** mod saves clips through it
automatically on kills, totem pops, deaths, wins and advancements. See [clips/README.md](clips/README.md).
`clips-share/` is the optional share-link and phone-sync server for it (Cloudflare Worker + R2).

## Replays (3D)

The **Replay** module records the world as you play, not just the screen: every packet the server sends (blocks,
players, mobs, items, particles, sounds) plus your own movement, from the moment you join a world or server. So
afterwards you can fly through it with a free camera, see moments from angles you never had, and render cinematic
video.

- **Record**: pause menu → *Start recording* (works mid-match: the session since joining is captured in the
  background), *Pause*, *Marker*, *Save replay*. Or turn on *Record Every Session*. Replays save when you leave.
- **Watch**: title screen → *Replays*. You're a spectator camera that flies through walls; the player who recorded
  is shown as a normal player. `P` play/pause, `←`/`→` 5 s, `↑`/`↓` speed, `K` camera keyframe, `E` editor.
- **Editor** (`E`): a timeline with markers and keyframes. *Camera*: keyframes make a smooth path (FOV and roll
  per keyframe), look at or orbit a player or mob, speed keyframes for slow motion and time-lapse. *Scene*: time of
  day, weather, fog, name tags, hide players and mobs, green-screen sky. *Render*: any size up to 4K (or vertical),
  30/60/120 fps, rendered frame by frame to MP4 (ffmpeg from Evade Clips or the PATH), straight into Evade Clips.
  *Sound*: game sounds (optionally music) heard from the camera, in sync with the picture: while rendering, the
  game's audio runs on an OpenAL loopback device and exactly 1/fps of a second is mixed after each frame, then muxed in
  as AAC (a `.wav` beside the video when there's no ffmpeg). Nothing plays through your speakers during a render.
  In slow motion sounds slow down with the picture, lower and longer like tape (Render tab: *Slow-mo: deep / normal*).
- **Voice chat** (with Simple Voice Chat installed): the voices you hear, and your own (settings *Record Voice Chat*,
  *Record My Voice*), are recorded into the replay as Opus through a Simple Voice Chat plugin (`voicechat`
  entrypoint, `VoiceChatPlugin`). Played back from where each player stands as they move, following speed and pause,
  and rendered into the video. The API is compiled against small stand-ins in `src/svcstub` (never packaged).
- **Player POV**: V (or *View* in the editor's Camera tab) watches through a player's eyes, who recorded first, then
  each other player, then back to the free camera; renders use it too (it takes over from a camera path while on).
- **HUD mods per replay**: Scene tab → *HUD mods* hides chosen HUD widgets (FPS, Keystrokes, CPS...) while watching that
  replay and in its videos; the normal HUD is unchanged. Also: POV hands, Follow (track entity), time-of-day keyframes.
- **Free camera** flies at normal speed whatever the replay speed (the game's own movement would slow with it).
- Files: `.minecraft/evade/replays/*.evadereplay` (a zip: `meta.json` + the recorded stream), camera paths in
  `*.evadereplay.project.json` beside them, renders in `.minecraft/evade/renders`.
- `./gradlew runClientGameTest -PreplayTest` records a scene in singleplayer and on a real dedicated server, replays
  both, plays, seeks both ways, checks green screen and renders a camera path to a 640x360 MP4 with sound, checking
  an explosion lands at the moment it happened, a voice-chat voice (recorded through the real Simple Voice Chat) is
  heard when it was said, a half-speed render stretches the sound x2, and the free camera flies at full speed at 0.25x.

## Using it

| Action | How |
|---|---|
| Open the menu | **Right Shift** (change it under *Settings*), or type `.menu` in chat |
| Toggle a mod | Click it |
| Mod settings and keybind | Click the gear, or right-click the mod |
| Search every mod and setting | Start typing, or press **Ctrl+F** |
| Move or resize HUD elements | *Edit HUD Layout*: drag (snaps to edges, the centre and other elements; **G** toggles a grid), scroll to resize, right-click for settings |
| Profiles | *Profiles*: separate setups, switched automatically per server and shared as a code |
| Look and feel | *Settings*: theme (Modern, Midnight, OLED, Classic), accent colour, GUI style (List, Cards, Compact), smooth font, HUD size, blur, animations |

Keybinds can use keyboard keys or extra mouse buttons, and the menu warns you when a key is already in use.
Toggling a mod with its key shows a small notification.

Chat commands are handled by Evade and never sent to the server. Mod and setting names work with or without
spaces (`.toggle Nick Hider` or `.toggle nickhider`). Anything that isn't a command goes to chat as typed, and
starting a message with `..` sends a command-looking message as normal chat.

| Command | Does |
|---|---|
| `.help` | Lists every command |
| `.menu` | Opens the menu |
| `.toggle <mod>` (`.t`) | Turns a mod on or off |
| `.bind <mod> <key\|mouse4\|none>` | Sets a mod's keybind |
| `.set <mod> <setting> [value]` | Changes a setting, or shows it (`.set Zoom Level 4`) |
| `.modules` | Lists the mods |
| `.wp` | Waypoints: add, del, list, near, color, hide, show, rename, share, target, clear |
| `.nick <name>` | Sets your Nick Hider name |
| `.clip` / `.clips` | Saves a clip now, or opens Evade Clips (`.clips last` edits your last clip) |
| `.voice` | Opens the voice chat menu |
| `.save` / `.load` | Saves or reloads the config |

Config lives in `config/evade/`: `config.json`, profiles in `profiles/` and waypoints in `waypoints.json`.

## Mods

The tables follow the menu's pages. Each mod has its own settings and keybind.

### Visuals

| Mod | What it does |
|---|---|
| **NoFog** | Removes distance fog so you see out to your full render distance. Fog from Blindness, Darkness, lava and powder snow stays. |
| **NoRender** | Hides fire on screen (always, or only while fire can't hurt you), pumpkin blur, the nausea and portal overlays and screen wobble, the totem animation (or shrinks it), and advancement, recipe and tutorial toasts. |
| **BetterTooltips** | Shulker box contents, durability and food values in item tooltips. |
| **Camera Distance** | Sets how far back the third-person camera sits (capped at 8 blocks; the camera still stops at walls). |
| **Motion Blur** | Blends frames together for smoother-looking motion. Can cause motion sickness. |
| **Waypoints** | Saved locations per server and dimension, drawn as a beam with the name and distance. Adds a point where you die. Manage them with `.wp`. |
| **Fullbright** | Lights up caves and nights as if gamma were maxed, without touching your brightness setting. |
| **Damage Tint** | A red vignette at the screen edges when your health gets low. |
| **Clear Water** | Removes underwater fog and can hide the water overlay. Blindness and Darkness still apply. |
| **Particles** | Hides crits, sharpness sparks, damage hearts, sweeps, explosions, totem pops, fireworks, mining particles or your own potion particles, each on its own switch. |
| **Pack Tweaks** | Lowers and fades the fire on screen, fades or drops the pumpkin and powder-snow overlays, holds the shield lower and shrinks a totem in your off hand. |
| **Inventory Blur** | Blurs the world behind open inventories. |
| **Boss Bar** | Shrinks, moves down, trims to a few bars, slims to just the bar or just the name, or hides the boss bars. |
| **Scoreboard** | The sidebar drawn by Evade: hide or recolour the numbers (or let Auto decide), trim it to fewer lines, hide the title or the server address line, move, resize or hide it. |
| **TNT Timer** | A countdown above primed TNT. |
| **Chunk Borders** | Chunk boundaries around you without F3+G, in your colour and height. |
| **Nick Hider** | Shows a nickname instead of your username everywhere on your screen and can hide your skin. Handy for streaming. Set the name with `.nick`. |
| **Tab Tweaks** | Numeric ping in the player list, and hides its header or footer. |
| **Hit Color** | Changes the colour of the flash when you hit players or mobs. |
| **1.7 Animations** | Visual 1.7-style swing while eating and held-item position. |
| **Item Physics** | Dropped items tumble as they fall, tip over flat when they land and float in water, or simply lie flat (Flat mode). |
| **ShinyPots** | Makes potions glint in your inventory and hotbar. |
| **EnchantGlint** | Recolours the enchantment glint (solid, gradient or rainbow), with its own speed, scale and armour glint. |
| **Hitboxes** | Restyles the F3+B hitboxes: colour, line width, eye line and look vector, and which entity types show. |

### Controls

| Mod | What it does |
|---|---|
| **ToggleSprint** | Tap sprint (and optionally sneak) to toggle it. Shows the state on screen. |
| **Zoom** | Hold **C** to zoom and scroll to change the level. Zooms smoothly and lowers mouse sensitivity while zoomed, with an optional cinematic camera. |
| **Freelook** | Hold **Left Alt** to look around your player without turning. Off by default, and always off on servers that forbid it. |
| **Crosshair** | Cross, dot, circle, cross + dot, T, X, square, arrow, circle + dot, or your own pixel pattern (with share codes). Set the size, gap, thickness, rotation, outline and colour, with a second colour while it's over a mob or player. Can also show in third person. |
| **No Hurt Cam** | Removes the screen shake when you're hit. |
| **Block Outline** | Colour, thickness and a translucent fill for the block you're looking at. |
| **Time Changer** / **Weather Changer** | Client-side time of day and weather. Only you see them. |
| **FOV Changer** | Keeps your field of view fixed so speed, sprinting and status effects don't stretch it. |
| **Sound Tweaks** | Volume sliders for rain, thunder, explosions, fireworks, villagers, anvils, armour equip and hit sounds. |
| **Performance** | Evade's built-in FPS boost. It culls far entities and block entities, caps particles, uses lighter video settings, offers a Low-End PC Mode, and drops the frame rate while you're AFK or tabbed out. It works alongside Sodium and the other performance mods the installer adds. |

### Server-facing

These send something to the server for you, so they have their own page and start switched off.

| Mod | What it does |
|---|---|
| **AutoGG** | Says gg in chat when a match ends. |
| **AutoReconnect** | Rejoins the server a few seconds after you're disconnected. |
| **AutoTip** | Sends Hypixel's `/tip all` on a timer. |

### Misc

| Mod | What it does |
|---|---|
| **BetterChat** | Timestamps on chat lines, and repeated messages stacked into one with a count. |
| **Notifier** | Tells you when your own totem pops. |
| **Protection** | Warns you when your armour or held item is about to break. |
| **Clips** | Instant replay and automatic clips. Press **F8** to save what just happened, or let it clip kills (close ones become one clip), totem pops, deaths, wins and advancements. It uses Evade Clips when that's installed, and otherwise saves an MP4 itself. |
| **Evade Icons** | Puts an Evade logo next to tab-list players who also use Evade, checked through evadeclient.com. Turn off *Show That I Use Evade* to keep yours hidden. |
| **Voice Chat** | Opens Simple Voice Chat's menu and shows whether the server supports it. |
| **Screenshot Tools** | Copy, Folder and Delete buttons after "Saved screenshot as ...", and an optional auto copy. |

### HUD (drag and resize in *Edit HUD Layout*)

| Element | What it shows |
|---|---|
| **Watermark** | The Evade logo or your own text in a corner, optionally with FPS, ping and time. |
| **Module List** | The mods you have on, listed down the side of the screen. Colours can be an accent wave, gradient, rainbow, per category, custom or plain, with slide or fade animations. |
| **ArmorHUD** | Your armour and held items with durability %, and a warning when something is about to break. Also counts supplies: totems, gapples, pearls, wind charges, XP bottles, splash potions, crystals and obsidian. |
| **Cooldowns** | Timers for your item cooldowns: ender pearls, wind charges, spears, chorus fruit, a shield disabled by an axe, goat horns and any cooldown a server adds. Also shows your weapon charge and effect timers, and flashes and pings when something is ready. |
| **Keystrokes** | WASD, mouse buttons with CPS, and the space bar, lighting up as you press them. |
| **CPS** / **FPS** / **Ping** | Clicks per second (left and right), frame rate, and your latency to the server. |
| **Coordinates** | Your position, facing and biome, with optional Nether/Overworld conversion. |
| **Potion Effects** | Your effects with icon, level and time left. Blinks when one is about to run out, and can replace the vanilla display. |
| **Direction HUD** | A compass strip with your heading. |
| **Clock** / **Server Address** / **Memory** / **Speed** | Real-world time (12h/24h), the server you're on, Java memory use, and your horizontal speed in blocks per second. |
| **Reach Display** / **Combo Counter** | Distance of your last hit, and hits in a row without being hit. |
| **Inventory Viewer** | Your main inventory on screen. |
| **Item Counter** | How many of an item you carry: what you're holding, blocks, arrows, food, gapples or pearls. |
| **Saturation** | Your hunger saturation. |
| **Stopwatch** | A stopwatch that runs while it's on. |
| **Pack Display** | Your active resource pack. |
| **Frame Time Graph** | Live frame times with average FPS, 1% low and worst frame. |
| **Frame Profiler** | FPS, 1% low, frame time, HUD cost and garbage-collection pauses, once a second. |

Every HUD element has Scale, a background style (Evade, Solid, Glass or Outline) with opacity, *Hide In Menus*
and *Only In Combat* (shown for a few seconds after you hit something or take damage). Text elements also have
text colour, chroma, brackets, text shadow and smooth font.

## Layout

```
net.evadeclient
├── EvadeClient          entrypoint: tick, HUD layers, world-render, chat and screen hooks
├── module/              Module, HudElementModule, TextHudModule, ModuleManager, Category
│   ├── visuals/         visual tweaks (NoFog, Waypoints, Nick Hider, Particles...)
│   ├── mods/            controls and camera (Zoom, Freelook, Crosshair, Performance...)
│   ├── misc/            chat, server-facing and convenience mods (Clips, BetterChat, AutoGG...)
│   ├── hud/             HUD readouts (FPS, Coordinates, Keystrokes...)
│   └── client/          Client Settings, Watermark, Module List, ArmorHUD, Cooldowns
├── setting/             Boolean, Number, Mode, Color, Key, String and Status settings
├── gui/                 EvadeMenu (sidebar, search, mods, settings, profiles), HudEditorScreen, Motion
├── hud/                 Hud (draw order and visibility rules) and HudDraw (shared panels and text)
├── render/              render layers for world overlays (block outline, chunk borders, waypoint beams)
├── util/                theme, fonts, icons, notifications, frame stats, click and cooldown tracking
├── command/             chat commands (handled locally, never sent to the server)
├── config/              config, profiles, share codes and per-server profiles
└── mixin/               client-side hooks into rendering, the HUD, input, camera and chat
src/gametest/            in-game automated tests, menu screenshots and footage for the Evade Clips site
installer/               Evade Installer
clips/                   Evade Clips desktop app
clips-share/             clips.evadeclient.com share server (Cloudflare Worker)
worker/                  evadeclient.com Worker
site-src/, site/         evadeclient.com pages (built by scripts/build-pages.py) and assets
```

## License

Evade Client is released under the [MIT License](LICENSE). The bundled Inter font is under the SIL Open Font
License ([src/main/resources/LICENSE-Inter-OFL.txt](src/main/resources/LICENSE-Inter-OFL.txt)).
Evade is not affiliated with Mojang or Microsoft.
