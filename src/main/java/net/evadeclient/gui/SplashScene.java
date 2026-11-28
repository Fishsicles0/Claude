package net.evadeclient.gui;

/**
 * The animated Evade loading screen as pure drawing math, with no Minecraft types: everything goes through a
 * {@link Canvas} in device pixels, so the game ({@link EvadeSplash}) and an offline renderer (a visual check that
 * renders frames at chosen times to PNGs) draw exactly the same scene.
 *
 * <p>The scene: a dark background with two slowly drifting crimson glows, a faint drifting grid and a few diagonal
 * light streaks, red embers rising, and a vignette; the Evade mark scales in with a glow bloom, slides left as the
 * wordmark reveals left to right, then a light sweep crosses the logo every few seconds. Below it, a slim rounded
 * progress bar (red gradient, a moving shimmer and a glowing head) whose progress is eased and never goes
 * backwards, a status line and percentage, and rotating tips.
 *
 * <p>Everything is multiplied by the game's own fade ({@link Frame#alpha}; the bar and text by
 * {@link Frame#uiAlpha}), so the screen blends into the title screen exactly like the vanilla one. The monochrome
 * logo option gives a calm greyscale palette; a low {@link Frame#motion} (the panorama speed accessibility option)
 * slows the ambient motion and replaces the intro's scale and the light sweep with plain fades.
 *
 * <p>Allocation free per frame: the embers' parameters are precomputed and the frame input object is reused.
 */
public final class SplashScene {
    /** Device-pixel drawing surface. Textures are white masks (except {@link #MARK}) multiplied by {@code argb}. */
    public interface Canvas {
        void fill(int x0, int y0, int x1, int y1, int argb);

        /** Draws the part (u0, v0)-(u1, v1) of {@code texture} (UVs 0..1) into (x, y, w, h), tinted by {@code argb}. */
        void blit(int texture, int x, int y, int w, int h, float u0, float v0, float u1, float v1, int argb);

        void push();

        void pop();

        void translate(float x, float y);

        void scale(float s);

        void rotate(float radians);

        /** Whether the game's font can draw yet (it can't early in the first load). */
        boolean textReady();

        /** Width of text {@code id} in device pixels. */
        int textWidth(int id);

        /** Height of a line of text in device pixels. */
        int textHeight();

        /** Draws text {@code id} with its top-left at (x, y). */
        void text(int id, int x, int y, int argb);

        /**
         * Emissive light: an elliptical glow of radii (rx, ry) at (x, y), {@code alpha} its peak. The game draws it
         * additively and dithered (no banding); the default is the glow texture.
         */
        default void softGlow(float x, float y, float rx, float ry, int rgb, float alpha) {
            if (alpha <= 0.002f) return;
            blit(GLOW, Math.round(x - rx), Math.round(y - ry), Math.round(rx * 2), Math.round(ry * 2), 0, 0, 1, 1, argb(rgb, alpha));
        }

        /** The vignette over the whole surface at {@code alpha}. The game draws it as a dithered radial falloff. */
        default void vignette(int w, int h, float alpha) {
            blit(VIGNETTE, 0, 0, w, h, 0, 0, 1, 1, argb(0x000000, alpha));
        }

        /** Whether {@link #titleLogo} can draw the menu's own logo lockup (for the shared-element hand-over). */
        default boolean hasTitleLogo() { return false; }

        /** The width of the menu's lockup with a mark {@code markSize} device px tall. */
        default float titleLockupWidth(float markSize) { return markSize * 3.4f; }

        /** The menu's logo lockup (mark and wordmark) with its mark at (x, y), {@code markSize} square, at {@code alpha}. */
        default void titleLogo(float x, float y, float markSize, float alpha) {}
    }

    /** Texture ids (see scripts/draw-splash.py). */
    public static final int MARK = 0, MARK_MASK = 1, WORD = 2, GLOW = 3, EMBER = 4, LINE = 5, VIGNETTE = 6, DISC = 7;
    public static final String[] TEXTURES = {"mark", "mark_mask", "word", "glow", "ember", "line", "vignette", "disc"};

