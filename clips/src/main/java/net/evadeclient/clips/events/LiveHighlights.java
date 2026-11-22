package net.evadeclient.clips.events;

import net.evadeclient.clips.core.CaptureEngine;
import net.evadeclient.clips.core.Highlights;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.function.Supplier;

/**
 * Auto-clips for any game, including ones with no event API (The Finals, Valorant, Fortnite, Apex…): while you
 * play, the newest game audio is measured every few seconds, and a moment that jumps well above the last minute
 * of play (a gunfight, an explosion, a cashout) becomes a clip through the same {@link AutoClipper} as game
 * events, so a fight is one clip, not five.
 */
public final class LiveHighlights {
    private static final double WINDOW = 0.25;
    private final CaptureEngine engine;
    private final AutoClipper clipper;
    private final Supplier<String> game;
    private final Path work;
    /** Loudness history (dB per 0.25 s) for the last ~60 s: the baseline a moment must stand out from. */
    private final Deque<Double> history = new ArrayDeque<>();
    private int lastSegment = -1;
    private long cooldownUntil;
    /** dB above the recent median that counts as a highlight (low sensitivity = needs a bigger jump). */
    public volatile double threshold = 12;
    private volatile boolean running;
    private Thread thread;

    public LiveHighlights(CaptureEngine engine, AutoClipper clipper, Supplier<String> game, Path workDir) {
        this.engine = engine;
        this.clipper = clipper;
        this.game = game;
        this.work = workDir;
    }

    public static double thresholdFor(String sensitivity) {
        return switch (sensitivity == null ? "medium" : sensitivity) { case "high" -> 9; case "low" -> 16; default -> 12; };
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(() -> {
            while (running) {
                try { step(); } catch (Exception e) { /* capture restarting etc.; try again next round */ }
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        }, "live-highlights");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        history.clear();
        lastSegment = -1;
    }

    public boolean running() { return running; }

    /** Analyses whatever audio arrived since last time. Public for tests. */
    public void step() throws Exception {
        if (!engine.isLive()) { history.clear(); lastSegment = -1; return; }
        Files.createDirectories(work);
        Path tmp = work.resolve("live.ts");
        int newest = engine.copyNewSegments(lastSegment, tmp);
        if (newest == lastSegment) return;
        boolean first = lastSegment < 0;
        lastSegment = newest;
        double[] db = Highlights.loudness(tmp, null);
        Files.deleteIfExists(tmp);
        if (first) { for (double d : db) push(d); return; } // first look only builds the baseline
        for (double d : db) {
            double median = median();
            boolean warmedUp = history.size() >= 40; // 10 s of play before judging
            push(d);
            if (!warmedUp || d < -40 || d - median < threshold) continue;
            long now = System.currentTimeMillis();
            if (now < cooldownUntil) continue;
            cooldownUntil = now + 4000;
            clipper.event(game.get(), "Highlight", "highlight", 1);
        }
    }

    private void push(double d) {
        history.addLast(d);
        while (history.size() > 60 / WINDOW) history.removeFirst();
    }

    private double median() {
        if (history.isEmpty()) return 0;
        double[] a = history.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(a);
        return a[a.length / 2];
    }
}
