package net.evadeclient.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A one-time heads-up when one of Evade's keys is also used by another installed mod (Simple Voice Chat, Iris and the
 * like), e.g. "World Map and Mute Microphone are both on M". Each clash is mentioned once, the first time the title
 * screen shows after it appears; the Keybinds page keeps showing every clash in red. Clashes with the game's own
 * controls are left to that page (Evade's defaults sit on them on purpose, like Zoom on C).
 * Remembered in {@code config/evade/key-clashes.json}.
 */
public final class KeyClashNotice {
    private KeyClashNotice() {}

    /** At most this many clashes are named in one notice; the rest are counted. */
    private static final int NAMED = 2;
    private static final Set<String> seen = new LinkedHashSet<>();
    private static boolean loaded, checked;

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (checked || !SafeStart.isTitle(screen) || SafeStart.testing()) return;
            checked = true;
            try {
                check(true);
            } catch (RuntimeException e) {
                EvadeClient.LOG.warn("Key clash check failed", e);
            }
        });
    }

    /**
     * Evade keys that clash with another mod's key, as "Evade key|other key|key name", not yet mentioned. With
     * {@code notify}, shows one notice for them and remembers them.
     */
    public static List<String> check(boolean notify) {
        load();
        List<Keybinds.Binding> all = Keybinds.all();
        List<String> fresh = new ArrayList<>();
        for (Keybinds.Binding evade : all) {
            if (evade.game() || !evade.bound()) continue;
            for (Keybinds.Binding other : all) {
                if (!other.game() || !(other.owner() instanceof KeyBinding kb) || vanilla(kb)) continue;
                if (!Keybinds.clash(evade, other)) continue;
                String id = evade.name() + "|" + other.name() + "|" + evade.keyText();
                if (!seen.contains(id) && !fresh.contains(id)) fresh.add(id);
            }
        }
        // Mods with their own hotkey systems (Litematica, MiniHUD...), which aren't game controls.
        for (String id : ModCompat.hotkeyClashes(all)) if (!seen.contains(id) && !fresh.contains(id)) fresh.add(id);
        if (notify && !fresh.isEmpty()) {
            List<String> named = new ArrayList<>();
            for (int i = 0; i < Math.min(NAMED, fresh.size()); i++) {
                String[] p = fresh.get(i).split("\\|", 3);
                named.add(p[0] + " and " + p[1] + " are both on " + p[2]);
            }
            String more = fresh.size() > NAMED ? " (+" + (fresh.size() - NAMED) + " more)" : "";
            Notifications.push("Key clash", String.join("; ", named) + more + ". Change one on the Keybinds page in the Evade menu.",
                    Notifications.Level.WARN, 12_000);
            seen.addAll(fresh);
            save();
        }
        return fresh;
    }

    /** A control of the game itself (its categories are in the minecraft namespace). */
    private static boolean vanilla(KeyBinding kb) {
        try {
            return "minecraft".equals(kb.getCategory().id().getNamespace());
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("key-clashes.json");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            JsonElement root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            if (root.isJsonObject() && root.getAsJsonObject().get("seen") instanceof JsonArray a) {
                for (JsonElement e : a) if (e.isJsonPrimitive() && seen.size() < 500) seen.add(e.getAsString());
            }
        } catch (Exception e) {
            EvadeClient.LOG.warn("Couldn't read {}", file(), e);
        }
    }

    private static void save() {
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray();
        seen.forEach(a::add);
        o.add("seen", a);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            EvadeClient.LOG.warn("Couldn't save {}", file(), e);
        }
    }
}
