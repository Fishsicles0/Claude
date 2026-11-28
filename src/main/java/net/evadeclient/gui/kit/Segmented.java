package net.evadeclient.gui.kit;

import net.evadeclient.gui.Motion;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont.Role;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Consumer;

/**
 * The segmented control (spec 6.6): a sunken pill holding the options, with a raised e3 thumb that glides to the
 * selected one on a smooth spring. Selected text n950, the others n700. Never red. Left / Right move it while focused.
 */
public final class Segmented {
    private Segmented() {}

    public static final int H = 22;

    /** The width {@link #draw} takes. */
    public static int width(Kit k, List<String> options) {
        int w = 4;
        for (String o : options) w += k.width(o, Role.LABEL) + 20;
        return w;
    }

    /** Draws the control at (x, y) and returns its width. */
    public static int draw(Kit k, String id, int x, int y, List<String> options, String selected, boolean enabled, Consumer<String> onPick) {
        int w = width(k, options);
        int st = k.state(id, x, y, x + w, y + H, enabled);
        float f = Theme.fade;
        if ((st & Kit.DISABLED) != 0) Theme.fade = f * 0.4f;
        try {
            Paint.sunken(k.ctx, x, y, x + w, y + H, H / 2f);
            int sel = Math.max(0, options.indexOf(selected));
            // Segment geometry.
            int sx = x + 2, selX = sx, selW = 0;
            for (int i = 0; i < options.size(); i++) {
                int sw = k.width(options.get(i), Role.LABEL) + 20;
                if (i == sel) { selX = sx; selW = sw; }
                sx += sw;
            }
            float tx = k.anim(id + "#x", selX, Motion.SMOOTH), tw = k.anim(id + "#w", selW, Motion.SMOOTH);
            Paint.surface(k.ctx, tx, y + 2, tx + tw, y + H - 2, (H - 4) / 2f, Tokens.E3.noAmbient(), 0, Float.NaN);
            sx = x + 2;
            for (int i = 0; i < options.size(); i++) {
                String o = options.get(i);
                int sw = k.width(o, Role.LABEL) + 20;
                String sid = id + "#" + i;
                boolean hot = k.hovered(sid);
                int col = i == sel ? Tokens.N950 : hot ? Tokens.N900 : Tokens.N700;
                k.textCentredY(o, sx + 10, y + H / 2f, col, Role.LABEL);
                if (enabled) k.hit(sid, sx, y, sx + sw, y + H, (b, mx, my) -> { if (b == 0) onPick.accept(o); }, false);
                sx += sw;
            }
        } finally {
            Theme.fade = f;
        }
        if ((st & Kit.FOCUSED) != 0) Paint.focusRing(k, x, y, x + w, y + H, H / 2f, Tokens.FOCUS_RING);
        if (enabled) {
            // The whole control is one Tab stop; arrows move the selection.
            k.hit(id, x, y, x, y, () -> {});
            int sel = Math.max(0, options.indexOf(selected)), n = options.size();
            k.onKey(id, (key, mods) -> {
                if (key == GLFW.GLFW_KEY_LEFT) { onPick.accept(options.get(Math.max(0, sel - 1))); return true; }
                if (key == GLFW.GLFW_KEY_RIGHT) { onPick.accept(options.get(Math.min(n - 1, sel + 1))); return true; }
                return false;
            });
        }
        return w;
    }
}
