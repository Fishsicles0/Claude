package net.evadeclient.installer;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.PaintContext;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.Transparency;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The launcher's half of the "Obsidian" design system (the in-game client's {@code util/Tokens} and kit): the same
 * colour ramps, radii, elevation levels and light model, painted with Java2D.
 * <ul>
 *   <li><b>Light model:</b> one key light from the top. Raised surfaces get a 1 device-pixel specular top rim that
 *       fades out towards the sides, a hairline border, a tight contact shadow and a wide ambient shadow.</li>
 *   <li><b>Soft shadows:</b> real Gaussian falloffs (three box passes in floating point), rendered at the screen's
 *       own pixel density and cached, dithered when they're written so the falloff never bands.</li>
 *   <li><b>Dithered gradients:</b> {@link Smooth} computes every gradient pixel in floating point and adds
 *       interleaved-gradient noise of half a step before rounding, the "10-bit feel" in an 8-bit window.</li>
 *   <li><b>Crisp at any scale:</b> borders and rims are 1 <i>device</i> pixel at 100, 125, 150 and 200%, and
 *       surfaces snap to the device pixel grid.</li>
 * </ul>
 * The values mirror {@code net.evadeclient.util.Tokens} (the launcher is a separate module, so they're copied, not
 * imported). Launcher pixels are about 1.5 of the in-game design pixels, so radii and shadow sizes are scaled by that.
 */
final class Obsidian {
    private Obsidian() {}

    // ------------------------------------------------------------------ neutrals (Tokens.OBSIDIAN, hue ~228)

    static final Color N0 = new Color(0x040507), N25 = new Color(0x07080B), N50 = new Color(0x0B0C10), N100 = new Color(0x0E1015),
            N150 = new Color(0x121419), N200 = new Color(0x15171D), N250 = new Color(0x1B1E25), N300 = new Color(0x20232B),
            N350 = new Color(0x262A33), N400 = new Color(0x2E323B), N500 = new Color(0x444955), N600 = new Color(0x636976),
            N700 = new Color(0x8C919C), N800 = new Color(0xB4B8C1), N900 = new Color(0xDADDE3), N950 = new Color(0xF4F5F7);

    // ------------------------------------------------------------------ the Evade red ramp (Tokens.RED_RAMP)

    static final Color RED50 = new Color(0xFFF1F2), RED100 = new Color(0xFFE0E3), RED200 = new Color(0xFFC2C8),
            RED300 = new Color(0xFF8F9A), RED400 = new Color(0xFF5566), RED500 = new Color(0xF42A3D), RED600 = new Color(0xE11D2E),
            RED700 = new Color(0xB8142A), RED800 = new Color(0x8C0F22), RED900 = new Color(0x5C0A17), RED950 = new Color(0x33050D);

    // ------------------------------------------------------------------ status (Tokens.OK/WARN/DANGER/INFO)

    static final Color OK = new Color(0x34D399), WARN = new Color(0xFBBF24), DANGER = new Color(0xF43F5E), INFO = new Color(0x60A5FA);

    // ------------------------------------------------------------------ radii and spacing (launcher px)

    /** Chips and badges; buttons, inputs and rows; cards and popovers; the hero, windows and dialogs. */
    static final int R_XS = 5, R_SM = 8, R_MD = 12, R_LG = 18, R_XL = 24;
    /** The Evade slant (degrees): edge lights, progress heads, chevrons. */
    static final double SLANT = Math.tan(Math.toRadians(12));

    // ------------------------------------------------------------------ elevation (Tokens.E1..E5, launcher px)

    /**
     * One elevation level: the rim (specular top line) and border (all-round hairline) as white alphas, and a contact
     * and an ambient shadow ({@code y} offset / Gaussian sigma / alpha).
     */
    record Level(float rim, float border, float contactY, float contactSigma, float contactAlpha,
                 float ambientY, float ambientSigma, float ambientAlpha) {
        Level noAmbient() { return new Level(rim, border, contactY, contactSigma, contactAlpha, 0, 0, 0); }
        Level rim(float r) { return new Level(r, border, contactY, contactSigma, contactAlpha, ambientY, ambientSigma, ambientAlpha); }
    }

