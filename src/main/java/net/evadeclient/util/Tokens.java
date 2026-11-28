package net.evadeclient.util;

/**
 * Evade's design tokens ("Obsidian", GUI 3.0): the colour ramps, elevation levels, radii, spacing and type sizes
 * every kit component ({@code gui/kit}) and migrated screen draws with. Sizes are design pixels (GUI pixels until a
 * screen lays out in design units).
 *
 * <p>Colours follow the Theme and Accent chosen in Client Settings: {@link #refresh()} (called by the kit every
 * frame, a cheap no-op when nothing changed) rebuilds the neutral ramp for the preset, the accent ramp for the
 * accent and the derived tokens. The user's Theme Studio colours flow in through {@link Theme}'s fields (window,
 * card and text). {@link Theme}'s old public colour fields stay valid and, for the Modern preset, are aliases of
 * these neutrals, so screens that are not migrated yet look the same as before, only a little cooler and deeper.
 *
 * <p>Render thread only (plain static fields, like {@link Theme}).
 */
public final class Tokens {
    private Tokens() {}

    // ------------------------------------------------------------------ neutrals (Modern = "Obsidian", hue ~228)

    /** The Obsidian neutral ramp for the Modern theme, darkest first (spec 3.1). */
    public static final int[] OBSIDIAN = {
            0xFF040507, 0xFF07080B, 0xFF0B0C10, 0xFF0E1015, 0xFF121419, 0xFF15171D, 0xFF1B1E25, 0xFF20232B,
            0xFF262A33, 0xFF2E323B, 0xFF444955, 0xFF636976, 0xFF8C919C, 0xFFB4B8C1, 0xFFDADDE3, 0xFFF4F5F7};
    private static final int[] MIDNIGHT = {
            0xFF03050A, 0xFF060912, 0xFF0A0E19, 0xFF0D1220, 0xFF111726, 0xFF141B2C, 0xFF1A2236, 0xFF1F2840,
            0xFF262F4A, 0xFF2E3854, 0xFF434E6A, 0xFF5F6A86, 0xFF8A93AA, 0xFFB2B9CA, 0xFFD8DCE6, 0xFFF3F5F9};
    private static final int[] OLED = {
            0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000, 0xFF060607, 0xFF0A0A0C, 0xFF121215, 0xFF18181C,
            0xFF1F1F24, 0xFF2A2A30, 0xFF424249, 0xFF62626B, 0xFF8D8D96, 0xFFB6B6BE, 0xFFDCDCE1, 0xFFF5F5F7};
    /** Vanilla's own greys for the Classic homage (light chrome, mid, dark slots). */
    private static final int[] CLASSIC = {
            0xFF000000, 0xFF101010, 0xFF181818, 0xFF1E1E1E, 0xFF262626, 0xFF2E2E2E, 0xFF373737, 0xFF404040,
            0xFF454545, 0xFF555555, 0xFF6C6C6C, 0xFF8B8B8B, 0xFFA8A8A8, 0xFFC6C6C6, 0xFFE0E0E0, 0xFFFFFFFF};

    public static int N0, N25, N50, N100, N150, N200, N250, N300, N350, N400, N500, N600, N700, N800, N900, N950;
    public static final int WHITE = 0xFFFFFFFF;

    // ------------------------------------------------------------------ accent ramp (Red by default)

    /** The Evade red ramp, 50..950 (spec 3.2). */
    public static final int[] RED_RAMP = {
            0xFFFFF1F2, 0xFFFFE0E3, 0xFFFFC2C8, 0xFFFF8F9A, 0xFFFF5566, 0xFFF42A3D, 0xFFE11D2E, 0xFFB8142A,
            0xFF8C0F22, 0xFF5C0A17, 0xFF33050D};
    /** CIELAB lightness of each accent stop (50..950); other accents keep their hue on these. */
    private static final float[] RAMP_L = {97, 93, 85, 72, 62, 55, 49, 41, 33, 22, 13};

