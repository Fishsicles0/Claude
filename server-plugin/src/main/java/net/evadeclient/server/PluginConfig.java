/*
 * Evade Server API - Copyright (c) 2026 Evade Client contributors
 * SPDX-License-Identifier: MIT (see LICENSE)
 */
package net.evadeclient.server;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** An immutable snapshot of config.yml. */
final class PluginConfig {
    /** Mod name -> setting name -> value. */
    static final class Settings extends LinkedHashMap<String, Map<String, Object>> {}

    record WorldRules(List<String> disabledMods, Settings settings) {}

    final List<String> disabledMods;
    final Settings settings;
    /** Keyed by lower-case world name. */
    final Map<String, WorldRules> worlds;

    final boolean welcomeEnabled;
    final String welcomeTitle;
    final String welcomeText;
    final int welcomeSeconds;
    final String welcomeLevel;

    final boolean legacyRules;
    final long legacyRulesDelayTicks;
    final boolean logJoins;

    PluginConfig(ConfigurationSection c) {
        disabledMods = List.copyOf(c.getStringList("disabled-mods"));
        settings = readSettings(c.getConfigurationSection("settings"));

        Map<String, WorldRules> w = new HashMap<>();
        ConfigurationSection worldsSection = c.getConfigurationSection("worlds");
        if (worldsSection != null) {
            for (String world : worldsSection.getKeys(false)) {
                ConfigurationSection ws = worldsSection.getConfigurationSection(world);
                if (ws == null) continue;
                List<String> mods = ws.isList("disabled-mods") ? List.copyOf(ws.getStringList("disabled-mods")) : null;
                w.put(world.toLowerCase(Locale.ROOT), new WorldRules(mods, readSettings(ws.getConfigurationSection("settings"))));
            }
        }
        worlds = Map.copyOf(w);

        welcomeEnabled = c.getBoolean("welcome.enabled", true);
        welcomeTitle = c.getString("welcome.title", "Evade features enabled");
        welcomeText = c.getString("welcome.text", "");
        welcomeSeconds = Math.max(1, c.getInt("welcome.seconds", 5));
        welcomeLevel = c.getString("welcome.level", "info");

        legacyRules = c.getBoolean("legacy-rules", true);
        legacyRulesDelayTicks = Math.max(1, c.getInt("legacy-rules-delay-ticks", 40));
        logJoins = c.getBoolean("log-joins", true);
    }

    /** The disabled-mods list for {@code world} (a world override replaces the global list). */
    List<String> disabledModsFor(String world) {
        WorldRules r = world == null ? null : worlds.get(world.toLowerCase(Locale.ROOT));
        return r != null && r.disabledMods() != null ? r.disabledMods() : disabledMods;
    }

    /** The forced settings for {@code world}: global settings with the world's settings on top. */
    Settings settingsFor(String world) {
        Settings out = new Settings();
        merge(out, settings);
        WorldRules r = world == null ? null : worlds.get(world.toLowerCase(Locale.ROOT));
        if (r != null) merge(out, r.settings());
        return out;
    }

    static void merge(Settings into, Map<String, Map<String, Object>> from) {
        for (Map.Entry<String, Map<String, Object>> e : from.entrySet())
            into.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>()).putAll(e.getValue());
    }

    private static Settings readSettings(ConfigurationSection s) {
        Settings out = new Settings();
        if (s == null) return out;
        for (String mod : s.getKeys(false)) {
            ConfigurationSection ms = s.getConfigurationSection(mod);
            if (ms == null) continue;
            Map<String, Object> values = new LinkedHashMap<>();
            for (String setting : ms.getKeys(false)) {
                Object v = ms.get(setting);
                if (v instanceof ConfigurationSection) continue; // nested sections aren't settings
                if (v instanceof List<?> l) v = new ArrayList<>(l);
                values.put(setting, v);
            }
            out.put(mod, values);
        }
        return out;
    }
}
