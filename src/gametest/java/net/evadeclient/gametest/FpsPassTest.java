package net.evadeclient.gametest;

import com.google.gson.JsonElement;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.Module;
import net.evadeclient.module.mods.Performance;
import net.evadeclient.setting.Setting;
import net.evadeclient.util.Benchmark;
import net.evadeclient.util.FrameStats;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FPS pass: a busy, repeatable scene (mobs, armor stands, dropped items, item frames, chests, campfires, spawners
 * and a steady stream of particles) measured with Evade off, with its defaults, and with every HUD widget on. Each
 * window reports frames, frame time, the render thread's allocations per frame, garbage collections and Evade's HUD
 * time per frame; "[gametest] FPSPASS" lines in the log. Allocation and GC figures don't depend on the graphics, so
 * they're the regression budget here (the software renderer's frame times are only compared with each other).
 *
 * <p>{@code -Devade.fpsonly=true}: only this, with a Java Flight Recording of the busy window saved to
 * {@code evade-fps.jfr} in the run folder (for profiling, see the FPS pass report). {@code -Devade.fpsjfr=false}
 * skips the recording.
 */
public final class FpsPassTest {
    private FpsPassTest() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    private static void log(String fmt, Object... args) {
        EvadeClient.LOG.info("[gametest] FPSPASS " + String.format(Locale.ROOT, fmt, args));
    }