    /** Accent stops: 50, 100, 200 ... 900, 950. {@code A400} is accent text and icons on dark, {@code A600} the core. */
    public static int A50, A100, A200, A300, A400, A500, A600, A700, A800, A900, A950;
    /** Text and icons on an accent fill. */
    public static int ON_ACCENT = WHITE;
    /** The selected-row fill, the selected edge light, the additive glow colour and the focus ring. */
    public static int ACCENT_SURFACE, ACCENT_EDGE, ACCENT_GLOW, FOCUS_RING, FOCUS_OUTER;

    // ------------------------------------------------------------------ status and semantic (every theme)

    public static final int OK = 0xFF34D399, WARN = 0xFFFBBF24, DANGER = 0xFFF43F5E, INFO = 0xFF60A5FA;
    public static final int RARITY_COMMON = 0xFF8C919C, RARITY_RARE = 0xFF4F9DFF, RARITY_EPIC = 0xFFA970FF,
            RARITY_LEGENDARY = 0xFFF5B83D;

    // ------------------------------------------------------------------ elevation (spec 3.4)

    /**
     * One elevation level: a fill (with its alpha), a 1 device px specular top rim and an all-round hairline (white
     * alphas), a tight contact shadow and a wide ambient shadow ({@code y} offset / Gaussian {@code sigma} / alpha,
     * design px). {@code shade} is the inner shade (negative: inside the top edge, for sunken controls).
     */
    public record Elevation(String name, int fill, float rim, float border, float contactY, float contactSigma,
                            float contactAlpha, float ambientY, float ambientSigma, float ambientAlpha, float shade) {
        /** The same level with another fill (hover, selected, a custom colour). */
        public Elevation withFill(int f) {
            return new Elevation(name, f, rim, border, contactY, contactSigma, contactAlpha, ambientY, ambientSigma, ambientAlpha, shade);
        }

        /** The same level without its wide ambient shadow (list rows, tooltips). */
        public Elevation noAmbient() {
            return new Elevation(name, fill, rim, border, contactY, contactSigma, contactAlpha, 0, 0, 0, shade);
        }
    }

    /** Window (glass), card, raised, popover, modal and sunken (inputs, tracks). Rebuilt by {@link #refresh()}. */
    public static Elevation E1, E2, E3, E4, E5, SUNKEN;

    // ------------------------------------------------------------------ radii and spacing (design px)

    public static final float R_XS = 3, R_SM = 5, R_MD = 8, R_LG = 12, R_XL = 18;
    /** Spacing scale s0..s12. */
    public static final int S0 = 0, S1 = 2, S2 = 4, S3 = 6, S4 = 8, S5 = 12, S6 = 16, S7 = 20, S8 = 24, S9 = 32,
            S10 = 40, S11 = 56, S12 = 72;
    /** Control heights: small, medium, large buttons, title CTAs; list rows dense / comfortable. */
    public static final int H_SM = 20, H_MD = 24, H_LG = 28, H_XL = 36, ROW_DENSE = 32, ROW_COMFY = 40;
    /** The Evade slant (degrees) used by tab indicators, edge lights and chevrons. */
    public static final float SLANT_DEG = 12f;
    /** At most this many glows on screen in menus (0 in the HUD). */
    public static final int MAX_GLOWS = 6;

    // ------------------------------------------------------------------ accessibility and user options

    /** Reduce Transparency: glass becomes solid e1/e2, no glow. High Contrast: stronger borders, no glow. */
    public static boolean reduceTransparency, highContrast;
    /** List density: rows 32 (dense) or 40 (comfortable). */
    public static boolean comfortable;
    /** UI Scale multiplier for screens laid out in design units (0.9..1.3). */
    public static float uiScale = 1f;

    // ------------------------------------------------------------------ refresh

    private static String appliedPreset;
    private static int appliedRed, appliedBg, appliedCard, appliedText;
    private static boolean appliedHc, appliedRt, applied;

