package net.evadeclient.installer;

import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.LinearGradientPaint;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ImageObserver;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.RenderableImage;
import java.text.AttributedCharacterIterator;
import java.util.Map;

/**
 * Graphics that paint in the chosen theme: every colour (plain, gradient, background) passes through
 * {@link LauncherTheme#map} on its way to the real graphics, and (for the launcher's own painting) text sizes follow
 * the text-size setting. Getters hand back what the caller set, so code that saves and restores a colour still
 * works. Pictures are drawn unchanged. {@link #raw} paints a part as designed (the photo-backed Home hero stays dark).
 */
final class ThemedGraphics extends Graphics2D {
    private final Graphics2D d;
    private Color color = Color.BLACK, background;
    private Paint paint;
    private Font font;
    /** Text size factor for this graphics (1: as set). */
    float fontScale = 1f;
    /** 0: themed; 1: a dark island (accent only, no light theme); 2: colours as given; 3: danger (light theme, never the accent). */
    private int mode;

    ThemedGraphics(Graphics2D d) {
        this.d = d;
        color = d.getColor();
        paint = d.getPaint();
        font = d.getFont();
        background = d.getBackground();
    }

    private ThemedGraphics(Graphics2D d, ThemedGraphics from) {
        this.d = d;
        color = from.color;
        paint = from.paint;
        font = from.font;
        background = from.background;
        fontScale = from.fontScale;
        mode = from.mode;
    }

    /** The graphics with text scaled by the text-size setting (wrapping {@code g} when it isn't themed yet). */
    static Graphics2D withText(Graphics2D g) {
        float k = LauncherTheme.textScale();
        if (k == 1f) return g;
        ThemedGraphics t = g instanceof ThemedGraphics x ? x : new ThemedGraphics(g);
        t.fontScale = k;
        t.setFont(t.font);
        return t;
    }

    /** Turns colour mapping off (true) or back to themed (false) for {@code g}; returns the previous mode. */
    static int raw(Graphics2D g, boolean on) { return mode(g, on ? 2 : 0); }

    /** Paints as a dark island (the dark look with the accent, even in the light theme); returns the previous mode. */
    static int island(Graphics2D g) { return mode(g, 1); }

    /**
     * Paints danger and error colours (Delete, failures, crash cards) red whatever the accent: the light theme still
     * applies. Inside a dark island (or raw painting) colours are taken as given. Returns the previous mode.
     */
    static int danger(Graphics2D g) {
        if (!(g instanceof ThemedGraphics t)) return 0;
        return mode(g, t.mode == 1 || t.mode == 2 ? 2 : 3);
    }

    /** Sets the colour mode (0 themed, 1 dark island, 2 as given, 3 danger); returns the previous one. */
    static int mode(Graphics2D g, int mode) {
        if (!(g instanceof ThemedGraphics t)) return 0;
        int was = t.mode;
        t.mode = mode;
        t.setPaint(t.paint);
        t.setBackground(t.background);
        return was;
    }

    private Color m(Color c) {
        return mode == 2 ? c : mode == 1 ? LauncherTheme.mapDark(c) : mode == 3 ? LauncherTheme.mapDanger(c) : LauncherTheme.map(c);
    }

    private Paint m(Paint p) {
        if (mode == 2 || p == null) return p;
        if (p instanceof Color c) return m(c);
        if (p instanceof GradientPaint gp)
            return new GradientPaint(gp.getPoint1(), m(gp.getColor1()), gp.getPoint2(), m(gp.getColor2()), gp.isCyclic());
        if (p instanceof RadialGradientPaint rp)
            return new RadialGradientPaint(rp.getCenterPoint(), rp.getRadius(), rp.getFocusPoint(), rp.getFractions(), m(rp.getColors()),
                    rp.getCycleMethod(), rp.getColorSpace(), rp.getTransform());
        if (p instanceof LinearGradientPaint lp)
            return new LinearGradientPaint(lp.getStartPoint(), lp.getEndPoint(), lp.getFractions(), m(lp.getColors()),
                    lp.getCycleMethod(), lp.getColorSpace(), lp.getTransform());
        return p;
    }

