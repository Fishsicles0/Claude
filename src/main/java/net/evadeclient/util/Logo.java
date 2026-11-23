package net.evadeclient.util;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * Draws the Evade logo: the speed-bar "E" mark and the slashed EVADE wordmark. Both ship as a small mip chain
 * (mark 32/64/128 px, wordmark 80x24/160x48/320x96) with LINEAR sampling, and the smallest source that covers the
 * on-screen size in device pixels is used, so the logo stays smooth at every GUI scale.
 */
public final class Logo {
    private static final Identifier MARK_32 = gui("logo_mark_32"), MARK_64 = gui("logo_mark_64"), MARK_128 = gui("logo_mark");
    private static final Identifier WORD_80 = gui("logo_word_80"), WORD_160 = gui("logo_word_160"), WORD_320 = gui("logo_word");
    private static final int WORD_W = 320, WORD_H = 96;
    /** The wordmark texture has padding around the letters; this is the visible letter height as a fraction. */
    private static final float WORD_LETTER_FRACTION = 0.62f;

    private Logo() {}

    private static Identifier gui(String name) {
        return Identifier.of("evade", "textures/gui/" + name + ".png");
    }

    public static void mark(DrawContext ctx, int x, int y, int size) {
        mark(ctx, x, y, size, 0xFFFFFFFF);
    }

    /** The mark, {@code size} GUI pixels square, multiplied by {@code color} (white keeps the artwork's colours). */
    public static void mark(DrawContext ctx, int x, int y, int size, int color) {
        if (size <= 0 || color >>> 24 == 0) return;
        float px = size * Theme.deviceScale(ctx);
        int src = px <= 32 ? 32 : px <= 64 ? 64 : 128;
        Identifier id = src == 32 ? MARK_32 : src == 64 ? MARK_64 : MARK_128;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, id, x, y, 0, 0, size, size, src, src, src, src, color);
    }

    /** Wordmark whose letters are {@code letterHeight} tall; returns the drawn width. */
    public static int word(DrawContext ctx, int x, int y, int letterHeight) {
        return word(ctx, x, y, letterHeight, 0xFFFFFFFF);
    }

    /** Wordmark whose letters are {@code letterHeight} tall, multiplied by {@code color}; returns the drawn width. */
    public static int word(DrawContext ctx, int x, int y, int letterHeight, int color) {
        int h = Math.round(letterHeight / WORD_LETTER_FRACTION);
        int w = Math.round(h * (WORD_W / (float) WORD_H));
        if (h <= 0 || color >>> 24 == 0) return w;
        int top = y - Math.round((h - letterHeight) * 0.42f);
        float px = h * Theme.deviceScale(ctx);
        int sh = px <= 24 ? 24 : px <= 48 ? 48 : 96, sw = sh * WORD_W / WORD_H;
        Identifier id = sh == 24 ? WORD_80 : sh == 48 ? WORD_160 : WORD_320;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, id, x, top, 0, 0, w, h, sw, sh, sw, sh, color);
        return w;
    }

    public static int wordWidth(int letterHeight) {
        int h = Math.round(letterHeight / WORD_LETTER_FRACTION);
        return Math.round(h * (WORD_W / (float) WORD_H));
    }

    /** Mark followed by the wordmark, vertically centred on the mark, faded with {@link Theme#fade}. Returns total width. */
    public static int lockup(DrawContext ctx, int x, int y, int markSize) {
        int white = Theme.fa(0xFFFFFFFF);
        mark(ctx, x, y, markSize, white);
        int letter = Math.max(6, Math.round(markSize * 0.52f));
        int wx = x + markSize + Math.max(2, markSize / 6);
        return (wx - x) + word(ctx, wx, y + (markSize - letter) / 2, letter, white);
    }

    public static int lockupWidth(int markSize) {
        int letter = Math.max(6, Math.round(markSize * 0.52f));
        return markSize + Math.max(2, markSize / 6) + wordWidth(letter);
    }
}
