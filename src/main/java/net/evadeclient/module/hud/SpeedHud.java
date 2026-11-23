package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How fast you (or what you ride) are moving: horizontally, in 3D or vertically, averaged over the last few ticks
 * so sprint-jumping reads as a steady number instead of flickering with every hop. Teleports are left out. Can
 * also show the peak speed over a window and a small graph of the last few seconds.
 */
public class SpeedHud extends TextHudModule {
    /** Per-tick samples kept for Smoothing (its maximum). */
    private static final int RING = 20;
    /** Smoothed speeds kept for Peak Window and the graph: 120 s of ticks. */
    private static final int HISTORY = 2400;
    /** A tick moving further than 10 blocks is a teleport, respawn or dimension change, not speed. */
    private static final double MAX_STEP_SQ = 100;
    /** Below this many blocks a tick (squared) you are standing still. */
    private static final double STILL_SQ = 1e-6;
    /** Ticks without moving before Hide When Still hides the speed. */
    private static final int STILL_TICKS = 20;
    /** Sprinting on flat ground, in blocks per second: the HUD editor's sample and the graph's guide line. */
    private static final float SPRINT = 5.612f;
    /** The graph's top is at least this fast, so walking doesn't fill it. */
    private static final float MIN_SCALE = 5.6f;
    private static final String[] FORMATS = {"%.0f", "%.1f", "%.2f", "%.3f"};

    private final ModeSetting unit = add(new ModeSetting("Unit", "m/s", "m/s", "km/h", "mph", "b/t")
            .describe("Metres (blocks) per second, kilometres or miles per hour, or blocks per tick."));
    private final NumberSetting decimals = add(new NumberSetting("Decimals", 2, 0, 3, 1)
            .describe("Digits shown after the decimal point."));
    private final BooleanSetting showUnit = add(new BooleanSetting("Show Unit", true)
            .describe("Write the unit after the number."));
    private final ModeSetting measure = add(new ModeSetting("Measure", "Horizontal", "Horizontal", "3D", "Vertical")
            .describe("Horizontal ignores falling and jumping, 3D counts every direction, Vertical shows climbing (+) and falling (-)."));
    private final NumberSetting smoothing = add(new NumberSetting("Smoothing", 10, 1, 20, 1)
            .describe("How many ticks (20 a second) the speed is averaged over. 1 shows every tick as it is."));
    private final BooleanSetting ignoreTeleports = add(new BooleanSetting("Ignore Teleports", true)
            .describe("Leave out ticks that moved more than 10 blocks (teleports, respawns, dimension changes)."));
    private final BooleanSetting showPeak = add(new BooleanSetting("Show Peak", false)
            .describe("A second line with the highest speed over Peak Window."));
    private final NumberSetting peakWindow = add(new NumberSetting("Peak Window", 10, 0, 120, 5)
            .describe("Seconds the peak looks back over. 0 keeps the highest since the widget was turned on or the world changed."));
    private final BooleanSetting graph = add(new BooleanSetting("Graph", false)
            .describe("A small graph of your speed under the number."));
    private final NumberSetting graphSeconds = add(new NumberSetting("Graph Seconds", 5, 2, 20, 1)
            .describe("How many seconds the graph shows."));
    private final NumberSetting graphHeight = add(new NumberSetting("Graph Height", 14, 8, 40, 2)
            .describe("Height of the graph in pixels."));
    private final ColorSetting graphColor = add(new ColorSetting("Graph Color", 0xFF29D3F0)
            .describe("Colour of the graph."));
    private final BooleanSetting speedColors = add(new BooleanSetting("Speed Colors", false)
            .describe("The number shifts from Text Color towards Fast Color as you speed up."));
    private final NumberSetting fastSpeed = add(new NumberSetting("Fast Speed", 20, 5, 100, 1)
            .describe("Speed in blocks per second at which the number is fully Fast Color."));
    private final ColorSetting fastColor = add(new ColorSetting("Fast Color", 0xFF3BE37A)
            .describe("Colour of the number at Fast Speed."));
    private final BooleanSetting hideWhenStill = add(new BooleanSetting("Hide When Still", false)
            .describe("Hide the widget after a second of standing still."));

