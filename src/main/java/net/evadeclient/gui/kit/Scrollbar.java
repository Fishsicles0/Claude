package net.evadeclient.gui.kit;

import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;

import java.util.function.Consumer;

/**
 * The overlay scrollbar (spec 6.14): 4 px wide (6 on hover), a white 18% thumb (32% hovered or dragged), no track
 * until hovered (then white 4%), hidden when the content fits, and fading out 800 ms after scrolling stops. Never
 * red. {@link #edgeFades} fades the top and bottom 16 px of a scrolling region into its background.
 */
public final class Scrollbar {
    private Scrollbar() {}

    /**
     * The scrollbar for a region from y1 to y2 at the right edge x (the bar's right side). {@code scroll} and
     * {@code max} are in px; {@code onScroll} gets the new scroll while the thumb is dragged.
     */
    public static void draw(Kit k, String id, int x, int y1, int y2, float scroll, float max, Consumer<Float> onScroll) {
        if (max <= 0.5f) return;
        float last = k.mem(id + "#scroll", Float.NaN);
        if (last != scroll) { k.remember(id + "#scroll", scroll); k.remember(id + "#moved", k.now); }
        long moved = k.mem(id + "#moved", k.now);
        int st = k.state(id, x - 10, y1, x, y2, true);
        boolean hot = (st & (Kit.HOVER | Kit.PRESSED)) != 0;
        float vis = k.anim(id + "#v", hot || k.now - moved < 800 ? 1f : 0f);
        float wide = k.anim(id + "#w", hot ? 1f : 0f);
        if (vis < 0.01f && !hot) { register(k, id, x, y1, y2, max, onScroll); return; }
        int h = y2 - y1;
        float viewFrac = h / (h + max);
        float th = Math.max(20, h * viewFrac);
        float ty = y1 + (h - th) * (scroll / max);
        float bw = 4 + 2 * wide;
        float f = Theme.fade;
        Theme.fade = f * vis;
        try {
            if (wide > 0.01f) Paint.pill(k.ctx, x - bw - 2, y1, x - 2, y2, Theme.alpha(0xFFFFFFFF, 0.04f * wide));
            Paint.pill(k.ctx, x - bw - 2, ty, x - 2, ty + th, Theme.alpha(0xFFFFFFFF, 0.18f + 0.14f * Math.max(wide, (st & Kit.PRESSED) != 0 ? 1 : 0)));
        } finally {
            Theme.fade = f;
        }
        register(k, id, x, y1, y2, max, onScroll);
    }

    private static void register(Kit k, String id, int x, int y1, int y2, float max, Consumer<Float> onScroll) {
        k.hit(id, x - 10, y1, x, y2, (b, mx, my) -> {}, false);
        k.onDrag(id, (mx, my) -> onScroll.accept((float) Math.max(0, Math.min(max, (my - y1) / (double) (y2 - y1) * (max + (y2 - y1)) - (y2 - y1) / 2.0))));
    }

    /** Fade the top and bottom {@code 16} px of (x1, y1)-(x2, y2) into {@code bg} (draw after the content). */
    public static void edgeFades(Kit k, int x1, int y1, int x2, int y2, int bg, boolean top, boolean bottom) {
        int clear = bg & 0x00FFFFFF;
        if (top) Paint.vgradient(k.ctx, x1, y1, x2, y1 + 16, 0, bg, clear);
        if (bottom) Paint.vgradient(k.ctx, x1, y2 - 16, x2, y2, 0, clear, bg);
    }

    /** The default fade colour behind content on a window body. */
    public static int windowBg() { return Tokens.N100; }
}
