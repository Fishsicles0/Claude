package net.evadeclient.gametest;

import com.google.gson.JsonElement;
import net.evadeclient.EvadeClient;
import net.evadeclient.gui.BenchmarkResultScreen;
import net.evadeclient.gui.FirstRun;
import net.evadeclient.hud.Hud;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.Module;
import net.evadeclient.module.client.HudToggle;
import net.evadeclient.module.hud.ProfilerHud;
import net.evadeclient.module.mods.Performance;
import net.evadeclient.setting.Setting;
import net.evadeclient.util.BenchmarkScene;
import net.evadeclient.util.BootTimer;
import net.evadeclient.util.FramePacer;
import net.evadeclient.util.FrameStats;
import net.evadeclient.util.GpuInfo;
import net.evadeclient.util.HudCache;
import net.evadeclient.util.PowerState;
import net.evadeclient.util.RenderScale;
import net.evadeclient.util.SodiumBridge;
import net.evadeclient.util.TextureWarmup;
import net.evadeclient.util.Upscaler;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * v2.5 performance and graphics: GPU detection and the laptop profile, Evade's upscaler, the HUD cache (with its
 * measured per-frame cost), the Sodium bridge and its crash-safe restore, the benchmark scene's flight and result card,
 * the battery cap and Smooth Pacing, start-up timing and texture warm-up, and HUD Toggle (key and screenshots).
 * Measured numbers are logged with "[gametest] PERF". Leaves every setting, module and file as it found them.
 */
public final class PerfV25Test {
    private PerfV25Test() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    private static void perf(String fmt, Object... args) {
        EvadeClient.LOG.info("[gametest] PERF " + String.format(Locale.ROOT, fmt, args));
    }

    private static Map<String, JsonElement> snapshot(Module m) {
        Map<String, JsonElement> out = new LinkedHashMap<>();
        for (Setting<?> s : m.getSettings()) out.put(s.getName(), s.toJson());
        return out;
    }

    private static void restore(Module m, Map<String, JsonElement> snap) {
        for (Setting<?> s : m.getSettings()) if (snap.containsKey(s.getName())) s.loadJson(snap.get(s.getName()));
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        Map<String, JsonElement> perfSnap = ctx.computeOnClient(c -> snapshot(EvadeClient.modules.get(Performance.class)));
        Map<String, JsonElement> toggleSnap = ctx.computeOnClient(c -> snapshot(EvadeClient.modules.get(HudToggle.class)));
        boolean perfWas = ctx.computeOnClient(c -> EvadeClient.modules.get(Performance.class).isEnabled());
        try {
            gpu(ctx);
            boot(ctx);
            sodium(ctx);
            powerAndPacing(ctx);
            hudCache(ctx);
            hudToggle(ctx);
            benchmark(ctx, sp);
        } finally {
            ctx.runOnClient(c -> {
                GpuInfo.forcedRenderer = null;
                PowerState.forced = null;
                HudCache.forceOff = false;
                Performance p = EvadeClient.modules.get(Performance.class);
                restore(p, perfSnap);
                p.setEnabled(perfWas);
                HudToggle t = EvadeClient.modules.get(HudToggle.class);
                restore(t, toggleSnap);
                t.setHidden(false);
                EvadeClient.config.save();
            });
            ctx.waitTicks(5);
        }
    }

    // ------------------------------------------------------------------ 1: GPU detection and the laptop profile

