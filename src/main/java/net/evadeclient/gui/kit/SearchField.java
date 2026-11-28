package net.evadeclient.gui.kit;

import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

/**
 * The search field (spec 6.10), also the kit's single-line text input: 24 tall, sunken, r5, a search icon and an
 * n600 placeholder, n950 text and a blinking caret. Focused: a 1 device px accent border with a soft glow. A clear
 * "×" appears when there is text; an optional key-chip hint ("Ctrl F") sits right-aligned inside the field. Keep it at
 * most 360 wide in toolbars. Keys: left / right / home / end, backspace / delete (Ctrl: a word), Ctrl+A clears
 * selection-free by selecting all on the next type, Ctrl+V pastes, Escape unfocuses.
 */
public final class SearchField {
    private SearchField() {}

    public static final int H = Tokens.H_MD, MAX_W = 360;

    /** Draws the field and returns its current text. {@code icon}: false for a plain text input. */
    public static String draw(Kit k, String id, int x, int y, int w, String placeholder, String hint, boolean icon) {
        Kit.TextState t = k.text(id);
        boolean focus = k.isFocused(id);
        int st = k.state(id, x, y, x + w, y + H, true);
        float hov = k.anim(id + "#h", (st & Kit.HOVER) != 0 ? 1f : 0f);
        float foc = k.anim(id + "#f", focus ? 1f : 0f);
        Paint.surface(k.ctx, x, y, x + w, y + H, Tokens.R_SM, Tokens.SUNKEN.withFill(Theme.lerpColor(Tokens.N150, Tokens.N200, 0.5f * hov)), 0, Float.NaN);
        if (foc > 0.01f) {
            Paint.glow(k, x, y, x + w, y + H, Tokens.R_SM, 2, Theme.alpha(Tokens.A600, 0.35f * foc));
            Paint.ring(k.ctx, x, y, x + w, y + H, Tokens.R_SM, Theme.alpha(Tokens.A400, foc), 1);
        }
        float cy = y + H / 2f;
        int tx = x + 8;
        if (icon) {
            Icons.draw(k.ctx, "search", x + 7, Math.round(cy - 6), 12, Tokens.N600);
            tx = x + 25;
        }
        int right = x + w - 6;
        String s = t.text.toString();
        if (!s.isEmpty()) {
            String cid = id + "#clear";
            boolean hot = k.hovered(cid);
            Icons.draw(k.ctx, "close", right - 12, Math.round(cy - 5), 10, hot ? Tokens.N950 : Tokens.N700);
            k.hit(cid, right - 15, y + 3, right + 1, y + H - 3, (b, mx, my) -> { t.text.setLength(0); t.caret = 0; }, false);
            right -= 18;
        } else if (hint != null && !focus) {
            int hw = k.width(hint, Role.TINY) + 10;
            Paint.rect(k.ctx, right - hw, cy - 7, right, cy + 7, Tokens.R_XS, Tokens.N250);
            k.textCentredY(hint, right - hw + 5, cy, Tokens.N700, Role.TINY);
            right -= hw + 4;
        }
        int maxText = right - tx;
        k.ctx.enableScissor(tx, y, right, y + H);
        try {
            if (s.isEmpty()) {
                k.textCentredY(k.ellipsize(placeholder, Role.BODY, maxText), tx, cy, Tokens.N600, Role.BODY);
            } else {
                // Scroll so the caret stays visible.
                int caretPx = k.width(s.substring(0, Math.min(t.caret, s.length())), Role.BODY);
                int off = Math.max(0, caretPx - maxText + 2);
                k.textCentredY(s, tx - off, cy, Tokens.N950, Role.BODY);
                if (focus && ((k.now - t.lastEdit) % 1000 < 550 || net.evadeclient.gui.Motion.level() == net.evadeclient.gui.Motion.Level.OFF))
                    Theme.fill(k.ctx, tx - off + caretPx, Math.round(cy - 5), tx - off + caretPx + 1, Math.round(cy + 5), Tokens.A400);
            }
            if (s.isEmpty() && focus && (k.now - t.lastEdit) % 1000 < 550)
                Theme.fill(k.ctx, tx, Math.round(cy - 5), tx + 1, Math.round(cy + 5), Tokens.A400);
        } finally {
            k.ctx.disableScissor();
        }
        if ((st & Kit.FOCUSED) != 0) Paint.focusRing(k, x, y, x + w, y + H, Tokens.R_SM, Tokens.FOCUS_RING);
        k.hit(id, x, y, x + w, y + H, (b, mx, my) -> {
            k.focus(id, false);
            // Click to place the caret.
            String cur = t.text.toString();
            int px = (int) mx - (icon ? x + 25 : x + 8), best = cur.length();
            for (int i = 0; i <= cur.length(); i++) if (k.width(cur.substring(0, i), Role.BODY) >= px) { best = i; break; }
            t.caret = best;
            t.lastEdit = k.now;
        }, true);
        k.onKey(id, (key, mods) -> edit(k, id, t, key, mods));
        return s;
    }

