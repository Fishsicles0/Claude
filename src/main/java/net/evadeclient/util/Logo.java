package net.evadeclient.util;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/** Draws the Evade logo: the speed-bar "E" mark and the slashed EVADE wordmark. */
public final class Logo {
    private static final Identifier MARK = Identifier.of("evade", "textures/gui/logo_mark.png");
    private static final Identifier WORD = Identifier.of("evade", "textures/gui/logo_word.png");
    private static final int MARK_SIZE = 128, WORD_W = 320, WORD_H = 96;
    /** The wordmark texture has padding around the letters; this is the visible letter height as a fraction. */
    private static final float WORD_LETTER_FRACTION = 0.62f;

    private Logo() {}

    public static void mark(DrawContext ctx, int x, int y, int size) {
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, MARK, x, y, 0, 0, size, size, MARK_SIZE, MARK_SIZE, MARK_SIZE, MARK_SIZE);
    }

    /** Wordmark whose letters are {@code letterHeight} tall; returns the drawn width. */
    public static int word(DrawContext ctx, int x, int y, int letterHeight) {
        int h = Math.round(letterHeight / WORD_LETTER_FRACTION);
        int w = Math.round(h * (WORD_W / (float) WORD_H));
        int top = y - Math.round((h - letterHeight) * 0.42f);
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, WORD, x, top, 0, 0, w, h, WORD_W, WORD_H, WORD_W, WORD_H);
        return w;
    }

    public static int wordWidth(int letterHeight) {
        int h = Math.round(letterHeight / WORD_LETTER_FRACTION);
        return Math.round(h * (WORD_W / (float) WORD_H));
    }

    /** Mark followed by the wordmark, vertically centred on the mark. Returns total width. */
    public static int lockup(DrawContext ctx, int x, int y, int markSize) {
        mark(ctx, x, y, markSize);
        int letter = Math.max(6, Math.round(markSize * 0.52f));
        int wx = x + markSize + Math.max(2, markSize / 6);
        return (wx - x) + word(ctx, wx, y + (markSize - letter) / 2, letter);
    }

    public static int lockupWidth(int markSize) {
        int letter = Math.max(6, Math.round(markSize * 0.52f));
        return markSize + Math.max(2, markSize / 6) + wordWidth(letter);
    }
}