    /** Text ids: the status lines, the "Tip" label, the tips, then the percentages 0..100. */
    public static final int T_LOADING = 0, T_RELOADING = 1, T_READY = 2, T_TIP = 3, T_TIPS = 4;
    public static final String[] TIPS = {
            "Right Shift opens the Evade menu",
            "Type .bench in a world to measure your FPS",
            "Press F8 to save a clip of the last 30 seconds",
            "Hold C to zoom in",
            "Type .help in chat for every Evade command",
            "Type .t <mod> to toggle any mod from chat",
    };
    public static final int T_PERCENT = T_TIPS + TIPS.length;
    public static final int TEXT_COUNT = T_PERCENT + 101;

    /** The text for id {@code id}. */
    public static String text(int id) {
        return switch (id) {
            case T_LOADING -> "Loading Evade";
            case T_RELOADING -> "Reloading resources";
            case T_READY -> "Ready";
            case T_TIP -> "TIP";
            default -> id < T_PERCENT ? TIPS[id - T_TIPS] : (id - T_PERCENT) + "%";
        };
    }

    /** One frame's input, filled in by the caller (reuse one instance). */
    public static final class Frame {
        /** Seconds since this loading screen first drew. */
        public float time;
        /** The game's fade of the whole screen, 0..1. */
        public float alpha = 1f;
        /** The game's fade of the progress bar (and so the text), 0..1. */
        public float uiAlpha = 1f;
        /** The real load progress, 0..1 (may jump; the bar eases toward it). */
        public float progress;
        /** The load finished (the bar runs to 100% and the status says Ready). */
        public boolean complete;
        /** A resource reload (F3+T, resource packs) rather than the game's first load. */
        public boolean reloading;
        /** The monochrome logo accessibility option: a calm greyscale palette. */
        public boolean mono;
        /** Ambient motion speed, 0..1 (the panorama speed option); below {@link #CALM} the intro only fades. */
        public float motion = 1f;
        /** Device pixels. */
        public int width, height;
        /** The menu's logo slot behind this screen, device px (mark x, y and size); NaN when there is none. */
        public float slotX = Float.NaN, slotY, slotSize;
        /** Hand-over to the menu: 0..1 while this screen fades out over it (the logo flies to its slot); -1 none. */
        public float handoff = -1f;
        /** Hand-over from the menu at a reload's start: 0..1 as this screen fades in (the logo flies from its slot). */
        public float handin = -1f;
        /** Skip the logo's own entrance: it arrived from the menu already formed. */
        public boolean formed;
    }

    public static final float CALM = 0.35f;

    // Texture geometry (scripts/draw-splash.py): texel sizes, and where the letters sit inside them.
    private static final float MARK_ASPECT = 432f / 288f, WORD_ASPECT = 960f / 288f;
    /** The E's height as a fraction of the mark texture's, and the letters' height in the wordmark texture. */
    private static final float MARK_BODY = 264f / 288f, WORD_LETTERS = 168f / 288f, WORD_LETTER_MID = 138f / 288f;
    /** Letter height of the wordmark relative to the E (the lockup the menu uses). */
    private static final float WORD_TO_E = 0.55f;

    private static final int EMBERS = 44;
    private static final float[] EX = new float[EMBERS], ESPEED = new float[EMBERS], ESIZE = new float[EMBERS],
            EPHASE = new float[EMBERS], ESWAY = new float[EMBERS], EFREQ = new float[EMBERS], EHUE = new float[EMBERS];

    static {
        for (int i = 0; i < EMBERS; i++) {
            EX[i] = rand(i, 1);
            ESPEED[i] = 0.022f + 0.05f * rand(i, 2) * rand(i, 2);
            ESIZE[i] = 0.006f + 0.014f * rand(i, 3) * rand(i, 3);
            EPHASE[i] = rand(i, 4);
            ESWAY[i] = 0.004f + 0.018f * rand(i, 5);
            EFREQ[i] = 0.08f + 0.2f * rand(i, 6);
            EHUE[i] = rand(i, 7);
        }
    }

