package net.evadeclient.gui.kit;

import net.evadeclient.gui.Motion;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont.Role;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The modal dialog (spec 6.13): e5, r12, at most 440 wide, 20 padding: a HEADLINE, the BODY n800 text and the actions
 * right-aligned (Secondary then Primary, or Ghost then Danger for destructive ones), over an n0 60% scrim that swallows
 * clicks. It opens with a fade and a 0.97 → 1 scale (a crossfade only under Reduced motion). Escape runs the first
 * (cancel) action, Enter the last (confirm). Draw it last in the frame, after the content it covers; the screen can
 * read {@link #pushBack} to scale and dim its own content behind it.
 */
public final class Dialog {
    private Dialog() {}

    /** One action button. */
    public record Action(String label, Button.Variant variant, Runnable run) {}

    public static final int MAX_W = 440, PAD = Tokens.S7;

    /** How far the content behind an open dialog is pushed back (0..1): scale it to {@code 1 - 0.015 x} and dim it. */
    public static float pushBack(Kit k, String id, boolean open) {
        return k.anim(id + "#push", open ? 1f : 0f, Motion.SMOOTH);
    }

    /** Draws the dialog centred on the screen. {@code openedAt}: when it opened (ms), for the entrance. */
    public static void draw(Kit k, String id, String title, String body, List<Action> actions, long openedAt) {
        int sw = k.screenW(), sh = k.screenH();
        float t = Motion.clamp01((k.now - openedAt) / (float) Math.max(1, Motion.duration(Motion.DUR_SLOW)));
        if (Motion.level() == Motion.Level.OFF) t = 1f;
        float e = Motion.standard(t);
        k.barrier(id + "#scrim", 0, 0, sw, sh, null);
        Theme.fill(k.ctx, 0, 0, sw, sh, Theme.alpha(Tokens.N0, 0.6f * e));
        int w = Math.min(MAX_W, sw - 32);
        List<String> lines = k.wrap(body, Role.BODY, w - 2 * PAD, 8);
        int h = PAD + 24 + 8 + lines.size() * 12 + 18 + Button.Size.MD.h + PAD;
        int x = (sw - w) / 2, y = (sh - h) / 2;
        float f = Theme.fade;
        Theme.fade = f * e;
        var m = k.ctx.getMatrices();
        m.pushMatrix();
        try {
            if (Motion.decorative()) {
                float sc = 0.97f + 0.03f * e;
                m.translate(sw / 2f, sh / 2f);
                m.scale(sc, sc);
                m.translate(-sw / 2f, -sh / 2f + Theme.snap(k.ctx, (1 - e) * 10));
            }
            Paint.surface(k.ctx, x, y, x + w, y + h, Tokens.R_LG, Tokens.E5, 0, Float.NaN);
            k.text(title, x + PAD, y + PAD - 3, Tokens.N950, Role.HEADLINE);
            int ty = y + PAD + 24 + 8;
            for (String l : lines) {
                k.text(l, x + PAD, ty, Tokens.N800, Role.BODY);
                ty += 12;
            }
            int bx = x + w - PAD, by = y + h - PAD - Button.Size.MD.h;
            for (int i = actions.size() - 1; i >= 0; i--) {
                Action a = actions.get(i);
                int bw = Button.width(k, a.label(), Button.Size.MD, null, false);
                bx -= bw;
                Button.draw(k, id + "#a" + i, bx, by, bw, Button.Size.MD, a.variant(), a.label(), null, true, a.run());
                bx -= Tokens.S3;
            }
        } finally {
            m.popMatrix();
            Theme.fade = f;
        }
        // Escape cancels (first action), Enter confirms (last action). Focus stays inside the dialog: its buttons
        // are the only focusable hits above the scrim.
        k.onKey(id + "#scrim", (key, mods) -> false);
        if (!actions.isEmpty() && (k.focused() == null || !k.focused().startsWith(id + "#a")))
            k.focus(id + "#a" + (actions.size() - 1), false);
        for (int i = 0; i < actions.size(); i++) {
            final int idx = i;
            k.onKey(id + "#a" + i, (key, mods) -> {
                if (key == GLFW.GLFW_KEY_ESCAPE) { actions.getFirst().run().run(); return true; }
                if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { actions.get(idx).run().run(); return true; }
                return false;
            });
        }
    }
}
