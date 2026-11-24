/*
 * Evade Server API - Copyright (c) 2026 Evade Client contributors
 * SPDX-License-Identifier: MIT (see LICENSE)
 */
package net.evadeclient.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The Evade Server API for other plugins. Add {@code depend: [EvadeServerAPI]} (or {@code softdepend}) to your
 * plugin.yml and call these from the main thread.
 *
 * <p>Every sending method returns {@code false} and sends nothing when the player isn't using Evade Client, so it is
 * safe to call them for everyone. Players count as Evade players once their client has said hello, a moment after
 * joining; listen for {@link EvadeHelloEvent} to send things as soon as that happens.
 *
 * <p>Colours are {@code 0xRRGGBB} ints (e.g. {@code 0xFF5555}); {@link #color(String)} turns {@code "#FF5555"} into one.
 */
public final class EvadeApi {
    private EvadeApi() {}

    private static EvadePlugin plugin() {
        return EvadePlugin.instance();
    }

    // ---------------------------------------------------------------- players

    /** Whether {@code player} is using Evade Client (its client said hello on this server). */
    public static boolean isEvade(Player player) {
        return plugin().isEvade(player);
    }

    /** The player's Evade Client version, or {@code null} when they aren't using Evade Client. */
    public static String version(Player player) {
        EvadeClientInfo i = plugin().info(player);
        return i == null ? null : i.version();
    }

    /** Everything the player's client reported, or {@code null} when they aren't using Evade Client. */
    public static EvadeClientInfo info(Player player) {
        return plugin().info(player);
    }

    /** Whether the player's client listed {@code feature} in its hello (e.g. {@code "waypoint"}). */
    public static boolean hasFeature(Player player, String feature) {
        EvadeClientInfo i = plugin().info(player);
        return i != null && i.hasFeature(feature);
    }

    /** The online players using Evade Client. */
    public static List<Player> players() {
        return plugin().evadePlayers();
    }

    /** {@code "#RRGGBB"} (or {@code "RRGGBB"}) to {@code 0xRRGGBB}; white when it can't be read. */
    public static int color(String hex) {
        return Json.parseColor(hex, 0xFFFFFF);
    }

    /**
     * Sends a raw message on {@code evade:api}: {@code fields} plus {@code "type": type}. For message types this
     * class doesn't wrap yet.
     */
    public static boolean send(Player player, String type, JsonObject fields) {
        JsonObject msg = fields == null ? new JsonObject() : fields.deepCopy();
        msg.addProperty("type", type);
        return plugin().send(player, msg);
    }

    // ---------------------------------------------------------------- waypoints

    /** Adds (or replaces, same {@code id}) a waypoint at {@code location}, in that location's world, with a beam. */
    public static boolean sendWaypoint(Player player, String id, String name, Location location, int color) {
        World w = location.getWorld();
        return sendWaypoint(player, id, name, location.getX(), location.getY(), location.getZ(), color,
                w == null ? null : dimension(w), true);
    }

    /**
     * Adds (or replaces, same {@code id}) a waypoint.
     *
     * @param dimension the dimension id it belongs to (e.g. {@code "minecraft:the_nether"}), or {@code null} for any
     * @param beam      whether the client draws a light beam at it
     */
    public static boolean sendWaypoint(Player player, String id, String name, double x, double y, double z, int color,
                                       String dimension, boolean beam) {
        JsonObject m = Json.message("waypoint.add");
        m.addProperty("id", id);
        m.addProperty("name", name);
        m.addProperty("x", x);
        m.addProperty("y", y);
        m.addProperty("z", z);
        m.addProperty("color", Json.color(color));
        if (dimension != null) m.addProperty("dimension", dimension);
        m.addProperty("beam", beam);
        return plugin().send(player, m);
    }

    public static boolean removeWaypoint(Player player, String id) {
        JsonObject m = Json.message("waypoint.remove");
        m.addProperty("id", id);
        return plugin().send(player, m);
    }

    /** Removes every waypoint the server sent. */
    public static boolean clearWaypoints(Player player) {
        return plugin().send(player, Json.message("waypoint.clear"));
    }

    // ---------------------------------------------------------------- cooldowns

    /**
     * Shows a cooldown on the player's HUD.
     *
     * @param icon an item id such as {@code "minecraft:ender_pearl"}
     */
    public static boolean setCooldown(Player player, String id, String name, String icon, double seconds) {
        JsonObject m = Json.message("cooldown.set");
        m.addProperty("id", id);
        m.addProperty("name", name);
        m.addProperty("icon", icon);
        m.addProperty("seconds", seconds);
        return plugin().send(player, m);
    }

    /** Removes one cooldown. */
    public static boolean clearCooldown(Player player, String id) {
        JsonObject m = Json.message("cooldown.clear");
        m.addProperty("id", id);
        return plugin().send(player, m);
    }

    /** Removes every cooldown the server set. */
    public static boolean clearCooldowns(Player player) {
        return plugin().send(player, Json.message("cooldown.clear"));
    }

    // ---------------------------------------------------------------- notifications and titles

    /** Shows a notification toast for 5 seconds at level {@code info}. */
    public static boolean notify(Player player, String title, String text) {
        return notify(player, title, text, 5, "info");
    }

    /**
     * Shows a notification toast.
     *
     * @param level e.g. {@code "info"}, {@code "success"}, {@code "warning"} or {@code "error"}
     */
    public static boolean notify(Player player, String title, String text, double seconds, String level) {
        JsonObject m = Json.message("notification");
        m.addProperty("title", title);
        m.addProperty("text", text);
        m.addProperty("seconds", seconds);
        m.addProperty("level", level == null ? "info" : level);
        return plugin().send(player, m);
    }

    /** Shows an Evade-styled title. Times are in ticks (20 per second). */
    public static boolean title(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        JsonObject m = Json.message("title");
        m.addProperty("title", title);
        m.addProperty("subtitle", subtitle == null ? "" : subtitle);
        m.addProperty("fadeIn", fadeIn);
        m.addProperty("stay", stay);
        m.addProperty("fadeOut", fadeOut);
        return plugin().send(player, m);
    }

    // ---------------------------------------------------------------- teams, borders, beams, holograms

    /** Marks {@code teammates} as the viewer's team (highlighted in {@code color}). Replaces the previous team. */
    public static boolean setTeam(Player viewer, Collection<UUID> teammates, int color) {
        JsonObject m = Json.message("team.set");
        m.add("players", Json.strings(teammates));
        m.addProperty("color", Json.color(color));
        return plugin().send(viewer, m);
    }

    /** {@link #setTeam(Player, Collection, int)} with players instead of UUIDs. */
    public static boolean setTeamPlayers(Player viewer, Collection<? extends Player> teammates, int color) {
        return setTeam(viewer, teammates.stream().map(Player::getUniqueId).toList(), color);
    }

    public static boolean clearTeam(Player viewer) {
        return plugin().send(viewer, Json.message("team.clear"));
    }

    /** Draws a rectangular border (block coordinates) in {@code color}. Replaces the previous border. */
    public static boolean setBorder(Player player, double minX, double minZ, double maxX, double maxZ, int color) {
        JsonObject m = Json.message("border.set");
        m.addProperty("minX", Math.min(minX, maxX));
        m.addProperty("minZ", Math.min(minZ, maxZ));
        m.addProperty("maxX", Math.max(minX, maxX));
        m.addProperty("maxZ", Math.max(minZ, maxZ));
        m.addProperty("color", Json.color(color));
        return plugin().send(player, m);
    }

    public static boolean clearBorder(Player player) {
        return plugin().send(player, Json.message("border.clear"));
    }

    /** Adds (or moves, same {@code id}) a light beam. */
    public static boolean beam(Player player, String id, double x, double y, double z, int color) {
        JsonObject m = Json.message("beam.add");
        m.addProperty("id", id);
        m.addProperty("x", x);
        m.addProperty("y", y);
        m.addProperty("z", z);
        m.addProperty("color", Json.color(color));
        return plugin().send(player, m);
    }

    public static boolean beam(Player player, String id, Location location, int color) {
        return beam(player, id, location.getX(), location.getY(), location.getZ(), color);
    }

    public static boolean removeBeam(Player player, String id) {
        JsonObject m = Json.message("beam.remove");
        m.addProperty("id", id);
        return plugin().send(player, m);
    }

    /** Adds (or replaces, same {@code id}) a client-side hologram: floating text lines, top line first. */
    public static boolean hologram(Player player, String id, double x, double y, double z, List<String> lines) {
        JsonObject m = Json.message("hologram.add");
        m.addProperty("id", id);
        m.addProperty("x", x);
        m.addProperty("y", y);
        m.addProperty("z", z);
        JsonArray a = Json.strings(lines);
        m.add("lines", a);
        return plugin().send(player, m);
    }

    public static boolean hologram(Player player, String id, Location location, List<String> lines) {
        return hologram(player, id, location.getX(), location.getY(), location.getZ(), lines);
    }

    public static boolean removeHologram(Player player, String id) {
        JsonObject m = Json.message("hologram.remove");
        m.addProperty("id", id);
        return plugin().send(player, m);
    }

    // ---------------------------------------------------------------- rules

    /**
     * Switches off Evade mods (names as in the Evade menu, e.g. {@code "Freelook"}) for this player, on top of
     * config.yml's {@code disabled-mods}. They stay off until {@link #resetRules(Player)} or the player leaves.
     */
    public static boolean disableMods(Player player, List<String> mods) {
        EvadePlugin p = plugin();
        p.rulesOf(player).disabled.addAll(mods);
        return p.sendRules(player);
    }

    /**
     * Limits one number setting of one Evade mod (a number is a maximum; {"min","max"} sets a range) for this player (on top of config.yml's {@code settings}).
     *
     * @param value a Boolean, Number or String (or a list of them)
     */
    public static boolean setSetting(Player player, String mod, String setting, Object value) {
        EvadePlugin p = plugin();
        p.rulesOf(player).settings.computeIfAbsent(mod, k -> new java.util.LinkedHashMap<>()).put(setting, value);
        return p.sendRules(player);
    }

    /** Limits several number settings at once: {@code {"Mod": {"Setting": value}}}. */
    public static boolean setSettings(Player player, Map<String, Map<String, Object>> settings) {
        EvadePlugin p = plugin();
        PluginConfig.merge(p.rulesOf(player).settings, settings);
        return p.sendRules(player);
    }

    /** Drops everything set with disableMods/setSetting for this player; config.yml's rules still apply. */
    public static boolean resetRules(Player player) {
        EvadePlugin p = plugin();
        p.clearRules(player);
        return p.sendRules(player);
    }

    // ---------------------------------------------------------------- v2: prompts, widgets, player-card entries
    // Clients from Evade 2.5 on list these features in their hello ("prompt", "transfer", "economy", "timer",
    // "interaction"); older clients ignore the messages, so check hasFeature first when it matters.

    /**
     * Asks the player to type something ("Name your island"). They can always cancel. {@code onAnswer} (main thread)
     * gets what they typed, or null when they cancelled; {@link EvadePromptReplyEvent} fires too. {@code max} is the
     * longest answer (1 to 256). Returns false when the player's client can't show prompts.
     */
    public static boolean promptInput(Player player, String id, String title, String text, String placeholder, int max,
                                      java.util.function.Consumer<String> onAnswer) {
        if (!hasFeature(player, "prompt")) return false;
        JsonObject m = Json.message("prompt.input");
        m.addProperty("id", id);
        m.addProperty("title", title);
        m.addProperty("text", text);
        if (placeholder != null) m.addProperty("placeholder", placeholder);
        m.addProperty("max", Math.max(1, Math.min(256, max)));
        plugin().expect(player, "prompt.input", id, onAnswer);
        return plugin().send(player, m);
    }

    /**
     * Offers the player a move to another server: they see the address (and {@code name}, {@code reason}) and only go
     * if they click Join. {@code onAnswer} (main thread) gets true or false. For servers behind one proxy, use the
     * proxy's own transfer instead; this is for sending players to a different address.
     */
    public static boolean promptTransfer(Player player, String id, String address, String name, String reason,
                                         java.util.function.Consumer<Boolean> onAnswer) {
        if (!hasFeature(player, "transfer")) return false;
        JsonObject m = Json.message("prompt.transfer");
        m.addProperty("id", id);
        m.addProperty("address", address);
        if (name != null) m.addProperty("name", name);
        if (reason != null) m.addProperty("reason", reason);
        plugin().expect(player, "prompt.transfer", id, onAnswer == null ? null : v -> onAnswer.accept("true".equals(v)));
        return plugin().send(player, m);
    }

    /** A balance line on the player's HUD ("Coins" "1,250"); up to 4. An empty value removes it. */
    public static boolean setBalance(Player player, String id, String label, String value, int color) {
        JsonObject m = Json.message("economy.set");
        m.addProperty("id", id);
        m.addProperty("label", label);
        m.addProperty("value", value);
        m.addProperty("color", Json.color(color));
        return plugin().send(player, m);
    }

    /** Removes a balance line ({@code id} null: all of them). */
    public static boolean clearBalance(Player player, String id) {
        JsonObject m = Json.message("economy.clear");
        if (id != null) m.addProperty("id", id);
        return plugin().send(player, m);
    }

    /**
     * A timer on the player's HUD: counting down {@code seconds} to zero, or ({@code countUp}) up from {@code seconds}.
     * Up to 4, at most 24 hours. The client counts, so send it once.
     */
    public static boolean setTimer(Player player, String id, String label, double seconds, boolean countUp, int color) {
        JsonObject m = Json.message("timer.set");
        m.addProperty("id", id);
        m.addProperty("label", label);
        m.addProperty("seconds", seconds);
        m.addProperty("mode", countUp ? "up" : "down");
        m.addProperty("color", Json.color(color));
        return plugin().send(player, m);
    }

    /** Removes a timer ({@code id} null: all of them). */
    public static boolean clearTimer(Player player, String id) {
        JsonObject m = Json.message("timer.clear");
        if (id != null) m.addProperty("id", id);
        return plugin().send(player, m);
    }

    /**
     * An entry on the player's player cards (the Evade list of players in the tab list): {@code label}, and one of
     * your server's commands to run when they click it, with {@code {player}} for the card's player, e.g.
     * {@code addInteraction(p, "trade", "Trade", "trade {player}")}. Up to 8. The player's click sends exactly that one
     * command, as if they had typed it, so your usual permission checks apply.
     */
    public static boolean addInteraction(Player player, String id, String label, String command) {
        JsonObject m = Json.message("interaction.add");
        m.addProperty("id", id);
        m.addProperty("label", label);
        m.addProperty("command", command);
        return plugin().send(player, m);
    }

    public static boolean removeInteraction(Player player, String id) {
        JsonObject m = Json.message("interaction.remove");
        m.addProperty("id", id);
        return plugin().send(player, m);
    }

    public static boolean clearInteractions(Player player) {
        return plugin().send(player, Json.message("interaction.clear"));
    }

    // ---------------------------------------------------------------- misc

    /** Sets the Discord Rich Presence text the client shows while on this server. */
    public static boolean discord(Player player, String text) {
        JsonObject m = Json.message("discord");
        m.addProperty("text", text);
        return plugin().send(player, m);
    }

    // ---------------------------------------------------------------- partner cosmetics

    /** Whether partner cosmetics are on (config.yml has {@code cosmetics.partner-id} and {@code cosmetics.partner-key}). */
    public static boolean partnerCosmeticsEnabled() {
        return PartnerCosmetics.configured(plugin().settings());
    }

    /**
     * Gives {@code player} one of this server's own partner cosmetics on evadeclient.com (an item whose partner is the
     * {@code cosmetics.partner-id} in config.yml). The player doesn't need to be online or using Evade Client right now.
     *
     * <p>Asynchronous: completes with {@code true} when it was granted, {@code false} when the player already owned it,
     * or exceptionally (partner cosmetics off, not one of your items, network error...). It completes on a background
     * thread: switch to the main thread (the Bukkit scheduler) before touching players or worlds.
     */
    public static CompletableFuture<Boolean> grantPartnerCosmetic(OfflinePlayer player, String itemId) {
        return grantPartnerCosmetic(player == null ? null : player.getUniqueId(), itemId);
    }

    /** {@link #grantPartnerCosmetic(OfflinePlayer, String)} by player UUID. */
    public static CompletableFuture<Boolean> grantPartnerCosmetic(UUID player, String itemId) {
        return PartnerCosmetics.grant(plugin().settings(), player, itemId);
    }

    /**
     * The dimension id the client sees for a world: the main worlds are {@code minecraft:overworld},
     * {@code minecraft:the_nether} and {@code minecraft:the_end}; extra worlds use their own key.
     */
    static String dimension(World world) {
        return world.getKey().toString();
    }
}