    private float shown;
    private float lastTime = -1f;
    private float textSince = -1f;
    private int tipStart;

    /** Starts a new loading screen: the bar from 0, the intro from the start, tips from {@code seed}. */
    public void reset(long seed) {
        shown = 0f;
        lastTime = -1f;
        textSince = -1f;
        tipStart = (int) Math.floorMod(seed, (long) TIPS.length);
    }

    /** The bar's eased progress (for tests). */
    public float shownProgress() {
        return shown;
    }

    // ----- the background and the logo --------------------------------------------------------------------------

    /** Draws the background effects and the logo (call after the base background colour is down). */
    public void drawScene(Canvas c, Frame f) {
        float a = clamp01(f.alpha);
        if (a <= 0.002f || f.width <= 0 || f.height <= 0) return;
        int w = f.width, h = f.height;
        float u = Math.min(w * 0.75f, h);
        float calm = f.motion < CALM ? 0f : 1f;
        float at = f.time * clamp01(f.motion); // ambient time
        boolean mono = f.mono;

        // Two slow crimson glows, drifting on Lissajous paths, and a low warm one the embers rise from.
        float cx = w * 0.5f, cy = h * 0.44f;
        glow(c, cx + (float) Math.sin(at * 0.11f) * w * 0.16f, cy + (float) Math.cos(at * 0.083f) * h * 0.10f,
                u * 1.9f, u * 1.6f, mono ? 0x9A9AA6 : 0xC8142A, a * (mono ? 0.07f : 0.22f));
        glow(c, cx - (float) Math.sin(at * 0.07f + 1.3f) * w * 0.22f, cy + (float) Math.sin(at * 0.05f) * h * 0.14f,
                u * 2.8f, u * 2.2f, mono ? 0x5A5A64 : 0x5E0A16, a * (mono ? 0.10f : 0.34f));
        glow(c, cx, h * 1.02f, w * 1.5f, h * 0.55f, mono ? 0x6A6A74 : 0xA01020, a * (mono ? 0.06f : 0.22f));

        grid(c, w, h, u, at, a * (mono ? 0.021f : 0.027f));
        streaks(c, w, h, u, at, mono ? 0xE8E8EE : 0xFF5B69, a * (mono ? 0.05f : 0.085f));
        embers(c, w, h, u, at, mono, a);

        c.vignette(w, h, a * 0.82f);

        logo(c, f, w, h, u, cx, cy, calm, a);
    }

    private static void glow(Canvas c, float x, float y, float gw, float gh, int rgb, float alpha) {
        if (alpha <= 0.002f) return;
        c.softGlow(x, y, gw / 2, gh / 2, rgb, alpha);
    }

    /** A faint grid that drifts one cell diagonally every 14 s; each line fades out toward its ends. */
    private static void grid(Canvas c, int w, int h, float u, float at, float alpha) {
        if (alpha <= 0.002f) return;
        float cell = Math.max(12f, u * 0.085f);
        int px = Math.max(1, Math.round(u / 1000f));
        float off = (at / 14f % 1f) * cell;
        int col = argb(0xFFFFFF, alpha);
        // Horizontal lines: the line texture's middle rows, stretched over the width.
        for (float y = off - cell; y < h + cell; y += cell) {
            int yi = Math.round(y);
            c.blit(LINE, 0, yi, w, px, 0, 0.47f, 1, 0.53f, col);
        }
        // Vertical lines: the same, turned a quarter.
        for (float x = off - cell; x < w + cell; x += cell) {
            c.push();
            c.translate(Math.round(x) + px, 0);
            c.rotate((float) (Math.PI / 2));
            c.blit(LINE, 0, 0, h, px, 0, 0.47f, 1, 0.53f, col);
            c.pop();
        }
    }

