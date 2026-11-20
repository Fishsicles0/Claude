# Evade Client

A Fabric utility client for **Minecraft 1.21.4** (Java 21).

> Most multiplayer servers ban modified clients like this one. Use it in singleplayer,
> on your own server, or on servers that allow it (anarchy servers).

## Building

```bash
./gradlew build          # jar lands in build/libs/evade-client-<version>.jar
./gradlew runClient      # launches a dev Minecraft with the client loaded
```

Install by dropping the jar plus [Fabric API](https://modrinth.com/mod/fabric-api) into
`.minecraft/mods` with the Fabric Loader for 1.21.4.

## Using it

| Action | How |
|---|---|
| Open ClickGUI | **Right Shift** |
| Toggle module | Left-click it |
| Show settings / keybind | Right-click it |
| Set keybind | Click **Bind**, press a key (Esc/Delete clears) |
| Move a panel | Drag its header |

### Chat commands (never sent to the server)

```
.help
.toggle <module>
.bind <module> <key|none>        e.g. .bind flight f
.set <module> <setting> <value>  e.g. .set killaura range 4.2
.modules
.save / .load
```

Config is saved to `config/evade/config.json`.

## Modules

| Category | Module | What it does |
|---|---|---|
| Combat | KillAura | Attacks nearby players/mobs/animals; range, priority, cooldown |
| Combat | AutoClicker | Randomised CPS while holding attack on an entity |
| Movement | Sprint | Always sprint |
| Movement | Flight | Vanilla (abilities) or Velocity mode, adjustable speed |
| Movement | NoFall | Tells the server you're on the ground while falling |
| Movement | Step | Step up 1–3 blocks |
| Render | Fullbright | Client-side night vision |
| Render | ESP | Glowing outlines through walls |
| Player | FastPlace | No block-place delay |
| Player | AutoRespawn | Skip the death screen |
| Client | HUD | Watermark, array list, coords, FPS, rainbow |
| Client | ClickGUI | The menu |

## Adding a module

1. Create a class in `src/main/java/net/evadeclient/module/<category>/` extending `Module`.
2. Add settings with `add(new BooleanSetting(...))`, `NumberSetting`, `ModeSetting`.
3. Put per-tick logic in `onTick()` (only called while enabled and in a world).
4. Register it in `ModuleManager`'s constructor.

It then appears in the ClickGUI and HUD, and its settings save automatically.

## Layout

```
net.evadeclient
├── EvadeClient          entrypoint, wires Fabric events
├── module/              Module base, ModuleManager, modules by category
├── setting/             Boolean / Number / Mode settings
├── gui/                 ClickGUI screen + panels
├── hud/                 in-game overlay
├── command/             '.' chat commands
├── config/              JSON save/load
└── mixin/               ESP outline hook, item-use cooldown accessor
```
