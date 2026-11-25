package net.evadeclient.util;

import net.evadeclient.EvadeClient;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Turns Iris shaders on or off and switches between shader packs, without opening Iris' own screens: the ones the
 * installer adds (Evade Shaders, Photon, and the lighter Complementary Reimagined) by name, or any pack in shaderpacks/ through
 * {@link #listPacks()} and {@link #applyPack(String)} (the Shader Pack picker). Iris is optional: everything here is reflection and
 * does nothing when Iris isn't installed. Render thread only (Iris reloads its pipeline).
 */
public final class ShaderSwitch {
    private ShaderSwitch() {}

    public static boolean irisLoaded() { return FabricLoader.getInstance().isModLoaded("iris"); }

    /** The file in shaderpacks/ whose name starts with {@code prefix} (case doesn't matter), if there is one. */
    public static Optional<String> findPack(String prefix) {
        if (!irisLoaded()) return Optional.empty();
        try {
            Path dir = (Path) Class.forName("net.irisshaders.iris.Iris").getMethod("getShaderpacksDirectory").invoke(null);
            if (dir == null || !Files.isDirectory(dir)) return Optional.empty();
            String p = prefix.toLowerCase(Locale.ROOT);
            try (Stream<Path> s = Files.list(dir)) {
                return s.map(f -> f.getFileName().toString()).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(p)).sorted().reduce((a, b) -> b);
            }
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** The shaderpacks folder: Iris' own when it's loaded, else the game folder's (where Iris would look). */
    public static Path packsDir() {
        if (irisLoaded()) {
            try {
                Path dir = (Path) Class.forName("net.irisshaders.iris.Iris").getMethod("getShaderpacksDirectory").invoke(null);
                if (dir != null) return dir;
            } catch (Throwable ignored) {
                // fall back below
            }
        }
        return FabricLoader.getInstance().getGameDir().resolve("shaderpacks");
    }

    /**
     * Every shader pack in shaderpacks/: zip files, and folders that hold a {@code shaders} folder (an unzipped
     * pack), sorted by name. Empty when there's no folder.
     */
    public static List<String> listPacks() {
        List<String> out = new ArrayList<>();
        Path dir = packsDir();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.forEach(f -> {
                String n = f.getFileName().toString();
                if (n.startsWith(".")) return;
                if (Files.isRegularFile(f) && n.toLowerCase(Locale.ROOT).endsWith(".zip")) out.add(n);
                else if (Files.isDirectory(f) && Files.isDirectory(f.resolve("shaders"))) out.add(n);
            });
        } catch (Exception e) {
            EvadeClient.LOG.warn("Shaders: couldn't list {}", dir, e);
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** The pack Iris has selected (whether or not shaders are on), if any. */
    public static Optional<String> selectedPack() {
        if (!irisLoaded()) return Optional.empty();
        try {
            Object cfg = config();
            @SuppressWarnings("unchecked")
            Optional<String> pack = (Optional<String>) cfg.getClass().getMethod("getShaderPackName").invoke(cfg);
            return pack;
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** Whether Iris has shaders switched on (a pack may still fail to load). */
    public static boolean enabled() {
        if (!irisLoaded()) return false;
        try {
            Object cfg = config();
            return (boolean) cfg.getClass().getMethod("areShadersEnabled").invoke(cfg);
        } catch (Throwable t) {
            return false;
        }
    }

    /** A pack's file name as players know it: "Photon", "Complementary", else the name without ".zip". */
    public static String displayName(String file) { return pretty(file); }

    /**
     * Turns shaders on with {@code file} from shaderpacks/ (any pack, not only the installer's), or off when
     * {@code file} is null. Returns a message for the player. Render thread only.
     */
    public static String applyPack(String file) {
        if (!irisLoaded()) return "Shaders need Iris. Run the Evade installer again with Shaders switched on.";
        if (file != null && !listPacks().contains(file)) return file + " isn't in your shaderpacks folder any more.";
        try {
            Object cfg = config();
            Class<?> c = cfg.getClass();
            if (file == null) {
                c.getMethod("setShadersEnabled", boolean.class).invoke(cfg, false);
            } else {
                c.getMethod("setShaderPackName", String.class).invoke(cfg, file);
                c.getMethod("setShadersEnabled", boolean.class).invoke(cfg, true);
            }
            c.getMethod("save").invoke(cfg);
            Class.forName("net.irisshaders.iris.Iris").getMethod("reload").invoke(null);
            return file == null ? "Shaders off" : pretty(file) + " shaders on";
        } catch (Throwable t) {
            EvadeClient.LOG.warn("Shaders: couldn't switch to {}", file, t);
            return "Couldn't switch shaders: " + t.getClass().getSimpleName();
        }
    }

    /** Whether an Iris shader pack is drawing the world right now. */
    public static boolean inUse() {
        if (!irisLoaded()) return false;
        try {
            Object api = Class.forName("net.irisshaders.iris.api.v0.IrisApi").getMethod("getInstance").invoke(null);
            return (boolean) api.getClass().getMethod("isShaderPackInUse").invoke(api);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The pack Iris has selected and whether shaders are on, e.g. "Photon (on)". Empty without Iris. */
    public static String status() {
        if (!irisLoaded()) return "Iris not installed";
        try {
            Object cfg = config();
            boolean on = (boolean) cfg.getClass().getMethod("areShadersEnabled").invoke(cfg);
            @SuppressWarnings("unchecked")
            Optional<String> pack = (Optional<String>) cfg.getClass().getMethod("getShaderPackName").invoke(cfg);
            return on ? pack.map(ShaderSwitch::pretty).orElse("On") : "Off";
        } catch (Throwable t) {
            return "Unknown";
        }
    }

    private static String pretty(String file) {
        String n = file.toLowerCase(Locale.ROOT);
        if (n.startsWith("photon")) return "Photon";
        if (n.startsWith("complementaryreimagined")) return "Complementary Reimagined";
        if (n.startsWith("complementaryunbound")) return "Complementary Unbound";
        if (n.startsWith("complementary")) return "Complementary";
        if (n.startsWith("bsl")) return "BSL";
        if (n.startsWith("sildur's vibrant") || n.startsWith("sildurs vibrant") || n.startsWith("sildurs-vibrant")) return "Sildur's Vibrant";
        if (n.startsWith("evade shaders") || n.startsWith("evadeshaders") || n.startsWith("evade-shaders")) return "Evade Shaders";
        return file.replaceAll("\\.zip$", "");
    }

    /** File-name starts that mark Evade Shaders: "Evade Shaders v1.0.zip" (from the launcher) or "EvadeShaders.zip". */
    private static final String[] EVADE_PREFIXES = {"evade shaders", "evadeshaders", "evade-shaders"};

    /** Whether a shaderpacks/ file is Evade Shaders. */
    public static boolean isEvadePack(String file) {
        if (file == null) return false;
        String n = file.toLowerCase(Locale.ROOT);
        for (String p : EVADE_PREFIXES) if (n.startsWith(p)) return true;
        return false;
    }

    /** The pack file a Shaders choice means ("Evade Shaders", "Photon", "Complementary"), if it's in shaderpacks/. */
    static Optional<String> packFor(String choice) {
        return switch (choice) {
            case "Evade Shaders" -> findPack(EVADE_PREFIXES[0]).or(() -> findPack(EVADE_PREFIXES[1])).or(() -> findPack(EVADE_PREFIXES[2]));
            case "Photon" -> findPack("photon");
            default -> findPack("complementaryreimagined").or(() -> findPack("complementary"));
        };
    }

    /**
     * Applies a choice: "Off", "Evade Shaders", "Photon" or "Complementary". Returns a message for the player (what
     * happened, or why nothing could).
     */
    public static String apply(String choice) {
        if (!irisLoaded()) return "Shaders need Iris. Run the Evade installer again with Shaders switched on.";
        try {
            Object cfg = config();
            Class<?> c = cfg.getClass();
            if (choice.equals("Off")) {
                c.getMethod("setShadersEnabled", boolean.class).invoke(cfg, false);
            } else {
                Optional<String> pack = packFor(choice);
                if (pack.isEmpty()) return choice + " isn't in your shaderpacks folder. Run the Evade installer again with Shaders on.";
                c.getMethod("setShaderPackName", String.class).invoke(cfg, pack.get());
                c.getMethod("setShadersEnabled", boolean.class).invoke(cfg, true);
            }
            c.getMethod("save").invoke(cfg);
            Method reload = Class.forName("net.irisshaders.iris.Iris").getMethod("reload");
            reload.invoke(null);
            return choice.equals("Off") ? "Shaders off" : choice + " shaders on";
        } catch (Throwable t) {
            EvadeClient.LOG.warn("Shaders: couldn't switch to {}", choice, t);
            return "Couldn't switch shaders: " + t.getClass().getSimpleName();
        }
    }

    // ------------------------------------------------------------------ Evade Shaders on integrated graphics

    /** The quality level Evade Shaders starts on with integrated graphics. */
    public static final String LAPTOP_PROFILE = "LOW";

    /**
     * With integrated graphics: when Evade Shaders is the active pack and the player has never changed its settings
     * (no shaderpacks/&lt;pack&gt;.txt yet), starts it on its {@value #LAPTOP_PROFILE} level, then reloads Iris if shaders
     * are on. Does nothing without Iris, on other graphics, or for any other pack. Render thread.
     *
     * @return whether the level was set
     */
    public static boolean laptopProfile() {
        if (!irisLoaded() || GpuInfo.kind() != GpuInfo.Kind.INTEGRATED) return false;
        Optional<String> pack = selectedPack();
        if (pack.isEmpty() || !isEvadePack(pack.get())) return false;
        if (!setProfileIfUntouched(packsDir(), pack.get(), LAPTOP_PROFILE)) return false;
        EvadeClient.LOG.info("Shaders: {} starts on its {} level for integrated graphics", pack.get(), LAPTOP_PROFILE);
        if (enabled()) {
            try {
                Class.forName("net.irisshaders.iris.Iris").getMethod("reload").invoke(null);
            } catch (Throwable t) {
                EvadeClient.LOG.warn("Shaders: couldn't reload Iris", t);
            }
        }
        return true;
    }

    /**
     * Writes a pack's Iris settings file (shaderpacks/&lt;pack&gt;.txt) for the quality {@code profile} its
     * shaders.properties defines, when the file doesn't exist yet (the player never changed a setting). The file holds
     * {@code profile=NAME} and the profile's option values, which is what Iris reads. Works on a zip or an unzipped
     * pack folder; false when the pack, its shaders.properties or the profile can't be found, or the file exists.
     */
    public static boolean setProfileIfUntouched(Path dir, String pack, String profile) {
        if (dir == null || pack == null || pack.contains("/") || pack.contains("\\")) return false;
        Path packPath = dir.resolve(pack), txt = dir.resolve(pack + ".txt");
        if (Files.exists(txt)) return false;
        try {
            java.util.Properties props = readPackProperties(packPath);
            if (props == null) return false;
            java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
            if (!profileValues(props, profile, values, 0)) return false;
            StringBuilder out = new StringBuilder("#Evade Shaders: the " + profile + " level, set by Evade Client for integrated graphics\n");
            out.append("profile=").append(profile).append('\n');
            for (var e : values.entrySet()) out.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            Path tmp = dir.resolve(pack + ".txt.evade-tmp");
            Files.writeString(tmp, out.toString(), java.nio.charset.StandardCharsets.ISO_8859_1);
            try {
                Files.move(tmp, txt, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.io.IOException e) {
                Files.deleteIfExists(tmp);
                return false;
            }
            return true;
        } catch (Exception e) {
            EvadeClient.LOG.warn("Shaders: couldn't set {} on {}", profile, pack, e);
            return false;
        }
    }

    /** shaders/shaders.properties from a pack zip or folder, or null. */
    private static java.util.Properties readPackProperties(Path pack) throws java.io.IOException {
        java.util.Properties p = new java.util.Properties();
        if (Files.isDirectory(pack)) {
            Path f = pack.resolve("shaders").resolve("shaders.properties");
            if (!Files.isRegularFile(f)) return null;
            try (var in = Files.newInputStream(f)) { p.load(in); }
            return p;
        }
        if (!Files.isRegularFile(pack)) return null;
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(pack.toFile())) {
            java.util.zip.ZipEntry e = z.getEntry("shaders/shaders.properties");
            if (e == null) return null;
            try (var in = z.getInputStream(e)) { p.load(in); }
        }
        return p;
    }

    /**
     * A profile's option values from shaders.properties ({@code profile.LOW=SHADOWS !BLOOM shadowDistance=64.0
     * profile.OTHER ...}): {@code NAME} is true, {@code !NAME} false, {@code NAME=value} that value, and another
     * profile's values are included. False when the profile isn't defined.
     */
    static boolean profileValues(java.util.Properties props, String profile, java.util.Map<String, String> out, int depth) {
        String line = props.getProperty("profile." + profile);
        if (line == null || depth > 8) return false;
        for (String t : line.trim().split("\\s+")) {
            if (t.isEmpty()) continue;
            if (t.startsWith("profile.")) profileValues(props, t.substring(8), out, depth + 1);
            else if (t.startsWith("!")) out.put(t.substring(1), "false");
            else if (t.contains("=")) out.put(t.substring(0, t.indexOf('=')), t.substring(t.indexOf('=') + 1));
            else if (t.contains(":")) out.put(t.substring(0, t.indexOf(':')), t.substring(t.indexOf(':') + 1));
            else out.put(t, "true");
        }
        return true;
    }

    private static Object config() throws Exception {
        return Class.forName("net.irisshaders.iris.Iris").getMethod("getIrisConfig").invoke(null);
    }
}
