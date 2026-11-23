package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2f;

/**
 * Evade's colours and shapes, shared by the menu and the HUD. The colours change with the Theme and Accent chosen in
 * Client Settings ({@link #apply}); {@code RED} is the accent colour (red by default). The shape engine draws
 * anti-aliased rounded rects, rings, hairlines, pills, discs, soft shadows and fade lines from small textures, and
 * honours the group {@link #fade}.
 */
public final class Theme {
    public static int RED = 0xFFE11D2E;
    /** The accent made more vivid (lighter but still saturated): active labels, counts and hover highlights. */
    public static int RED_BRIGHT = 0xFFFF4051;
    public static int RED_DARK = 0xFF7A0D18;
    public static int BG = 0xF20A0A0C;
    public static int SIDEBAR = 0xFF0E0E11;
    public static int CARD = 0xFF15151A;
    public static int CARD_HOVER = 0xFF1C1C22;
    public static int BORDER = 0xFF26262D;
    /** Text and icons drawn on a solid accent fill: dark ink on light accents (Gold, Green, Cyan, Orange), white otherwise. */
    public static int ON_ACCENT = 0xFFFFFFFF;
    public static int TEXT = 0xFFF4F4F6;
    public static int TEXT_DIM = 0xFF9B9BA6;
    public static int TEXT_FAINT = 0xFF5E5E69;
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

    /** Status colours, the same in every theme: success / on, warning, and destructive or error. */
    public static final int OK = 0xFF3DDC84, WARN = 0xFFFFB020, DANGER = 0xFFFF4D5E;

    /** Corner radii in GUI pixels: chips and small badges, regular controls and cards, the menu window, HUD panels. */
    public static final int R_XS = 2, R = 3, R_WIN = 5, R_HUD = 3;

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
        RED_BRIGHT = brighten(RED);
        RED_DARK = lerpColor(RED, 0xFF000000, 0.46f);
        ON_ACCENT = relLum(RED) > 0.30f ? 0xFF141418 : 0xFFFFFFFF;
        square = false;
        TEXT = 0xFFF4F4F6; TEXT_DIM = 0xFF9B9BA6; TEXT_FAINT = 0xFF5E5E69; TEXT_SOFT = 0xFFA8A8B2; TEXT_HEADER = 0xFF6E6E7A;
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

    /** A colour made more vivid: less saturation, more brightness (red E11D2E becomes FF4051). Opaque result. */
    public static int brighten(int color) {
        float[] h = java.awt.Color.RGBtoHSB(color >> 16 & 255, color >> 8 & 255, color & 255, null);
        return 0xFF000000 | java.awt.Color.HSBtoRGB(h[0], h[1] * 0.86f, Math.min(1f, h[2] * 1.14f));
    }

    /** WCAG relative luminance (0 black .. 1 white) of a colour's RGB, ignoring alpha. */
    public static float relLum(int color) {
        return 0.2126f * linear(color >> 16 & 255) + 0.7152f * linear(color >> 8 & 255) + 0.0722f * linear(color & 255);
    }

    private static float linear(int channel) {
        float c = channel / 255f;
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
    }

    /** The card colour blended toward the accent: used for selected and enabled items. */
    public static int tint(float amount) {
        return lerpColor(CARD, RED, amount);
    }

    private Theme() {}

    // ------------------------------------------------------------------ shape engine
    //
    // Every shape is built from plain fills and quads cut from small anti-aliased textures in textures/gui/shape
    // (white, tinted by the vertex colour, sampled LINEAR through their .mcmeta). The textures hold 4 texels per
    // GUI pixel, so a corner of radius r is an exact r x r quarter of disc_r<r>. Pieces never overlap, so
    // translucent colours blend once. In the Classic theme ({@link #square}) every rounded shape is a plain rect.
    //
    // Every public drawing method multiplies its colours by the group {@link #fade} exactly once.

