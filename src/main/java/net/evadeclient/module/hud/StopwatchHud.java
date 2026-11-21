package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;

import java.util.ArrayList;
import java.util.List;

/** A running stopwatch. Starts when you turn it on; the elapsed time shows on the HUD. */
public class StopwatchHud extends TextHudModule {
    private long startedAt;

    public StopwatchHud() {
        super("Stopwatch", "A running stopwatch.", 4, 160);
    }

    /** Milliseconds since the stopwatch started (0 when off). */
    public long elapsedMs() {
        if (!isEnabled()) return 0;
        if (startedAt == 0) startedAt = System.currentTimeMillis();
        return System.currentTimeMillis() - startedAt;
    }

    @Override
    protected void onDisable() { startedAt = 0; }

    @Override
    protected List<String> lines(boolean preview) {
        long ms = preview ? 83_450 : elapsedMs();
        long sec = ms / 1000;
        return new ArrayList<>(List.of(String.format("%d:%02d.%02d", sec / 60, sec % 60, (ms % 1000) / 10)));
    }
}
