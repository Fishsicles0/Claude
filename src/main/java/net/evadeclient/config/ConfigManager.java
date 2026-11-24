package net.evadeclient.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.Module;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.Setting;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Saves module state, keybinds and settings to config/evade/config.json, and to the active profile. */
public class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Root key holding the layout version of a config file. Files without it are version 1 (written before
     * Watermark and Module List were split and before the Direction HUD's centred sentinel) and are migrated
     * when they load. Module names never start with an underscore, so it can't clash with one.
     */
    public static final String FORMAT_KEY = "_format";
    /** The layout version this build writes. */
    public static final int FORMAT = 2;
    /** HUD positions further than this from an edge are treated as broken and put back at the default spot. */
    static final int MAX_COORD = 10_000;

    private final Path file = FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("config.json");

    /** Set by EvadeClient after construction; when present, every save mirrors into the active profile. */
    public ProfileManager profiles;

    /** Each module's state before any config touched it, captured on first use: enabled, and x/y for HUD elements. */
    private final Map<String, Boolean> defaultEnabled = new HashMap<>();
    private final Map<String, int[]> defaultPos = new HashMap<>();
    private boolean defaultsCaptured;

    public ConfigManager() {
        captureDefaults();
    }

    public void save() {
        String json = serialize();
        write(file, json);
        if (profiles != null) profiles.mirror(json);
    }

    public void load() {
        loadFrom(file);
    }

    /** Serialize the whole client state to {@code target}. */
    public void saveTo(Path target) {
        write(target, serialize());
    }

    /** The whole client state as config JSON. */
    String serialize() {
        captureDefaults();
        JsonObject root = new JsonObject();
        root.addProperty(FORMAT_KEY, FORMAT);
        for (Module m : EvadeClient.modules.all()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", m.isEnabled());
            obj.addProperty("key", m.getKey());
            JsonObject settings = new JsonObject();
            for (Setting<?> s : m.getSettings()) settings.add(s.getName(), s.toJson());
            obj.add("settings", settings);
            if (m instanceof HudElementModule h) {
                obj.addProperty("x", h.x);
                obj.addProperty("y", h.y);
            }
            root.add(m.getName(), obj);
        }
        return GSON.toJson(root);
    }

    /** {@code json} laid out the way config files are written. */
    static String pretty(JsonElement json) {
        return GSON.toJson(json);
    }

    private static void write(Path target, String json) {
        try {
            writeAtomic(target, json);
        } catch (IOException e) {
            EvadeClient.LOG.error("Failed to save config {}", target, e);
        }
    }

    /**
     * Write {@code text} to a temporary file next to {@code target} and move it into place, so a crash or a full
     * disk mid-save never leaves a half-written config behind.
     */
    static void writeAtomic(Path target, String text) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // e.g. the target is briefly locked by a virus scanner on Windows: fall back to a plain write.
            Files.deleteIfExists(tmp);
            Files.writeString(target, text, StandardCharsets.UTF_8);
        }
    }

    /**
     * Apply client state from {@code source} if it exists. Keys the client doesn't know are ignored; after a
     * module's settings are applied, {@link Module#onConfigLoaded} gets the raw saved object to migrate old keys.
     * A broken entry only skips that one module (a broken field, that one field).
     *
     * @return false if the file exists but couldn't be read (a copy is kept next to it as {@code .corrupt})
     */
    public boolean loadFrom(Path source) {
        captureDefaults();
        if (!Files.exists(source)) return true;
        JsonObject root = read(source);
        if (root == null) return false;
        apply(root);
        return true;
    }

    /**
     * Parse a config file. On failure the file is copied to {@code <name>.corrupt} (it is about to be overwritten
     * by the next save) and null is returned.
     */
    JsonObject read(Path source) {
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(source, StandardCharsets.UTF_8));
            if (parsed.isJsonObject()) return parsed.getAsJsonObject();
            throw new IllegalStateException("not a JSON object");
        } catch (Exception e) {
            EvadeClient.LOG.error("Failed to read config {}", source, e);
            try {
                Path backup = source.resolveSibling(source.getFileName() + ".corrupt");
                Files.copy(source, backup, StandardCopyOption.REPLACE_EXISTING);
                EvadeClient.LOG.warn("Kept a copy of the unreadable config as {}", backup);
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    /** Apply an already parsed config (migrating it first if it is from an older version). */
    void apply(JsonObject root) {
        captureDefaults();
        migrate(root);
        for (Module m : EvadeClient.modules.all()) {
            JsonElement entry = root.get(m.getName());
            if (entry == null || !entry.isJsonObject()) continue;
            try {
                load(m, entry.getAsJsonObject());
            } catch (Exception e) {
                EvadeClient.LOG.warn("Skipped the saved config for {}", m.getName(), e);
            }
        }
    }

    private void load(Module m, JsonObject obj) {
        JsonElement key = obj.get("key");
        if (key != null) {
            try {
                int code = key.getAsInt();
                m.setKey(isValidBind(code) ? code : GLFW.GLFW_KEY_UNKNOWN);
            } catch (RuntimeException e) {
                EvadeClient.LOG.warn("Ignored a broken keybind for {}", m.getName());
            }
        }
        JsonElement saved = obj.get("settings");
        if (saved != null && saved.isJsonObject()) {
            JsonObject settings = saved.getAsJsonObject();
            for (Setting<?> s : m.getSettings()) {
                JsonElement v = settings.get(s.getName());
                if (v == null) continue;
                try {
                    s.loadJson(v);
                } catch (RuntimeException e) {
                    EvadeClient.LOG.warn("Ignored a broken value for {} {}", m.getName(), s.getName());
                }
            }
            try {
                m.onConfigLoaded(settings);
            } catch (Exception e) {
                EvadeClient.LOG.error("Config migration failed for {}", m.getName(), e);
            }
        }
        if (m instanceof HudElementModule h && obj.has("x") && obj.has("y")) {
            int[] def = defaultPos.getOrDefault(m.getName(), new int[]{h.x, h.y});
            h.x = coord(obj.get("x"), def[0]);
            h.y = coord(obj.get("y"), def[1]);
        }
        JsonElement enabled = obj.get("enabled");
        if (enabled != null) {
            try {
                m.setEnabled(enabled.getAsBoolean());
            } catch (RuntimeException e) {
                EvadeClient.LOG.warn("Ignored a broken on/off state for {}", m.getName());
            }
        }
    }

    /** A key a module can be bound to: none, a keyboard key, or a middle/side mouse button. */
    static boolean isValidBind(int code) {
        return KeySetting.isValidKey(code) || KeySetting.isValidMouse(code);
    }

    /**
     * A saved HUD coordinate, or {@code def} if it isn't a usable one. The element's own default (which may be a
     * sentinel such as {@link Integer#MIN_VALUE} for "centred") is always accepted.
     */
    static int coord(JsonElement e, int def) {
        try {
            double d = e.getAsDouble();
            if (d == def) return def;
            // An element snapped to the centre line in the HUD editor stays centred, whatever its default.
            if (d == HudElementModule.CENTER) return HudElementModule.CENTER;
            if (!Double.isFinite(d) || Math.abs(d) > MAX_COORD) return def;
            return (int) Math.round(d);
        } catch (RuntimeException ex) {
            return def;
        }
    }

    // ------------------------------------------------------------------ defaults

    /** Remember every module's untouched state. Runs once, before the first load or save changes anything. */
    private void captureDefaults() {
        if (defaultsCaptured || EvadeClient.modules == null) return;
        defaultsCaptured = true;
        for (Module m : EvadeClient.modules.all()) {
            defaultEnabled.put(m.getName(), m.isEnabled());
            if (m instanceof HudElementModule h) defaultPos.put(m.getName(), new int[]{h.x, h.y});
        }
    }

    /** Whether {@code m} is on in a fresh install. */
    boolean defaultEnabled(Module m) {
        captureDefaults();
        return defaultEnabled.getOrDefault(m.getName(), false);
    }

    /** Where a HUD element sits in a fresh install, as {x, y}. */
    int[] defaultPosition(HudElementModule h) {
        captureDefaults();
        int[] p = defaultPos.get(h.getName());
        return p == null ? new int[]{h.x, h.y} : p.clone();
    }

    // ------------------------------------------------------------------ migration

    /**
     * Upgrade a parsed config (config.json, a profile or an imported code) to the current layout, in place.
     * Safe to run on a config that is already current.
     */
    void migrate(JsonObject root) {
        if (EvadeClient.modules == null) return;
        int format = format(root);
        for (Map.Entry<String, String> r : RENAMED_MODULES.entrySet()) {
            if (root.has(r.getKey()) && !root.has(r.getValue())) root.add(r.getValue(), root.remove(r.getKey()));
        }
        renameHiddenModules(root);
        rekeyRenamedModules(root);
        migrateModuleList(root);
        if (format < 2) {
            // Toggle Sprint's old default (4, -2) sat over the offhand slot: an untouched one moves to the new
            // default (centred under the Direction HUD).
            var toggleSprint = EvadeClient.modules.get(net.evadeclient.module.mods.ToggleSprint.class);
            JsonObject ts = toggleSprint == null ? null : object(root, toggleSprint.getName());
            if (ts != null && ts.has("x") && ts.has("y")
                    && coord(ts.get("x"), Integer.MIN_VALUE) == 4 && coord(ts.get("y"), Integer.MIN_VALUE) == -2) {
                int[] def = defaultPosition(toggleSprint);
                ts.addProperty("x", def[0]);
                ts.addProperty("y", def[1]);
            }
            // The Direction HUD used x == 0 for "centred" (so dragging it to the left edge sent it back to the
            // middle); its default is now the CENTER sentinel, so a saved 0 becomes that. Only the Direction HUD:
            // anywhere else a saved 0 meant "dragged to the left edge".
            var compass = EvadeClient.modules.get(net.evadeclient.module.hud.CompassHud.class);
            if (compass != null && defaultPosition(compass)[0] == HudElementModule.CENTER) {
                JsonObject e = object(root, compass.getName());
                if (e != null && e.has("x") && coord(e.get("x"), Integer.MIN_VALUE) == 0) e.addProperty("x", HudElementModule.CENTER);
            }
        }
        if (format < FORMAT) root.addProperty(FORMAT_KEY, FORMAT);
    }

    /** Modules whose display name changed beyond spacing or case: old saved key -> current name. */
    private static final Map<String, String> RENAMED_MODULES = Map.of("CameraClip", "Camera Distance");

    /** The Module List's "Hidden Modules" names renamed modules by their old names: point them at the new ones. */
    private static void renameHiddenModules(JsonObject root) {
        JsonObject list = object(root, "Module List");
        JsonObject s = list == null ? null : object(list, "settings");
        JsonElement hidden = s == null ? null : s.get("Hidden Modules");
        if (hidden == null || !hidden.isJsonPrimitive() || !hidden.getAsJsonPrimitive().isString()) return;
        String v = hidden.getAsString(), was = v;
        for (Map.Entry<String, String> r : RENAMED_MODULES.entrySet())
            v = v.replaceAll("(?i)(?<![A-Za-z0-9])" + java.util.regex.Pattern.quote(r.getKey()) + "(?![A-Za-z0-9])",
                    java.util.regex.Matcher.quoteReplacement(r.getValue()));
        if (!v.equals(was)) s.addProperty("Hidden Modules", v);
    }

    /** The layout version of a parsed config (1 when it has none). */
    static int format(JsonObject root) {
        try {
            JsonElement f = root.get(FORMAT_KEY);
            return f == null ? 1 : f.getAsInt();
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /**
     * Modules renamed only in spacing or capitalisation ("ToggleSprint" to "Toggle Sprint") keep their saved
     * state: an entry with no exact match moves to the module whose name matches it ignoring case and punctuation.
     */
    private static void rekeyRenamedModules(JsonObject root) {
        Map<String, String> byLoose = new HashMap<>();
        for (Module m : EvadeClient.modules.all()) byLoose.putIfAbsent(loose(m.getName()), m.getName());
        List<String> keys = new ArrayList<>(root.keySet());
        for (String key : keys) {
            if (key.startsWith("_") || byLoose.containsValue(key)) continue;
            String target = byLoose.get(loose(key));
            if (target != null && !root.has(target)) root.add(target, root.remove(key));
        }
    }

    private static String loose(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * Before Watermark and Module List were separate elements, one "Module List" module drew both, with inner
     * "Watermark" and "Module List" toggles; its colour modes were "Red Wave" and "Static Red".
     */
    private static void migrateModuleList(JsonObject root) {
        JsonObject list = object(root, "Module List");
        JsonObject s = list == null ? null : object(list, "settings");
        if (s == null) return;
        JsonElement color = s.get("List Color");
        if (color != null && color.isJsonPrimitive()) {
            String c = color.getAsString();
            if (c.equalsIgnoreCase("Red Wave")) s.addProperty("List Color", "Accent Wave");
            else if (c.equalsIgnoreCase("Static Red")) s.addProperty("List Color", "Accent");
        }
        boolean oldMark = s.has("Watermark"), oldList = s.has("Module List");
        if (!oldMark && !oldList) return;
        boolean wasOn = bool(list.get("enabled"), true);
        if (oldList) list.addProperty("enabled", wasOn && bool(s.get("Module List"), true));
        if (oldMark && !root.has("Watermark")) {
            JsonObject mark = new JsonObject();
            mark.addProperty("enabled", wasOn && bool(s.get("Watermark"), true));
            root.add("Watermark", mark);
        }
        s.remove("Watermark");
        s.remove("Module List");
    }

    static JsonObject object(JsonObject parent, String key) {
        JsonElement e = parent == null ? null : parent.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    static boolean bool(JsonElement e, boolean fallback) {
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsBoolean() : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    public Path file() { return file; }
}
