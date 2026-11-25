package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.util.ShaderSwitch;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Evade Shaders (shaders/evade-shaders/): checks the pack loads in Iris and draws the world, and (in its own mode)
 * films every quality profile in a set of scenes for visual review and frame-time comparison.
 * <ul>
 *   <li>{@link #run}: part of the normal suite. Loads the pack on its MEDIUM defaults, checks Iris reports no shader
 *       error and the world isn't drawn black, then puts the previous shader state back. Skipped without Iris.</li>
 *   <li>{@link #film}: {@code -Devade.shadershots=true} (via JAVA_TOOL_OPTIONS). Builds a scene (pond, trees, glass,
 *       lights, a closed cave, the Nether, the End), then for shaders off, POTATO..ULTRA and any other packs named in
 *       {@code -Devade.shadershots.compare=a.zip,b.zip} takes screenshots and times frames. Output:
 *       {@code -Devade.shadershots.dir} (default ../evade-shaders-shots) with timings.txt.</li>
 * </ul>
 */
final class EvadeShadersTest {
    private static final String PACK_FILE = "Evade Shaders (test).zip";

    private EvadeShadersTest() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    // ------------------------------------------------------------------ pack plumbing

    /** shaders/evade-shaders/ in the repository (the run directory is build/run/clientGameTest). */
    private static Path packSource() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++, p = p.getParent()) {
            Path c = p.resolve("shaders/evade-shaders");
            if (Files.isDirectory(c.resolve("shaders"))) return c;
        }
        return null;
    }

    /** Zips the pack source into shaderpacks/ (the same layout scripts/build-evade-shaders.py produces). */
    private static void installPack(ClientGameTestContext ctx, Path src) throws IOException {
        Path dir = ctx.computeOnClient(c -> ShaderSwitch.packsDir());
        Files.createDirectories(dir);
        Path zip = dir.resolve(PACK_FILE);
        try (OutputStream o = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(o);
             Stream<Path> files = Files.walk(src.resolve("shaders"))) {
            for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                z.putNextEntry(new ZipEntry("shaders/" + src.resolve("shaders").relativize(f).toString().replace('\\', '/')));
                z.write(Files.readAllBytes(f));
                z.closeEntry();
            }
            z.putNextEntry(new ZipEntry("LICENSE"));
            z.write(Files.readAllBytes(src.resolve("LICENSE")));
            z.closeEntry();
        }
    }

    /** Option values of each quality profile, read from shaders.properties (same rules as Iris' profiles). */
    static Map<String, Properties> profiles(Path src) throws IOException {
        String text = Files.readString(src.resolve("shaders/shaders.properties")).replace("\\\n", " ");
        Map<String, Properties> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(?m)^profile\\.(\\w+)\\s*=\\s*(.*)$").matcher(text);
        while (m.find()) {
            Properties p = new Properties();
            for (String tok : m.group(2).trim().split("\\s+")) {
                if (tok.startsWith("profile.")) p.putAll(out.get(tok.substring(8)));
                else if (tok.contains("=")) p.setProperty(tok.substring(0, tok.indexOf('=')), tok.substring(tok.indexOf('=') + 1));
                else if (tok.startsWith("!")) p.setProperty(tok.substring(1), "false");
                else p.setProperty(tok, "true");
            }
            out.put(m.group(1), p);
        }
        return out;
    }

    private static Object iris(String method, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        return Class.forName("net.irisshaders.iris.Iris").getMethod(method, types).invoke(null, args);
    }

    /** Switches to {@code file} (null = shaders off) with the given option values; returns Iris' stored error, if any. */
    private static String apply(ClientGameTestContext ctx, String file, Properties options) {
        return ctx.computeOnClient(c -> {
            try {
                if (options != null) {
                    iris("queueShaderPackOptionsFromProperties", new Class<?>[]{Properties.class}, options);
                }
                String msg = ShaderSwitch.applyPack(file);
                Object err = iris("getStoredError", new Class<?>[0]);
                if (err instanceof java.util.Optional<?> o && o.isPresent()) return String.valueOf(o.get());
                return msg.startsWith("Couldn't") ? msg : "";
            } catch (ReflectiveOperationException e) {
                return "reflection: " + e;
            }
        });
    }

    private static double meanBrightness(Path png) {
        try {
            BufferedImage img = ImageIO.read(png.toFile());
            long sum = 0;
            int n = 0;
            for (int y = 0; y < img.getHeight(); y += 4) for (int x = 0; x < img.getWidth(); x += 4) {
                int rgb = img.getRGB(x, y);
                sum += ((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255);
                n += 3;
            }
            return (double) sum / n;
        } catch (IOException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ suite check

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        Path src = packSource();
        if (!ctx.computeOnClient(c -> ShaderSwitch.irisLoaded()) || src == null) {
            EvadeClient.LOG.info("[gametest] SKIP: Evade Shaders check (Iris or the pack source not available)");
            return;
        }
        String before = ctx.computeOnClient(c -> ShaderSwitch.enabled() ? ShaderSwitch.selectedPack().orElse(null) : null);
        try {
            installPack(ctx, src);
            check(profiles(src).keySet().equals(java.util.Set.of("POTATO", "LOW", "MEDIUM", "HIGH", "ULTRA")),
                    "Evade Shaders defines the POTATO, LOW, MEDIUM, HIGH and ULTRA profiles");
            sp.getServer().runCommand("time set 6000");
            String err = apply(ctx, PACK_FILE, null);
            check(err.isEmpty(), "Evade Shaders loads in Iris without shader errors (" + err + ")");
            ctx.waitTicks(10);
            boolean inUse = ctx.computeOnClient(c -> ShaderSwitch.inUse());
            check(inUse, "Iris draws the world with Evade Shaders");
            double lum = meanBrightness(ctx.takeScreenshot("evade-shaders-medium"));
            check(lum > 8, String.format(Locale.ROOT, "Evade Shaders draws a visible daytime world (mean brightness %.1f)", lum));
        } catch (IOException e) {
            throw new AssertionError("FAILED: Evade Shaders pack files: " + e);
        } finally {
            apply(ctx, before, null);
            Path dir = ctx.computeOnClient(c -> ShaderSwitch.packsDir());
            try { Files.deleteIfExists(dir.resolve(PACK_FILE)); Files.deleteIfExists(dir.resolve(PACK_FILE + ".txt")); } catch (IOException ignored) {}
        }
    }

    // ------------------------------------------------------------------ screenshot + timing mode

    private record Scene(String name, String dimension, BlockPos pos, float yaw, float pitch, String time, String weather) {}

    static void film(ClientGameTestContext ctx) {
        Path out = Path.of(System.getProperty("evade.shadershots.dir", "../evade-shaders-shots")).toAbsolutePath();
        Path src = packSource();
        if (src == null) throw new AssertionError("FAILED: shaders/evade-shaders not found from " + System.getProperty("user.dir"));
        try (TestSingleplayerContext sp = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(c -> {
            c.setSeed("evadeshaders");
            c.setCheatsEnabled(true);
            c.setGenerateStructures(false);
        }).create()) {
            Files.createDirectories(out);
            installPack(ctx, src);
            new Filming(ctx, sp, out, src).run();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final class Filming {
        final ClientGameTestContext ctx;
        final TestSingleplayerContext sp;
        final Path out;
        final Path src;
        final List<String> timings = new ArrayList<>();

        Filming(ClientGameTestContext ctx, TestSingleplayerContext sp, Path out, Path src) {
            this.ctx = ctx; this.sp = sp; this.out = out; this.src = src;
        }

        void cmd(String c) {
            try { sp.getServer().runCommand(c); } catch (Throwable t) { System.out.println("[shadershots] command failed: " + c + " (" + t.getMessage() + ")"); }
        }

        /** A flat grassy plains spot (same idea as Footage.spot, simpler). */
        BlockPos spot() {
            return sp.getServer().computeOnServer(server -> {
                var w = server.getOverworld();
                var found = w.locateBiome(b -> b.matchesKey(net.minecraft.world.biome.BiomeKeys.PLAINS),
                        new BlockPos(0, 80, 0), 3000, 32, 64);
                BlockPos c = found != null ? found.getFirst() : new BlockPos(0, 80, 0);
                for (int dx = -3; dx <= 1; dx++) for (int dz = -2; dz <= 2; dz++) w.getChunk((c.getX() >> 4) + dx, (c.getZ() >> 4) + dz); // generate
                int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ());
                return new BlockPos(c.getX(), y, c.getZ());
            });
        }

        List<Scene> build() {
            BlockPos p = spot();
            int x = p.getX(), y = p.getY(), z = p.getZ();
            System.out.println("[shadershots] scene at " + p);
            cmd("gamerule doDaylightCycle false");
            cmd("gamerule doWeatherCycle false");
            cmd("gamerule doMobSpawning false");
            cmd("gamemode creative @p");
            // Level ground west of the player (the sun sets in the west), then a pond, trees, glass and lights.
            cmd(String.format("fill %d %d %d %d %d %d air", x - 40, y, z - 20, x + 4, y + 12, z + 20));
            cmd(String.format("fill %d %d %d %d %d %d grass_block", x - 40, y - 1, z - 20, x + 4, y - 1, z + 20));
            cmd(String.format("fill %d %d %d %d %d %d dirt", x - 40, y - 4, z - 20, x + 4, y - 2, z + 20));
            cmd(String.format("fill %d %d %d %d %d %d sand", x - 22, y - 5, z - 9, x - 5, y - 4, z + 9));
            cmd(String.format("fill %d %d %d %d %d %d water", x - 21, y - 3, z - 8, x - 6, y - 1, z + 8));
            for (int[] t : new int[][]{{-28, -8}, {-30, 4}, {-26, 12}, {-33, -14}, {-12, 14}, {-14, -14}})
                cmd(String.format("place feature minecraft:%s %d %d %d", (t[0] + t[1]) % 3 == 0 ? "birch" : "oak", x + t[0], y, z + t[1]));
            cmd(String.format("fill %d %d %d %d %d %d short_grass replace air", x - 4, y, z - 6, x + 2, y, z + 6));
            cmd(String.format("fill %d %d %d %d %d %d light_blue_stained_glass", x - 4, y, z + 7, x - 1, y + 3, z + 7));
            cmd(String.format("fill %d %d %d %d %d %d glass", x - 4, y, z - 7, x - 1, y + 3, z - 7));
            cmd(String.format("setblock %d %d %d lantern", x - 3, y, z - 3));
            cmd(String.format("setblock %d %d %d torch", x - 3, y, z + 3));
            cmd(String.format("setblock %d %d %d glowstone", x - 5, y, z + 5));
            cmd(String.format("setblock %d %d %d poppy", x - 2, y, z - 1));
            cmd(String.format("setblock %d %d %d dandelion", x - 2, y, z + 1));
            // A sealed cave 40 blocks down: sky light 0, lit only by a torch, a lantern and a little lava.
            int cy = y - 40;
            cmd(String.format("fill %d %d %d %d %d %d stone", x - 9, cy - 1, z - 9, x + 9, cy + 7, z + 9));
            cmd(String.format("fill %d %d %d %d %d %d air", x - 8, cy, z - 8, x + 8, cy + 6, z + 8));
            cmd(String.format("fill %d %d %d %d %d %d lava", x - 7, cy - 1, z - 7, x - 5, cy - 1, z - 5));
            cmd(String.format("setblock %d %d %d torch", x - 6, cy, z + 4));
            cmd(String.format("setblock %d %d %d lantern", x + 3, cy, z - 4));
            cmd(String.format("setblock %d %d %d iron_ore", x - 8, cy + 1, z));
            // The Nether: a carved hall over a lava lake; the End: a platform by the main island.
            cmd("execute in minecraft:the_nether run forceload add -16 -16 16 16");
            cmd("execute in minecraft:the_nether run fill -14 34 -14 14 56 14 air");
            cmd("execute in minecraft:the_nether run fill -14 33 -14 14 33 14 lava");
            cmd("execute in minecraft:the_nether run fill -2 33 -2 2 40 2 netherrack");
            cmd("execute in minecraft:the_nether run fill -6 56 -6 6 56 6 glowstone");
            cmd("execute in minecraft:the_end run forceload add 90 -10 110 10");
            cmd("execute in minecraft:the_end run fill 98 48 -2 102 48 2 obsidian");

            String ow = "minecraft:overworld";
            BlockPos eye = new BlockPos(x, y, z);
            return List.of(
                    new Scene("day", ow, eye, 90, 8, "6000", "clear"),
                    new Scene("sunset", ow, eye, 90, -2, "12400", "clear"),
                    new Scene("night", ow, eye, 90, 8, "18000", "clear"),
                    new Scene("water", ow, new BlockPos(x - 4, y + 2, z - 4), 60, 28, "4000", "clear"),
                    new Scene("rain", ow, eye, 90, 8, "6000", "thunder"),
                    new Scene("cave", ow, new BlockPos(x + 6, cy, z + 6), 135, 10, "6000", "clear"),
                    new Scene("nether", "minecraft:the_nether", new BlockPos(0, 41, 0), 45, 15, "6000", "clear"),
                    new Scene("end", "minecraft:the_end", new BlockPos(100, 49, 0), 90, -5, "6000", "clear"));
        }

        void go(Scene s) {
            cmd("weather " + s.weather());
            cmd("time set " + s.time());
            cmd(String.format(Locale.ROOT, "execute in %s run tp @p %d.5 %d %d.5 %.1f %.1f", s.dimension(),
                    s.pos().getX(), s.pos().getY(), s.pos().getZ(), s.yaw(), s.pitch()));
            ctx.waitTicks(5);
            try { sp.getClientWorld().waitForChunksRender(); }
            catch (AssertionError e) { System.out.println("[shadershots] chunks still building for " + s.name()); }
            // Rain/thunder strength ramps up over a few seconds; TAA/eye adaptation need a few frames too.
            ctx.waitTicks(s.weather().equals("clear") ? 30 : 140);
        }

        /** Milliseconds per client frame (one tick renders one frame in the gametest loop), averaged. */
        double frameMs() {
            ctx.waitTicks(20);
            long t0 = System.nanoTime();
            ctx.waitTicks(60);
            return (System.nanoTime() - t0) / 60 / 1e6;
        }

        void shoot(String config, List<Scene> scenes, boolean time) throws IOException {
            for (Scene s : scenes) {
                go(s);
                if (time && s.name().equals("day")) {
                    double ms = frameMs();
                    timings.add(String.format(Locale.ROOT, "%-28s %8.1f ms/frame", config, ms));
                    System.out.println("[shadershots] " + config + " day: " + ms + " ms/frame");
                }
                Path shot = ctx.takeScreenshot("shadershot-" + config + "-" + s.name());
                Files.copy(shot, out.resolve(config + "-" + s.name() + ".png"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.write(out.resolve("timings.txt"), timings);
        }

        void run() throws IOException {
            ctx.runOnClient(c -> {
                c.options.hudHidden = true;
                c.options.getViewDistance().setValue(8);
            });
            List<Scene> scenes = build();
            String only = System.getProperty("evade.shadershots.only", "");
            List<Scene> chosen = only.isEmpty() ? scenes : scenes.stream().filter(s -> only.contains(s.name())).toList();
            String profilesWanted = System.getProperty("evade.shadershots.profiles", "OFF,POTATO,LOW,MEDIUM,HIGH,ULTRA");
            Map<String, Properties> profiles = profiles(src);
            for (String prof : profilesWanted.split(",")) {
                String err;
                if (prof.equals("OFF")) err = apply(ctx, null, null);
                else err = apply(ctx, PACK_FILE, profiles.get(prof));
                System.out.println("[shadershots] profile " + prof + (err.isEmpty() || err.equals("Shaders off") ? "" : " ERROR " + err));
                if (!err.isEmpty() && !prof.equals("OFF")) timings.add(prof + " failed to load: " + err);
                shoot(prof, chosen, true);
            }
            for (String other : System.getProperty("evade.shadershots.compare", "").split(",")) {
                if (other.isBlank()) continue;
                Path dir = ctx.computeOnClient(c -> ShaderSwitch.packsDir());
                Path from = Path.of(other);
                Files.copy(from, dir.resolve(from.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                String err = apply(ctx, from.getFileName().toString(), null);
                String label = from.getFileName().toString().replaceAll("[^A-Za-z0-9]+", "_");
                System.out.println("[shadershots] compare " + label + " " + err);
                shoot(label, chosen.stream().filter(s -> s.name().equals("day") || s.name().equals("sunset")
                        || s.name().equals("water")).toList(), true);
            }
            apply(ctx, null, null);
            ctx.runOnClient(c -> c.options.hudHidden = false);
            Files.write(out.resolve("timings.txt"), timings);
        }
    }
}
