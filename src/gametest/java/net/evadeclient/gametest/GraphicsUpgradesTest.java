package net.evadeclient.gametest;

import com.google.gson.JsonElement;
import net.evadeclient.EvadeClient;
import net.evadeclient.gui.ModBrowserScreen;
import net.evadeclient.gui.PackFeaturesScreen;
import net.evadeclient.gui.ShaderPackScreen;
import net.evadeclient.module.misc.PackFeatures;
import net.evadeclient.module.mods.Performance;
import net.evadeclient.module.visuals.BetterTooltips;
import net.evadeclient.module.visuals.tooltip.BannerPreviewData;
import net.evadeclient.module.visuals.tooltip.BookPreviewData;
import net.evadeclient.module.visuals.tooltip.MapPreviewData;
import net.evadeclient.mods.ContentInstaller;
import net.evadeclient.mods.ModrinthApi.ContentType;
import net.evadeclient.setting.Setting;
import net.evadeclient.setting.StatusSetting;
import net.evadeclient.util.Benchmark;
import net.evadeclient.util.BenchmarkAll;
import net.evadeclient.util.Borderless;
import net.evadeclient.util.ShaderSwitch;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.option.TextureFilteringMode;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.map.MapState;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * v2.3 graphics, performance and pack support: FPS Mode presets with the newer video options, Sharper Distant Textures,
 * Benchmark Every FPS Mode, Borderless Fullscreen, the shader pack picker, Pack Features (OptiFine-format detection and
 * the x-ray warning), the Mod Browser's resource pack / shader / data pack tabs, and the map, book and banner tooltip
 * previews. Everything is put back as it was; the test ends with no screen open.
 */
