package net.evadeclient.util;

import net.evadeclient.gui.Motion;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Evade's toasts (GUI 3.0, spec 6.12), e.g. "Zoom enabled" when you use a keybind. Each toast is a 260 x 44 e4 card
 * (up to 320 wide) with a status icon in a tinted circle, the title in STRONG n950, the body in SMALL n800 (never
 * red) and a time hairline in the status colour along the bottom. Always the smooth font (the pixel font in the
 * Classic theme). In game they stack at the middle of the right edge; while a menu is open they stack from the
 * top-right corner, below the screen's close button. At most three show, with a "+N more" chip; the same title and
 * body pushed again within 3 seconds merges into the showing toast with a ×N count and restarts its timer. Hovering
 * a toast (in a menu) pauses its timer. Safe to push from any thread.
 */
public final class Notifications {
    /** The toast's status: neutral info, good news, a warning, or an error. */
    public enum Level { INFO, SUCCESS, WARN, ERROR }

    private static final class Toast {
        final String title, text;
        final Level level;
        final long life;
        long start, paused;
        int count = 1;
        float y = Float.NaN;

        Toast(String title, String text, Level level, long start, long life) {
            this.title = title; this.text = text; this.level = level; this.start = start; this.life = life;
        }

        long age(long now) { return now - start - paused; }
    }

    private static final List<Toast> toasts = new ArrayList<>();
    private static final long LIFE = 2200, MERGE_MS = 3000;
    /** Longest a toast may stay up (server notifications ask for their own time). */
    public static final long MAX_LIFE = 15_000;
    private static final int W = 260, MAX_W = 320, H = 44, GAP = 6, VISIBLE = 3, KEEP = 8;
    private static long lastFrame;
    /** A screen whose own chrome sits in the top-right corner (the Evade menu's close button) sets the GUI y the toasts
     *  start below while it is open, and puts it back to -1 when it closes. */
    public static int screenTopInset = -1;

    private Notifications() {}

    public static void push(String title, String text, boolean good) {
        push(title, text, good ? Level.SUCCESS : Level.INFO, LIFE);
    }

    /** A toast with its own level and time on screen ({@code lifeMs}, kept between 0.5 and 15 seconds). */
    public static void push(String title, String text, Level level, long lifeMs) {
        long life = Math.max(500, Math.min(MAX_LIFE, lifeMs));
        String ti = title == null ? "" : title, te = text == null ? "" : text;
        Level lv = level == null ? Level.INFO : level;
        long now = System.currentTimeMillis();
        synchronized (toasts) {
            for (Toast t : toasts) {
                if (t.title.equals(ti) && t.text.equals(te) && t.level == lv && now - t.start < MERGE_MS + t.paused) {
                    t.count++;
                    t.start = now; // restart its timer
                    t.paused = 0;
                    return;
                }
            }
            toasts.add(new Toast(ti, te, lv, now, life));
            while (toasts.size() > KEEP) toasts.removeFirst();
        }
    }

    /** The toasts showing now, as "title|body|count" (tests). */
    public static List<String> snapshot() {
        synchronized (toasts) {
            List<String> out = new ArrayList<>();
            for (Toast t : toasts) out.add(t.title + "|" + t.text + "|" + t.count);
            return out;
        }
    }

    /** Remove every toast (tests). */
    public static void clear() {
        synchronized (toasts) { toasts.clear(); }
    }