    private Color[] m(Color[] cs) {
        Color[] out = new Color[cs.length];
        for (int i = 0; i < cs.length; i++) out[i] = m(cs[i]);
        return out;
    }

    private Font f(Font f) {
        if (f == null || fontScale == 1f) return f;
        return f.deriveFont(LauncherTheme.fontSize(f.getSize2D()));
    }

    // ------------------------------------------------------------------ state

    @Override public Graphics create() { return new ThemedGraphics((Graphics2D) d.create(), this); }
    @Override public Color getColor() { return color; }
    @Override public void setColor(Color c) {
        if (c == null) return;
        color = c;
        paint = c;
        d.setColor(m(c));
    }
    @Override public void setPaint(Paint p) {
        if (p == null) return;
        paint = p;
        if (p instanceof Color c) { color = c; d.setColor(m(c)); }
        else d.setPaint(m(p));
    }
    @Override public Paint getPaint() { return paint; }
    @Override public void setBackground(Color c) { background = c; d.setBackground(m(c)); }
    @Override public Color getBackground() { return background; }
    @Override public Font getFont() { return font; }
    @Override public void setFont(Font f) { if (f == null) return; font = f; d.setFont(f(f)); }
    @Override public FontMetrics getFontMetrics() { return d.getFontMetrics(); }
    @Override public FontMetrics getFontMetrics(Font f) { return d.getFontMetrics(f(f)); }
    @Override public void setPaintMode() { d.setPaintMode(); }
    @Override public void setXORMode(Color c) { d.setXORMode(m(c)); }
    @Override public void setComposite(Composite c) { d.setComposite(c); }
    @Override public Composite getComposite() { return d.getComposite(); }
    @Override public void setStroke(Stroke s) { d.setStroke(s); }
    @Override public Stroke getStroke() { return d.getStroke(); }
    @Override public void setRenderingHint(RenderingHints.Key k, Object v) { d.setRenderingHint(k, v); }
    @Override public Object getRenderingHint(RenderingHints.Key k) { return d.getRenderingHint(k); }
    @Override public void setRenderingHints(Map<?, ?> h) { d.setRenderingHints(h); }
    @Override public void addRenderingHints(Map<?, ?> h) { d.addRenderingHints(h); }
    @Override public RenderingHints getRenderingHints() { return d.getRenderingHints(); }
    @Override public GraphicsConfiguration getDeviceConfiguration() { return d.getDeviceConfiguration(); }
    @Override public FontRenderContext getFontRenderContext() { return d.getFontRenderContext(); }

    // ------------------------------------------------------------------ transform and clip

    @Override public void translate(int x, int y) { d.translate(x, y); }
    @Override public void translate(double x, double y) { d.translate(x, y); }
    @Override public void rotate(double t) { d.rotate(t); }
    @Override public void rotate(double t, double x, double y) { d.rotate(t, x, y); }
    @Override public void scale(double sx, double sy) { d.scale(sx, sy); }
    @Override public void shear(double sx, double sy) { d.shear(sx, sy); }
    @Override public void transform(AffineTransform t) { d.transform(t); }
    @Override public void setTransform(AffineTransform t) { d.setTransform(t); }
    @Override public AffineTransform getTransform() { return d.getTransform(); }
    @Override public Rectangle getClipBounds() { return d.getClipBounds(); }
    @Override public Rectangle getClipBounds(Rectangle r) { return d.getClipBounds(r); }
    @Override public void clipRect(int x, int y, int w, int h) { d.clipRect(x, y, w, h); }
    @Override public void setClip(int x, int y, int w, int h) { d.setClip(x, y, w, h); }
    @Override public Shape getClip() { return d.getClip(); }
    @Override public void setClip(Shape s) { d.setClip(s); }
    @Override public void clip(Shape s) { d.clip(s); }
    @Override public boolean hitClip(int x, int y, int w, int h) { return d.hitClip(x, y, w, h); }
    @Override public boolean hit(Rectangle r, Shape s, boolean onStroke) { return d.hit(r, s, onStroke); }