    private static void gpu(ClientGameTestContext ctx) {
        String[][] cases = {
                {"AMD Radeon 780M Graphics", "INTEGRATED"}, {"AMD Radeon(TM) Graphics", "INTEGRATED"},
                {"AMD Radeon 780M (radeonsi, gfx1103_r1, LLVM 18.1.8, DRM 3.57)", "INTEGRATED"},
                {"AMD Radeon(TM) 760M", "INTEGRATED"}, {"Radeon RX Vega 8 Graphics", "INTEGRATED"},
                {"AMD Radeon(TM) Vega 3 Graphics", "INTEGRATED"}, {"AMD Radeon 880M", "INTEGRATED"}, {"AMD Radeon(TM) 610M", "INTEGRATED"},
                {"AMD Radeon 740M Graphics", "INTEGRATED"}, {"AMD Radeon RX Vega 64", "DISCRETE"},
                {"Mesa Intel(R) Graphics (ADL GT2)", "INTEGRATED"}, {"Intel(R) Iris(R) Xe Graphics", "INTEGRATED"},
                {"Intel(R) UHD Graphics 620", "INTEGRATED"}, {"Intel(R) Arc(TM) Graphics", "INTEGRATED"},
                {"NVIDIA GeForce RTX 4060 Laptop GPU/PCIe/SSE2", "DISCRETE"}, {"AMD Radeon RX 7600 (radeonsi, navi33)", "DISCRETE"},
                {"Intel(R) Arc(TM) A770 Graphics", "DISCRETE"}, {"llvmpipe (LLVM 20.1.2, 256 bits)", "SOFTWARE"},
                {"", "UNKNOWN"}};
        List<String> wrong = new ArrayList<>();
        for (String[] c : cases) if (!GpuInfo.classify(c[0]).name().equals(c[1])) wrong.add(c[0] + " -> " + GpuInfo.classify(c[0]));
        check(wrong.isEmpty(), "GPU detection: renderer strings are sorted into integrated, discrete and software (" + wrong + ")");
        check(GpuInfo.shortName("AMD Radeon 780M (radeonsi, gfx1103_r1, LLVM 18.1.8, DRM 3.57)").equals("AMD Radeon 780M")
                        && GpuInfo.shortName("NVIDIA GeForce RTX 4060/PCIe/SSE2").equals("NVIDIA GeForce RTX 4060"),
                "GPU detection: the result card names the GPU without driver details");

        String live = ctx.computeOnClient(c -> GpuInfo.renderer() + " | " + GpuInfo.kind() + " | " + GpuInfo.cpuName() + " | " + GpuInfo.physicalCores());
        perf("graphics: %s", live);
        check(!live.startsWith(" |"), "GPU detection: the game reads its OpenGL renderer (" + live + ")");

        // The laptop profile, as on the owner's Ryzen 7 250 (Radeon 780M).
        String r = ctx.computeOnClient(c -> {
            GpuInfo.forcedRenderer = "AMD Radeon 780M Graphics";
            String did = FirstRun.autoProfile(true);
            String again = FirstRun.autoProfile(false); // same GPU: nothing more
            Performance p = EvadeClient.modules.get(Performance.class);
            String s = did + "|" + again + "|" + p.isEnabled() + "|" + p.fpsMode.get() + "|" + p.renderScale.getInt() + "|" + p.upscale.get()
                    + "|" + p.unlockFps.get() + "|" + p.entityDistance.getInt() + "|" + p.lowEnd.get() + "|" + p.tunedGpu.get();
            GpuInfo.forcedRenderer = "NVIDIA GeForce RTX 4070/PCIe/SSE2";
            String discrete = FirstRun.autoProfile(true);
            GpuInfo.forcedRenderer = null;
            return s + "|" + discrete;
        });
        check(r.equals("laptop|kept|true|Max FPS|65|Evade (best)|true|48|false|AMD Radeon 780M Graphics|kept"),
                "Laptop profile: integrated graphics get Max FPS, Render Scale 65% with Evade's upscaler, Unlock FPS and entity distance 48, once per GPU; a graphics card keeps its settings (" + r + ")");
        ctx.waitTicks(3);
    }

    // ------------------------------------------------------------------ 30: start-up

