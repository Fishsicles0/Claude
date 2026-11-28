package net.evadeclient.gui.kit;

import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont.Role;

/**
 * Badges and tags (spec 6.18): 14 px pills in TINY SemiBold. Status tones use the status colour at 14% behind text in
 * that colour; neutral counts are n800 on n250; only "new" / "requests" counts use {@link Tone#ACCENT} (accent 400 on
 * accent 900 at 50%).
 */
public final class Badge {
    private Badge() {}

    public enum Tone { NEUTRAL, ACCENT, OK, WARN, DANGER, INFO }

    public static final int H = 14;

    public static int width(Kit k, String text) { return k.width(text, Role.TINY) + 10; }

    /** Draws the badge at (x, y) and returns its width. */
    public static int draw(Kit k, int x, int y, String text, Tone tone) {
        int w = width(k, text);
        int bg, fg;
        switch (tone) {
            case NEUTRAL -> { bg = Tokens.N250; fg = Tokens.N800; }
            case ACCENT -> { bg = Theme.alpha(Tokens.A900, 0.5f); fg = Tokens.A400; }
            default -> { fg = color(tone); bg = Theme.alpha(fg, 0.14f); }
        }
        Paint.pill(k.ctx, x, y, x + w, y + H, bg);
        k.textCentredY(text, x + 5, y + H / 2f, fg, Role.TINY);
        return w;
    }

    /** The status colour of a tone. */
    public static int color(Tone t) {
        return switch (t) {
            case OK -> Tokens.OK;
            case WARN -> Tokens.WARN;
            case DANGER -> Tokens.DANGER;
            case INFO -> Tokens.INFO;
            case ACCENT -> Tokens.A400;
            default -> Tokens.N800;
        };
    }
}
