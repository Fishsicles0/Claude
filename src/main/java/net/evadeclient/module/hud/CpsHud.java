package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.ClickTracker;
import net.minecraft.client.world.ClientWorld;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Clicks per second, counted from every press GLFW reports (see {@link ClickTracker}) and averaged over an adjustable
 * sample window, with optional peaks (last 10 seconds or this session) and a stacked one-line-per-button layout.
 */
public class CpsHud extends TextHudModule {
    /** Ticks of history for the "Last 10s" peak. */
    private static final int HISTORY = 200;

    private final BooleanSetting right = add(new BooleanSetting("Show Right", true)
            .describe("Also counts right clicks per second next to left clicks."));
    private final ModeSetting label = add(new ModeSetting("Label", "Suffix", "Suffix", "Prefix", "Per Button", "None")
            .describe("Where the label goes: \"12 | 9 CPS\", \"CPS: 12 | 9\", \"L 12  R 9\" or just \"12 | 9\"."));
    private final ModeSetting layout = add(new ModeSetting("Layout", "Inline", "Inline", "Stacked")
            .describe("Both buttons on one line, or one line per button (\"LMB 12 CPS\", \"RMB 9 CPS\")."));
    private final NumberSetting window = add(new NumberSetting("Sample Window", 1.0, 0.5, 3.0, 0.25)
            .describe("Seconds of clicks averaged into the number. Longer is steadier, shorter reacts faster."));
    private final NumberSetting decimals = add(new NumberSetting("Decimals", 0, 0, 1, 1)
            .describe("Show the clicks per second with one decimal place."));
    private final ModeSetting peak = add(new ModeSetting("Peak", "Off", "Off", "Last 10s", "Session")
            .describe("Also show your highest CPS of the last 10 seconds, or since you joined this world."));
    private final BooleanSetting hideIdle = add(new BooleanSetting("Hide When Idle", false)
            .describe("Hide the counter when you haven't clicked for a while."));
    private final NumberSetting idleDelay = add(new NumberSetting("Idle Delay", 3, 1, 10, 1)
            .describe("Seconds without a click before Hide When Idle hides the counter."));

    /** Per-tick CPS of each button over the last {@link #HISTORY} ticks (a ring), for the "Last 10s" peak. */
    private final float[] historyLeft = new float[HISTORY], historyRight = new float[HISTORY];
    private int historyPos;
    private float sessionLeft, sessionRight;
    private ClientWorld lastWorld;

    public CpsHud() {
        super("CPS", "Clicks per second.", 4, 20);
        setEnabled(true);
    }

    @Override
    protected void onEnable() {
        resetPeaks();
        lastWorld = mc.world;
    }

    @Override
    public void onTick() {
        if (mc.world != lastWorld) {
            resetPeaks();
            lastWorld = mc.world;
        }
        double w = windowMs();
        float l = ClickTracker.cps(true, w), r = ClickTracker.cps(false, w);
        historyLeft[historyPos] = l;
        historyRight[historyPos] = r;
        historyPos = (historyPos + 1) % HISTORY;
        sessionLeft = Math.max(sessionLeft, l);
        sessionRight = Math.max(sessionRight, r);
    }

    private void resetPeaks() {
        java.util.Arrays.fill(historyLeft, 0f);
        java.util.Arrays.fill(historyRight, 0f);
        historyPos = 0;
        sessionLeft = 0;
        sessionRight = 0;
    }

    private double windowMs() {
        return window.get() * 1000.0;
    }

    private static float max(float[] values) {
        float m = 0;
        for (float v : values) m = Math.max(m, v);
        return m;
    }

    @Override
    protected List<String> lines(boolean preview) {
        if (!preview && hideIdle.get() && ClickTracker.msSinceLastPress() > idleDelay.get() * 1000) return new ArrayList<>();

        double w = windowMs();
        float l = ClickTracker.cps(true, w), r = ClickTracker.cps(false, w);
        boolean showPeak = !peak.is("Off");
        float pl = 0, pr = 0;
        if (peak.is("Session")) {
            pl = Math.max(sessionLeft, l);
            pr = Math.max(sessionRight, r);
        } else if (showPeak) {
            pl = Math.max(max(historyLeft), l);
            pr = Math.max(max(historyRight), r);
        }
        if (preview && l == 0 && r == 0) {
            // The HUD editor shows what the counter looks like while clicking.
            l = 11;
            r = 7;
            if (pl == 0 && pr == 0) {
                pl = 15;
                pr = 11;
            }
        }

        boolean both = right.get();
        String ls = format(l), rs = format(r);
        String peakText = both ? format(pl) + " | " + format(pr) : format(pl);
        List<String> out = new ArrayList<>(3);
        if (layout.is("Stacked")) {
            out.add(stackedLine("LMB", "L", ls));
            if (both) out.add(stackedLine("RMB", "R", rs));
            if (showPeak) out.add("Max " + peakText);
        } else {
            String line = switch (label.get()) {
                case "Prefix" -> "CPS: " + (both ? ls + " | " + rs : ls);
                case "Per Button" -> both ? "L " + ls + "  R " + rs : "L " + ls;
                case "None" -> both ? ls + " | " + rs : ls;
                default -> (both ? ls + " | " + rs : ls) + " CPS";
            };
            if (showPeak) line += "  max " + peakText;
            out.add(line);
        }
        return out;
    }

    /** One button's line in the Stacked layout, e.g. "LMB 12 CPS", "LMB CPS: 12", "L 12" or "LMB 12". */
    private String stackedLine(String button, String shortName, String value) {
        return switch (label.get()) {
            case "Prefix" -> button + " CPS: " + value;
            case "Per Button" -> shortName + " " + value;
            case "None" -> button + " " + value;
            default -> button + " " + value + " CPS";
        };
    }

    private String format(float cps) {
        if (decimals.getInt() >= 1) return String.format(Locale.ROOT, "%.1f", cps);
        return Integer.toString(Math.round(cps));
    }
}
