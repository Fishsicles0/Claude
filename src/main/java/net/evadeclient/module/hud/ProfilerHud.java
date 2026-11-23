package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.util.FrameStats;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where your frame time goes, updated once a second: FPS and 1% low, average and worst frame time,
 * how long Evade's HUD takes, and Java garbage-collection pauses (the usual cause of micro-stutter).
 *
 * <p>FPS, the average and the worst frame cover the last second; the 1% low covers the whole recorded window
 * (about ten seconds), since one second at under 200 FPS holds too few frames for a meaningful 1%.
 */
public class ProfilerHud extends TextHudModule {
    private List<String> shown = List.of();
    private long nextUpdate;
    private long lastGcCount = -1, lastGcMillis;

    public ProfilerHud() {
        super("Frame Profiler", "FPS, 1% low, frame time, HUD cost and garbage-collection pauses, once a second.", 4, 254);
    }

    @Override
    protected List<String> lines(boolean preview) {
        if (preview && FrameStats.count() == 0)
            return new ArrayList<>(List.of("144 FPS  ·  1% low 71", "6.9 ms  ·  worst 14.2 ms", "HUD 0.31 ms  ·  GC 1/s, 3 ms"));
        long now = System.currentTimeMillis();
        if (now >= nextUpdate) {
            nextUpdate = now + 1000;
            float[] second = FrameStats.summary(Math.max(60, mc.getCurrentFps()));
            // The last ten seconds, whatever the frame rate (the recorded history can reach about 50 s at 60 FPS).
            float[] window = FrameStats.summary((int) FrameStats.framesWithin(10_000)[0]);
            double hudMs = FrameStats.takeHudMicrosForProfiler() / 1000.0;
            long count = 0, millis = 0;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                // Concurrent collectors (ZGC, Shenandoah) also report whole "Cycles" that run alongside the game;
                // only their "Pauses" stop a frame.
                if (gc.getName().endsWith("Cycles")) continue;
                count += Math.max(0, gc.getCollectionCount());
                millis += Math.max(0, gc.getCollectionTime());
            }
            long dCount = lastGcCount < 0 ? 0 : count - lastGcCount, dMillis = lastGcCount < 0 ? 0 : millis - lastGcMillis;
            lastGcCount = count; lastGcMillis = millis;
            float avgMs = second[0] > 0 ? 1000f / second[0] : 0;
            shown = List.of(
                    Math.round(second[0]) + " FPS  ·  1% low " + Math.round(window[1]),
                    String.format(Locale.ROOT, "%.1f ms  ·  worst %.1f ms", avgMs, second[2]),
                    String.format(Locale.ROOT, "HUD %.2f ms  ·  GC %d/s, %d ms", hudMs, dCount, dMillis));
        }
        return new ArrayList<>(shown);
    }
}
