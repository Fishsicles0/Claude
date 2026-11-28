package net.evadeclient.gui.kit;

import net.evadeclient.gui.Motion;
import net.evadeclient.render.ui.UiShape;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;

/**
 * Progress (spec 6.17). Linear: a 4 px sunken pill track, an accent 700 → 500 gradient fill and an emissive head (a
 * small additive glow at the leading edge); indeterminate sweeps a 30% segment across every 1.2 s (progress keeps
 * moving under Reduced motion: it is functional). Circular: a round-capped 2 device px ring arc. Labels and the
 * percentage use tabular digits.
 */
public final class Progress {
    private Progress() {}

    /**
     * A linear bar at (x, y), {@code w} wide. {@code value} 0..1, or NaN for indeterminate. With {@code label} the
     * label sits above on the left and the percentage on the right (12 px taller).
     */
    public static void linear(Kit k, int x, int y, int w, float value, String label) {
        int ty = y;
        if (label != null) {
            k.text(label, x, ty, Tokens.N800, Role.SMALL);
            if (!Float.isNaN(value)) {
                String pct = Math.round(Motion.clamp01(value) * 100) + "%";
                int pw = UiFont.tabularWidth(k.tr, pct, Role.SMALL, k.smooth);
                UiFont.drawTabular(k.ctx, k.tr, pct, x + w - pw, ty, Tokens.N700, Role.SMALL, k.smooth);
            }
            y += 12;
        }
        Paint.sunken(k.ctx, x, y, x + w, y + 4, 2);
        float a, b;
        if (Float.isNaN(value)) {
            float t = Motion.standard((k.now % 1200) / 1200f);
            a = x + (w * 1.3f) * t - w * 0.3f;
            b = a + w * 0.3f;
            a = Math.max(x, a);
            b = Math.min(x + w, b);
        } else {
            a = x;
            b = x + w * Motion.clamp01(value);
        }
        if (b - a < 0.5f) return;
        Paint.hgradient(k.ctx, a, y, b, y + 4, 2, Tokens.A700, Tokens.A500);
        Paint.glow(k, b - 3, y, b + 1, y + 4, 2, 3, Theme.alpha(Tokens.A500, 0.55f));
        if (Paint.gpu()) UiShape.shape(b - 2, y + 0.5f, b, y + 3.5f).pill().fill(Theme.alpha(Tokens.A200, 0.9f)).draw(k.ctx);
    }

    /** A circular progress ring centred on (cx, cy), radius {@code r}; NaN spins a quarter arc. */
    public static void circle(Kit k, float cx, float cy, float r, float value) {
        float th = Math.max(2f / Theme.deviceScale(k.ctx), 2f);
        if (!Paint.gpu()) { // fallback: a track and a bar under it
            Paint.ring(k.ctx, cx - r, cy - r, cx + r, cy + r, r, Tokens.N250, 2);
            if (!Float.isNaN(value)) Theme.fill(k.ctx, Math.round(cx - r), Math.round(cy + r + 2), Math.round(cx - r + 2 * r * Motion.clamp01(value)), Math.round(cy + r + 3), Tokens.A500);
            return;
        }
        UiShape.arc(k.ctx, cx, cy, r, th, 0, 360, Tokens.N250);
        if (Float.isNaN(value)) {
            float start = (k.now % 1000) / 1000f * 360f;
            UiShape.arc(k.ctx, cx, cy, r, th, start, 90, Tokens.A500);
        } else if (value > 0) {
            UiShape.arc(k.ctx, cx, cy, r, th, 0, 360 * Motion.clamp01(value), Tokens.A500);
        }
    }
}
