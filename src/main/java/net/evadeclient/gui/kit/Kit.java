package net.evadeclient.gui.kit;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.Motion;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The kit's per-screen state: one {@code Kit} per screen, owned by it. Components are immediate-mode static helpers
 * (see {@code gui/kit/README.md}) that draw themselves and register a {@link Hit} here; the screen forwards its input
 * events to the kit, which runs the right action. The kit also owns springs per id, keyboard focus (Tab, Shift+Tab,
 * Enter / Space, arrow keys to the focused control), text-field state, deferred overlays (dropdown menus, colour
 * popovers, tooltips) and the per-frame glow budget.
 *
 * <p>Frame: {@code kit.begin(ctx, mx, my)} → draw components → {@code kit.end()}. Input:
 * {@code mouseClicked / mouseReleased / mouseDragged / mouseScrolled / keyPressed / charTyped} return true when the
 * kit used the event. Hit testing uses the hits of the frame just drawn, topmost (last registered) first, so a
 * popover drawn in {@code end()} covers what is under it. Render thread only.
 */
public final class Kit {
    /** Component states (also usable with {@link #force} to pin a state for screenshots). */
    public static final int HOVER = 1, PRESSED = 2, FOCUSED = 4, DISABLED = 8, SELECTED = 16;

    /** A clickable region. {@code focusable} hits join the Tab order. */
    public record Hit(String id, int x1, int y1, int x2, int y2, ClickAction action, boolean focusable) {
        boolean contains(double x, double y) { return x >= x1 && x < x2 && y >= y1 && y < y2; }
    }

    /** What a click does: the mouse button and the click position. */
    @FunctionalInterface
    public interface ClickAction { void click(int button, double x, double y); }

    /** Follows the mouse while a control is dragged (sliders, colour areas, scrollbars). */
    @FunctionalInterface
    public interface DragAction { void drag(double x, double y); }

    /** Keys sent to the focused control: return true when used. */
    @FunctionalInterface
    public interface KeyAction { boolean key(int key, int modifiers); }

    /** Text-field state: the text and the caret. */
    public static final class TextState {
        public final StringBuilder text = new StringBuilder();
        public int caret;
        public long lastEdit;
    }

    private final Map<String, Motion.Spring> springs = new HashMap<>();
    private final Map<String, Object> memory = new HashMap<>();
    private final Map<String, TextState> texts = new HashMap<>();
    /** This frame's hits while it is drawn; after {@link #end()} the frame's complete list. */
    private List<Hit> hits = new ArrayList<>(), lastHits = new ArrayList<>();
    private boolean frameOpen;
    private final Map<String, KeyAction> keys = new HashMap<>();
    private final Map<String, DragAction> drags = new HashMap<>();
    private final List<Runnable> overlays = new ArrayList<>();
    private String hovered, focused, pressed, dragging, openPopup;
    private boolean focusVisible;
    private int force, lastForced;
    private long lastNanos;
    private int glows;

    // tooltip candidate of this frame and the one showing
    private String tipId, tipText, tipSub, shownTip;
    private int tx1, ty1, tx2, ty2;
    private long tipSince;

    public DrawContext ctx;
    public TextRenderer tr;
    public int mx, my;
    /** Seconds since the last frame (at most 0.1). */
    public float dt;
    /** Smooth font (Client Settings) and not the Classic theme. */
    public boolean smooth = true;
    /** Milliseconds now (tests can pin it for stable screenshots). */
    public long now;
    /** When set, tooltips show without their 450 ms delay (screenshots). */
    public boolean instantTooltips;

    // ------------------------------------------------------------------ frame

    /** Start a frame: refresh the tokens, pick the hovered hit from the last frame, clear this frame's registry. */
    public void begin(DrawContext ctx, int mx, int my) {
        Tokens.refresh();
        this.ctx = ctx;
        this.tr = MinecraftClient.getInstance().textRenderer;
        this.mx = mx;
        this.my = my;
        long t = System.nanoTime();
        dt = lastNanos == 0 ? 0f : Math.min(0.1f, (t - lastNanos) / 1e9f);
        lastNanos = t;
        now = System.currentTimeMillis();
        ClientSettings cs = EvadeClient.modules == null ? null : EvadeClient.modules.get(ClientSettings.class);
        smooth = (cs == null || cs.smoothFont.get()) && !Theme.square;
        hovered = topHit(hits, mx, my); // the frame just drawn decides what is on top
        List<Hit> swap = lastHits;
        lastHits = hits;
        hits = swap;
        hits.clear();
        frameOpen = true;
        keys.clear();
        overlays.clear();
        tipId = null;
        glows = 0;
        force = 0;
    }

