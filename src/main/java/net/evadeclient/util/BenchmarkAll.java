package net.evadeclient.util;

import net.evadeclient.command.CommandManager;
import net.evadeclient.module.mods.Performance;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Benchmark Every FPS Mode" (Performance, or {@code .bench all}): picks Max FPS, Balanced and Quality in turn, lets
 * each settle (render distance changes rebuild chunks), measures it with {@link Benchmark}, then puts your own FPS Mode
 * back and recommends one: the best-looking mode that still averages your monitor's refresh rate (60 to 144) with a
 * 1% low of at least half that. If none does, the fastest. Nothing is changed for you: you pick the mode yourself.
 * Client thread only ({@link #tick} runs from Performance's tick).
 */
public final class BenchmarkAll {
    /** Best-looking first: the recommendation is the first that keeps up. */
    public static final List<String> MODES = List.of("Quality", "Balanced", "Max FPS");
    /** Ticks a mode settles before it's measured (chunks rebuild after the render distance changes). */
    static final int SETTLE_TICKS = 100;
    public static final int DEFAULT_SECONDS = 10;

    private enum Phase { IDLE, SETTLE, MEASURE }

    private static Phase phase = Phase.IDLE;
    private static int index, wait, seconds = DEFAULT_SECONDS;
    private static String originalMode;
    private static final Map<String, Benchmark.Result> results = new LinkedHashMap<>();
    private static String summary = "";

    private BenchmarkAll() {}

    public static boolean running() { return phase != Phase.IDLE; }

    /** The last full run's results by mode (for tests and the status line). */
    public static Map<String, Benchmark.Result> results() { return new LinkedHashMap<>(results); }

    /** One line for Performance's status row: progress, the last recommendation, or how to start. */
    public static String status() {
        if (running()) {
            String mode = MODES.get(Math.min(index, MODES.size() - 1));
            return "Measuring " + mode + " (" + (index + 1) + " of " + MODES.size() + ")";
        }
        return summary.isEmpty() ? "Click to run (about " + estimateSeconds(seconds) + " s)" : summary;
    }

    static int estimateSeconds(int perMode) { return MODES.size() * (perMode + SETTLE_TICKS / 20); }

    /**
     * Starts a run with {@code perMode} seconds per FPS Mode. False (with a message in chat) when there's no world,
     * Performance is off, or a benchmark is already running.
     */
    public static boolean start(int perMode) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Performance p = Performance.active();
        if (mc.player == null || mc.world == null) { CommandManager.print("§cJoin a world first."); return false; }
        if (p == null) { CommandManager.print("§cTurn Performance on first: FPS Modes are part of it."); return false; }
        if (running() || Benchmark.running()) { CommandManager.print("§cA benchmark is already running."); return false; }
        seconds = Math.max(5, Math.min(60, perMode));
        originalMode = p.fpsMode.get();
        results.clear();
        index = 0;
        begin(p);
        if (mc.currentScreen != null) mc.setScreen(null);
        CommandManager.print("§7Benchmarking every FPS Mode, " + seconds + " s each (about " + estimateSeconds(seconds) + " s)."
                + (mc.isInSingleplayer() ? " The camera turns while it measures." : " Stand still; nothing is sent to the server."));
        return true;
    }

    /** Stops a run and puts your FPS Mode back. */
    public static void cancel() {
        if (!running()) return;
        if (Benchmark.running()) Benchmark.cancel();
        phase = Phase.IDLE;
        restore();
        CommandManager.print("§7FPS Mode benchmark stopped. Your FPS Mode is back to " + originalMode + ".");
    }

    private static void begin(Performance p) {
        p.fpsMode.set(MODES.get(index));
        phase = Phase.SETTLE;
        wait = SETTLE_TICKS;
    }

    /** Every client tick while Performance is on. */
    public static void tick() {
        if (!running()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        Performance p = Performance.active();
        if (p == null || mc.world == null || mc.player == null) { cancel(); return; }
        switch (phase) {
            case SETTLE -> {
                if (--wait > 0) return;
                Benchmark.setQuiet(true);
                if (!Benchmark.start(seconds)) { Benchmark.setQuiet(false); cancel(); return; }
                phase = Phase.MEASURE;
            }
            case MEASURE -> {
                if (Benchmark.running()) return;
                Benchmark.setQuiet(false);
                Benchmark.Result r = Benchmark.last();
                if (r == null) { cancel(); return; }
                results.put(MODES.get(index), r);
                if (++index < MODES.size()) begin(p);
                else finish();
            }
            default -> { }
        }
    }

    private static void finish() {
        phase = Phase.IDLE;
        restore();
        int target = target();
        String best = recommend(results, target);
        List<String> parts = new ArrayList<>();
        CommandManager.print("§fFPS Modes on this PC §7(target " + target + " FPS):");
        for (String m : MODES) {
            Benchmark.Result r = results.get(m);
            if (r == null) continue;
            CommandManager.print(String.format(Locale.ROOT, "  %s%s§7: §a%.0f FPS§7 average, §e%.0f§7 1%% low", m.equals(best) ? "§c» §f" : "§7", m, r.avgFps(), r.low1()));
            parts.add(m);
        }
        Benchmark.Result b = results.get(best);
        summary = b == null ? "" : String.format(Locale.ROOT, "Best for you: %s (%.0f FPS, 1%% low %.0f)", best, b.avgFps(), b.low1());
        CommandManager.print("§fRecommended: §c" + best + "§7. " + (best.equals(originalMode) ? "That's the mode you use already."
                : "Pick it in Performance > FPS Mode (you're on " + originalMode + ")."));
        Notifications.push("FPS Mode benchmark", "Best for this PC: " + best, true);
    }

    private static void restore() {
        Performance p = Performance.active();
        if (p != null && originalMode != null) p.fpsMode.set(originalMode);
    }

    /** The frame rate to aim for: your monitor's refresh rate, between 60 and 144. */
    static int target() {
        int hz = FrameStats.refreshRate();
        return Math.max(60, Math.min(144, hz <= 0 ? 60 : hz));
    }

    /**
     * The best-looking mode that keeps up: average at least {@code target} and 1% low at least half of it. When none
     * does, the one with the highest average.
     */
    public static String recommend(Map<String, Benchmark.Result> res, int target) {
        for (String m : MODES) {
            Benchmark.Result r = res.get(m);
            if (r != null && r.avgFps() >= target && r.low1() >= target * 0.5) return m;
        }
        String best = MODES.get(MODES.size() - 1);
        double top = -1;
        for (String m : MODES) {
            Benchmark.Result r = res.get(m);
            if (r != null && r.avgFps() > top) { top = r.avgFps(); best = m; }
        }
        return best;
    }
}
