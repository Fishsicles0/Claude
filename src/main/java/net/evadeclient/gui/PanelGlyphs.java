package net.evadeclient.gui;

import net.evadeclient.gui.kit.Paint;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;

/**
 * Small vector glyphs the panels need that the icon set doesn't have yet (folder, mirror, layers, lock, copy, grid,
 * alignment, undo / redo, duplicate, help), drawn from rounded strokes and rings on the kit's shape layer so they
 * stay crisp at any GUI scale. They follow the icon grid: 24 units, a 20-unit live area, strokes about 1.75 units
 * with round caps. {@link #draw} also accepts any {@link Icons} name, so callers can mix both.
 */
public final class PanelGlyphs {
    private PanelGlyphs() {}

    /**
     * A square ghost icon button (24 or 28) showing a glyph or an {@link Icons} icon, with a kit tooltip.
     * {@code on}: switched on (accent surface, accent glyph). {@code action} gets the mouse button.
     */
    public static void button(net.evadeclient.gui.kit.Kit kit, String id, int x, int y, int size, String glyph, String tip,
                              boolean enabled, boolean on, java.util.function.IntConsumer action) {
        int st = kit.state(id, x, y, x + size, y + size, enabled);
        boolean dis = (st & net.evadeclient.gui.kit.Kit.DISABLED) != 0, press = (st & net.evadeclient.gui.kit.Kit.PRESSED) != 0;
        float hov = kit.anim(id + "#h", (st & net.evadeclient.gui.kit.Kit.HOVER) != 0 ? 1f : 0f);
        int T = net.evadeclient.util.Tokens.N250;
        if (on) {
            Paint.rect(kit.ctx, x, y, x + size, y + size, net.evadeclient.util.Tokens.R_SM,
                    Theme.lerpColor(net.evadeclient.util.Tokens.ACCENT_SURFACE, net.evadeclient.util.Tokens.N300, 0.3f * hov));
            Paint.ring(kit.ctx, x, y, x + size, y + size, net.evadeclient.util.Tokens.R_SM, Theme.alpha(net.evadeclient.util.Tokens.A500, 0.45f), 1);
        } else if (!dis && (hov > 0.01f || press)) {
            Paint.rect(kit.ctx, x, y, x + size, y + size, net.evadeclient.util.Tokens.R_SM, press ? net.evadeclient.util.Tokens.N200 : Theme.alpha(T, 0.8f * hov));
        }
        int is = Math.round(size * 0.58f);
        int col = dis ? net.evadeclient.util.Tokens.N600 : on ? net.evadeclient.util.Tokens.A400
                : Theme.lerpColor(net.evadeclient.util.Tokens.N800, net.evadeclient.util.Tokens.N950, hov);
        float dy = press ? 0.5f / Theme.deviceScale(kit.ctx) : 0f;
        draw(kit.ctx, glyph, x + (size - is) / 2f, y + (size - is) / 2f + dy, is, col);
        if ((st & net.evadeclient.gui.kit.Kit.FOCUSED) != 0)
            Paint.focusRing(kit, x, y, x + size, y + size, net.evadeclient.util.Tokens.R_SM, net.evadeclient.util.Tokens.FOCUS_RING);
        if (enabled) kit.hit(id, x, y, x + size, y + size, (b, px, py) -> action.accept(b), true);
        if (tip != null) kit.tooltip(id, tip, null, x, y, x + size, y + size);
    }

    /** Whether {@code name} is one of these glyphs (otherwise it is an icon from the icon set). */
    public static boolean has(String name) {
        return switch (name) {
            case "folder", "mirror", "layers", "lock", "unlock", "copy", "grid", "undo", "redo", "rotate_cw", "rotate_ccw",
                 "align_left", "align_hcenter", "align_right", "align_top", "align_vcenter", "align_bottom",
                 "dist_h", "dist_v", "play", "pause", "skip_start", "chev_up", "chev_down", "help", "trash", "eye_off", "list", "gauge", "sparkle" -> true;
            default -> false;
        };
    }