    /** End the frame: draw the deferred overlays (popovers, dropdown menus) and the tooltip, on top of everything. */
    public void end() {
        for (int i = 0; i < overlays.size(); i++) overlays.get(i).run(); // overlays may add overlays
        overlays.clear();
        frameOpen = false;
        if (tipId != null) {
            if (!tipId.equals(shownTip)) { shownTip = tipId; tipSince = now; }
            long delay = instantTooltips ? 0 : 450;
            float a = Motion.clamp01((now - tipSince - delay) / (float) Math.max(1, Motion.duration(Motion.DUR_QUICK)));
            if (Motion.level() == Motion.Level.OFF && now - tipSince >= delay) a = 1f;
            if (a > 0) Tooltip.draw(this, tipText, tipSub, tx1, ty1, tx2, ty2, a);
        } else {
            shownTip = null;
        }
        // Hits registered after this frame's topHit: the next begin() resolves hover against them.
    }

    // ------------------------------------------------------------------ registry

    /** Register a clickable, focusable region. Later registrations are on top. */
    public void hit(String id, int x1, int y1, int x2, int y2, Runnable onClick) {
        hits.add(new Hit(id, x1, y1, x2, y2, onClick == null ? null : (b, x, y) -> { if (b == 0) onClick.run(); }, true));
    }

    /** Register a region with a full click handler (any button, the click position). */
    public void hit(String id, int x1, int y1, int x2, int y2, ClickAction action, boolean focusable) {
        hits.add(new Hit(id, x1, y1, x2, y2, action, focusable));
    }

    /** A region that swallows clicks and hover beneath it (a modal's scrim, an open popover's backdrop). */
    public void barrier(String id, int x1, int y1, int x2, int y2, Runnable onClick) {
        hits.add(new Hit(id, x1, y1, x2, y2, (b, x, y) -> { if (onClick != null) onClick.run(); }, false));
    }

    /** Follow the mouse while {@code id} is pressed (registered with {@link #hit}). */
    public void onDrag(String id, DragAction action) { drags.put(id, action); }

    /** Keys for {@code id} while it is focused. */
    public void onKey(String id, KeyAction action) { keys.put(id, action); }

    /** Draw {@code r} after every component this frame (popovers, menus). */
    public void overlay(Runnable r) { overlays.add(r); }

    /** Show {@code text} (and an optional second paragraph) as the kit tooltip while (x1, y1)-(x2, y2) is hovered. */
    public void tooltip(String id, String text, String sub, int x1, int y1, int x2, int y2) {
        if (text == null || text.isEmpty()) return;
        if (!id.equals(hovered) && (lastForced & HOVER) == 0) return;
        tipId = id; tipText = text; tipSub = sub;
        tx1 = x1; ty1 = y1; tx2 = x2; ty2 = y2;
    }

    /** Pin states (bits) for the next component only: the gallery shows every state at once this way. */
    public void force(int bits) { force = bits; }

    /** The state bits of {@code id} at (x1, y1)-(x2, y2) this frame (hover from the topmost hit, press, focus,
     *  disabled), plus any {@link #force forced} bits, which are consumed. */
    public int state(String id, int x1, int y1, int x2, int y2, boolean enabled) {
        int s = force;
        lastForced = force;
        force = 0;
        if (!enabled) return s | DISABLED;
        boolean over = id.equals(hovered);
        if (over) s |= HOVER;
        if (over && id.equals(pressed)) s |= PRESSED;
        if (id.equals(dragging)) s |= PRESSED | HOVER;
        if (focusVisible && id.equals(focused)) s |= FOCUSED;
        return s;
    }

