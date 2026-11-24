/*
 * Evade Server API - Copyright (c) 2026 Evade Client contributors
 * SPDX-License-Identifier: MIT (see LICENSE)
 */
package net.evadeclient.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * The plugin: listens for {@code evade:hello}, remembers which players use Evade Client, sends them the configured
 * mod rules and a welcome notification, and carries the messages of {@link EvadeApi} on {@code evade:api}.
 */
public final class EvadePlugin extends JavaPlugin implements Listener, PluginMessageListener {
    /** Server to client: one UTF-8 JSON object per message, {@code {"type": ...}}. */
    public static final String API_CHANNEL = "evade:api";
    /** Server to client, older clients: {@code {"disable": [...]}}. */
    public static final String RULES_CHANNEL = "evade:rules";
    /** Client to server: {@code {"client":"evade","version":"x","api":1,"features":[...]}}. */
    public static final String HELLO_CHANNEL = "evade:hello";

    private static volatile EvadePlugin instance;

    /** Rules other plugins set for one player through the API (added to config.yml's rules). */
    static final class PlayerRules {
        final Set<String> disabled = new LinkedHashSet<>();
        final PluginConfig.Settings settings = new PluginConfig.Settings();
    }

    private final Map<UUID, EvadeClientInfo> clients = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerRules> playerRules = new ConcurrentHashMap<>();
    /** Players whose client only registered evade:rules (an Evade Client from before the hello message). */
    private final Set<UUID> legacy = ConcurrentHashMap.newKeySet();
    private volatile PluginConfig config;

    static EvadePlugin instance() {
        EvadePlugin p = instance;
        if (p == null) throw new IllegalStateException("EvadeServerAPI is not enabled (add `depend: [EvadeServerAPI]` to your plugin.yml)");
        return p;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        loadSettings();

        var messenger = getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(this, API_CHANNEL);
        messenger.registerOutgoingPluginChannel(this, RULES_CHANNEL);
        messenger.registerIncomingPluginChannel(this, HELLO_CHANNEL, this);
        getServer().getPluginManager().registerEvents(this, this);

        PluginCommand command = getCommand("evade");
        if (command != null) {
            EvadeCommand executor = new EvadeCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
    }

    @Override
    public void onDisable() {
        var messenger = getServer().getMessenger();
        messenger.unregisterIncomingPluginChannel(this);
        messenger.unregisterOutgoingPluginChannel(this);
        clients.clear();
        playerRules.clear();
        legacy.clear();
        instance = null;
    }

    /** Re-reads config.yml. */
    void loadSettings() {
        reloadConfig();
        config = new PluginConfig(getConfig());
    }

    /** Re-reads config.yml and re-sends the rules to every Evade player. */
    void reload() {
        loadSettings();
        for (Player p : getServer().getOnlinePlayers()) if (isEvade(p) || legacy.contains(p.getUniqueId())) sendRules(p);
    }

    PluginConfig settings() {
        return config;
    }

    // ---- players ----

    boolean isEvade(Player p) {
        return p != null && clients.containsKey(p.getUniqueId());
    }

    EvadeClientInfo info(Player p) {
        return p == null ? null : clients.get(p.getUniqueId());
    }

    boolean isLegacy(Player p) {
        return p != null && legacy.contains(p.getUniqueId());
    }

    List<Player> evadePlayers() {
        List<Player> out = new ArrayList<>();
        for (Player p : getServer().getOnlinePlayers()) if (isEvade(p)) out.add(p);
        return out;
    }

    // ---- sending ----

    /** Sends one {@code evade:api} message. False (and nothing sent) when {@code p} isn't using Evade Client. */
    boolean send(Player p, JsonObject message) {
        if (!isEvade(p) || !p.isOnline()) return false;
        p.sendPluginMessage(this, API_CHANNEL, message.toString().getBytes(StandardCharsets.UTF_8));
        return true;
    }

    PlayerRules rulesOf(Player p) {
        return playerRules.computeIfAbsent(p.getUniqueId(), k -> new PlayerRules());
    }

    void clearRules(Player p) {
        playerRules.remove(p.getUniqueId());
    }

    /** config.yml's rules for the player's world plus whatever other plugins set for this player. */
    private Set<String> disabledFor(Player p) {
        Set<String> disabled = new LinkedHashSet<>(config.disabledModsFor(p.getWorld().getName()));
        PlayerRules extra = playerRules.get(p.getUniqueId());
        if (extra != null) disabled.addAll(extra.disabled);
        return disabled;
    }

    /** Sends the player's full rule set (it replaces whatever the client had before). */
    boolean sendRules(Player p) {
        if (p == null || !p.isOnline()) return false;
        Set<String> disabled = disabledFor(p);
        if (isEvade(p)) {
            PluginConfig.Settings settings = config.settingsFor(p.getWorld().getName());
            PlayerRules extra = playerRules.get(p.getUniqueId());
            if (extra != null) PluginConfig.merge(settings, extra.settings);

            JsonObject msg = Json.message("rules");
            msg.add("disable", Json.strings(disabled));
            msg.add("settings", Json.value(settings));
            return send(p, msg);
        }
        if (legacy.contains(p.getUniqueId())) {
            JsonObject msg = new JsonObject();
            msg.add("disable", Json.strings(disabled));
            p.sendPluginMessage(this, RULES_CHANNEL, msg.toString().getBytes(StandardCharsets.UTF_8));
            return true;
        }
        return false;
    }

    // ---- incoming ----

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!HELLO_CHANNEL.equals(channel)) return;
        EvadeClientInfo info;
        try {
            info = parseHello(message);
        } catch (RuntimeException e) {
            getLogger().log(Level.FINE, "Ignored a malformed evade:hello from " + player.getName(), e);
            return;
        }
        if (info == null) return;