    private static boolean edit(Kit k, String id, Kit.TextState t, int key, int mods) {
        boolean ctrl = (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        int n = t.text.length();
        t.caret = Math.max(0, Math.min(n, t.caret));
        t.lastEdit = k.now;
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> t.caret = ctrl ? wordStart(t.text, t.caret) : Math.max(0, t.caret - 1);
            case GLFW.GLFW_KEY_RIGHT -> t.caret = ctrl ? wordEnd(t.text, t.caret) : Math.min(n, t.caret + 1);
            case GLFW.GLFW_KEY_HOME -> t.caret = 0;
            case GLFW.GLFW_KEY_END -> t.caret = n;
            case GLFW.GLFW_KEY_BACKSPACE -> {
                int from = ctrl ? wordStart(t.text, t.caret) : Math.max(0, t.caret - 1);
                t.text.delete(from, t.caret);
                t.caret = from;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                int to = ctrl ? wordEnd(t.text, t.caret) : Math.min(n, t.caret + 1);
                t.text.delete(t.caret, to);
            }
            case GLFW.GLFW_KEY_ESCAPE -> {
                if (t.text.isEmpty()) return false;
                t.text.setLength(0);
                t.caret = 0;
            }
            case GLFW.GLFW_KEY_A -> {
                if (!ctrl) return false;
                t.caret = n; // no selection model: Ctrl+A then Backspace clears with Ctrl+Backspace semantics
            }
            case GLFW.GLFW_KEY_V -> {
                if (!ctrl) return false;
                String clip = MinecraftClient.getInstance().keyboard.getClipboard();
                if (clip != null) {
                    clip = clip.replaceAll("[\\r\\n\\t]", " ");
                    t.text.insert(t.caret, clip);
                    t.caret += clip.length();
                }
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_TAB -> { return false; }
            default -> { return key >= 32 && key < 256 && !ctrl; } // printable keys arrive as charTyped
        }
        return true;
    }

    private static int wordStart(CharSequence s, int i) {
        while (i > 0 && s.charAt(i - 1) == ' ') i--;
        while (i > 0 && s.charAt(i - 1) != ' ') i--;
        return i;
    }

    private static int wordEnd(CharSequence s, int i) {
        int n = s.length();
        while (i < n && s.charAt(i) == ' ') i++;
        while (i < n && s.charAt(i) != ' ') i++;
        return i;
    }

    /** The current text of field {@code id}. */
    public static String text(Kit k, String id) { return k.text(id).text.toString(); }

    /** Replace the text of field {@code id} (caret to the end). */
    public static void set(Kit k, String id, String s) {
        Kit.TextState t = k.text(id);
        t.text.setLength(0);
        t.text.append(s == null ? "" : s);
        t.caret = t.text.length();
    }
}
