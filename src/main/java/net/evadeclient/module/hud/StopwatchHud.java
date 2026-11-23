package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A stopwatch on the HUD. Starts when you turn it on (unless Start On Enable is off); pause, resume and reset it
 * with its own keys without hiding it.
 */
public class StopwatchHud extends TextHudModule {
    private final KeySetting startPauseKey = add(new KeySetting("Start/Pause Key", GLFW.GLFW_KEY_UNKNOWN)
            .describe("Starts, pauses and resumes the stopwatch."));
    private final KeySetting resetKey = add(new KeySetting("Reset Key", GLFW.GLFW_KEY_UNKNOWN)
            .describe("Sets the stopwatch back to zero (it keeps running if it was)."));
    private final BooleanSetting startOnEnable = add(new BooleanSetting("Start On Enable", true)
            .describe("Start counting as soon as the stopwatch is turned on."));

    /** Time counted before the current run, and when the current run started (0 while paused). */
    private long accumulatedMs, runningSince;
    private boolean startPauseWasDown, resetWasDown;

    public StopwatchHud() {
        super("Stopwatch", "A stopwatch you can start, pause and reset.", 4, 168);
    }

    /** Milliseconds counted so far (0 when off). Reading it changes nothing. */
    public long elapsedMs() {
        if (!isEnabled()) return 0;
        return accumulatedMs + (runningSince != 0 ? System.currentTimeMillis() - runningSince : 0);
    }

    public boolean isRunning() { return isEnabled() && runningSince != 0; }

    /** Start, or pause a running stopwatch. */
    public void startPause() {
        long now = System.currentTimeMillis();
        if (runningSince != 0) {
            accumulatedMs += now - runningSince;
            runningSince = 0;
        } else {
            runningSince = now;
        }
    }

    /** Back to zero; a running stopwatch keeps running from zero. */
    public void reset() {
        accumulatedMs = 0;
        if (runningSince != 0) runningSince = System.currentTimeMillis();
    }

    @Override
    protected void onEnable() {
        accumulatedMs = 0;
        runningSince = startOnEnable.get() ? System.currentTimeMillis() : 0;
        // A key held while turning the stopwatch on doesn't count as a press.
        startPauseWasDown = startPauseKey.isDown();
        resetWasDown = resetKey.isDown();
    }

    @Override
    protected void onDisable() {
        accumulatedMs = 0;
        runningSince = 0;
    }

    @Override
    public void onTick() {
        // KeySetting.isDown is false while any screen is open, so typing in chat never starts or resets it.
        boolean sp = startPauseKey.isDown(), rs = resetKey.isDown();
        if (sp && !startPauseWasDown) startPause();
        if (rs && !resetWasDown) reset();
        startPauseWasDown = sp;
        resetWasDown = rs;
    }

    @Override
    protected List<String> lines(boolean preview) {
        long ms = preview && elapsedMs() == 0 ? 83_450 : elapsedMs();
        long sec = ms / 1000, cs = ms % 1000 / 10;
        String t = sec >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d.%02d", sec / 3600, sec / 60 % 60, sec % 60, cs)
                : String.format(Locale.ROOT, "%d:%02d.%02d", sec / 60, sec % 60, cs);
        if (!preview && runningSince == 0 && ms > 0) t += " (paused)";
        return new ArrayList<>(List.of(t));
    }
}
