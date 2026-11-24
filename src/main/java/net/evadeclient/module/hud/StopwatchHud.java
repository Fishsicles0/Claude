package net.evadeclient.module.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.sound.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A stopwatch, or a countdown timer, on the HUD. It starts when you turn it on, the first time you move, or only
 * with its key (Auto Start); pause, resume, reset and record laps with its own keys without hiding it. Time is
 * wall-clock time, or the world's ticks so lag and pauses don't count (Time Source).
 */
public class StopwatchHud extends TextHudModule {
    private static final int FASTER = 0xFF3BE37A, SLOWER = 0xFFFF4D5E, FLASH = 0xFFFF4D5E;
    /** How long the time flashes red when a countdown reaches zero, and how fast it blinks. */
    private static final long FLASH_MS = 3000, BLINK_MS = 250;
    /** A jump in the world's clock larger than this (10 s) is a world change or a correction, not time played. */
    private static final long MAX_TICK_STEP = 200;
    private static final long MS_PER_TICK = 50;
    private static final long[] POW10 = {1, 10, 100, 1000};
    /** What the HUD editor shows while the stopwatch is at zero: a running time and two laps. */
    private static final long SAMPLE_MS = 83_450;
    private static final long[] SAMPLE_SPLITS = {41_200, 81_550};
    /** Kinds of line for {@link #lineColor}: the time, a lap as fast as the one before (or the first), faster, slower. */
    private static final int MAIN = 0, SAME = 1, FAST = 2, SLOW = 3;

    private final KeySetting startPauseKey = add(new KeySetting("Start/Pause Key", GLFW.GLFW_KEY_UNKNOWN)
            .describe("Starts, pauses and resumes the timer."));
    private final KeySetting resetKey = add(new KeySetting("Reset Key", GLFW.GLFW_KEY_UNKNOWN)
            .describe("Sets the timer back to the start (it keeps running if it was, unless Auto Start is On First Move)."));
    private final KeySetting lapKey = add(new KeySetting("Lap Key", GLFW.GLFW_KEY_UNKNOWN)
            .describe("Records a lap while the timer runs."));
    private final ModeSetting autoStart = add(new ModeSetting("Auto Start", "On Enable", "On Enable", "On First Move", "Manual")
            .describe("When the timer starts by itself: as soon as it is turned on, the first time you move or jump after turning it on or resetting it, or never (only Start/Pause Key)."));
    private final BooleanSetting resetOnWorldChange = add(new BooleanSetting("Reset On World Change", false)
            .describe("Back to the start when you change worlds, dimensions or servers."));
    private final ModeSetting mode = add(new ModeSetting("Mode", "Stopwatch", "Stopwatch", "Countdown")
            .describe("Count up from zero, or count down from Countdown Minutes and Seconds."));
    private final NumberSetting countdownMinutes = add(new NumberSetting("Countdown Minutes", 5, 0, 120, 1)
            .describe("Minutes the countdown starts from."));
    private final NumberSetting countdownSeconds = add(new NumberSetting("Countdown Seconds", 0, 0, 55, 5)
            .describe("Seconds added to Countdown Minutes."));
    private final BooleanSetting finishAlert = add(new BooleanSetting("Finish Alert", true)
            .describe("A toast, a chime and a red flash when the countdown reaches zero."));
    private final BooleanSetting loopCountdown = add(new BooleanSetting("Loop Countdown", false)
            .describe("Start the countdown over as soon as it finishes instead of holding at zero."));
    private final ModeSetting format = add(new ModeSetting("Format", "Auto", "Auto", "M:SS", "H:MM:SS", "Seconds")
            .describe("How the time is written. Auto shows minutes and seconds, and hours too once past an hour."));
    private final NumberSetting decimals = add(new NumberSetting("Decimals", 2, 0, 3, 1)
            .describe("Digits shown after the seconds."));
    private final ModeSetting timeSource = add(new ModeSetting("Time Source", "Real Time", "Real Time", "Game Ticks")
            .describe("Real Time is wall-clock time. Game Ticks counts the world's ticks (20 a second), so lag and pauses don't count."));
    private final BooleanSetting pauseWithGame = add(new BooleanSetting("Pause With Game", false)
            .describe("Real Time stops while the singleplayer pause menu is open."));
    private final NumberSetting shownLaps = add(new NumberSetting("Shown Laps", 3, 0, 8, 1)
            .describe("How many of the latest laps are listed under the time, newest first. The best lap is marked *."));
    private final ModeSetting lapTime = add(new ModeSetting("Lap Time", "Lap", "Lap", "Split", "Both")
            .describe("Each lap row shows the lap's own time, the timer's reading when it was recorded, or both."));
    private final BooleanSetting lapDelta = add(new BooleanSetting("Lap Delta", true)
            .describe("How much faster (green) or slower (red) each lap was than the one before."));