    private static void boot(ClientGameTestContext ctx) {
        String b = ctx.computeOnClient(c -> BootTimer.readyMs() + "|" + BootTimer.evadeInitMs() + "|" + BootTimer.heapMb());
        String[] p = b.split("\\|");
        long ready = Long.parseLong(p[0]), init = Long.parseLong(p[1]), heap = Long.parseLong(p[2]);
        List<Long> earlier = ctx.computeOnClient(c -> BootTimer.previousStarts());
        perf("boot: ready %d ms, Evade's set-up %d ms, %d MB at the title screen; earlier Evade set-ups %s", ready, init, heap, earlier);
        check(ready > 0 && init > 0 && heap > 0, "Start-up: boot time, Evade's set-up time and memory at the title screen are recorded (" + b + ")");
        // Budget: no more than 15% slower than the median of earlier starts on this machine (plus 150 ms for noise).
        if (earlier.size() >= 3) {
            List<Long> sorted = new ArrayList<>(earlier);
            sorted.sort(null);
            long median = sorted.get(sorted.size() / 2);
            long budget = Math.round(median * 1.15) + 150;
            check(init <= budget, "Start-up budget: Evade's set-up " + init + " ms is within 15% of earlier starts (median " + median + " ms, budget " + budget + " ms)");
        }
        check(init < 5000, "Start-up: Evade's own set-up takes under 5 s (" + init + " ms)");

        for (int i = 0; i < 40 && !ctx.computeOnClient(c -> TextureWarmup.done()); i++) ctx.waitTicks(5);
        String w = ctx.computeOnClient(c -> {
            int[] n = TextureWarmup.counts();
            boolean icon = c.getTextureManager() instanceof net.evadeclient.mixin.TextureManagerWarmupAccessor a
                    && a.evade$textures().containsKey(net.minecraft.util.Identifier.of("evade", "textures/gui/nav/settings.png"));
            return n[0] + "|" + n[1] + "|" + n[2] + "|" + icon + "|" + TextureWarmup.decodeMs();
        });
        String[] wn = w.split("\\|");
        perf("texture warm-up: %s found, %s uploaded, %s already loaded, decoded in %s ms off the render thread", wn[0], wn[1], wn[2], wn[4]);
        check(Integer.parseInt(wn[0]) > 50 && Integer.parseInt(wn[1]) + Integer.parseInt(wn[2]) == Integer.parseInt(wn[0]) && wn[3].equals("true"),
                "Start-up: Evade's menu textures are decoded off the render thread and all loaded (" + w + ")");

        String line = ctx.computeOnClient(c -> {
            ProfilerHud ph = EvadeClient.modules.get(ProfilerHud.class);
            List<String> l = ph.textForTest();
            return l.isEmpty() ? "" : l.getLast();
        });
        check(line.matches("Boot \\d+\\.\\d s  ·  Evade \\d+\\.\\d\\d s"), "Frame Profiler shows the boot time (\"" + line + "\")");
    }

    // ------------------------------------------------------------------ 6: Sodium bridge

    private static Object sodiumField(String mod, String path) {
        return SodiumBridge.read(mod, path);
    }

    private static void setField(String mod, String path, Object value) throws Exception {
        Object root = mod.equals("sodium")
                ? Class.forName("net.caffeinemc.mods.sodium.client.SodiumClientMod").getMethod("options").invoke(null)
                : Class.forName("me.flashyreese.mods.sodiumextra.client.SodiumExtraClientMod").getMethod("options").invoke(null);
        String[] parts = path.split("\\.");
        Object o = root;
        for (int i = 0; i < parts.length - 1; i++) o = o.getClass().getField(parts[i]).get(o);
        var f = o.getClass().getField(parts[parts.length - 1]);
        if (f.getType().isEnum() && value instanceof String s) {
            for (Object e : f.getType().getEnumConstants()) if (((Enum<?>) e).name().equals(s)) value = e;
        }
        f.set(o, value);
    }

