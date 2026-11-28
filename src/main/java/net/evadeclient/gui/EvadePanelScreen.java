package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.Kit;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.Scrollbar;
import net.evadeclient.gui.kit.SearchField;
import net.evadeclient.gui.kit.Surface;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.render.ui.UiShape;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.function.IntConsumer;

/**
 * The window every Evade panel shares (Keybinds, Waypoints, Schematics, Material List, Text Hotkeys, the setup wizard,
 * the screenshot gallery): GUI 3.0 "Obsidian" chrome built on the kit. A glass window (e1, r12, grain, rim light and
 * a faint accent light leaking from below) over a dithered scrim; a header with a back (or close) button, the
 * HEADLINE title and a subtitle, and right-side actions; an optional toolbar row with the kit search field; the
 * scrolling content with fading edges and the overlay scrollbar; and an optional footer bar.
 *
 * <p>Subclasses draw with the kit ({@link #kit}) or the helpers here and register what can be clicked with
 * {@link #contentHit} / {@link #hit}; every hit goes through the kit, so the keyboard (Tab, Enter) reaches them and
 * the topmost one wins. Hits made while the content draws are clipped to the content view.
 *
 * <p>Motion: the window enters with a fade, a 0.97 to 1 scale and a 10 px rise (Full); a short fade only (Reduced);
 * nothing (Off). Rows can stagger in with {@link #enter}.
 */
public abstract class EvadePanelScreen extends Screen {
    protected final Screen parent;
    protected final Kit kit = new Kit();
    protected boolean smooth = true, anims = true;
    private long openedAt, listAt;
    protected float dt;

    /** Header height, toolbar row height and the gap under it. */
    protected static final int HEAD_H = 52, TOOL_H = 24;

    // window and content rect, set each frame
    protected int wx, wy, ww, wh, cx, cy, cw, ch;

    // content scrolling
    protected float scroll, scrollTarget;
    protected int contentHeight;

    /** The search field's text (when {@link #hasSearch}). */
    protected String search = "";
    private int autoId;

    /** Button styles for {@link #button}. One primary (red) per view. */
    protected static final int SECONDARY = 0, PRIMARY = 1, DANGER = 2, GHOST = 3, SELECTED = 4;

    protected EvadePanelScreen(String title, Screen parent) {
        super(Text.literal(title));
        this.parent = parent;
    }

    protected static ClientSettings settings() {
        return EvadeClient.modules == null ? null : EvadeClient.modules.get(ClientSettings.class);
    }

    @Override
    protected void init() {
        ClientSettings s = settings();
        smooth = s == null || s.smoothFont.get();
        anims = Motion.level() != Motion.Level.OFF;
        if (openedAt == 0) { openedAt = System.currentTimeMillis(); listAt = openedAt; }
    }

    @Override public boolean shouldPause() { return false; }

    @Override public void close() { if (client != null) client.setScreen(parent); }

    // ------------------------------------------------------------------ to implement

    /** Window size wanted (clamped to the screen). */
    protected int prefWidth() { return 640; }
    protected int prefHeight() { return 400; }

    /** A subtitle under the title (may be empty). */
    protected String subtitle() { return ""; }

    /** An icon shown in a tile before the title (null: none). */
    protected String headerIcon() { return null; }

    protected boolean hasSearch() { return false; }

    /** The search field's placeholder. */
    protected String searchHint() { return "Search"; }

    /** Draw the header's right side (buttons, chips) ending at {@code right}; {@code y} is the top of a 20 px row. */
    protected void renderHeaderRight(DrawContext ctx, int right, int y, int mx, int my) {}

    /** Draw toolbar controls right of the search field, from {@code x} to {@code right}, in a 24 px row at {@code y}. */
    protected void renderToolbar(DrawContext ctx, int x, int y, int right, int mx, int my) {}

    /** Draw the scrolling content starting at {@code top} (already offset by the scroll); return its total height. */
    protected abstract int renderContent(DrawContext ctx, int top, int mx, int my);

    /** Height of the footer bar under the content (0: none). */
    protected int footerHeight() { return 0; }

