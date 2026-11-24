package net.evadeclient.util;

import net.evadeclient.EvadeClient;
import net.evadeclient.command.CommandManager;
import net.evadeclient.gui.BenchmarkResultScreen;
import net.evadeclient.module.mods.Performance;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.Text;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.rule.GameRules;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * {@code .bench scene}: a benchmark anyone can repeat. Evade makes a new world with a fixed seed (always the same
 * landscape), waits for it to load, then flies the camera on a fixed path for 60 seconds: one slow circle around the
 * spawn, 30 blocks up, looking along the way, with a gentle rise and fall. Then the world is closed and deleted, and a
 * result card shows the average FPS, 1% and 0.1% lows, the graphics, processor and settings, and a share code.
 *
 * <p>It only runs in its own temporary singleplayer world (in spectator mode, at noon, with clear weather), so the
 * camera path never touches a server. The flight part can also run where you are (tests, {@link #flyHere}).
 * Render thread only.
 */
public final class BenchmarkScene {
    private BenchmarkScene() {}

    public static final int DEFAULT_SECONDS = 60;
    /** The world's name and the fixed seed. */
    public static final String WORLD_NAME = "Evade Benchmark";
    public static final long SEED = 2_511_240_025L;
    /** Seconds spent at the start of the path before measuring (chunks load and build, the JIT warms up). */
    static final int WARMUP_TICKS = 200;
    static final double RADIUS = 48, HEIGHT = 30, BOB = 6;

    private enum Phase { IDLE, LOADING, WARMUP, FLYING }

    private static Phase phase = Phase.IDLE;
    private static int seconds = DEFAULT_SECONDS, ticks;
    /** The scene's own world (closed and deleted at the end) or the current one ({@link #flyHere}). */
    private static boolean ownWorld;
    private static String worldDir;
    private static double cx, cy, cz;
    private static long flightStart;
    private static Result last;
    /** Benchmark's last result before the flight (a cancelled flight leaves it unchanged). */
    private static Benchmark.Result before;
    private static String status = "Not run yet";

    /** A finished run, with what it ran on. */
    public record Result(double avgFps, double low1, double low01, int frames, double seconds, String gpu, String cpu,
                         String mode, String resolution, int renderDistance, String renderScale, String version) {
        /** A short code to share: "EVB1-" then the result, URL-safe Base64. */
        public String shareCode() {
            String raw = String.join("|", "1", fmt(avgFps), fmt(low1), fmt(low01), Integer.toString(frames), fmt(seconds),
                    clean(gpu), clean(cpu), clean(mode), clean(resolution), Integer.toString(renderDistance), clean(renderScale), clean(version));
            return "EVB1-" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        private static String fmt(double d) { return String.format(Locale.ROOT, "%.1f", d); }

        private static String clean(String s) { return s == null ? "" : s.replace('|', '/'); }

        /** A share code read back, or null when it isn't one. */
        public static Result decode(String code) {
            if (code == null || !code.strip().startsWith("EVB1-")) return null;
            try {
                String raw = new String(Base64.getUrlDecoder().decode(code.strip().substring(5)), StandardCharsets.UTF_8);
                String[] p = raw.split("\\|", -1);
                if (p.length != 13 || !p[0].equals("1")) return null;
                return new Result(Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                        Integer.parseInt(p[4]), Double.parseDouble(p[5]), p[6], p[7], p[8], p[9], Integer.parseInt(p[10]), p[11], p[12]);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    public static boolean running() { return phase != Phase.IDLE; }

    public static Result last() { return last; }

    public static String status() {
        return switch (phase) {
            case LOADING -> "Making the world...";
            case WARMUP -> "Loading the scene...";
            case FLYING -> String.format(Locale.ROOT, "Flying... %d s left", Math.max(0, seconds - (System.nanoTime() - flightStart) / 1_000_000_000L));
            default -> status;
        };
    }

    // ------------------------------------------------------------------ start

    /** {@code .bench scene [seconds] [confirm]}. In a world it asks for "confirm" first, since the world is left. */
    public static void command(String[] a) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int secs = DEFAULT_SECONDS;
        boolean confirm = false;
        for (int i = 2; i < a.length; i++) {
            if (a[i].equalsIgnoreCase("confirm")) confirm = true;
            else try { secs = Integer.parseInt(a[i]); } catch (NumberFormatException ignored) {}
        }
        if (running()) { CommandManager.print("§cThe benchmark scene is already running (.bench stop to end it)."); return; }
        if (mc.world != null && !confirm) {
            CommandManager.print("§7The benchmark scene runs in a new temporary world, so this " + (mc.isInSingleplayer() ? "world is saved and closed" : "leaves the server") + ".");
            CommandManager.print("§7Type §d.bench scene confirm§7 to go ahead.");
            return;
        }
        start(secs);
    }

    /** Leaves the current world (if any), makes the scene's world and runs it for {@code secs} seconds. */
    public static boolean start(int secs) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (running() || Benchmark.running()) return false;
        if (mc.world != null && mc.currentScreen != null && !(mc.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen)) {
            Notifications.push("Benchmark Scene", "Leave your world first, then run it from the menu, or type .bench scene confirm", false);
            return false;
        }
        seconds = Math.max(10, Math.min(120, secs));
        ownWorld = true;
        phase = Phase.LOADING;
        ticks = 0;
        if (mc.world != null) mc.disconnect(Text.literal("Starting the Evade benchmark scene"));
        mc.send(BenchmarkScene::createWorld);
        return true;
    }

    private static void createWorld() {
        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            worldDir = net.minecraft.util.path.PathUtil.getNextUniqueName(mc.getLevelStorage().getSavesDirectory(), WORLD_NAME, "");
            LevelInfo info = new LevelInfo(WORLD_NAME, GameMode.SPECTATOR, false, Difficulty.PEACEFUL, true,
                    new GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES), DataConfiguration.SAFE_MODE);
            mc.createIntegratedServerLoader().createAndStart(worldDir, info, new GeneratorOptions(SEED, true, false),
                    WorldPresets::createDemoOptions, new TitleScreen());
        } catch (Exception e) {
            EvadeClient.LOG.error("Benchmark scene: couldn't create the world", e);
            fail("Couldn't create the benchmark world");
        }
    }

    /** Runs only the flight, where you are now (tests; a world you're in with nothing open). */
    public static boolean flyHere(int secs) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (running() || Benchmark.running() || mc.player == null || mc.world == null) return false;
        seconds = Math.max(3, Math.min(120, secs));
        ownWorld = false;
        worldDir = null;
        beginWarmup(mc, 20);
        return true;
    }

    public static void cancel() {
        if (!running()) return;
        if (Benchmark.running()) Benchmark.cancel();
        Benchmark.setQuiet(false);
        boolean own = ownWorld;
        phase = Phase.IDLE;
        status = "Stopped";
        if (own) leaveAndDelete();
    }

    private static void fail(String why) {
        phase = Phase.IDLE;
        status = why;
        Benchmark.setQuiet(false);
        Notifications.push("Benchmark Scene", why, false);
    }

    // ------------------------------------------------------------------ every client tick and frame

    /** From the client tick (registered once). */
    public static void tick(MinecraftClient mc) {
        switch (phase) {
            case LOADING -> {
                if (++ticks > 20 * 120) { fail("The world took too long to load"); return; }
                if (mc.player == null || mc.world == null || mc.currentScreen != null || mc.getServer() == null) return;
                beginWarmup(mc, WARMUP_TICKS);
            }
            case WARMUP -> {
                if (mc.player == null || mc.world == null) { fail("Stopped: the world closed"); return; }
                pose(mc, 0);
                if (ownWorld) holdNoonAndClear(mc.getServer());
                if (--ticks > 0) return;
                Benchmark.setQuiet(true);
                before = Benchmark.last();
                if (!Benchmark.start(seconds, false)) { fail("Couldn't start measuring"); return; }
                flightStart = System.nanoTime();
                phase = Phase.FLYING;
            }
            case FLYING -> {
                if (ownWorld) holdNoonAndClear(mc.getServer());
                if (Benchmark.running()) {
                    if (mc.currentScreen != null && !(mc.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen)) {
                        cancel();
                        Notifications.push("Benchmark Scene", "Stopped: a menu opened during the flight", false);
                    }
                    return;
                }
                finish(mc);
            }
            default -> { }
        }
    }

    /** From {@link Benchmark}'s frame hook while the flight is measured: the camera follows the path. */
    static void onFrame(long elapsedNanos, long durationNanos) {
        if (phase != Phase.FLYING) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) pose(mc, Math.min(1.0, elapsedNanos / (double) durationNanos));
    }

    private static void beginWarmup(MinecraftClient mc, int warmupTicks) {
        cx = Math.floor(mc.player.getX()) + 0.5;
        cz = Math.floor(mc.player.getZ()) + 0.5;
        int ground = mc.world.getTopY(Heightmap.Type.MOTION_BLOCKING, (int) Math.floor(cx), (int) Math.floor(cz));
        cy = Math.max(ground, mc.world.getSeaLevel()) + HEIGHT;
        ticks = warmupTicks;
        phase = Phase.WARMUP;
        pose(mc, 0);
    }

    /**
     * The camera at fraction {@code t} (0..1) of the flight: one circle of {@link #RADIUS} blocks around the start,
     * rising and falling {@link #BOB} blocks twice per lap, looking along the path and a little down.
     */
    static void pose(MinecraftClient mc, double t) {
        double a = 2 * Math.PI * t;
        double x = cx + RADIUS * Math.cos(a) - RADIUS, z = cz + RADIUS * Math.sin(a), y = cy + BOB * Math.sin(2 * a);
        // Direction of travel (-sin a, cos a); Minecraft's yaw 0 looks along +Z, 90 along -X.
        float yaw = (float) Math.toDegrees(Math.atan2(Math.sin(a), Math.cos(a)));
        float pitch = (float) (12 + 6 * Math.cos(2 * a));
        var p = mc.player;
        p.setVelocity(0, 0, 0);
        p.getAbilities().flying = true;
        p.refreshPositionAndAngles(x, y, z, yaw, pitch);
        p.setHeadYaw(yaw);
        p.setBodyYaw(yaw);
    }

    private static void holdNoonAndClear(IntegratedServer server) {
        if (server == null) return;
        server.execute(() -> {
            var w = server.getOverworld();
            if (w == null) return;
            if (w.getTimeOfDay() % 24000 != 6000) w.setTimeOfDay(6000);
            if (w.isRaining() || w.isThundering()) w.setWeather(1_000_000, 0, false, false);
        });
    }

    private static void finish(MinecraftClient mc) {
        Benchmark.setQuiet(false);
        Benchmark.Result r = Benchmark.last();
        boolean own = ownWorld;
        phase = Phase.IDLE;
        if (r == null || r == before || r.frames() < 10) {
            status = r == before ? "Stopped" : "Too few frames to measure";
            if (own) leaveAndDelete();
            return;
        }
        Performance p = Performance.active();
        String scale = p == null || p.renderScale.get() >= 100 ? "100%"
                : p.renderScale.getInt() + "% " + Upscaler.Mode.of(p.upscale.get()).label;
        var fb = mc.getWindow();
        last = new Result(r.avgFps(), r.low1(), r.low01(), r.frames(), r.seconds(), GpuInfo.shortName(), GpuInfo.cpuName(),
                p == null ? "Performance off" : p.fpsMode.get(), fb.getFramebufferWidth() + "x" + fb.getFramebufferHeight(),
                mc.options.getViewDistance().getValue(), scale, EvadeClient.VERSION);
        status = String.format(Locale.ROOT, "%.0f FPS average, %.0f 1%% low (click to run again)", last.avgFps(), last.low1());
        EvadeClient.LOG.info("Benchmark scene: {}", last);
        if (own) {
            leaveAndDelete();
            mc.setScreen(new BenchmarkResultScreen(mc.currentScreen, last));
        }
    }

    /** Closes the scene's world and deletes it. */
    private static void leaveAndDelete() {
        MinecraftClient mc = MinecraftClient.getInstance();
        String dir = worldDir;
        worldDir = null;
        if (mc.world != null) mc.disconnect(Text.literal("Benchmark finished"));
        if (dir == null) return;
        try (var session = mc.getLevelStorage().createSessionWithoutSymlinkCheck(dir)) {
            session.deleteSessionLock();
        } catch (Exception e) {
            EvadeClient.LOG.warn("Benchmark scene: couldn't delete the world {}", dir, e);
        }
    }
}