    /** Draws {@code name} in a {@code size} px box at (x, y). */
    public static void draw(DrawContext ctx, String name, float x, float y, float size, int color) {
        if (!has(name)) { Icons.draw(ctx, name, Math.round(x), Math.round(y), Math.round(size), color); return; }
        float u = size / 24f, w = Math.max(1.75f * u, 1f);
        G g = new G(ctx, x, y, u, w, color);
        switch (name) {
            case "folder" -> {
                g.poly(3, 7, 3, 18, 21, 18, 21, 9, 12, 9, 10, 6.5f, 4, 6.5f, 3, 7);
            }
            case "mirror" -> {
                for (float yy = 3; yy < 21; yy += 4) g.line(12, yy, 12, yy + 2);
                g.poly(9, 6, 3, 17, 9, 17, 9, 6);
                g.poly(15, 6, 21, 17, 15, 17, 15, 6);
            }
            case "layers" -> {
                g.poly(12, 4, 21, 8.5f, 12, 13, 3, 8.5f, 12, 4);
                g.poly(3, 12.5f, 12, 17, 21, 12.5f);
                g.poly(3, 16.5f, 12, 21, 21, 16.5f);
            }
            case "lock", "unlock" -> {
                g.box(5, 11, 19, 20.5f, 2.5f);
                if (name.equals("lock")) g.arc(12, 11, 4.5f, 180, 180);
                else { g.arc(12, 8, 4.5f, 180, 150); g.line(7.5f, 8, 7.5f, 11); }
                g.dot(12, 15.5f, 2.2f);
            }
            case "copy" -> {
                g.box(8.5f, 8.5f, 20, 20, 2.5f);
                g.poly(15.5f, 5.5f, 15.5f, 4, 4, 4, 4, 15.5f, 5.5f, 15.5f);
            }
            case "grid" -> {
                g.box(4, 4, 20, 20, 2.5f);
                g.line(9.5f, 4.5f, 9.5f, 19.5f);
                g.line(14.5f, 4.5f, 14.5f, 19.5f);
                g.line(4.5f, 9.5f, 19.5f, 9.5f);
                g.line(4.5f, 14.5f, 19.5f, 14.5f);
            }
            case "undo", "rotate_ccw" -> {
                g.arc(12.5f, 13, 7, 200, 250);
                g.poly(4, 6.5f, 5.5f, 12, 11, 10.5f);
            }
            case "redo", "rotate_cw" -> {
                g.arc(11.5f, 13, 7, 90, 250);
                g.poly(20, 6.5f, 18.5f, 12, 13, 10.5f);
            }
            case "align_left" -> { g.line(4, 3, 4, 21); g.bar(7, 6, 19, 10.5f); g.bar(7, 13.5f, 14, 18); }
            case "align_right" -> { g.line(20, 3, 20, 21); g.bar(5, 6, 17, 10.5f); g.bar(10, 13.5f, 17, 18); }
            case "align_hcenter" -> { g.line(12, 3, 12, 21); g.bar(5, 6, 19, 10.5f); g.bar(8, 13.5f, 16, 18); }
            case "align_top" -> { g.line(3, 4, 21, 4); g.bar(6, 7, 10.5f, 19); g.bar(13.5f, 7, 18, 14); }
            case "align_bottom" -> { g.line(3, 20, 21, 20); g.bar(6, 5, 10.5f, 17); g.bar(13.5f, 10, 18, 17); }
            case "align_vcenter" -> { g.line(3, 12, 21, 12); g.bar(6, 5, 10.5f, 19); g.bar(13.5f, 8, 18, 16); }
            case "dist_h" -> { g.line(4, 4, 4, 20); g.line(20, 4, 20, 20); g.bar(9.5f, 7, 14.5f, 17); }
            case "dist_v" -> { g.line(4, 4, 20, 4); g.line(4, 20, 20, 20); g.bar(7, 9.5f, 17, 14.5f); }
            case "play" -> g.poly(8, 5, 19, 12, 8, 19, 8, 5);
            case "pause" -> { g.bar(7, 5, 10.5f, 19); g.bar(13.5f, 5, 17, 19); }
            case "skip_start" -> { g.bar(5, 5, 7.5f, 19); g.poly(18, 5, 10, 12, 18, 19, 18, 5); }
            case "chev_up" -> g.poly(6, 15, 12, 9, 18, 15);
            case "chev_down" -> g.poly(6, 9, 12, 15, 18, 9);
            case "help" -> {
                g.arc(12, 12, 9, 0, 360);
                g.arc(12, 9.5f, 2.8f, 160, 250);
                g.line(12, 12.4f, 12, 13.6f);
                g.dot(12, 17, 2.2f);
            }
            case "trash" -> {
                g.line(4, 6.5f, 20, 6.5f);
                g.poly(9.5f, 6.5f, 9.5f, 4, 14.5f, 4, 14.5f, 6.5f);
                g.poly(6, 6.5f, 7, 20.5f, 17, 20.5f, 18, 6.5f);
                g.line(10.5f, 10, 10.5f, 17);
                g.line(13.5f, 10, 13.5f, 17);
            }
            case "eye_off" -> {
                g.arc(12, 19, 11, 215, 110);
                g.arc(12, 5, 11, 35, 110);
                g.arc(12, 12, 3, 0, 360);
                g.line(4, 4, 20, 20);
            }
            case "list" -> {
                for (int i = 0; i < 3; i++) { g.dot(5, 6.5f + i * 5.5f, 2.4f); g.line(9, 6.5f + i * 5.5f, 20, 6.5f + i * 5.5f); }
            }
            case "gauge" -> {
                g.arc(12, 14, 8.5f, 150, 240);
                g.line(12, 14, 16.5f, 9.5f);
                g.dot(12, 14, 3);
            }
            case "sparkle" -> {
                g.poly(12, 3, 14, 10, 21, 12, 14, 14, 12, 21, 10, 14, 3, 12, 10, 10, 12, 3);
            }
            default -> { }
        }
    }