    // ------------------------------------------------------------------ springs and memory

    /** A spring per id, chasing {@code target} with the {@link Motion#SNAPPY} preset (motion level honoured). */
    public float anim(String id, float target) { return anim(id, target, Motion.SNAPPY); }

    /** A spring per id with any preset. */
    public float anim(String id, float target, Motion.SpringToken t) {
        Motion.Spring s = springs.computeIfAbsent(id, k -> new Motion.Spring());
        return Motion.spring(s, target, t, dt);
    }

    /** Jump {@code id}'s spring to {@code v}. */
    public void snapAnim(String id, float v) { springs.computeIfAbsent(id, k -> new Motion.Spring()).set(v); }

    /** Arbitrary per-id memory for components (open state, scroll, last value). */
    @SuppressWarnings("unchecked")
    public <T> T mem(String id, T fallback) { return (T) memory.getOrDefault(id, fallback); }

    /** Remember {@code value} for {@code id} (read back with {@link #mem}). */
    public void remember(String id, Object value) { memory.put(id, value); }

    /** The text state of field {@code id}. */
    public TextState text(String id) { return texts.computeIfAbsent(id, k -> new TextState()); }

    /** The id of the open popover (dropdown, colour picker), or null. Only one is open at a time. */
    public String popup() { return openPopup; }

    public void popup(String id) { openPopup = id; }

    /** Use one of the frame's glows (at most {@link Tokens#MAX_GLOWS}): false when the budget is spent or glows are
     *  off (High Contrast, Reduce Transparency). */
    public boolean glow() {
        if (!Tokens.glows() || glows >= Tokens.MAX_GLOWS) return false;
        glows++;
        return true;
    }

    // ------------------------------------------------------------------ focus

    public String focused() { return focused; }

    /** Focus {@code id} (null clears). {@code visible}: show the ring (keyboard focus). */
    public void focus(String id, boolean visible) { focused = id; focusVisible = visible && id != null; }

    public boolean isFocused(String id) { return id.equals(focused); }

    // ------------------------------------------------------------------ input