    /** Three long, thin diagonal light streaks gliding across, each on its own slow cycle. */
    private static void streaks(Canvas c, int w, int h, float u, float at, int rgb, float alpha) {
        if (alpha <= 0.002f) return;
        float angle = (float) Math.toRadians(-22);
        float diag = (float) Math.sqrt(w * (float) w + h * (float) h);
        for (int i = 0; i < 3; i++) {
            float period = 11f + i * 3.7f;
            float p = ((at + i * 4.1f) / period) % 1f;
            float len = diag * (0.42f + 0.12f * i);
            float thick = Math.max(2f, u * (0.010f + 0.006f * i));
            // Travel along the streak's own direction, from off one side to off the other.
            float along = -diag * 0.75f + p * diag * 1.5f;
            float across = (i - 1) * h * 0.28f + h * 0.06f;
            float ox = w * 0.5f + along * (float) Math.cos(angle) - across * (float) Math.sin(angle);
            float oy = h * 0.5f + along * (float) Math.sin(angle) + across * (float) Math.cos(angle);
            float fade = (float) Math.sin(Math.PI * p);
            c.push();
            c.translate(ox, oy);
            c.rotate(angle);
            c.blit(LINE, Math.round(-len / 2), Math.round(-thick / 2), Math.round(len), Math.round(thick), 0, 0, 1, 1,
                    argb(rgb, alpha * fade * (i == 1 ? 1f : 0.7f)));
            c.pop();
        }
    }

    /** Embers rising slowly with a gentle sway and twinkle, fading in at the bottom and out toward the top. */
    private static void embers(Canvas c, int w, int h, float u, float at, boolean mono, float a) {
        for (int i = 0; i < EMBERS; i++) {
            float travel = 1.25f;
            float p = (EPHASE[i] * travel + at * ESPEED[i]) % travel; // 0 at the bottom edge
            float y = h * (1.05f - p);
            float x = EX[i] * w + (float) Math.sin(at * EFREQ[i] * 6.2832f + EPHASE[i] * 6.2832f) * ESWAY[i] * w;
            float life = smooth(clamp01(p / 0.12f)) * (1f - smooth(clamp01((p - 0.35f) / 0.75f)));
            float twinkle = 0.65f + 0.35f * (float) Math.sin(at * (1.7f + EHUE[i] * 2.3f) + i * 1.7f);
            float s = ESIZE[i] * u * 3.2f;
            float al = a * life * twinkle * (mono ? 0.32f : 0.62f);
            if (al <= 0.003f) continue;
            int rgb = mono ? 0xC8C8D0 : mix(0xFF3A2A, 0xFF7A4A, EHUE[i] * EHUE[i]);
            c.blit(EMBER, Math.round(x - s / 2), Math.round(y - s / 2), Math.round(s), Math.round(s), 0, 0, 1, 1, argb(rgb, al));
        }
    }

