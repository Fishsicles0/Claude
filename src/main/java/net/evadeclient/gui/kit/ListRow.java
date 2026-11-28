package net.evadeclient.gui.kit;

import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont.Role;

/**
 * A list row (spec 6.15): 32 (dense) or 40 (comfortable, Client Settings: Density) tall. A leading 24 px icon tile
 * (n250, glyph n800, accent 400 when on), the STRONG title, a SMALL n700 description on the same line (ellipsized,
 * the full text in the tooltip), and trailing controls grouped tightly on the right: the gear icon button and the
 * toggle, 6 px apart. Hover lifts it to e3. On/selected is shown by the toggle and the icon tint only: no left bar,
 * no outline. Rows in a group sit 2 px apart.
 */
public final class ListRow {
    private ListRow() {}

    /** The row height for the current density. */
    public static int height() { return Tokens.comfortable ? Tokens.ROW_COMFY : Tokens.ROW_DENSE; }

    /**
     * A row at (x, y), {@code w} wide. {@code on} null hides the toggle; {@code onGear} null hides the gear.
     * Clicking the row body calls {@code onClick}.
     */
    public static void draw(Kit k, String id, int x, int y, int w, String icon, String title, String desc, Boolean on,
                            Runnable onToggle, Runnable onGear, Runnable onClick) {
        int h = height();
        int st = k.state(id, x, y, x + w, y + h, true);
        float hov = k.anim(id + "#h", (st & Kit.HOVER) != 0 ? 1f : 0f);
        Paint.surface(k.ctx, x, y, x + w, y + h, Tokens.R_SM,
                Tokens.E2.noAmbient().withFill(Theme.lerpColor(Tokens.N200, Tokens.N250, hov)), hov, Float.NaN);
        if ((st & Kit.FOCUSED) != 0) Paint.focusRing(k, x, y, x + w, y + h, Tokens.R_SM, Tokens.FOCUS_RING);
        k.hit(id, x, y, x + w, y + h, onClick);
        float cy = y + h / 2f;
        int tx = x + Tokens.S4;
        if (icon != null) {
            Paint.rect(k.ctx, tx, cy - 12, tx + 24, cy + 12, Tokens.R_XS, Tokens.N250);
            boolean lit = on != null && on;
            Icons.draw(k.ctx, icon, tx + 5, Math.round(cy - 7), 14, lit ? Tokens.A400 : Tokens.N800);
            tx += 24 + Tokens.S4;
        }
        // Trailing controls, right to left.
        int right = x + w - Tokens.S4;
        if (on != null) {
            right -= Toggle.MD_W;
            Toggle.draw(k, id + "#t", right, Math.round(cy - Toggle.MD_H / 2f), false, on, true, onToggle);
            right -= Tokens.S3;
        }
        if (onGear != null) {
            right -= 22;
            Button.icon(k, id + "#g", right, Math.round(cy - 11), 22, "settings", "Settings", true, onGear);
            right -= Tokens.S3;
        }
        int titleW = k.width(title, Role.STRONG);
        int avail = right - tx - Tokens.S4;
        String t = k.ellipsize(title, Role.STRONG, avail);
        k.textCentredY(t, tx, cy, Tokens.N950, Role.STRONG);
        if (desc != null && !desc.isEmpty() && titleW + 40 < avail) {
            int dx = tx + titleW + Tokens.S4;
            String d = k.ellipsize(desc, Role.SMALL, avail - titleW - Tokens.S4);
            k.textCentredY(d, dx, cy, Tokens.N700, Role.SMALL);
            if (!d.equals(desc)) k.tooltip(id, title, desc, x, y, x + w, y + h);
        }
    }
}