    /** Rebuild every token for the current Theme preset, accent and user colours (no-op when nothing changed). */
    public static void refresh() {
        var cs = net.evadeclient.EvadeClient.modules == null ? null
                : net.evadeclient.EvadeClient.modules.get(net.evadeclient.module.client.ClientSettings.class);
        String preset = cs == null ? "Modern" : cs.theme.get();
        if (cs != null) {
            reduceTransparency = cs.reduceTransparency.get();
            highContrast = cs.highContrast.get();
            comfortable = cs.density.is("Comfortable");
            uiScale = Math.max(0.9f, Math.min(1.3f, cs.uiScale.getFloat() / 100f));
        }
        // Cheap when nothing changed (called every frame): compare the inputs, no allocation.
        if (applied && preset.equals(appliedPreset) && Theme.RED == appliedRed && Theme.BG == appliedBg && Theme.CARD == appliedCard
                && Theme.TEXT == appliedText && highContrast == appliedHc && reduceTransparency == appliedRt) return;
        applied = true;
        appliedPreset = preset; appliedRed = Theme.RED; appliedBg = Theme.BG; appliedCard = Theme.CARD; appliedText = Theme.TEXT;
        appliedHc = highContrast; appliedRt = reduceTransparency;
        int[] n = switch (preset) {
            case "Midnight" -> MIDNIGHT;
            case "OLED" -> OLED;
            case "Classic" -> CLASSIC;
            default -> OBSIDIAN;
        };
        n = n.clone();
        // Theme Studio colours: the window, card and text the user picked replace their stops.
        if ((Theme.CARD | 0xFF000000) != n[5] && !"Classic".equals(preset)) {
            int card = Theme.CARD | 0xFF000000;
            n[5] = card;
            n[6] = Theme.lerpColor(card, WHITE, 0.04f);
            n[7] = Theme.lerpColor(card, WHITE, 0.07f);
            n[8] = Theme.lerpColor(card, WHITE, 0.10f);
        }
        if ((Theme.TEXT | 0xFF000000) != n[15]) n[15] = Theme.TEXT | 0xFF000000;
        N0 = n[0]; N25 = n[1]; N50 = n[2]; N100 = n[3]; N150 = n[4]; N200 = n[5]; N250 = n[6]; N300 = n[7];
        N350 = n[8]; N400 = n[9]; N500 = n[10]; N600 = n[11]; N700 = n[12]; N800 = n[13]; N900 = n[14]; N950 = n[15];

        int[] a = (Theme.RED | 0xFF000000) == RED_RAMP[6] ? RED_RAMP : ramp(Theme.RED | 0xFF000000);
        A50 = a[0]; A100 = a[1]; A200 = a[2]; A300 = a[3]; A400 = a[4]; A500 = a[5]; A600 = a[6]; A700 = a[7];
        A800 = a[8]; A900 = a[9]; A950 = a[10];
        ON_ACCENT = Theme.relLum(A600) > 0.30f ? 0xFF141418 : WHITE;
        ACCENT_SURFACE = Theme.lerpColor(N200, A900, 0.35f);
        ACCENT_EDGE = Theme.alpha(A500, 0.85f);
        ACCENT_GLOW = A600;
        FOCUS_RING = A400;
        FOCUS_OUTER = Theme.alpha(A600, 0.35f);

        boolean oled = "OLED".equals(preset);
        float hc = highContrast ? 2.2f : 1f;
        float glass = reduceTransparency ? 1f : 0.90f;
        E1 = new Elevation("e1", Theme.alpha(oled ? 0xFF000000 : N100, glass), 0.10f, Math.min(0.22f, 0.06f * hc), 2, 3, 0.45f, 24, 32, 0.55f, 0);
        E2 = new Elevation("e2", N200, 0.06f, Math.min(0.22f, 0.05f * hc), 1, 1.5f, 0.35f, 6, 10, 0.30f, 0);
        E3 = new Elevation("e3", N250, 0.09f, Math.min(0.22f, 0.08f * hc), 2, 2, 0.40f, 10, 16, 0.35f, 0);
        E4 = new Elevation("e4", Theme.alpha(N300, reduceTransparency ? 1f : 0.96f), 0.12f, Math.min(0.22f, 0.09f * hc), 3, 4, 0.45f, 18, 28, 0.50f, 0);
        E5 = new Elevation("e5", Theme.alpha(N350, reduceTransparency ? 1f : 0.97f), 0.14f, Math.min(0.22f, 0.10f * hc), 4, 6, 0.50f, 32, 48, 0.60f, 0);
        SUNKEN = new Elevation("sunken", N150, 0, Math.min(0.22f, 0.04f * hc), 0, 0, 0, 0, 0, 0, -0.5f);
    }