    /** The mark and wordmark with their intro, bloom and light sweep. */
    private static void logo(Canvas c, Frame f, int w, int h, float u, float cx, float cy, float calm, float a) {
        float t = f.time;
        float markH = u * 0.18f, markW = markH * MARK_ASPECT;
        float eH = markH * MARK_BODY;
        float letter = eH * WORD_TO_E;
        float wordH = letter / WORD_LETTERS, wordW = wordH * WORD_ASPECT;
        float gap = eH * 0.20f;
        float total = markW + gap + wordW;
        float lockX = cx - total / 2;              // the mark's left edge in the final lockup
        float markCxFinal = lockX + markW / 2;

        // Intro timings (seconds).
        float markIn = easeOutCubic(clamp01((t - 0.10f) / 0.85f));
        float markAlpha = smooth(clamp01((t - 0.05f) / 0.40f));
        float slide = easeInOutCubic(clamp01((t - 0.70f) / 0.85f));
        float reveal = easeOutCubic(clamp01((t - 0.95f) / 0.90f));
        float scale = calm > 0 ? 0.70f + 0.30f * markIn : 1f;
        if (calm == 0) slide = reveal = smooth(clamp01((t - 0.2f) / 0.8f));
        if (f.formed) { markIn = markAlpha = slide = reveal = scale = 1f; }

        // Shared element: the menu's own lockup flies between its slot and the middle of this screen. On the way
        // out (hand-over) it is drawn at full strength while everything else fades; on the way in (a reload starting
        // over the menu) it flies from the menu into the middle, where this screen's logo takes over.
        boolean slot = !Float.isNaN(f.slotX) && c.hasTitleLogo() && (f.handoff >= 0f || f.handin >= 0f);
        float own = 1f; // this screen's own logo strength
        if (slot) {
            float tw = c.titleLockupWidth(f.slotSize);
            float s0 = total / Math.max(1f, tw);
            float m0 = f.slotSize * s0, x0 = cx - total / 2, y0 = cy - m0 / 2;
            float p, xf;
            if (f.handoff >= 0f) {
                p = easeInOutCubic(clamp01(f.handoff / 0.62f));
                xf = smooth(clamp01(f.handoff / 0.14f));
            } else {
                p = 1f - easeInOutCubic(clamp01(f.handin));
                xf = 1f - smooth(clamp01((f.handin - 0.78f) / 0.22f));
            }
            own = 1f - xf;
            float lx = x0 + (f.slotX - x0) * p, ly = y0 + (f.slotY - y0) * p, lm = m0 + (f.slotSize - m0) * p;
            c.titleLogo(lx, ly, lm, xf);
            if (own <= 0.002f) return;
        } else if (f.handin >= 0f) {
            own = f.handin * f.handin * f.handin; // no double exposure over the menu's own logo
        }
        a *= own;

        float markCx = cx + (markCxFinal - cx) * slide;
        float markCy = cy;

        // Bloom: a burst behind the mark as it lands, settling into a slow breathing glow.
        float burst = calm > 0 ? clamp01((t - 0.15f) / 0.35f) * (float) Math.exp(-Math.max(0, t - 0.5f) * 2.2f) : 0f;
        float breathe = 1f + 0.10f * (float) Math.sin(t * clamp01(f.motion) * 1.25f);
        float rest = 0.30f * markAlpha * breathe;
        int glowRgb = f.mono ? 0xBDBDC6 : 0xE11D2E;
        float glowK = f.mono ? 0.45f : 1f;
        glow(c, markCx, markCy, markH * (3.0f + 1.4f * burst), markH * (2.6f + 1.4f * burst), glowRgb,
                a * glowK * (rest + 0.75f * burst));
        // A wide, low glow under the whole lockup once it has formed.
        glow(c, cx, cy, total * 1.5f, markH * 2.0f, glowRgb, a * glowK * 0.16f * slide);

        // The wordmark, revealed left to right behind a soft edge, sliding in a touch from the left.
        // Anchored to the mark's right edge, so the word unrolls out of the mark as it slides into place.
        float wx = markCx + markW / 2 + gap - (1f - reveal) * eH * 0.25f;
        float wy = cy - wordH * WORD_LETTER_MID;
        int wordCol = argb(f.mono ? 0xE6E6EA : 0xEDEDF1, a * smooth(clamp01(reveal * 3f)));
        reveal(c, WORD, Math.round(wx), Math.round(wy), Math.round(wordW), Math.round(wordH), reveal, wordCol);

        // The mark, scaling up about its centre.
        int markTex = f.mono ? MARK_MASK : MARK;
        int markCol = argb(f.mono ? 0xF2F2F4 : 0xFFFFFF, a * markAlpha);
        c.push();
        c.translate(markCx, markCy);
        c.scale(scale);
        c.blit(markTex, Math.round(-markW / 2), Math.round(-markH / 2), Math.round(markW), Math.round(markH), 0, 0, 1, 1, markCol);
        c.pop();

        // The light sweep: first once the logo has formed, then every 5 s (not while handing over).
        if (calm > 0 && t > 2.0f && !slot) {
            float p = (t - 2.0f) % 5.0f;
            float dur = 1.15f;
            if (p < dur) {
                float s = easeInOutSine(p / dur);
                float band = markH * 0.55f;
                float x0 = lockX - band, x1 = lockX + total + band;
                float bx = x0 + (x1 - x0) * s;
                float sa = a * (f.mono ? 0.45f : 0.70f) * (float) Math.sin(Math.PI * p / dur);
                float mx = markCx - markW / 2, my = markCy - markH / 2;
                sweep(c, MARK_MASK, mx, my, markW, markH, bx, band, cy, sa);
                sweep(c, WORD, Math.round(wx), Math.round(wy), wordW, wordH, bx, band, cy, sa * 0.8f);
            }
        }
    }