public final class GraphicsUpgradesTest {
    private GraphicsUpgradesTest() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        testPresets(ctx);
        testBenchmarkAll(ctx);
        testBorderless(ctx);
        testShaderPicker(ctx);
        testPackFeatures(ctx);
        testModBrowserTypes(ctx);
        testTooltipPreviews(ctx, sp);
        ctx.setScreen(() -> null);
    }

    // ---------------------------------------------------------------- presets

    private static Map<String, JsonElement> snapshot(Performance p) {
        Map<String, JsonElement> m = new LinkedHashMap<>();
        for (Setting<?> s : p.getSettings()) if (!(s instanceof StatusSetting)) m.put(s.getName(), s.toJson());
        return m;
    }

    private static void restore(Performance p, Map<String, JsonElement> m) {
        for (Setting<?> s : p.getSettings()) if (m.containsKey(s.getName())) s.loadJson(m.get(s.getName()));
    }

    private static void testPresets(ClientGameTestContext ctx) {
        Performance perf = ctx.computeOnClient(c -> EvadeClient.modules.get(Performance.class));
        boolean wasOn = ctx.computeOnClient(c -> perf.isEnabled());
        Map<String, JsonElement> saved = ctx.computeOnClient(c -> snapshot(perf));
        Object[] before = ctx.computeOnClient(c -> new Object[]{c.options.getTextureFiltering().getValue(), c.options.getVignette().getValue(),
                c.options.getWeatherRadius().getValue(), c.options.getCloudRenderDistance().getValue(), c.options.getImprovedTransparency().getValue()});
        ctx.runOnClient(c -> perf.setEnabled(false)); // hand every option back before setting the test's own values
        ctx.waitTicks(1);
        ctx.runOnClient(c -> {
            c.options.getTextureFiltering().setValue(TextureFilteringMode.NONE);
            c.options.getVignette().setValue(true);
            c.options.getWeatherRadius().setValue(10);
            c.options.getCloudRenderDistance().setValue(64);
            perf.setEnabled(true);
            perf.fpsMode.set("Max FPS");
        });
        ctx.waitTicks(5);
        Object[] max = ctx.computeOnClient(c -> new Object[]{c.options.getTextureFiltering().getValue(), c.options.getVignette().getValue(),
                c.options.getWeatherRadius().getValue(), c.options.getCloudRenderDistance().getValue(), c.options.getImprovedTransparency().getValue(),
                perf.sharperTextures.get()});
        check(max[0] == TextureFilteringMode.NONE && max[1].equals(false) && max[2].equals(3) && (int) max[3] <= 8 && max[4].equals(false)
                        && max[5].equals(false),
                "Max FPS sets the newer video options (filtering none, no vignette, weather radius 3, clouds <= 8, no improved transparency): "
                        + java.util.Arrays.toString(max));

        ctx.runOnClient(c -> perf.fpsMode.set("Quality"));
        ctx.waitTicks(5);
        Object[] q = ctx.computeOnClient(c -> new Object[]{c.options.getTextureFiltering().getValue(), c.options.getVignette().getValue(),
                c.options.getWeatherRadius().getValue(), c.options.getCloudRenderDistance().getValue(), c.options.getImprovedTransparency().getValue()});
        check(q[0] == TextureFilteringMode.ANISOTROPIC && q[1].equals(true) && q[2].equals(10) && q[3].equals(64) && q[4].equals(before[4]),
                "Quality turns on anisotropic filtering and hands every other option back (" + java.util.Arrays.toString(q) + ")");

        ctx.runOnClient(c -> perf.setEnabled(false));
        ctx.waitTicks(2);
        Object filtering = ctx.computeOnClient(c -> c.options.getTextureFiltering().getValue());
        check(filtering == TextureFilteringMode.NONE, "Turning Performance off puts texture filtering back");

        ctx.runOnClient(c -> {
            c.options.getTextureFiltering().setValue((TextureFilteringMode) before[0]);
            c.options.getVignette().setValue((Boolean) before[1]);
            c.options.getWeatherRadius().setValue((Integer) before[2]);
            c.options.getCloudRenderDistance().setValue((Integer) before[3]);
            restore(perf, saved);
            perf.setEnabled(wasOn);
        });
    }

    // ---------------------------------------------------------------- benchmark

    private static void testBenchmarkAll(ClientGameTestContext ctx) {
        // The recommendation rule on its own.
        Map<String, Benchmark.Result> r = new LinkedHashMap<>();
        r.put("Quality", new Benchmark.Result(50, 30, 20, 15, 500, 10, ""));
        r.put("Balanced", new Benchmark.Result(90, 60, 40, 30, 900, 10, ""));
        r.put("Max FPS", new Benchmark.Result(140, 90, 60, 50, 1400, 10, ""));
        check(BenchmarkAll.recommend(r, 60).equals("Balanced"), "Benchmark recommends the best-looking mode that keeps up");
        check(BenchmarkAll.recommend(r, 144).equals("Max FPS"), "Benchmark falls back to the fastest mode when none keeps up");

        Performance perf = ctx.computeOnClient(c -> EvadeClient.modules.get(Performance.class));
        boolean wasOn = ctx.computeOnClient(c -> perf.isEnabled());
        Map<String, JsonElement> saved = ctx.computeOnClient(c -> snapshot(perf));
        ctx.runOnClient(c -> { perf.setEnabled(true); perf.fpsMode.set("Custom"); });
        ctx.waitTicks(2);
        boolean started = ctx.computeOnClient(c -> BenchmarkAll.start(5));
        check(started, "Benchmark Every FPS Mode starts in a world");
        for (int i = 0; i < 120 && ctx.computeOnClient(c -> BenchmarkAll.running()); i++) ctx.waitTicks(20);
        int n = ctx.computeOnClient(c -> BenchmarkAll.results().size());
        String mode = ctx.computeOnClient(c -> perf.fpsMode.get());
        String status = ctx.computeOnClient(c -> BenchmarkAll.status());
        EvadeClient.LOG.info("[gametest] BENCHMARK every FPS Mode: {} -> {}", BenchmarkAll.results(), status);
        check(n == 3 && mode.equals("Custom") && status.startsWith("Best for you"),
                "Benchmark Every FPS Mode measures all three and puts your FPS Mode back (" + n + ", " + mode + ", " + status + ")");
        ctx.runOnClient(c -> { restore(perf, saved); perf.setEnabled(wasOn); });
    }

    // ---------------------------------------------------------------- borderless

    private static void testBorderless(ClientGameTestContext ctx) {
        Performance perf = ctx.computeOnClient(c -> EvadeClient.modules.get(Performance.class));
        boolean wasOn = ctx.computeOnClient(c -> perf.isEnabled());
        int[] size = ctx.computeOnClient(c -> new int[]{c.getWindow().getWidth(), c.getWindow().getHeight()});
        ctx.runOnClient(c -> { perf.setEnabled(true); perf.borderless.set(true); });
        ctx.waitTicks(5);
        int[] on = ctx.computeOnClient(c -> new int[]{GLFW.glfwGetWindowAttrib(c.getWindow().getHandle(), GLFW.GLFW_DECORATED), Borderless.applied() ? 1 : 0});
        ctx.takeScreenshot("evade-borderless");
        ctx.runOnClient(c -> perf.borderless.set(false));
        ctx.waitTicks(5);
        int[] off = ctx.computeOnClient(c -> new int[]{GLFW.glfwGetWindowAttrib(c.getWindow().getHandle(), GLFW.GLFW_DECORATED), Borderless.applied() ? 1 : 0,
                c.getWindow().getWidth(), c.getWindow().getHeight()});
        ctx.runOnClient(c -> perf.setEnabled(wasOn));
        check(on[0] == GLFW.GLFW_FALSE && on[1] == 1, "Borderless Fullscreen takes the window's frame off");
        check(off[0] == GLFW.GLFW_TRUE && off[1] == 0, "Turning Borderless Fullscreen off gives the frame back");
        EvadeClient.LOG.info("[gametest] borderless window {}x{} -> {}x{}", size[0], size[1], off[2], off[3]);
        if (off[2] != size[0] || off[3] != size[1]) ctx.getInput().resizeWindow(size[0], size[1]);
    }

    // ---------------------------------------------------------------- shaders

    private static void testShaderPicker(ClientGameTestContext ctx) {
        Path dir = ctx.computeOnClient(c -> ShaderSwitch.packsDir());
        Path zip = dir.resolve("Evade Test Pack.zip"), folder = dir.resolve("Evade Folder Pack");
        try {
            Files.createDirectories(folder.resolve("shaders"));
            try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
                z.putNextEntry(new ZipEntry("shaders/"));
                z.closeEntry();
            }
            List<String> packs = ctx.computeOnClient(c -> ShaderSwitch.listPacks());
            check(packs.contains("Evade Test Pack.zip") && packs.contains("Evade Folder Pack"),
                    "The shader pack picker lists every pack in shaderpacks/, zips and folders (" + packs + ")");
            ctx.runOnClient(c -> c.setScreen(new ShaderPackScreen(null)));
            ctx.waitTicks(5);
            ctx.takeScreenshot("evade-shader-picker");
            int listed = ctx.computeOnClient(c -> c.currentScreen instanceof ShaderPackScreen s ? s.packsForTest().size() : -1);
            check(listed == packs.size(), "The Shader Pack picker opens with " + listed + " packs");
            boolean iris = ctx.computeOnClient(c -> ShaderSwitch.irisLoaded());
            if (iris) {
                boolean wasOn = ctx.computeOnClient(c -> ShaderSwitch.enabled());
                if (!wasOn) {
                    String msg = ctx.computeOnClient(c -> ShaderSwitch.applyPack(null));
                    check(msg.equals("Shaders off"), "Off in the picker turns shaders off (" + msg + ")");
                }
            }
            ctx.setScreen(() -> null);
        } catch (java.io.IOException e) {
            throw new AssertionError("FAILED: shader picker test files: " + e);
        } finally {
            try {
                Files.deleteIfExists(zip);
                Files.deleteIfExists(folder.resolve("shaders"));
                Files.deleteIfExists(folder);
            } catch (Exception ignored) { }
        }
    }

    // ---------------------------------------------------------------- packs

    private static byte[] png(int alpha) throws java.io.IOException {
        try (NativeImage img = new NativeImage(16, 16, false)) {
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) img.setColorArgb(x, y, (alpha << 24) | 0x777777);
            Path tmp = Files.createTempFile("evade-pack", ".png");
            img.writeTo(tmp);
            byte[] b = Files.readAllBytes(tmp);
            Files.deleteIfExists(tmp);
            return b;
        }
    }

    private static void testPackFeatures(ClientGameTestContext ctx) {
        java.util.Set<PackFeatures.Feature> f = java.util.EnumSet.noneOf(PackFeatures.Feature.class);
        for (String p : List.of("assets/minecraft/optifine/ctm/glass/1.png", "assets/minecraft/optifine/cit/sword.properties",
                "assets/minecraft/optifine/random/entity/cow/cow2.png", "assets/minecraft/optifine/cem/creeper.jem",
                "assets/minecraft/optifine/sky/world0/sky1.properties", "assets/minecraft/optifine/emissive.properties",
                "assets/minecraft/mcpatcher/ctm/stone/0.png", "assets/minecraft/textures/block/stone.png"))
            classifyForTest(p, f);
        check(f.containsAll(List.of(PackFeatures.Feature.CONNECTED, PackFeatures.Feature.CIT, PackFeatures.Feature.RANDOM_ENTITIES,
                PackFeatures.Feature.ENTITY_MODELS, PackFeatures.Feature.SKY, PackFeatures.Feature.EMISSIVE)) && f.size() == 6,
                "Pack Features recognises the OptiFine and MCPatcher folders (" + f + ")");

        Path dir = ctx.computeOnClient(c -> c.getResourcePackDir());
        Path pack = dir.resolve("Evade Test XRay.zip"), plain = dir.resolve("Evade Test Plain.zip");
        try {
            Files.createDirectories(dir);
            byte[] clear = png(0), solid = png(255);
            try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(pack))) {
                put(z, "pack.mcmeta", "{\"pack\":{\"pack_format\":75,\"description\":\"test\"}}".getBytes());
                put(z, "assets/minecraft/optifine/ctm/glass/0.png", solid);
                put(z, "assets/minecraft/optifine/sky/world0/sky1.properties", "source=./sky1.png".getBytes());
                for (String b : List.of("stone", "deepslate", "dirt")) put(z, "assets/minecraft/textures/block/" + b + ".png", clear);
            }
            try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(plain))) {
                put(z, "pack.mcmeta", "{\"pack\":{\"pack_format\":75,\"description\":\"test\"}}".getBytes());
                put(z, "assets/minecraft/textures/block/stone.png", solid);
                put(z, "assets/minecraft/textures/block/dirt.png", solid);
            }
            PackFeatures.Report x = PackFeatures.scan(pack), p = PackFeatures.scan(plain);
            check(x != null && x.xray() && x.features().contains(PackFeatures.Feature.CONNECTED) && x.features().contains(PackFeatures.Feature.SKY),
                    "Pack Features finds connected textures, a custom sky and see-through stone (" + x + ")");
            check(p != null && !p.xray() && p.features().isEmpty(), "A plain pack has no extra features and no x-ray warning (" + p + ")");
            boolean continuity = ctx.computeOnClient(c -> net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("continuity"));
            check(x.missing().contains(PackFeatures.Feature.CONNECTED) != continuity, "Missing mods are reported by whether they're loaded");

            ctx.runOnClient(c -> c.setScreen(new PackFeaturesScreen(null)));
            for (int i = 0; i < 40 && ctx.computeOnClient(c -> c.currentScreen instanceof PackFeaturesScreen s && s.reportsForTest() == null); i++) ctx.waitTicks(2);
            ctx.waitTicks(2);
            ctx.takeScreenshot("evade-pack-features");
            int reports = ctx.computeOnClient(c -> c.currentScreen instanceof PackFeaturesScreen s && s.reportsForTest() != null ? s.reportsForTest().size() : -1);
            check(reports >= 2, "The pack features list shows every pack (" + reports + ")");

            // The resource pack screen gets the "?" button next to the star.
            ctx.runOnClient(c -> c.setScreen(new PackScreen(c.getResourcePackManager(), m -> { }, c.getResourcePackDir(), Text.literal("Resource Packs"))));
            ctx.waitTicks(5);
            ctx.takeScreenshot("evade-pack-screen-buttons");
            boolean button = ctx.computeOnClient(c -> {
                if (c.currentScreen == null) return false;
                for (var w : Screens.getButtons(c.currentScreen)) if (w.getMessage().getString().equals("?")) return true;
                return false;
            });
            check(button, "The resource pack screen has the pack features button");
            ctx.setScreen(() -> null);
        } catch (java.io.IOException e) {
            throw new AssertionError("FAILED: pack features test files: " + e);
        } finally {
            try { Files.deleteIfExists(pack); Files.deleteIfExists(plain); } catch (Exception ignored) { }
        }
    }

    private static void classifyForTest(String path, java.util.Set<PackFeatures.Feature> out) {
        try {
            var m = PackFeatures.class.getDeclaredMethod("classify", String.class, java.util.Set.class);
            m.setAccessible(true);
            m.invoke(null, path, out);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("FAILED: classify: " + e);
        }
    }

    private static void put(ZipOutputStream z, String name, byte[] data) throws java.io.IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    // ---------------------------------------------------------------- mod browser

    private static void testModBrowserTypes(ClientGameTestContext ctx) {
        String[] folders = ctx.computeOnClient(c -> {
            try {
                return new String[]{ContentInstaller.folder(ContentType.RESOURCE_PACK, null).getFileName().toString(),
                        ContentInstaller.folder(ContentType.SHADER, null).getFileName().toString()};
            } catch (java.io.IOException e) {
                return new String[]{"", ""};
            }
        });
        check(folders[0].equals("resourcepacks") && folders[1].equals("shaderpacks"), "Resource packs and shaders install into their own folders");
        List<ContentInstaller.World> worlds = ctx.computeOnClient(c -> ContentInstaller.worlds(6));
        check(!worlds.isEmpty(), "Data packs can pick a singleplayer world (" + worlds.size() + " found)");
        String dp = ctx.computeOnClient(c -> {
            try {
                return ContentInstaller.folder(ContentType.DATA_PACK, worlds.get(0).folder()).toString().replace('\\', '/');
            } catch (java.io.IOException e) {
                return e.getMessage();
            }
        });
        check(dp.endsWith("/datapacks") && dp.contains("/saves/"), "A data pack goes into that world's datapacks folder (" + dp + ")");
        boolean refused = ctx.computeOnClient(c -> {
            try { ContentInstaller.folder(ContentType.DATA_PACK, "../escape"); return false; } catch (java.io.IOException e) { return true; }
        });
        check(refused, "A data pack can't be pointed outside the saves folder");

        List<String> results = new ArrayList<>();
        for (ContentType t : List.of(ContentType.RESOURCE_PACK, ContentType.SHADER, ContentType.DATA_PACK)) {
            ctx.runOnClient(c -> {
                ModBrowserScreen.setContentTypeForTest(t);
                ModBrowserScreen.setSearchForTest("", "downloads", null);
                c.setScreen(new ModBrowserScreen(null));
            });
            int count = -1;
            for (int i = 0; i < 75 && count < 0; i++) {
                ctx.waitTicks(4);
                count = ctx.computeOnClient(c -> c.currentScreen instanceof ModBrowserScreen s ? s.resultCountForTest() : -2);
            }
            ctx.waitTicks(10);
            ctx.runOnClient(c -> c.getToastManager().clear());
            ctx.takeScreenshot("evade-mod-browser-" + t.projectType);
            ContentType shown = ctx.computeOnClient(c -> ModBrowserScreen.contentType());
            check(shown == t, "The Mod Browser shows " + t + " (" + count + " results; 0 when Modrinth can't be reached)");
            results.add(t + "=" + count);
            ctx.setScreen(() -> null);
        }
        EvadeClient.LOG.info("[gametest] Mod Browser content tabs: {}", results);
        ctx.runOnClient(c -> ModBrowserScreen.setContentTypeForTest(ContentType.MOD));
    }

    // ---------------------------------------------------------------- tooltips

    private static void testTooltipPreviews(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var server = sp.getServer();
        server.runCommand("clear @a");
        server.runCommand("item replace entity @a hotbar.0 with minecraft:written_book[minecraft:written_book_content={title:\"Evade\",author:\"Test\",pages:[\"The first page of a test book, long enough to wrap onto a second line.\",\"Page two\"]}]");
        server.runCommand("item replace entity @a hotbar.1 with minecraft:white_banner[minecraft:banner_patterns=[{pattern:\"minecraft:stripe_top\",color:\"red\"},{pattern:\"minecraft:cross\",color:\"blue\"}]]");
        server.runCommand("item replace entity @a hotbar.2 with minecraft:flower_banner_pattern");
        server.runCommand("item replace entity @a hotbar.3 with minecraft:filled_map[minecraft:map_id=4242]");
        ctx.waitTicks(10);
        ctx.runOnClient(c -> {
            MapState state = MapState.of((byte) 0, true, World.OVERWORLD);
            for (int x = 0; x < 128; x++) for (int z = 0; z < 128; z++) state.colors[x + z * 128] = (byte) (((x / 16 + z / 16) % 2 == 0 ? 4 : 30) * 4 + 2);
            c.world.putClientsideMapState(new MapIdComponent(4242), state);
            EvadeClient.modules.get(BetterTooltips.class).setEnabled(true);
        });
        String[] kinds = ctx.computeOnClient(c -> {
            String[] out = new String[4];
            for (int i = 0; i < 4; i++) {
                var data = BetterTooltips.tooltipData(c.player.getInventory().getStack(i));
                out[i] = data == null ? "none" : data.getClass().getSimpleName();
            }
            return out;
        });
        check(kinds[0].equals(BookPreviewData.class.getSimpleName()), "A written book previews its first page (" + kinds[0] + ")");
        check(kinds[1].equals(BannerPreviewData.class.getSimpleName()), "A banner previews its patterns (" + kinds[1] + ")");
        check(kinds[2].equals(BannerPreviewData.class.getSimpleName()), "A banner pattern previews its pattern (" + kinds[2] + ")");
        check(kinds[3].equals(MapPreviewData.class.getSimpleName()), "A filled map previews its picture (" + kinds[3] + ")");

        // Hover each one in the inventory and take a picture (also proves the components draw without errors).
        ctx.runOnClient(c -> c.setScreen(new InventoryScreen(c.player)));
        ctx.waitTicks(5);
        String[] names = {"book", "banner", "banner-pattern", "map"};
        for (int i = 0; i < 4; i++) {
            int slot = i;
            double[] pos = ctx.computeOnClient(c -> {
                double scale = c.getWindow().getScaleFactor();
                int x0 = (c.getWindow().getScaledWidth() - 176) / 2, y0 = (c.getWindow().getScaledHeight() - 166) / 2;
                return new double[]{(x0 + 8 + slot * 18 + 8) * scale, (y0 + 142 + 8) * scale};
            });
            ctx.getInput().setCursorPos(pos[0], pos[1]);
            ctx.waitTicks(3);
            ctx.takeScreenshot("evade-tooltip-" + names[i]);
        }
        ctx.setScreen(() -> null);
        server.runCommand("clear @a");
    }
}
