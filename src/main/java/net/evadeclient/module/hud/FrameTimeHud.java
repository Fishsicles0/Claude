package net.evadeclient.module.hud;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.FrameStats;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * Frame time graph: one bar per frame, coloured against a target frame rate (good within its frame budget, slow
 * up to twice the budget, a stutter beyond), with a header of stats over a real time window (average FPS, 1% and
 * 0.1% lows, worst frame, frame time and a stutter count). Stutters show up as spikes; a key freezes the graph so
 * a hitch can be read before it scrolls away.
 *
 * <p>Built to cost almost nothing: the bars are painted into a small texture at most once per tick (20/s) and
 * drawn with a single call each frame, and the stats are recomputed four times a second.
 */
public class FrameTimeHud extends HudElementModule {
    private static final Identifier TEX_ID = Identifier.of("evade", "frame_time_graph");
    /** Ticks between header refreshes (four a second). */
    private static final int HEADER_TICKS = 5;
    /** The faint target line. */
    private static final int GUIDE = 0x50FFFFFF;
    /** What the HUD editor shows before any frame has been recorded, in {@link FrameStats#summary} order. */
    private static final float[] SAMPLE = {144, 98, 12.4f, 71, 6.9f, 9.8f, 6.8f};

    public final NumberSetting bars = add(new NumberSetting("Frames Shown", 120, 60, 240, 30)
            .describe("How many recent frames the graph shows, one bar each."));

    // ---- header
    public final ModeSetting header = add(new ModeSetting("Header", "Top", "Top", "Bottom", "Off")
            .describe("Where the stats line sits: above the graph, below it, or hidden."));
    public final ModeSetting statsWindow = add(new ModeSetting("Stats Window", "10 s", "1 s", "5 s", "10 s")
            .describe("How many seconds of recent frames the header's stats cover."));
    public final BooleanSetting showAverage = add(new BooleanSetting("Show Average", true)
            .describe("Average FPS over the stats window."));
    public final BooleanSetting showLow = add(new BooleanSetting("Show 1% Low", true)
            .describe("The frame rate of the slowest 1% of frames: how low the dips go."));
    public final BooleanSetting showLowest = add(new BooleanSetting("Show 0.1% Low", false)
            .describe("The frame rate of the slowest 0.1% of frames: the rare worst hitches."));
    public final BooleanSetting showWorst = add(new BooleanSetting("Show Worst Frame", true)
            .describe("The single slowest frame in the stats window, in milliseconds."));
    public final BooleanSetting showFrameTime = add(new BooleanSetting("Show Frame Time", false)
            .describe("The average time per frame, in milliseconds."));
    public final ColorSetting headerColor = add(new ColorSetting("Header Color", 0xFFFFFFFF)
            .describe("Colour of the stats line above or below the graph."));

    // ---- graph
    public final NumberSetting graphHeight = add(new NumberSetting("Graph Height", 32, 16, 64, 4)
            .describe("Height of the graph in pixels."));
    public final NumberSetting barWidth = add(new NumberSetting("Bar Width", 1, 1, 3, 1)
            .describe("Pixels per frame. Wider bars make a wider graph."));
    public final ModeSetting graphRange = add(new ModeSetting("Graph Range", "50 ms", "Auto", "25 ms", "50 ms", "100 ms", "200 ms")
            .describe("The frame time that fills the graph's height. Auto fits it to your recent frames."));
    public final ModeSetting graphStyle = add(new ModeSetting("Graph Style", "Bars", "Bars", "Line", "Area")
            .describe("Draw each frame as a bar, as a connected line, or as a filled area."));

    // ---- target and colours
    public final ModeSetting targetFps = add(new ModeSetting("Target FPS", "60", "30", "60", "120", "144", "165", "240", "Monitor", "FPS Limit")
            .describe("Frames within this frame rate's budget are good, up to twice the budget slow, beyond that a stutter. "
                    + "Monitor uses your screen's refresh rate, FPS Limit the game's Max Framerate."));
    public final BooleanSetting targetLine = add(new BooleanSetting("Target Line", true)
            .describe("A faint line across the graph at the target frame time."));
    public final ColorSetting goodColor = add(new ColorSetting("Good Color", 0xFF22C55E)
            .describe("Colour of frames that made the Target FPS budget."));
    public final ColorSetting slowColor = add(new ColorSetting("Slow Color", 0xFFEAB308)
            .describe("Colour of frames that took up to twice the target budget."));
    public final ColorSetting stutterColor = add(new ColorSetting("Stutter Color", 0xFFE11D2E)
            .describe("Colour of frames that took more than twice the target budget: visible stutters."));