    /** {@code texture} in (x, y, w, h) shown up to {@code p} of its width, the last part fading out softly. */
    private static void reveal(Canvas c, int texture, int x, int y, int w, int h, float p, int col) {
        if (p <= 0f || col >>> 24 == 0) return;
        if (p >= 1f) {
            c.blit(texture, x, y, w, h, 0, 0, 1, 1, col);
            return;
        }
        float edge = 0.14f; // the soft edge, as a fraction of the width
        float head = p * (1f + edge);       // where the edge ends (fully transparent)
        float solid = head - edge;          // fully visible up to here
        if (solid > 0) {
            int sw = Math.round(w * Math.min(1f, solid));
            c.blit(texture, x, y, sw, h, 0, 0, sw / (float) w, 1, col);
        }
        int steps = 8;
        for (int k = 0; k < steps; k++) {
            float f0 = solid + edge * k / steps, f1 = solid + edge * (k + 1) / steps;
            f0 = Math.max(0, f0);
            f1 = Math.min(1, f1);
            if (f1 <= f0) continue;
            int px0 = Math.round(w * f0), px1 = Math.round(w * f1);
            if (px1 <= px0) continue;
            float fade = 1f - (k + 0.5f) / steps;
            c.blit(texture, x + px0, y, px1 - px0, h, px0 / (float) w, 0, px1 / (float) w, 1, scaleAlpha(col, fade * fade));
        }
    }

    /**
     * A leaning band of white light across {@code texture} at (x, y, w, h): the band's centre is {@code bx} at height
     * {@code cy}, leaning right as it goes up. Drawn as rows of thin vertical slices of the texture, each in white at
     * the band's brightness there, so the light only shows on the logo's own pixels.
     */
    private static void sweep(Canvas c, int texture, float x, float y, float w, float h, float bx, float band, float cy, float alpha) {
        if (alpha <= 0.003f) return;
        int ix = Math.round(x), iy = Math.round(y), iw = Math.round(w), ih = Math.round(h);
        // Rows about 3 device pixels tall (so the lean has no visible steps), up to 40.
        int rows = Math.max(6, Math.min(40, ih / 3)), slices = 10;
        float lean = 0.42f;
        for (int r = 0; r < rows; r++) {
            int ry0 = iy + Math.round(ih * r / (float) rows), ry1 = iy + Math.round(ih * (r + 1) / (float) rows);
            if (ry1 <= ry0) continue;
            float mid = (ry0 + ry1) * 0.5f;
            float center = bx + (cy - mid) * lean;
            for (int s = 0; s < slices; s++) {
                float s0 = center - band / 2 + band * s / slices, s1 = center - band / 2 + band * (s + 1) / slices;
                int sx0 = Math.max(ix, Math.round(s0)), sx1 = Math.min(ix + iw, Math.round(s1));
                if (sx1 <= sx0) continue;
                float k = (s + 0.5f) / slices;
                float bright = (float) Math.sin(Math.PI * k);
                bright *= bright;
                c.blit(texture, sx0, ry0, sx1 - sx0, ry1 - ry0, (sx0 - ix) / (float) iw, (ry0 - iy) / (float) ih,
                        (sx1 - ix) / (float) iw, (ry1 - iy) / (float) ih, argb(0xFFFFFF, alpha * bright));
            }
        }
    }

