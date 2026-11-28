package net.evadeclient.gui.kit;

import net.evadeclient.gui.Motion;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont.Role;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Consumer;

/**
 * Text tabs (spec 6.7): STRONG labels (n700, selected n950) over a 2 device px accent underline shaped as the Evade
 * 12-degree parallelogram, gliding to the selected tab on a smooth spring, with a hairline under the whole row.
 * When the tabs are wider than {@code maxW} the row scrolls horizontally with edge fades (it never wraps).
 */
public final class Tabs {
    private Tabs() {}

    public static final int H = 26, GAP = 18;

    /** Draws the tab row at (x, y), clipped to {@code maxW}; returns the row's full width. */
    public static int draw(Kit k, String id, int x, int y, int maxW, List<String> tabs, String selected, Consumer<String> onPick) {
        int total = 0;
        for (String t : tabs) total += k.width(t, Role.STRONG) + GAP;
        total -= GAP;
        int sel = Math.max(0, tabs.indexOf(selected));
        // Scroll so the selected tab is visible.
        int selStart = 0, selW = 0, acc = 0;
        for (int i = 0; i < tabs.size(); i++) {
            int tw = k.width(tabs.get(i), Role.STRONG);
            if (i == sel) { selStart = acc; selW = tw; }
            acc += tw + GAP;
        }
        float scrollTarget = total <= maxW ? 0 : Math.max(0, Math.min(total - maxW, selStart + selW / 2f - maxW / 2f));
        float scroll = k.anim(id + "#s", scrollTarget, Motion.SMOOTH);
        int st = k.state(id, x, y, x + Math.min(maxW, total), y + H, true);
        k.ctx.enableScissor(x, y - 2, x + maxW, y + H + 2);
        try {
            int tx = Math.round(x - scroll);
            for (int i = 0; i < tabs.size(); i++) {
                String t = tabs.get(i);
                int tw = k.width(t, Role.STRONG);
                String tid = id + "#" + i;
                int col = i == sel ? Tokens.N950 : k.hovered(tid) ? Tokens.N900 : Tokens.N700;
                k.textCentredY(t, tx, y + (H - 4) / 2f, col, Role.STRONG);
                k.hit(tid, tx - GAP / 2, y, tx + tw + GAP / 2, y + H, (b, mx, my) -> { if (b == 0) onPick.accept(t); }, false);
                tx += tw + GAP;
            }
            float ux = k.anim(id + "#x", x - scroll + selStart, Motion.SMOOTH), uw = k.anim(id + "#w", selW, Motion.SMOOTH);
            float th = 2f / Theme.deviceScale(k.ctx);
            Paint.fadeLine(k.ctx, x, y + H - 1, x + Math.min(maxW, total), Theme.alpha(0xFFFFFFFF, 0.08f));
            Paint.slant(k.ctx, ux, y + H - 1 - th, ux + uw, y + H - 1 + th * 0.5f, Tokens.A500);
        } finally {
            k.ctx.disableScissor();
        }
        if (total > maxW) { // edge fades over the clipped ends
            if (scroll > 1) Paint.hgradient(k.ctx, x, y, x + 16, y + H, 0, Tokens.N100, Tokens.N100 & 0xFFFFFF);
            if (scroll < total - maxW - 1) Paint.hgradient(k.ctx, x + maxW - 16, y, x + maxW, y + H, 0, Tokens.N100 & 0xFFFFFF, Tokens.N100);
        }
        if ((st & Kit.FOCUSED) != 0) Paint.focusRing(k, x - 4, y, x + Math.min(maxW, total) + 4, y + H, Tokens.R_SM, Tokens.FOCUS_RING);
        k.hit(id, x, y, x, y, () -> {});
        int n = tabs.size();
        k.onKey(id, (key, mods) -> {
            if (key == GLFW.GLFW_KEY_LEFT) { onPick.accept(tabs.get(Math.max(0, sel - 1))); return true; }
            if (key == GLFW.GLFW_KEY_RIGHT) { onPick.accept(tabs.get(Math.min(n - 1, sel + 1))); return true; }
            return false;
        });
        return total;
    }
}
