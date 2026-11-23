package net.evadeclient.util;

import net.evadeclient.EvadeClient;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Turns Iris shaders on or off and switches between the packs the installer adds (Photon, and the lighter
 * Complementary Reimagined), without opening Iris' own screens. Iris is optional: everything here is reflection and
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
        if (n.startsWith("complementary")) return "Complementary";
        return file.replaceAll("\\.zip$", "");
    }

    /**
     * Applies a choice: "Off", "Photon" or "Complementary". Returns a message for the player (what happened, or why
     * nothing could).
     */
    public static String apply(String choice) {
        if (!irisLoaded()) return "Shaders need Iris. Run the Evade installer again with Shaders switched on.";
        try {
            Object cfg = config();
            Class<?> c = cfg.getClass();
            if (choice.equals("Off")) {
                c.getMethod("setShadersEnabled", boolean.class).invoke(cfg, false);
            } else {
                Optional<String> pack = findPack(choice.equals("Photon") ? "photon" : "complementary");
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

    private static Object config() throws Exception {
        return Class.forName("net.irisshaders.iris.Iris").getMethod("getIrisConfig").invoke(null);
    }
}
