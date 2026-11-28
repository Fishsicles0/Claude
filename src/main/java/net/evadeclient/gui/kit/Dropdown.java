package net.evadeclient.gui.kit;

import net.evadeclient.gui.Motion;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont.Role;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Consumer;

/**
 * The dropdown select (spec 6.5), replacing the old "‹ Hold ›" cyclers: a secondary-style trigger showing the value
 * and a down chevron (min width 96), and an e4 menu (r8, 4 px padding, 22 px options, a check on the selected one)
 * that opens below with a short slide and fade. Keyboard: Enter / Space / Down opens, arrows move, Enter picks,
 * Escape closes, typing a letter jumps to the next option starting with it. The mouse wheel over the closed trigger
 * still cycles the value (the power-user path).
 */
public final class Dropdown {
    private Dropdown() {}

    public static final int H = Tokens.H_MD, MIN_W = 96, OPTION_H = 22;

    /** A dropdown trigger at (x, y), {@code w} wide (at least 96). Picking calls {@code onPick} with the option. */
    public static void draw(Kit k, String id, int x, int y, int w, List<String> options, String selected, boolean enabled,
                            Consumer<String> onPick) {
        w = Math.max(MIN_W, w);
        boolean open = id.equals(k.popup());
        int st = k.state(id, x, y, x + w, y + H, enabled);
        boolean dis = (st & Kit.DISABLED) != 0;
        float hov = k.anim(id + "#h", (st & Kit.HOVER) != 0 || open ? 1f : 0f);
        float f = Theme.fade;
        if (dis) Theme.fade = f * 0.4f;
        try {
            Paint.surface(k.ctx, x, y, x + w, y + H, Tokens.R_SM, Tokens.E3.noAmbient().withFill(Theme.lerpColor(Tokens.N250, Tokens.N300, hov)), hov, Float.NaN);
            k.textCentredY(k.ellipsize(selected, Role.BODY, w - 30), x + 10, y + H / 2f, Tokens.N950, Role.BODY);
            // Down chevron: the right chevron turned a quarter, flipping up while open.
            var m = k.ctx.getMatrices();
            m.pushMatrix();
            m.translate(x + w - 13, y + H / 2f);
            m.rotate((float) Math.toRadians(open ? -90 : 90));
            Icons.draw(k.ctx, "chevron_r", -4, -4, 8, Tokens.N700);
            m.popMatrix();
        } finally {
            Theme.fade = f;
        }
        if ((st & Kit.FOCUSED) != 0 && !open) Paint.focusRing(k, x, y, x + w, y + H, Tokens.R_SM, Tokens.FOCUS_RING);
        if (!enabled) return;
        int sel = Math.max(0, options.indexOf(selected));
        k.hit(id, x, y, x + w, y + H, () -> {
            k.popup(open ? null : id);
            k.remember(id + "#cursor", sel);
            k.remember(id + "#openedAt", k.now);
        });
        k.onKey(id + "#wheel", (key, mods) -> {
            if (open || options.isEmpty()) return false;
            int n = options.size();
            onPick.accept(options.get(((sel + (key == GLFW.GLFW_KEY_UP ? -1 : 1)) % n + n) % n));
            return true;
        });
        k.onKey(id, (key, mods) -> {
            int n = options.size();
            if (n == 0) return false;
            int cur = k.mem(id + "#cursor", sel);
            if (!open) {
                if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_SPACE) {
                    k.popup(id); k.remember(id + "#cursor", sel); k.remember(id + "#openedAt", k.now); return true;
                }
                return false;
            }
            switch (key) {
                case GLFW.GLFW_KEY_DOWN -> { k.remember(id + "#cursor", Math.min(n - 1, cur + 1)); return true; }
                case GLFW.GLFW_KEY_UP -> { k.remember(id + "#cursor", Math.max(0, cur - 1)); return true; }
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> {
                    onPick.accept(options.get(cur)); k.popup(null); return true;
                }
                case GLFW.GLFW_KEY_ESCAPE -> { k.popup(null); return true; }
                default -> {
                    if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) { // type-ahead
                        char c = (char) ('a' + key - GLFW.GLFW_KEY_A);
                        for (int i = 1; i <= n; i++) {
                            int j = (cur + i) % n;
                            if (!options.get(j).isEmpty() && Character.toLowerCase(options.get(j).charAt(0)) == c) {
                                k.remember(id + "#cursor", j);
                                return true;
                            }
                        }
                    }
                    return false;
                }
            }
        });
        if (open) {
            final int fw = w;
            k.overlay(() -> menu(k, id, x, y + H + 4, fw, options, selected, onPick));
        }
    }

    private static void menu(Kit k, String id, int x, int y, int w, List<String> options, String selected, Consumer<String> onPick) {
        long openedAt = k.mem(id + "#openedAt", 0L);
        float a = Motion.standard(Motion.clamp01((k.now - openedAt) / (float) Math.max(1, Motion.duration(Motion.DUR_QUICK))));
        if (Motion.level() == Motion.Level.OFF) a = 1f;
        int h = options.size() * OPTION_H + 8;
        // Flip above the trigger when there is no room below.
        if (y + h > k.screenH() - 4) y = Math.max(4, y - H - 8 - h);
        float dy = (1 - a) * -4;
        float f = Theme.fade;
        Theme.fade = f * a;
        var m = k.ctx.getMatrices();
        m.pushMatrix();
        try {
            m.translate(0, Theme.snap(k.ctx, dy));
            k.barrier(id + "#menu", x, y, x + w, y + h, null);
            Paint.surface(k.ctx, x, y, x + w, y + h, Tokens.R_MD, Tokens.E4, 0, Float.NaN);
            int cursor = k.mem(id + "#cursor", -1);
            for (int i = 0; i < options.size(); i++) {
                String o = options.get(i);
                int oy = y + 4 + i * OPTION_H;
                String oid = id + "#o" + i;
                boolean hot = k.hovered(oid) || i == cursor;
                if (hot) Paint.rect(k.ctx, x + 4, oy, x + w - 4, oy + OPTION_H, Tokens.R_XS + 1, Tokens.N350);
                boolean sel = o.equals(selected);
                if (sel) Icons.draw(k.ctx, "check", x + 9, oy + (OPTION_H - 10) / 2, 10, Tokens.A400);
                k.textCentredY(k.ellipsize(o, Role.BODY, w - 34), x + 24, oy + OPTION_H / 2f, sel ? Tokens.N950 : Tokens.N800, Role.BODY);
                k.hit(oid, x + 4, oy, x + w - 4, oy + OPTION_H, () -> { onPick.accept(o); k.popup(null); });
            }
        } finally {
            m.popMatrix();
            Theme.fade = f;
        }
    }
}
