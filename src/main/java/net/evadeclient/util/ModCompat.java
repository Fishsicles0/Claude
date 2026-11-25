package net.evadeclient.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Module;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Getting along with popular add-on mods (the launcher's Add-ons tab: Flashback, SkyHanni, Litematica, MiniHUD...):
 * <ul>
 *   <li>Switching an Evade module off when the launcher asks ({@code .evade/modules-off.json}, written by the Mod
 *   check's "Turn Evade's off" when an add-on does the same job). Read once at start-up and deleted; the module can be
 *   switched back on in the Evade menu like any other. Nothing is locked.</li>
 *   <li>A one-time heads-up on the title screen when an add-on overlaps an Evade module that's on (Flashback and
 *   Evade's Replay both recording; SkyHanni's own scoreboard and tab list next to Evade's). Evade never turns its own
 *   modules off by itself.</li>
 *   <li>Hotkeys that aren't game controls, so the Keybinds page can't see them (MaLiLib mods keep their own), for
 *   {@link KeyClashNotice}: e.g. Litematica's selection tool uses Left Alt, Evade's Freelook default.</li>
 * </ul>
 * Remembered in {@code config/evade/mod-compat.json}. Nothing leaves the computer.
 */
public final class ModCompat {
    private ModCompat() {}

    public static final String OFF_FILE = "modules-off.json";

    /** An add-on next to an Evade module that does the same job: {mod id, add-on name, Evade module, what to say}. */
    static final String[][] OVERLAPS = {
            {"flashback", "Flashback", "Replay", "Flashback and Evade's Replay both record replays. Keep one: switch Replay off in the Evade menu if you use Flashback."},
            {"replaymod", "Replay Mod", "Replay", "Replay Mod and Evade's Replay both record replays. Keep one: switch Replay off in the Evade menu if you use Replay Mod."},
            {"skyhanni", "SkyHanni", "Scoreboard", "If you turn on SkyHanni's own scoreboard or tab list, switch off Evade's Scoreboard or Tab Tweaks so only one restyles it."},
    };

    /** Hotkeys of mods that don't use game controls: {mod id, what it is, GLFW key}. */
    static final Object[][] HOTKEYS = {
            {"litematica", "Litematica's selection tool", GLFW.GLFW_KEY_LEFT_ALT},
            {"minihud", "MiniHUD's show/hide", GLFW.GLFW_KEY_H},
            {"tweakeroo", "Tweakeroo's placement keys", GLFW.GLFW_KEY_LEFT_ALT},
    };

    private static final Set<String> seen = new LinkedHashSet<>();
    private static final List<String[]> pending = new ArrayList<>();
    private static boolean loaded, shown;

    /** From the client initializer, after the config has loaded. */
    public static void init() {
        try {
            List<String> off = applyOffRequest(FabricLoader.getInstance().getGameDir().resolve(LauncherBridge.DIR).resolve(OFF_FILE));
            if (!off.isEmpty()) pending.add(new String[]{"Evade", "Switched off for this instance, as the launcher asked: " + String.join(", ", off)
                    + ". Switch back on any time in the Evade menu."});
            for (String[] o : overlaps(id -> FabricLoader.getInstance().isModLoaded(id))) {
                load();
                if (seen.add("overlap|" + o[0])) pending.add(new String[]{o[1], o[3]});
            }
            if (!pending.isEmpty()) save();
        } catch (RuntimeException e) {
            EvadeClient.LOG.warn("Mod compatibility check failed", e);
        }
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (shown || !SafeStart.isTitle(screen) || SafeStart.testing()) return;
            shown = true;
            for (String[] p : pending) Notifications.push(p[0], p[1], Notifications.Level.INFO, 12_000);
            pending.clear();
        });
    }

    /**
     * Switches off the Evade modules named in the launcher's request file, saves, and deletes the file. Returns the
     * names switched off (unknown names are skipped).
     */
    public static List<String> applyOffRequest(Path file) {
        List<String> done = new ArrayList<>();
        if (!Files.isRegularFile(file) || EvadeClient.modules == null) return done;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (root.isJsonObject() && root.getAsJsonObject().get("off") instanceof JsonArray a) {
                int n = 0;
                for (JsonElement e : a) {
                    if (!e.isJsonPrimitive() || ++n > 64) continue;
                    Module m = EvadeClient.modules.get(e.getAsString());
                    if (m == null) continue;
                    if (m.isEnabled()) m.setEnabled(false);
                    done.add(m.getName());
                }
            }
        } catch (Exception e) {
            EvadeClient.LOG.warn("Couldn't read {}", file, e);
        }
        try { Files.deleteIfExists(file); } catch (Exception ignored) { }
        if (!done.isEmpty() && EvadeClient.config != null) EvadeClient.config.save();
        return done;
    }

    /** The overlaps that apply: the add-on is loaded and the Evade module it overlaps is on. */
    public static List<String[]> overlaps(Predicate<String> loaded) {
        List<String[]> out = new ArrayList<>();
        for (String[] o : OVERLAPS) {
            if (!loaded.test(o[0])) continue;
            Module m = EvadeClient.modules != null ? EvadeClient.modules.get(o[2]) : null;
            if (m != null && m.isEnabled()) out.add(o);
        }
        return out;
    }

    /**
     * Evade bindings on a key another loaded mod uses outside the game's controls, as KeyClashNotice ids
     * ("Freelook Key|Litematica's selection tool|Left Alt").
     */
    public static List<String> hotkeyClashes(List<Keybinds.Binding> all, Predicate<String> loaded) {
        List<String> out = new ArrayList<>();
        for (Object[] h : HOTKEYS) {
            if (!loaded.test((String) h[0])) continue;
            int code = (Integer) h[2];
            for (Keybinds.Binding b : all) {
                if (b.game() || !b.bound() || b.code() != code || b.mods() != 0) continue;
                String id = b.name() + "|" + h[1] + "|" + b.keyText();
                if (!out.contains(id)) out.add(id);
            }
        }
        return out;
    }

    /** As above, for the mods installed now. */
    public static List<String> hotkeyClashes(List<Keybinds.Binding> all) {
        return hotkeyClashes(all, id -> FabricLoader.getInstance().isModLoaded(id));
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("mod-compat.json");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            JsonElement root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
            if (root.isJsonObject() && root.getAsJsonObject().get("seen") instanceof JsonArray a)
                for (JsonElement e : a) if (e.isJsonPrimitive() && seen.size() < 200) seen.add(e.getAsString());
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
