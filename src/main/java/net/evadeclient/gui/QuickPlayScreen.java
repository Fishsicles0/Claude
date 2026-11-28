package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.kit.Badge;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.Kit;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.Scrollbar;
import net.evadeclient.gui.kit.Sidebar;
import net.evadeclient.gui.kit.Surface;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.module.misc.QuickPlay;
import net.evadeclient.module.misc.QuickPlay.Game;
import net.evadeclient.module.misc.QuickPlay.Mode;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The Quick Play picker on the kit: games in a sidebar (the selection glides), the selected game's modes as cards on
 * the right, with rows of pinned favourites and recently played modes above them. Left click a card or chip to play
 * it (one command per click), right click (or click its star) to pin or unpin it. Off Hypixel the cards are dimmed
 * and clicking one only says so.
 */
public final class QuickPlayScreen extends Screen {
    private static final int SIDE = 156, CARD_H = 52, CARD_MIN_W = 132, GAP = 8;

    private final Screen parent;
    private final Kit kit = new Kit();
    private Game game;
    private long openedAt;
    private float scroll, scrollTarget;
    private int contentHeight, viewHeight;

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
        if (openedAt == 0) openedAt = System.currentTimeMillis();
    }

    @Override public boolean shouldPause() { return false; }

    @Override public void close() { if (client != null) client.setScreen(parent); }

    // ------------------------------------------------------------------ drawing

    private float fade() {
        Motion.Level l = Motion.level();
        if (l == Motion.Level.OFF) return 1f;
        return Motion.clamp01((System.currentTimeMillis() - openedAt) / (float) (l == Motion.Level.REDUCED ? Motion.DUR_REDUCED_MAX : Motion.DUR_QUICK));
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ClientSettings s = settings();
        if (s != null) s.applyTheme();
        if (s == null || s.blur.get()) super.renderBackground(ctx, mouseX, mouseY, delta);
        Paint.scrim(ctx, width, height, fade());
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        kit.begin(ctx, mx, my);
        ww = Math.min(680, width - 20);
        wh = Math.min(420, height - 20);
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;
        float rise = Motion.decorative() ? Motion.standard(Motion.clamp01((System.currentTimeMillis() - openedAt) / (float) Motion.DUR_SLOW)) : 1f;
        float base = Theme.fade;
        Theme.fade = base * fade();
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            if (rise < 1f) {
                float sc = 0.97f + 0.03f * rise;
                m.translate(width / 2f, height / 2f);
                m.scale(sc, sc);
                m.translate(-width / 2f, -height / 2f + Theme.snap(ctx, (1f - rise) * 10f));
            }
            // Anything outside the window closes the picker.
            kit.barrier("outside", 0, 0, width, height, this::close);
            kit.barrier("window", wx, wy, wx + ww, wy + wh, null);
            Surface.window(kit, wx, wy, wx + ww, wy + wh);
            // The sidebar: a darker inset panel along the left.
            Paint.rect(ctx, wx + 6, wy + 6, wx + SIDE, wy + wh - 6, Tokens.R_MD, Theme.alpha(Tokens.N50, 0.7f));
            renderSidebar(ctx);
            renderContent(ctx, mx, my);
            kit.end();
        } finally {
            m.popMatrix();
            Theme.fade = base;
        }
    }

    private void renderSidebar(DrawContext ctx) {
        int x = wx + 12, w = SIDE - 18;
        kit.text("Quick Play", x + 4, wy + 16, Tokens.N950, Role.HEADLINE);
        kit.text("Hypixel games", x + 4, wy + 38, Tokens.N700, Role.SMALL);
        int y = wy + 58;
        Sidebar.highlight(kit, "games", x, w);
        for (Game g : QuickPlay.GAMES) {
            if (y + Sidebar.ITEM_H > wy + wh - 8) break;
            Game gg = g;
            Sidebar.item(kit, "games", "g:" + g.name(), x, y, w, null, g.name(), String.valueOf(g.modes().size()), g == game, false, () -> pick(gg));
            // The game's colour as a lit dot where the icon goes.
            Paint.circle(ctx, x + 18, y + Sidebar.ITEM_H / 2f, g == game ? 9 : 7, g.color() | 0xFF000000);
            if (g == game) Paint.glow(kit, x + 13, y + 9, x + 23, y + 19, 5, 4, Theme.alpha(g.color() | 0xFF000000, 0.35f));
            y += Sidebar.ITEM_H;
        }
    }

    private void pick(Game g) {
        if (g == game) return;
        game = g;
        scrollTarget = 0;
        QuickPlay qp = QuickPlay.instance();
        if (qp != null) qp.setLastGame(game.name());
    }

    private void renderContent(DrawContext ctx, int mx, int my) {
        QuickPlay qp = QuickPlay.instance();
        boolean on = QuickPlay.available();
        int cx = wx + SIDE + 18, cw = ww - SIDE - 36;
        int top = wy + 16;

        // Status, top right: icon plus words (never colour alone).
        String status = on ? "On Hypixel" : "Not on Hypixel";
        int sw = Badge.width(kit, status) + 14;
        int sx = wx + ww - 18 - sw;
        Paint.pill(ctx, sx, top + 2, sx + sw, top + 16, Theme.alpha(on ? Tokens.OK : Tokens.WARN, 0.14f));
        Icons.draw(ctx, on ? "check" : "warn", sx + 4, top + 4, 10, on ? Tokens.OK : Tokens.WARN);
        kit.textCentredY(status, sx + 17, top + 9, on ? Tokens.OK : Tokens.WARN, Role.TINY);

        // Game header: a colour tile, the name and the hint.
        Paint.rect(ctx, cx, top, cx + 30, top + 30, Tokens.R_SM, Theme.lerpColor(Tokens.N250, game.color() | 0xFF000000, 0.25f));
        Paint.ring(ctx, cx, top, cx + 30, top + 30, Tokens.R_SM, Theme.alpha(game.color() | 0xFF000000, 0.5f), 1);
        String tag = game.tag() == null || game.tag().isEmpty() ? game.name().substring(0, 1) : game.tag();
        kit.textCentredY(tag, cx + 15 - kit.width(tag, Role.STRONG) / 2, top + 15, Tokens.N950, Role.STRONG);
        kit.text(game.name(), cx + 40, top + 1, Tokens.N950, Role.TITLE);
        kit.text(on ? "Click to play · right-click or the star to pin" : "Join Hypixel to play · you can still pin modes", cx + 40, top + 18, Tokens.N700, Role.SMALL);
        Paint.fadeLine(ctx, cx - 4, top + 40, cx + cw + 4, Theme.alpha(0xFFFFFFFF, 0.06f));

        int viewTop = top + 48, viewBottom = wy + wh - 12;
        viewHeight = viewBottom - viewTop;
        float target = Math.max(0, Math.min(maxScroll(), scrollTarget));
        scroll = kit.anim("scroll", target, Motion.SMOOTH);
        int y = viewTop - Math.round(scroll);
        ctx.enableScissor(cx - 6, viewTop, cx + cw + 6, viewBottom);
        kit.clipHits(cx - 6, viewTop, cx + cw + 6, viewBottom);
        try {
            List<Mode> favs = qp == null ? List.of() : qp.favourites();
            if (!favs.isEmpty()) y = chipRow(ctx, "FAVOURITES", favs, cx, cw, y, on);
            List<Mode> recent = qp == null || !qp.showRecent() ? List.of() : qp.recent();
            if (!recent.isEmpty()) y = chipRow(ctx, "RECENT", recent, cx, cw, y, on);

            y = Surface.section(kit, "MODES", String.valueOf(game.modes().size()), cx, y);
            int cols = Math.max(1, (cw + GAP) / (CARD_MIN_W + GAP));
            int cardW = (cw - GAP * (cols - 1)) / cols;
            List<Mode> modes = game.modes();
            for (int i = 0; i < modes.size(); i++) {
                int col = i % cols, row = i / cols;
                int x1 = cx + col * (cardW + GAP), y1 = y + row * (CARD_H + GAP);
                if (y1 + CARD_H > viewTop && y1 < viewBottom) card(ctx, modes.get(i), x1, y1, cardW, on, qp, i);
            }
            int rows = (modes.size() + cols - 1) / cols;
            y += rows * (CARD_H + GAP);
            contentHeight = y + Math.round(scroll) - viewTop;
        } finally {
            kit.unclipHits();
            ctx.disableScissor();
        }
        int max = maxScroll();
        if (max > 0) {
            Scrollbar.edgeFades(kit, cx - 6, viewTop, cx + cw + 6, viewBottom, Theme.alpha(Tokens.N100, 0.85f), scroll > 1, scroll < max - 1);
            Scrollbar.draw(kit, "bar", wx + ww - 4, viewTop, viewBottom, scroll, max, v -> { scrollTarget = v; kit.snapAnim("scroll", v); });
        }
    }

    private int chipRow(DrawContext ctx, String title, List<Mode> modes, int cx, int cw, int y, boolean on) {
        y = Surface.section(kit, title, null, cx, y);
        int x = cx;
        for (Mode m : modes) {
            String label = m.fullName();
            int w = Button.width(kit, label, Button.Size.SM, null, false) + 10;
            if (x + w > cx + cw && x > cx) { x = cx; y += Button.Size.SM.h + 4; }
            String id = "chip:" + title + ":" + m.command();
            Button.draw(kit, id, x, y, w, Button.Size.SM, Button.Variant.SECONDARY, "  " + label, null, true, () -> {});
            Paint.circle(ctx, x + 10, y + 10, 6, colorOf(m) | 0xFF000000);
            kit.hit(id, x, y, x + w, y + Button.Size.SM.h, (b, px, py) -> clickMode(m, b, false), true);
            x += w + 4;
        }
        return y + Button.Size.SM.h + Tokens.S5;
    }

    private void card(DrawContext ctx, Mode m, int x, int y, int w, boolean on, QuickPlay qp, int index) {
        String id = "card:" + m.command();
        float fd = Theme.fade;
        if (!on) Theme.fade = fd * 0.6f;
        try {
            Surface.card(kit, id, x, y, x + w, y + CARD_H, false, on, null);
            // The game's colour as an edge light, leaning with the Evade slant.
            Paint.slant(ctx, x + 1, y + 10, x + 3, y + CARD_H - 10, game.color() | 0xFF000000);
            kit.text(kit.ellipsize(m.label(), Role.STRONG, w - 36), x + 12, y + 12, Tokens.N950, Role.STRONG);
            kit.text("/" + m.command(), x + 12, y + 30, Tokens.N600, Role.SMALL);
            if (on && kit.hovered(id)) Icons.draw(ctx, "chevron_r", x + w - 16, y + CARD_H - 18, 8, Tokens.N800);
        } finally {
            Surface.endCard(kit);
            Theme.fade = fd;
        }
        kit.hit(id, x, y, x + w, y + CARD_H, (b, px, py) -> clickMode(m, b, false), true);
        // Pin star, top right.
        boolean fav = qp != null && qp.isFavourite(m);
        String sid = id + "#star";
        int sx = x + w - 22, sy = y + 6;
        boolean hot = kit.hovered(sid);
        if (hot) Paint.rect(ctx, sx, sy, sx + 16, sy + 16, Tokens.R_XS, Theme.alpha(Tokens.N300, 0.8f));
        Icons.draw(ctx, "star", sx + 2, sy + 2, 12, fav ? Tokens.WARN : hot ? Tokens.N900 : Tokens.N600);
        kit.hit(sid, sx, sy, sx + 16, sy + 16, (b, px, py) -> clickMode(m, b, true), false);
        kit.tooltip(sid, fav ? "Unpin" : "Pin to favourites", null, sx, sy, sx + 16, sy + 16);
    }

    private void clickMode(Mode m, int button, boolean star) {
        QuickPlay qp = QuickPlay.instance();
        if (qp == null) return;
        if (button == 1 || star) {
            boolean now = qp.toggleFavourite(m);
            Notifications.push("Quick Play", (now ? "Pinned " : "Unpinned ") + m.fullName(), now);
            return;
        }
        if (button != 0) return;
        if (!QuickPlay.available()) {
            Notifications.push("Quick Play", "Only works on Hypixel", false);
            return;
        }
        if (qp.play(m) && qp.closeOnPick() && client != null) client.setScreen(null);
    }

    private int colorOf(Mode m) {
        for (Game g : QuickPlay.GAMES) if (g.name().equals(m.game())) return g.color();
        return Tokens.N700;
    }

    private int maxScroll() { return Math.max(0, contentHeight - viewHeight); }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        return kit.mouseClicked(click.x(), click.y(), click.button()) || super.mouseClicked(click, doubled);
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
        scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget - (float) v * 28f));
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            int i = QuickPlay.GAMES.indexOf(game) + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1);
            int n = QuickPlay.GAMES.size();
            pick(QuickPlay.GAMES.get((i % n + n) % n));
            return true;
        }
        if (key != GLFW.GLFW_KEY_ESCAPE && kit.keyPressed(key, input.modifiers())) return true;
        return super.keyPressed(input);
    }
}
