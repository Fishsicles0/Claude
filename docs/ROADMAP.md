# Evade Client: merged mod list (v0.4.0)

Both request lists, merged and de-duplicated. **Had** = already in Evade before v0.4.0. **Added** = new in v0.4.0
(every one has an in-game test unless noted). **Same as** = a duplicate or a different name for something Evade
has. **Skipped** = not built, with the reason.

Ban risk is how likely a server's anti-cheat or staff are to catch it (low / med / high). Most servers ban any
client with combat or movement cheats regardless of detection.

## Combat (17 asked)

| Mod | Status | Ban risk | Notes |
|---|---|---|---|
| KillAura | **Added** | high | Attacks targets around you; priority, FOV, rotate, shield check. Tested. |
| CrystalAura | **Added** | high | Places/breaks end crystals using the game's own exposure maths; self-damage limits. Not tested in game (needs a second player). |
| AnchorAura | **Added** | high | Place, charge, detonate respawn anchors outside the Nether. Not tested in game (needs a second player). |
| AimAssist | Had | med | |
| BowAimbot | **Added** | high | Aims bow/crossbow/trident with drop and target movement. Not tested in game. |
| Triggerbot | Had (TriggerBot) | med | |
| AutoTotem | Had | med | |
| AutoArmor | **Added** | low | Listed twice (Combat and Player). Equips the best armour. Tested. |
| Surround | **Added** | med | Obsidian around your feet. Tested. |
| AutoTrap | **Added** | med | Obsidian box around the nearest enemy. Not tested in game (needs a second player). |
| Velocity | **Added** | high | Cuts knockback (hits and explosions). Tested with and without. |
| Reach | Had | high | |
| Criticals | Same as AutoCritical | med | |
| AntiBot | **Added** | low | Ignores fake players (not in tab list, invisible with no armour). On by default. |
| AutoLog | **Added** | low | Disconnects at low health or with no totems left. Not tested in game (it would end the test). |
| Hitbox | Had | med | |
| Sprint | Same as ToggleSprint | low | |

## Movement (16 asked, plus "Fly/ElytraFly" in the first list)

| Mod | Status | Ban risk | Notes |
|---|---|---|---|
| Fly | **Added** | high | Vanilla or Velocity mode, anti-kick dip. Tested. |
| ElytraFly | **Added** | high | Steer an elytra like flight. Not tested in game. |
| Speed | **Added** | high | Strafe or Bhop. Not tested in game. |
| Scaffold | **Added** | high | Bridges under you, tower mode. Tested. |
| NoFall | **Added** | high | Tested with and without (20-block fall). |
| Step | **Added** | med | Tested. |
| Spider | **Added** | high | Climb walls. Not tested in game. |
| LongJump | **Added** | high | Not tested in game. |
| HighJump | **Added** | high | Not tested in game. |
| Jesus | **Added** | high | Walk on water and lava. Tested. |
| Dolphin | **Added** | med | Stay at the surface while swimming. Not tested in game. |
| Parkour | **Added** | low | Jumps at block edges. Not tested in game. |
| AntiVoid | **Added** | med | Bounces you out of the void. Not tested in game. |
| SafeWalk | **Added** | low | Tested. |
| Blink | **Added** | high | Holds movement packets, then releases. Tested on the server side. |
| Timer | **Added** | high | Listed under Misc and Movement. Tested (x2 doubles the game ticks counted). |

## Render / Visuals (17 asked)

| Mod | Status | Ban risk | Notes |
|---|---|---|---|
| Player ESP | **Added** as ESP | low | One ESP module covers players, mobs, animals and items (box or glow). |
| Mob ESP | Same as ESP | low | |
| Chams | Partly, via ESP Glow mode | low | Glow mode shows entities through walls; full Chams (coloured models through walls) skipped. |
| ChestESP | Had | low | |
| StorageESP | Same as ChestESP | low | ChestESP already covers chests, shulkers, barrels, ender chests, hoppers, spawners. |
| XRay / OreESP | **Added** as OreESP | low | Ores outlined through walls. True X-ray (hiding all other blocks) skipped: it needs a terrain renderer rewrite and conflicts with Sodium. |
| Tracers | Had (PlayerTracers) | low | |
| Nametags | **Added** | low | Name, health, distance, armour and held items through walls. Not tested in game (needs a second player). |
| FullBright | Had | low | |
| Freecam | **Added** | low | Tested. |
| Zoom | Had | low | Listed in Render and OptiFine-type. |
| Hitbox ESP | Same as Hitbox's "Show Boxes" | low | |
| NoFog | **Added** | low | |
| NoWeather | Same as Weather Changer | low | |
| Dynamic Lights | Skipped | low | Needs a lighting-engine mod; install LambDynamicLights alongside Evade instead. |
| Shaders | Skipped | low | Needs a shader pipeline; install Iris alongside Evade instead. |
| Trajectories | **Added** | low | Shows where bows, pearls, snowballs, tridents, wind charges land. |

