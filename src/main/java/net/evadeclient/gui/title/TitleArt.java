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
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.evadeclient.render.ui.UiShape;

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

    /** Client Settings' Animations, and not the Title Screen's Reduce Motion. */
    public static boolean anims() {
        ClientSettings s = settings();
        return (s == null || s.animations.full()) && !net.evadeclient.module.misc.TitleScreenModule.reduceMotion();
    }

    // ------------------------------------------------------------------ text

    /**
     * Design units: the menu is laid out in GUI px divided by this whole number and drawn under a matching matrix
     * scale, so it fills large screens at small GUI scales (1080p at GUI 1, 4K at GUI 1-2). The smooth font is then
     * picked for {@code guiScale x zoom} device pixels per unit, so glyphs are still rasterised 1:1 (never magnified).
     */
    private static int zoom = 1;

    /** Sets the design-unit zoom (see {@link #zoom}); 1 outside the title screen. */
    public static void zoom(int z) { zoom = Math.max(1, z); }

    public static int zoom() { return zoom; }

    /** Device pixels per design unit, as a font file index (1..8). */
    static int fontG() {
        return Math.max(UiFont.MIN_G, Math.min(UiFont.MAX_G, Theme.gs() * zoom));
    }

    private static MutableText styled(String s, Role r) {
        return Text.literal(s).setStyle(UiFont.style(r, fontG()));
    }

    /** {@code s} in role {@code r} with its top-left at (x, y), faded with {@link Theme#fade}. */
    public static void text(DrawContext ctx, String s, int x, int y, int color, Role r) {
        if (s == null || s.isEmpty()) return;
        if (!smooth()) { UiFont.draw(ctx, MinecraftClient.getInstance().textRenderer, s, x, y, color, r, false); return; }
        int c = Theme.fa(color);
        if (c >>> 24 == 0) return;
        ctx.drawText(MinecraftClient.getInstance().textRenderer, styled(s, r), x, y, c, false);
    }

    /** {@code s} with its capital letters optically centred on {@code centreY} (snapped to a device pixel). */
    public static void textMid(DrawContext ctx, String s, int x, float centreY, int color, Role r) {
        float y = Theme.snap(ctx, centreY + r.capHeight(smooth()) / 2f - r.baseline());
        int iy = (int) Math.floor(y);
        if (y == iy) { text(ctx, s, x, iy, color, r); return; }
        var m = ctx.getMatrices();
        m.pushMatrix();
        try { m.translate(0, y - iy); text(ctx, s, x, iy, color, r); } finally { m.popMatrix(); }
    }

    public static int width(String s, Role r) {
        if (s == null || s.isEmpty()) return 0;
        var tr = MinecraftClient.getInstance().textRenderer;
        return smooth() ? tr.getWidth(styled(s, r)) : UiFont.width(tr, s, r, false);
    }

    /** Tracked small caps (section headers): each letter at its advance plus {@code tracking}, snapped to a device pixel. */
    public static void caps(DrawContext ctx, String s, int x, int y, int color, Role r, float tracking) {
        if (s == null || s.isEmpty()) return;
        var tr = MinecraftClient.getInstance().textRenderer;
        if (!smooth()) { UiFont.caps(ctx, tr, s, x, y, color, r, tracking, false); return; }
        int c = Theme.fa(color);
        if (c >>> 24 == 0) return;
        String up = s.toUpperCase(java.util.Locale.ROOT);
        var m = ctx.getMatrices();
        float pen = 0;
        for (int i = 0; i < up.length(); ) {
            int cp = up.codePointAt(i);
            i += Character.charCount(cp);
            Text ch = styled(new String(Character.toChars(cp)), r);
            if (!Character.isWhitespace(cp)) {
                m.pushMatrix();
                try {
                    m.translate(Theme.snap(ctx, x + pen), y);
                    ctx.drawText(tr, ch, 0, 0, c, false);
                } finally {
                    m.popMatrix();
                }
            }
            pen += tr.getTextHandler().getWidth(ch) + tracking;
        }
    }

    public static int capsWidth(String s, Role r, float tracking) {
        if (s == null || s.isEmpty()) return 0;
        var tr = MinecraftClient.getInstance().textRenderer;
        if (!smooth()) return UiFont.capsWidth(tr, s, r, tracking, false);
        String up = s.toUpperCase(java.util.Locale.ROOT);
        float pen = 0;
        int n = 0;
        for (int i = 0; i < up.length(); ) {
            int cp = up.codePointAt(i);
            i += Character.charCount(cp);
            pen += tr.getTextHandler().getWidth(styled(new String(Character.toChars(cp)), r));
            n++;
        }
        return Math.round(pen + tracking * Math.max(0, n - 1));
    }

    /** {@code s} cut to {@code max} GUI pixels in role {@code r}, with an ellipsis when it had to be cut. */
    public static String ellipsize(String s, int max, Role r) {
        if (s == null) return "";
        if (width(s, r) <= max) return s;
        int ell = width("\u2026", r);
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (width(s.substring(0, mid), r) + ell <= max) lo = mid; else hi = mid - 1;
        }
        return s.substring(0, lo).stripTrailing() + "\u2026";
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

    // ------------------------------------------------------------------ light (shader: dithered, no banding)

    /** Whether soft light is drawn by the shape shader (dithered, additive) this frame; else the textures above. */
    public static boolean gpu() {
        return UiShape.available();
    }

    /**
     * Emissive light: an additive, dithered elliptical glow centred on (cx, cy) fading out by (rx, ry). The colour's
     * alpha is the peak strength. Light adds to what is under it, so a red glow on dark glass reads as light, never
     * as a red smear. Without the shader: the soft glow texture (alpha blended).
     */
    public static void softGlow(DrawContext ctx, float cx, float cy, float rx, float ry, int color) {
        int c = Theme.fa(color);
        if (c >>> 24 == 0 || !(rx > 1) || !(ry > 1)) return;
        if (!UiShape.available()) {
            glow(ctx, Math.round(cx), Math.round(cy), Math.round(rx * 2), Math.round(ry * 2), color);
            return;
        }
        // A small core under a wide Gaussian: the shader's erf edge reaches its centre at ~0.58, so lift it by 1.7.
        // Additive light only needs colour x strength: fold the strength into the colour and send it opaque, so the
        // shader's dither runs at full amplitude across the whole falloff (it fades the dither out with alpha).
        c = emissive(c, (c >>> 24) / 255f * 1.7f);
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            m.translate(cx, cy);
            if (ry != rx) m.scale(1f, ry / rx);
            float core = rx * 0.06f;
            UiShape.shape(-core, -core, core, core).radius(core).fill(c).raw().asGlow(rx * 0.31f, false).draw(ctx);
        } finally {
            m.popMatrix();
        }
    }

    /** {@code rgb x k} as an opaque colour (additive light with its strength folded in). */
    static int emissive(int argb, float k) {
        k = Math.max(0f, Math.min(1f, k));
        int r = Math.round((argb >> 16 & 255) * k), g = Math.round((argb >> 8 & 255) * k), b = Math.round((argb & 255) * k);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /**
     * The stage: one full-screen, dithered radial darkening (shader only), elliptical, lightest at (cx, cy) in
     * {@code centre} and reaching {@code edge} at the screen's sides. It replaces a flat veil plus the vignette
     * texture (two full-screen passes) with one.
     */
    public static void stage(DrawContext ctx, int w, int h, float cx, float cy, int centre, int edge) {
        float aspect = Math.max(1f, w / (float) Math.max(1, h)) * 0.92f;
        float r = h * 0.86f;
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            m.translate(cx, cy);
            m.scale(aspect, 1f);
            float x1 = -cx / aspect, x2 = (w - cx) / aspect, y1 = -cy, y2 = h - cy;
            UiShape.shape(x1, y1, x2, y2).radial(centre, edge, -x1, -y1, r).draw(ctx);
        } finally {
            m.popMatrix();
        }
    }

    /**
     * The kit tooltip (e5 surface, r5, BODY text up to 220 wide) placed above the target box (below it when there is
     * no room), never covering it. {@code a} is its fade-in (0..1).
     */
    public static void tooltip(DrawContext ctx, String text, int tx1, int ty1, int tx2, int ty2, int sw, int sh, float a) {
        if (text == null || text.isEmpty() || a <= 0.01f) return;
        java.util.List<String> lines = new java.util.ArrayList<>(3);
        int maxW = 220, w = 0;
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String tr = line.isEmpty() ? word : line + " " + word;
            if (width(tr, Role.BODY) <= maxW || line.isEmpty()) { line.setLength(0); line.append(tr); continue; }
            lines.add(line.toString());
            line.setLength(0);
            line.append(word);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        for (String l : lines) w = Math.max(w, width(l, Role.BODY));
        int padX = 8, padY = 6, lh = 11;
        int bw = w + padX * 2, bh = lines.size() * lh - 2 + padY * 2;
        int x = Math.max(4, Math.min(sw - 4 - bw, (tx1 + tx2) / 2 - bw / 2));
        int y = ty1 - 6 - bh;
        boolean below = y < 4;
        if (below) y = ty2 + 6;
        float base = Theme.fade;
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            Theme.fade = base * a;
            m.translate(0, Theme.snap(ctx, (1f - a) * (below ? 4f : -4f)));
            var e = net.evadeclient.util.Tokens.E5;
            if (UiShape.available()) {
                UiShape.shape(x, y, x + bw, y + bh).radius(net.evadeclient.util.Tokens.R_SM).fill(Theme.alpha(0xFF000000, 0.5f))
                        .asShadow(5f, 3f, true).draw(ctx);
                UiShape.shape(x, y, x + bw, y + bh).radius(net.evadeclient.util.Tokens.R_SM).fill(e.fill())
                        .border(Theme.alpha(0xFFFFFFFF, e.border()), 1).rim(e.rim()).draw(ctx);
            } else {
                Theme.shadow(ctx, Theme.SH_CARD, x, y + 2, x + bw, y + bh + 2, 0x80000000);
                Theme.round(ctx, x, y, x + bw, y + bh, e.fill() | 0xFF000000, 4);
                Theme.hairline(ctx, x, y, x + bw, y + bh, 0x24FFFFFF, 4);
            }
            int ly = y + padY;
            for (String l : lines) {
                textMid(ctx, l, x + padX, ly + 4f, net.evadeclient.util.Tokens.N950, Role.BODY);
                ly += lh;
            }
        } finally {
            Theme.fade = base;
            m.popMatrix();
        }
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
