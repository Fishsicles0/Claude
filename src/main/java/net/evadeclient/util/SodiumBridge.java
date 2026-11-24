package net.evadeclient.util;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.evadeclient.EvadeClient;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lets Performance's FPS Mode also set Sodium and Sodium Extra, so one page tunes the whole stack. Both are optional:
 * everything here goes through reflection and does nothing when a mod (or an option it expects) isn't there.
 *
 * <p>Same model as Performance's video settings: every option changed is remembered with your value and the value
 * Evade set, and putting it back only touches options that still hold Evade's value, so anything you changed in
 * Sodium's own screen meanwhile is kept. The list is also written to {@code config/evade/sodium-restore.json}, so a
 * crash or a killed game can't leave Evade's values in Sodium's config as if they were yours: the next start puts them
 * back. Sodium's own config files are saved after each change and each restore, so its menu always shows the truth.
 *
 * <p>What it changes, by FPS Mode (never anything that shows you less of what matters: beacon beams, name tags,
 * pistons, item frames, fog in water, lava and powder snow, and Sodium Extra's other toggles stay as they are):
 * <ul>
 *   <li>Max FPS: Sodium Extra's water, lava, fire and portal texture animations and the stars off; Sodium defers
 *       chunk updates ("Always") and builds chunks with one thread per physical core, less two (applies from the next
 *       world load), and animates only textures on screen.</li>
 *   <li>Balanced: Sodium animates only textures on screen and gets the same build threads.</li>
 *   <li>Quality and Custom: nothing.</li>
 * </ul>
 * Render thread only.
 */
public final class SodiumBridge {
    private SodiumBridge() {}

    private static final String SODIUM = "net.caffeinemc.mods.sodium.client.SodiumClientMod";
    private static final String SODIUM_OPTIONS = "net.caffeinemc.mods.sodium.client.gui.SodiumOptions";
    private static final String EXTRA = "me.flashyreese.mods.sodiumextra.client.SodiumExtraClientMod";

    /** One option Evade changed: where it lives ("sodium" or "extra"), its path, your value and Evade's. */
    private record Changed(String mod, String path, String label, Object original, Object applied) {}

    private static final List<Changed> changed = new ArrayList<>();
    private static String appliedMode = "";
    private static boolean recovered;

    // ------------------------------------------------------------------ presence

    public static boolean sodiumLoaded() { return FabricLoader.getInstance().isModLoaded("sodium"); }

    public static boolean extraLoaded() { return FabricLoader.getInstance().isModLoaded("sodium-extra"); }

    /** Sodium's live options object, or null. */
    private static Object sodiumOptions() {
        if (!sodiumLoaded()) return null;
        try {
            return Class.forName(SODIUM).getMethod("options").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Sodium Extra's live options object, or null. */
    private static Object extraOptions() {
        if (!extraLoaded()) return null;
        try {
            return Class.forName(EXTRA).getMethod("options").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object root(String mod) {
        return mod.equals("sodium") ? sodiumOptions() : extraOptions();
    }

    // ------------------------------------------------------------------ field paths ("performance.chunkBuilderThreads")

    private record Slot(Object owner, Field field) {
        Object get() throws IllegalAccessException { return field.get(owner); }
        void set(Object v) throws IllegalAccessException { field.set(owner, v); }
    }

    private static Slot slot(Object root, String path) {
        if (root == null) return null;
        try {
            Object o = root;
            String[] parts = path.split("\\.");
            for (int i = 0; i < parts.length - 1; i++) {
                Field f = o.getClass().getField(parts[i]);
                o = f.get(o);
                if (o == null) return null;
            }
            Field f = o.getClass().getField(parts[parts.length - 1]);
            if (java.lang.reflect.Modifier.isFinal(f.getModifiers())) return null;
            return new Slot(o, f);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The value at {@code path} in {@code mod}'s options, or null when it isn't there. */
    public static Object read(String mod, String path) {
        Slot s = slot(root(mod), path);
        try {
            return s == null ? null : s.get();
        } catch (Throwable t) {
            return null;
        }
    }

    /** An enum constant of the field's type by name, or the value as-is. */
    private static Object coerce(Slot s, Object value) {
        Class<?> type = s.field().getType();
        if (type.isEnum() && value instanceof String name) {
            for (Object c : type.getEnumConstants()) if (((Enum<?>) c).name().equals(name)) return c;
            return null;
        }
        if ((type == int.class || type == Integer.class) && value instanceof Number n) return n.intValue();
        return value;
    }

    private static boolean change(String mod, String path, String label, Object value) {
        Slot s = slot(root(mod), path);
        if (s == null) return false;
        try {
            Object v = coerce(s, value);
            if (v == null) return false;
            Object old = s.get();
            if (Objects.equals(old, v)) return false;
            s.set(v);
            changed.add(new Changed(mod, path, label, old, v));
            return true;
        } catch (Throwable t) {
            EvadeClient.LOG.warn("Sodium bridge: couldn't set {} {}", mod, path, t);
            return false;
        }
    }

    // ------------------------------------------------------------------ apply and restore

    /** Build threads for Max FPS and Balanced: one per physical core, less two (at least one, at most 10). */
    public static int buildThreads() {
        return Math.max(1, Math.min(10, GpuInfo.physicalCores() - 2));
    }

    /**
     * Follows FPS Mode: when {@code mode} differs from what is applied, puts back what Evade changed, then applies the
     * new mode's changes. {@code mode} "" (or Performance off, or the option off) means change nothing.
     */
    public static void follow(String mode) {
        if (mode == null) mode = "";
        if (!recovered) recover();
        if (mode.equals(appliedMode)) return;
        boolean any = putBack();
        appliedMode = mode;
        switch (mode) {
            case "Max FPS" -> {
                any |= change("extra", "animationSettings.water", "water animation off", false);
                any |= change("extra", "animationSettings.lava", "lava animation off", false);
                any |= change("extra", "animationSettings.fire", "fire animation off", false);
                any |= change("extra", "animationSettings.portal", "portal animation off", false);
                any |= change("extra", "detailSettings.stars", "stars off", false);
                any |= change("sodium", "performance.chunkBuildDeferMode", "chunk updates deferred", "ALWAYS");
                any |= change("sodium", "performance.chunkBuilderThreads", buildThreads() + " build threads", buildThreads());
                any |= change("sodium", "performance.animateOnlyVisibleTextures", "only visible textures animate", true);
            }
            case "Balanced" -> {
                any |= change("sodium", "performance.chunkBuilderThreads", buildThreads() + " build threads", buildThreads());
                any |= change("sodium", "performance.animateOnlyVisibleTextures", "only visible textures animate", true);
            }
            default -> { }
        }
        if (any) saveMods();
        saveRestoreFile();
    }

    /** Puts back every option that still holds Evade's value. True if anything changed. */
    private static boolean putBack() {
        boolean any = false;
        for (int i = changed.size() - 1; i >= 0; i--) {
            Changed c = changed.get(i);
            Slot s = slot(root(c.mod()), c.path());
            if (s == null) continue;
            try {
                if (!Objects.equals(s.get(), c.applied())) continue; // you changed it in Sodium's screen: keep yours
                s.set(c.original());
                any = true;
            } catch (Throwable t) {
                EvadeClient.LOG.warn("Sodium bridge: couldn't put back {} {}", c.mod(), c.path(), t);
            }
        }
        changed.clear();
        return any;
    }

    /** Everything back (Performance turned off, or the game quitting). */
    public static void restore() {
        boolean any = putBack();
        appliedMode = "";
        if (any) saveMods();
        saveRestoreFile();
    }

    /** "Also changed in Sodium: ..." for Performance, or a line saying why nothing is. */
    public static String status() {
        if (!sodiumLoaded() && !extraLoaded()) return "Sodium isn't installed";
        if (changed.isEmpty()) return appliedMode.isEmpty() ? "Nothing changed" : "Nothing to change for " + appliedMode;
        List<String> labels = new ArrayList<>();
        for (Changed c : changed) labels.add(c.label());
        return String.join(", ", labels);
    }

    /** What is changed right now: "mod:path" to Evade's value (tests). */
    public static Map<String, Object> changedForTest() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Changed c : changed) out.put(c.mod() + ":" + c.path(), c.applied());
        return out;
    }

    /** Writes Sodium's and Sodium Extra's config files from their live options (so their menus agree). */
    private static void saveMods() {
        Object so = sodiumOptions();
        if (so != null) {
            try {
                Method w = Class.forName(SODIUM_OPTIONS).getMethod("writeToDisk", so.getClass());
                w.invoke(null, so);
            } catch (Throwable t) {
                EvadeClient.LOG.warn("Sodium bridge: couldn't save Sodium's options", t);
            }
        }
        Object eo = extraOptions();
        if (eo != null) {
            try {
                eo.getClass().getMethod("writeChanges").invoke(eo);
            } catch (Throwable t) {
                EvadeClient.LOG.warn("Sodium bridge: couldn't save Sodium Extra's options", t);
            }
        }
    }

    // ------------------------------------------------------------------ crash-safe restore file

    private static Path restoreFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("sodium-restore.json");
    }

    private static JsonElement json(Object v) {
        if (v instanceof Boolean b) return new JsonPrimitive(b);
        if (v instanceof Number n) return new JsonPrimitive(n);
        if (v instanceof Enum<?> e) return new JsonPrimitive(e.name());
        return v == null ? null : new JsonPrimitive(String.valueOf(v));
    }

    private static Object unjson(Slot s, JsonElement j) {
        if (j == null || !j.isJsonPrimitive()) return null;
        Class<?> type = s.field().getType();
        if (type == boolean.class || type == Boolean.class) return j.getAsBoolean();
        if (type == int.class || type == Integer.class) return j.getAsInt();
        if (type.isEnum()) return coerce(s, j.getAsString());
        return null;
    }

    private static void saveRestoreFile() {
        Path file = restoreFile();
        try {
            if (changed.isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            JsonObject root = new JsonObject();
            for (Changed c : changed) {
                JsonElement o = json(c.original()), a = json(c.applied());
                if (o == null || a == null) continue;
                JsonObject e = new JsonObject();
                e.add("original", o);
                e.add("applied", a);
                root.add(c.mod() + ":" + c.path(), e);
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(root));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            EvadeClient.LOG.warn("Sodium bridge: couldn't save {}", file, e);
        }
    }

    /**
     * Once, before the first change: a restore file means the game last stopped while Evade's values were in place.
     * Put your values back wherever Evade's are still set, save Sodium's files, and forget the file.
     */
    public static void recover() {
        recovered = true;
        Path file = restoreFile();
        if (!Files.exists(file)) return;
        boolean any = false;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (var e : root.entrySet()) {
                int colon = e.getKey().indexOf(':');
                if (colon < 0 || !e.getValue().isJsonObject()) continue;
                Slot s = slot(root(e.getKey().substring(0, colon)), e.getKey().substring(colon + 1));
                if (s == null) continue;
                JsonObject entry = e.getValue().getAsJsonObject();
                Object original = unjson(s, entry.get("original")), applied = unjson(s, entry.get("applied"));
                if (original == null || applied == null || !Objects.equals(s.get(), applied)) continue;
                s.set(original);
                any = true;
            }
            if (any) {
                saveMods();
                EvadeClient.LOG.info("Sodium bridge: restored your Sodium settings after an unclean shutdown");
            }
        } catch (Throwable t) {
            EvadeClient.LOG.warn("Sodium bridge: couldn't read {}", file, t);
        }
        try {
            Files.deleteIfExists(file);
        } catch (Exception ignored) {
        }
    }

    /** Test hook: forget the crash-recovery check, so the next follow() reads the restore file again. */
    public static void resetRecoveryForTest() { recovered = false; }
}
