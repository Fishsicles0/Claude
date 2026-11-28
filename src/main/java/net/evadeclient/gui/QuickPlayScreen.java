package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.module.misc.QuickPlay;
import net.evadeclient.module.misc.QuickPlay.Game;
import net.evadeclient.module.misc.QuickPlay.Mode;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Quick Play picker, drawn in the menu's style: games down the left, the selected game's modes as cards on the
 * right, with rows of pinned favourites and recently played modes above them. Left click a card or chip to play it
 * (one command per click), right click (or click its star) to pin or unpin it. Off Hypixel the cards are dimmed and
 * clicking one only says so.
 */
public final class QuickPlayScreen extends Screen {
    private static final int SIDE = 132, NAV_H = 22, CARD_H = 38, CARD_MIN_W = 118, GAP = 6, CHIP_H = 18;
    private static final long OPEN_MS = 160;

    private final Screen parent;
    private Game game;
    private boolean smooth = true, anims = true;
    private long openedAt;
    private float scroll, scrollTarget;
    private int contentHeight, viewHeight;
    private long lastFrame = System.nanoTime();
    private final Map<String, Motion.Spring> hover = new HashMap<>();
    private final Motion.Spring navSpring = new Motion.Spring();

    /** What the mouse can hit this frame, rebuilt by every render (rectangles in screen coordinates). */
    private record Hit(int x1, int y1, int x2, int y2, Mode mode, Game game, boolean star) {
        boolean contains(double x, double y) { return x >= x1 && x < x2 && y >= y1 && y < y2; }
    }

    private final List<Hit> hits = new ArrayList<>();

    // Window rect, set each frame.
    private int wx, wy, ww, wh;

    public QuickPlayScreen(Screen parent) {
        super(Text.literal("Quick Play"));
        this.parent = parent;
        QuickPlay qp = QuickPlay.instance();
        this.game = QuickPlay.gameNamed(qp == null ? "" : qp.lastGame());
    }

    private static ClientSettings settings() {
        return EvadeClient.modules == null ? null : EvadeClient.modules.get(ClientSettings.class);
    }

    @Override
    protected void init() {
        ClientSettings s = settings();
        smooth = s == null || s.smoothFont.get();
        anims = s == null || s.animations.on();
        if (openedAt == 0) openedAt = System.currentTimeMillis();
    }

    @Override public boolean shouldPause() { return false; }

    @Override public void close() { if (client != null) client.setScreen(parent); }

    // ------------------------------------------------------------------ drawing

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
        return Motion.cubicOut((System.currentTimeMillis() - openedAt) / (float) OPEN_MS);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        hits.clear();

        ww = Math.min(620, width - 20);
        wh = Math.min(380, height - 20);
        wx = (width - ww) / 2;
        float a = openAlpha();
        int rise = anims ? Math.round((1f - a) * 8f) : 0;
        wy = (height - wh) / 2 + rise;

