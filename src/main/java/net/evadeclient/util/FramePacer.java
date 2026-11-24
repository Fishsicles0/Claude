package net.evadeclient.util;

import java.util.concurrent.locks.LockSupport;

/**
 * Smooth Pacing's frame limiter: holds each frame to an even schedule (one deadline per frame, each one period after
 * the last) by sleeping until shortly before the deadline and then spinning for the rest. The game's own limiter
 * waits for window events with a timeout, which the OS rounds to its timer (up to several ms on Windows), so frames
 * come out uneven; this keeps them within a fraction of a millisecond. Render thread only.
 */
public final class FramePacer {
    private FramePacer() {}

    /** Spin for the last part of each wait; sleeping is only precise to about a millisecond. */
    private static final long SPIN_NS = 1_200_000L, SPIN_BATTERY_NS = 400_000L;

    private static long next;
    private static int lastFps;
    /** Recent frame intervals the pacer released (ns), for tests and the status line. */
    private static final long[] intervals = new long[256];
    private static int intervalCount;
    private static long lastRelease;

    /** Waits until this frame's deadline at {@code fps} frames a second. */
    public static void limit(int fps) {
        if (fps <= 0) return;
        long period = 1_000_000_000L / fps;
        long now = System.nanoTime();
        if (fps != lastFps || next == 0 || now - next > period * 4) next = now; // new rate, or a long stall: start fresh
        lastFps = fps;
        next += period;
        long late = now - next;
        if (late > period) {
            // Already more than a frame behind (a slow frame): don't rush the next ones to catch up.
            next = now;
        } else if (late < 0) {
            long spin = PowerState.onBattery() ? SPIN_BATTERY_NS : SPIN_NS;
            long sleepUntil = next - spin;
            while ((now = System.nanoTime()) < sleepUntil) LockSupport.parkNanos(sleepUntil - now);
            while (System.nanoTime() < next) Thread.onSpinWait();
        }
        long released = System.nanoTime();
        if (lastRelease != 0) intervals[intervalCount++ % intervals.length] = released - lastRelease;
        lastRelease = released;
    }

    /** Forget the schedule and the recorded intervals (pacing switched on or off). */
    public static void reset() {
        next = 0;
        lastFps = 0;
        lastRelease = 0;
        intervalCount = 0;
    }

    /** {mean, standard deviation} of the recorded frame intervals in ms; zeros when fewer than 2 were recorded. */
    public static double[] intervalStats() {
        int n = Math.min(intervalCount, intervals.length);
        if (n < 2) return new double[2];
        double sum = 0;
        for (int i = 0; i < n; i++) sum += intervals[i];
        double mean = sum / n, var = 0;
        for (int i = 0; i < n; i++) var += (intervals[i] - mean) * (intervals[i] - mean);
        return new double[]{mean / 1e6, Math.sqrt(var / n) / 1e6};
    }
}