    // ----- the progress bar and the text --------------------------------------------------------------------------

    /** Draws the progress bar, status, percentage and tips (call once per frame, after {@link #drawScene}). */
    public void drawBar(Canvas c, Frame f) {
        // Ease the shown progress toward the real one, time based, never backwards.
        float target = f.complete ? 1f : clamp01(f.progress);
        float dt = lastTime < 0 ? 0f : Math.max(0f, Math.min(0.1f, f.time - lastTime));
        lastTime = f.time;
        float next = shown + (target - shown) * (1f - (float) Math.exp(-dt * (f.complete ? 9f : 4.5f)));
        if (target - next < 0.0015f) next = target;
        shown = Math.max(shown, Math.min(1f, next));

        float a = clamp01(f.alpha) * clamp01(f.uiAlpha);
        if (a <= 0.002f || f.width <= 0 || f.height <= 0) return;
        int w = f.width, h = f.height;
        float u = Math.min(w * 0.75f, h);
        boolean mono = f.mono;
        float t = f.time;

        int barW = Math.round(u * 0.56f);
        int barH = Math.max(2, Math.round(u * 0.0062f));
        int bx = Math.round((w - barW) / 2f);
        int by = Math.round(h * 0.44f + u * 0.25f);
        float appear = smooth(clamp01((t - 0.35f) / 0.6f));
        float ba = a * appear;

        // Track.
        pill(c, bx, by, barW, barH, argb(0xFFFFFF, ba * 0.075f), argb(0xFFFFFF, ba * 0.075f), argb(0xFFFFFF, ba * 0.075f));

        // Fill: dark red on the left into bright red at the head, then a shimmer and a glowing head.
        int fw = Math.round(barW * shown);
        if (fw > 0) {
            int bright = mono ? 0xF0F0F4 : 0xFF3446, dark = mono ? 0x55555E : 0x7A0D18;
            int r = barH / 2;
            if (fw <= barH) {
                c.blit(DISC, bx, by, Math.max(fw, 2), barH, 0, 0, 1, 1, argb(dark, ba));
            } else {
                pill(c, bx, by, fw, barH, argb(dark, ba), argb(bright, ba), argb(bright, ba));
                // The middle blends dark -> bright: the line texture's falling half as a dark ramp over the bright fill.
                c.blit(LINE, bx + r, by, fw - 2 * r, barH, 0.5f, 0.47f, 1f, 0.53f, argb(dark, ba));
                float speed = clamp01(f.motion);
                if (speed > 0.05f && f.motion >= CALM) {
                    float period = 1.8f;
                    float p = (t * speed / period) % 1f;
                    float sw = Math.max(barH * 10f, barW * 0.16f);
                    float sx = bx - sw + p * (fw + sw * 2);
                    clippedLine(c, sx, sw, by, barH, bx + r, bx + fw - r, argb(0xFFFFFF, ba * (mono ? 0.35f : 0.5f)));
                }
            }
            float head = shown < 1f ? 1f : 0.6f;
            glow(c, bx + fw - r, by + barH / 2f, barH * 9f, barH * 5f, mono ? 0xFFFFFF : 0xFF2A3C, ba * 0.55f * head);
        }

        // Text (once the font can draw): the status left above the bar, the percentage right, tips below.
        if (!c.textReady()) {
            textSince = -1f;
            return;
        }
        if (textSince < 0) textSince = t;
        float ta = a * smooth(clamp01((t - textSince) / 0.45f)) * smooth(clamp01((t - 0.5f) / 0.6f));
        if (ta <= 0.003f) return;
        int th = c.textHeight();
        int ty = by - th - Math.max(3, Math.round(u * 0.016f));
        int status = f.complete ? T_READY : f.reloading ? T_RELOADING : T_LOADING;
        c.text(status, bx, ty, argb(0xA8A8B2, ta * 0.9f));
        int pct = T_PERCENT + Math.min(100, (int) Math.floor(shown * 100f + 0.001f));
        c.text(pct, bx + barW - c.textWidth(pct), ty, argb(0xF4F4F6, ta * 0.95f));

        // Tips: one every 4 s, fading in and out; from a little after the logo has formed.
        float tt = t - 1.2f;
        if (tt > 0) {
            float slot = 4f;
            int k = (int) (tt / slot);
            float p = tt - k * slot;
            float tipA = smooth(clamp01(p / 0.4f)) * smooth(clamp01((slot - p) / 0.4f));
            int id = T_TIPS + (tipStart + k) % TIPS.length;
            int lw = c.textWidth(T_TIP), gapPx = Math.max(4, c.textWidth(T_TIP) / 2);
            int total = lw + gapPx + c.textWidth(id);
            int x = Math.round((w - total) / 2f);
            int y = by + barH + Math.max(8, Math.round(u * 0.06f));
            float rise = (1f - tipA) * th * 0.25f;
            c.text(T_TIP, x, Math.round(y + rise), argb(mono ? 0xFFFFFF : 0xFF4051, ta * tipA));
            c.text(id, x + lw + gapPx, Math.round(y + rise), argb(0x9B9BA6, ta * tipA));
        }
    }

