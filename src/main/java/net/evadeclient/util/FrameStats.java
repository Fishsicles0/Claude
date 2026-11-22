package net.evadeclient.util;

import java.util.Arrays;

/** Records how long every frame took (measured at the end of each frame) for the Frame Time Graph. */
public final class FrameStats {
    public static final int SIZE = 600; // ~10 s at 60 FPS
    private static final float[] MS = new float[SIZE];
    private static int head, count;
    private static long last;

    private FrameStats() {}

    /** Called once per frame, after it has been drawn. */
    public static void frameEnd() {
        long now = System.nanoTime();
        if (last != 0) {
            float ms = (now - last) / 1_000_000f;
            if (ms < 1000) { MS[head] = ms; head = (head + 1) % SIZE; count = Math.min(count + 1, SIZE); }
        }
        last = now;
    }

    public static int count() { return count; }

    /** The i-th most recent frame time in ms (0 = latest). */
    public static float recent(int i) { return MS[Math.floorMod(head - 1 - i, SIZE)]; }

    /** {average FPS, 1% low FPS, worst frame ms} over the last {@code n} frames. */
    public static float[] summary(int n) {
        n = Math.min(n, count);
        if (n == 0) return new float[]{0, 0, 0};
        float[] f = new float[n];
        double total = 0;
        for (int i = 0; i < n; i++) { f[i] = recent(i); total += f[i]; }
        Arrays.sort(f);
        int slow = Math.max(1, n / 100);
        double slowTotal = 0;
        for (int i = n - slow; i < n; i++) slowTotal += f[i];
        return new float[]{(float) (1000.0 * n / total), (float) (1000.0 * slow / slowTotal), f[n - 1]};
    }
}