    private static void sodium(ClientGameTestContext ctx) {
        boolean present = ctx.computeOnClient(c -> SodiumBridge.sodiumLoaded() && SodiumBridge.extraLoaded());
        if (!present) {
            // Without Sodium the bridge must do nothing, quietly.
            String s = ctx.computeOnClient(c -> { SodiumBridge.follow("Max FPS"); String st = SodiumBridge.status(); SodiumBridge.restore(); return st; });
            check(s.contains("isn't installed"), "Sodium bridge: does nothing without Sodium (" + s + ")");
            return;
        }
        String[] keys = {"extra:animationSettings.water", "extra:animationSettings.lava", "extra:animationSettings.fire",
                "extra:animationSettings.portal", "extra:detailSettings.stars", "sodium:performance.chunkBuildDeferMode",
                "sodium:performance.chunkBuilderThreads", "sodium:performance.animateOnlyVisibleTextures"};
        // Your own Sodium values: with the bridge off (the laptop profile above switched Performance to Max FPS).
        ctx.runOnClient(c -> EvadeClient.modules.get(Performance.class).tuneSodium.set(false));
        ctx.waitTicks(3);
        Map<String, String> before = ctx.computeOnClient(c -> {
            Map<String, String> m = new LinkedHashMap<>();
            for (String k : keys) m.put(k, String.valueOf(sodiumField(k.substring(0, k.indexOf(':')), k.substring(k.indexOf(':') + 1))));
            return m;
        });
        // Max FPS through Performance's own setting.
        ctx.runOnClient(c -> {
            Performance p = EvadeClient.modules.get(Performance.class);
            p.setEnabled(true);
            p.tuneSodium.set(true);
            p.fpsMode.set("Max FPS");
        });
        ctx.waitTicks(3);
        String applied = ctx.computeOnClient(c -> sodiumField("extra", "animationSettings.water") + "|" + sodiumField("extra", "detailSettings.stars")
                + "|" + sodiumField("sodium", "performance.chunkBuildDeferMode") + "|" + sodiumField("sodium", "performance.chunkBuilderThreads")
                + "|" + SodiumBridge.buildThreads() + "|" + Files.exists(c.runDirectory.toPath().resolve("config/evade/sodium-restore.json"))
                + "|" + EvadeClient.modules.get(Performance.class).sodiumNow.get());
        String[] a = applied.split("\\|");
        perf("sodium bridge, Max FPS: %s", applied);
        check(a[0].equals("false") && a[1].equals("false") && a[2].equals("ALWAYS") && a[3].equals(a[4]) && a[5].equals("true") && a[6].contains("water animation off"),
                "Sodium bridge: Max FPS turns off Sodium Extra's animations and stars, defers chunk updates, sizes build threads, and keeps a restore file (" + applied + ")");

        // Quality: everything back exactly as it was.
        ctx.runOnClient(c -> EvadeClient.modules.get(Performance.class).fpsMode.set("Quality"));
        ctx.waitTicks(3);
        Map<String, String> after = ctx.computeOnClient(c -> {
            Map<String, String> m = new LinkedHashMap<>();
            for (String k : keys) m.put(k, String.valueOf(sodiumField(k.substring(0, k.indexOf(':')), k.substring(k.indexOf(':') + 1))));
            return m;
        });
        boolean fileGone = ctx.computeOnClient(c -> !Files.exists(c.runDirectory.toPath().resolve("config/evade/sodium-restore.json")));
        check(after.equals(before) && fileGone, "Sodium bridge: switching to Quality puts every Sodium option back and deletes the restore file (" + before + " -> " + after + ")");

        // A value you change yourself in Sodium's screen is kept.
        String kept = ctx.computeOnClient(c -> {
            try {
                Performance p = EvadeClient.modules.get(Performance.class);
                p.fpsMode.set("Max FPS");
                SodiumBridge.follow("Max FPS");
                setField("extra", "animationSettings.water", true); // "you" turn water animation back on
                p.fpsMode.set("Quality");
                SodiumBridge.follow("Quality");
                return String.valueOf(sodiumField("extra", "animationSettings.water")) + "|" + sodiumField("extra", "animationSettings.lava");
            } catch (Exception e) {
                return e.toString();
            } finally {
                try { setField("extra", "animationSettings.water", Boolean.valueOf(before.get("extra:animationSettings.water"))); } catch (Exception ignored) {}
            }
        });
        check(kept.equals("true|" + before.get("extra:animationSettings.lava")), "Sodium bridge: a Sodium option you changed yourself is kept (" + kept + ")");

        // After a crash: Sodium's options still hold Evade's values and the restore file is there. The next start puts yours back.
        String recovered = ctx.computeOnClient(c -> {
            try {
                Performance p = EvadeClient.modules.get(Performance.class);
                p.tuneSodium.set(false); // the tick follows "" from here on
                SodiumBridge.follow("Max FPS");
                Path f = c.runDirectory.toPath().resolve("config/evade/sodium-restore.json");
                String saved = Files.readString(f);
                SodiumBridge.restore();
                // the "crashed" state: Evade's values in Sodium, the restore file left behind
                setField("extra", "animationSettings.lava", false);
                setField("sodium", "performance.chunkBuildDeferMode", "ALWAYS");
                Files.writeString(f, saved);
                SodiumBridge.resetRecoveryForTest();
                SodiumBridge.follow("");
                return sodiumField("extra", "animationSettings.lava") + "|" + sodiumField("sodium", "performance.chunkBuildDeferMode") + "|" + Files.exists(f);
            } catch (Exception e) {
                return e.toString();
            }
        });
        check(recovered.equals(before.get("extra:animationSettings.lava") + "|" + before.get("sodium:performance.chunkBuildDeferMode") + "|false"),
                "Sodium bridge: after a crash, the next start puts your Sodium values back and forgets the restore file (" + recovered + ")");
        ctx.waitTicks(2);
    }

