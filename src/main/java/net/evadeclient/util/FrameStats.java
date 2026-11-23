package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;

import java.util.Arrays;

/**
 * Records how long every frame took (measured at the end of each frame) for the FPS widget, the Frame Time Graph
 * and the Frame Profiler. Render thread only.
 */
public final class FrameStats {
    /** Frames kept: ten seconds at 300 FPS, about fifty at 60 FPS. */
    public static final int SIZE = 3000;
    private static final float[] MS = new float[SIZE];
    /** Scratch copy that {@link #summary} sorts, so a summary allocates nothing but its result. */
    private static final float[] SORT = new float[SIZE];
    private static int head, count;
    private static long last;

    private FrameStats() {}

    /** Called once per frame, after it has been drawn. */
    public static void frameEnd() {
        long now = System.nanoTime();
        if (last != 0) {
            float ms = (now - last) / 1_000_000f;
            if (ms < 1000) { MS[head] = ms; head = (head + 1) % SIZE; count = Math.min(count + 1, SIZE); Benchmark.onFrame(ms); }
        }
        last = now;
    }

    public static int count() { return count; }

    // ---- Evade's own HUD cost per frame (so we can keep it near zero)
    // Two independent accumulators: one for tests and tools (takeHudMicros), one for the Frame Profiler, so the
    // profiler's once-a-second reading never resets a measurement someone else is taking.
    private static long hudNanos, hudFrames, profNanos, profFrames;

    public static void addHudTime(long nanos) {
        hudNanos += nanos; hudFrames++;
        profNanos += nanos; profFrames++;
    }

    /** Average time Evade's HUD took per frame since the last call, in microseconds (resets). */
    public static double takeHudMicros() {
        double us = hudFrames == 0 ? 0 : hudNanos / 1000.0 / hudFrames;
        hudNanos = 0; hudFrames = 0;
        return us;
    }

    /** Like {@link #takeHudMicros} but with the Frame Profiler's own accumulator (resets only that one). */
    public static double takeHudMicrosForProfiler() {
        double us = profFrames == 0 ? 0 : profNanos / 1000.0 / profFrames;
        profNanos = 0; profFrames = 0;
        return us;
    }

    private static int refreshHz = 60;
    private static long refreshCheckedMs;

    /**
     * The refresh rate of the monitor the game is on (the primary monitor while windowed) in Hz, checked at most
     * every two seconds since it asks GLFW; 60 when it can't be read. Render thread only.
     */
    public static int refreshRate() {
        long now = System.currentTimeMillis();
        if (now - refreshCheckedMs >= 2000 || now < refreshCheckedMs) {
            refreshCheckedMs = now;
            try {
                int hz = MinecraftClient.getInstance().getWindow().getRefreshRate();
                if (hz > 0) refreshHz = hz;
            } catch (RuntimeException ignored) {
                // No window yet, or called off the render thread: keep the last known rate.
            }
        }
        return refreshHz;
    }

    /** The i-th most recent frame time in ms (0 = latest). */
    public static float recent(int i) { return MS[Math.floorMod(head - 1 - i, SIZE)]; }

    /**
     * The most recent frames that together cover {@code ms} milliseconds: {frames, their total ms}. Walks back from
     * the newest frame until the total reaches {@code ms} (the frame that crosses it counts), or the recorded frames
     * run out, so a short history returns fewer milliseconds than asked for. {0, 0} when nothing is recorded yet.
     */
    public static float[] framesWithin(float ms) {
        int n = 0;
        double total = 0;
        while (n < count && total < ms) total += recent(n++);
        return new float[]{n, (float) total};
    }

    /**
     * Statistics over the last {@code n} frames (fewer when fewer are recorded; all zeros when none are):
     * <ol start="0">
     *   <li>average FPS</li>
     *   <li>1% low FPS (the average of the slowest 1% of frames, at least one)</li>
     *   <li>worst frame, ms</li>
     *   <li>0.1% low FPS (the slowest 0.1%, at least one frame)</li>
     *   <li>average frame time, ms</li>
     *   <li>99th percentile frame time, ms</li>
     *   <li>median frame time, ms</li>
     * </ol>
     * New values are only ever appended, so callers that read the first three stay correct.
     */
    public static float[] summary(int n) {
        n = Math.min(n, count);
        if (n <= 0) return new float[7];
        float[] f = SORT;
        double total = 0;
        for (int i = 0; i < n; i++) { f[i] = recent(i); total += f[i]; }
        Arrays.sort(f, 0, n);
        int slow = Math.max(1, n / 100), slowest = Math.max(1, n / 1000);
        double slowTotal = 0, slowestTotal = 0;
        for (int i = n - slow; i < n; i++) slowTotal += f[i];
        for (int i = n - slowest; i < n; i++) slowestTotal += f[i];
        // Sub-nanosecond frames can't happen, but never divide by zero.
        total = Math.max(total, 1e-6); slowTotal = Math.max(slowTotal, 1e-6); slowestTotal = Math.max(slowestTotal, 1e-6);
        int p99 = Math.max(0, Math.min(n - 1, (int) Math.ceil(n * 0.99) - 1));
        float median = (n & 1) == 1 ? f[n / 2] : (f[n / 2 - 1] + f[n / 2]) / 2f;
        return new float[]{
                (float) (1000.0 * n / total),
                (float) (1000.0 * slow / slowTotal),
                f[n - 1],
                (float) (1000.0 * slowest / slowestTotal),
                (float) (total / n),
                f[p99],
                median};
    }
}