        float base = Theme.fade;
        Theme.fade = base * a;
        try {
            Theme.shadow(ctx, Theme.SH_WINDOW, wx, wy, wx + ww, wy + wh, 0x90000000);
            Theme.round(ctx, wx, wy, wx + ww, wy + wh, Theme.BG, Theme.R_WIN);
            Theme.stroke(ctx, wx, wy, wx + ww, wy + wh, Theme.BORDER, Theme.R_WIN);
            Theme.round(ctx, wx + 1, wy + 1, wx + SIDE, wy + wh - 1, Theme.SIDEBAR, Theme.R_WIN - 1, Theme.TL | Theme.BL);
            Theme.fill(ctx, wx + SIDE, wy + 1, wx + SIDE + 1, wy + wh - 1, Theme.BORDER);
            renderSidebar(ctx, mx, my, dt);
            renderContent(ctx, mx, my, dt);
        } finally {
            Theme.fade = base;
        }
    }

    private void renderSidebar(DrawContext ctx, int mx, int my, float dt) {
        UiFont.draw(ctx, textRenderer, "Quick Play", wx + 14, wy + 12, Theme.TEXT, Role.TITLE, smooth);
        UiFont.draw(ctx, textRenderer, "Hypixel games", wx + 14, wy + 28, Theme.TEXT_DIM, Role.SMALL, smooth);
        Theme.fill(ctx, wx + 12, wy + 42, wx + SIDE - 12, wy + 43, Theme.DIVIDER);

        int y = wy + 50;
        int selY = y;
        for (Game g : QuickPlay.GAMES) {
            if (y + NAV_H > wy + wh - 6) break;
            if (g == game) selY = y;
            y += NAV_H;
        }
        // The selection highlight glides between games.
        float hy = anims ? navSpring.step(selY, 26f, 1f, dt) : selY;
        if (!anims) navSpring.set(selY);
        int hyI = Math.round(hy);
        Theme.roundBordered(ctx, wx + 8, hyI, wx + SIDE - 8, hyI + NAV_H - 2, Theme.tint(0.11f), Theme.alpha(Theme.RED, 0.35f));

        y = wy + 50;
        for (Game g : QuickPlay.GAMES) {
            if (y + NAV_H > wy + wh - 6) break;
            boolean hv = mx >= wx + 8 && mx < wx + SIDE - 8 && my >= y && my < y + NAV_H - 2;
            float h = spring("nav:" + g.name(), hv && g != game ? 1 : 0, dt);
            if (h > 0.01f) Theme.round(ctx, wx + 8, y, wx + SIDE - 8, y + NAV_H - 2, Theme.alpha(Theme.ROW_HOVER, h));
            Theme.disc(ctx, wx + 16, y + 6, 8, g.color());
            int c = g == game ? Theme.TEXT : Theme.lerpColor(Theme.TEXT_SOFT, Theme.TEXT, h);
            UiFont.draw(ctx, textRenderer, g.name(), wx + 30, y + 6, c, Role.BODY, smooth);
            hits.add(new Hit(wx + 8, y, wx + SIDE - 8, y + NAV_H - 2, null, g, false));
            y += NAV_H;
        }
    }

    private void renderContent(DrawContext ctx, int mx, int my, float dt) {
        QuickPlay qp = QuickPlay.instance();
        boolean on = QuickPlay.available();
        int cx = wx + SIDE + 16, cw = ww - SIDE - 32;
        int top = wy + 12;

        // Status pill, top right.
        String status = on ? "Connected to Hypixel" : "Not on Hypixel";
        int sw = UiFont.width(textRenderer, status, Role.SMALL, smooth) + 18;
        int sx = wx + ww - 14 - sw;
        Theme.roundBordered(ctx, sx, top, sx + sw, top + 14, Theme.CONTROL, Theme.BORDER);
        Theme.disc(ctx, sx + 6, top + 5, 4, on ? Theme.OK : Theme.DANGER);
        UiFont.draw(ctx, textRenderer, status, sx + 13, top + 4, on ? Theme.TEXT_SOFT : Theme.DANGER, Role.SMALL, smooth);

        UiFont.draw(ctx, textRenderer, game.name(), cx, top + 1, Theme.TEXT, Role.TITLE, smooth);
        String sub = on ? "Click to play. Right click to pin." : "Join Hypixel to play. You can still pin modes.";
        UiFont.draw(ctx, textRenderer, sub, cx, top + 17, Theme.TEXT_DIM, Role.SMALL, smooth);

        int viewTop = wy + 46, viewBottom = wy + wh - 10;
        viewHeight = viewBottom - viewTop;
        scroll = anims ? stepScroll(dt) : scrollTarget;
        int y = viewTop - Math.round(scroll);
        ctx.enableScissor(cx - 4, viewTop, cx + cw + 4, viewBottom);
        try {
            List<Mode> favs = qp == null ? List.of() : qp.favourites();
            if (!favs.isEmpty()) y = chipRow(ctx, "Favourites", favs, cx, cw, y, mx, my, dt, on, viewTop, viewBottom);
            List<Mode> recent = qp == null || !qp.showRecent() ? List.of() : qp.recent();
            if (!recent.isEmpty()) y = chipRow(ctx, "Recent", recent, cx, cw, y, mx, my, dt, on, viewTop, viewBottom);

            UiFont.caps(ctx, textRenderer, "Modes", cx, y + 2, Theme.TEXT_HEADER, Role.CAPS, 1f, smooth);
            y += 14;
            int cols = Math.max(1, (cw + GAP) / (CARD_MIN_W + GAP));
            int cardW = (cw - GAP * (cols - 1)) / cols;
            List<Mode> modes = game.modes();
            for (int i = 0; i < modes.size(); i++) {
                int col = i % cols, row = i / cols;
                int x1 = cx + col * (cardW + GAP), y1 = y + row * (CARD_H + GAP);
                card(ctx, modes.get(i), x1, y1, cardW, mx, my, dt, on, qp, viewTop, viewBottom);
            }
            int rows = (modes.size() + cols - 1) / cols;
            y += rows * (CARD_H + GAP);
            contentHeight = y + Math.round(scroll) - viewTop;
        } finally {
            ctx.disableScissor();
        }
        // Scroll hint: a thin bar when the content is taller than the view.
        int max = maxScroll();
        if (max > 0) {
            int barH = Math.max(16, viewHeight * viewHeight / Math.max(1, contentHeight));
            int barY = viewTop + Math.round((viewHeight - barH) * (scroll / max));
            Theme.round(ctx, wx + ww - 6, barY, wx + ww - 4, barY + barH, Theme.alpha(Theme.TEXT_FAINT, 0.7f), 1);
        }
    }

    private int chipRow(DrawContext ctx, String title, List<Mode> modes, int cx, int cw, int y, int mx, int my,
                        float dt, boolean on, int viewTop, int viewBottom) {
        UiFont.caps(ctx, textRenderer, title, cx, y + 2, Theme.TEXT_HEADER, Role.CAPS, 1f, smooth);
        y += 14;
        int x = cx;
        for (Mode m : modes) {
            String label = m.fullName();
            int w = UiFont.width(textRenderer, label, Role.LABEL, smooth) + 22;
            if (x + w > cx + cw && x > cx) { x = cx; y += CHIP_H + 4; }
            boolean visible = y + CHIP_H > viewTop && y < viewBottom;
            boolean hv = visible && on && mx >= x && mx < x + w && my >= y && my < y + CHIP_H && my >= viewTop && my < viewBottom;
            float h = spring("chip:" + title + ":" + m.command(), hv ? 1 : 0, dt);
            Theme.roundBordered(ctx, x, y, x + w, y + CHIP_H,
                    Theme.lerpColor(Theme.CONTROL, Theme.CONTROL_HOVER, h), Theme.lerpColor(Theme.BORDER, Theme.BORDER_HOVER, h));
            Theme.disc(ctx, x + 7, y + 7, 5, colorOf(m));
            UiFont.draw(ctx, textRenderer, label, x + 16, y + 5, on ? Theme.TEXT : Theme.TEXT_DIM, Role.LABEL, smooth);
            if (visible) hits.add(new Hit(x, Math.max(y, viewTop), x + w, Math.min(y + CHIP_H, viewBottom), m, null, false));
            x += w + 4;
        }
        return y + CHIP_H + 10;
    }

    private void card(DrawContext ctx, Mode m, int x, int y, int w, int mx, int my, float dt, boolean on,
                      QuickPlay qp, int viewTop, int viewBottom) {
        boolean visible = y + CARD_H > viewTop && y < viewBottom;
        if (!visible) return;
        boolean inView = my >= viewTop && my < viewBottom;
        boolean hv = inView && mx >= x && mx < x + w && my >= y && my < y + CARD_H;
        float h = spring("card:" + m.command(), hv ? 1 : 0, dt);
        if (h > 0.01f) Theme.shadow(ctx, Theme.SH_CARD, x, y, x + w, y + CARD_H, Theme.alpha(0xFF000000, 0.35f * h));
        int fill = Theme.lerpColor(Theme.CARD, Theme.CARD_HOVER, h);
        int border = Theme.lerpColor(Theme.BORDER, on ? Theme.alpha(Theme.RED, 0.8f) : Theme.BORDER_HOVER, h);
        Theme.roundBordered(ctx, x, y, x + w, y + CARD_H, fill, border);
        // Accent edge in the game's colour.
        Theme.round(ctx, x, y + 4, x + 2, y + CARD_H - 4, Theme.alpha(game.color(), on ? 1f : 0.45f), 1);

        UiFont.draw(ctx, textRenderer, m.label(), x + 10, y + 8, on ? Theme.TEXT : Theme.TEXT_DIM, Role.STRONG, smooth);
        UiFont.draw(ctx, textRenderer, "/" + m.command(), x + 10, y + 23, Theme.TEXT_FAINT, Role.TINY, smooth);

        // Pin star in the top right corner (pixel font: the smooth font has no star glyph).
        boolean fav = qp != null && qp.isFavourite(m);
        int starX = x + w - 14, starY = y + 5;
        boolean starHv = hv && mx >= starX - 3 && mx < starX + 11 && my >= starY - 3 && my < starY + 11;
        int starC = fav ? Theme.WARN : starHv ? Theme.TEXT_SOFT : Theme.alpha(Theme.TEXT_FAINT, 0.4f + 0.6f * h);
        ctx.drawText(textRenderer, Text.literal(fav ? "★" : "☆"), starX, starY, Theme.fa(starC), false);

        int y1 = Math.max(y, viewTop), y2 = Math.min(y + CARD_H, viewBottom);
        hits.add(new Hit(starX - 3, Math.max(starY - 3, viewTop), starX + 11, Math.min(starY + 11, viewBottom), m, null, true));
        hits.add(new Hit(x, y1, x + w, y2, m, null, false));
    }

    private int colorOf(Mode m) {
        for (Game g : QuickPlay.GAMES) if (g.name().equals(m.game())) return g.color();
        return Theme.TEXT_DIM;
    }

    private float spring(String key, float target, float dt) {
        if (!anims) return target;
        return Motion.clamp01(hover.computeIfAbsent(key, k -> new Motion.Spring(target)).step(target, 22f, 1f, dt));
    }

    private int maxScroll() { return Math.max(0, contentHeight - viewHeight); }

    private final Motion.Spring scrollSpring = new Motion.Spring(0);

    private float stepScroll(float dt) {
        return scrollSpring.step(scrollTarget, 20f, 1f, dt);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;
        double x = click.x(), y = click.y();
        int button = click.button();
        QuickPlay qp = QuickPlay.instance();
        for (Hit h : hits) {
            if (!h.contains(x, y)) continue;
            if (h.game() != null) {
                if (button == 0 && h.game() != game) {
                    game = h.game();
                    scrollTarget = 0;
                    if (qp != null) qp.setLastGame(game.name());
                }
                return true;
            }
            if (qp == null || h.mode() == null) return true;
            if (button == 1 || h.star()) {
                boolean now = qp.toggleFavourite(h.mode());
                Notifications.push("Quick Play", (now ? "Pinned " : "Unpinned ") + h.mode().fullName(), now);
                return true;
            }
            if (button == 0) {
                if (!QuickPlay.available()) {
                    Notifications.push("Quick Play", "Only works on Hypixel", false);
                    return true;
                }
                if (qp.play(h.mode()) && qp.closeOnPick() && client != null) client.setScreen(null);
                return true;
            }
            return true;
        }
        // A click outside the window closes the picker.
        if (button == 0 && (x < wx || x >= wx + ww || y < wy || y >= wy + wh)) {
            close();
            return true;
        }
        return false;
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
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            int i = QuickPlay.GAMES.indexOf(game) + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1);
            int n = QuickPlay.GAMES.size();
            game = QuickPlay.GAMES.get((i % n + n) % n);
            scrollTarget = 0;
            QuickPlay qp = QuickPlay.instance();
            if (qp != null) qp.setLastGame(game.name());
            return true;
        }
        return super.keyPressed(input);
    }
}