    // ------------------------------------------------------------------ 29: battery cap and Smooth Pacing

    private static void powerAndPacing(ClientGameTestContext ctx) {
        String real = ctx.computeOnClient(c -> PowerState.describe());
        perf("power: %s", real);
        String caps = ctx.computeOnClient(c -> {
            Performance p = EvadeClient.modules.get(Performance.class);
            p.setEnabled(true);
            p.smoothPacing.set(false);
            p.batteryCap.set("60");
            PowerState.forced = false;
            int base = c.getInactivityFpsLimiter().update();
            PowerState.forced = true;
            int onBattery = c.getInactivityFpsLimiter().update();
            String status = p.powerNow.get();
            p.batteryCap.set("90");
            int ninety = c.getInactivityFpsLimiter().update();
            PowerState.forced = false;
            int plugged = c.getInactivityFpsLimiter().update();
            PowerState.forced = null;
            return onBattery + "|" + ninety + "|" + plugged + "|" + base + "|" + status;
        });
        String[] cp = caps.split("\\|");
        int base = Integer.parseInt(cp[3]);
        check(Integer.parseInt(cp[0]) == Math.min(base, 60) && Integer.parseInt(cp[1]) == Math.min(base, 90) && Integer.parseInt(cp[2]) == base
                        && cp[4].contains("capped at 60"),
                "Battery FPS Cap: on battery the frame rate is held at 60 or 90; plugged in it isn't (" + caps + ")");

        String pacing = ctx.computeOnClient(c -> {
            Performance p = EvadeClient.modules.get(Performance.class);
            boolean vsync = c.options.getEnableVsync().getValue();
            c.options.getEnableVsync().setValue(false);
            p.smoothPacing.set(true);
            int cap = c.getInactivityFpsLimiter().update();
            int want = Math.max(30, FrameStats.refreshRate() - 3);
            p.smoothPacing.set(false);
            c.options.getEnableVsync().setValue(vsync);
            return cap + "|" + want;
        });
        String[] pc = pacing.split("\\|");
        check(Integer.parseInt(pc[0]) <= Integer.parseInt(pc[1]), "Smooth Pacing: caps the frame rate 3 below the monitor's refresh rate (" + pacing + ")");

        // The pacer itself (sleep, then spin): 150 frames at 200 FPS on this thread.
        FramePacer.reset();
        for (int i = 0; i < 150; i++) FramePacer.limit(200);
        double[] st = FramePacer.intervalStats();
        FramePacer.reset();
        perf("frame pacer at 200 FPS: mean %.3f ms, deviation %.3f ms", st[0], st[1]);
        check(Math.abs(st[0] - 5.0) < 0.25 && st[1] < 0.5, String.format(Locale.ROOT,
                "Smooth Pacing: frames are released on an even 5 ms schedule (mean %.3f ms, deviation %.3f ms)", st[0], st[1]));
    }

