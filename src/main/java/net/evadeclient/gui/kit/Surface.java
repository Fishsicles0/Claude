package net.evadeclient.gui.kit;

import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;

/**
 * Panels and cards (spec 6.1): the window (e1 glass, r12, 16 padding) with its 40 px header, cards (e2, r8, 12
 * padding; hover lifts to e3 with the rim brightening toward the cursor and, in card grids, a small tilt; selected
 * gets the accent surface gradient and a slanted accent edge light on the left) and CAPS section headers.
 */
public final class Surface {
    private Surface() {}

    public static final int HEADER_H = 40, PAD = Tokens.S6;

    /** The window body (e1) at (x1, y1)-(x2, y2). */
    public static void window(Kit k, int x1, int y1, int x2, int y2) {
        Paint.surface(k.ctx, x1, y1, x2, y2, Tokens.R_LG, Tokens.E1, 0, Float.NaN);
    }

    /**
     * The window header: an optional back button, the HEADLINE title (with an optional SMALL n700 subtitle on a second
     * line), and an optional close button on the right, over a divider that fades out at both ends. Returns the x
     * where right-side header actions should end (left of the close button).
     */
    public static int header(Kit k, String id, int x1, int y1, int x2, String title, String subtitle, Runnable onBack, Runnable onClose) {
        int tx = x1 + PAD, cy = y1 + HEADER_H / 2;
        if (onBack != null) {
            Button.icon(k, id + "#back", tx - 4, cy - 12, 24, "chevron_l", "Back", true, onBack);
            tx += 26;
        }
        if (subtitle == null || subtitle.isEmpty()) {
            k.textCentredY(title, tx, cy, Tokens.N950, Role.HEADLINE);
        } else {
            k.textCentredY(title, tx, cy - 6, Tokens.N950, Role.TITLE);
            k.textCentredY(k.ellipsize(subtitle, Role.SMALL, x2 - tx - 80), tx, cy + 8, Tokens.N700, Role.SMALL);
        }
        int right = x2 - PAD + 4;
        if (onClose != null) {
            right -= 24;
            Button.icon(k, id + "#close", right, cy - 12, 24, "close", "Close", true, onClose);
            right -= Tokens.S3;
        }
        Paint.fadeLine(k.ctx, x1 + 8, y1 + HEADER_H, x2 - 8, Theme.alpha(0xFFFFFFFF, 0.06f));
        return right;
    }

    /**
     * A card at (x1, y1)-(x2, y2). {@code tilt}: the hover tilt (card grids only, never in long lists). Draw the
     * card's content between this and {@link #endCard}. Clicking calls {@code onClick} (null: not clickable).
     */
    public static void card(Kit k, String id, int x1, int y1, int x2, int y2, boolean selected, boolean tilt, Runnable onClick) {
        int st = k.state(id, x1, y1, x2, y2, true);
        float hov = k.anim(id + "#h", (st & Kit.HOVER) != 0 ? 1f : 0f);
        Paint.beginTilt(k, x1, y1, x2, y2, tilt ? hov : 0);
        float press = (st & Kit.PRESSED) != 0 ? 0.5f / Theme.deviceScale(k.ctx) : 0;
        float rimX = k.mx - x1;
        Tokens.Elevation e = hov > 0.5f ? Tokens.E3 : Tokens.E2;
        int fill = Theme.lerpColor(Tokens.N200, Tokens.N250, hov);
        Paint.surface(k.ctx, x1, y1 + press, x2, y2 + press, Tokens.R_MD, e.withFill(fill), hov, rimX);
        if (selected) {
            Paint.hgradient(k.ctx, x1, y1, x2, y2, Tokens.R_MD, Theme.alpha(Tokens.A900, 0.45f), Theme.alpha(Tokens.A900, 0f));
            Paint.slant(k.ctx, x1 + 1, y1 + 8, x1 + 3, y2 - 8, Tokens.A500);
        }
        if (hov > 0.01f && Paint.gpu() && net.evadeclient.gui.Motion.decorative()) {
            // The cursor-follow specular: a faint radial light where the cursor is.
            Paint.radial(k.ctx, x1, y1, x2, y2, Tokens.R_MD, Theme.alpha(0xFFFFFFFF, 0.06f * hov), 0x00FFFFFF,
                    k.mx, k.my, Math.max(x2 - x1, y2 - y1) * 0.4f);
        }
        if ((st & Kit.FOCUSED) != 0) Paint.focusRing(k, x1, y1, x2, y2, Tokens.R_MD, Tokens.FOCUS_RING);
        if (onClick != null) k.hit(id, x1, y1, x2, y2, onClick);
    }

    /** Ends a {@link #card} (pops its tilt). */
    public static void endCard(Kit k) { Paint.endTilt(k); }

    /** A CAPS section header in n700 with an optional n600 count. Returns the y below it (header 20 + 6 gap). */
    public static int section(Kit k, String title, String count, int x, int y) {
        UiFont.caps(k.ctx, k.tr, title, x, y + 6, Tokens.N700, Role.CAPS, 0.9f, k.smooth);
        if (count != null) {
            int w = UiFont.capsWidth(k.tr, title, Role.CAPS, 0.9f, k.smooth);
            k.text(count, x + w + 6, y + 6, Tokens.N600, Role.SMALL);
        }
        return y + 20 + Tokens.S3;
    }
}