    /** Group opacity, 0..1, multiplied into every colour drawn through Theme, {@link Icons} and {@link Logo#lockup}.
     *  Set it around a group (save the old value, multiply, restore in a finally); the HUD always draws at 1. */
    public static float fade = 1f;

    /** Corner bits for {@link #round(DrawContext, int, int, int, int, int, int, int)}: top-left, top-right,
     *  bottom-left, bottom-right, and all four. A corner whose bit is clear is drawn square. */
    public static final int TL = 1, TR = 2, BL = 4, BR = 8, ALL = 15;

    private static final Identifier[] DISC = new Identifier[9], RING = new Identifier[9], HAIR = new Identifier[9];
    private static final Identifier FADE_H = shape("fade_h"), KNOB_SHADOW = shape("shadow_knob");

    static {
        for (int r = 1; r <= 8; r++) {
            DISC[r] = shape("disc_r" + r);
            if (r >= 2) { RING[r] = shape("ring_r" + r); HAIR[r] = shape("hair_r" + r); }
        }
    }

    private static Identifier shape(String name) {
        return Identifier.of("evade", "textures/gui/shape/" + name + ".png");
    }

    /**
     * A nine-slice soft shadow texture: {@code texels} square, covering {@code logical} GUI pixels; each corner
     * slice is {@code slice} GUI pixels and the shadow reaches {@code pad} pixels outside the shape. A knockout
     * shadow has the (rounded) body cut out, so it can sit under translucent panels without darkening them.
     */
    public record Shadow(Identifier tex, int texels, int logical, int slice, int pad, boolean knockout) {}

    /** The menu window's drop shadow and halo (radius 5, wide and soft, not knocked out). */
    public static final Shadow SH_WINDOW = new Shadow(shape("shadow_window"), 168, 84, 41, 18, false);
    /** Cards, inputs and hover glows (radius 3). */
    public static final Shadow SH_CARD = new Shadow(shape("shadow_card"), 128, 32, 15, 6, true);
    /** HUD panels and toasts (radius 3, tight). */
    public static final Shadow SH_HUD = new Shadow(shape("shadow_hud"), 112, 28, 13, 5, true);

    /** {@code c} with the group {@link #fade} applied. */
    public static int fa(int c) {
        return fade >= 1f ? c : alpha(c, fade);
    }

