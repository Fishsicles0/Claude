package net.evadeclient.gui.menu;

import net.evadeclient.gui.Motion;
import net.evadeclient.gui.kit.Kit;
import net.evadeclient.gui.kit.Scrollbar;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.minecraft.client.gui.DrawContext;

/**
 * The scroll of a menu page: where the content is drawn ({@link #pos}, gliding on a critically damped spring so
 * wheel notches in a row blend into one glide and it never runs past an end) and where it is headed
 * ({@link #target}). Layout and hit tests use whole pixels ({@link #px}); the fraction under a pixel is a
 * device-pixel translation while drawing ({@link #push}), so a glide is smooth at any scale and text stays crisp.
 */
public final class MenuScroll {
    private static final Motion.SpringToken GLIDE = new Motion.SpringToken(28f, 1f);
    private final Motion.Spring spring = new Motion.Spring(0f);
    public double pos, target;
    private int max;

    /** Step the glide one frame. */
    public void frame(float dt) {
        pos = Motion.spring(spring, (float) target, GLIDE, dt);
        if (Math.abs(pos - target) < 0.01) pos = target;
    }

    /** The content is {@code contentH} tall in a {@code viewH} view: clamp the target to it. */
    public void clamp(int contentH, int viewH) {
        max = Math.max(0, contentH - viewH);
        target = Math.max(0, Math.min(target, max));
    }

    public int max() { return max; }

    public int px() { return (int) Math.floor(pos); }

    /** Move by a wheel amount (notches); returns whether it could move. */
    public boolean wheel(double v) {
        double was = target;
        target = Math.max(0, Math.min(max, target - v * 36));
        return was != target;
    }

    /** Jump to {@code to} (clamped) with a glide. */
    public void to(double to) { target = Math.max(0, Math.min(max, to)); }

    /** Jump to the top at once (a new page, filter or search). */
    public void reset() { pos = target = 0; spring.set(0); }

    /** Put the end at once (dragging the thumb: 1:1, no glide). */
    public void jump(double to) { pos = target = Math.max(0, Math.min(max, to)); spring.set((float) pos); }

    /** Push the sub-pixel part of the scroll as an upward translation (after enableScissor); pop it after. */
    public void push(DrawContext ctx) {
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(0, -Theme.snap(ctx, (float) (pos - px())));
    }

    public void pop(DrawContext ctx) { ctx.getMatrices().popMatrix(); }

    /** The overlay scrollbar at the view's right edge {@code x}, and the soft fades at the clipped ends. */
    public void chrome(Kit k, String id, int x1, int y1, int x2, int y2) {
        Scrollbar.edgeFades(k, x1, y1, x2, y2, Theme.alpha(Tokens.N100, 0.96f), pos > 1, pos < max - 1);
        Scrollbar.draw(k, id, x2 + 8, y1 + 2, y2 - 2, (float) pos, max, this::jump);
    }
}
