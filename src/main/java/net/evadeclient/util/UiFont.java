package net.evadeclient.util;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2fStack;

import java.util.Locale;

/**
 * Evade's UI typography: a fixed set of text {@link Role roles}, each drawn either in Inter (Smooth Font) or in
 * Minecraft's own font scaled the way the menu always scaled it.
 *
 * <p>Smooth text is never matrix-scaled. Each role has its own font definition per GUI scale
 * ({@code assets/evade/font/ui_<role>_<g>.json}, g = 1..6) whose point size is the role's size and whose
 * oversample is the GUI scale, so glyphs are rasterised exactly 1:1 with device pixels and stay sharp at every
 * size. The definitions' vertical shifts line each role's baseline up with where the scaled pixel font sat, so a
 * caller can switch roles without moving its x and y.
 *
 * <p>With Smooth Font off every role falls back to the vanilla font under a matrix scale of {@link Role#scale},
 * which is exactly how the menu drew small text before roles existed.
 *
 * <p>All drawing goes through {@link Theme#fa} once, so text honours the group {@link Theme#fade} like shapes do.
 * Render thread only.
 */
public final class UiFont {
    /** The text roles, largest first: the font file key and the vanilla-font scale each one stands in for. */
    public enum Role {
        /** Page titles (SemiBold 13). */
        TITLE("title", 1.45f),
        /** Module names and primary buttons (SemiBold 9). */
        STRONG("strong", 1f),
        /** Regular UI text: nav labels, setting rows, control values (Medium 9). */
        BODY("body", 1f),
        /** Secondary lines: counts, descriptions under a title, small buttons (Medium 8). */
        LABEL("label", 0.87f),
        /** Row descriptions, hints and footnotes (Medium 7). */
        SMALL("small", 0.78f),
        /** The smallest readable text: card descriptions and key chips (Medium 6). */
        TINY("tiny", 0.68f),
        /** Tracked small-caps headers (SemiBold 7). */
        CAPS("caps", 0.78f),
        /** Tracked tiny caps: the logo tagline and card categories (SemiBold 6). */
        CAPS_SM("caps_sm", 0.65f);

        /** The {@code ui_<key>_<g>} font file stem. */
        public final String key;
        /** Matrix scale of the vanilla font when Smooth Font is off. */
        public final float scale;

        Role(String key, float scale) {
            this.key = key;
            this.scale = scale;
        }
    }

    /** Lowest and highest GUI scale with a font definition per role. */
    public static final int MIN_G = 1, MAX_G = 6;

    private static final Style[][] STYLES = new Style[Role.values().length][MAX_G + 1];

    private UiFont() {}

    private static int clampG(int g) {
        return Math.max(MIN_G, Math.min(MAX_G, g));
    }

    /** The style selecting {@code r}'s Inter font rasterised for {@code g} device pixels per GUI pixel (clamped to 1..6). */
    public static Style style(Role r, int g) {
        int k = clampG(g);
        Style[] row = STYLES[r.ordinal()];
        Style s = row[k];
        if (s == null) {
            s = Style.EMPTY.withFont(new StyleSpriteSource.Font(Identifier.of("evade", "ui_" + r.key + "_" + k)));
            row[k] = s;
        }
        return s;
    }

    /** The body style for HUD text drawn under an element scale: oversampled for the scale it will appear at, so a
     *  scaled Smooth Font element is still rasterised 1:1. */
    public static Style hudBody(float elementScale) {
        float s = Float.isFinite(elementScale) && elementScale > 0 ? elementScale : 1f;
        return style(Role.BODY, clampG(Math.round(Theme.gs() * s)));
    }

    /** {@code s} as a Text in {@code r}'s Inter font for the current GUI scale, or plain (vanilla font) when not smooth. */
    public static MutableText text(String s, Role r, boolean smooth) {
        MutableText t = Text.literal(s);
        return smooth ? t.setStyle(style(r, Theme.gs())) : t;
    }

