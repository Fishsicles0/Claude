package net.evadeclient.installer;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The launcher's colour themes. Everything is designed in the dark Evade palette; another accent or the light theme
 * is a mapping applied while painting ({@link ThemedGraphics}), so every page, dialog, toast and the embedded Clips
 * pages follow it without each colour being written twice:
 * <ul>
 *   <li>accent: Evade's reds (and red-tinted darks) turn to the chosen hue;</li>
 *   <li>light: near-black surfaces become near-white, light text becomes dark ink, white highlights become dark
 *       ones, dark scrims become light, and bright colours are deepened so they read on white. Pure white over a
 *       colour (the text on accent buttons, switch knobs) stays white.</li>
 * </ul>
 * Text size and reduced motion live here too, as the state the painting code reads.
 */
final class LauncherTheme {
    private LauncherTheme() {}

    /** Accent choices: name and colour. The first is Evade red (the default). */
    static final String[] ACCENT_NAMES = {"Evade red", "Orange", "Green", "Teal", "Blue", "Purple", "Pink"};
    static final int[] ACCENTS = {0xE11D2E, 0xF97316, 0x22C55E, 0x14B8A6, 0x3D8BFF, 0x8B5CF6, 0xEC4899};

    private static volatile boolean light;
    private static volatile int accent;
    private static volatile float accentHue = hue(ACCENTS[0]);
    private static volatile float textScale = 1f;
    private static volatile boolean reduceMotion;
    private static final ConcurrentHashMap<Integer, Color> cache = new ConcurrentHashMap<>();
    /** Bumped on every change, so cached layers know to redraw. */
    private static volatile int generation;

    static void apply(UiPrefs p) {
        light = p.light;
        accent = Math.floorMod(p.accent, ACCENTS.length);
        accentHue = hue(ACCENTS[accent]);
        textScale = p.textSize / 100f;
        reduceMotion = p.reduceMotion;
        cache.clear();
        generation++;
    }

    static boolean light() { return light; }
    static int accent() { return accent; }
    static float textScale() { return textScale; }
    static boolean reduceMotion() { return reduceMotion; }
    static int generation() { return generation; }

    /** True when colours are painted as designed (dark theme, Evade red): no mapping needed. */
    static boolean identity() { return !light && accent == 0; }

    /** The graphics to paint through: wrapped when the theme changes colours, else {@code g} itself. */
    static Graphics2D wrap(Graphics g) {
        if (g instanceof ThemedGraphics t) return t;
        return identity() ? (Graphics2D) g : new ThemedGraphics((Graphics2D) g);
    }

    /** A text size scaled for the text-size setting: small text grows fully, large headings by the same amount as 14px. */
    static float fontSize(float size) {
        float k = textScale;
        if (k == 1f) return size;
        return size <= 14f ? size * k : size + 14f * (k - 1f);
    }

    // ------------------------------------------------------------------ the mapping

    static Color map(Color c) {
        if (c == null || identity()) return c;
        int argb = c.getRGB();
        return cache.computeIfAbsent(argb, k -> new Color(mapArgb(k), true));
    }

    static int mapArgb(int argb) {
        int a = argb >>> 24, r = (argb >> 16) & 255, gr = (argb >> 8) & 255, b = argb & 255;
        float[] hsl = hsl(r, gr, b);
        float h = hsl[0], s = hsl[1], l = hsl[2];
        boolean red = s > 0.22f && (h >= 0.93f || h <= 0.035f);
        if (accent != 0 && red) {
            float d = h > 0.5f ? h - 1f : h; // signed distance from pure red
            h = accentHue + (d - (hue(ACCENTS[0]) - 1f));
            h -= (float) Math.floor(h);
        }
        if (light) {
            boolean neutral = s < 0.25f;
            if ((argb & 0xFFFFFF) == 0xFFFFFF && a >= 150) {
                // white on a colour (button text, knobs): stays white
            } else if (a < 255) {
                if (neutral || l < 0.2f) {
                    if (l > 0.5f) { l = 0.1f; s = 0.05f; a = Math.min(255, Math.round(a * 1.15f)); } // white highlight -> dark ink
                    else { l = 0.965f; s = Math.min(s, 0.1f); a = Math.round(a * 0.8f); }               // dark scrim -> light veil
                }
            } else if (neutral || l < 0.25f) {
                l = curve(l);
            } else {
                l = Math.min(l, 0.42f); // deepen bright colours so they read on white
            }
        }
        int rgb = rgb(h, s, l);
        return (a << 24) | rgb;
    }

    /** Lightness of a dark-theme neutral in the light theme. */
    private static float curve(float l) {
        if (l < 0.115f) return Math.min(1f, 0.945f + (l - 0.04f) * 0.75f);      // page, cards, fields: near white
        if (l < 0.3f) return Math.max(0.6f, 0.92f - (l - 0.115f) * 1.2f);       // borders, hovers, tracks: light grey
        return Math.max(0.04f, 0.08f + (0.96f - l) * 0.75f);                    // text: dark ink (dim stays dimmer)
    }

    private static float hue(int rgb) { return hsl((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255)[0]; }

    static float[] hsl(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf)), min = Math.min(rf, Math.min(gf, bf));
        float l = (max + min) / 2f, h = 0, s = 0;
        if (max != min) {
            float d = max - min;
            s = l > 0.5f ? d / (2f - max - min) : d / (max + min);
            if (max == rf) h = (gf - bf) / d + (gf < bf ? 6 : 0);
            else if (max == gf) h = (bf - rf) / d + 2;
            else h = (rf - gf) / d + 4;
            h /= 6f;
        }
        return new float[]{h, s, l};
    }

    static int rgb(float h, float s, float l) {
        float r, g, b;
        if (s == 0) r = g = b = l;
        else {
            float q = l < 0.5f ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
            r = hueToRgb(p, q, h + 1f / 3);
            g = hueToRgb(p, q, h);
            b = hueToRgb(p, q, h - 1f / 3);
        }
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static float hueToRgb(float p, float q, float t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1f / 6) return p + (q - p) * 6 * t;
        if (t < 1f / 2) return q;
        if (t < 2f / 3) return p + (q - p) * (2f / 3 - t) * 6;
        return p;
    }

    private static int clamp(float v) { return Math.max(0, Math.min(255, Math.round(v * 255))); }

    /** WCAG contrast ratio of two opaque colours (for the self-test). */
    static double contrast(int a, int b) {
        double la = lum(a), lb = lum(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double lum(int rgb) {
        double[] c = {((rgb >> 16) & 255) / 255.0, ((rgb >> 8) & 255) / 255.0, (rgb & 255) / 255.0};
        for (int i = 0; i < 3; i++) c[i] = c[i] <= 0.03928 ? c[i] / 12.92 : Math.pow((c[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }
}
