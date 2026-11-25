package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.FrameStats;
import net.evadeclient.util.Theme;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Frames per second. At the default one-second interval it shows exactly the game's own F3 counter; other intervals
 * count the recorded frames of that span (see {@link FrameStats}). Optional extras: the 1% low and the average
 * frame time over the same span, the frame rate limit in effect (and why the game is holding it lower), colours by
 * frame rate, and showing only while the frame rate is low.
 */
public class FpsHud extends TextHudModule {
    /** The Max Framerate slider's "Unlimited". */
    private static final int UNLIMITED = 260;

    private final ModeSetting format = add(new ModeSetting("Format", "144 FPS", "144 FPS", "FPS: 144", "144")
            .describe("How the frame rate is written: \"144 FPS\", \"FPS: 144\" or just the number."));
    private final BooleanSetting showLow = add(new BooleanSetting("Show 1% Low", false)
            .describe("Also show the 1% low: the frame rate of the slowest 1% of frames, so dips stand out."));
    private final BooleanSetting showFrameTime = add(new BooleanSetting("Show Frame Time", false)
            .describe("Also show the average time per frame, in milliseconds."));
    private final NumberSetting interval = add(new NumberSetting("Update Interval", 1.0, 0.25, 2.0, 0.25)
            .describe("Seconds between updates. 1 matches the game's F3 counter; shorter reacts faster, longer is steadier."));
    private final ModeSetting colorBy = add(new ModeSetting("Color By FPS", "Off", "Off", "Steps", "Gradient")
            .describe("Colour the counter by frame rate: three fixed colours, or a smooth blend between them."));
    private final NumberSetting goodFps = add(new NumberSetting("Good FPS", 60, 20, 360, 5)
            .describe("At or above this frame rate the counter uses Good Color."));
    private final NumberSetting lowFps = add(new NumberSetting("Low FPS", 30, 5, 240, 5)
            .describe("Below this frame rate the counter uses Low Color; in between, Okay Color."));
    private final ColorSetting goodColor = add(new ColorSetting("Good Color", 0xFF3BE37A)
            .describe("Counter colour at or above Good FPS, with Color By FPS on."));
    private final ColorSetting okayColor = add(new ColorSetting("Okay Color", 0xFFFFD21F)
            .describe("Counter colour between Low FPS and Good FPS, with Color By FPS on."));
    private final ColorSetting lowColor = add(new ColorSetting("Low Color", 0xFFE11D2E)
            .describe("Counter colour below Low FPS, with Color By FPS on."));
    private final ModeSetting showCap = add(new ModeSetting("Show Cap", "Off", "Off", "Cap", "Cap + Reason")
            .describe("Show the frame rate limit in effect right now, and why the game is holding it lower "
                    + "(minimized, AFK, unfocused or in a menu)."));
    private final NumberSetting showBelow = add(new NumberSetting("Show Only Below", 0, 0, 240, 10)
            .describe("Only show the counter while the frame rate is under this. 0 always shows it."));
    private final NumberSetting linger = add(new NumberSetting("Linger", 3, 0, 10, 1)
            .describe("Seconds the counter stays up after the frame rate recovers (with Show Only Below)."));

    private int fps;
    private float low, frameMs;
    /** When the shown values next update ({@link Util#getMeasuringTimeMs} clock). */
    private long nextUpdate;
    /** Frames the last update covered, and whether {@link #low}/{@link #frameMs} were computed for them. */
    private int spanFrames = 1;
    private boolean extrasFresh;
    /** The last time the frame rate was under Show Only Below. */
    private long lastLowMs = Long.MIN_VALUE / 4;
    /** Colour by frame rate for this tick (see {@link #lineColor}). */
    private int fpsColor;
    private boolean colored;

    public FpsHud() {
        super("FPS", "Frames per second.", 4, 4);
        setEnabled(true);
    }