    /**
     * Draw {@code s} in role {@code r} with its top-left at (x, y), without a shadow. Smooth: Inter at native size.
     * Otherwise: the vanilla font scaled by {@link Role#scale} about (x, y).
     */
    public static void draw(DrawContext ctx, TextRenderer tr, String s, int x, int y, int color, Role r, boolean smooth) {
        if (s == null || s.isEmpty()) return;
        int c = Theme.fa(color);
        if (c >>> 24 == 0) return;
        if (smooth || r.scale == 1f) {
            ctx.drawText(tr, text(s, r, smooth), x, y, c, false);
            return;
        }
        Matrix3x2fStack m = ctx.getMatrices();
        m.pushMatrix();
        try {
            m.translate(x, y);
            m.scale(r.scale, r.scale);
            ctx.drawText(tr, Text.literal(s), 0, 0, c, false);
        } finally {
            m.popMatrix();
        }
    }

    /** The width {@link #draw} takes for {@code s}, in GUI pixels (the vanilla width is rounded after scaling). */
    public static int width(TextRenderer tr, String s, Role r, boolean smooth) {
        if (s == null || s.isEmpty()) return 0;
        if (smooth) return tr.getWidth(text(s, r, true));
        return r.scale == 1f ? tr.getWidth(s) : Math.round(tr.getWidth(s) * r.scale);
    }

    /**
     * Draw {@code s} upper-cased with {@code tracking} extra GUI pixels between letters: a small-caps header.
     * Smooth: each letter is placed on its own, at its true advance plus the tracking, snapped to a device pixel.
     * Otherwise: the letters joined by spaces in the scaled vanilla font, as the menu always spaced its headers.
     */
    public static void caps(DrawContext ctx, TextRenderer tr, String s, int x, int y, int color, Role r,
                            float tracking, boolean smooth) {
        if (s == null || s.isEmpty()) return;
        String up = s.toUpperCase(Locale.ROOT);
        if (!smooth) {
            draw(ctx, tr, spaced(up), x, y, color, r, false);
            return;
        }
        int c = Theme.fa(color);
        if (c >>> 24 == 0) return;
        Style st = style(r, Theme.gs());
        Matrix3x2fStack m = ctx.getMatrices();
        float pen = 0;
        for (int i = 0; i < up.length(); ) {
            int cp = up.codePointAt(i);
            i += Character.charCount(cp);
            Text ch = Text.literal(new String(Character.toChars(cp))).setStyle(st);
            if (!Character.isWhitespace(cp)) {
                m.pushMatrix();
                try {
                    m.translate(Theme.snap(x + pen), y);
                    ctx.drawText(tr, ch, 0, 0, c, false);
                } finally {
                    m.popMatrix();
                }
            }
            pen += tr.getTextHandler().getWidth(ch) + tracking;
        }
    }

    /** The width {@link #caps} takes for {@code s}, in GUI pixels (no tracking after the last letter). */
    public static int capsWidth(TextRenderer tr, String s, Role r, float tracking, boolean smooth) {
        if (s == null || s.isEmpty()) return 0;
        String up = s.toUpperCase(Locale.ROOT);
        if (!smooth) return width(tr, spaced(up), r, false);
        Style st = style(r, Theme.gs());
        float pen = 0;
        int n = 0;
        for (int i = 0; i < up.length(); ) {
            int cp = up.codePointAt(i);
            i += Character.charCount(cp);
            pen += tr.getTextHandler().getWidth(Text.literal(new String(Character.toChars(cp))).setStyle(st));
            n++;
        }
        return Math.round(pen + tracking * Math.max(0, n - 1));
    }

    /** The letters of {@code s} separated by single spaces: the vanilla-font stand-in for letter tracking. */
    public static String spaced(String s) {
        StringBuilder b = new StringBuilder(s.length() * 2);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (i > 0) b.append(' ');
            b.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        return b.toString();
    }
}