        boolean first = clients.put(player.getUniqueId(), info) == null;
        legacy.remove(player.getUniqueId());
        if (first && config.logJoins)
            getLogger().info(player.getName() + " is using Evade Client " + info.version() + " (API " + info.api() + ", features " + info.features() + ")");

        sendRules(player);
        if (first && config.welcomeEnabled)
            EvadeApi.notify(player, config.welcomeTitle, config.welcomeText, config.welcomeSeconds, config.welcomeLevel);
        getServer().getPluginManager().callEvent(new EvadeHelloEvent(player, info, first));
    }

    static EvadeClientInfo parseHello(byte[] body) {
        JsonElement root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
        if (!root.isJsonObject()) return null;
        JsonObject o = root.getAsJsonObject();
        if (!o.has("client") || !"evade".equalsIgnoreCase(o.get("client").getAsString())) return null;
        String version = o.has("version") && !o.get("version").isJsonNull() ? o.get("version").getAsString() : "unknown";
        int api = o.has("api") && o.get("api").isJsonPrimitive() ? o.get("api").getAsInt() : 1;
        Set<String> features = new LinkedHashSet<>();
        if (o.has("features") && o.get("features").isJsonArray()) {
            JsonArray a = o.getAsJsonArray("features");
            for (JsonElement e : a) if (e.isJsonPrimitive()) features.add(e.getAsString().toLowerCase(Locale.ROOT));
        }
        return new EvadeClientInfo(version.length() > 64 ? version.substring(0, 64) : version, api, features, System.currentTimeMillis());
    }

    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        if (!RULES_CHANNEL.equals(event.getChannel()) || !config.legacyRules) return;
        Player p = event.getPlayer();
        // Give a current client time to say hello; only an older one (no hello) gets the legacy message.
        getServer().getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline() || isEvade(p)) return;
            legacy.add(p.getUniqueId());
            sendRules(p);
        }, config.legacyRulesDelayTicks);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player p = event.getPlayer();
        if (isEvade(p) || isLegacy(p)) sendRules(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        clients.remove(id);
        playerRules.remove(id);
        legacy.remove(id);
    }

    /** For /evade list: name -> client info, in join order of the online list. */
    Map<Player, EvadeClientInfo> snapshot() {
        Map<Player, EvadeClientInfo> out = new LinkedHashMap<>();
        Collection<? extends Player> online = getServer().getOnlinePlayers();
        for (Player p : online) {
            EvadeClientInfo i = clients.get(p.getUniqueId());
            if (i != null) out.put(p, i);
        }
        return out;
    }
}