    // ------------------------------------------------------------------ 5: HUD cache

    private static void hudCache(ClientGameTestContext ctx) {
        List<String> turnedOn = ctx.computeOnClient(c -> {
            List<String> on = new ArrayList<>();
            for (HudElementModule el : EvadeClient.modules.hudElements()) {
                if (el.isEnabled() || el instanceof net.evadeclient.addon.AddonHudElement) continue;
                el.setEnabled(true);
                on.add(el.getName());
            }
            EvadeClient.modules.get(Performance.class).setEnabled(true);
            return on;
        });
        int shown = ctx.computeOnClient(c -> (int) EvadeClient.modules.hudElements().stream().filter(Module::isEnabled).count());
        ctx.waitTicks(20);
        Map<String, Double> us = new LinkedHashMap<>();
        Map<String, long[]> counts = new LinkedHashMap<>();
        for (int round = 0; round < 2; round++) {
            for (String mode : new String[]{"Every Frame", "60 Hz", "30 Hz"}) {
                ctx.runOnClient(c -> {
                    EvadeClient.modules.get(Performance.class).hudRefresh.set(mode);
                    HudCache.configure(mode);
                });
                ctx.waitTicks(10);
                ctx.runOnClient(c -> { FrameStats.takeHudMicros(); HudCache.takeCounts(); });
                ctx.waitTicks(80);
                double t = ctx.computeOnClient(c -> FrameStats.takeHudMicros());
                long[] n = ctx.computeOnClient(c -> HudCache.takeCounts());
                if (round == 1) { us.put(mode, t); counts.put(mode, n); } // the first round warms up
            }
        }
        perf("HUD cost with %d widgets on: every frame %.0f us, 60 Hz %.0f us, 30 Hz %.0f us per frame; reused/redrawn/live draws at 30 Hz %s",
                shown, us.get("Every Frame"), us.get("60 Hz"), us.get("30 Hz"), java.util.Arrays.toString(counts.get("30 Hz")));
        long[] c30 = counts.get("30 Hz");
        check(counts.get("Every Frame")[0] == 0 && c30[0] > 0 && c30[1] > 0,
                "HUD cache: Every Frame never reuses a drawing; 30 Hz reuses and redraws (" + java.util.Arrays.toString(c30) + ")");
        check(us.get("30 Hz") < us.get("Every Frame") * 0.8,
                String.format(Locale.ROOT, "HUD cache: 30 Hz costs less per frame than drawing every frame (%.0f vs %.0f us)", us.get("30 Hz"), us.get("Every Frame")));

        // A replayed widget adds the same elements as drawing it: compare one text widget recorded and replayed.
        String same = ctx.computeOnClient(c -> {
            var coords = EvadeClient.modules.get(net.evadeclient.module.hud.CoordsHud.class);
            return HudCache.animated(coords) + "|" + HudCache.animated(EvadeClient.modules.get(net.evadeclient.module.hud.MinimapHud.class))
                    + "|" + (HudCache.lastRecordedElements() > 0);
        });
        check(same.equals("false|true|true"), "HUD cache: text widgets are cached, moving widgets (minimap) are always drawn live (" + same + ")");
        ctx.takeScreenshot("evade-hud-cache-30hz");

        // A screen (the Evade menu could be changing settings) always draws live.
        ctx.runOnClient(c -> c.setScreen(new net.minecraft.client.gui.screen.GameMenuScreen(true)));
        ctx.waitTicks(5);
        ctx.runOnClient(c -> HudCache.takeCounts());
        ctx.waitTicks(10);
        long[] menu = ctx.computeOnClient(c -> HudCache.takeCounts());
        ctx.runOnClient(c -> c.setScreen(null));
        check(menu[0] == 0, "HUD cache: nothing is reused while a menu is open (" + java.util.Arrays.toString(menu) + ")");

        ctx.runOnClient(c -> {
            for (HudElementModule el : EvadeClient.modules.hudElements()) if (turnedOn.contains(el.getName())) el.setEnabled(false);
            EvadeClient.modules.get(Performance.class).hudRefresh.set("Auto");
        });
        ctx.waitTicks(2);
    }

