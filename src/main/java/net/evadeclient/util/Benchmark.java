package net.evadeclient.util;

import net.evadeclient.EvadeClient;
import net.evadeclient.command.CommandManager;
import net.evadeclient.module.mods.Performance;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;

/**
 * {@code /evade bench}: measures the frame rate for a fixed time and reports average FPS, 1% and 0.1% lows. In
 * singleplayer the camera turns one full circle so every run sees the same scene; on a server you stand still and
 * nothing is sent (the camera isn't moved). Each result is compared with the previous one and appended to
 * config/evade/bench.txt, so presets can be compared on the same PC. Render thread only.
 */
public final class Benchmark {
    private static final int MAX_FRAMES = 200_000;
    private static float[] frames;
    private static int count;
    private static long startNanos, durationNanos;
    private static boolean spin;
    private static float startYaw;
    private static Result last;
    /** Set by {@link BenchmarkAll}: results go to bench.txt but not to chat (it reports them all at the end). */
    private static boolean quiet;

    public record Result(double avgFps, double low1, double low01, double minFps, int frames, double seconds, String setup) {}

    private Benchmark() {}

    public static boolean running() { return frames != null; }

    /** Starts a run of {@code seconds}. False if one is already running or there's no world. */
    public static boolean start(int seconds) {
        return start(seconds, MinecraftClient.getInstance().isInSingleplayer());
    }

    /** Like {@link #start(int)}; {@code turn} turns the camera one full circle (singleplayer only). */
    public static boolean start(int seconds, boolean turn) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (running() || mc.player == null || mc.world == null) return false;
        frames = new float[MAX_FRAMES];
        count = 0;
        durationNanos = Math.max(5, Math.min(120, seconds)) * 1_000_000_000L;
        startNanos = System.nanoTime();
        spin = turn && mc.isInSingleplayer();
        startYaw = mc.player.getYaw();
        return true;
    }

    public static void cancel() { frames = null; }

    /** Results are saved but not printed (one summary is printed for several runs instead). */
    public static void setQuiet(boolean q) { quiet = q; }

    /** From {@link FrameStats#frameEnd}: one frame of {@code ms}. */
    static void onFrame(float ms) {
        if (frames == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) { cancel(); CommandManager.print("§cBenchmark stopped: you left the world."); return; }
        long elapsed = System.nanoTime() - startNanos;
        if (count < MAX_FRAMES) frames[count++] = ms;
        if (spin) mc.player.setYaw(startYaw + 360f * (float) Math.min(1.0, elapsed / (double) durationNanos));
        BenchmarkScene.onFrame(elapsed, durationNanos);
        if (elapsed >= durationNanos) finish(elapsed / 1e9);
    }

    private static void finish(double seconds) {
        float[] f = Arrays.copyOf(frames, count);
        frames = null;
        if (f.length < 10) { CommandManager.print("§cBenchmark: too few frames to measure."); return; }
        Result r = compute(f, seconds, setup());
        if (!quiet) report(r, last);
        save(r);
        last = r;
    }

    /** Average FPS over the run and the "1% low": the FPS of the slowest 1% of frames, averaged. */
    public static Result compute(float[] ms, double seconds, String setup) {
        float[] sorted = ms.clone();
        Arrays.sort(sorted);
        double total = 0;
        for (float v : ms) total += v;
        double avg = ms.length / (total / 1000.0);
        return new Result(avg, lowFps(sorted, 0.01), lowFps(sorted, 0.001), 1000.0 / sorted[sorted.length - 1], ms.length, seconds, setup);
    }

    private static double lowFps(float[] sorted, double fraction) {
        int n = Math.max(1, (int) Math.round(sorted.length * fraction));
        double sum = 0;
        for (int i = sorted.length - n; i < sorted.length; i++) sum += sorted[i];
        return 1000.0 / (sum / n);
    }

    private static String setup() {
        MinecraftClient mc = MinecraftClient.getInstance();
        Performance p = Performance.active();
        String mode = p == null ? "Performance off" : p.fpsMode.get();
        String gpu = "";
        try { gpu = com.mojang.blaze3d.systems.RenderSystem.getDevice().getRenderer(); } catch (Throwable ignored) {}
        return String.format(Locale.ROOT, "%s, render distance %d, %s%s", mode, mc.options.getViewDistance().getValue(),
                mc.options.getEnableVsync().getValue() ? "VSync on" : "VSync off",
                gpu == null || gpu.isBlank() ? "" : ", " + gpu);
    }

    private static void report(Result r, Result prev) {
        CommandManager.print(String.format(Locale.ROOT, "§fBenchmark (%.0f s, %d frames): §a%.0f FPS§7 average, §e%.0f§7 1%% low, §6%.0f§7 0.1%% low",
                r.seconds(), r.frames(), r.avgFps(), r.low1(), r.low01()));
        CommandManager.print("§7" + r.setup());
        if (prev != null) {
            double d = (r.avgFps() - prev.avgFps()) / prev.avgFps() * 100;
            CommandManager.print(String.format(Locale.ROOT, "§7vs last run: %s%+.0f%%§7 average (%.0f → %.0f FPS)",
                    d >= 0 ? "§a" : "§c", d, prev.avgFps(), r.avgFps()));
        }
        Notifications.push("Benchmark done", String.format(Locale.ROOT, "%.0f FPS average, %.0f 1%% low", r.avgFps(), r.low1()), true);
    }

    private static void save(Result r) {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("bench.txt");
        try {
            Files.createDirectories(file.getParent());
            String line = String.format(Locale.ROOT, "%s  avg %.1f  1%%low %.1f  0.1%%low %.1f  min %.1f  frames %d  (%s)%n",
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), r.avgFps(), r.low1(),
                    r.low01(), r.minFps(), r.frames(), r.setup());
            Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            EvadeClient.LOG.warn("Benchmark: couldn't save {}", file, e);
        }
    }

    /** The last result this session (for tests and the profiler). */
    public static Result last() { return last; }
}