    /** Window/hero, card, raised (hover), popover, modal/tooltip. */
    static final Level E1 = new Level(.10f, .06f, 3, 4.5f, .45f, 30, 40, .50f),
            E2 = new Level(.06f, .05f, 1.5f, 2.2f, .35f, 8, 14, .28f),
            E3 = new Level(.09f, .08f, 3, 3, .40f, 14, 22, .34f),
            E4 = new Level(.12f, .09f, 4, 6, .45f, 24, 36, .50f),
            E5 = new Level(.14f, .10f, 6, 9, .50f, 40, 60, .58f);

    // ------------------------------------------------------------------ device pixels

    /** Device pixels per launcher pixel for {@code g} (window scale times screen scale, e.g. 1.25 at 125%). */
    static double scale(Graphics2D g) {
        AffineTransform t = g.getTransform();
        double s = Math.sqrt(Math.abs(t.getDeterminant()));
        return s > 0 ? s : 1;
    }

    /** One device pixel, in launcher pixels. */
    static double px(Graphics2D g) { return 1 / scale(g); }

    /** {@code v} on the x axis snapped to the device pixel grid. */
    static double snapX(Graphics2D g, double v) {
        AffineTransform t = g.getTransform();
        double sx = t.getScaleX();
        if (sx == 0 || t.getShearX() != 0) return v;
        return (Math.round(v * sx + t.getTranslateX()) - t.getTranslateX()) / sx;
    }

    static double snapY(Graphics2D g, double v) {
        AffineTransform t = g.getTransform();
        double sy = t.getScaleY();
        if (sy == 0 || t.getShearY() != 0) return v;
        return (Math.round(v * sy + t.getTranslateY()) - t.getTranslateY()) / sy;
    }

