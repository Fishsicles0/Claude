package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.StringHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The frame the Evade sub-screens share (Keybinds, Waypoint Manager, first-run setup): a centred window in the menu's
 * colours with a title, a Back button, an optional one-line search field and a scrolling content area. Subclasses
 * draw rows with the helpers here and register what can be clicked as {@link Hit}s each frame.
 */
public abstract class EvadePanelScreen extends Screen {
    protected final Screen parent;
    protected boolean smooth = true, anims = true;
    private long openedAt;
    private long lastFrame = System.nanoTime();
    protected float dt;

    // window and content rect, set each frame
    protected int wx, wy, ww, wh, cx, cy, cw, ch;

    // content scrolling
    protected float scroll, scrollTarget;
    protected int contentHeight;
    private final Motion.Spring scrollSpring = new Motion.Spring(0);
    private final Map<String, Motion.Spring> springs = new HashMap<>();

    /** A clickable rectangle registered while drawing: {@code action} gets the mouse button. */
    protected record Hit(int x1, int y1, int x2, int y2, java.util.function.IntConsumer action) {
        boolean contains(double x, double y) { return x >= x1 && x < x2 && y >= y1 && y < y2; }
    }

    protected final List<Hit> hits = new ArrayList<>();

    /** The search field's text (when {@link #hasSearch}) and whether it has focus. */
    protected String search = "";
    protected boolean searchFocused;

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
        anims = s == null || s.animations.get();
        if (openedAt == 0) openedAt = System.currentTimeMillis();
    }

    @Override public boolean shouldPause() { return false; }

    @Override public void close() { if (client != null) client.setScreen(parent); }

    // ------------------------------------------------------------------ to implement

    /** Window size wanted (clamped to the screen). */
    protected int prefWidth() { return 640; }
    protected int prefHeight() { return 400; }

    /** A subtitle under the title (may be empty). */
    protected String subtitle() { return ""; }

    protected boolean hasSearch() { return false; }

    /** Draw the header's right side (buttons, chips) ending at {@code right}; register hits. */
    protected void renderHeaderRight(DrawContext ctx, int right, int y, int mx, int my) {}

    /** Draw the scrolling content starting at {@code top} (already offset by the scroll); return its total height. */
    protected abstract int renderContent(DrawContext ctx, int top, int mx, int my);

    /** Draw anything over the content (a footer line, a popup). */
    protected void renderOverlay(DrawContext ctx, int mx, int my) {}

    // ------------------------------------------------------------------ frame

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ClientSettings s = settings();
        if (s != null) s.applyTheme();
        if (s == null || s.blur.get()) super.renderBackground(ctx, mouseX, mouseY, delta);
        float a = openAlpha();
        ctx.fillGradient(0, 0, width, height, Theme.alpha(0xB0000000, a),
                Theme.alpha(0xD0000000 | (Theme.lerpColor(0xFF000000, Theme.RED, 0.09f) & 0xFFFFFF), a));
    }

    private float openAlpha() {
        if (!anims) return 1f;
        return Motion.cubicOut((System.currentTimeMillis() - openedAt) / 160f);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        long now = System.nanoTime();
        dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        hits.clear();

        ww = Math.min(prefWidth(), width - 20);
        wh = Math.min(prefHeight(), height - 20);
        wx = (width - ww) / 2;
        float a = openAlpha();
        wy = (height - wh) / 2 + (anims ? Math.round((1f - a) * 8f) : 0);
        cx = wx + 16;
        cw = ww - 32;
        cy = wy + 50 + (hasSearch() ? 26 : 0);
        ch = wy + wh - 12 - cy;

        float base = Theme.fade;
        Theme.fade = base * a;
        try {
            Theme.shadow(ctx, Theme.SH_WINDOW, wx, wy, wx + ww, wy + wh, 0x90000000);
            Theme.round(ctx, wx, wy, wx + ww, wy + wh, Theme.BG, Theme.R_WIN);
            Theme.stroke(ctx, wx, wy, wx + ww, wy + wh, Theme.BORDER, Theme.R_WIN);

            // Back, title and subtitle
            boolean hb = mx >= cx && mx < cx + 46 && my >= wy + 12 && my < wy + 28;
            float bh = hover("back", hb);
            Theme.roundBordered(ctx, cx, wy + 12, cx + 46, wy + 28, Theme.lerpColor(Theme.CONTROL, Theme.RED, bh), Theme.lerpColor(Theme.BORDER, Theme.RED, bh));
            text(ctx, "‹ Back", cx + 7, wy + 16, Theme.lerpColor(Theme.TEXT, Theme.ON_ACCENT, bh), Role.LABEL);
            hits.add(new Hit(cx, wy + 12, cx + 46, wy + 28, b -> close()));
            text(ctx, title.getString(), cx + 56, wy + 12, Theme.TEXT, Role.TITLE);
            String sub = subtitle();
            if (!sub.isEmpty()) text(ctx, ellipsize(sub, Role.SMALL, cw - 260), cx + 56, wy + 30, Theme.TEXT_DIM, Role.SMALL);
            renderHeaderRight(ctx, cx + cw, wy + 13, mx, my);
            Theme.fill(ctx, cx, wy + 42, cx + cw, wy + 43, Theme.DIVIDER);

            if (hasSearch()) renderSearch(ctx, mx, my);

            // content
            scroll = anims ? scrollSpring.step(scrollTarget, 22f, 1f, dt) : scrollTarget;
            if (!anims) scrollSpring.set(scrollTarget);
            ctx.enableScissor(cx - 4, cy, cx + cw + 4, cy + ch);
            try {
                contentHeight = renderContent(ctx, cy - Math.round(scroll), mx, my);
            } finally {
                ctx.disableScissor();
            }
            int max = maxScroll();
            if (scrollTarget > max) scrollTarget = max;
            if (max > 0) {
                int barH = Math.max(16, ch * ch / Math.max(1, contentHeight));
                int barY = cy + Math.round((ch - barH) * (scroll / max));
                Theme.round(ctx, wx + ww - 7, barY, wx + ww - 5, barY + barH, Theme.alpha(Theme.TEXT_FAINT, 0.7f), 1);
            }
            renderOverlay(ctx, mx, my);
        } finally {
            Theme.fade = base;
        }
    }

    private void renderSearch(DrawContext ctx, int mx, int my) {
        int y = wy + 50;
        boolean hv = mx >= cx && mx < cx + cw && my >= y && my < y + 20;
        Theme.roundBordered(ctx, cx, y, cx + cw, y + 20, Theme.INPUT,
                searchFocused ? Theme.alpha(Theme.RED, 0.8f) : hv ? Theme.BORDER_HOVER : Theme.BORDER);
        net.evadeclient.util.Icons.draw(ctx, "search", cx + 6, y + 5, 10, Theme.TEXT_DIM);
        boolean empty = search.isEmpty();
        String shown = empty && !searchFocused ? "Search..." : search;
        text(ctx, shown, cx + 22, y + 6, empty ? Theme.TEXT_FAINT : Theme.TEXT, Role.BODY);
        if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int tx = cx + 22 + width(search, Role.BODY);
            Theme.fill(ctx, tx + 1, y + 5, tx + 2, y + 15, Theme.TEXT);
        }
        hits.add(new Hit(cx, y, cx + cw, y + 20, b -> searchFocused = true));
    }

    // ------------------------------------------------------------------ helpers

    protected int maxScroll() { return Math.max(0, contentHeight - ch); }

    /** A 0..1 hover amount that eases toward {@code on}. */
    protected float hover(String key, boolean on) {
        float target = on ? 1 : 0;
        if (!anims) return target;
        return Motion.clamp01(springs.computeIfAbsent(key, k -> new Motion.Spring(target)).step(target, 24f, 1f, dt));
    }

    protected void text(DrawContext ctx, String s, int x, int y, int color, Role r) {
        UiFont.draw(ctx, textRenderer, s, x, y, color, r, smooth);
    }

    protected int width(String s, Role r) { return UiFont.width(textRenderer, s, r, smooth); }

    protected String ellipsize(String s, Role r, int max) {
        if (max <= 0) return "";
        if (width(s, r) <= max) return s;
        String dots = "…";
        int end = s.length();
        while (end > 0 && width(s.substring(0, end) + dots, r) > max) end--;
        return s.substring(0, end) + dots;
    }

    /**
     * A small button ending at {@code right} (right-aligned) at {@code y}, 16 px tall. Returns its left edge.
     * {@code style}: 0 plain, 1 accent, 2 danger.
     */
    protected int button(DrawContext ctx, String id, String label, int right, int y, int mx, int my, int style,
                         java.util.function.IntConsumer action) {
        int w = width(label, Role.LABEL) + 14;
        int x1 = right - w;
        boolean hv = mx >= x1 && mx < right && my >= y && my < y + 16;
        float h = hover("btn:" + id, hv);
        int accent = style == 2 ? Theme.DANGER : Theme.RED;
        int fill = style == 1 ? Theme.lerpColor(Theme.alpha(Theme.RED, 0.75f), Theme.RED, h)
                : Theme.lerpColor(Theme.CONTROL, style == 2 ? Theme.alpha(Theme.DANGER, 0.25f) : Theme.CONTROL_HOVER, h);
        int border = style == 1 ? Theme.RED : Theme.lerpColor(Theme.BORDER, accent, h * 0.8f);
        Theme.roundBordered(ctx, x1, y, right, y + 16, fill, border);
        text(ctx, label, x1 + 7, y + 4, style == 1 ? Theme.ON_ACCENT : Theme.lerpColor(Theme.TEXT_SOFT, Theme.TEXT, h), Role.LABEL);
        hits.add(new Hit(x1, y, right, y + 16, action));
        return x1;
    }

    /** Whether a row at {@code y..y+h} is inside the content view (worth drawing and clicking). */
    protected boolean inView(int y, int h) { return y + h > cy && y < cy + ch; }

    /** Register a hit clipped to the content view. */
    protected void contentHit(int x1, int y1, int x2, int y2, java.util.function.IntConsumer action) {
        int a = Math.max(y1, cy), b = Math.min(y2, cy + ch);
        if (b > a) hits.add(new Hit(x1, a, x2, b, action));
    }

    protected boolean mouseIn(int mx, int my, int x1, int y1, int x2, int y2) {
        return mx >= x1 && mx < x2 && my >= y1 && my < y2 && my >= cy && my < cy + ch;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double x = click.x(), y = click.y();
        boolean wasFocused = searchFocused;
        searchFocused = false;
        // Later hits are drawn on top: check them first.
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (!h.contains(x, y)) continue;
            h.action().accept(click.button());
            return true;
        }
        if (wasFocused && !search.isEmpty()) searchFocused = false;
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double h, double v) {
        scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget - (float) v * 24f));
        if (!anims) scroll = scrollTarget;
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (hasSearch() && searchFocused) {
            if (key == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
                search = (input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0 ? "" : search.substring(0, search.length() - 1);
                searchChanged();
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                if (!search.isEmpty()) { search = ""; searchChanged(); } else searchFocused = false;
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { searchFocused = false; return true; }
        }
        if (key == GLFW.GLFW_KEY_F && (input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0 && hasSearch()) { searchFocused = true; return true; }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (hasSearch() && searchFocused && input.isValidChar()) {
            String s = input.asString();
            if (StringHelper.isValidChar(s.isEmpty() ? 0 : s.charAt(0)) && search.length() < 48) {
                search += s;
                searchChanged();
            }
            return true;
        }
        // Typing anywhere starts a search.
        if (hasSearch() && input.isValidChar()) {
            String s = input.asString();
            if (!s.isBlank()) {
                searchFocused = true;
                search += s;
                searchChanged();
                return true;
            }
        }
        return super.charTyped(input);
    }

    /** The search text changed: back to the top. */
    protected void searchChanged() { scrollTarget = 0; }
}