    /** One measured window. */
    record Window(String name, int frames, double avgFps, double low1, double msPerFrame, double bytesPerFrame,
                  long gcs, long gcMs, double hudUs, double ticksPerFrame) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%-22s %5d frames  avg %6.1f FPS  1%%low %6.1f  %6.2f ms/frame  %8.1f KB/frame alloc  %d GCs (%d ms)  HUD %4.0f us/frame  %.2f ticks/frame",
                    name, frames, avgFps, low1, msPerFrame, bytesPerFrame / 1024.0, gcs, gcMs, hudUs, ticksPerFrame);
        }
    }

    // ------------------------------------------------------------------ entry points

    /** In the full suite: the scene, Evade off vs defaults, and the allocation budget. */
    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        benchmarksIgnoreIdleCaps(ctx);
        measureAll(ctx, sp, 6, false, false);
        keystrokesCached(ctx);
    }

    /** HUD Refresh: Keystrokes is cached while no key moves, and drawn live the moment one is pressed. */
    private static void keystrokesCached(ClientGameTestContext ctx) {
        var ks = net.evadeclient.module.hud.KeystrokesHud.class;
        boolean was = ctx.computeOnClient(c -> EvadeClient.modules.get(ks).isEnabled());
        ctx.runOnClient(c -> EvadeClient.modules.get(ks).setEnabled(true));
        ctx.waitTicks(20); // drawn at least once with no key down, fades finished
        boolean[] r = ctx.computeOnClient(c -> {
            var k = EvadeClient.modules.get(ks);
            boolean settled = !net.evadeclient.util.HudCache.animated(k);
            long key = k.cacheKey();
            c.options.forwardKey.setPressed(true);
            boolean pressed = net.evadeclient.util.HudCache.animated(k);
            boolean keyChanged = k.cacheKey() != key;
            c.options.forwardKey.setPressed(false);
            return new boolean[]{settled, pressed, keyChanged};
        });
        ctx.runOnClient(c -> EvadeClient.modules.get(ks).setEnabled(was));
        check(r[0] && r[1] && r[2], "HUD cache: Keystrokes is cached while no key moves and drawn live when one is pressed ("
                + java.util.Arrays.toString(r) + ")");
    }

    /**
     * A benchmark flies the camera with nobody at the keys, so the AFK cap must not apply while one runs (it used to
     * hold the last seconds of a 60 second {@code .bench scene} at the AFK frame rate).
     */
    private static void benchmarksIgnoreIdleCaps(ClientGameTestContext ctx) {
        int[] r = ctx.computeOnClient(c -> {
            Performance p = EvadeClient.modules.get(Performance.class);
            boolean was = p.isEnabled();
            p.setEnabled(true);
            var option = c.options.getInactivityFpsLimit();
            var old = option.getValue();
            option.setValue(net.minecraft.client.option.InactivityFpsLimit.AFK);
            var lim = c.getInactivityFpsLimiter();
            try {
                var pretend = lim.getClass().getMethod("evade$pretendIdle", long.class);
                pretend.invoke(lim, 600_000L);
                int idle = lim.update();
                Benchmark.setQuiet(true);
                Benchmark.start(5, false);
                pretend.invoke(lim, 600_000L);
                int measuring = lim.update();
                return new int[]{idle, measuring, p.afkFps.getInt()};
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            } finally {
                Benchmark.cancel();
                Benchmark.setQuiet(false);
                lim.onInput();
                option.setValue(old);
                p.setEnabled(was);
            }
        });
        check(r[0] <= r[2] && r[1] > r[2], "FPS pass: the AFK cap holds an idle game (" + r[0]
                + " FPS) but never a running benchmark (" + r[1] + " FPS)");
    }

    /** {@code -Devade.fpsonly=true}: longer windows, every HUD widget, and a flight recording. */
    static void profile(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        boolean jfr = !"false".equals(System.getProperty("evade.fpsjfr"));
        benchmarksIgnoreIdleCaps(ctx);
        measureAll(ctx, sp, 12, true, jfr);
        keystrokesCached(ctx);
    }

    private static void measureAll(ClientGameTestContext ctx, TestSingleplayerContext sp, int seconds, boolean allHud, boolean jfr) {
        Map<String, Boolean> enabledBefore = ctx.computeOnClient(c -> {
            Map<String, Boolean> m = new LinkedHashMap<>();
            for (Module mod : EvadeClient.modules.all()) m.put(mod.getName(), mod.isEnabled());
            return m;
        });
        Map<String, JsonElement> perfSnap = ctx.computeOnClient(c -> snapshot(EvadeClient.modules.get(Performance.class)));
        List<Window> out = new ArrayList<>();
        try {
            buildScene(ctx, sp);

            // 1: Evade's modules all off (only what can't be switched off stays: the menu and settings).
            ctx.runOnClient(c -> {
                for (Module m : EvadeClient.modules.all())
                    if (!(m instanceof net.evadeclient.module.client.ClientSettings)) m.setEnabled(false);
            });
            out.add(measure(ctx, "evade-off", seconds));

            // 2: Performance alone (its particle cap and culling change what the game itself draws and allocates).
            ctx.runOnClient(c -> EvadeClient.modules.get(Performance.class).setEnabled(true));
            out.add(measure(ctx, "performance-only", seconds));

            // 3: Evade's defaults (what a fresh install has on), Performance on its default FPS Mode. The difference
            // from 2 is the cost of everything else Evade runs by default: HUD, hooks, ticks.
            ctx.runOnClient(c -> {
                for (Module m : EvadeClient.modules.all()) m.setEnabled(defaultOn(m, enabledBefore));
                EvadeClient.modules.get(Performance.class).setEnabled(true);
            });
            String onByDefault = ctx.computeOnClient(c -> {
                StringBuilder b = new StringBuilder();
                for (Module m : EvadeClient.modules.all()) if (m.isEnabled()) b.append(b.length() == 0 ? "" : ", ").append(m.getName());
                return b.toString();
            });
            log("Evade defaults: %s", onByDefault);
            Object rec = jfr ? startJfr() : null;
            out.add(measure(ctx, "evade-defaults", seconds));
            if (rec != null) stopJfr(rec, "evade-fps-defaults.jfr");

            // 4: the same with HUD Refresh at 60 Hz (Auto only caches above 90 FPS, which software rendering never reaches).
            ctx.runOnClient(c -> EvadeClient.modules.get(Performance.class).hudRefresh.set("60 Hz"));
            out.add(measure(ctx, "defaults-hud-60hz", seconds));

            hudDrawCost(ctx);
            moduleLookupCost(ctx);
            hudCacheBreakEven(ctx);

            // 5: every HUD widget on as well (the heaviest HUD anyone could set up), drawn live and then cached.
            if (allHud) {
                ctx.runOnClient(c -> {
                    EvadeClient.modules.get(Performance.class).hudRefresh.set("Every Frame");
                    for (HudElementModule el : EvadeClient.modules.hudElements()) el.setEnabled(true);
                });
                ctx.waitTicks(10);
                rec = jfr ? startJfr() : null;
                out.add(measure(ctx, "all-hud", seconds));
                if (rec != null) stopJfr(rec, "evade-fps-allhud.jfr");
                ctx.runOnClient(c -> EvadeClient.modules.get(Performance.class).hudRefresh.set("60 Hz"));
                out.add(measure(ctx, "all-hud-60hz", seconds));
            }
        } finally {
            clearScene(ctx, sp);
            ctx.runOnClient(c -> {
                for (Module m : EvadeClient.modules.all()) {
                    Boolean was = enabledBefore.get(m.getName());
                    if (was != null) m.setEnabled(was);
                }
                restore(EvadeClient.modules.get(Performance.class), perfSnap);
                EvadeClient.modules.get(Performance.class).setEnabled(enabledBefore.getOrDefault("Performance", false));
            });
            ctx.waitTicks(5);
        }
        for (Window w : out) log("%s", w);
        Window off = out.get(0), perf = out.get(1), def = out.get(2);
        double extraKb = (def.bytesPerFrame() - perf.bytesPerFrame()) / 1024.0;
        log("Performance alone vs off: %+.1f%% frame time, %+.1f KB/frame allocated",
                (perf.msPerFrame() / off.msPerFrame() - 1) * 100, (perf.bytesPerFrame() - off.bytesPerFrame()) / 1024.0);
        log("Evade defaults vs Performance alone: %+.1f%% frame time, %+.1f KB/frame allocated, HUD %.0f us/frame (%.0f us cached at 60 Hz)",
                (def.msPerFrame() / perf.msPerFrame() - 1) * 100, extraKb, def.hudUs(), out.get(3).hudUs());
        for (int i = 4; i < out.size(); i++) {
            Window w = out.get(i);
            log("%s vs Performance alone: %+.1f%% frame time, %+.1f KB/frame allocated, HUD %.0f us/frame", w.name(),
                    (w.msPerFrame() / perf.msPerFrame() - 1) * 100, (w.bytesPerFrame() - perf.bytesPerFrame()) / 1024.0, w.hudUs());
        }
        check(off.frames() > 20 && def.frames() > 20, "FPS pass: the busy scene was measured (" + off.frames() + " and " + def.frames() + " frames)");
        // Allocation budget: what Evade's defaults add to every frame in a busy scene, on top of the game itself. The
        // game's own allocations swing by a few hundred KB a frame with the number of ticks per frame and live
        // particles, so this only catches a real leak of garbage (Evade's own share, from a flight recording, is a few
        // KB a frame: see the FPS pass report); the HUD's time per frame below is the finer check.
        check(extraKb < ALLOC_BUDGET_KB, String.format(Locale.ROOT,
                "FPS pass: Evade's defaults allocate under %.0f KB more per frame than Performance alone in a busy scene (%.1f KB)", ALLOC_BUDGET_KB, extraKb));
        check(def.hudUs() < HUD_BUDGET_US, String.format(Locale.ROOT,
                "FPS pass: Evade's default HUD draws in under %.0f us per frame here (%.0f us)", HUD_BUDGET_US, def.hudUs()));
    }

    /**
     * Evade's default HUD drawn live 400 times back to back into a scratch GUI state: microseconds and bytes allocated
     * per draw, on frames within one tick (most frames, at any frame rate over 20) and on frames that start a new tick
     * (the text widgets read their text again). Allocation counts don't depend on how busy the computer is.
     */
    private static void hudDrawCost(ClientGameTestContext ctx) {
        double[] r = ctx.computeOnClient(c -> {
            double[] out = new double[4];
            var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            long tid = Thread.currentThread().threadId();
            net.evadeclient.util.HudCache.forceOff = true;
            try {
                for (int pass = 0; pass < 3; pass++) { // pass 0 warms up; 1 = same tick, 2 = a new tick every draw
                    long best = Long.MAX_VALUE, bytes = 0;
                    for (int round = 0; round < 4; round++) {
                        long b0 = bean.getThreadAllocatedBytes(tid), t0 = System.nanoTime();
                        for (int i = 0; i < 100; i++) {
                            if (pass == 2) EvadeClient.ticks++;
                            var dc = new net.minecraft.client.gui.DrawContext(c, new net.minecraft.client.gui.render.state.GuiRenderState(), 0, 0);
                            net.evadeclient.hud.Hud.render(dc);
                        }
                        best = Math.min(best, System.nanoTime() - t0);
                        bytes += bean.getThreadAllocatedBytes(tid) - b0;
                    }
                    if (pass > 0) {
                        out[(pass - 1) * 2] = best / 100 / 1000.0;
                        out[(pass - 1) * 2 + 1] = bytes / 400.0;
                    }
                }
            } finally {
                net.evadeclient.util.HudCache.forceOff = false;
                FrameStats.takeHudMicros();
            }
            return out;
        });
        // A scratch DrawContext and GuiRenderState are part of each draw here (a few hundred bytes).
        log("HUD draw (defaults, live): within a tick %.0f us and %.1f KB per frame; on a new tick %.0f us and %.1f KB",
                r[0], r[1] / 1024, r[2], r[3] / 1024);
    }

    /**
     * {@code modules.get(SomeModule.class)}: hooks call it per entity, particle and frame (Performance.active() and
     * friends). Its cost next to a scan over every module, which is what it was before the lookup was cached.
     */
    private static void moduleLookupCost(ClientGameTestContext ctx) {
        double[] ns = ctx.computeOnClient(c -> {
            var mods = EvadeClient.modules;
            Class<?>[] types = {Performance.class, net.evadeclient.module.visuals.Particles.class,
                    net.evadeclient.module.mods.Zoom.class, net.evadeclient.module.client.ClientSettings.class};
            long sink = 0;
            double[] out = new double[2];
            for (int pass = 0; pass < 3; pass++) {
                long t0 = System.nanoTime();
                for (int i = 0; i < 400_000; i++) {
                    Class<?> t = types[i & 3];
                    if (pass == 2) {
                        for (Module m : mods.all()) if (t.isInstance(m)) { sink += m.hashCode(); break; }
                    } else {
                        @SuppressWarnings("unchecked") Module m = mods.get((Class<? extends Module>) t);
                        sink += m.hashCode();
                    }
                }
                if (pass > 0) out[pass - 1] = (System.nanoTime() - t0) / 400_000.0;
            }
            if (sink == 42) EvadeClient.LOG.info("");
            return out;
        });
        log("Module lookup by class: %.1f ns cached, %.1f ns scanning every module (%.0fx)", ns[0], ns[1], ns[1] / Math.max(0.01, ns[0]));
        check(ns[0] < ns[1], String.format(Locale.ROOT, "FPS pass: looking a module up by class is cached (%.1f ns vs a %.1f ns scan)", ns[0], ns[1]));
    }

    /**
     * HUD Refresh: what a frame costs drawn live, redrawn into the cache, and reused from it (the default widgets, 200
     * draws back to back each), and from that the frame rate above which a 60 Hz cache is cheaper than drawing live.
     * Auto uses the cache above {@code HudCache.AUTO_MIN_FPS}.
     */
    private static void hudCacheBreakEven(ClientGameTestContext ctx) {
        double[] us = ctx.computeOnClient(c -> {
            double[] out = new double[3];
            String mode = EvadeClient.modules.get(Performance.class).hudRefresh.get();
            try {
                for (int pass = 0; pass < 4; pass++) { // pass 0 warms up; then live, redraw every draw, reuse
                    int kind = pass == 0 ? 2 : pass - 1;
                    net.evadeclient.util.HudCache.forceOff = kind == 0;
                    net.evadeclient.util.HudCache.configure("60 Hz");
                    long t0 = System.nanoTime();
                    for (int i = 0; i < 200; i++) {
                        // Switching the refresh rate drops the cached picture, so every draw redraws it.
                        if (kind == 1) net.evadeclient.util.HudCache.configure(i % 2 == 0 ? "30 Hz" : "60 Hz");
                        var dc = new net.minecraft.client.gui.DrawContext(c, new net.minecraft.client.gui.render.state.GuiRenderState(), 0, 0);
                        net.evadeclient.hud.Hud.render(dc);
                    }
                    if (pass > 0) out[kind] = (System.nanoTime() - t0) / 200 / 1000.0;
                }
            } finally {
                net.evadeclient.util.HudCache.forceOff = false;
                net.evadeclient.util.HudCache.configure(mode);
                FrameStats.takeHudMicros();
            }
            return out;
        });
        double breakEven = us[0] <= us[2] ? Double.POSITIVE_INFINITY : 60 * (us[1] - us[2]) / (us[0] - us[2]);
        log("HUD Refresh: live %.0f us, redraw into the cache %.0f us, reuse %.0f us per frame; a 60 Hz cache wins above %.0f FPS",
                us[0], us[1], us[2], breakEven);
    }

    /** Budgets for the regression checks (software renderer under xvfb; real PCs are several times faster). */
    static final double ALLOC_BUDGET_KB = 1024, HUD_BUDGET_US = 2500;

    private static boolean defaultOn(Module m, Map<String, Boolean> before) {
        return before.getOrDefault(m.getName(), false);
    }

    // ------------------------------------------------------------------ measuring

    private static Window measure(ClientGameTestContext ctx, String name, int seconds) {
        ctx.waitTicks(60); // settle: chunks rebuilt, JIT warm, the cache filled
        long[] before = ctx.computeOnClient(c -> {
            FrameStats.takeHudMicros();
            net.evadeclient.hud.Hud.PROFILE = new HashMap<>();
            Benchmark.setQuiet(true);
            Benchmark.start(seconds, false);
            return counters();
        });
        for (int i = 0; i < 400 && ctx.computeOnClient(c -> Benchmark.running()); i++) ctx.waitTicks(5);
        long[] after = ctx.computeOnClient(c -> counters());
        double hud = ctx.computeOnClient(c -> FrameStats.takeHudMicros());
        String widgets = ctx.computeOnClient(c -> {
            Map<String, Long> p = net.evadeclient.hud.Hud.PROFILE;
            net.evadeclient.hud.Hud.PROFILE = null;
            long frames = Math.max(1, p.getOrDefault("__frames", 1L));
            StringBuilder b = new StringBuilder();
            p.entrySet().stream().filter(e -> !e.getKey().startsWith("__")).sorted((x, y) -> Long.compare(y.getValue(), x.getValue())).limit(8)
                    .forEach(e -> b.append(String.format(Locale.ROOT, "%s %.0fus, ", e.getKey(), e.getValue() / 1000.0 / frames)));
            return b.toString();
        });
        if (!widgets.isEmpty()) log("%s widgets (per frame drawn): %s", name, widgets);
        Benchmark.Result r = ctx.computeOnClient(c -> { Benchmark.setQuiet(false); return Benchmark.last(); });
        int frames = r == null ? 0 : r.frames();
        double ms = r == null ? 0 : 1000.0 / r.avgFps();
        // Allocation counters run a little past the window (the last check); scaled by the frames they saw.
        long framesSeen = Math.max(1, after[3] - before[3]);
        return new Window(name, frames, r == null ? 0 : r.avgFps(), r == null ? 0 : r.low1(), ms,
                (after[0] - before[0]) / (double) framesSeen, after[1] - before[1], after[2] - before[2], hud,
                (after[4] - before[4]) / (double) framesSeen);
    }

    /** {render thread bytes allocated, GC count, GC ms, frames}: render thread only. */
    private static long[] counters() {
        long bytes = -1;
        var bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean sun) bytes = sun.getThreadAllocatedBytes(Thread.currentThread().threadId());
        long gcs = 0, gcMs = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcs += Math.max(0, gc.getCollectionCount());
            gcMs += Math.max(0, gc.getCollectionTime());
        }
        return new long[]{bytes, gcs, gcMs, net.evadeclient.util.FrameStats.totalFrames(), EvadeClient.ticks};
    }

    // ------------------------------------------------------------------ the scene

    /** The scene's centre, in front of the test world's spawn (superflat, ground at y -61). */
    private static final int Y = -60;

    private static void buildScene(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var s = sp.getServer();
        s.runCommand("gamemode spectator @a");
        s.runCommand("time set noon");
        s.runCommand("weather clear");
        s.runCommand("gamerule advance_time false");
        s.runCommand("gamerule command_block_output false");
        s.runCommand("kill @e[type=!player]");
        String[] mobs = {"cow", "pig", "sheep", "chicken", "villager", "zombie", "skeleton", "horse"};
        int n = 0;
        for (int i = 0; i < 8; i++) for (int j = 0; j < 6; j++)
            s.runCommand(String.format(Locale.ROOT, "summon %s %d %d %d {NoAI:1b,Silent:1b,PersistenceRequired:1b,Invulnerable:1b}",
                    mobs[n++ % mobs.length], -14 + i * 4, Y, 4 + j * 4));
        for (int i = 0; i < 16; i++)
            s.runCommand(String.format(Locale.ROOT, "summon armor_stand %d %d %d {ShowArms:1b,equipment:{head:{id:\"minecraft:diamond_helmet\",count:1},chest:{id:\"minecraft:iron_chestplate\",count:1},mainhand:{id:\"minecraft:diamond_sword\",count:1}}}",
                    -15 + i * 2, Y, 30));
        String[] items = {"diamond", "cobblestone", "oak_log", "golden_apple", "iron_ingot", "bow"};
        for (int i = 0; i < 72; i++)
            s.runCommand(String.format(Locale.ROOT, "summon item %.1f %.1f %.1f {Item:{id:\"minecraft:%s\",count:12},PickupDelay:32767,Age:-32768,NoGravity:1b}",
                    -12 + (i % 12) * 2.1, Y + 0.2, 2 + (i / 12) * 4.3, items[i % items.length]));
        s.runCommand("fill -16 -61 34 16 -61 34 chest");
        s.runCommand("fill -16 -61 36 16 -61 36 campfire");
        s.runCommand("fill -16 -61 38 16 -61 38 spawner");
        s.runCommand("fill -16 -61 40 16 -61 40 enchanting_table");
        s.runCommand("fill -16 -60 42 16 -56 42 stone");
        for (int i = 0; i < 12; i++)
            s.runCommand(String.format(Locale.ROOT, "summon item_frame %d -58 41 {Facing:2b,Item:{id:\"minecraft:diamond_sword\",count:1}}", -14 + i * 2));
        // A steady stream of particles: a repeating command block under the floor.
        s.runCommand("setblock 0 -63 0 repeating_command_block{auto:1b,Command:\"particle minecraft:end_rod 0 -57 18 8 3 10 0.01 40 force\"}");
        s.runCommand("setblock 2 -63 0 repeating_command_block{auto:1b,Command:\"particle minecraft:flame 0 -59 20 10 1 10 0.01 30 force\"}");
        s.runCommand("tp @a 0 -53 -8 0 25");
        ctx.waitTicks(40);
        sp.getClientWorld().waitForChunksRender();
    }

    private static void clearScene(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        var s = sp.getServer();
        s.runCommand("setblock 0 -63 0 dirt");
        s.runCommand("setblock 2 -63 0 dirt");
        s.runCommand("kill @e[type=!player]");
        s.runCommand("fill -16 -61 34 16 -61 40 grass_block");
        s.runCommand("fill -16 -60 42 16 -56 42 air");
        s.runCommand("kill @e[type=item]");
        s.runCommand("gamerule advance_time true");
        s.runCommand("gamerule command_block_output true");
        s.runCommand("time set day");
        s.runCommand("gamemode survival @a");
        s.runCommand("tp @a 0 -60 0 0 0");
        ctx.waitTicks(10);
    }

    // ------------------------------------------------------------------ flight recording (profiling only)

    private static Object startJfr() {
        try {
            var rec = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
            rec.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(5));
            rec.enable("jdk.ObjectAllocationSample").with("throttle", "2000/s");
            rec.start();
            return rec;
        } catch (Exception e) {
            EvadeClient.LOG.warn("FPS pass: no flight recording", e);
            return null;
        }
    }

    private static void stopJfr(Object o, String name) {
        try (var rec = (jdk.jfr.Recording) o) {
            rec.stop();
            Path file = Path.of(name).toAbsolutePath();
            Files.deleteIfExists(file);
            rec.dump(file);
            log("flight recording saved to %s", file);
        } catch (Exception e) {
            EvadeClient.LOG.warn("FPS pass: couldn't save the flight recording", e);
        }
    }

    // ------------------------------------------------------------------ settings snapshot

    private static Map<String, JsonElement> snapshot(Module m) {
        Map<String, JsonElement> out = new HashMap<>();
        for (Setting<?> s : m.getSettings()) out.put(s.getName(), s.toJson());
        return out;
    }

    private static void restore(Module m, Map<String, JsonElement> snap) {
        for (Setting<?> s : m.getSettings()) if (snap.containsKey(s.getName())) s.loadJson(snap.get(s.getName()));
    }
}