    /** A rectangle with its edges on device pixels. */
    static Rectangle2D.Double snap(Graphics2D g, double x, double y, double w, double h) {
        double x0 = snapX(g, x), y0 = snapY(g, y), x1 = snapX(g, x + w), y1 = snapY(g, y + h);
        return new Rectangle2D.Double(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    static RoundRectangle2D.Double rr(double x, double y, double w, double h, double r) {
        double d = Math.min(2 * r, Math.min(w, h));
        return new RoundRectangle2D.Double(x, y, w, h, d, d);
    }

    static Color alpha(Color c, float a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, Math.round(255 * a))));
    }

    static Color white(float a) { return alpha(Color.WHITE, a); }

    static Color mix(Color a, Color b, float t) { return LauncherView.mix(a, b, t); }

    /** True when the light theme is being painted (white rims would turn into dark lines, shadows are softer). */
    static boolean lightPainting(Graphics2D g) { return ThemedGraphics.lightPainting(g); }

    // ------------------------------------------------------------------ dithered gradients

    /**
     * A linear or (elliptical) radial gradient computed per pixel in floating point and dithered with interleaved
     * gradient noise (±0.5 of an 8-bit step) before rounding, so long, dark or faint gradients never band.
     * The theme's colour mapping applies to it like any other paint ({@link #map}).
     */
    static final class Smooth implements Paint {
        final boolean radial;
        /** Linear: start and end. Radial: centre and the two radii. */
        final float x1, y1, x2, y2;
        final float[] fractions;
        final Color[] colors;

        private Smooth(boolean radial, float x1, float y1, float x2, float y2, float[] fractions, Color[] colors) {
            this.radial = radial;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.fractions = fractions;
            this.colors = colors;
        }

        Smooth map(UnaryOperator<Color> f) {
            Color[] out = new Color[colors.length];
            for (int i = 0; i < colors.length; i++) out[i] = f.apply(colors[i]);
            return new Smooth(radial, x1, y1, x2, y2, fractions, out);
        }

        @Override public int getTransparency() {
            for (Color c : colors) if (c.getAlpha() < 255) return Transparency.TRANSLUCENT;
            return Transparency.OPAQUE;
        }

        @Override public PaintContext createContext(ColorModel cm, Rectangle deviceBounds, Rectangle2D userBounds,
                                                    AffineTransform xform, RenderingHints hints) {
            AffineTransform inv;
            try { inv = xform.createInverse(); } catch (NoninvertibleTransformException e) { inv = new AffineTransform(); }
            return new Ctx(this, inv);
        }

        /** 1024 colour stops in floating point (straight alpha, 0..255). */
        float[] lut() {
            int n = 1024;
            float[] lut = new float[n * 4];
            for (int i = 0; i < n; i++) {
                float t = i / (float) (n - 1);
                int k = 0;
                while (k < fractions.length - 2 && t > fractions[k + 1]) k++;
                float f0 = fractions[k], f1 = fractions[Math.min(k + 1, fractions.length - 1)];
                float u = f1 > f0 ? Math.max(0, Math.min(1, (t - f0) / (f1 - f0))) : (t >= f1 ? 1 : 0);
                if (t <= fractions[0]) { k = 0; u = 0; }
                Color a = colors[k], b = colors[Math.min(k + 1, colors.length - 1)];
                lut[i * 4] = a.getAlpha() + (b.getAlpha() - a.getAlpha()) * u;
                lut[i * 4 + 1] = a.getRed() + (b.getRed() - a.getRed()) * u;
                lut[i * 4 + 2] = a.getGreen() + (b.getGreen() - a.getGreen()) * u;
                lut[i * 4 + 3] = a.getBlue() + (b.getBlue() - a.getBlue()) * u;
            }
            return lut;
        }
    }

    private static final class Ctx implements PaintContext {
        private final Smooth p;
        private final AffineTransform inv;
        private final float[] lut;

        Ctx(Smooth p, AffineTransform inv) {
            this.p = p;
            this.inv = inv;
            this.lut = p.lut();
        }

        @Override public void dispose() {}
        @Override public ColorModel getColorModel() { return ColorModel.getRGBdefault(); }

        @Override public Raster getRaster(int x, int y, int w, int h) {
            WritableRaster r = getColorModel().createCompatibleWritableRaster(w, h);
            int[] data = ((DataBufferInt) r.getDataBuffer()).getData();
            double m00 = inv.getScaleX(), m01 = inv.getShearX(), m02 = inv.getTranslateX();
            double m10 = inv.getShearY(), m11 = inv.getScaleY(), m12 = inv.getTranslateY();
            float dx = p.x2 - p.x1, dy = p.y2 - p.y1;
            float len2 = dx * dx + dy * dy;
            float irx = p.radial ? 1f / Math.max(1e-3f, p.x2) : 0, iry = p.radial ? 1f / Math.max(1e-3f, p.y2) : 0;
            int last = lut.length / 4 - 1;
            int o = 0;
            for (int j = 0; j < h; j++) {
                double py = y + j + 0.5;
                for (int i = 0; i < w; i++) {
                    double px = x + i + 0.5;
                    float ux = (float) (m00 * px + m01 * py + m02), uy = (float) (m10 * px + m11 * py + m12);
                    float t;
                    if (p.radial) {
                        float ax = (ux - p.x1) * irx, ay = (uy - p.y1) * iry;
                        t = (float) Math.sqrt(ax * ax + ay * ay);
                    } else t = len2 > 0 ? ((ux - p.x1) * dx + (uy - p.y1) * dy) / len2 : 0;
                    if (t < 0) t = 0;
                    else if (t > 1) t = 1;
                    int k = Math.round(t * last) * 4;
                    // interleaved gradient noise in [-0.5, 0.5): one value for every channel (no colour speckle)
                    float n = ign(x + i, y + j) - 0.5f;
                    int a = clamp8(lut[k] + n), rr = clamp8(lut[k + 1] + n), gg = clamp8(lut[k + 2] + n), bb = clamp8(lut[k + 3] + n);
                    data[o++] = (a << 24) | (rr << 16) | (gg << 8) | bb;
                }
            }
            return r;
        }
    }

    /** Interleaved gradient noise (Jimenez 2014), in [0, 1). */
    static float ign(int x, int y) {
        double v = 0.06711056 * x + 0.00583715 * y;
        v -= Math.floor(v);
        v *= 52.9829189;
        return (float) (v - Math.floor(v));
    }

    private static int clamp8(float v) {
        int i = (int) (v + 0.5f);
        return i < 0 ? 0 : Math.min(i, 255);
    }

    /** A dithered linear gradient from ({@code x1},{@code y1}) to ({@code x2},{@code y2}). */
    static Smooth linear(double x1, double y1, double x2, double y2, float[] fractions, Color... colors) {
        return new Smooth(false, (float) x1, (float) y1, (float) x2, (float) y2, fractions, colors);
    }

    static Smooth linear(double x1, double y1, double x2, double y2, Color a, Color b) {
        return linear(x1, y1, x2, y2, new float[]{0f, 1f}, a, b);
    }

    /** A dithered radial gradient centred on ({@code cx},{@code cy}) with radii {@code rx} and {@code ry} (an ellipse). */
    static Smooth radial(double cx, double cy, double rx, double ry, float[] fractions, Color... colors) {
        return new Smooth(true, (float) cx, (float) cy, (float) rx, (float) ry, fractions, colors);
    }

    /** A Gaussian-looking radial falloff from {@code c} (at alpha {@code a}) to nothing: a glow without a visible edge. */
    static Smooth glowPaint(double cx, double cy, double rx, double ry, Color c, float a) {
        float[] f = {0f, .25f, .5f, .75f, 1f};
        Color[] cs = new Color[f.length];
        for (int i = 0; i < f.length; i++) cs[i] = alpha(c, a * (float) Math.exp(-4.5 * f[i] * f[i]) * (1 - f[i]));
        return radial(cx, cy, rx, ry, f, cs);
    }

    /** Fills an elliptical glow (dithered, no box edges). */
    static void glow(Graphics2D g, double cx, double cy, double rx, double ry, Color c, float a) {
        if (a <= 0.003f || rx <= 0 || ry <= 0) return;
        AffineTransform t = g.getTransform();
        double sx = t.getScaleX(), sy = t.getScaleY();
        if (t.getShearX() != 0 || t.getShearY() != 0 || sx <= 0 || sy <= 0) {
            Paint was = g.getPaint();
            g.setPaint(glowPaint(cx, cy, rx, ry, c, a));
            g.fill(new Rectangle2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry));
            g.setPaint(was);
            return;
        }
        // a cached, dithered sprite at device resolution (alpha in 1/200 steps)
        Color m = ThemedGraphics.mapped(g, c);
        int iw = Math.max(1, (int) Math.ceil(2 * rx * sx)), ih = Math.max(1, (int) Math.ceil(2 * ry * sy));
        if ((long) iw * ih > 4_000_000L) return;
        int qa = Math.max(1, Math.round(a * 200));
        String key = iw + "x" + ih + "|" + Integer.toHexString(m.getRGB()) + "|" + qa;
        BufferedImage img;
        synchronized (GLOWS) { img = GLOWS.get(key); }
        if (img == null) {
            img = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
            Graphics2D q = img.createGraphics();
            q.setPaint(glowPaint(iw / 2.0, ih / 2.0, iw / 2.0, ih / 2.0, m, qa / 200f * m.getAlpha() / 255f));
            q.fillRect(0, 0, iw, ih);
            q.dispose();
            synchronized (GLOWS) { GLOWS.put(key, img); }
        }
        Graphics2D q = (Graphics2D) g.create();
        q.setTransform(new AffineTransform());
        double dx = (cx - rx) * sx + t.getTranslateX(), dy = (cy - ry) * sy + t.getTranslateY();
        q.drawImage(img, (int) Math.round(dx), (int) Math.round(dy), null);
        q.dispose();
    }

    private static final Map<String, BufferedImage> GLOWS = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) { return size() > 160; }
    };

    // ------------------------------------------------------------------ shadows

    private static final Map<String, BufferedImage> SHADOWS = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) { return size() > 96; }
    };

    /**
     * A soft Gaussian shadow of a rounded rectangle, offset down by {@code dy}. {@code ring > 0} blurs only a ring of that
     * width (focus glows). Rendered at the device's pixel density and cached; the falloff is dithered.
     */
    static void shadow(Graphics2D g, double x, double y, double w, double h, double r, double dy, double sigma, float alpha, Color color, double ring) {
        if (alpha <= 0.003f || w <= 0 || h <= 0) return;
        if (lightPainting(g)) alpha *= 0.45f;
        AffineTransform t = g.getTransform();
        if (t.getShearX() != 0 || t.getShearY() != 0) return;
        double s = scale(g);
        Color c = ThemedGraphics.mapped(g, color);
        float a = alpha * c.getAlpha() / 255f;
        double pad = Math.ceil(sigma * 3 + ring + 1);
        // device-pixel box of the shape (snapped), and of the whole shadow
        int bx0 = (int) Math.round(x * t.getScaleX() + t.getTranslateX()), by0 = (int) Math.round((y + dy) * t.getScaleY() + t.getTranslateY());
        int bx1 = (int) Math.round((x + w) * t.getScaleX() + t.getTranslateX()), by1 = (int) Math.round((y + dy + h) * t.getScaleY() + t.getTranslateY());
        int dpad = (int) Math.ceil(pad * s);
        // Nine-slice: render the shadow of a small box once (corners exact), stretch its straight edges, and skip the
        // centre, which the (opaque) surface covers anyway: cheap for big cards, and a knockout under translucent ones.
        int core = (int) Math.ceil((r + 3 * sigma + ring + 2) * s);
        boolean sliced = ring == 0 && bx1 - bx0 > 2 * core + 2 && by1 - by0 > 2 * core + 2;
        int tw = sliced ? 2 * core + 1 : bx1 - bx0, th = sliced ? 2 * core + 1 : by1 - by0;
        int iw = tw + 2 * dpad, ih = th + 2 * dpad;
        if ((long) iw * ih > 6_000_000L) return;
        String key = iw + "x" + ih + "|" + r + "|" + sigma + "|" + ring + "|" + Math.round(a * 1000) + "|" + Integer.toHexString(c.getRGB() & 0xFFFFFF) + "|" + s;
        BufferedImage img;
        synchronized (SHADOWS) { img = SHADOWS.get(key); }
        if (img == null) {
            img = renderShadow(iw, ih, s, dpad / s, tw / s, th / s, r, sigma, a, c, ring);
            synchronized (SHADOWS) { SHADOWS.put(key, img); }
        }
        Graphics2D q = (Graphics2D) g.create();
        q.setTransform(new AffineTransform());
        int X0 = bx0 - dpad, Y0 = by0 - dpad, X1 = bx1 + dpad, Y1 = by1 + dpad;
        if (!sliced) q.drawImage(img, X0, Y0, null);
        else {
            int cw = dpad + core, chh = dpad + core; // corner piece size in the template
            int mx = iw - cw, my = ih - chh;         // where the far corners start in the template
            // corners
            q.drawImage(img, X0, Y0, X0 + cw, Y0 + chh, 0, 0, cw, chh, null);
            q.drawImage(img, X1 - (iw - mx), Y0, X1, Y0 + chh, mx, 0, iw, chh, null);
            q.drawImage(img, X0, Y1 - (ih - my), X0 + cw, Y1, 0, my, cw, ih, null);
            q.drawImage(img, X1 - (iw - mx), Y1 - (ih - my), X1, Y1, mx, my, iw, ih, null);
            // edges (one template column / row stretched along the edge)
            q.drawImage(img, X0 + cw, Y0, X1 - (iw - mx), Y0 + chh, cw, 0, cw + 1, chh, null);
            q.drawImage(img, X0 + cw, Y1 - (ih - my), X1 - (iw - mx), Y1, cw, my, cw + 1, ih, null);
            q.drawImage(img, X0, Y0 + chh, X0 + cw, Y1 - (ih - my), 0, chh, cw, chh + 1, null);
            q.drawImage(img, X1 - (iw - mx), Y0 + chh, X1, Y1 - (ih - my), mx, chh, iw, chh + 1, null);
        }
        q.dispose();
    }

    static void shadow(Graphics2D g, double x, double y, double w, double h, double r, double dy, double sigma, float alpha) {
        shadow(g, x, y, w, h, r, dy, sigma, alpha, Color.BLACK, 0);
    }

    private static BufferedImage renderShadow(int iw, int ih, double s, double pad, double w, double h, double r, double sigma,
                                              float alpha, Color c, double ring) {
        BufferedImage mask = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
        Graphics2D m = mask.createGraphics();
        m.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        m.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        m.scale(s, s);
        m.setColor(Color.WHITE);
        Shape shape = rr(pad, pad, w, h, r);
        if (ring > 0) {
            m.setStroke(new BasicStroke((float) ring));
            m.draw(shape);
        } else m.fill(shape);
        m.dispose();
        int n = iw * ih;
        int[] px = ((DataBufferInt) mask.getRaster().getDataBuffer()).getData();
        float[] a = new float[n], tmp = new float[n];
        for (int i = 0; i < n; i++) a[i] = (px[i] >>> 24) / 255f;
        double sd = sigma * s;
        if (sd > 0.3) {
            int[] boxes = boxes(sd);
            for (int b : boxes) {
                boxH(a, tmp, iw, ih, b);
                boxV(tmp, a, iw, ih, b);
            }
        }
        BufferedImage out = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_ARGB);
        int[] o = ((DataBufferInt) out.getRaster().getDataBuffer()).getData();
        int rgb = c.getRGB() & 0xFFFFFF;
        float k = alpha * 255f;
        for (int yy = 0, i = 0; yy < ih; yy++) {
            for (int xx = 0; xx < iw; xx++, i++) {
                int v = clamp8(a[i] * k + ign(xx, yy) - 0.5f);
                o[i] = (v << 24) | rgb;
            }
        }
        return out;
    }

    /** Three box radii whose passes approximate a Gaussian of {@code sigma} (Kovesi / Ivan Kutskir). */
    private static int[] boxes(double sigma) {
        int n = 3;
        double wIdeal = Math.sqrt(12 * sigma * sigma / n + 1);
        int wl = (int) Math.floor(wIdeal);
        if (wl % 2 == 0) wl--;
        int wu = wl + 2;
        double mIdeal = (12 * sigma * sigma - n * wl * wl - 4 * n * wl - 3 * n) / (-4.0 * wl - 4);
        long m = Math.round(mIdeal);
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = ((i < m ? wl : wu) - 1) / 2;
        return r;
    }

    private static void boxH(float[] src, float[] dst, int w, int h, int r) {
        if (r <= 0) { System.arraycopy(src, 0, dst, 0, src.length); return; }
        float inv = 1f / (2 * r + 1);
        for (int y = 0; y < h; y++) {
            int row = y * w;
            float acc = 0;
            for (int x = -r; x <= r; x++) acc += x >= 0 && x < w ? src[row + x] : 0;
            for (int x = 0; x < w; x++) {
                dst[row + x] = acc * inv;
                int add = x + r + 1, sub = x - r;
                if (add < w) acc += src[row + add];
                if (sub >= 0) acc -= src[row + sub];
            }
        }
    }

    private static void boxV(float[] src, float[] dst, int w, int h, int r) {
        if (r <= 0) { System.arraycopy(src, 0, dst, 0, src.length); return; }
        float inv = 1f / (2 * r + 1);
        for (int x = 0; x < w; x++) {
            float acc = 0;
            for (int y = -r; y <= r; y++) acc += y >= 0 && y < h ? src[y * w + x] : 0;
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = acc * inv;
                int add = y + r + 1, sub = y - r;
                if (add < h) acc += src[add * w + x];
                if (sub >= 0) acc -= src[sub * w + x];
            }
        }
    }

    /** The contact and ambient shadows of an elevation level. {@code lift} (0..1, hover) grows the ambient shadow a little. */
    static void elevate(Graphics2D g, double x, double y, double w, double h, double r, Level lv, float lift) {
        if (lv.ambientAlpha() > 0) {
            float k = 1 + 0.25f * lift;
            shadow(g, x, y, w, h, r, lv.ambientY() * k, lv.ambientSigma() * k, lv.ambientAlpha() * (0.9f + 0.1f * lift));
        }
        if (lv.contactAlpha() > 0) shadow(g, x, y, w, h, r, lv.contactY(), lv.contactSigma(), lv.contactAlpha());
    }

    // ------------------------------------------------------------------ surfaces

    /** The 1 device-pixel specular top rim of {@code shape} (bounds x..x+w), white at {@code a} fading out over the outer 20%. */
    static void rim(Graphics2D g, Shape shape, float a) {
        if (a <= 0.003f || lightPainting(g)) return;
        Rectangle2D b = shape.getBounds2D();
        double p = px(g);
        Area band = new Area(shape);
        band.subtract(new Area(AffineTransform.getTranslateInstance(0, p).createTransformedShape(shape)));
        Paint was = g.getPaint();
        g.setPaint(linear(b.getX(), 0, b.getMaxX(), 0, new float[]{0f, .2f, .8f, 1f}, white(0), white(a), white(a), white(0)));
        g.fill(band);
        g.setPaint(was);
    }

    /** A 1 device-pixel border just inside a rounded rectangle. */
    static void border(Graphics2D g, double x, double y, double w, double h, double r, Color c) {
        if (c.getAlpha() == 0) return;
        double p = px(g);
        Stroke was = g.getStroke();
        g.setStroke(new BasicStroke((float) p));
        g.setColor(c);
        g.draw(rr(x + p / 2, y + p / 2, w - p, h - p, Math.max(0, r - p / 2)));
        g.setStroke(was);
    }

    /**
     * A raised surface: shadows of {@code lv}, a fill (flat, or a dithered top-to-bottom gradient when {@code bottom} is
     * given), the specular rim and the hairline border. Snapped to device pixels. Returns the shape.
     */
    static Shape surface(Graphics2D g, double x, double y, double w, double h, double r, Level lv, Color top, Color bottom, float lift) {
        Rectangle2D.Double s = snap(g, x, y, w, h);
        if (lv != null) elevate(g, s.x, s.y, s.width, s.height, r, lv, lift);
        Shape shape = rr(s.x, s.y, s.width, s.height, r);
        double dev = s.width * s.height * scale(g) * scale(g);
        if (bottom == null || bottom.equals(top)) g.setColor(top);
        else if (dev > 160_000) g.setColor(mix(top, bottom, .5f)); // big panels: flat (a 1-step gradient isn't worth a pass)
        else g.setPaint(linear(0, s.y, 0, s.y + s.height, top, bottom));
        g.fill(shape);
        if (lv != null) {
            rim(g, shape, lv.rim() + 0.05f * lift);
            border(g, s.x, s.y, s.width, s.height, r, white(lv.border() + 0.03f * lift));
        }
        return shape;
    }

    /** A sunken well (inputs, tracks): darker fill, a soft inner shade along the top edge, a faint hairline. */
    static Shape sunken(Graphics2D g, double x, double y, double w, double h, double r, Color fill, float focus) {
        Rectangle2D.Double s = snap(g, x, y, w, h);
        Shape shape = rr(s.x, s.y, s.width, s.height, r);
        g.setColor(fill);
        g.fill(shape);
        Shape clip = g.getClip();
        g.clip(shape);
        Paint was = g.getPaint();
        if (!lightPainting(g)) {
            g.setPaint(linear(0, s.y, 0, s.y + Math.min(6, s.height / 2), alpha(N0, .45f), alpha(N0, 0)));
            g.fill(new Rectangle2D.Double(s.x, s.y, s.width, Math.min(6, s.height / 2)));
        }
        g.setPaint(was);
        g.setClip(clip);
        border(g, s.x, s.y, s.width, s.height, r, focus > 0 ? mix(white(.06f), RED400, focus) : white(.06f));
        return shape;
    }

    /** The selected-item look: light entering from the left over a red-tinted surface, and a slanted edge light. */
    static void selected(Graphics2D g, double x, double y, double w, double h, double r, float a) {
        selected(g, x, y, w, h, r, a, x + 6);
    }

    /** As above, with the edge light at {@code edgeX} (outside the surface for icon-only items). */
    static void selected(Graphics2D g, double x, double y, double w, double h, double r, float a, double edgeX) {
        if (a <= 0.003f) return;
        Shape shape = rr(x, y, w, h, r);
        Paint was = g.getPaint();
        g.setPaint(linear(x, 0, x + w, 0, new float[]{0f, 1f}, alpha(mix(N200, RED900, .55f), .92f * a), alpha(N200, 0.5f * a)));
        g.fill(shape);
        g.setPaint(was);
        rim(g, shape, .07f * a);
        border(g, x, y, w, h, r, alpha(RED500, .18f * a));
        slantEdge(g, edgeX, y + h * 0.24, h * 0.52, alpha(RED500, a));
    }

    /** The Evade slant: a thin parallelogram leaning 12° (edge lights, indicators). */
    static void slantEdge(Graphics2D g, double x, double y, double h, Color c) {
        double lean = h * SLANT, w = 3;
        Path2D p = new Path2D.Double();
        p.moveTo(x + lean, y);
        p.lineTo(x + lean + w, y);
        p.lineTo(x + w, y + h);
        p.lineTo(x, y + h);
        p.closePath();
        g.setColor(c);
        g.fill(p);
        // emissive halo
        glow(g, x + lean / 2 + 1.5, y + h / 2, 8, h * 0.7, c, 0.28f * c.getAlpha() / 255f);
    }

    // ------------------------------------------------------------------ buttons

    /**
     * The primary call to action: a red-500 to red-700 dithered gradient with a soft top sheen, a specular rim and an
     * emissive glow that rises on hover. Pressing dips it by one pixel and flattens it to red-700.
     * Returns the y offset of the press (labels follow it).
     */
    static double cta(Graphics2D g, double x, double y, double w, double h, double r, float hover, float press, boolean enabled) {
        if (!enabled) {
            surface(g, x, y, w, h, r, E2.noAmbient(), N300, null, 0);
            return 0;
        }
        // light the page under it (emissive red), then a tight contact shadow
        shadow(g, x + 6, y + 4, w - 12, h - 4, r, 6 + 2 * hover, 12 + 6 * hover, (.22f + .20f * hover) * (1 - .5f * press), RED600, 0);
        shadow(g, x, y, w, h, r, 2 - press, 3, .45f);
        double dy = press;
        Rectangle2D.Double s = snap(g, x, y + dy, w, h);
        Shape shape = rr(s.x, s.y, s.width, s.height, r);
        Color top = mix(mix(RED500, new Color(0xFF3D50), hover), RED700, press), bottom = mix(mix(RED700, RED600, hover), RED700, press);
        g.setPaint(linear(0, s.y, 0, s.y + s.height, top, bottom));
        g.fill(shape);
        // the sheen: a soft white fade over the top 45%, no hard split
        Shape clip = g.getClip();
        g.clip(shape);
        g.setPaint(linear(0, s.y, 0, s.y + s.height * .45, white(.16f * (1 - press)), white(0)));
        g.fill(new Rectangle2D.Double(s.x, s.y, s.width, s.height * .45));
        g.setClip(clip);
        rim(g, shape, .22f + .12f * hover - .15f * press);
        border(g, s.x, s.y, s.width, s.height, r, alpha(RED300, .22f + .18f * hover));
        return dy;
    }

    /**
     * A secondary button: an e3 surface, brighter on hover, dipping when pressed. {@code fill} null uses the neutral.
     */
    static Shape secondary(Graphics2D g, double x, double y, double w, double h, double r, float hover, float press) {
        Color top = mix(mix(N250, N300, hover), N250, press), bottom = mix(mix(new Color(0x181A21), N250, hover), N200, press);
        return surface(g, x, y + press, w, h, r, E2.noAmbient().rim(.08f), top, bottom, hover);
    }

    // ------------------------------------------------------------------ focus

    /**
     * The keyboard focus ring: 2px red-400 offset 3px outside the control, with an emissive outer glow
     * (glow.focus). {@code a} fades the glow in.
     */
    static void focusRing(Graphics2D g, Rectangle2D r, double radius, float a) {
        if (a <= 0.003f) return;
        double o = 3;
        double x = r.getX() - o, y = r.getY() - o, w = r.getWidth() + 2 * o, h = r.getHeight() + 2 * o, rad = radius + o;
        shadow(g, x, y, w, h, rad, 0, 4, .55f * a, RED600, 2);
        Stroke was = g.getStroke();
        g.setStroke(new BasicStroke(2f));
        g.setColor(alpha(RED400, Math.min(1f, a * 1.1f)));
        g.draw(rr(x, y, w, h, rad));
        g.setStroke(was);
    }

    /** The radius a control with this hit box most likely has (pills when short, buttons otherwise). */
    static double radiusFor(Rectangle2D r) {
        double h = r.getHeight();
        if (h <= 30) return h / 2;
        return R_SM + 2;
    }

    // ------------------------------------------------------------------ texture

    private static BufferedImage grain;

    /** A tiling 64×64 noise tile (±2 steps, centred on transparent) used to break up large flat fills. */
    static BufferedImage grain() {
        if (grain != null) return grain;
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        java.util.Random rnd = new java.util.Random(0x0B5D1A);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                float n = (ign(x * 7 + 3, y * 5 + 1) + rnd.nextFloat()) / 2f;
                int a = Math.round(Math.abs(n - .5f) * 2 * 9); // up to ~3.5% alpha
                img.setRGB(x, y, ((n > .5f ? 0xFFFFFF : 0x000000)) | (a << 24));
            }
        }
        grain = img;
        return img;
    }

    /** Lays the grain over {@code area} at {@code opacity} (on cached layers only: it costs a full pass). */
    static void grain(Graphics2D g, Shape area, float opacity) {
        Shape clip = g.getClip();
        g.clip(area);
        Composite was = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
        Rectangle b = area.getBounds();
        BufferedImage t = grain();
        for (int y = b.y; y < b.y + b.height; y += 64)
            for (int x = b.x; x < b.x + b.width; x += 64) g.drawImage(t, x, y, null);
        g.setComposite(was);
        g.setClip(clip);
    }
}
