package net.evadeclient.gui.title;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Logo;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * Drawing helpers for Evade's main menu: its own line icons (textures/title, a 24/48/96 px chain like the menu's nav
 * icons, which it also uses), the Evade logo with a larger mip chain than {@link Logo} for big sizes on high-DPI
 * screens, and the soft textures of the backdrop (glow, vignette, ramp, sheen). Every colour goes through
 * {@link Theme#fa}, so the group {@link Theme#fade} applies. Render thread only.
 */
public final class TitleArt {
    private TitleArt() {}

    // ------------------------------------------------------------------ textures

    static final Identifier GLOW = tex("glow"), VIGNETTE = tex("vignette"), RAMP_H = tex("ramp_h"), SHEEN = tex("sheen");
    private static final Identifier MARK_256 = tex("mark_256"), MARK_512 = tex("mark_512"), WORD_640 = tex("word_640");
    /** Icons that ship in textures/title (the rest come from the menu's nav set through {@link Icons}). */
    private static final String[] OWN = {"users", "clips", "power", "access", "play", "external"};
    private static final Identifier[][] OWN_IDS = new Identifier[OWN.length][3];

    static {
        for (int i = 0; i < OWN.length; i++) {
            OWN_IDS[i][0] = tex(OWN[i]);
            OWN_IDS[i][1] = tex(OWN[i] + "_48");
            OWN_IDS[i][2] = tex(OWN[i] + "_96");
        }
    }

    private static Identifier tex(String name) {
        return Identifier.of("evade", "textures/title/" + name + ".png");
    }

    // ------------------------------------------------------------------ settings read per frame

    static ClientSettings settings() {
        return EvadeClient.modules == null ? null : EvadeClient.modules.get(ClientSettings.class);
    }

    /** Client Settings' Smooth Font (Inter) or the game's font. */
    public static boolean smooth() {
        ClientSettings s = settings();
        return s == null || s.smoothFont.get();
    }

    /** Client Settings' Animations. */
    public static boolean anims() {
        ClientSettings s = settings();
        return s == null || s.animations.get();
    }

    // ------------------------------------------------------------------ text

    /** {@code s} in role {@code r} with its top-left at (x, y), faded with {@link Theme#fade}. */
    public static void text(DrawContext ctx, String s, int x, int y, int color, Role r) {
        UiFont.draw(ctx, MinecraftClient.getInstance().textRenderer, s, x, y, color, r, smooth());
    }

    public static int width(String s, Role r) {
        return UiFont.width(MinecraftClient.getInstance().textRenderer, s, r, smooth());
    }

    /** Tracked small caps (section headers). */
    public static void caps(DrawContext ctx, String s, int x, int y, int color, Role r, float tracking) {
        UiFont.caps(ctx, MinecraftClient.getInstance().textRenderer, s, x, y, color, r, tracking, smooth());
    }

    public static int capsWidth(String s, Role r, float tracking) {
        return UiFont.capsWidth(MinecraftClient.getInstance().textRenderer, s, r, tracking, smooth());
    }

    /** {@code s} cut to {@code max} GUI pixels in role {@code r}, with an ellipsis when it had to be cut. */
    public static String ellipsize(String s, int max, Role r) {
        if (s == null) return "";
        if (width(s, r) <= max) return s;
        int ell = width("…", r);
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (width(s.substring(0, mid), r) + ell <= max) lo = mid; else hi = mid - 1;
        }
        return s.substring(0, lo).stripTrailing() + "…";
    }

    // ------------------------------------------------------------------ icons

    /** Icon {@code name} at {@code size} GUI px, tinted. Title icons first, then the menu's nav icons. */
    public static void icon(DrawContext ctx, String name, int x, int y, int size, int color) {
        if ("evade".equals(name)) {
            mark(ctx, x, y, size, Theme.fa(Logo.markTint() & 0xFFFFFF | (color & 0xFF000000)));
            return;
        }
        for (int i = 0; i < OWN.length; i++) {
            if (!OWN[i].equals(name)) continue;
            int c = Theme.fa(color);
            if (size <= 0 || c >>> 24 == 0) return;
            float px = size * Theme.deviceScale(ctx);
            int k = px <= 24 ? 0 : px <= 48 ? 1 : 2, src = 24 << k;
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, OWN_IDS[i][k], x, y, 0, 0, size, size, src, src, src, src, c);
            return;
        }
        Icons.draw(ctx, name, x, y, size, color);
    }

    // ------------------------------------------------------------------ logo

    /** The mark at {@code size} GUI px multiplied by {@code color} (already faded by the caller): Logo's chain up
     *  to 128 px, then this screen's 256 and 512 px renders of the same artwork. */
    public static void mark(DrawContext ctx, int x, int y, int size, int color) {
        if (size <= 0 || color >>> 24 == 0) return;
        float px = size * Theme.deviceScale(ctx);
        if (px <= 128) { Logo.mark(ctx, x, y, size, color); return; }
        int src = px <= 256 ? 256 : 512;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, src == 256 ? MARK_256 : MARK_512, x, y, 0, 0, size, size, src, src, src, src, color);
    }

    /** Height of the wordmark's letters for a lockup with a {@code markSize} mark. */
    public static int wordLetter(int markSize) {
        return Math.max(6, Math.round(markSize * 0.5f));
    }

    public static int lockupWidth(int markSize) {
        return markSize + gap(markSize) + Logo.wordWidth(wordLetter(markSize));
    }

    private static int gap(int markSize) {
        return Math.max(2, Math.round(markSize * 0.16f));
    }

    /** X of the wordmark in a lockup at x. */
    public static int wordX(int x, int markSize) {
        return x + markSize + gap(markSize);
    }

    /** Mark (in {@code markColor}) and wordmark (in {@code wordColor}), both faded with {@link Theme#fade}. */
    public static void lockup(DrawContext ctx, int x, int y, int markSize, int markColor, int wordColor) {
        mark(ctx, x, y, markSize, Theme.fa(markColor));
        int letter = wordLetter(markSize);
        word(ctx, wordX(x, markSize), y + (markSize - letter) / 2, letter, Theme.fa(wordColor));
    }

    /** The wordmark with letters {@code letterHeight} tall: Logo's chain, then a 640 px render for big sizes. */
    public static void word(DrawContext ctx, int x, int y, int letterHeight, int color) {
        int w = Logo.wordWidth(letterHeight);
        int h = Math.round(w * 96f / 320f);
        if (h * Theme.deviceScale(ctx) <= 96 || color >>> 24 == 0) { Logo.word(ctx, x, y, letterHeight, color); return; }
        // Same placement as Logo.word: the letters' top sits at y.
        int top = y - Math.round((h - letterHeight) * 0.42f);
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, WORD_640, x, top, 0, 0, w, h, 640, 192, 640, 192, color);
    }

    // ------------------------------------------------------------------ soft shapes

    /** A soft round glow of diameter {@code d} centred on (cx, cy), tinted. */
    public static void glow(DrawContext ctx, int cx, int cy, int dw, int dh, int color) {
        int c = Theme.fa(color);
        if (c >>> 24 == 0 || dw <= 0 || dh <= 0) return;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, GLOW, cx - dw / 2, cy - dh / 2, 0, 0, dw, dh, 256, 256, 256, 256, c);
    }

    /** A horizontal ramp from {@code color} at x1 to clear at x2 (or mirrored when {@code flip}). */
    public static void rampH(DrawContext ctx, int x1, int y1, int x2, int y2, int color, boolean flip) {
        int c = Theme.fa(color);
        if (c >>> 24 == 0 || x2 <= x1 || y2 <= y1) return;
        if (flip) ctx.drawTexture(RenderPipelines.GUI_TEXTURED, RAMP_H, x1, y1, 256, 0, x2 - x1, y2 - y1, -256, 4, 256, 4, c);
        else ctx.drawTexture(RenderPipelines.GUI_TEXTURED, RAMP_H, x1, y1, 0, 0, x2 - x1, y2 - y1, 256, 4, 256, 4, c);
    }

    public static void vignette(DrawContext ctx, int w, int h, int color) {
        int c = Theme.fa(color);
        if (c >>> 24 == 0) return;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, VIGNETTE, 0, 0, 0, 0, w, h, 256, 256, 256, 256, c);
    }

    /** A soft vertical light band {@code w} wide over (x, y1..y2). */
    public static void sheen(DrawContext ctx, int x, int y1, int w, int y2, int color) {
        int c = Theme.fa(color);
        if (c >>> 24 == 0 || w <= 0 || y2 <= y1) return;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, SHEEN, x, y1, 0, 0, w, y2 - y1, 64, 4, 64, 4, c);
    }

    /** Players as "1.2K", "18K", "1.1M". */
    public static String compact(int n) {
        if (n < 1000) return Integer.toString(Math.max(0, n));
        if (n < 10_000) return trim1(n / 1000f) + "K";
        if (n < 1_000_000) return (n / 1000) + "K";
        return trim1(n / 1_000_000f) + "M";
    }

    private static String trim1(float v) {
        int tenths = (int) (v * 10);
        return tenths % 10 == 0 ? Integer.toString(tenths / 10) : (tenths / 10) + "." + (tenths % 10);
    }
}
