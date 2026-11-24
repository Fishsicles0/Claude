package net.evadeclient.util;

import net.evadeclient.EvadeClient;
import net.evadeclient.addon.AddonHudElement;
import net.evadeclient.mixin.DrawContextStateAccessor;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.TextHudModule;
import net.evadeclient.module.hud.CompassHud;
import net.evadeclient.module.hud.FrameTimeHud;
import net.evadeclient.module.hud.KeystrokesHud;
import net.evadeclient.module.hud.MinimapHud;
import net.evadeclient.module.hud.PaperDollHud;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.ItemGuiElementRenderState;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.gui.render.state.TextGuiElementRenderState;
import net.minecraft.client.gui.render.state.special.SpecialGuiElementRenderState;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * HUD Refresh (Performance): Evade's corner widgets are drawn into a recording once and the recording is repeated on
 * the frames in between, so at hundreds of FPS the HUD costs almost nothing.
 *
 * <p>Minecraft 1.21.11 draws the GUI in two steps: during the frame, widgets add small immutable "render states"
 * (a quad, a line of text, an item) to the frame's GUI state; at the end of the frame the GUI renderer turns them all
 * into vertices in one go. The costly part of a widget is the first step (its layout, text and shapes). So a widget
 * is drawn with its elements recorded (see {@code GuiRenderStateRecordMixin}), and until the recording goes stale the
 * same elements are added again in the same order. Text elements even keep their laid-out glyphs. The picture is
 * identical to drawing the widget again: that is the same as an off-screen copy of the HUD, without a texture or an
 * extra pass on the graphics card.
 *
 * <p>A recording is used again only while all of this holds; otherwise the widget is drawn (and recorded) afresh:
 * <ul>
 *   <li>it is younger than the refresh period (1/60 or 1/30 s);</li>
 *   <li>the widget sits at the same spot and scale, on a window of the same size and HUD scale;</li>
 *   <li>for text widgets, the text is unchanged (checked once a tick: a changed number redraws at once);</li>
 *   <li>no screen other than chat is open (settings may be changing) and no resource reload is running.</li>
 * </ul>
 * Widgets that move every frame (minimap, compass, keystrokes, the frame-time graph, the paper doll, Chroma text and
 * other mods' widgets) are always drawn every frame. Render thread only.
 */
public final class HudCache {
    private HudCache() {}

    public static final int ITEM = 0, TEXT = 1, SPECIAL = 2, SIMPLE = 3, UNREPLAYABLE = 4;

    /** Auto: cache only while the game runs faster than this. */
    private static final double AUTO_MIN_FPS = 90;

    private static String mode = "Every Frame";
    private static long periodNanos;
    private static boolean auto;

    /** Follows HUD Refresh ("Auto", "Every Frame", "60 Hz", "30 Hz"); every client tick. */
    public static void configure(String refresh) {
        if (refresh == null) refresh = "Every Frame";
        if (refresh.equals(mode)) return;
        mode = refresh;
        auto = refresh.equals("Auto");
        periodNanos = switch (refresh) {
            case "30 Hz" -> 1_000_000_000L / 30;
            case "60 Hz", "Auto" -> 1_000_000_000L / 60;
            default -> 0;
        };
        clear();
    }

    /** Whether recordings are reused on this frame (Auto checks the frame rate). */
    public static boolean active() {
        if (periodNanos <= 0 || forceOff) return false;
        if (!auto) return true;
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.getCurrentFps() > AUTO_MIN_FPS;
    }

    /** Test hook: draw every widget every frame regardless of the setting. */
    public static boolean forceOff;

    // ------------------------------------------------------------------ recording (GuiRenderState mixin)

    private static List<Object> recording;
    private static boolean recordingBroken;

    public static boolean recording() { return recording != null; }

    public static void record(int kind, Object state) {
        if (kind == UNREPLAYABLE || state == null) { recordingBroken = true; return; }
        recording.add(kind);
        recording.add(state);
    }

    // ------------------------------------------------------------------ per-widget cache

    private static final class Entry {
        Object[] ops = new Object[0];
        long at;
        int x, y, sw, sh, width, height;
        float scale, hudScale;
        Object content;
        long contentTick = -1;
        boolean valid;
    }

    private static final Map<HudElementModule, Entry> entries = new IdentityHashMap<>();
    private static long hits, misses, live;
    private static int lastOps;

    /** Drops every recording (setting changed, resources reloaded). */
    public static void clear() {
        entries.clear();
    }

    /** Widgets that move every frame: always drawn live. */
    private static final Set<Class<?>> ANIMATED = Set.of(MinimapHud.class, CompassHud.class, KeystrokesHud.class,
            FrameTimeHud.class, PaperDollHud.class);

    public static boolean animated(HudElementModule el) {
        if (ANIMATED.contains(el.getClass()) || el instanceof AddonHudElement) return true;
        return el instanceof TextHudModule t && t.chroma.get();
    }

    /**
     * Draws {@code el} through the cache: replays its recording when still good, else runs {@code draw} (which
     * renders the widget) while recording it.
     */
    public static void draw(HudElementModule el, DrawContext ctx, int sw, int sh, Runnable draw) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getOverlay() != null) clear(); // a resource reload replaces the font's glyph textures
        boolean screenOpen = mc.currentScreen != null && !(mc.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen);
        if (!active() || screenOpen || animated(el) || recording != null || !(ctx instanceof DrawContextStateAccessor acc)) {
            live++;
            draw.run();
            return;
        }
        long now = System.nanoTime();
        int x = el.resolveX(sw), y = el.resolveY(sh);
        float scale = el.scale.getFloat(), hudScale = net.evadeclient.hud.Hud.scale();
        Entry e = entries.computeIfAbsent(el, k -> new Entry());
        Object content = e.content;
        if (el instanceof TextHudModule t && e.contentTick != EvadeClient.ticks) {
            // Once a tick, the same rate text widgets rebuild their text at.
            content = t.textForTest();
            e.contentTick = EvadeClient.ticks;
        }
        boolean fresh = e.valid && now - e.at < periodNanos && e.x == x && e.y == y && e.sw == sw && e.sh == sh
                && e.scale == scale && e.hudScale == hudScale && Objects.equals(e.content, content)
                && e.width == el.getWidth() && e.height == el.getHeight();
        if (fresh) {
            replay(acc.evade$state(), e.ops);
            hits++;
            return;
        }
        List<Object> rec = new ArrayList<>(Math.max(16, e.ops.length));
        recording = rec;
        recordingBroken = false;
        try {
            draw.run();
        } finally {
            recording = null;
        }
        e.ops = rec.toArray();
        lastOps = e.ops.length / 2;
        e.valid = !recordingBroken;
        e.at = now;
        e.x = x; e.y = y; e.sw = sw; e.sh = sh; e.scale = scale; e.hudScale = hudScale;
        e.content = content;
        e.width = el.getWidth();
        e.height = el.getHeight();
        misses++;
    }

    private static void replay(GuiRenderState state, Object[] ops) {
        for (int i = 0; i < ops.length; i += 2) {
            Object s = ops[i + 1];
            switch ((Integer) ops[i]) {
                case ITEM -> state.addItem((ItemGuiElementRenderState) s);
                case TEXT -> state.addText((TextGuiElementRenderState) s);
                case SPECIAL -> state.addSpecialElement((SpecialGuiElementRenderState) s);
                default -> state.addSimpleElement((SimpleGuiElementRenderState) s);
            }
        }
    }

    // ------------------------------------------------------------------ status and tests

    /** {hits, misses, live} since the last call (resets). */
    public static long[] takeCounts() {
        long[] c = {hits, misses, live};
        hits = misses = live = 0;
        return c;
    }

    /** Elements in the most recent recording (tests). */
    public static int lastRecordedElements() { return lastOps; }

    private static long statusHits, statusMisses, statusAt;
    private static String statusText = "";

    /** "Reusing 92% of widget draws" for Performance, refreshed once a second. */
    public static String status() {
        if (periodNanos <= 0) return "Off: every widget is drawn every frame";
        if (auto && !active()) return "Waiting: the game runs under " + (int) AUTO_MIN_FPS + " FPS";
        long now = System.currentTimeMillis();
        if (now - statusAt >= 1000) {
            long h = hits - statusHits, m = misses - statusMisses;
            statusHits = hits;
            statusMisses = misses;
            statusAt = now;
            if (h < 0 || m < 0) { h = 0; m = 0; } // counters were taken by a test meanwhile
            statusText = h + m == 0 ? "Nothing cached yet (in a world)"
                    : String.format(Locale.ROOT, "Reusing %d%% of widget draws", Math.round(100.0 * h / (h + m)));
        }
        return statusText;
    }
}