    /** Stroke drawing in glyph units (24 per glyph) at (x, y). */
    private record G(DrawContext ctx, float x, float y, float u, float w, int color) {
        float px(float v) { return x + v * u; }
        float py(float v) { return y + v * u; }

        /** A round-capped stroke from (x1, y1) to (x2, y2). */
        void line(float x1, float y1, float x2, float y2) {
            float ax = px(x1), ay = py(y1), bx = px(x2), by = py(y2);
            float dx = bx - ax, dy = by - ay, len = (float) Math.sqrt(dx * dx + dy * dy);
            var m = ctx.getMatrices();
            m.pushMatrix();
            try {
                m.translate(ax, ay);
                m.rotate((float) Math.atan2(dy, dx));
                Paint.rect(ctx, -w / 2, -w / 2, len + w / 2, w / 2, w / 2, color);
            } finally {
                m.popMatrix();
            }
        }

        /** A polyline through the points (x0, y0, x1, y1, ...). */
        void poly(float... p) {
            for (int i = 0; i + 3 < p.length; i += 2) line(p[i], p[i + 1], p[i + 2], p[i + 3]);
        }

        /** A rounded box outline. */
        void box(float x1, float y1, float x2, float y2, float r) {
            Paint.ring(ctx, px(x1) - w / 2, py(y1) - w / 2, px(x2) + w / 2, py(y2) + w / 2, r * u + w / 2, color,
                    w * Theme.deviceScale(ctx));
        }

        /** A filled rounded bar (alignment blocks). */
        void bar(float x1, float y1, float x2, float y2) {
            Paint.rect(ctx, px(x1), py(y1), px(x2), py(y2), 1.2f * u, color);
        }

        void dot(float cx, float cy, float d) { Paint.circle(ctx, px(cx), py(cy), d * u, color); }

        /** An arc of radius {@code r} from {@code start} degrees (0 = 3 o'clock, clockwise) sweeping {@code sweep}. */
        void arc(float cx, float cy, float r, float start, float sweep) {
            if (Paint.gpu()) {
                net.evadeclient.render.ui.UiShape.arc(ctx, px(cx), py(cy), r * u + w / 2, w, (start + 90) % 360, sweep, color);
                return;
            }
            int n = Math.max(6, Math.round(Math.abs(sweep) / 20f));
            float prevX = 0, prevY = 0;
            for (int i = 0; i <= n; i++) {
                double a = Math.toRadians(start + sweep * i / n);
                float ex = cx + (float) Math.cos(a) * r, ey = cy + (float) Math.sin(a) * r;
                if (i > 0) line(prevX, prevY, ex, ey);
                prevX = ex;
                prevY = ey;
            }
        }
    }
}