    // ------------------------------------------------------------------ 34: HUD Toggle

    private static void hudToggle(ClientGameTestContext ctx) {
        ctx.runOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.hud.FpsHud.class).setEnabled(true));
        ctx.runOnClient(c -> { Hud.PROFILE = new java.util.HashMap<>(); EvadeClient.modules.get(HudToggle.class).setHidden(true); });
        ctx.waitTicks(5);
        long hiddenFrames = ctx.computeOnClient(c -> Hud.PROFILE.getOrDefault("__frames", 0L));
        ctx.runOnClient(c -> { Hud.PROFILE = new java.util.HashMap<>(); EvadeClient.modules.get(HudToggle.class).setHidden(false); });
        ctx.waitTicks(5);
        long shownFrames = ctx.computeOnClient(c -> { long n = Hud.PROFILE.getOrDefault("__frames", 0L); Hud.PROFILE = null; return n; });
        check(hiddenFrames == 0 && shownFrames > 0, "HUD Toggle: hides only Evade's widgets and shows them again (" + hiddenFrames + " / " + shownFrames + " frames)");

        // Hide In Screenshots: F2 waits for one frame drawn without Evade's HUD.
        Path shots = ctx.computeOnClient(c -> c.runDirectory.toPath().resolve("screenshots"));
        Set<Path> before = list(shots);
        int taken = ctx.computeOnClient(c -> { EvadeClient.modules.get(HudToggle.class).hideInScreenshots.set(true); return HudToggle.shotsWithoutHudForTest(); });
        ctx.getInput().pressKey(GLFW.GLFW_KEY_F2);
        ctx.waitTicks(10);
        int after = ctx.computeOnClient(c -> HudToggle.shotsWithoutHudForTest());
        boolean pending = ctx.computeOnClient(c -> HudToggle.shotPendingForTest());
        Set<Path> made = new HashSet<>(list(shots));
        made.removeAll(before);
        for (Path p : made) try { Files.deleteIfExists(p); } catch (Exception ignored) {}
        check(after == taken + 1 && !pending && !made.isEmpty(), "HUD Toggle: an F2 screenshot is taken from a frame without Evade's HUD and saved (" + made + ")");
        ctx.runOnClient(c -> EvadeClient.modules.get(HudToggle.class).hideInScreenshots.set(false));
    }

    private static Set<Path> list(Path dir) {
        Set<Path> out = new HashSet<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.toString().endsWith(".png")).forEach(out::add);
        } catch (Exception ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------------ 2 + 10: the benchmark flight and the upscaler

    private static BenchmarkScene.Result fly(ClientGameTestContext ctx, int seconds) {
        boolean started = ctx.computeOnClient(c -> BenchmarkScene.flyHere(seconds));
        check(started, "Benchmark scene: the flight starts in a world");
        for (int i = 0; i < 60 && ctx.computeOnClient(c -> BenchmarkScene.running()); i++) ctx.waitTicks(10);
        return ctx.computeOnClient(c -> BenchmarkScene.last());
    }

    private static void benchmark(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        // Not in its own world without "confirm": nothing happens.
        boolean idle = ctx.computeOnClient(c -> {
            BenchmarkScene.command(new String[]{"bench", "scene"});
            return !BenchmarkScene.running();
        });
        check(idle, "Benchmark scene: .bench scene in a world asks for confirm before leaving it");

        sp.getServer().runCommand("gamemode spectator @a");
        ctx.waitTicks(5);
        record Cfg(String name, int scale, String upscale, String mode) {}
        List<Cfg> cfgs = List.of(
                new Cfg("Balanced, 100%", 100, "Smooth", "Balanced"),
                new Cfg("Balanced, 65% Smooth", 65, "Smooth", "Balanced"),
                new Cfg("Balanced, 65% Evade", 65, "Evade (best)", "Balanced"),
                new Cfg("Max FPS, 65% Evade (laptop profile)", 65, "Evade (best)", "Max FPS"));
        Map<String, BenchmarkScene.Result> results = new LinkedHashMap<>();
        try {
            for (Cfg cfg : cfgs) {
                ctx.runOnClient(c -> {
                    Performance p = EvadeClient.modules.get(Performance.class);
                    p.setEnabled(true);
                    p.fpsMode.set(cfg.mode());
                });
                ctx.waitTicks(2);
                ctx.runOnClient(c -> {
                    Performance p = EvadeClient.modules.get(Performance.class);
                    p.renderScale.set((double) cfg.scale());
                    p.upscale.set(cfg.upscale());
                });
                ctx.waitTicks(5);
                long scaledBefore = ctx.computeOnClient(c -> RenderScale.scaledFrames());
                BenchmarkScene.Result r = fly(ctx, 8);
                long scaled = ctx.computeOnClient(c -> RenderScale.scaledFrames()) - scaledBefore;
                String used = ctx.computeOnClient(c -> Upscaler.lastUsed());
                check(r != null && r.frames() > 10 && r.avgFps() > 0, "Benchmark scene: " + cfg.name() + " measured (" + r + ")");
                perf("scene %-36s avg %6.1f FPS, 1%% low %6.1f, 0.1%% low %6.1f, %d frames (upscaler %s, %d scaled frames)",
                        cfg.name(), r.avgFps(), r.low1(), r.low01(), r.frames(), cfg.scale() < 100 ? used : "-", scaled);
                if (cfg.scale() < 100) check(scaled > 0, "Render Scale draws scaled frames at " + cfg.scale() + "% (" + scaled + ")");
                if (cfg.upscale().startsWith("Evade")) {
                    check(used.equals("evade"), "Evade Upscale: the edge-aware upscaler compiles and draws (" + used + ")");
                    ctx.takeScreenshot("evade-upscale-" + cfg.mode().replace(' ', '-').toLowerCase(Locale.ROOT));
                } else if (cfg.scale() < 100) {
                    ctx.takeScreenshot("evade-upscale-smooth");
                } else {
                    ctx.takeScreenshot("evade-upscale-native");
                }
                results.put(cfg.name(), r);
            }
        } finally {
            sp.getServer().runCommand("gamemode survival @a");
            sp.getServer().runCommand("tp @a 0 -60 0 0 0");
        }
        double base = results.get("Balanced, 100%").avgFps();
        for (var e : results.entrySet())
            perf("scene relative: %-36s %5.2fx of native (%.1f FPS)", e.getKey(), e.getValue().avgFps() / base, e.getValue().avgFps());
        // FPS regression guard: the scene must keep moving under software rendering (generous floor for xvfb).
        check(base > 5, String.format(Locale.ROOT, "Benchmark regression guard: the scene runs above 5 FPS here (%.1f)", base));

        // Result card and share code.
        BenchmarkScene.Result last = results.get("Max FPS, 65% Evade (laptop profile)");
        BenchmarkScene.Result back = BenchmarkScene.Result.decode(last.shareCode());
        check(back != null && Math.abs(back.avgFps() - last.avgFps()) < 0.06 && back.gpu().equals(last.gpu()) && back.frames() == last.frames(),
                "Benchmark scene: the share code reads back the same result (" + last.shareCode() + ")");
        check(BenchmarkScene.Result.decode("EVB1-nonsense") == null && BenchmarkScene.Result.decode("hello") == null,
                "Benchmark scene: a broken share code is refused");
        ctx.runOnClient(c -> c.setScreen(new BenchmarkResultScreen(null, last)));
        ctx.waitTicks(5);
        ctx.takeScreenshot("evade-benchmark-result");
        boolean card = ctx.computeOnClient(c -> c.currentScreen instanceof BenchmarkResultScreen);
        ctx.runOnClient(c -> c.setScreen(null));
        check(card, "Benchmark scene: the result card opens");
    }
}
