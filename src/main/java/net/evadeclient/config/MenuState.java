package net.evadeclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Module;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Small menu-wide state that belongs to this PC rather than to a profile: pinned mods, whether the first-run
 * setup has been shown, and a performance tune waiting for the next world. Saved in {@code config/evade/menu.json}.
 * Profiles and share codes never carry it.
 */
public final class MenuState {
    private static final Set<String> pinned = new LinkedHashSet<>();
    private static boolean setupDone;
    private static boolean tunePending;
    private static boolean loaded;

    private MenuState() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("menu.json");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        Path f = file();
        if (!Files.exists(f)) return;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return;
            JsonObject o = root.getAsJsonObject();
            JsonElement p = o.get("pinned");
            if (p != null && p.isJsonArray()) {
                for (JsonElement e : p.getAsJsonArray()) {
                    if (e.isJsonPrimitive() && pinned.size() < 200) pinned.add(e.getAsString());
                }
            }
            setupDone = ConfigManager.bool(o.get("setupDone"), false);
            tunePending = ConfigManager.bool(o.get("tunePending"), false);
        } catch (Exception e) {
            EvadeClient.LOG.warn("Couldn't read {}; starting with no pinned mods", f, e);
        }
    }

    private static void save() {
        JsonObject o = new JsonObject();
        JsonArray p = new JsonArray();
        pinned.forEach(p::add);
        o.add("pinned", p);
        o.addProperty("setupDone", setupDone);
        o.addProperty("tunePending", tunePending);
        try {
            ConfigManager.writeAtomic(file(), ConfigManager.pretty(o));
        } catch (Exception e) {
            EvadeClient.LOG.warn("Couldn't save {}", file(), e);
        }
    }

    // ------------------------------------------------------------------ pinned mods

    public static boolean isPinned(Module m) {
        load();
        return m != null && pinned.contains(m.getName());
    }

    /** Pin or unpin {@code m}; returns whether it is pinned now. */
    public static boolean togglePin(Module m) {
        load();
        if (m == null) return false;
        boolean now = !pinned.remove(m.getName());
        if (now) pinned.add(m.getName());
        save();
        return now;
    }

    public static void setPinned(Module m, boolean pin) {
        if (isPinned(m) != pin) togglePin(m);
    }

    /** How many mods are pinned (names of mods that no longer exist are not counted). */
    public static int pinnedCount() {
        load();
        int n = 0;
        if (EvadeClient.modules != null) for (Module m : EvadeClient.modules.all()) if (pinned.contains(m.getName())) n++;
        return n;
    }

    // ------------------------------------------------------------------ first run

    public static boolean setupDone() {
        load();
        return setupDone;
    }

    public static void setSetupDone(boolean done) {
        load();
        if (setupDone == done) return;
        setupDone = done;
        save();
    }

    /** "Tune for my PC" was picked outside a world: the benchmark runs once the next world has loaded. */
    public static boolean tunePending() {
        load();
        return tunePending;
    }

    public static void setTunePending(boolean pending) {
        load();
        if (tunePending == pending) return;
        tunePending = pending;
        save();
    }
}