## World (13 asked, plus "material list" in the first list)

| Mod | Status | Ban risk | Notes |
|---|---|---|---|
| Schematics / Litematica Printer | **Added** as Printer | high | Follows Litematica's placement when Litematica is installed, otherwise reads .litematic files itself. Tested both ways (14/14 blocks correct). |
| Material list | **Added** as `.printer materials` | low | |
| Easy Place | Skipped | med | Litematica already has Easy Place built in; the Printer covers automatic placing. |
| Nuker | **Added** | high | All / Selected / Flatten. Tested. |
| AutoMine | **Added** | med | Keeps mining what you look at. |
| VeinMiner | **Added** | high | Whole ore veins and trees. Not tested in game. |
| FastPlace | **Added** | med | Listed twice (World and Player/Utility). |
| FastBreak | **Added** | high | No delay, finish early. |
| InstaMine | Same as FastBreak ("Finish At %") | high | |
| AutoFarm | **Added** | med | Harvest and replant. Tested. |
| AutoBuild | Skipped | high | Free-form building needs its own design; the Printer builds any schematic instead. |
| HighwayBuilder | Skipped | high | Large, anarchy-specific project; not started. |
| BaseFinder | Same as ChunkLocator | low | |
| ChunkLocator | Had | low | |
| SpawnProofer | **Added** | low | Slabs/buttons/plates/carpet on dark spawnable spots. Not tested in game. |

## Player / Utility (14 asked)

| Mod | Status | Ban risk | Notes |
|---|---|---|---|
| AutoEat | **Added** | low | Tested. |
| AutoTool | **Added** | low | Tested. |
| ChestStealer | **Added** | med | Listed twice (World and Player). Tested. |
| InventorySorter | **Added** | low | Sorts the main inventory when you open it. Not tested in game. |
| AutoFish | **Added** | med | Not tested in game. |
| AutoRespawn | **Added** | low | Tested. |
| AntiHunger | **Added** | med | Hides sprint starts from the server. |
| NoHunger | Same as AntiHunger | med | |
| AntiAFK | **Added** | low | |
| AutoWalk | **Added** | low | |
| AutoClicker | Had | med | |
| FastPlace | Same as FastPlace (World) | med | |
| GhostHand | Skipped | high | Opening containers through walls is rejected by most servers' reach checks; left out. |

## HUD (16 asked)

| Mod | Status | Notes |
|---|---|---|
| ArrayList, Watermark | Had (Module List) | |
| FPS, CPS, Ping, Speed | Had | |
| Coords | Had (Coordinates) | |
| Compass | Had (Direction HUD) | |
| Keystrokes, TargetHUD, Cooldowns, Potion Effects | Had | |
| ArmourHUD | Had (ArmorHUD) | |
| Time | Same as Clock | |
| Inventory Viewer | **Added** | |
| Scoreboard | Skipped | Moving/restyling the vanilla scoreboard needs more HUD hooks; not started. |

## Performance / OptiFine-type

| Item | Status | Notes |
|---|---|---|
| FPS Boost, Sodium-type rendering | Skipped | Install **Sodium** alongside Evade. Rewriting the renderer isn't realistic, and bundling other mods isn't allowed. |
| Lithium-type optimisations | Skipped | Install **Lithium** alongside. |
| Entity Culling | Skipped | Install **Entity Culling** alongside. |
| Memory optimisation | Skipped | Install **FerriteCore** alongside. |
| Connected textures | Skipped | Needs a mod like Continuity plus a pack. |
| OptiFine itself | Not bundled | As requested. |

Compatibility of Evade with Sodium/Iris/Lithium hasn't been tested yet.

## Totals

- Evade before v0.4.0: 38 mods.
- Added in v0.4.0: 50 (49 mods + the Printer).
- Evade v0.4.0: 88 mods.
- By category: Combat 19, Movement 16, Visuals 9, World 8, Player 9, HUD 18, Mods 9.
- In-game test suite: 44 checks, all passing (27 of them new in v0.4.0, including the Printer with real
  Litematica 0.26.16 and the texture pack loading as compatible).
