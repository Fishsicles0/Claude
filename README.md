# Evade Client

A Fabric PvP client for **Minecraft 1.21.11** (Java 21).

> Most multiplayer servers ban modified clients like this one. Use it in singleplayer,
> on your own server, or on servers that allow it.

## Building

```bash
./gradlew build          # jar lands in build/libs/evade-client-<version>.jar
./gradlew runClient      # launches a dev Minecraft with the client loaded
```

Install by dropping the jar plus [Fabric API](https://modrinth.com/mod/fabric-api) into
`.minecraft/mods` with Fabric Loader 0.18+ for 1.21.11.

### In-game tests

```bash
xvfb-run ./gradlew runClientGameTest
```

Launches the real game, builds a test world and checks each module works: AutoTotem equips, AutoPot heals,
AutoMend repairs, Reach/Hitbox extend, TriggerBot kills, AimAssist locks on. Screenshots land in
`build/run/clientGameTest/screenshots/`.

## Using it

| Action | How |
|---|---|
| Open ClickGUI | **Right Shift** |
| Toggle module | Left-click it |
| Show settings / keybind | Right-click it |
| Set keybind | Click **Bind**, press a key (Esc/Delete clears) |
| Move a panel | Drag its header |
| Move a HUD element | Open the ClickGUI and drag the box |

Chat commands (never sent to the server): `.help`, `.toggle <module>`, `.bind <module> <key|none>`,
`.set <module> <setting> <value>`, `.modules`, `.save`, `.load`. Config: `config/evade/config.json`.

## Modules

### Combat
| Module | What it does |
|---|---|
| **AutoPot** | Throws splash healing below a health threshold (falls back to regen), and re-buffs strength/speed when they run out and fire res when burning. Looks down, throws, restores your slot and rotation in the same tick. Refills the hotbar from your inventory. |
| **AutoTotem** | *Always*: re-equips the offhand totem instantly after a pop. *Smart*: only takes the offhand when you're low, falling or flying with an elytra, so you keep your shield/gapple otherwise. Optional backup totem in a hotbar slot. |
| **AutoMend** | Throws XP bottles at your feet when Mending armour drops below a threshold and keeps going until it's full. *Strip Repaired* takes off finished pieces so all the XP goes to damaged ones, then puts them back. Pauses when enemies are near or your health is low. |
| **AimAssist** | Per-frame, frame-rate-independent easing towards the nearest point of the target's hitbox. Movement is snapped to your mouse sensitivity like real input. FOV, range, priority (angle/distance/health), sticky lock, wall check. |
| **AutoCritical** | *Jump*: hops when you're about to hit and holds TriggerBot/AutoClicker swings until you're falling, so they land as real crits. *Packet*: tiny fake hop before each hit. Drops sprint before the hit, since sprinting cancels crits. |
| **AutoClicker** | Runs per frame for accurate CPS, with gaussian-randomised gaps and occasional pauses. Left and/or right click, *Smart Cooldown* for 1.9+ combat, doesn't interfere with mining. |
| **Reach** | Extends entity reach (3.0–6.0) with per-tick variance; optional block reach. |
| **TriggerBot** | Attacks when your crosshair is on a valid target and your weapon is charged. Random reaction delay and charge threshold, skips shield-blocking players unless you hold an axe. |
| **Hitbox** | Expands targets' hitboxes for your crosshair; optional outline display. |

All targeting modules share filters: players / mobs / animals, ignore teammates, ignore invisible.

### Render (all drawn through walls)
| Module | What it does |
|---|---|
| **ChestESP** | Chests (double chests merged), trapped chests, ender chests, shulkers in their own colour, barrels, hoppers, spawners. Outline/fill, tracers, distance fade. |
| **ChunkLocator** | Flags chunks that show signs of a base: stashes of storage, shulker boxes, rotated deepslate (natural deepslate is always vertical) and player-made blocks below y=0. Highlights the chunk, pings and posts the coordinates in chat. Uses palette checks, so scanning is cheap. |
| **PlayerTracers** | Lines from your crosshair to every player, coloured by distance or team. Optional boxes; your current fight target is highlighted. |

### HUD (drag in the ClickGUI)
| Element | What it shows |
|---|---|
| **ArmorHUD** | Your armour and held items with durability %, a flashing warning at low durability, and counts of totems, gapples, pearls, wind charges, XP, pots, crystals, obsidian. |
| **TargetHUD** | Whoever you're fighting (last hit) or looking at (up to 64 blocks): face, name, animated health + absorption bar, distance, their armour with durability, held items, blocking/using status, and Winning/Losing. |
| **Cooldowns** | Timers for every item cooldown: ender pearl, wind charge, spear, chorus fruit, shield disabled by an axe, goat horn, and gapple cooldowns on servers that add them. Also shows weapon charge and effect timers (gapple absorption, regen, strength, speed, fire res, resistance). Flashes READY and pings when something comes off cooldown. |
| **HUD** | Watermark, active-module list, coords, FPS. |

## Layout

```
net.evadeclient
├── EvadeClient          entrypoint: tick, HUD and world-render hooks
├── module/              Module, HudElementModule, ModuleManager, combat/ render/ client/
├── setting/             Boolean / Number / Mode settings
├── gui/                 ClickGUI (panels + HUD dragging)
├── hud/                 HUD rendering helpers
├── render/              through-wall line/box drawing (custom no-depth pipelines)
├── util/                inventory, rotations, targets, cooldowns, target tracking
├── command/ config/
└── mixin/               attack hook, reach, hitbox margin, cooldown + input access
src/gametest/            in-game automated tests
```
