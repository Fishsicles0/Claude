package net.evadeclient.clips.events;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Turns game events into clips. Events close together become one clip (a triple kill is one clip, not three),
 * and it waits a few seconds after the last one so the clip shows the aftermath. The clip reaches back
 * {@code before} seconds before the first event.
 */
public final class AutoClipper {
    /** Saves a clip: seconds to keep, title, game, comma-separated tags. */
    public interface Sink { void save(int seconds, String title, String game, String tags) throws Exception; }

    private final Sink sink;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "auto-clipper");
        t.setDaemon(true);
        return t;
    });
    public volatile int before = 20, after = 4;
    /** Called for every event (clipped or not), to drop a named marker on the timeline. */
    public volatile java.util.function.Consumer<String> onMarker = l -> {};

    /** An event worth a bookmark but not a clip of its own (e.g. a death, unless deaths are clipped). */
    public void mark(String title) { onMarker.accept(title); }

    private final List<String> titles = new ArrayList<>();
    private final List<String> tags = new ArrayList<>();
    private int priority = -1;
    private String best = "";
    private String game;
    private long first;
    private ScheduledFuture<?> pending;

    public AutoClipper(Sink sink) { this.sink = sink; }

    /**
     * Records an event. {@code priority} picks the clip's title when several events merge (an ace beats a
     * kill); {@code title} is used if it's the most important so far.
     */
    public synchronized void event(String game, String title, String tag, int priority) {
        onMarker.accept(title);
        if (titles.isEmpty()) first = System.currentTimeMillis();
        this.game = game;
        titles.add(title);
        if (tag != null && !tag.isBlank() && !tags.contains(tag)) tags.add(tag);
        if (priority >= this.priority) { this.priority = priority; best = title; }
        if (pending != null) pending.cancel(false);
        pending = timer.schedule(this::flush, Math.max(0, after) * 1000L, TimeUnit.MILLISECONDS);
    }

    /** Saves the merged clip now (used by tests and when a match ends). */
    public void flush() {
        int seconds;
        String title, g, t;
        synchronized (this) {
            if (titles.isEmpty()) return;
            long span = (System.currentTimeMillis() - first) / 1000;
            seconds = (int) Math.min(600, before + span);
            title = best;
            g = game;
            t = String.join(",", tags);
            titles.clear();
            tags.clear();
            priority = -1;
            if (pending != null) pending.cancel(false);
            pending = null;
        }
        try { sink.save(seconds, title, g, t); }
        catch (Exception e) { System.err.println("[clips] auto clip failed: " + e.getMessage()); }
    }

    public void shutdown() { timer.shutdownNow(); }
}