    private static String topHit(List<Hit> list, double x, double y) {
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).contains(x, y)) return list.get(i).id();
        return null;
    }

    /** The last complete frame's hits (input arrives between frames). */
    private List<Hit> done() { return frameOpen ? lastHits : hits; }

    private Hit topHitObj(double x, double y) {
        List<Hit> list = done();
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).contains(x, y)) return list.get(i);
        return null;
    }

    /** Whether {@code id} was hovered (topmost) when this frame began. */
    public boolean hovered(String id) { return id.equals(hovered); }

    /** A mouse press: runs the topmost hit's action. A press outside an open popover closes it. */
    public boolean mouseClicked(double x, double y, int button) {
        Hit h = topHitObj(x, y);
        if (h == null) {
            if (openPopup != null) { openPopup = null; return true; }
            if (focused != null && texts.containsKey(focused)) focused = null;
            return false;
        }
        // A press anywhere but on the open popover (or its trigger) closes it, and does nothing else.
        if (openPopup != null && !h.id().startsWith(openPopup)) { openPopup = null; return true; }
        pressed = h.id();
        if (h.focusable()) focus(h.id(), false);
        if (drags.containsKey(h.id()) && button == 0) {
            dragging = h.id();
            drags.get(h.id()).drag(x, y);
        }
        if (h.action() != null) h.action().click(button, x, y);
        return true;
    }

    public boolean mouseReleased(double x, double y, int button) {
        boolean was = pressed != null || dragging != null;
        pressed = null;
        dragging = null;
        return was;
    }

    public boolean mouseDragged(double x, double y, int button) {
        if (dragging == null) return false;
        DragAction d = drags.get(dragging);
        if (d != null) d.drag(x, y);
        return true;
    }

    /** Scroll over a hit that registered a key action for scrolling (dropdown triggers cycle on the wheel). */
    public boolean mouseScrolled(double x, double y, double amount) {
        Hit h = topHitObj(x, y);
        if (h == null) return false;
        KeyAction k = keys.get(h.id() + "#wheel");
        return k != null && k.key(amount > 0 ? GLFW.GLFW_KEY_UP : GLFW.GLFW_KEY_DOWN, 0);
    }

    /** Keys: Tab / Shift+Tab move focus, Escape closes a popover, Enter / Space press the focused control, anything
     *  else goes to the focused control's key action. */
    public boolean keyPressed(int key, int modifiers) {
        if (focused != null) {
            KeyAction k = keys.get(focused);
            if (k != null && k.key(key, modifiers)) return true;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            // Focus is trapped above the last modal scrim (a dialog's own buttons).
            List<Hit> all = done(), order = new ArrayList<>();
            int from = 0;
            for (int j = 0; j < all.size(); j++) if (all.get(j).id().endsWith("#scrim")) from = j + 1;
            for (int j = from; j < all.size(); j++) if (all.get(j).focusable() && all.get(j).action() != null) order.add(all.get(j));
            if (order.isEmpty()) return false;
            int i = -1;
            for (int j = 0; j < order.size(); j++) if (order.get(j).id().equals(focused)) i = j;
            boolean back = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
            int n = order.size();
            focus(order.get(((back ? i - 1 : i + 1) % n + n) % n).id(), true);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && openPopup != null) { openPopup = null; return true; }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE) && focused != null) {
            for (Hit h : done()) if (h.id().equals(focused) && h.action() != null) {
                h.action().click(0, (h.x1() + h.x2()) / 2.0, (h.y1() + h.y2()) / 2.0);
                focusVisible = true;
                return true;
            }
        }
        return false;
    }

    /** Typed characters go to the focused text field. */
    public boolean charTyped(int codePoint) {
        if (focused == null || !texts.containsKey(focused) || codePoint < 32) return false;
        TextState t = texts.get(focused);
        t.text.insert(Math.min(t.caret, t.text.length()), Character.toChars(codePoint));
        t.caret = Math.min(t.text.length(), t.caret + Character.charCount(codePoint));
        t.lastEdit = now;
        return true;
    }

    // ------------------------------------------------------------------ text

    public void text(String s, int x, int y, int color, Role r) { UiFont.draw(ctx, tr, s, x, y, color, r, smooth); }

    /** {@code s} with its caps optically centred on {@code centreY}. */
    public void textCentredY(String s, int x, float centreY, int color, Role r) {
        float y = UiFont.centredY(r, centreY, smooth);
        int iy = (int) Math.floor(y);
        if (y == iy) { text(s, x, iy, color, r); return; }
        var m = ctx.getMatrices();
        m.pushMatrix();
        try { m.translate(0, y - iy); text(s, x, iy, color, r); } finally { m.popMatrix(); }
    }

    public int width(String s, Role r) { return UiFont.width(tr, s, r, smooth); }

    /** {@code s} cut to {@code max} px with an ellipsis. */
    public String ellipsize(String s, Role r, int max) {
        if (s == null) return "";
        if (width(s, r) <= max) return s;
        String e = "…";
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (width(s.substring(0, mid).stripTrailing() + e, r) <= max) lo = mid; else hi = mid - 1;
        }
        return s.substring(0, lo).stripTrailing() + e;
    }

    /** {@code s} word-wrapped to {@code max} px (at most {@code maxLines}, the last one ellipsized). */
    public List<String> wrap(String s, Role r, int max, int maxLines) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String trial = line.isEmpty() ? word : line + " " + word;
            if (width(trial, r) <= max || line.isEmpty()) { line.setLength(0); line.append(trial); continue; }
            out.add(line.toString());
            line.setLength(0);
            line.append(word);
        }
        if (!line.isEmpty()) out.add(line.toString());
        if (out.size() > maxLines) {
            List<String> cut = new ArrayList<>(out.subList(0, maxLines));
            StringBuilder rest = new StringBuilder(cut.get(maxLines - 1));
            for (int i = maxLines; i < out.size(); i++) rest.append(' ').append(out.get(i));
            cut.set(maxLines - 1, ellipsize(rest.toString(), r, max));
            return cut;
        }
        return out;
    }

    /** The scaled screen size. */
    public int screenW() { return ctx.getScaledWindowWidth(); }

    public int screenH() { return ctx.getScaledWindowHeight(); }
}
