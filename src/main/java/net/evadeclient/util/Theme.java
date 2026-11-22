package net.evadeclient.util;

import net.minecraft.client.gui.DrawContext;

/**
 * Evade's colours, shared by the menu and the HUD. The values change with the Theme and Accent chosen in
 * Client Settings ({@link #apply}); {@code RED} is the accent colour (red by default).
 */
public final class Theme {
    public static int RED = 0xFFE11D2E;
    public static int RED_BRIGHT = 0xFFFF3B4C;
    public static int RED_DARK = 0xFF7A0D18;
    public static int BG = 0xF20A0A0C;
    public static int SIDEBAR = 0xFF0E0E11;
    public static int CARD = 0xFF15151A;
    public static int CARD_HOVER = 0xFF1C1C22;
    public static int BORDER = 0xFF26262D;
    public static int TEXT = 0xFFF2F2F5;
    public static int TEXT_DIM = 0xFF8A8A96;
    public static int TEXT_FAINT = 0xFF55555F;
    public static int HUD_BG = 0x900A0A0C;
    /** Text fields and segmented controls. */
    public static int INPUT = 0xFF101013;
    /** Small buttons, chips and value boxes. */
    public static int CONTROL = 0xFF17171C;
    public static int CONTROL_HOVER = 0xFF24242C;
    public static int DIVIDER = 0xFF1C1C22;
    public static int TRACK = 0xFF2A2A31;
    public static int BORDER_HOVER = 0xFF3A3A44;
    public static int ROW = 0xFF121216;
    public static int ROW_HOVER = 0xFF18181D;
    public static int TEXT_SOFT = 0xFFA8A8B2;
    public static int TEXT_HEADER = 0xFF6E6E7A;
    /** Classic look: square corners like vanilla menus. */
    public static boolean square;

    public static final String[] PRESETS = {"Modern", "Midnight", "OLED", "Classic"};
    public static final String[] ACCENTS = {"Red", "Blue", "Purple", "Pink", "Orange", "Gold", "Green", "Cyan"};
    private static final int[] ACCENT_RGB = {0xE11D2E, 0x2F7BFF, 0x8B5CF6, 0xEC4899, 0xF97316, 0xEAB308, 0x22C55E, 0x06B6D4};
    private static String applied = "";

    /** Switch to a theme preset and accent colour (cheap no-op when nothing changed). */
    public static void apply(String preset, String accent) {
        String key = preset + "/" + accent;
        if (key.equals(applied)) return;
        applied = key;
        int i = java.util.Arrays.asList(ACCENTS).indexOf(accent);
        RED = 0xFF000000 | ACCENT_RGB[Math.max(0, i)];
        RED_BRIGHT = lerpColor(RED, 0xFFFFFFFF, 0.22f);
        RED_DARK = lerpColor(RED, 0xFF000000, 0.46f);
        square = false;
        TEXT = 0xFFF2F2F5; TEXT_DIM = 0xFF8A8A96; TEXT_FAINT = 0xFF55555F; TEXT_SOFT = 0xFFA8A8B2; TEXT_HEADER = 0xFF6E6E7A;
        switch (preset) {
            case "Midnight" -> surfaces(0xF20A0D16, 0xFF0C1020, 0xFF131A2A, 0xFF1A2236, 0xFF26304A, 0xFF0E1322, 0xFF161D2E, 0xFF20293F);
            case "OLED" -> surfaces(0xFA000000, 0xFF000000, 0xFF0B0B0B, 0xFF151515, 0xFF222222, 0xFF050505, 0xFF101010, 0xFF1C1C1C);
            case "Classic" -> {
                // Vanilla-style stone grey with black outlines and square corners.
                surfaces(0xE8181818, 0xFF262626, 0xFF373737, 0xFF454545, 0xFF0A0A0A, 0xFF1E1E1E, 0xFF2E2E2E, 0xFF505050);
                TEXT = 0xFFFFFFFF; TEXT_DIM = 0xFFA8A8A8; TEXT_FAINT = 0xFF6C6C6C; TEXT_SOFT = 0xFFC6C6C6; TEXT_HEADER = 0xFF8A8A8A;
                HUD_BG = 0xA0101010;
                square = true;
            }
            default -> surfaces(0xF20A0A0C, 0xFF0E0E11, 0xFF15151A, 0xFF1C1C22, 0xFF26262D, 0xFF101013, 0xFF17171C, 0xFF24242C);
        }
    }

    private static void surfaces(int bg, int sidebar, int card, int cardHover, int border, int input, int control, int controlHover) {
        BG = bg; SIDEBAR = sidebar; CARD = card; CARD_HOVER = cardHover; BORDER = border;
        INPUT = input; CONTROL = control; CONTROL_HOVER = controlHover;
        DIVIDER = cardHover;
        TRACK = lerpColor(controlHover, 0xFF808080, 0.12f);
        BORDER_HOVER = lerpColor(border, 0xFFFFFFFF, 0.12f);
        ROW = lerpColor(input, card, 0.4f);
        ROW_HOVER = lerpColor(card, cardHover, 0.4f);
        HUD_BG = 0x90000000 | (bg & 0xFFFFFF);
    }

    /** The card colour blended toward the accent: used for selected and enabled items. */
    public static int tint(float amount) {
        return lerpColor(CARD, RED, amount);
    }

    private Theme() {}

    /** Rectangle with 2px rounded corners. */
    public static void round(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
        if (square || x2 - x1 < 4 || y2 - y1 < 4) { ctx.fill(x1, y1, x2, y2, color); return; }
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
        int track = lerpColor(TRACK, RED, t);
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
