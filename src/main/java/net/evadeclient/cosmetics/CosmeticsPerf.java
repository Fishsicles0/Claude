package net.evadeclient.cosmetics;

/** How long the cosmetics layer takes (render thread): read by the tests and the frame profiler comparisons. */
public final class CosmeticsPerf {
    private static long nanos, frames, drawn;
    private static long lastFrame = -1;

    private CosmeticsPerf() {}

    static void add(long ns) { nanos += ns; }

    static void drew() { drawn++; }

    /** Emissive (glow layer) parts drawn since launch (tests). */
    public static long glowParts;

    /** Average microseconds per call window since the last take, and resets. */
    public static double takeMicros(long frameCount) {
        double us = frameCount <= 0 ? 0 : nanos / 1000.0 / frameCount;
        nanos = 0;
        return us;
    }

    /** Players drawn with cosmetics since the last take, and resets. */
    public static long takeDrawn() { long d = drawn; drawn = 0; return d; }

    /** Total cosmetics time since the last take in microseconds (resets). */
    public static double takeTotalMicros() { double us = nanos / 1000.0; nanos = 0; return us; }
}