    /** Per-tick speeds in blocks per second (signed for Vertical), newest at {@code head - 1}. */
    private final float[] steps = new float[RING];
    private int head, count;
    /** Smoothed speed per tick, newest at {@code histHead - 1}. Shared by the peak and the graph. */
    private final float[] history = new float[HISTORY];
    private int histHead, histCount;
    /** The smoothed speed now, the peak over Peak Window, and the highest since the last clear (by size). */
    private float current, peak, maxSinceClear;
    /** Top of the graph this tick (the fastest it shows, at least {@link #MIN_SCALE}). */
    private float graphScale = MIN_SCALE;
    private int stillTicks;
    /** What moved last tick (you or your vehicle), the world, and the Measure the samples were taken with. */
    private Entity lastMover;
    private WeakReference<ClientWorld> lastWorld = new WeakReference<>(null);
    private String lastMeasure = "";
    private boolean drawingPreview;

    public SpeedHud() { super("Speed", "How fast you're moving, with an optional peak and graph.", 4, 120); }

    @Override
    protected void onEnable() { clear(); }

    private void clear() {
        head = 0;
        count = 0;
        histHead = 0;
        histCount = 0;
        current = 0;
        peak = 0;
        maxSinceClear = 0;
        graphScale = MIN_SCALE;
        stillTicks = 0;
        lastMover = null; // the first tick after a clear only notes what moves
        lastWorld = new WeakReference<>(mc.world);
        lastMeasure = measure.get();
    }

    @Override
    public void onTick() {
        var p = mc.player;
        if (p == null || mc.world == null) return;
        // A new world, or samples taken along another axis: start over.
        if (lastWorld.get() != mc.world || !measure.is(lastMeasure)) clear();
        // The singleplayer pause menu stops the world: nothing moved, and no time passed for the graph.
        if (mc.isPaused()) return;

        Entity e = p.getRootVehicle();
        if (e != lastMover) {
            // Mounting, dismounting or respawning moves you without travelling: skip this tick's step.
            lastMover = e;
            record();
            return;
        }
        double dx = e.getX() - e.lastX, dy = e.getY() - e.lastY, dz = e.getZ() - e.lastZ;
        double sq = dx * dx + dy * dy + dz * dz;
        stillTicks = sq < STILL_SQ ? stillTicks + 1 : 0;
        if (!(ignoreTeleports.get() && sq > MAX_STEP_SQ)) {
            double v = switch (measure.get()) {
                case "Vertical" -> dy;
                case "3D" -> Math.sqrt(sq);
                default -> Math.sqrt(dx * dx + dz * dz);
            };
            steps[head] = (float) (v * 20);
            head = (head + 1) % RING;
            count = Math.min(count + 1, RING);
            current = average();
        }
        record();
    }

    /** Average over the Smoothing window of the samples there are. */
    private float average() {
        int n = Math.min(count, Math.max(1, smoothing.getInt()));
        if (n == 0) return 0;
        float total = 0;
        for (int i = 0; i < n; i++) total += steps[Math.floorMod(head - 1 - i, RING)];
        return total / n;
    }

    /** Adds this tick's smoothed speed to the history, then updates the peak and the graph's scale from it. */
    private void record() {
        history[histHead] = current;
        histHead = (histHead + 1) % HISTORY;
        histCount = Math.min(histCount + 1, HISTORY);
        if (Math.abs(current) > Math.abs(maxSinceClear)) maxSinceClear = current;
        if (showPeak.get()) {
            int window = peakWindow.getInt() * 20;
            peak = window <= 0 ? maxSinceClear : largest(Math.min(window, histCount));
        }
        if (graph.get()) graphScale = Math.max(MIN_SCALE, Math.abs(largest(Math.min(graphTicks(), histCount))));
    }

    /** The speed of largest size among the newest {@code n} history entries (signed, as recorded). */
    private float largest(int n) {
        float best = 0;
        for (int i = 0; i < n; i++) {
            float v = history[Math.floorMod(histHead - 1 - i, HISTORY)];
            if (Math.abs(v) > Math.abs(best)) best = v;
        }
        return best;
    }

    /** Smoothed speed in blocks per second along Measure (negative when falling with Vertical). */
    public double speed() { return current; }

    /** {@code v} blocks per second in the chosen Unit, with Decimals digits (no "-0.00"). */
    private String format(float v) {
        double x = switch (unit.get()) {
            case "km/h" -> v * 3.6;
            case "mph" -> v * 2.23694;
            case "b/t" -> v / 20.0;
            default -> v;
        };
        int d = Math.max(0, Math.min(3, decimals.getInt()));
        if (Math.abs(x) < 0.5 / Math.pow(10, d)) x = 0;
        String s = String.format(Locale.ROOT, FORMATS[d], x);
        return showUnit.get() ? s + " " + unit.get() : s;
    }

    /** The speed on the first line: the HUD editor shows sprinting while you stand still in it. */
    private float shown(boolean preview) {
        return preview && Math.abs(current) < 0.005f ? SPRINT : current;
    }

