# Store listings for the Evade Server API plugin

Text and settings ready to paste when publishing `EvadeServerAPI-1.2.0.jar` (build it with
`./gradlew :server-plugin:build`). Publishing needs the owner's Modrinth and Hangar accounts; nothing here is
uploaded automatically.

## Common

- **Name:** Evade Server API
- **Slug / id:** `evade-server-api`
- **Licence:** MIT
- **Source:** the `server-plugin/` folder of the Evade repository
- **Platforms:** Paper 1.21.x and Paper forks (Purpur, Folia not tested), Java 21
- **Categories:** Utility, Social (Modrinth: `utility`, `social`; Hangar: Admin Tools, Gameplay)
- **Version:** 1.2.0, release channel. Game versions: 1.21 to 1.21.11.
- **Icon:** the Evade logo mark (`src/main/resources/assets/evade/icon.png` in the client), 512x512.

## Summary (one line)

Show waypoints, cooldowns, timers, balances, prompts and more to players using Evade Client. Players without it
aren't affected.

## Description

**Evade Server API** lets your server talk to players who use [Evade Client](https://evadeclient.com), a free
Minecraft client. Everything is visual or informational and chosen by your server; Evade never reads a player's
mod list or sends you anything beyond a short hello (client version and the features it supports).

What you can show:

- **Waypoints** with beams, **holograms** and **beams** at any spot
- **Cooldowns** in the player's Cooldown HUD, with an item icon
- **Notifications** and **titles** in Evade's own style
- **Team highlights** (only for teammates the player can already see) and **arena borders**
- **Balances** ("Coins 1,250") and **timers** (count down to an event, or up) on the HUD
- **Prompts**: ask the player to type a name or answer, or offer a move to another server address. The player
  always sees what's asked and can cancel; a transfer only happens if they click Join.
- **Player-card entries**: add "Trade", "Duel" or "Invite" to Evade's player cards; a click runs your own command
- **Discord status** text while the player is on your server
- **Rules**: switch off Evade mods you don't allow (for example Freelook) or cap settings (for example zoom)

Players without Evade Client see nothing different: messages only go to clients that said hello. Every API method
returns `false` for them, so you can call it for everyone.

For developers: add the jar as a `compileOnly` dependency, `depend: [EvadeServerAPI]` in your plugin.yml, and use
`net.evadeclient.server.EvadeApi`. The README lists every method and the plain-JSON protocol (any server software
can speak it).

## Changelog for 1.2.0

- New: partner cosmetics. Evade partner servers can give players their own partner cosmetics with
  `EvadeApi.grantPartnerCosmetic(player, item)` or `/evade cosmetic grant <player> <item>` (needs the partner id and
  key in config.yml; off without them)

## Changelog for 1.1.0

- New: `promptInput` and `promptTransfer` (answers come back on `evade:reply`, as a callback and an
  `EvadePromptReplyEvent`)
- New: HUD balances (`setBalance`) and timers (`setTimer`)
- New: player-card entries (`addInteraction`)
- Needs Evade Client 2.5 or later for the new features; older clients ignore them

## Screenshots to take

1. A waypoint with a beam and a hologram in a lobby
2. The HUD with a balance and a round timer
3. A prompt ("Name your island") over the game
4. A player card with a server entry ("Trade")
