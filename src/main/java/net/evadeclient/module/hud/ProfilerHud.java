package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.util.FrameStats;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Where your frame time goes, updated once a second: FPS and 1% low, average and worst frame time,
 * how long Evade's HUD takes, and Java garbage-collection pauses (the usual cause of micro-stutter).
 */
public class ProfilerHud extends TextHudModule {
    private List<String> shown = List.of();
    private long nextUpdate;
    private long lastGcCount = -1, lastGcMillis;

    public ProfilerHud() {
        super("Frame Profiler", "FPS, 1% low, frame time, HUD cost and garbage-collection pauses, once a second.", 4, 150);
    }

    @Override
    protected List<String> lines(boolean preview) {
        if (preview) return new ArrayList<>(List.of("144 FPS  ·  1% low 96", "6.9 ms  ·  worst 14.2 ms", "HUD 0.31 ms  ·  GC 1/s, 3 ms"));
        long now = System.currentTimeMillis();
        if (now >= nextUpdate) {
            nextUpdate = now + 1000;
            float[] f = FrameStats.summary(Math.max(60, mc.getCurrentFps()));
            double hudMs = FrameStats.takeHudMicros() / 1000.0;
            long count = 0, millis = 0;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                count += Math.max(0, gc.getCollectionCount());
                millis += Math.max(0, gc.getCollectionTime());
            }
            long dCount = lastGcCount < 0 ? 0 : count - lastGcCount, dMillis = lastGcCount < 0 ? 0 : millis - lastGcMillis;
            lastGcCount = count; lastGcMillis = millis;
            float avgMs = f[0] > 0 ? 1000f / f[0] : 0;
            shown = List.of(
                    Math.round(f[0]) + " FPS  ·  1% low " + Math.round(f[1]),
                    String.format("%.1f ms  ·  worst %.1f ms", avgMs, f[2]),
                    String.format("HUD %.2f ms  ·  GC %d/s, %d ms", hudMs, dCount, dMillis));
        }
        return new ArrayList<>(shown);
    }
}