    /** Draws the live toasts in HUD units (see {@link net.evadeclient.hud.Hud#scale()}). */
    public static void render(DrawContext ctx) {
        long now = System.currentTimeMillis();
        long frameDt = lastFrame == 0 ? 0 : Math.min(100, now - lastFrame);
        lastFrame = now;
        List<Toast> live;
        synchronized (toasts) {
            toasts.removeIf(t -> t.age(now) > t.life);
            if (toasts.isEmpty()) return;
            live = new ArrayList<>(toasts);
        }
        // The built-in clip recorder films the finished frame: keep toasts out of it unless the player opted in
        // ("Show Overlays In Clips"). Clips shows its own state in the window title meanwhile. Toasts still expire
        // on time above, so none pops up stale once recording stops.
        if (net.evadeclient.module.misc.Clips.hideOverlays()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        Tokens.refresh();
        boolean smooth = !Theme.square;
        float k = net.evadeclient.hud.Hud.scale();
        int sw = Math.round(ctx.getScaledWindowWidth() / k), sh = Math.round(ctx.getScaledWindowHeight() / k);
        int maxW = Math.max(120, Math.min(MAX_W, sw - 16));
        boolean menu = mc.currentScreen != null;
        // Below a screen's header close button when one is open (Evade screens put it in the top-right corner).
        int top = menu ? (mc.currentScreen.getClass().getName().startsWith("net.evadeclient.") ? 8 + 36 : 8) : sh / 2 - 40;
        if (menu && screenTopInset >= 0) top = Math.max(top, Math.round(screenTopInset / k));
        double mx = mc.mouse.getX() * ctx.getScaledWindowWidth() / Math.max(1, mc.getWindow().getWidth()) / k;
        double my = mc.mouse.getY() * ctx.getScaledWindowHeight() / Math.max(1, mc.getWindow().getHeight()) / k;
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.scale(k, k);
        float fade = Theme.fade;
        try {
            int shown = Math.min(VISIBLE, live.size());
            float y = top;
            for (int i = live.size() - shown; i < live.size(); i++) { // newest at the bottom of the stack
                Toast t = live.get(i);
                long age = t.age(now);
                int dIn = Motion.duration(Motion.DUR_BASE), dOut = Motion.duration(Math.round(Motion.DUR_BASE * 0.75f));
                float in = dIn == 0 ? 1 : Motion.standard(Math.min(1, age / (float) dIn));
                float out = dOut == 0 ? (age < t.life ? 1 : 0) : Motion.clamp01((t.life - age) / (float) dOut);
                String title = t.count > 1 ? t.title + "  ×" + t.count : t.title;
                int w = Math.min(maxW, Math.max(W, 56 + Math.max(UiFont.width(tr, title, Role.STRONG, smooth), UiFont.width(tr, t.text, Role.SMALL, smooth)) + 14));
                // Reflow: each toast glides to its slot (a spring would need per-toast state; an exponential ease is
                // frame-rate independent and settles in about 200 ms).
                if (Float.isNaN(t.y)) t.y = y;
                else t.y += (y - t.y) * (Motion.level() == Motion.Level.OFF ? 1f : 1f - (float) Math.exp(-frameDt / 60.0));
                float slide = Motion.decorative() ? (1 - in) * 24 : 0;
                float exitUp = Motion.decorative() ? (1 - out) * -8 : 0;
                float vis = Math.min(in, out);
                float x = sw - 8 - w + slide;
                float ty = t.y + exitUp;
                boolean hover = menu && mx >= x && mx < x + w && my >= ty && my < ty + H;
                if (hover) t.paused += frameDt;
                Theme.fade = fade * vis;
                m.pushMatrix();
                m.translate(Theme.snap(ctx, x), Theme.snap(ctx, ty));
                try {
                    drawToast(ctx, tr, smooth, t, title, w, now);
                } finally {
                    m.popMatrix();
                }
                y += (H + GAP) * Math.max(0.0f, out);
            }
            if (live.size() > shown) {
                Theme.fade = fade;
                String more = "+" + (live.size() - shown) + " more";
                int cw = UiFont.width(tr, more, Role.TINY, smooth) + 12;
                float cx = sw - 8 - cw, cy = y;
                Paint.pill(ctx, cx, cy, cx + cw, cy + 14, Theme.alpha(Tokens.N300, 0.94f));
                UiFont.draw(ctx, tr, more, Math.round(cx + 6), Math.round(UiFont.centredY(Role.TINY, cy + 7, smooth)), Tokens.N800, Role.TINY, smooth);
            }
        } finally {
            Theme.fade = fade;
            m.popMatrix();
        }
    }

    /** One toast drawn at (x, y), {@code w} wide, without the queue (the kit gallery and screenshots). */
    public static void preview(DrawContext ctx, int x, int y, int w, Level level, String title, String body, int count) {
        Tokens.refresh();
        Toast t = new Toast(title, body, level, System.currentTimeMillis() - 700, 2200);
        t.count = count;
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(x, y);
        try {
            drawToast(ctx, MinecraftClient.getInstance().textRenderer, !Theme.square, t, count > 1 ? title + "  \u00D7" + count : title, w,
                    System.currentTimeMillis());
        } finally {
            m.popMatrix();
        }
    }

    private static void drawToast(DrawContext ctx, TextRenderer tr, boolean smooth, Toast t, String title, int w, long now) {
        int status = switch (t.level) {
            case SUCCESS -> Tokens.OK;
            case WARN -> Tokens.WARN;
            case ERROR -> Tokens.DANGER;
            default -> Tokens.INFO;
        };
        String icon = switch (t.level) {
            case SUCCESS -> "check";
            case WARN -> "warn";
            case ERROR -> "error";
            default -> "info";
        };
        Paint.surface(ctx, 0, 0, w, H, Tokens.R_MD, Tokens.E4.withFill(Theme.alpha(Tokens.N300, 0.94f)).noAmbient(), 0, Float.NaN);
        // Leading icon in a status-tinted circle.
        Paint.circle(ctx, 8 + 14, H / 2f, 28, Theme.alpha(status, 0.16f));
        Icons.draw(ctx, icon, 8 + 14 - 8, Math.round(H / 2f - 8), 16, status);
        int tx = 8 + 28 + 10, maxText = w - tx - 10;
        boolean body = !t.text.isEmpty();
        float titleY = body ? H / 2f - 6 : H / 2f, bodyY = H / 2f + 7;
        UiFont.draw(ctx, tr, fit(tr, title, Role.STRONG, smooth, maxText), tx, Math.round(UiFont.centredY(Role.STRONG, titleY, smooth)), Tokens.N950, Role.STRONG, smooth);
        if (body) UiFont.draw(ctx, tr, fit(tr, t.text, Role.SMALL, smooth, maxText), tx, Math.round(UiFont.centredY(Role.SMALL, bodyY, smooth)), Tokens.N800, Role.SMALL, smooth);
        // Time hairline along the bottom, inside the rounded corners.
        float remaining = Math.max(0f, 1f - t.age(now) / (float) t.life);
        float hw = (w - 2 * Tokens.R_MD) * remaining;
        if (hw > 0.5f) Paint.rect(ctx, Tokens.R_MD, H - 2, Tokens.R_MD + hw, H - 1, 0.5f, Theme.alpha(status, 0.6f));
    }

    /** {@code s} cut to {@code width} pixels, ending in an ellipsis when it had to be shortened. */
    private static String fit(TextRenderer tr, String s, Role r, boolean smooth, int width) {
        if (UiFont.width(tr, s, r, smooth) <= width) return s;
        String e = "…";
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (UiFont.width(tr, s.substring(0, mid).stripTrailing() + e, r, smooth) <= width) lo = mid; else hi = mid - 1;
        }
        return s.substring(0, lo).stripTrailing() + e;
    }
}
