package net.evadeclient.gui.kit;

import net.evadeclient.util.Icons;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont.Role;

/**
 * The empty state (spec 6.19): a 48 px n600 glyph in a 72 px n200 circle, a TITLE line in n900, a SMALL n700 line and
 * an optional secondary button, centred on {@code cx}. Players (no one online), Shader Packs (none), Material List
 * (done), Friends (no friends), Gallery (no screenshots).
 */
public final class EmptyState {
    private EmptyState() {}

    /** Draws it with its top at y; returns the height used. */
    public static int draw(Kit k, String id, int cx, int y, String icon, String title, String line, String button, Runnable onButton) {
        Paint.circle(k.ctx, cx, y + 36, 72, Tokens.N200);
        Paint.ring(k.ctx, cx - 36, y, cx + 36, y + 72, 36, net.evadeclient.util.Theme.alpha(0xFFFFFFFF, 0.05f), 1);
        Icons.draw(k.ctx, icon, cx - 20, y + 16, 40, Tokens.N600);
        int ty = y + 84;
        k.text(title, cx - k.width(title, Role.TITLE) / 2, ty, Tokens.N900, Role.TITLE);
        ty += 20;
        if (line != null) {
            for (String l : k.wrap(line, Role.SMALL, 260, 2)) {
                k.text(l, cx - k.width(l, Role.SMALL) / 2, ty, Tokens.N700, Role.SMALL);
                ty += 10;
            }
        }
        if (button != null) {
            ty += 8;
            int bw = Button.width(k, button, Button.Size.MD, null, false);
            Button.draw(k, id + "#b", cx - bw / 2, ty, bw, Button.Size.MD, Button.Variant.SECONDARY, button, null, true, onButton);
            ty += Button.Size.MD.h;
        }
        return ty - y;
    }
}
