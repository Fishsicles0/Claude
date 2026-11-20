package net.evadeclient.util;

import net.minecraft.client.gui.DrawContext;

/** Evade's black & red look, shared by the menu and the HUD. */
public final class Theme {
    public static final int RED = 0xFFE11D2E;
    public static final int RED_BRIGHT = 0xFFFF3B4C;
    public static final int RED_DARK = 0xFF7A0D18;
    public static final int BG = 0xF20A0A0C;
    public static final int SIDEBAR = 0xFF0E0E11;
    public static final int CARD = 0xFF15151A;
    public static final int CARD_HOVER = 0xFF1C1C22;
    public static final int BORDER = 0xFF26262D;
    public static final int TEXT = 0xFFF2F2F5;
    public static final int TEXT_DIM = 0xFF8A8A96;
    public static final int TEXT_FAINT = 0xFF55555F;
    public static final int HUD_BG = 0x900A0A0C;

    private Theme() {}

    /** Rectangle with 2px rounded corners. */
    public static void round(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
        if (x2 - x1 < 4 || y2 - y1 < 4) { ctx.fill(x1, y1, x2, y2, color); return; }
        ctx.fill(x1 + 2, y1, x2 - 2, y1 + 1, color);
        ctx.fill(x1 + 1, y1 + 1, x2 - 1, y1 + 2, color);
        ctx.fill(x1, y1 + 2, x2, y2 - 2, color);
        ctx.fill(x1 + 1, y2 - 2, x2 - 1, y2 - 1, color);
        ctx.fill(x1 + 2, y2 - 1, x2 - 2, y2, color);
    }

    /** Rounded rectangle with a 1px border. */
    public static void roundBordered(DrawContext ctx, int x1, int y1, int x2, int y2, int fill, int border) {
        round(ctx, x1, y1, x2, y2, border);
        round(ctx, x1 + 1, y1 + 1, x2 - 1, y2 - 1, fill);
    }

    /** Standard HUD element background: dark glass with a thin red top edge. */
    public static void hudBox(DrawContext ctx, int x, int y, int w, int h) {
        round(ctx, x, y, x + w, y + h, HUD_BG);
        ctx.fill(x + 2, y, x + w - 2, y + 1, RED);
    }

    /** Pill-shaped on/off switch. {@code t} is the animation progress 0..1. */
    public static void toggle(DrawContext ctx, int x, int y, float t) {
        int w = 20, h = 10;
        int track = lerpColor(0xFF2A2A31, RED, t);
        round(ctx, x, y, x + w, y + h, track);
        int knobX = x + 1 + Math.round((w - h) * t);
        round(ctx, knobX, y + 1, knobX + h - 2, y + h - 1, 0xFFFFFFFF);
    }

    public static int lerpColor(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int aa = a >>> 24, ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int ba = b >>> 24, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return Math.round(aa + (ba - aa) * t) << 24 | Math.round(ar + (br - ar) * t) << 16
                | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
    }

    public static int alpha(int color, float a) {
        int base = color >>> 24;
        return Math.round(base * Math.max(0, Math.min(1, a))) << 24 | (color & 0xFFFFFF);
    }
}