    // ---- freeze and stutters
    public final KeySetting freezeKey = add(new KeySetting("Freeze Key", GLFW.GLFW_KEY_UNKNOWN)
            .describe("Freezes the graph and its stats so you can read them; press again to go live."));
    public final BooleanSetting markStutters = add(new BooleanSetting("Mark Stutters", false)
            .describe("Tint the graph behind frames that took far longer than usual, and count them in the header."));
    public final NumberSetting stutterFactor = add(new NumberSetting("Stutter Factor", 2.5, 1.5, 5.0, 0.5)
            .describe("A frame counts as a stutter when it takes this many times the median frame time."));

    /** Stats over the window, in {@link FrameStats#summary} order; kept while frozen. */
    private float[] stats = new float[7];
    /** Frames that took longer than the stutter limit in the stats window. */
    private int stutters;
    private String headerString = "";
    private Text headerText = Text.empty();
    private int headerWidth;
    private long headerTick = Long.MIN_VALUE, paintTick = Long.MIN_VALUE;
    /** Fingerprint of everything that changes the painted graph (see {@link #paintKey}). */
    private int paintKey;
    private NativeImageBackedTexture texture;
    private int texW, texH;
    /** The frame time (ms) that fills the graph; eased toward its target in Auto. */
    private float range = -1;

    private boolean frozen, freezeWasDown, repaint;
    /** The newest frames when the graph was frozen (newest first), so it can be repainted after a setting change. */
    private final float[] frozenFrames = new float[(int) bars.getMax()];
    private int frozenCount;

    public FrameTimeHud() {
        super("Frame Time Graph", "Live frame times with average FPS, 1% low and worst frame.", -1, 130);
    }

    @Override
    protected void onDisable() {
        if (texture != null) { mc.getTextureManager().destroyTexture(TEX_ID); texture = null; }
        frozen = false;
        freezeWasDown = false;
        headerTick = paintTick = Long.MIN_VALUE;
        range = -1;
    }

    @Override
    public void onTick() {
        // Toggles on the press, not while held (KeySetting.isDown is false while a screen is open).
        boolean down = freezeKey.isDown();
        if (down && !freezeWasDown) setFrozen(!frozen);
        freezeWasDown = down;
    }

    /** Whether the graph is frozen by Freeze Key. */
    public boolean isFrozen() { return frozen; }

    /** Freeze or unfreeze the graph, as Freeze Key does. */
    public void toggleFrozen() { setFrozen(!frozen); }

    /** Test hook: the header text as last built (rebuilt four times a second while the element is drawn). */
    public String headerForTest() { return headerString; }