    /** Started and not paused by the user (Pause With Game can still hold the real-time clock). */
    private boolean running;
    /** On Enable: start once the enable hooks run (in a world). */
    private boolean autoStartPending;
    /** A countdown that reached zero and is holding there. */
    private boolean finished;
    /** Real time counted before the current run, and when the current run started (0 while not counting). */
    private long accumulatedMs, runningSince;
    /** Game ticks counted while running, and the world clock last tick ({@link Long#MIN_VALUE}: unknown). */
    private long tickCount, lastWorldTime = Long.MIN_VALUE;
    /** The timer's elapsed time at each lap. */
    private final List<Long> splits = new ArrayList<>();
    /** When a countdown last reached zero (for the red flash), 0 for never. */
    private long finishedAt;
    private boolean startPauseWasDown, resetWasDown, lapWasDown;
    private WeakReference<ClientWorld> lastWorld = new WeakReference<>(null);
    /** Whether a world has been seen since the stopwatch was turned on (the first one is no change). */
    private boolean seenWorld;
    /** Line kinds of the last lines built, in game and in the HUD editor (lineColor runs every frame). */
    private int[] kinds = {MAIN}, previewKinds = {MAIN};
    private boolean drawingPreview;

    public StopwatchHud() {
        super("Stopwatch", "A stopwatch or countdown you can start, pause, reset and lap.", 4, 168);
    }

    // ---- clock ---------------------------------------------------------------------------------------------

    /** Milliseconds counted so far in the chosen Time Source (0 when off). Reading it changes nothing. */
    public long elapsedMs() {
        if (!isEnabled()) return 0;
        return gameTicks() ? tickCount * MS_PER_TICK : realMs();
    }

    private long realMs() {
        return accumulatedMs + (runningSince != 0 ? Math.max(0, System.currentTimeMillis() - runningSince) : 0);
    }

    private boolean gameTicks() { return timeSource.is("Game Ticks"); }
    private boolean countdown() { return mode.is("Countdown"); }

    /** The countdown's length in ms; 0 means no countdown (it never finishes). */
    private long targetMs() { return (countdownMinutes.getInt() * 60L + countdownSeconds.getInt()) * 1000L; }

    public boolean isRunning() { return isEnabled() && running; }

    /** Whether the real-time clock should be counting right now (running, and not held by the pause menu). */
    private boolean realClockOn() { return running && !(pauseWithGame.get() && mc.isPaused()); }

    /** Starts or stops the real-time segment to match {@link #realClockOn}. */
    private void syncRealClock() {
        long now = System.currentTimeMillis();
        boolean on = realClockOn();
        if (on && runningSince == 0) runningSince = now;
        else if (!on && runningSince != 0) {
            accumulatedMs += Math.max(0, now - runningSince);
            runningSince = 0;
        }
    }

    /** Both clocks set to {@code ms} (switching Time Source mid-run then reads the same). */
    private void setElapsed(long ms) {
        ms = Math.max(0, ms);
        accumulatedMs = ms;
        runningSince = 0;
        tickCount = ms / MS_PER_TICK;
        syncRealClock();
    }

    private void start() {
        running = true;
        finished = false;
        syncRealClock();
    }

    private void pause() {
        running = false;
        syncRealClock();
    }

    /** Back to zero, laps and the finish flash gone; stopped. */
    private void clearTime() {
        running = false;
        finished = false;
        accumulatedMs = 0;
        runningSince = 0;
        tickCount = 0;
        finishedAt = 0;
        splits.clear();
    }

    /** Start, or pause a running stopwatch. A finished countdown starts over. */
    public void startPause() {
        if (running) {
            pause();
            return;
        }
        if (atEnd()) clearTime();
        start();
    }

    /** A countdown that has nothing left (finished, or its length was shortened below the time counted). */
    private boolean atEnd() {
        return finished || (countdown() && targetMs() > 0 && elapsedMs() >= targetMs());
    }

    /**
     * Back to zero and the laps cleared. A running stopwatch keeps running from zero, except with Auto Start On
     * First Move, where it stops and waits for you to move again.
     */
    public void reset() {
        boolean keepRunning = running && !autoStart.is("On First Move");
        clearTime();
        if (keepRunning) start();
    }

    /** Auto Start On First Move, at the start and stopped: the next movement key starts it. */
    private boolean armed() {
        return autoStart.is("On First Move") && !running && !finished && elapsedMs() == 0;
    }

