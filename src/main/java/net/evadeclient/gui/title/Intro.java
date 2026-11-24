package net.evadeclient.gui.title;

import net.evadeclient.gui.Motion;

/**
 * The main menu's entrance timeline. The first time the menu shows in a session everything arrives in a short
 * stagger (backdrop, logo, buttons, side cards, footer); afterwards (back from Options, a world or a server) it all
 * fades in together, quickly. With Animations off everything is simply there. Time-based, so it plays the same at
 * every frame rate.
 */
public final class Intro {
    private static final long CLOCK_ORIGIN = System.nanoTime();
    /** Whether the full entrance has played this session. */
    private static boolean played;

    private double start = -1;
    private boolean full;
    private boolean enabled = true;

    /** Milliseconds since the client started (monotonic). */
    public static double now() {
        return (System.nanoTime() - CLOCK_ORIGIN) / 1e6;
    }

    /** Starts the timeline (once per showing of the menu). */
    public void begin(boolean animations) {
        enabled = animations;
        full = !played;
        played = true;
        start = now();
    }

    /** Called when the menu is shown again (returning to it): replays the short fade. */
    public void restart(boolean animations) {
        enabled = animations;
        full = false;
        start = now();
    }

    public boolean full() { return full && enabled; }

    public boolean started() { return start >= 0; }

    /** Milliseconds into the timeline. */
    public double elapsed() {
        return start < 0 ? 1e9 : now() - start;
    }

    /**
     * Progress 0..1 (ease out) of an element that starts {@code delayMs} into the full entrance and takes
     * {@code durMs}. In the short replay every element uses 0 delay and 180 ms.
     */
    public float p(double delayMs, double durMs) {
        if (!enabled) return 1f;
        if (start < 0) return 0f; // not started yet (the loading screen is still up)
        double t = elapsed();
        if (!full) return Motion.cubicOut((float) (t / 180.0));
        return Motion.cubicOut((float) ((t - delayMs) / durMs));
    }

    /** Whether everything has arrived. */
    public boolean done() {
        return !enabled || elapsed() > (full ? 2200 : 200);
    }
}