    /** The GUI scale: device pixels per GUI pixel (at least 1). */
    public static int gs() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null) return 1;
        return Math.max(1, mc.getWindow().getScaleFactor());
    }

    /** {@code v} rounded to the nearest device pixel, for fractional translations (animations, scrolling). */
    public static float snap(float v) {
        int k = gs();
        return Math.round(v * k) / (float) k;
    }

    /** Device pixels per GUI pixel at the current matrix: the GUI scale times any scale pushed on the matrix stack
     *  (HUD element scale). Used to pick a texture size that is never magnified. */
    public static float deviceScale(DrawContext ctx) {
        Matrix3x2f m = ctx.getMatrices();
        float sx = (float) Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01());
        float sy = (float) Math.sqrt(m.m10() * m.m10() + m.m11() * m.m11());
        float s = Math.max(sx, sy);
        return gs() * (s > 1e-4f && Float.isFinite(s) ? s : 1f);
    }

    private static boolean invisible(int c) {
        return c >>> 24 == 0;
    }

    /** A plain rectangle. Empty or reversed rects draw nothing (DrawContext.fill would swap them into a visible rect). */
    public static void fill(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
        rawFill(ctx, x1, y1, x2, y2, fa(color));
    }

    private static void rawFill(DrawContext ctx, int x1, int y1, int x2, int y2, int c) {
        if (x2 <= x1 || y2 <= y1 || invisible(c)) return;
        ctx.fill(x1, y1, x2, y2, c);
    }

    /** One quad of a shape texture: {@code w x h} GUI px at (x, y) from the {@code rw x rh} texel region at (u, v). */
    private static void quad(DrawContext ctx, Identifier tex, int x, int y, int w, int h, float u, float v,
                             int rw, int rh, int texW, int texH, int c) {
        if (w <= 0 || h <= 0) return;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, tex, x, y, u, v, w, h, rw, rh, texW, texH, c);
    }

    /** Rectangle with anti-aliased corners of the standard radius {@link #R}. */
    public static void round(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
        roundRaw(ctx, x1, y1, x2, y2, fa(color), R, ALL);
    }

    /** Rectangle with anti-aliased corners of radius {@code r} (clamped to half the shorter side). */
    public static void round(DrawContext ctx, int x1, int y1, int x2, int y2, int color, int r) {
        roundRaw(ctx, x1, y1, x2, y2, fa(color), r, ALL);
    }

    /** Rectangle rounding only the corners in {@code corners} ({@link #TL} | {@link #TR} | {@link #BL} | {@link #BR}). */
    public static void round(DrawContext ctx, int x1, int y1, int x2, int y2, int color, int r, int corners) {
        roundRaw(ctx, x1, y1, x2, y2, fa(color), r, corners);
    }

    private static void roundRaw(DrawContext ctx, int x1, int y1, int x2, int y2, int c, int r, int corners) {
        if (x2 <= x1 || y2 <= y1 || invisible(c)) return;
        r = Math.min(r, Math.min((x2 - x1) / 2, (y2 - y1) / 2));
        if (square || r <= 0 || (corners & ALL) == 0) { ctx.fill(x1, y1, x2, y2, c); return; }
        int t = Math.min(r, 8), q = 4 * t, n = 8 * t;
        Identifier tex = DISC[t];
        // Four corners (a quarter of the disc each, or a square where the bit is clear) ...
        if ((corners & TL) != 0) quad(ctx, tex, x1, y1, r, r, 0, 0, q, q, n, n, c); else ctx.fill(x1, y1, x1 + r, y1 + r, c);
        if ((corners & TR) != 0) quad(ctx, tex, x2 - r, y1, r, r, q, 0, q, q, n, n, c); else ctx.fill(x2 - r, y1, x2, y1 + r, c);
        if ((corners & BL) != 0) quad(ctx, tex, x1, y2 - r, r, r, 0, q, q, q, n, n, c); else ctx.fill(x1, y2 - r, x1 + r, y2, c);
        if ((corners & BR) != 0) quad(ctx, tex, x2 - r, y2 - r, r, r, q, q, q, q, n, n, c); else ctx.fill(x2 - r, y2 - r, x2, y2, c);
        // ... then three bands that tile the rest without overlapping.
        rawFill(ctx, x1 + r, y1, x2 - r, y1 + r, c);
        rawFill(ctx, x1, y1 + r, x2, y2 - r, c);
        rawFill(ctx, x1 + r, y2 - r, x2 - r, y2, c);
    }

    /** Fully rounded ends (radius half the shorter side): a capsule, or a circle for a square rect. */
    public static void pill(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
        roundRaw(ctx, x1, y1, x2, y2, fa(color), Math.min(x2 - x1, y2 - y1) / 2, ALL);
    }

    /** A filled anti-aliased circle of diameter {@code d} with its top-left at (x, y). */
    public static void disc(DrawContext ctx, int x, int y, int d, int color) {
        int c = fa(color);
        if (d <= 0 || invisible(c)) return;
        if (square) { ctx.fill(x, y, x + d, y + d, c); return; }
        int t = Math.max(1, Math.min(8, d / 2)), n = 8 * t;
        quad(ctx, DISC[t], x, y, d, d, 0, 0, n, n, n, n, c);
    }

    /** A 1 px anti-aliased border of corner radius {@code r}, inside the rect. */
    public static void stroke(DrawContext ctx, int x1, int y1, int x2, int y2, int color, int r) {
        strokeRaw(ctx, x1, y1, x2, y2, fa(color), r, ALL);
    }

    /** A 1 px border rounding only the corners in {@code corners}; the others get a square corner. */
    public static void stroke(DrawContext ctx, int x1, int y1, int x2, int y2, int color, int r, int corners) {
        strokeRaw(ctx, x1, y1, x2, y2, fa(color), r, corners);
    }

    private static void strokeRaw(DrawContext ctx, int x1, int y1, int x2, int y2, int c, int r, int corners) {
        if (x2 <= x1 || y2 <= y1 || invisible(c)) return;
        r = Math.min(r, Math.min((x2 - x1) / 2, (y2 - y1) / 2));
        if (square || r < 2 || x2 - x1 < 4 || y2 - y1 < 4 || (corners & ALL) == 0) { frame(ctx, x1, y1, x2, y2, c); return; }
        int t = Math.min(r, 8), q = 4 * t, n = 8 * t;
        Identifier tex = RING[t];
        if ((corners & TL) != 0) quad(ctx, tex, x1, y1, r, r, 0, 0, q, q, n, n, c);
        else { ctx.fill(x1, y1, x1 + r, y1 + 1, c); rawFill(ctx, x1, y1 + 1, x1 + 1, y1 + r, c); }
        if ((corners & TR) != 0) quad(ctx, tex, x2 - r, y1, r, r, q, 0, q, q, n, n, c);
        else { ctx.fill(x2 - r, y1, x2, y1 + 1, c); rawFill(ctx, x2 - 1, y1 + 1, x2, y1 + r, c); }
        if ((corners & BL) != 0) quad(ctx, tex, x1, y2 - r, r, r, 0, q, q, q, n, n, c);
        else { ctx.fill(x1, y2 - 1, x1 + r, y2, c); rawFill(ctx, x1, y2 - r, x1 + 1, y2 - 1, c); }
        if ((corners & BR) != 0) quad(ctx, tex, x2 - r, y2 - r, r, r, q, q, q, q, n, n, c);
        else { ctx.fill(x2 - r, y2 - 1, x2, y2, c); rawFill(ctx, x2 - 1, y2 - r, x2, y2 - 1, c); }
        rawFill(ctx, x1 + r, y1, x2 - r, y1 + 1, c);
        rawFill(ctx, x1 + r, y2 - 1, x2 - r, y2, c);
        rawFill(ctx, x1, y1 + r, x1 + 1, y2 - r, c);
        rawFill(ctx, x2 - 1, y1 + r, x2, y2 - r, c);
    }

    /** The square 1 px frame: four fills that do not overlap. */
    private static void frame(DrawContext ctx, int x1, int y1, int x2, int y2, int c) {
        ctx.fill(x1, y1, x2, y1 + 1, c);
        if (y2 - y1 >= 2) rawFill(ctx, x1, y2 - 1, x2, y2, c);
        rawFill(ctx, x1, y1 + 1, x1 + 1, y2 - 1, c);
        if (x2 - x1 >= 2) rawFill(ctx, x2 - 1, y1 + 1, x2, y2 - 1, c);
    }

    /** A half-pixel anti-aliased border of corner radius {@code r} (one device pixel at GUI scale 2), on the
     *  rect's outer edge. Square, tiny or r &lt; 2 rects get the 1 px frame. */
    public static void hairline(DrawContext ctx, int x1, int y1, int x2, int y2, int color, int r) {
        int c = fa(color);
        if (x2 <= x1 || y2 <= y1 || invisible(c)) return;
        r = Math.min(r, Math.min((x2 - x1) / 2, (y2 - y1) / 2));
        if (square || r < 2 || x2 - x1 < 4 || y2 - y1 < 4) { frame(ctx, x1, y1, x2, y2, c); return; }
        int t = Math.min(r, 8), q = 4 * t, n = 8 * t, w = x2 - x1 - 2 * r, h = y2 - y1 - 2 * r;
        Identifier tex = HAIR[t];
        quad(ctx, tex, x1, y1, r, r, 0, 0, q, q, n, n, c);
        quad(ctx, tex, x2 - r, y1, r, r, q, 0, q, q, n, n, c);
        quad(ctx, tex, x1, y2 - r, r, r, 0, q, q, q, n, n, c);
        quad(ctx, tex, x2 - r, y2 - r, r, r, q, q, q, q, n, n, c);
        // Straight sides: one texel column (or row) at the middle of the texture's straight edge, 4 texels
        // (one GUI px) deep, stretched along the side. Only its outer half is opaque: a true 0.5 px line.
        quad(ctx, tex, x1 + r, y1, w, 1, q - 1, 0, 1, 4, n, n, c);
        quad(ctx, tex, x1 + r, y2 - 1, w, 1, q - 1, n - 4, 1, 4, n, n, c);
        quad(ctx, tex, x1, y1 + r, 1, h, 0, q - 1, 4, 1, n, n, c);
        quad(ctx, tex, x2 - 1, y1 + r, 1, h, n - 4, q - 1, 4, 1, n, n, c);
    }

    /** Rounded rectangle (radius {@link #R}) with a 1 px border: the fill first, then the ring over its edge, so a
     *  translucent fill never shows through the border. */
    public static void roundBordered(DrawContext ctx, int x1, int y1, int x2, int y2, int fill, int border) {
        roundRaw(ctx, x1, y1, x2, y2, fa(fill), R, ALL);
        strokeRaw(ctx, x1, y1, x2, y2, fa(border), R, ALL);
    }

    /** The soft shadow under an 8 px toggle knob at (x, y) (14 x 14, centred on the knob). */
    public static void knobShadow(DrawContext ctx, int x, int y, int color) {
        int c = fa(color);
        if (invisible(c)) return;
        quad(ctx, KNOB_SHADOW, x - 3, y - 3, 14, 14, 0, 0, 56, 56, 56, 56, c);
    }

    /** A 1 px horizontal line that fades in over its first and out over its last 16 texels (a quarter of its length). */
    public static void fadeLine(DrawContext ctx, int x1, int y, int x2, int color) {
        int c = fa(color);
        if (x2 <= x1 || invisible(c)) return;
        quad(ctx, FADE_H, x1, y, x2 - x1, 1, 0, 0, 64, 1, 64, 1, c);
    }

    /**
     * Soft shadow (or glow, in any colour) around the rect, nine-sliced from {@code s}: four corners and four
     * stretched edges, never the centre (at most 8 quads). Small rects clamp the slices per axis, so the shape stays
     * continuous down to tiny panels. Knockout shadows are skipped when square: their rounded hole would not match
     * a square body.
     */
    public static void shadow(DrawContext ctx, Shadow s, int x1, int y1, int x2, int y2, int color) {
        int c = fa(color);
        if (x2 <= x1 || y2 <= y1 || invisible(c) || (square && s.knockout())) return;
        int T = s.texels(), d = T / s.logical(), P = s.pad(), C = s.slice();
        int X1 = x1 - P, Y1 = y1 - P, X2 = x2 + P, Y2 = y2 + P;
        int cx = Math.min(C, P + (x2 - x1) / 2), cy = Math.min(C, P + (y2 - y1) / 2);
        int tx = cx * d, ty = cy * d;
        int iw = X2 - X1 - 2 * cx, ih = Y2 - Y1 - 2 * cy;
        int m0 = C * d, mw = T - 2 * C * d;   // the 2-logical-px middle strip the edges stretch
        Identifier tex = s.tex();
        quad(ctx, tex, X1, Y1, cx, cy, 0, 0, tx, ty, T, T, c);
        quad(ctx, tex, X2 - cx, Y1, cx, cy, T - tx, 0, tx, ty, T, T, c);
        quad(ctx, tex, X1, Y2 - cy, cx, cy, 0, T - ty, tx, ty, T, T, c);
        quad(ctx, tex, X2 - cx, Y2 - cy, cx, cy, T - tx, T - ty, tx, ty, T, T, c);
        quad(ctx, tex, X1 + cx, Y1, iw, cy, m0, 0, mw, ty, T, T, c);
        quad(ctx, tex, X1 + cx, Y2 - cy, iw, cy, m0, T - ty, mw, ty, T, T, c);
        quad(ctx, tex, X1, Y1 + cy, cx, ih, 0, m0, tx, mw, T, T, c);
        quad(ctx, tex, X2 - cx, Y1 + cy, cx, ih, T - tx, m0, tx, mw, T, T, c);
    }

    /** The background style of the HUD element being drawn (set around each element's render). */
    public static String hudStyle = "Evade";
    /** Background opacity of the HUD element being drawn, 0..1. */
    public static float hudOpacity = 1f;

    /** Fill colour for HUD backgrounds in the current style and opacity. */
    public static int hudFill() {
        return switch (hudStyle) {
            case "Solid" -> alpha(CARD | 0xFF000000, 0.94f * hudOpacity);
            case "Glass" -> alpha(lerpColor(HUD_BG | 0xFF000000, 0xFFFFFFFF, 0.1f), 0.5f * hudOpacity);
            case "Outline" -> 0;
            default -> alpha(HUD_BG, hudOpacity);
        };
    }

    /** HUD element background in the element's chosen style and opacity (drawn by
     *  {@link net.evadeclient.hud.HudDraw#panel}): a soft knockout shadow, then Evade (dark glass with an accent top
     *  edge that fades out before the rounded corners), Solid, Glass (light frosted panel with a thin rim and a top
     *  highlight) or Outline (accent border only, no shadow). Classic squares it and drops the shadow. */
    public static void hudBox(DrawContext ctx, int x, int y, int w, int h) {
        hudBox(ctx, x, y, w, h, true);
    }

    /** {@link #hudBox(DrawContext, int, int, int, int)} with or without the Evade style's accent top edge
     *  (sub-panels inside a widget, like Keystrokes' keys, leave it off). */
    public static void hudBox(DrawContext ctx, int x, int y, int w, int h, boolean accentEdge) {
        net.evadeclient.hud.HudDraw.panel(ctx, x, y, w, h, accentEdge);
    }

    /**
     * The 20 x 10 on/off switch at (x, y). {@code t} is the animation progress 0..1: the capsule track blends from
     * {@link #TRACK} to the accent while the round knob (with a soft contact shadow) slides across in device pixels.
     * Classic keeps its square track and knob, which step a whole GUI pixel at a time like vanilla widgets.
     */
    public static void toggle(DrawContext ctx, int x, int y, float t) {
        int w = 20, h = 10;
        t = Math.max(0f, Math.min(1f, t));
        int track = lerpColor(TRACK, RED, t);
        if (square) {
            round(ctx, x, y, x + w, y + h, track);
            int knobX = x + 1 + Math.round((w - h) * t);
            round(ctx, knobX, y + 1, knobX + h - 2, y + h - 1, 0xFFFFFFFF);
            return;
        }
        pill(ctx, x, y, x + w, y + h, track);
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(snap(ctx, (w - h) * t), 0);
        knobShadow(ctx, x + 1, y + 2, alpha(0xFF000000, 0.35f));
        disc(ctx, x + 1, y + 1, h - 2, 0xFFFFFFFF);
        m.popMatrix();
    }

    /**
     * {@code v} (a length in the current matrix's units) rounded to the nearest device pixel at that matrix's scale:
     * like {@link #snap(float)}, but also right inside a scaled group (the menu drawn smaller to fit the screen, a
     * scaled HUD element). Use it for fractional offsets of animated parts.
     */
    public static float snap(DrawContext ctx, float v) {
        float k = deviceScale(ctx);
        return k > 0f && Float.isFinite(k) ? Math.round(v * k) / k : v;
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