    private void setFrozen(boolean freeze) {
        if (freeze) {
            refreshStats();
            frozenCount = Math.min(frozenFrames.length, FrameStats.count());
            for (int i = 0; i < frozenCount; i++) frozenFrames[i] = FrameStats.recent(i);
        }
        frozen = freeze;
        // Rebuild the header (FROZEN on or off) and repaint the graph from exactly the frames kept.
        headerTick = paintTick = Long.MIN_VALUE;
        repaint = true;
        // Without a header nothing on the graph says it is frozen, so say it once.
        if (header.is("Off")) Notifications.push(getName(), freeze ? "Frozen" : "Live", freeze);
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        var tr = mc.textRenderer;
        int n = bars.getInt(), bw = barWidth.getInt(), h = graphHeight.getInt();
        long tick = EvadeClient.ticks;
        if (tick != paintTick) {
            paintTick = tick;
            if (headerTick == Long.MIN_VALUE || tick - headerTick >= HEADER_TICKS || tick < headerTick) {
                headerTick = tick;
                if (!frozen) refreshStats();
                rebuildHeader(preview);
            }
            easeRange();
            // Frozen: only repaint when a setting changes how the (frozen) frames are drawn.
            if (!frozen || repaint || texture == null || paintKey(n, bw, h) != paintKey) paintGraph(n, bw, h);
        } else if (texture == null || texW != n * bw || texH != h) {
            paintGraph(n, bw, h);
        }

        boolean head = !header.is("Off") && !headerString.isEmpty();
        boolean below = header.is("Bottom");
        int gw = n * bw;
        width = Math.max(gw + 8, head ? headerWidth + 8 : 0);
        height = h + (head ? 18 : 8);
        if (background.get()) Theme.hudBox(ctx, left, top, width, height);
        int gx = left + (width - gw) / 2;
        int gy = top + (head && !below ? 14 : 4);
        if (head) ctx.drawText(tr, headerText, left + 4, below ? gy + h + 3 : top + 3, headerColor.get(), true);
        // One draw call per frame (measured ~4x cheaper than drawing a bar per frame).
        if (texture != null) ctx.drawTexture(RenderPipelines.GUI_TEXTURED, TEX_ID, gx, gy, 0, 0, texW, texH, texW, texH);
    }

    private float windowMs() {
        return switch (statsWindow.get()) {
            case "1 s" -> 1000f;
            case "5 s" -> 5000f;
            default -> 10_000f;
        };
    }

    /** Recompute the stats over the last Stats Window of frames. */
    private void refreshStats() {
        int frames = (int) FrameStats.framesWithin(windowMs())[0];
        stats = FrameStats.summary(frames);
        stutters = 0;
        if (markStutters.get() && stats[6] > 0) {
            float limit = stats[6] * stutterFactor.getFloat();
            for (int i = 0; i < frames; i++) if (FrameStats.recent(i) > limit) stutters++;
        }
    }

    private void rebuildHeader(boolean preview) {
        boolean sample = preview && FrameStats.count() == 0;
        float[] s = sample ? SAMPLE : stats;
        int st = sample ? 2 : stutters;
        StringBuilder b = new StringBuilder();
        if (frozen) join(b, "FROZEN");
        if (showAverage.get()) join(b, "avg " + Math.round(s[0]) + " FPS");
        if (showLow.get()) join(b, "1% low " + Math.round(s[1]));
        if (showLowest.get()) join(b, "0.1% low " + Math.round(s[3]));
        if (showWorst.get()) join(b, String.format(Locale.ROOT, "max %.1fms", s[2]));
        if (showFrameTime.get()) join(b, String.format(Locale.ROOT, "frame %.1fms", s[4]));
        if (markStutters.get()) join(b, st + (st == 1 ? " stutter" : " stutters"));
        headerString = b.toString();
        headerText = styled(headerString);
        headerWidth = headerString.isEmpty() ? 0 : mc.textRenderer.getWidth(headerText);
    }

    private static void join(StringBuilder b, String part) {
        if (!b.isEmpty()) b.append("  ");
        b.append(part);
    }

    // ---------------------------------------------------------------- target and range

    /** The frame rate the colours and the target line are measured against. */
    private float targetFps() {
        return switch (targetFps.get()) {
            case "Monitor" -> FrameStats.refreshRate();
            case "FPS Limit" -> {
                int limit = mc.options.getMaxFps().getValue();
                int hz = FrameStats.refreshRate();
                // 260 is "Unlimited": nothing past the screen's refresh rate is ever seen.
                if (limit >= 260) yield hz;
                yield mc.options.getEnableVsync().getValue() ? Math.min(limit, hz) : limit;
            }
            default -> {
                try {
                    yield Integer.parseInt(targetFps.get());
                } catch (NumberFormatException e) {
                    yield 60;
                }
            }
        };
    }

    /** The target's frame budget in ms (16.7 ms at 60 FPS). */
    private float budgetMs() {
        return 1000f / Math.max(1f, targetFps());
    }

