package net.evadeclient.gui.kit;

import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont.Role;

/**
 * A keybind chip (spec 6.9): at least 44 x 18, sunken, TINY SemiBold n900 text with the modifiers spelled out
 * (see {@link #label}). "None" recedes as a dashed n500 outline with an n600 dash. While listening it pulses
 * an accent ring (1 Hz) and reads "Press a key…". A conflict gets a warning outline and icon, and its tooltip lists
 * the clashes.
 */
public final class KeyChip {
    private KeyChip() {}

    public enum Mode { BOUND, NONE, LISTENING, CONFLICT }

    public static final int H = 18, MIN_W = 44;

    /** "Ctrl+Shift+F" as "Ctrl Shift F": modifiers then the key, spaced like printed keycaps. (The smooth font has no
     *  modifier glyphs; the pixel-font fallback for them is unreadable at chip size.) */
    public static String label(String bind) {
        if (bind == null) return "";
        return bind.replace("+", " ");
    }

    /** The chip's width for {@code text}. */
    public static int width(Kit k, Mode mode, String text) {
        String t = mode == Mode.NONE ? "—" : mode == Mode.LISTENING ? "Press a key…" : text;
        return Math.max(MIN_W, k.width(t, Role.TINY) + 14 + (mode == Mode.CONFLICT ? 12 : 0));
    }

    /** Draws the chip at (x, y) and returns its width. {@code tip}: the tooltip (conflicts). */
    public static int draw(Kit k, String id, int x, int y, Mode mode, String text, String tip, Runnable onClick) {
        int w = width(k, mode, text);
        int st = k.state(id, x, y, x + w, y + H, true);
        float hov = k.anim(id + "#h", (st & Kit.HOVER) != 0 ? 1f : 0f);
        float cy = y + H / 2f;
        switch (mode) {
            case NONE -> {
                if (hov > 0.01f) Paint.rect(k.ctx, x, y, x + w, y + H, Tokens.R_XS, Theme.alpha(Tokens.N150, hov));
                Paint.dashed(k.ctx, x, y, x + w, y + H, Theme.lerpColor(Tokens.N500, Tokens.N600, hov));
                String t = "—";
                k.textCentredY(t, x + (w - k.width(t, Role.TINY)) / 2, cy, Tokens.N600, Role.TINY);
            }
            case LISTENING -> {
                Paint.sunken(k.ctx, x, y, x + w, y + H, Tokens.R_XS);
                float pulse = 0.55f + 0.45f * (float) Math.cos((k.now % 1000) / 1000.0 * Math.PI * 2);
                if (net.evadeclient.gui.Motion.level() != net.evadeclient.gui.Motion.Level.FULL) pulse = 1f;
                Paint.ring(k.ctx, x, y, x + w, y + H, Tokens.R_XS, Theme.alpha(Tokens.A400, pulse), 2);
                String t = "Press a key…";
                k.textCentredY(t, x + (w - k.width(t, Role.TINY)) / 2, cy, Tokens.N950, Role.TINY);
            }
            default -> {
                Paint.surface(k.ctx, x, y, x + w, y + H, Tokens.R_XS, Tokens.SUNKEN.withFill(Theme.lerpColor(Tokens.N150, Tokens.N250, hov)), 0, Float.NaN);
                int tx = x + 7;
                if (mode == Mode.CONFLICT) {
                    Paint.ring(k.ctx, x, y, x + w, y + H, Tokens.R_XS, Tokens.WARN, 1.5f);
                    Icons.draw(k.ctx, "warn", tx - 1, Math.round(cy - 5), 10, Tokens.WARN);
                    tx += 12;
                }
                int tw = k.width(text, Role.TINY);
                k.textCentredY(text, mode == Mode.CONFLICT ? tx : x + (w - tw) / 2, cy, Tokens.N900, Role.TINY);
            }
        }
        if ((st & Kit.FOCUSED) != 0) Paint.focusRing(k, x, y, x + w, y + H, Tokens.R_XS, Tokens.FOCUS_RING);
        k.hit(id, x, y, x + w, y + H, onClick);
        if (tip != null) k.tooltip(id, tip, null, x, y, x + w, y + H);
        return w;
    }
}