    @Override
    protected List<String> lines(boolean preview) {
        if (hideWhenStill.get() && stillTicks > STILL_TICKS && !preview) return List.of();
        List<String> out = new ArrayList<>(2);
        out.add(format(shown(preview)));
        if (showPeak.get()) out.add("Peak " + format(preview && peak == 0 ? 7.13f : peak));
        return out;
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        drawingPreview = preview;
        super.render(ctx, left, top, preview);
    }

    /** Speed Colors: the speed line from Text Color towards Fast Color as you approach Fast Speed. */
    @Override
    protected int lineColor(int index, int base) {
        if (index != 0 || !speedColors.get()) return base;
        float t = Math.abs(shown(drawingPreview)) / Math.max(1f, fastSpeed.getFloat());
        return Theme.lerpColor(base, fastColor.get(), Math.max(0f, Math.min(1f, t)));
    }

    // ---- graph ---------------------------------------------------------------------------------------------

    private int graphTicks() { return graphSeconds.getInt() * 20; }

    @Override
    protected int extraHeight() { return graph.get() ? graphHeight.getInt() + 2 : 0; }

    /** One pixel column per tick at least. */
    @Override
    protected int minWidth() { return graph.get() ? graphTicks() + 8 : 0; }

    /**
     * The graph: one column per tick of the last Graph Seconds, oldest on the left, scaled to the fastest speed
     * shown (at least {@link #MIN_SCALE}), with a faint guide at sprinting speed. Vertical draws climbing above a
     * middle line and falling below it. Reads the history only.
     */
    @Override
    protected void drawExtra(DrawContext ctx, int left, int y, int width) {
        if (!graph.get()) return;
        int n = graphTicks();
        int x1 = left + 4, x2 = left + width - 4, span = x2 - x1;
        int h = graphHeight.getInt();
        int top = y + 1, bottom = top + h;
        if (span <= 0 || h <= 0) return;
        boolean sample = drawingPreview && histCount == 0;
        int avail = sample ? n : Math.min(histCount, n);
        boolean vertical = measure.is("Vertical");
        float scale = graphScale;
        if (sample) {
            scale = MIN_SCALE;
            for (int t = 0; t < n; t++) scale = Math.max(scale, Math.abs(sampleAt(t, vertical)));
        }
        int c = graphColor.get();
        int fillC = Theme.alpha(c, 0.35f), faint = Theme.alpha(c, 0.3f);
        ctx.fill(x1, top, x2, bottom, Theme.alpha(c, 0.07f));

        int mid = top + h / 2;
        if (vertical) {
            ctx.fill(x1, mid, x2, mid + 1, faint);
        } else {
            int sy = bottom - Math.round(SPRINT / scale * h);
            if (sy > top) for (int x = x1; x < x2; x += 4) ctx.fill(x, sy, Math.min(x + 2, x2), sy + 1, faint);
        }

        int prevEdge = Integer.MIN_VALUE;
        for (int col = 0; col < span; col++) {
            int t = (int) ((long) col * n / span); // 0 = oldest tick shown, n - 1 = now
            int age = n - 1 - t;
            if (age >= avail) { prevEdge = Integer.MIN_VALUE; continue; }
            float v = sample ? sampleAt(t, vertical) : history[Math.floorMod(histHead - 1 - age, HISTORY)];
            int x = x1 + col;
            int edge;
            if (vertical) {
                int len = Math.min(h / 2, Math.round(Math.abs(v) / scale * (h / 2f)));
                edge = v >= 0 ? mid - len : mid + len;
                if (len > 0) ctx.fill(x, Math.min(edge, mid), x + 1, Math.max(edge, mid), fillC);
            } else {
                int len = Math.min(h, Math.round(Math.abs(v) / scale * h));
                edge = bottom - len;
                if (len > 0) ctx.fill(x, edge, x + 1, bottom, fillC);
            }
            // The line along the top of the area, joined to the previous column so steep changes stay connected.
            int from = prevEdge == Integer.MIN_VALUE ? edge : prevEdge;
            int lo = Math.max(top, Math.min(from, edge)), hi = Math.min(bottom, Math.max(from, edge) + 1);
            if (hi > lo) ctx.fill(x, lo, x + 1, hi, c);
            prevEdge = edge;
        }
    }

    /** A made-up sprint-jumping trace for the HUD editor ({@code t} = 0 oldest). */
    private static float sampleAt(int t, boolean vertical) {
        if (vertical) return (float) (4.2 * Math.sin(t * 0.52));
        return (float) (SPRINT + 1.1 * Math.sin(t * 0.52) + 0.5 * Math.sin(t * 0.09));
    }
}