    private void lap() {
        if (!running) return;
        long at = elapsedMs();
        if (!splits.isEmpty() && at <= splits.getLast()) return; // no time has passed (Game Ticks while paused)
        splits.add(at);
        if (shownLaps.getInt() == 0) {
            int n = splits.size();
            long lap = at - (n > 1 ? splits.get(n - 2) : 0);
            Notifications.push(getName(), "Lap " + n + "  " + time(lap, false), true);
        }
    }

    /** The countdown reached zero: alert, then hold at zero or go round again. */
    private void finish(long target) {
        if (finishAlert.get()) {
            finishedAt = System.currentTimeMillis();
            Notifications.push("Timer finished", "Countdown of " + time(target, false) + " is up", true);
            mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_PLING, 1.0f));
        }
        if (loopCountdown.get()) {
            splits.clear();
            setElapsed(elapsedMs() % target);
        } else {
            running = false;
            finished = true;
            setElapsed(target);
        }
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------

    /**
     * Turning the stopwatch on always starts from zero, even outside a world (a config loaded at startup); with
     * Auto Start On Enable it starts counting once the enable hooks run in a world.
     */
    @Override
    public void setEnabled(boolean enabled) {
        if (enabled && !isEnabled()) {
            clearTime();
            autoStartPending = autoStart.is("On Enable");
            lastWorld = new WeakReference<>(null);
            seenWorld = false;
            lastWorldTime = Long.MIN_VALUE;
        }
        super.setEnabled(enabled);
    }

    @Override
    protected void onEnable() {
        if (autoStartPending) {
            autoStartPending = false;
            start();
        }
        // A key held while turning the stopwatch on doesn't count as a press.
        startPauseWasDown = startPauseKey.isDown();
        resetWasDown = resetKey.isDown();
        lapWasDown = lapKey.isDown();
    }

    @Override
    protected void onDisable() {
        clearTime();
        autoStartPending = false;
    }

    @Override
    public void onTick() {
        ClientWorld w = mc.world;
        if (lastWorld.get() != w) {
            boolean changed = seenWorld;
            lastWorld = new WeakReference<>(w);
            seenWorld = true;
            lastWorldTime = Long.MIN_VALUE;
            if (changed && resetOnWorldChange.get()) reset();
        }

        // Game Ticks: the world's own clock, so lag and the pause menu don't count. Small backward corrections
        // from the server are taken off; jumps (a new world) are ignored.
        long worldTime = w.getTime();
        if (running && lastWorldTime != Long.MIN_VALUE) {
            long d = worldTime - lastWorldTime;
            if (Math.abs(d) <= MAX_TICK_STEP) tickCount = Math.max(0, tickCount + d);
        }
        lastWorldTime = worldTime;
        syncRealClock();

        // KeySetting.isDown is false while any screen is open, so typing in chat never starts or resets it.
        boolean sp = startPauseKey.isDown(), rs = resetKey.isDown(), lp = lapKey.isDown();
        if (sp && !startPauseWasDown) {
            boolean wasRunning = running, fresh = atEnd() || elapsedMs() == 0;
            startPause();
            Notifications.push(getName(), wasRunning ? "Paused at " + mainTime(elapsedMs()) : fresh ? "Started" : "Resumed", true);
        }
        if (rs && !resetWasDown) {
            reset();
            Notifications.push(getName(), "Reset to " + mainTime(0), true);
        }
        if (lp && !lapWasDown) lap();
        startPauseWasDown = sp;
        resetWasDown = rs;
        lapWasDown = lp;

        if (armed() && movementPressed()) start();

        long target = targetMs();
        if (countdown() && target > 0 && running && elapsedMs() >= target) finish(target);
    }

    private boolean movementPressed() {
        var o = mc.options;
        return o.forwardKey.isPressed() || o.backKey.isPressed() || o.leftKey.isPressed() || o.rightKey.isPressed()
                || o.jumpKey.isPressed();
    }

    // ---- text ----------------------------------------------------------------------------------------------

    /**
     * {@code ms} in the chosen Format with Decimals digits: cut down for a stopwatch, rounded up for a countdown
     * (so it only reads zero once it has finished).
     */
    private String time(long ms, boolean roundUp) {
        int d = Math.max(0, Math.min(3, decimals.getInt()));
        long pow = POW10[d];
        long q = roundUp ? Math.ceilDiv(ms * pow, 1000) : ms * pow / 1000;
        long sec = q / pow, frac = q % pow;
        String tail = d == 0 ? "" : String.format(Locale.ROOT, ".%0" + d + "d", frac);
        return switch (format.get()) {
            case "Seconds" -> sec + tail + "s";
            case "M:SS" -> String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60) + tail;
            case "H:MM:SS" -> hms(sec) + tail;
            default -> (sec >= 3600 ? hms(sec) : String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60)) + tail;
        };
    }

    private static String hms(long sec) {
        return String.format(Locale.ROOT, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60);
    }

    /** What the main line shows for {@code elapsed}: the time counted, or for a countdown the time left. */
    private String mainTime(long elapsed) {
        return countdown() ? time(Math.max(0, targetMs() - elapsed), true) : time(elapsed, false);
    }

    /** A lap's difference to the one before, signed: "-0.85", "+1:02.30". */
    private String delta(long ms) {
        String sign = ms < 0 ? "-" : "+";
        long abs = Math.abs(ms);
        int d = Math.max(0, Math.min(3, decimals.getInt()));
        long pow = POW10[d];
        long q = (abs * pow + 500) / 1000; // nearest
        long sec = q / pow, frac = q % pow;
        String tail = d == 0 ? "" : String.format(Locale.ROOT, ".%0" + d + "d", frac);
        return sign + (sec >= 60 ? String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60) : Long.toString(sec)) + tail;
    }

    @Override
    protected List<String> lines(boolean preview) {
        long ms = elapsedMs();
        boolean sample = preview && ms == 0 && !running && splits.isEmpty();
        if (sample) ms = countdown() && targetMs() > 0 ? Math.min(SAMPLE_MS, targetMs() / 3) : SAMPLE_MS;
        String main = mainTime(ms);
        if (!preview && !running && ms > 0 && !finished) main += " (paused)";
        List<String> out = new ArrayList<>(1 + shownLaps.getInt());
        List<Integer> kind = new ArrayList<>(1 + shownLaps.getInt());
        out.add(main);
        kind.add(MAIN);

        int shown = shownLaps.getInt();
        int n = sample ? (shown > 0 ? SAMPLE_SPLITS.length : 0) : splits.size();
        if (shown > 0 && n > 0) {
            long best = Long.MAX_VALUE;
            for (int i = 0; i < n; i++) best = Math.min(best, lapMs(i, sample));
            boolean both = lapTime.is("Both"), split = lapTime.is("Split");
            for (int i = n - 1; i >= Math.max(0, n - shown); i--) {
                long lap = lapMs(i, sample), at = sample ? SAMPLE_SPLITS[i] : splits.get(i);
                StringBuilder row = new StringBuilder("L").append(i + 1).append(' ');
                if (split) row.append(mainTime(at));
                else row.append(time(lap, false));
                if (both) row.append(" / ").append(mainTime(at));
                int k = SAME;
                if (i > 0) {
                    long prev = lapMs(i - 1, sample);
                    k = lap < prev ? FAST : lap > prev ? SLOW : SAME;
                    if (lapDelta.get()) row.append(' ').append(delta(lap - prev));
                }
                if (n > 1 && lap == best) row.append(" *");
                out.add(row.toString());
                kind.add(k);
            }
        }
        int[] arr = new int[kind.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = kind.get(i);
        if (preview) previewKinds = arr; else kinds = arr;
        return out;
    }

    /** Length of lap {@code i} (0 = the first). */
    private long lapMs(int i, boolean sample) {
        if (sample) return SAMPLE_SPLITS[i] - (i > 0 ? SAMPLE_SPLITS[i - 1] : 0);
        return splits.get(i) - (i > 0 ? splits.get(i - 1) : 0);
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        drawingPreview = preview;
        super.render(ctx, left, top, preview);
    }

    /** The time: red flash after a finished countdown, dimmed while stopped. Laps: green faster, red slower. */
    @Override
    protected int lineColor(int index, int base) {
        int[] k = drawingPreview ? previewKinds : kinds;
        int kind = index < k.length ? k[index] : SAME;
        int alpha = base & 0xFF000000;
        return switch (kind) {
            case MAIN -> mainColor(base);
            case FAST -> alpha | (FASTER & 0xFFFFFF);
            case SLOW -> alpha | (SLOWER & 0xFFFFFF);
            default -> base;
        };
    }

    private int mainColor(int base) {
        if (finishedAt != 0) {
            long since = System.currentTimeMillis() - finishedAt;
            if (since >= 0 && since < FLASH_MS) {
                return since / BLINK_MS % 2 == 0 ? (base & 0xFF000000) | (FLASH & 0xFFFFFF) : base;
            }
        }
        if (!drawingPreview && !running) return Theme.lerpColor(base, (base & 0xFF000000) | 0x6E6E78, 0.5f);
        return base;
    }

    /** Start On Enable (a switch before Auto Start existed) off becomes Auto Start: Manual. */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        if (!settings.has(autoStart.getName()) && isFalse(settings, "Start On Enable") && autoStart.isDefault()) autoStart.set("Manual");
    }

    private static boolean isFalse(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            return e != null && e.isJsonPrimitive() && !e.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