    // ------------------------------------------------------------------ drawing

    @Override public void draw(Shape s) { d.draw(s); }
    @Override public void fill(Shape s) { d.fill(s); }
    @Override public void copyArea(int x, int y, int w, int h, int dx, int dy) { d.copyArea(x, y, w, h, dx, dy); }
    @Override public void drawLine(int x1, int y1, int x2, int y2) { d.drawLine(x1, y1, x2, y2); }
    @Override public void fillRect(int x, int y, int w, int h) { d.fillRect(x, y, w, h); }
    @Override public void drawRect(int x, int y, int w, int h) { d.drawRect(x, y, w, h); }
    @Override public void clearRect(int x, int y, int w, int h) { d.clearRect(x, y, w, h); }
    @Override public void drawRoundRect(int x, int y, int w, int h, int aw, int ah) { d.drawRoundRect(x, y, w, h, aw, ah); }
    @Override public void fillRoundRect(int x, int y, int w, int h, int aw, int ah) { d.fillRoundRect(x, y, w, h, aw, ah); }
    @Override public void drawOval(int x, int y, int w, int h) { d.drawOval(x, y, w, h); }
    @Override public void fillOval(int x, int y, int w, int h) { d.fillOval(x, y, w, h); }
    @Override public void drawArc(int x, int y, int w, int h, int s, int a) { d.drawArc(x, y, w, h, s, a); }
    @Override public void fillArc(int x, int y, int w, int h, int s, int a) { d.fillArc(x, y, w, h, s, a); }
    @Override public void drawPolyline(int[] x, int[] y, int n) { d.drawPolyline(x, y, n); }
    @Override public void drawPolygon(int[] x, int[] y, int n) { d.drawPolygon(x, y, n); }
    @Override public void fillPolygon(int[] x, int[] y, int n) { d.fillPolygon(x, y, n); }
    @Override public void drawString(String s, int x, int y) { d.drawString(s, x, y); }
    @Override public void drawString(String s, float x, float y) { d.drawString(s, x, y); }
    @Override public void drawString(AttributedCharacterIterator it, int x, int y) { d.drawString(it, x, y); }
    @Override public void drawString(AttributedCharacterIterator it, float x, float y) { d.drawString(it, x, y); }
    @Override public void drawChars(char[] c, int o, int n, int x, int y) { d.drawChars(c, o, n, x, y); }
    @Override public void drawGlyphVector(GlyphVector gv, float x, float y) { d.drawGlyphVector(gv, x, y); }
    @Override public boolean drawImage(Image img, AffineTransform t, ImageObserver o) { return d.drawImage(img, t, o); }
    @Override public void drawImage(BufferedImage img, BufferedImageOp op, int x, int y) { d.drawImage(img, op, x, y); }
    @Override public void drawRenderedImage(RenderedImage img, AffineTransform t) { d.drawRenderedImage(img, t); }
    @Override public void drawRenderableImage(RenderableImage img, AffineTransform t) { d.drawRenderableImage(img, t); }
    @Override public boolean drawImage(Image img, int x, int y, ImageObserver o) { return d.drawImage(img, x, y, o); }
    @Override public boolean drawImage(Image img, int x, int y, int w, int h, ImageObserver o) { return d.drawImage(img, x, y, w, h, o); }
    @Override public boolean drawImage(Image img, int x, int y, Color bg, ImageObserver o) { return d.drawImage(img, x, y, m(bg), o); }
    @Override public boolean drawImage(Image img, int x, int y, int w, int h, Color bg, ImageObserver o) { return d.drawImage(img, x, y, w, h, m(bg), o); }
    @Override public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, ImageObserver o) {
        return d.drawImage(img, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, o);
    }
    @Override public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, Color bg, ImageObserver o) {
        return d.drawImage(img, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, m(bg), o);
    }
    @Override public void dispose() { d.dispose(); }
}
