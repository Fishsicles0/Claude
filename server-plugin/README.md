# Evade Server API (Paper plugin)

A small, open-source (MIT) Paper plugin that lets a Minecraft server talk to players using **Evade Client**:
waypoints, HUD cooldowns, notifications, titles, team highlights, borders, beams, holograms, Discord status text,
and server rules that switch Evade mods off (for example Freelook) or limit their number settings (for example a maximum zoom).

Players without Evade Client are not affected: every message is only sent to players whose client said hello.

- Server software: Paper 1.21.x (or a Paper fork), Java 21
- Channels: `evade:hello` (client to server), `evade:api` and `evade:rules` (server to client)

## Install (server owners)

1. Build the jar (see [Building](#building)) or download `EvadeServerAPI-<version>.jar` from a release.
2. Put it in your server's `plugins/` folder and restart the server.
3. Edit `plugins/EvadeServerAPI/config.yml` (created on first start), then run `/evade reload`.
4. Join with Evade Client and run `/evade list` to see yourself listed, or `/evade notify` for a test notification.

Behind Velocity or BungeeCord, install the plugin on the backend (Paper) servers. Proxies pass the `evade:*` plugin
messages through, and each backend sends its own rules when the player arrives.

### config.yml

```yaml
# Mods switched off for every Evade player (names as in the Evade menu, case doesn't matter).
disabled-mods:
  - Freelook

# Number limits for every Evade player: <Mod>: { <Setting>: <max> or {min: x, max: y} } (on/off and mode settings are ignored)
settings:
  Zoom:
    Smooth: true

# Per-world overrides: a world's disabled-mods REPLACES the global list; its settings go on top of the global ones.
worlds:
  pvp_arena:
    disabled-mods: [Freelook, Minimap]

# Notification shown to each Evade player when they join.
welcome:
  enabled: true
  title: "Evade features enabled"
  text: "This server supports Evade waypoints, cooldowns and notifications."
  seconds: 5
  level: info

# Older Evade Clients (from before the hello message) still get disabled-mods on evade:rules.
legacy-rules: true
legacy-rules-delay-ticks: 40

log-joins: true
```

Rules are re-sent when a player changes world and on `/evade reload`. Mods switched off by the server come back
on their own when the player leaves.

### Commands and permissions

| Command | Permission | What it does |
| --- | --- | --- |
| `/evade list` | `evade.command.list` | Online players using Evade Client, with version and API level |
| `/evade info <player>` | `evade.command.list` | A player's client version and features |
| `/evade waypoint [player]` | `evade.command.test` | Sends a test waypoint (at your position, or the player's) |
| `/evade notify [player] [text]` | `evade.command.test` | Sends a test notification |
| `/evade reload` | `evade.command.reload` | Reloads config.yml and re-sends the rules |

`evade.admin` grants all of them. All default to operators.

## API for other plugins

Add the jar as a `compileOnly` dependency of your plugin and declare the dependency in your `plugin.yml`:

```yaml
depend: [EvadeServerAPI]      # or softdepend, and check that the plugin is enabled before calling it
```

Everything is in `net.evadeclient.server.EvadeApi` (static methods, main thread). Every sending method returns
`false` and sends nothing when the player isn't using Evade Client, so you can call them for everyone.

```java
import net.evadeclient.server.EvadeApi;
import net.evadeclient.server.EvadeHelloEvent;

// React as soon as a player's Evade Client says hello (a moment after join).
@EventHandler
public void onEvadeHello(EvadeHelloEvent event) {
    Player p = event.getPlayer();
    getLogger().info(p.getName() + " uses Evade " + event.getInfo().version());

    // Waypoint with a beam, in the location's world. Colours are 0xRRGGBB (or EvadeApi.color("#55FF55")).
    EvadeApi.sendWaypoint(p, "spawn", "Spawn", p.getWorld().getSpawnLocation(), 0x55FF55);

    // Explicit coordinates, dimension and beam.
    EvadeApi.sendWaypoint(p, "fortress", "Fortress", 120, 64, -340, 0xFF5555, "minecraft:the_nether", false);
}

// Is the player on Evade, and which version?
if (EvadeApi.isEvade(player)) {
    String version = EvadeApi.version(player);
}

// HUD cooldown with an item icon.
EvadeApi.setCooldown(player, "pearl", "Ender Pearl", "minecraft:ender_pearl", 15);
EvadeApi.clearCooldown(player, "pearl");

// Notification toast and title (title times in ticks).
EvadeApi.notify(player, "Capture point", "Your team captured B", 4, "success");
EvadeApi.title(player, "Round 2", "Fight!", 10, 40, 10);

// Highlight teammates, draw an arena border, a beam and a hologram.
EvadeApi.setTeam(player, List.of(ally1.getUniqueId(), ally2.getUniqueId()), 0x5555FF);
EvadeApi.setBorder(player, -100, -100, 100, 100, 0xFFAA00);
EvadeApi.beam(player, "flag", flagLocation, 0xFF5555);
EvadeApi.hologram(player, "shop", shopLocation, List.of("Shop", "Right-click to open"));

// Server rules for one player, on top of config.yml (sent as one message that replaces the previous rules).
EvadeApi.disableMods(player, List.of("Freelook", "Minimap"));
EvadeApi.setSetting(player, "Zoom", "Smooth", true);
EvadeApi.resetRules(player);   // back to just config.yml's rules

// Discord status text while on this server.
EvadeApi.discord(player, "Playing Bedwars on example.net");

// Clean up.
EvadeApi.removeWaypoint(player, "spawn");
EvadeApi.clearWaypoints(player);
EvadeApi.clearTeam(player);
EvadeApi.clearBorder(player);
EvadeApi.removeBeam(player, "flag");
EvadeApi.removeHologram(player, "shop");

// v2 (Evade 2.5+, plugin 1.1+): prompts, HUD widgets and player-card entries.
EvadeApi.promptInput(player, "island", "Name your island", "Up to 24 letters", "My island", 24,
        name -> { if (name != null) islands.rename(player, name); });       // null: they cancelled
EvadeApi.promptTransfer(player, "lobby2", "lobby2.example.net", "Lobby 2", "Lobby 1 is full",
        accepted -> getLogger().info(player.getName() + (accepted ? " moved" : " stayed")));
EvadeApi.setBalance(player, "coins", "Coins", "1,250", 0xFFD866);
EvadeApi.setTimer(player, "round", "Round ends", 300, false, 0xFFFFFF);   // counts down on the client
EvadeApi.addInteraction(player, "trade", "Trade", "trade {player}");      // on every player card; one click = one command

// Anything not wrapped yet: a raw evade:api message.
JsonObject fields = new JsonObject();
fields.addProperty("id", "x");
EvadeApi.send(player, "some.type", fields);
```

## Protocol

All messages are plugin messages whose body is UTF-8 JSON (no length prefix), so any server software can speak it.

Client to server, `evade:hello`, sent when the player joins:

```json
{"client": "evade", "version": "2.0.1", "api": 1, "features": ["waypoint", "cooldown", "notification"]}
```

Server to client, `evade:api`, one object per message with a `type`:

| type | fields |
| --- | --- |
| `waypoint.add` | `id`, `name`, `x`, `y`, `z`, `color` (`"#RRGGBB"` or int), `dimension`?, `beam`? |
| `waypoint.remove` / `waypoint.clear` | `id` / - |
| `cooldown.set` / `cooldown.clear` | `id`, `name`, `icon` (item id), `seconds` / `id`? |
| `notification` | `title`, `text`, `seconds`, `level` |
| `title` | `title`, `subtitle`, `fadeIn`, `stay`, `fadeOut` (ticks) |
| `team.set` / `team.clear` | `players` (UUID strings), `color` / - |
| `border.set` / `border.clear` | `minX`, `minZ`, `maxX`, `maxZ`, `color` / - |
| `beam.add` / `beam.remove` | `id`, `x`, `y`, `z`, `color` / `id` |
| `hologram.add` / `hologram.remove` | `id`, `x`, `y`, `z`, `lines` / `id` |
| `rules` | `disable` (mod names), `settings` (`{"Mod": {"Setting": value}}`); replaces the previous rules |
| `discord` | `text` |
| `prompt.input` | `id`, `title`, `text`, `placeholder`?, `max` (1-256); answered on `evade:reply` |
| `prompt.transfer` | `id`, `address`, `name`?, `reason`?; the player sees the address and chooses; answered on `evade:reply` |
| `economy.set` / `economy.clear` | `id`, `label`, `value` (text or number), `color` / `id`? (up to 4) |
| `timer.set` / `timer.clear` | `id`, `label`, `seconds`, `mode` (`down` or `up`), `color` / `id`? (up to 4, 24 h at most) |
| `interaction.add` / `interaction.remove` / `interaction.clear` | `id`, `label`, `command` (`{player}` is the card's player) / `id` / - (up to 8) |

Client to server, `evade:reply` (Evade 2.5+), the answer to a prompt:

```json
{"type": "prompt.input", "id": "island", "value": "Sunny Isle"}
{"type": "prompt.input", "id": "island", "cancelled": true}
{"type": "prompt.transfer", "id": "lobby2", "accepted": true}
```

The plugin only passes on answers to prompts it sent (and not yet answered), to the callback and as an
`EvadePromptReplyEvent`. A prompt shows when the player has no other screen open and can always be cancelled; a
client shows at most 3 waiting and 6 a minute.

Older clients also accept `{"disable": [...]}` on `evade:rules`.

Paper only delivers a plugin message to a client that registered the channel; Evade Client registers `evade:api` and
`evade:rules` when it connects.

## Building

From the repository root:

```sh
./gradlew :server-plugin:build
```

The jar is written to `server-plugin/build/libs/EvadeServerAPI-<version>.jar`. The Paper API is downloaded from
`https://repo.papermc.io/repository/maven-public/`; pass `-PpaperApiVersion=1.21.x-R0.1-SNAPSHOT` to compile against a
different 1.21 version.

This subproject is opt-in: a plain `./gradlew build` of the repository skips it (so the mod still builds on machines
that can't reach the Paper repository). It builds when its tasks are named (`:server-plugin:...`), when Gradle is run
from inside `server-plugin/`, or with `-Pevade.serverPlugin`.

## License

MIT, like the rest of the repository. See [LICENSE](../LICENSE).