    /** Whether glows are allowed now (not High Contrast or Reduce Transparency). */
    public static boolean glows() { return !highContrast && !reduceTransparency; }

    // ------------------------------------------------------------------ accent ramp generation (CIELAB / LCh)

    /** An 11-stop ramp for {@code accent}: its hue on the fixed lightness stops, chroma clamped to the sRGB gamut.
     *  The 600 stop is the accent itself, so the brand colour is exact. */
    public static int[] ramp(int accent) {
        float[] lab = toLab(accent);
        float c0 = (float) Math.hypot(lab[1], lab[2]);
        float h = (float) Math.atan2(lab[2], lab[1]);
        int[] out = new int[RAMP_L.length];
        for (int i = 0; i < RAMP_L.length; i++) {
            if (i == 6) { out[i] = accent | 0xFF000000; continue; }
            float l = RAMP_L[i];
            // Chroma peaks around the middle of the ramp and tapers toward white and black.
            float c = c0 * (float) Math.min(1.0, Math.sin(Math.PI * Math.min(1.0, l / 100.0)) * 1.25);
            int rgb = 0;
            for (int tries = 0; tries < 40; tries++) {
                int[] v = fromLab(l, (float) (c * Math.cos(h)), (float) (c * Math.sin(h)));
                if (v != null) { rgb = v[0] << 16 | v[1] << 8 | v[2]; break; }
                c *= 0.9f;
                if (tries == 39) { int[] g = fromLabClamped(l, 0, 0); rgb = g[0] << 16 | g[1] << 8 | g[2]; }
            }
            out[i] = 0xFF000000 | rgb;
        }
        return out;
    }

    private static float[] toLab(int argb) {
        double r = lin((argb >> 16 & 255) / 255.0), g = lin((argb >> 8 & 255) / 255.0), b = lin((argb & 255) / 255.0);
        double x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047;
        double y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        double z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883;
        double fx = f(x), fy = f(y), fz = f(z);
        return new float[]{(float) (116 * fy - 16), (float) (500 * (fx - fy)), (float) (200 * (fy - fz))};
    }

    /** sRGB 0..255 for a Lab colour, or null when it is outside the gamut. */
    private static int[] fromLab(float l, float a, float b) {
        double[] rgb = labToLinear(l, a, b);
        for (double v : rgb) if (v < -1e-4 || v > 1.0001) return null;
        return new int[]{enc(rgb[0]), enc(rgb[1]), enc(rgb[2])};
    }

    private static int[] fromLabClamped(float l, float a, float b) {
        double[] rgb = labToLinear(l, a, b);
        return new int[]{enc(rgb[0]), enc(rgb[1]), enc(rgb[2])};
    }

    private static double[] labToLinear(float l, float a, float b) {
        double fy = (l + 16) / 116.0, fx = fy + a / 500.0, fz = fy - b / 200.0;
        double x = 0.95047 * fi(fx), y = fi(fy), z = 1.08883 * fi(fz);
        return new double[]{3.2406 * x - 1.5372 * y - 0.4986 * z, -0.9689 * x + 1.8758 * y + 0.0415 * z,
                0.0557 * x - 0.2040 * y + 1.0570 * z};
    }

    private static double lin(double c) { return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); }

    private static int enc(double c) {
        c = Math.max(0, Math.min(1, c));
        double s = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
        return (int) Math.round(s * 255);
    }

    private static double f(double t) { return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116; }

    private static double fi(double t) { return t * t * t > 216.0 / 24389 ? t * t * t : (116 * t - 16) / (24389.0 / 27); }

    static {
        refresh(); // usable before the kit's first frame
    }
}