    /** Draw the footer bar's content between x1 and x2, {@code y} being the top of the bar. */
    protected void renderFooter(DrawContext ctx, int x1, int y, int x2, int mx, int my) {}

    /** Draw anything over the content (a toast-like bar, a popup). */
    protected void renderOverlay(DrawContext ctx, int mx, int my) {}

    // ------------------------------------------------------------------ frame

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ClientSettings s = settings();
        if (s != null) s.applyTheme();
        if (s == null || s.blur.get()) super.renderBackground(ctx, mouseX, mouseY, delta);
        float a = openFade();
        Paint.scrim(ctx, width, height, a);
    }

    /** 0..1: how far the window's fade-in has come. */
    protected float openFade() {
        Motion.Level l = Motion.level();
        if (l == Motion.Level.OFF) return 1f;
        int d = l == Motion.Level.REDUCED ? Motion.DUR_REDUCED_MAX : Motion.DUR_QUICK;
        return Motion.clamp01((System.currentTimeMillis() - openedAt) / (float) d);
    }

    /** 0..1: the window's scale-and-rise entrance (Full motion only). */
    private float openRise() {
        if (!Motion.decorative()) return 1f;
        return Motion.standard(Motion.clamp01((System.currentTimeMillis() - openedAt) / (float) Motion.DUR_SLOW));
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        anims = Motion.level() != Motion.Level.OFF;
        kit.begin(ctx, mx, my);
        dt = kit.dt;
        autoId = 0;

        ww = Math.min(prefWidth(), width - 20);
        wh = Math.min(prefHeight(), height - 20);
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;
        int pad = Surface.PAD;
        cx = wx + pad;
        cw = ww - 2 * pad;
        cy = wy + HEAD_H + (hasSearch() ? TOOL_H + 10 : 0) + 6;
        int foot = footerHeight();
        ch = wy + wh - (foot > 0 ? foot : 10) - cy;

        float base = Theme.fade;
        float a = openFade(), rise = openRise();
        Theme.fade = base * a;
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            if (rise < 1f) {
                float sc = 0.97f + 0.03f * rise;
                m.translate(width / 2f, height / 2f);
                m.scale(sc, sc);
                m.translate(-width / 2f, -height / 2f + Theme.snap(ctx, (1f - rise) * 10f));
            }
            drawWindow(ctx);
            drawHeader(ctx, mx, my);
            if (hasSearch()) drawToolbar(ctx, mx, my);

            // content
            float target = Math.max(0, Math.min(maxScroll(), scrollTarget));
            scroll = kit.anim("panel#scroll", target, Motion.SMOOTH);
            if (Math.abs(scroll - target) < 0.25f) scroll = target;
            ctx.enableScissor(cx - 6, cy, cx + cw + 6, cy + ch);
            kit.clipHits(cx - 6, cy, cx + cw + 6, cy + ch);
            try {
                contentHeight = renderContent(ctx, cy - Math.round(scroll), mx, my);
            } finally {
                kit.unclipHits();
                ctx.disableScissor();
            }
            int max = maxScroll();
            if (scrollTarget > max) scrollTarget = max;
            if (max > 0) {
                int bg = Tokens.N100;
                Scrollbar.edgeFades(kit, cx - 6, cy, cx + cw + 6, cy + ch, Theme.alpha(bg, 0.85f), scroll > 1, scroll < max - 1);
                Scrollbar.draw(kit, "panel#bar", wx + ww - 4, cy, cy + ch, scroll, max, v -> { scrollTarget = v; kit.snapAnim("panel#scroll", v); });
            }
            if (foot > 0) {
                int fy = wy + wh - foot;
                Paint.fadeLine(ctx, wx + 10, fy, wx + ww - 10, Theme.alpha(0xFFFFFFFF, 0.06f));
                renderFooter(ctx, cx, fy, cx + cw, mx, my);
            }
            renderOverlay(ctx, mx, my);
            kit.end();
        } finally {
            m.popMatrix();
            Theme.fade = base;
        }
    }

    /** The glass window, its shadows and the faint accent light along its bottom edge. */
    private void drawWindow(DrawContext ctx) {
        if (Paint.gpu() && Tokens.glows() && kit.glow())
            UiShape.glow(ctx, wx + ww * 0.2f, wy + wh - 6, wx + ww * 0.8f, wy + wh, 6, 22, Theme.alpha(Tokens.A600, 0.10f));
        Surface.window(kit, wx, wy, wx + ww, wy + wh);
    }

    private void drawHeader(DrawContext ctx, int mx, int my) {
        int hx = wx + Surface.PAD - 4, mid = wy + HEAD_H / 2;
        int tx = wx + Surface.PAD;
        if (parent != null) {
            Button.icon(kit, "panel#back", hx, mid - 12, 24, "chevron_l", "Back", true, this::close);
            tx = hx + 24 + Tokens.S3;
        }
        String icon = headerIcon();
        if (icon != null) {
            Paint.surface(ctx, tx, mid - 13, tx + 26, mid + 13, Tokens.R_SM, Tokens.E3.noAmbient(), 0, Float.NaN);
            Icons.draw(ctx, icon, tx + 6, mid - 7, 14, Tokens.N900);
            tx += 26 + Tokens.S4;
        }
        int right = wx + ww - Surface.PAD + 4;
        if (parent == null) {
            right -= 24;
            Button.icon(kit, "panel#close", right, mid - 12, 24, "close", "Close", true, this::close);
            right -= Tokens.S3;
        }
        String sub = subtitle();
        String t = title.getString();
        int maxT = Math.max(40, (right - tx) / 2);
        boolean hasSub = sub != null && !sub.isEmpty();
        kit.textCentredY(kit.ellipsize(t, Role.HEADLINE, maxT), tx, hasSub ? mid - 7 : mid, Tokens.N950, Role.HEADLINE);
        headerRightX = right;
        inHeader = true;
        try {
            renderHeaderRight(ctx, right, mid - 10, mx, my);
        } finally {
            inHeader = false;
        }
        if (hasSub) {
            // The subtitle stops short of the right-side actions.
            int max = Math.max(60, headerRightX - tx - 16);
            kit.textCentredY(kit.ellipsize(sub, Role.SMALL, max), tx, mid + 10, Tokens.N700, Role.SMALL);
        }
        Paint.fadeLine(ctx, wx + 10, wy + HEAD_H, wx + ww - 10, Theme.alpha(0xFFFFFFFF, 0.06f));
    }

    private boolean inHeader;
    private int headerRightX = Integer.MAX_VALUE;

    /** Header actions drawn by hand report how far left they reach (so the subtitle stops before them). */
    protected void headerReach(int x) { headerRightX = Math.min(headerRightX, x); }

    private void drawToolbar(DrawContext ctx, int mx, int my) {
        int y = wy + HEAD_H + 10;
        int w = Math.min(SearchField.MAX_W, Math.max(160, cw * 11 / 20));
        search = SearchField.draw(kit, "search", cx, y, w, searchHint(), "Ctrl F", true);
        if (!search.equals(lastSearch)) { lastSearch = search; searchChanged(); }
        renderToolbar(ctx, cx + w + Tokens.S3, y, cx + cw, mx, my);
    }

    private String lastSearch = "";

    // ------------------------------------------------------------------ helpers

    protected int maxScroll() { return Math.max(0, contentHeight - ch); }

    /** Whether the search field has the keyboard. */
    protected boolean searchFocused() { return hasSearch() && kit.isFocused("search"); }

    /** Replace the search text. */
    protected void setSearch(String s) {
        SearchField.set(kit, "search", s);
        search = s == null ? "" : s;
        lastSearch = search;
        searchChanged();
    }

    /** A 0..1 hover amount that springs toward {@code on}. */
    protected float hover(String key, boolean on) {
        return Motion.clamp01(kit.anim("h:" + key, on ? 1f : 0f));
    }

    /**
     * The entrance of the {@code index}th row (0..1): the first ten rows fade and rise in, 16 ms apart, when the
     * panel opens or {@link #restartEntrance} runs. 1 at once below Full motion.
     */
    protected float enter(int index) {
        if (!Motion.decorative() || index > 12) return 1f;
        float t = (System.currentTimeMillis() - listAt - Math.min(index, 10) * 16L) / (float) Motion.DUR_BASE;
        return Motion.standard(Motion.clamp01(t));
    }

    /** Replay the row entrance (a new page of content). */
    protected void restartEntrance() { listAt = System.currentTimeMillis(); }

    protected void text(DrawContext ctx, String s, int x, int y, int color, Role r) {
        UiFont.draw(ctx, textRenderer, s, x, y, color, r, smooth);
    }

    protected int width(String s, Role r) { return UiFont.width(textRenderer, s, r, smooth); }

    protected String ellipsize(String s, Role r, int max) {
        if (max <= 0) return "";
        return kit.ellipsize(s, r, max);
    }

    private static Button.Variant variant(int style) {
        return switch (style) {
            case PRIMARY -> Button.Variant.PRIMARY;
            case DANGER -> Button.Variant.DANGER;
            case GHOST -> Button.Variant.GHOST;
            default -> Button.Variant.SECONDARY;
        };
    }

    /**
     * A small (20 px) kit button ending at {@code right} whose old 16 px box started at {@code y} (it is centred on
     * that box). Returns its left edge. {@code style}: {@link #SECONDARY}, {@link #PRIMARY} (one per view),
     * {@link #DANGER}, {@link #GHOST} or {@link #SELECTED} (a secondary that is switched on). {@code action} gets the
     * mouse button (0 left, 1 right).
     */
    protected int button(DrawContext ctx, String id, String label, int right, int y, int mx, int my, int style, IntConsumer action) {
        return button(id, label, null, right, y - 2, style, action);
    }

    /** {@link #button} with an optional leading icon, its top at {@code top} (20 tall). Returns its left edge. */
    protected int button(String id, String label, String icon, int right, int top, int style, IntConsumer action) {
        int w = Button.width(kit, label, Button.Size.SM, icon, false);
        int x = right - w;
        drawButton(id, label, icon, x, top, w, Button.Size.SM, style, true, action);
        if (inHeader) headerReach(x);
        return x;
    }

    /** A kit button at (x, top) of any size; returns its width. */
    protected int drawButton(String id, String label, String icon, int x, int top, int w, Button.Size size, int style,
                             boolean enabled, IntConsumer action) {
        if (w <= 0) w = Button.width(kit, label, size, icon, false);
        if (icon != null && PanelGlyphs.has(icon) && style != PRIMARY) {
            glyphLabelButton(id, label, icon, x, top, w, size, style, enabled);
        } else if (style == SELECTED && enabled) {
            // A switched-on secondary: the accent surface, a slanted edge light and a check, never a red fill.
            int h = size.h;
            int st = kit.state(id, x, top, x + w, top + h, true);
            float hov = kit.anim(id + "#h", (st & Kit.HOVER) != 0 ? 1f : 0f);
            Paint.surface(kit.ctx, x, top, x + w, top + h, Tokens.R_SM,
                    Tokens.E3.noAmbient().withFill(Theme.lerpColor(Tokens.ACCENT_SURFACE, Theme.lerpColor(Tokens.ACCENT_SURFACE, Tokens.N300, 0.4f), hov)), hov, kit.mx - x);
            Paint.ring(kit.ctx, x, top, x + w, top + h, Tokens.R_SM, Theme.alpha(Tokens.A500, 0.55f), 1);
            if ((st & Kit.FOCUSED) != 0) Paint.focusRing(kit, x, top, x + w, top + h, Tokens.R_SM, Tokens.FOCUS_RING);
            int is = size == Button.Size.SM ? 12 : 14, lw = kit.width(label, size.role);
            String ic = icon != null ? icon : "check";
            int gx = x + (w - (is + Tokens.S3 + lw)) / 2;
            Icons.draw(kit.ctx, ic, gx, Math.round(top + h / 2f - is / 2f), is, Tokens.A400);
            kit.textCentredY(label, gx + is + Tokens.S3, top + h / 2f, Tokens.N950, size.role);
        } else {
            Button.draw(kit, id, x, top, w, size, variant(style), label, icon, enabled, () -> action.accept(0));
        }
        // Every mouse button reaches the action (right-click cycles backwards on sort buttons and the like).
        if (enabled) kit.hit(id, x, top, x + w, top + size.h, (b, px, py) -> action.accept(b), true);
        return w;
    }

    /** A secondary / ghost / danger / selected button whose leading icon is a {@link PanelGlyphs} glyph. */
    private void glyphLabelButton(String id, String label, String icon, int x, int top, int w, Button.Size size, int style, boolean enabled) {
        int h = size.h;
        int st = kit.state(id, x, top, x + w, top + h, enabled);
        boolean dis = (st & Kit.DISABLED) != 0, press = (st & Kit.PRESSED) != 0;
        float hov = kit.anim(id + "#h", (st & Kit.HOVER) != 0 ? 1f : 0f);
        float dy = press ? 0.5f / Theme.deviceScale(kit.ctx) : 0f;
        float f = Theme.fade;
        if (dis) Theme.fade = f * 0.4f;
        try {
            int text = Tokens.N950, ic = Tokens.N800;
            switch (style) {
                case GHOST -> {
                    if (hov > 0.01f || press) Paint.rect(kit.ctx, x, top + dy, x + w, top + h + dy, Tokens.R_SM, press ? Tokens.N200 : Theme.alpha(Tokens.N250, 0.6f * hov));
                    text = Theme.lerpColor(Tokens.N800, Tokens.N950, hov);
                    ic = text;
                }
                case DANGER -> {
                    if (hov > 0.01f || press) Paint.rect(kit.ctx, x, top + dy, x + w, top + h + dy, Tokens.R_SM, Theme.alpha(Tokens.DANGER, press ? 0.22f : 0.14f * hov));
                    text = Tokens.DANGER;
                    ic = text;
                }
                case SELECTED -> {
                    Paint.surface(kit.ctx, x, top + dy, x + w, top + h + dy, Tokens.R_SM, Tokens.E3.noAmbient().withFill(Tokens.ACCENT_SURFACE), hov, kit.mx - x);
                    Paint.ring(kit.ctx, x, top + dy, x + w, top + h + dy, Tokens.R_SM, Theme.alpha(Tokens.A500, 0.55f), 1);
                    ic = Tokens.A400;
                }
                default -> {
                    int fill = press ? Tokens.N250 : Theme.lerpColor(Tokens.N250, Tokens.N300, hov);
                    Paint.surface(kit.ctx, x, top + dy, x + w, top + h + dy, Tokens.R_SM, Tokens.E3.noAmbient().withFill(fill), press ? 0 : hov, kit.mx - x);
                    ic = Theme.lerpColor(Tokens.N800, Tokens.N950, hov);
                }
            }
            if ((st & Kit.FOCUSED) != 0) Paint.focusRing(kit, x, top, x + w, top + h, Tokens.R_SM, style == DANGER ? Tokens.DANGER : Tokens.FOCUS_RING);
            int is = size == Button.Size.SM ? 12 : size == Button.Size.XL ? 16 : 14;
            int lw = kit.width(label, size.role);
            int gx = Math.round(x + (w - (is + Tokens.S3 + lw)) / 2f);
            float cyy = top + h / 2f + dy;
            PanelGlyphs.draw(kit.ctx, icon, gx, Math.round(cyy - is / 2f), is, ic);
            kit.textCentredY(label, gx + is + Tokens.S3, cyy, text, size.role);
        } finally {
            Theme.fade = f;
        }
    }

    /**
     * A square ghost icon button (24 or 28) showing an {@link Icons} icon or a {@link PanelGlyphs} glyph, with a kit
     * tooltip. {@code on}: switched on (accent surface, accent glyph).
     */
    protected void glyphButton(String id, int x, int y, int size, String glyph, String tip, boolean enabled, boolean on, IntConsumer action) {
        PanelGlyphs.button(kit, id, x, y, size, glyph, tip, enabled, on, action);
    }

    /** A CAPS section header with an optional count; returns the y below it. */
    protected int section(String title, String count, int x, int y) {
        return Surface.section(kit, title, count, x, y);
    }

    /**
     * A list row's surface at (x1, y1)-(x2, y2): e2 without the ambient shadow, lifting to e3 with {@code hover}.
     * {@code on}: the selected look (accent surface from the left and the slanted edge light).
     */
    protected void rowSurface(int x1, int y1, int x2, int y2, float hover, boolean on) {
        Paint.surface(kit.ctx, x1, y1, x2, y2, Tokens.R_SM,
                Tokens.E2.noAmbient().withFill(Theme.lerpColor(Tokens.N200, Tokens.N250, hover)), hover, kit.mx - x1);
        if (on) {
            Paint.hgradient(kit.ctx, x1, y1, x2, y2, Tokens.R_SM, Theme.alpha(Tokens.A900, 0.5f), Theme.alpha(Tokens.A900, 0f));
            Paint.slant(kit.ctx, x1 + 1, y1 + 5, x1 + 3, y2 - 5, Tokens.A500);
        }
    }

    /** Whether a row at {@code y..y+h} is inside the content view (worth drawing and clicking). */
    protected boolean inView(int y, int h) { return y + h > cy && y < cy + ch; }

    /** Register a click region in the content (clipped to the view). {@code action} gets the mouse button. */
    protected void contentHit(int x1, int y1, int x2, int y2, IntConsumer action) {
        contentHit("c#" + (autoId++), x1, y1, x2, y2, action);
    }

    /** {@link #contentHit} with a stable id (keyboard focus survives scrolling). */
    protected void contentHit(String id, int x1, int y1, int x2, int y2, IntConsumer action) {
        int a = Math.max(y1, cy), b = Math.min(y2, cy + ch);
        if (b > a) kit.hit(id, x1, a, x2, b, (btn, px, py) -> action.accept(btn), true);
    }

    /** Register a click region anywhere (not clipped). */
    protected void hit(String id, int x1, int y1, int x2, int y2, IntConsumer action) {
        kit.hit(id, x1, y1, x2, y2, (btn, px, py) -> action.accept(btn), true);
    }

    /** A region that swallows clicks under it (a full-screen view over the panel). */
    protected void barrier(String id, int x1, int y1, int x2, int y2) {
        kit.barrier(id, x1, y1, x2, y2, null);
    }

    protected boolean mouseIn(int mx, int my, int x1, int y1, int x2, int y2) {
        return mx >= x1 && mx < x2 && my >= y1 && my < y2 && my >= cy && my < cy + ch;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (kit.mouseClicked(click.x(), click.y(), click.button())) return true;
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseReleased(Click click) {
        return kit.mouseReleased(click.x(), click.y(), click.button()) || super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        return kit.mouseDragged(click.x(), click.y(), click.button()) || super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        if (kit.mouseScrolled(x, y, v)) return true;
        scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget - (float) v * 28f));
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        boolean ctrl = (input.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        if (key == GLFW.GLFW_KEY_F && ctrl && hasSearch()) { kit.focus("search", true); return true; }
        if (kit.keyPressed(key, input.modifiers())) return true;
        if (searchFocused()) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                kit.focus(null, false);
                return true;
            }
        }
        if (key == GLFW.GLFW_KEY_PAGE_DOWN || key == GLFW.GLFW_KEY_PAGE_UP) {
            scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget + (key == GLFW.GLFW_KEY_PAGE_DOWN ? ch - 30 : -(ch - 30))));
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (kit.charTyped(input.codepoint())) return true;
        // Typing anywhere starts a search.
        if (hasSearch() && input.isValidChar()) {
            String s = input.asString();
            if (!s.isBlank()) {
                kit.focus("search", false);
                return kit.charTyped(input.codepoint());
            }
        }
        return super.charTyped(input);
    }

    /** The search text changed: back to the top. */
    protected void searchChanged() { scrollTarget = 0; }
}