    @Override
    protected List<String> lines(boolean preview) {
        long now = Util.getMeasuringTimeMs();
        update(now);
        int threshold = showBelow.getInt();
        if (threshold > 0 && fps < threshold) lastLowMs = now;
        updateColor();
        if (threshold > 0 && !preview && now - lastLowMs > linger.get() * 1000) return new ArrayList<>();

        StringBuilder line = new StringBuilder(switch (format.get()) {
            case "FPS: 144" -> "FPS: " + fps;
            case "144" -> Integer.toString(fps);
            default -> fps + " FPS";
        });
        if (showLow.get()) line.append(" · 1% ").append(Math.round(low));
        if (showFrameTime.get()) line.append(" · ").append(String.format(Locale.ROOT, "%.1f ms", frameMs));
        if (!showCap.is("Off")) line.append(" · ").append(cap());
        List<String> out = new ArrayList<>(1);
        out.add(line.toString());
        return out;
    }

    /** Colour by frame rate (worked out once per tick in {@link #lines}); Chroma still wins. */
    @Override
    protected int lineColor(int index, int base) {
        return colored ? fpsColor : base;
    }

    /** Update the frame rate (and the extras) when the interval rolls over. */
    private void update(long now) {
        long ms = Math.round(interval.get() * 1000);
        boolean f3 = ms == 1000;
        // The game's counter only changes once a second, so reading it every tick keeps us in step with F3.
        if (f3) fps = mc.getCurrentFps();
        // A shorter interval picked in the menu applies at once instead of after the old one runs out.
        if (now >= nextUpdate || nextUpdate - now > ms) {
            nextUpdate = now + ms;
            if (f3) {
                spanFrames = Math.max(1, mc.getCurrentFps());
            } else {
                float[] span = FrameStats.framesWithin(ms);
                spanFrames = Math.max(1, (int) span[0]);
                fps = span[1] > 0 ? Math.round(span[0] * 1000f / span[1]) : mc.getCurrentFps();
            }
            extrasFresh = false;
        }
        boolean extras = showLow.get() || showFrameTime.get();
        if (extras && !extrasFresh) {
            float[] s = FrameStats.summary(spanFrames);
            low = s[1];
            frameMs = s[4] > 0 ? s[4] : fps > 0 ? 1000f / fps : 0;
            extrasFresh = true;
        }
    }

    private void updateColor() {
        colored = !colorBy.is("Off");
        if (!colored) return;
        float good = goodFps.getFloat(), bad = Math.min(lowFps.getFloat(), good);
        int g = goodColor.get(), o = okayColor.get(), l = lowColor.get();
        if (colorBy.is("Steps")) {
            fpsColor = fps >= good ? g : fps >= bad ? o : l;
        } else if (fps >= good) {
            fpsColor = g;
        } else if (fps <= bad) {
            fpsColor = l;
        } else {
            // Low -> Okay over the lower half of the range, Okay -> Good over the upper half.
            float mid = (bad + good) / 2f;
            fpsColor = fps < mid ? Theme.lerpColor(l, o, (fps - bad) / (mid - bad))
                    : Theme.lerpColor(o, g, (fps - mid) / (good - mid));
        }
    }

    /** "cap 144", "cap inf", or with Cap + Reason and a lowered limit, "cap 30 (AFK)". */
    private String cap() {
        var limiter = mc.getInactivityFpsLimiter();
        int eff = limiter.update();
        int base = mc.options.getMaxFps().getValue();
        String value;
        if (mc.options.getEnableVsync().getValue()) {
            int hz = FrameStats.refreshRate();
            value = Integer.toString(Math.min(eff >= UNLIMITED ? hz : eff, hz));
        } else {
            value = eff >= UNLIMITED ? "inf" : Integer.toString(eff);
        }
        if (!showCap.is("Cap + Reason") || eff >= base) return "cap " + value;
        String reason = switch (limiter.getLimitReason()) {
            case WINDOW_ICONIFIED -> "Minimized";
            case SHORT_AFK, LONG_AFK -> "AFK";
            case OUT_OF_LEVEL_MENU -> "Menu";
            // The game itself isn't limiting: Performance's idle caps are (unfocused window or no input for a while).
            case NONE -> mc.isWindowFocused() ? "AFK" : "Unfocused";
        };
        return "cap " + value + " (" + reason + ")";
    }
}
