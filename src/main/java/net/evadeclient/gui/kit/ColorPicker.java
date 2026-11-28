package net.evadeclient.gui.kit;

import net.evadeclient.render.ui.UiShape;
import net.evadeclient.util.ColorHistory;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;

import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * The colour picker popover (spec 6.16): an e4 popover 232 wide with a 200 x 120 saturation / value area (r5, a
 * 1 device px inner border, a 14 px ring handle showing the colour), hue and alpha strips (200 x 10 pills with the
 * same handles), the hex value in tabular digits, 12 theme swatches and 8 recent colours (dashed placeholders while
 * empty). {@link #swatch} is the trigger: a small chip of the colour. Gradients are dithered by the shape shader.
 */
public final class ColorPicker {
    private ColorPicker() {}

    public static final int W = 232, SV_W = 200, SV_H = 120, STRIP_H = 10;
    /** The 12 theme swatches. */
    public static final int[] SWATCHES = {0xFFE11D2E, 0xFFF97316, 0xFFEAB308, 0xFF22C55E, 0xFF06B6D4, 0xFF2F7BFF,
            0xFF8B5CF6, 0xFFEC4899, 0xFFFFFFFF, 0xFFB4B8C1, 0xFF444955, 0xFF000000};

    /** A colour chip trigger (r3, a hairline, a checkerboard under translucent colours). Clicking opens the popover. */
    public static void swatch(Kit k, String id, int x, int y, int w, int h, int argb, IntConsumer onChange) {
        boolean open = id.equals(k.popup());
        int st = k.state(id, x, y, x + w, y + h, true);
        if ((argb >>> 24) < 255) checker(k, x, y, x + w, y + h);
        Paint.rect(k.ctx, x, y, x + w, y + h, Tokens.R_XS, argb);
        Paint.ring(k.ctx, x, y, x + w, y + h, Tokens.R_XS, Theme.alpha(0xFFFFFFFF, (st & Kit.HOVER) != 0 ? 0.3f : 0.12f), 1);
        if ((st & Kit.FOCUSED) != 0) Paint.focusRing(k, x, y, x + w, y + h, Tokens.R_XS, Tokens.FOCUS_RING);
        k.hit(id, x, y, x + w, y + h, () -> k.popup(open ? null : id));
        if (open) k.overlay(() -> {
            int px = Math.max(4, Math.min(k.screenW() - W - 4, x)), py = y + h + 6;
            if (py + height() > k.screenH() - 4) py = Math.max(4, y - height() - 6);
            popover(k, id + "#pop", px, py, argb, onChange);
        });
    }

    /** The popover's height. */
    public static int height() { return 16 + SV_H + 10 + STRIP_H + 8 + STRIP_H + 12 + 16 + 10 + 14 + 6 + 14 + 16; }

    /** The popover at (x, y): picking anywhere calls {@code onChange} with the new colour. */
    public static void popover(Kit k, String id, int x, int y, int argb, IntConsumer onChange) {
        int h = height();
        k.barrier(id + "#bg", x, y, x + W, y + h, null);
        Paint.surface(k.ctx, x, y, x + W, y + h, Tokens.R_MD, Tokens.E4, 0, Float.NaN);
        float[] hsv = java.awt.Color.RGBtoHSB(argb >> 16 & 255, argb >> 8 & 255, argb & 255, null);
        // A grey colour has no hue of its own: keep the one the user was on.
        float hue = hsv[1] < 0.01f ? k.mem(id + "#hue", hsv[0]) : hsv[0];
        k.remember(id + "#hue", hue);
        float alpha = (argb >>> 24) / 255f;
        int sx = x + 16, sy = y + 16;
        int pure = 0xFF000000 | java.awt.Color.HSBtoRGB(hue, 1, 1);
        // SV area: white → hue across, then transparent → black down.
        Paint.hgradient(k.ctx, sx, sy, sx + SV_W, sy + SV_H, Tokens.R_SM, 0xFFFFFFFF, pure);
        Paint.vgradient(k.ctx, sx, sy, sx + SV_W, sy + SV_H, Tokens.R_SM, 0x00000000, 0xFF000000);
        Paint.ring(k.ctx, sx, sy, sx + SV_W, sy + SV_H, Tokens.R_SM, Theme.alpha(0xFFFFFFFF, 0.08f), 1);
        handle(k, sx + hsv[1] * SV_W, sy + (1 - hsv[2]) * SV_H, argb | 0xFF000000);
        String sv = id + "#sv";
        final int fsx = sx, fsy = sy;
        k.hit(sv, sx, sy, sx + SV_W, sy + SV_H, (b, mx, my) -> {}, false);
        k.onDrag(sv, (mx, my) -> {
            float s = (float) Math.max(0, Math.min(1, (mx - fsx) / SV_W)), v = (float) Math.max(0, Math.min(1, 1 - (my - fsy) / SV_H));
            onChange.accept((Math.round(alpha * 255) << 24) | (java.awt.Color.HSBtoRGB(hue, s, v) & 0xFFFFFF));
        });
        // Hue strip: six dithered segments around the colour wheel.
        int hy = sy + SV_H + 10;
        for (int i = 0; i < 6; i++) {
            int c0 = 0xFF000000 | java.awt.Color.HSBtoRGB(i / 6f, 1, 1), c1 = 0xFF000000 | java.awt.Color.HSBtoRGB((i + 1) / 6f, 1, 1);
            float a0 = sx + SV_W * i / 6f, a1 = sx + SV_W * (i + 1) / 6f;
            if (Paint.gpu()) {
                float rl = i == 0 ? STRIP_H / 2f : 0, rr = i == 5 ? STRIP_H / 2f : 0;
                UiShape.shape(a0, hy, a1 + (i < 5 ? 0.02f : 0), hy + STRIP_H).radii(rl, rr, rr, rl).horizontal(c0, c1).draw(k.ctx);
            } else {
                Theme.fill(k.ctx, Math.round(a0), hy, Math.round(a1), hy + STRIP_H, Theme.lerpColor(c0, c1, 0.5f));
            }
        }
        handle(k, sx + hue * SV_W, hy + STRIP_H / 2f, pure);
        String hs = id + "#hue";
        k.hit(hs, sx, hy - 2, sx + SV_W, hy + STRIP_H + 2, (b, mx, my) -> {}, false);
        k.onDrag(hs, (mx, my) -> {
            float nh = (float) Math.max(0, Math.min(0.9999, (mx - fsx) / SV_W));
            k.remember(id + "#hue", nh);
            onChange.accept((argb & 0xFF000000) | (java.awt.Color.HSBtoRGB(nh, Math.max(hsv[1], 0.0001f), hsv[2]) & 0xFFFFFF));
        });
        // Alpha strip over a checkerboard.
        int ay = hy + STRIP_H + 8;
        checker(k, sx, ay, sx + SV_W, ay + STRIP_H);
        Paint.hgradient(k.ctx, sx, ay, sx + SV_W, ay + STRIP_H, STRIP_H / 2f, argb & 0xFFFFFF, argb | 0xFF000000);
        handle(k, sx + alpha * SV_W, ay + STRIP_H / 2f, argb);
        String as = id + "#alpha";
        k.hit(as, sx, ay - 2, sx + SV_W, ay + STRIP_H + 2, (b, mx, my) -> {}, false);
        k.onDrag(as, (mx, my) -> {
            int na = (int) Math.round(Math.max(0, Math.min(1, (mx - fsx) / SV_W)) * 255);
            onChange.accept(na << 24 | (argb & 0xFFFFFF));
        });
        // Hex value (tabular) and a preview chip.
        int ty = ay + STRIP_H + 12;
        String hex = String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF) + (alpha < 1 ? String.format(Locale.ROOT, "  %d%%", Math.round(alpha * 100)) : "");
        Paint.sunken(k.ctx, sx, ty, sx + 120, ty + 16, Tokens.R_XS);
        UiFont.drawTabular(k.ctx, k.tr, hex, sx + 6, Math.round(UiFont.centredY(Role.LABEL, ty + 8, k.smooth)), Tokens.N900, Role.LABEL, k.smooth);
        checker(k, sx + SV_W - 40, ty, sx + SV_W, ty + 16);
        Paint.rect(k.ctx, sx + SV_W - 40, ty, sx + SV_W, ty + 16, Tokens.R_XS, argb);
        // Swatches, then recents (dashed placeholders while empty).
        int wy = ty + 16 + 10;
        for (int i = 0; i < SWATCHES.length; i++) {
            int c = SWATCHES[i], cx = sx + i * 17;
            chip(k, id + "#s" + i, cx, wy, c, (c | 0xFF000000) == (argb | 0xFF000000), onChange, alpha);
        }
        int ry = wy + 14 + 6;
        List<Integer> recent = ColorHistory.get();
        for (int i = 0; i < 8; i++) {
            int cx = sx + i * 17;
            if (i < recent.size()) chip(k, id + "#r" + i, cx, ry, recent.get(i), false, onChange, -1);
            else Paint.dashed(k.ctx, cx, ry, cx + 14, ry + 14, Tokens.N500);
        }
    }

    private static void chip(Kit k, String id, int x, int y, int c, boolean selected, IntConsumer onChange, float keepAlpha) {
        Paint.rect(k.ctx, x, y, x + 14, y + 14, Tokens.R_XS, c | (keepAlpha < 0 ? 0 : 0xFF000000));
        Paint.ring(k.ctx, x, y, x + 14, y + 14, Tokens.R_XS, Theme.alpha(0xFFFFFFFF, k.hovered(id) ? 0.35f : 0.1f), 1);
        if (selected) Paint.ring(k.ctx, x - 2, y - 2, x + 16, y + 16, Tokens.R_XS + 2, Tokens.A400, 2);
        int alphaBits = keepAlpha < 0 ? (c & 0xFF000000) : Math.round(keepAlpha * 255) << 24;
        k.hit(id, x, y, x + 14, y + 14, (b, mx, my) -> { if (b == 0) onChange.accept(alphaBits | (c & 0xFFFFFF)); }, false);
    }

    /** A 14 px ring handle: 2 px white over a 1 px dark outline, filled with {@code fill}. */
    private static void handle(Kit k, float cx, float cy, int fill) {
        float r = 7;
        Paint.circle(k.ctx, cx, cy, 2 * r + 2, Theme.alpha(0xFF000000, 0.5f));
        Paint.circle(k.ctx, cx, cy, 2 * r, 0xFFFFFFFF);
        Paint.circle(k.ctx, cx, cy, 2 * r - 4, fill | 0xFF000000);
    }

    private static void checker(Kit k, int x1, int y1, int x2, int y2) {
        Theme.fill(k.ctx, x1, y1, x2, y2, 0xFFB0B0B0);
        for (int y = y1; y < y2; y += 4)
            for (int x = x1 + ((y - y1) / 4 % 2) * 4; x < x2; x += 8)
                Theme.fill(k.ctx, x, y, Math.min(x + 4, x2), Math.min(y + 4, y2), 0xFF808080);
    }
}
