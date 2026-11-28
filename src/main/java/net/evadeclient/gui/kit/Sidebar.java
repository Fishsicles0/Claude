package net.evadeclient.gui.kit;

import net.evadeclient.gui.Motion;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;

/**
 * Sidebar navigation (spec 6.8): 28 px items with a 16 px icon, a BODY label and an optional neutral count. Hover is
 * a faint n250 fill; the selected item gets a highlight that glides between items on a smooth spring, filled with
 * the accent surface gradient (light entering from the left), a 2 device px slanted accent edge on the left, the icon
 * in accent and the label n950. No outline. Notifications show as a small accent dot, never a red box.
 *
 * <p>Call {@link #highlight} once before the items (it draws under them, at the selected item the previous frame
 * reported), then {@link #item} for each item, and {@link #header} for CAPS section headers.
 */
public final class Sidebar {
    private Sidebar() {}

    public static final int ITEM_H = 28;

    /** The gliding selection highlight of group {@code group}, {@code w} wide at x. */
    public static void highlight(Kit k, String group, int x, int w) {
        Integer target = k.mem(group + "#selY", null);
        if (target == null) return;
        float y = k.anim(group + "#hl", target, Motion.SMOOTH);
        int y1 = Math.round(Theme.snap(k.ctx, y));
        Paint.hgradient(k.ctx, x, y1, x + w, y1 + ITEM_H, Tokens.R_SM, Theme.lerpColor(Tokens.N200, Tokens.A900, 0.45f), Theme.alpha(Tokens.N200, 0.0f));
        Paint.slant(k.ctx, x + 3, y1 + 7, x + 5, y1 + ITEM_H - 7, Tokens.A500);
    }

    /** A CAPS section header (n600, tracked). */
    public static void header(Kit k, String text, int x, int y) {
        UiFont.caps(k.ctx, k.tr, text, x, y, Tokens.N600, Role.CAPS, 1f, k.smooth);
    }

    /**
     * One item at (x, y), {@code w} wide. {@code count} (may be null) is a neutral count on the right; {@code dot}
     * shows the accent notification dot instead.
     */
    public static void item(Kit k, String group, String id, int x, int y, int w, String icon, String label, String count,
                            boolean selected, boolean dot, Runnable onClick) {
        int st = k.state(id, x, y, x + w, y + ITEM_H, true);
        float hov = k.anim(id + "#h", (st & Kit.HOVER) != 0 && !selected ? 1f : 0f);
        if (selected) {
            if (k.mem(group + "#selY", null) == null) k.snapAnim(group + "#hl", y);
            k.remember(group + "#selY", y);
        }
        if (hov > 0.01f) Paint.rect(k.ctx, x, y, x + w, y + ITEM_H, Tokens.R_SM, Theme.alpha(Tokens.N250, 0.7f * hov));
        float cy = y + ITEM_H / 2f;
        if (icon != null) Icons.draw(k.ctx, icon, x + 10, Math.round(cy - 8), 16, selected ? Tokens.A400 : Theme.lerpColor(Tokens.N700, Tokens.N900, hov));
        k.textCentredY(label, x + 34, cy, selected ? Tokens.N950 : Theme.lerpColor(Tokens.N800, Tokens.N950, hov), Role.BODY);
        if (dot) {
            Paint.circle(k.ctx, x + w - 12, cy, 6, Tokens.A500);
        } else if (count != null) {
            Badge.draw(k, x + w - 8 - Badge.width(k, count), Math.round(cy - Badge.H / 2f), count, Badge.Tone.NEUTRAL);
        }
        if ((st & Kit.FOCUSED) != 0) Paint.focusRing(k, x, y, x + w, y + ITEM_H, Tokens.R_SM, Tokens.FOCUS_RING);
        k.hit(id, x, y, x + w, y + ITEM_H, onClick);
    }
}