    /** A horizontal pill: left cap, middle and right cap each in their own colour. */
    private static void pill(Canvas c, int x, int y, int w, int h, int left, int mid, int right) {
        int r = h / 2;
        if (w < h || r < 1) {
            c.fill(x, y, x + w, y + h, mid);
            return;
        }
        int rw = h - r; // the right cap covers the odd pixel
        c.blit(DISC, x, y, r, h, 0, 0, r / (float) h, 1, left);
        c.fill(x + r, y, x + w - rw, y + h, mid);
        c.blit(DISC, x + w - rw, y, rw, h, 1 - rw / (float) h, 0, 1, 1, right);
    }

    /** The line texture's middle rows over [sx, sx + sw), clipped to [clip0, clip1). */
    private static void clippedLine(Canvas c, float sx, float sw, int y, int h, int clip0, int clip1, int col) {
        int x0 = Math.max(clip0, Math.round(sx)), x1 = Math.min(clip1, Math.round(sx + sw));
        if (x1 <= x0) return;
        float u0 = (x0 - sx) / sw, u1 = (x1 - sx) / sw;
        c.blit(LINE, x0, y, x1 - x0, h, u0, 0.47f, u1, 0.53f, col);
    }

    // ----- helpers ---------------------------------------------------------------------------------------------

    static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }

    static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }

    static float easeOutCubic(float t) {
        float k = 1f - t;
        return 1f - k * k * k;
    }

    static float easeInOutCubic(float t) {
        return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
    }

    static float easeInOutSine(float t) {
        return -((float) Math.cos(Math.PI * t) - 1f) / 2f;
    }

    static int argb(int rgb, float alpha) {
        int al = Math.round(clamp01(alpha) * 255f);
        return al << 24 | (rgb & 0xFFFFFF);
    }

    private static int scaleAlpha(int argb, float k) {
        int al = Math.round((argb >>> 24) * clamp01(k));
        return al << 24 | (argb & 0xFFFFFF);
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t);
        int g = Math.round((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return r << 16 | g << 8 | bl;
    }

    /** A fixed pseudo-random number in [0, 1) for (i, salt). */
    private static float rand(int i, int salt) {
        int x = i * 0x9E3779B1 + salt * 0x85EBCA77;
        x ^= x >>> 15;
        x *= 0x2C1B3C6D;
        x ^= x >>> 12;
        x *= 0x297A2D39;
        x ^= x >>> 15;
        return (x >>> 8) / (float) (1 << 24);
    }
}
