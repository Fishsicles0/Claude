package net.evadeclient.gui.kit;

import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import org.lwjgl.glfw.GLFW;

import java.util.function.DoubleConsumer;

/**
 * The slider (spec 6.4): a 4 px sunken pill track with an accent gradient fill that follows the knob exactly, a white
 * 12 px knob (14 on hover, 16 while dragging, with a 2 device px accent ring), a value bubble above while dragging,
 * optional tick marks for 10 or fewer steps, and the value in tabular digits in a fixed 40 px column on the right.
 * Arrow keys step it while focused.
 */
public final class Slider {
    private Slider() {}

    /** Width of the value column drawn right of the track (0 when {@code valueText} is null). */
    public static final int VALUE_W = 40;

    /**
     * A slider whose track spans (x, y)-(x + w, y + 16); with {@code valueText} the value column takes the last
     * {@link #VALUE_W} px of {@code w}. {@code step} 0 = continuous. {@code onChange} gets snapped, clamped values.
     */
    public static void draw(Kit k, String id, int x, int y, int w, double value, double min, double max, double step,
                            String valueText, boolean enabled, DoubleConsumer onChange) {
        int h = 16, tw = valueText != null ? w - VALUE_W - Tokens.S3 : w;
        int st = k.state(id, x, y, x + tw, y + h, enabled);
        boolean dis = (st & Kit.DISABLED) != 0, drag = (st & Kit.PRESSED) != 0;
        float hov = k.anim(id + "#h", (st & Kit.HOVER) != 0 ? 1f : 0f);
        float grab = k.anim(id + "#d", drag ? 1f : 0f);
        double span = max - min;
        float frac = span <= 0 ? 0f : (float) Math.max(0, Math.min(1, (value - min) / span));
        float cy = y + h / 2f, tx1 = x + 6, tx2 = x + tw - 6;
        float kx = tx1 + (tx2 - tx1) * frac;
        float f = Theme.fade;
        if (dis) Theme.fade = f * 0.4f;
        try {
            Paint.sunken(k.ctx, tx1 - 2, cy - 2, tx2 + 2, cy + 2, 2);
            if (frac > 0) Paint.hgradient(k.ctx, tx1 - 2, cy - 2, kx, cy + 2, 2, Tokens.A600, Tokens.A500);
            int steps = step > 0 && span > 0 ? (int) Math.round(span / step) : 0;
            if (steps >= 2 && steps <= 10)
                for (int i = 0; i <= steps; i++) {
                    float tx = tx1 + (tx2 - tx1) * i / steps;
                    Theme.fill(k.ctx, Math.round(tx), Math.round(cy + 4), Math.round(tx) + 1, Math.round(cy + 6), Tokens.N500);
                }
            float d = 12 + 2 * hov + 2 * grab;
            if (Paint.gpu())
                net.evadeclient.render.ui.UiShape.shape(kx - d / 2, cy - d / 2, kx + d / 2, cy + d / 2).radius(d / 2)
                        .fill(Theme.alpha(0xFF000000, 0.4f)).asShadow(1f, 0.8f, false).draw(k.ctx);
            Paint.circle(k.ctx, kx, cy, d, 0xFFFFFFFF);
            if (grab > 0.01f) Paint.ring(k.ctx, kx - d / 2, cy - d / 2, kx + d / 2, cy + d / 2, d / 2, Theme.alpha(Tokens.A500, grab), 2);
            if (grab > 0.01f && valueText != null) bubble(k, kx, cy - d / 2 - 4, valueText, grab);
            if (valueText != null) {
                int vx = x + w - VALUE_W;
                Paint.sunken(k.ctx, vx, y, x + w, y + h, Tokens.R_XS);
                int vw = UiFont.tabularWidth(k.tr, valueText, Role.LABEL, k.smooth);
                float vy = UiFont.centredY(Role.LABEL, cy, k.smooth);
                UiFont.drawTabular(k.ctx, k.tr, valueText, vx + (VALUE_W - vw) / 2, Math.round(vy), Tokens.N900, Role.LABEL, k.smooth);
            }
        } finally {
            Theme.fade = f;
        }
        if ((st & Kit.FOCUSED) != 0) Paint.focusRing(k, kx - 7, cy - 7, kx + 7, cy + 7, 7, Tokens.FOCUS_RING);
        if (!enabled) return;
        k.hit(id, x, y, x + tw, y + h, (b, mx, my) -> {}, true);
        final float a = tx1, b2 = tx2;
        k.onDrag(id, (mx, my) -> onChange.accept(snap(min + (max - min) * Math.max(0, Math.min(1, (mx - a) / (b2 - a))), min, max, step)));
        double inc = step > 0 ? step : span / 100.0;
        k.onKey(id, (key, mods) -> {
            if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_DOWN) { onChange.accept(snap(value - inc, min, max, step)); return true; }
            if (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_UP) { onChange.accept(snap(value + inc, min, max, step)); return true; }
            if (key == GLFW.GLFW_KEY_HOME) { onChange.accept(min); return true; }
            if (key == GLFW.GLFW_KEY_END) { onChange.accept(max); return true; }
            return false;
        });
    }

    private static double snap(double v, double min, double max, double step) {
        if (step > 0) v = min + Math.round((v - min) / step) * step;
        return Math.max(min, Math.min(max, v));
    }

    /** The value bubble (e4, tabular) centred on x with its bottom at {@code bottom}. */
    static void bubble(Kit k, float x, float bottom, String text, float a) {
        int tw = UiFont.tabularWidth(k.tr, text, Role.TINY, k.smooth) + 10, th = 14;
        float x1 = x - tw / 2f, y1 = bottom - th - 2 * (1 - a);
        float f = Theme.fade;
        Theme.fade = f * a;
        try {
            Paint.surface(k.ctx, x1, y1, x1 + tw, y1 + th, Tokens.R_XS, Tokens.E4.noAmbient(), 0, Float.NaN);
            float ty = UiFont.centredY(Role.TINY, y1 + th / 2f, k.smooth);
            UiFont.drawTabular(k.ctx, k.tr, text, Math.round(x1 + 5), Math.round(ty), Tokens.N950, Role.TINY, k.smooth);
        } finally {
            Theme.fade = f;
        }
    }
}
