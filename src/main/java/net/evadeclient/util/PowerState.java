package net.evadeclient.util;

import net.evadeclient.EvadeClient;

/**
 * Whether this PC runs on battery, read through OSHI (which ships with Minecraft) on a background thread every
 * {@link #POLL_MS} ms, so the render thread only ever reads two volatile fields. A desktop (no battery) reads as
 * plugged in. Started on first use.
 */
public final class PowerState {
    private PowerState() {}

    static final long POLL_MS = 15_000;

    private static volatile boolean onBattery, hasBattery;
    private static volatile int percent = -1;
    private static volatile boolean started, failed;
    /** Test hook: pretend to be on battery (TRUE), plugged in (FALSE), or read the real state (null). */
    public static Boolean forced;

    /** Starts the poller once. Safe from any thread. */
    public static synchronized void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(PowerState::loop, "Evade power state");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void loop() {
        while (!failed) {
            poll();
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static void poll() {
        try {
            var sources = new oshi.SystemInfo().getHardware().getPowerSources();
            boolean any = false, discharging = false;
            int pct = -1;
            for (var s : sources) {
                double cap = s.getRemainingCapacityPercent();
                // Some systems list a phantom source with no capacity at all: not a battery.
                if (!(cap > 0)) continue;
                any = true;
                pct = Math.max(pct, (int) Math.round(cap * 100));
                if (!s.isPowerOnLine() && (s.isDischarging() || !s.isCharging())) discharging = true;
            }
            hasBattery = any;
            onBattery = any && discharging;
            percent = pct;
        } catch (Throwable t) {
            // OSHI can't read power on this system: treat it as plugged in, and stop asking.
            failed = true;
            hasBattery = false;
            onBattery = false;
            EvadeClient.LOG.info("Power state unavailable ({}): battery features are off", t.toString());
        }
    }

    /** True while running on battery. */
    public static boolean onBattery() {
        if (forced != null) return forced;
        if (!started) start();
        return onBattery;
    }

    public static boolean hasBattery() { return forced != null || hasBattery; }

    /** Battery charge in percent, or -1 when unknown. */
    public static int percent() { return forced != null ? 50 : percent; }

    /** "On battery (73%)", "Plugged in", "No battery" for status lines. */
    public static String describe() {
        if (!started && forced == null) start();
        if (!hasBattery()) return failed ? "Can't read the battery" : "No battery";
        int p = percent();
        return onBattery() ? "On battery" + (p >= 0 ? " (" + p + "%)" : "") : "Plugged in" + (p >= 0 ? " (" + p + "%)" : "");
    }
}