    /** Move the graph range one tick toward its target: fixed ranges jump, Auto grows fast and shrinks slowly. */
    private void easeRange() {
        float target = switch (graphRange.get()) {
            case "25 ms" -> 25f;
            case "100 ms" -> 100f;
            case "200 ms" -> 200f;
            case "Auto" -> Math.max(10f, Math.min(200f, Math.max(stats[5] * 1.25f, 2 * budgetMs())));
            default -> 50f;
        };
        if (!graphRange.is("Auto") || range <= 0) { range = target; return; }
        range += (target - range) * (target > range ? 0.35f : 0.08f);
        if (Math.abs(target - range) < 0.05f) range = target;
    }

    // ---------------------------------------------------------------- painting

    /** Fingerprint of everything that changes the painted pixels apart from the frames themselves. */
    private int paintKey(int n, int bw, int h) {
        int k = n;
        k = 31 * k + bw;
        k = 31 * k + h;
        k = 31 * k + graphStyle.get().hashCode();
        k = 31 * k + Math.round(range * 10);
        k = 31 * k + Math.round(budgetMs() * 100);
        k = 31 * k + (targetLine.get() ? 1 : 0);
        k = 31 * k + goodColor.get();
        k = 31 * k + slowColor.get();
        k = 31 * k + stutterColor.get();
        k = 31 * k + (markStutters.get() ? 1 : 0);
        k = 31 * k + Math.round(stutterFactor.getFloat() * 10);
        k = 31 * k + Math.round(stats[6] * 100);
        return k;
    }

    /** Paint the last {@code n} frame times into the graph texture (newest on the right), {@code bw} pixels each. */
    private void paintGraph(int n, int bw, int h) {
        int w = n * bw;
        if (texture == null || texW != w || texH != h) {
            if (texture != null) mc.getTextureManager().destroyTexture(TEX_ID);
            texture = new NativeImageBackedTexture(() -> "evade_frame_time_graph", w, h, false);
            mc.getTextureManager().registerTexture(TEX_ID, texture);
            texW = w;
            texH = h;
        }
        paintKey = paintKey(n, bw, h);
        repaint = false;
        NativeImage img = texture.getImage();
        if (img == null) return;

        float scale = h / Math.max(1f, range);
        float budget = budgetMs(), slowMs = 2 * budget;
        int guideRow = -1;
        if (targetLine.get()) {
            int row = h - Math.round(budget * scale);
            if (row >= 0 && row < h) guideRow = row;
        }
        int good = goodColor.get(), slow = slowColor.get(), bad = stutterColor.get();
        boolean marks = markStutters.get() && stats[6] > 0;
        float stutterMs = stats[6] * stutterFactor.getFloat();
        int markColor = Theme.alpha(bad, 0.25f);
        boolean line = graphStyle.is("Line"), area = graphStyle.is("Area");
        int shown = Math.min(n, frozen ? frozenCount : FrameStats.count());

        int prevTop = -1;
        for (int col = 0; col < n; col++) {
            int i = n - 1 - col; // frame index back from the newest
            boolean has = i < shown;
            int color = 0, fill = 0, topRow = h;
            boolean marked = false;
            if (has) {
                float ms = frozen ? frozenFrames[i] : FrameStats.recent(i);
                topRow = h - Math.max(1, Math.min(h, Math.round(ms * scale)));
                color = ms <= budget ? good : ms <= slowMs ? slow : bad;
                fill = line ? 0 : area ? Theme.alpha(color, 0.35f) : color;
                marked = marks && ms > stutterMs;
            }
            int back = marked ? markColor : 0;
            for (int sub = 0; sub < bw; sub++) {
                int x = col * bw + sub;
                // The solid top of the bar; Line and Area also join it to the previous frame's top.
                int solidFrom = topRow, solidTo = topRow;
                if (has && (line || area) && sub == 0 && prevTop >= 0) {
                    solidFrom = Math.min(prevTop, topRow);
                    solidTo = Math.max(prevTop, topRow);
                }
                for (int y = 0; y < h; y++) {
                    int argb;
                    if (has && y >= solidFrom && y <= solidTo) argb = color;
                    else if (has && y > topRow && fill != 0) argb = fill;
                    else argb = y == guideRow ? GUIDE : back;
                    img.setColorArgb(x, y, argb);
                }
            }
            prevTop = has ? topRow : -1;
        }
        texture.upload();
    }
}
