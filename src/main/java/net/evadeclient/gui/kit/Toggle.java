package net.evadeclient.gui.kit;

import net.evadeclient.gui.Motion;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;

/**
 * The on/off switch (spec 6.3): lg 36 x 18 or md 28 x 14. Off: a sunken track with an inner shade and an n800 knob
 * with a contact shadow. On: an accent track with a light top rim, a white knob and a soft glow under it. The knob
 * slides on a snappy spring; the halo blooms and settles. The switch carries the state: the row label never turns red.
 */
public final class Toggle {
    private Toggle() {}

    public static final int LG_W = 36, LG_H = 18, MD_W = 28, MD_H = 14;

    /** A switch at (x, y). {@code large}: 36 x 18, else 28 x 14. Clicking (or Enter / Space) calls {@code onToggle}. */
    public static void draw(Kit k, String id, int x, int y, boolean large, boolean on, boolean enabled, Runnable onToggle) {
        int w = large ? LG_W : MD_W, h = large ? LG_H : MD_H;
        int st = k.state(id, x, y, x + w, y + h, enabled);
        boolean dis = (st & Kit.DISABLED) != 0;
        float t = k.anim(id + "#t", on ? 1f : 0f);
        float hov = k.anim(id + "#h", (st & Kit.HOVER) != 0 ? 1f : 0f);
        float halo = k.anim(id + "#g", on ? 0.6f : 0f, Motion.SMOOTH);
        float f = Theme.fade;
        if (dis) Theme.fade = f * 0.4f;
        try {
            float r = h / 2f;
            // Track: sunken when off, accent when on (crossfaded).
            if (t < 0.999f) Paint.surface(k.ctx, x, y, x + w, y + h, r, Tokens.SUNKEN.withFill(Theme.lerpColor(Tokens.N150, Tokens.N250, 0.35f * hov)), 0, Float.NaN);
            if (t > 0.001f) {
                if (Paint.gpu()) {
                    net.evadeclient.render.ui.UiShape.shape(x, y, x + w, y + h).radius(r)
                            .fill(Theme.alpha(Theme.lerpColor(Tokens.A600, Tokens.A500, hov), t))
                            .rim(0.22f * t).draw(k.ctx);
                } else {
                    Paint.pill(k.ctx, x, y, x + w, y + h, Theme.alpha(Tokens.A600, t));
                }
            }
            // Knob.
            float pad = large ? 2f : 1.5f, d = h - 2 * pad;
            float kx = x + pad + (w - h) * t, ky = y + pad;
            float kxs = Theme.snap(k.ctx, kx);
            if (halo > 0.01f && !dis) Paint.glow(k, kxs, ky, kxs + d, ky + d, d / 2, large ? 4 : 3, Theme.alpha(Tokens.A600, 0.5f * halo));
            if (Paint.gpu())
                net.evadeclient.render.ui.UiShape.shape(kxs, ky, kxs + d, ky + d).radius(d / 2).fill(Theme.alpha(0xFF000000, 0.35f))
                        .asShadow(0.8f, 0.6f, false).draw(k.ctx);
            Paint.circle(k.ctx, kxs + d / 2, ky + d / 2, d, Theme.lerpColor(Tokens.N800, 0xFFFFFFFF, t));
        } finally {
            Theme.fade = f;
        }
        if ((st & Kit.FOCUSED) != 0) Paint.focusRing(k, x, y, x + w, y + h, h / 2f, Tokens.FOCUS_RING);
        if (enabled) k.hit(id, x, y, x + w, y + h, onToggle);
    }
}
