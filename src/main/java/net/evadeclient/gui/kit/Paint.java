package net.evadeclient.gui.kit;

import net.evadeclient.render.ui.UiShape;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.Tokens.Elevation;
import net.minecraft.client.gui.DrawContext;
import org.joml.Matrix3x2f;

/**
 * The kit's drawing layer: surfaces at an elevation, fills, gradients, rings, glows and the slanted Evade motif.
 * Each call draws through {@link UiShape} (exact SDF shapes, dithered gradients, Gaussian shadows, additive glows)
 * when it is {@link UiShape#available() available}, and otherwise through {@link Theme}'s texture shapes, which look
 * close (the Classic theme, a driver where the shader failed, a forced fallback). Coordinates are GUI pixels (floats
 * allowed); colours are ARGB and honour {@link Theme#fade}.
 */
public final class Paint {
    private Paint() {}

    /** Whether the shader path is drawing (otherwise the texture fallback). */
    public static boolean gpu() { return UiShape.available(); }

    /** A plain rounded fill. */
    public static void rect(DrawContext ctx, float x1, float y1, float x2, float y2, float r, int color) {
        if (gpu()) { UiShape.shape(x1, y1, x2, y2).radius(r).fill(color).draw(ctx); return; }
        Theme.round(ctx, Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2), color, Math.round(r));
    }

    /** A pill (fully rounded ends). */
    public static void pill(DrawContext ctx, float x1, float y1, float x2, float y2, int color) {
        rect(ctx, x1, y1, x2, y2, Math.min(x2 - x1, y2 - y1) / 2f, color);
    }

    /** A circle of diameter {@code d} centred on (cx, cy). */
    public static void circle(DrawContext ctx, float cx, float cy, float d, int color) {
        if (gpu()) { UiShape.shape(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2).radius(d / 2).fill(color).draw(ctx); return; }
        Theme.disc(ctx, Math.round(cx - d / 2), Math.round(cy - d / 2), Math.round(d), color);
    }

    /** A border ring inside the rect: {@code devicePx} device pixels wide (1 = hairline at any scale). */
    public static void ring(DrawContext ctx, float x1, float y1, float x2, float y2, float r, int color, float devicePx) {
        if (gpu()) { UiShape.shape(x1, y1, x2, y2).radius(r).border(color, devicePx).draw(ctx); return; }
        int ix1 = Math.round(x1), iy1 = Math.round(y1), ix2 = Math.round(x2), iy2 = Math.round(y2), ir = Math.round(r);
        if (devicePx <= 1.01f) Theme.hairline(ctx, ix1, iy1, ix2, iy2, color, ir);
        else Theme.stroke(ctx, ix1, iy1, ix2, iy2, color, ir);
    }

    /** A dithered vertical gradient (top to bottom). Falls back to vanilla's undithered gradient. */
    public static void vgradient(DrawContext ctx, float x1, float y1, float x2, float y2, float r, int top, int bottom) {
        if (gpu()) { UiShape.shape(x1, y1, x2, y2).radius(r).vertical(top, bottom).draw(ctx); return; }
        if (r > 0) { Theme.round(ctx, Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2), Theme.lerpColor(top, bottom, 0.5f), Math.round(r)); return; }
        ctx.fillGradient(Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2), Theme.fa(top), Theme.fa(bottom));
    }

    /** A dithered horizontal gradient (left to right). */
    public static void hgradient(DrawContext ctx, float x1, float y1, float x2, float y2, float r, int left, int right) {
        if (gpu()) { UiShape.shape(x1, y1, x2, y2).radius(r).horizontal(left, right).draw(ctx); return; }
        Theme.round(ctx, Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2), Theme.lerpColor(left, right, 0.5f), Math.round(r));
    }

    /** A dithered radial gradient: {@code centre} at (cx, cy) to {@code edge} at radius {@code rad}, clipped to the rect. */
    public static void radial(DrawContext ctx, float x1, float y1, float x2, float y2, float r, int centre, int edge, float cx, float cy, float rad) {
        if (gpu()) { UiShape.shape(x1, y1, x2, y2).radius(r).radial(centre, edge, cx - x1, cy - y1, rad).draw(ctx); return; }
        // No soft radial without the shader: a faint flat wash of the mid colour.
        rect(ctx, x1, y1, x2, y2, r, Theme.lerpColor(centre, edge, 0.75f));
    }

    /** A 1 px line fading out at both ends (section dividers). */
    public static void fadeLine(DrawContext ctx, float x1, float y, float x2, int color) {
        if (gpu()) { UiShape.shape(x1, y, x2, y + 1).mirror(color, color & 0xFFFFFF, true, 0.4f).draw(ctx); return; }
        Theme.fadeLine(ctx, Math.round(x1), Math.round(y), Math.round(x2), color);
    }

    /**
     * A surface at elevation {@code e}: the ambient and contact shadows, the fill, an all-round hairline and the
     * specular top rim. {@code lift} (0..1, hover) raises it a little: brighter rim, longer shadow. {@code rimX} is the
     * rim's brightest point in GUI px from the left edge (cursor-follow light), or NaN for the middle.
     */
    public static void surface(DrawContext ctx, float x1, float y1, float x2, float y2, float r, Elevation e, float lift, float rimX) {
        int fill = e.fill();
        boolean translucent = (fill >>> 24) < 0xF8;
        if (gpu()) {
            // Shadows are knocked out of the body (it covers that area anyway): the shader then skips every pixel
            // under the panel, and translucent glass is never darkened by its own shadow.
            if (e.ambientAlpha() > 0)
                UiShape.shape(x1, y1, x2, y2).radius(r).fill(Theme.alpha(0xFF000000, e.ambientAlpha() * (0.85f + 0.3f * lift)))
                        .asShadow(e.ambientSigma() * (1f + 0.25f * lift), e.ambientY() * (1f + 0.3f * lift), true).draw(ctx);
            if (e.contactAlpha() > 0)
                UiShape.shape(x1, y1, x2, y2).radius(r).fill(Theme.alpha(0xFF000000, e.contactAlpha()))
                        .asShadow(e.contactSigma(), e.contactY(), true).draw(ctx);
            UiShape.Shape s = UiShape.shape(x1, y1, x2, y2).radius(r).fill(fill)
                    .border(Theme.alpha(0xFFFFFFFF, e.border()), 1).rim(Math.min(0.24f, e.rim() * (1f + 0.6f * lift))).shade(e.shade());
            if (!Float.isNaN(rimX)) s.rimAt(rimX);
            if (translucent && e == Tokens.E1) s.grain(Tokens.reduceTransparency ? 0 : 0.015f);
            s.draw(ctx);
            return;
        }
        int ix1 = Math.round(x1), iy1 = Math.round(y1), ix2 = Math.round(x2), iy2 = Math.round(y2), ir = Math.min(8, Math.round(r));
        if (e.ambientAlpha() > 0)
            Theme.shadow(ctx, e.ambientSigma() >= 20 ? Theme.SH_WINDOW : Theme.SH_CARD, ix1, iy1, ix2, iy2, Theme.alpha(0xFF000000, e.ambientAlpha()));
        Theme.round(ctx, ix1, iy1, ix2, iy2, fill, ir);
        if (e.border() > 0) Theme.hairline(ctx, ix1, iy1, ix2, iy2, Theme.alpha(0xFFFFFFFF, Math.max(0.08f, e.border() * 1.6f)), ir);
        if (e.shade() < 0 && iy2 - iy1 > 3) Theme.fill(ctx, ix1 + ir, iy1, ix2 - ir, iy1 + 1, Theme.alpha(0xFF000000, 0.25f));
    }

    /** A sunken well (inputs, tracks): darker fill, a faint hairline and an inner shade along the top edge. */
    public static void sunken(DrawContext ctx, float x1, float y1, float x2, float y2, float r) {
        surface(ctx, x1, y1, x2, y2, r, Tokens.SUNKEN, 0, Float.NaN);
    }

    /**
     * The keyboard focus ring: 2 device px of accent ({@link Tokens#FOCUS_RING}) 2 device px outside the rect, with
     * a soft additive glow when the kit's glow budget allows.
     */
    public static void focusRing(Kit k, float x1, float y1, float x2, float y2, float r, int color) {
        DrawContext ctx = k.ctx;
        float o = 2f / Theme.deviceScale(ctx) + 2f / Theme.deviceScale(ctx);
        if (gpu()) {
            if (k.glow()) UiShape.glow(ctx, x1 - o, y1 - o, x2 + o, y2 + o, r + o, 1.5f, Theme.alpha(Tokens.A600, 0.40f));
            UiShape.shape(x1 - o, y1 - o, x2 + o, y2 + o).radius(r + o).border(color, 2).draw(ctx);
            return;
        }
        Theme.stroke(ctx, Math.round(x1 - 1), Math.round(y1 - 1), Math.round(x2 + 1), Math.round(y2 + 1), color, Math.round(r + 1));
    }

    /** An additive glow around the rect (light: brightens what is under it). Counts against the kit's glow budget;
     *  nothing without the shader. */
    public static void glow(Kit k, float x1, float y1, float x2, float y2, float r, float sigma, int color) {
        if (!gpu() || (color >>> 24) == 0 || !k.glow()) return;
        UiShape.glow(k.ctx, x1, y1, x2, y2, r, sigma, color);
    }

    /**
     * The Evade slant: a bar from (x1, y1) to (x2, y2) sheared 12 degrees forward (the top leans right), with
     * anti-aliased slanted ends. Tab indicators, selected edge lights, progress heads.
     */
    public static void slant(DrawContext ctx, float x1, float y1, float x2, float y2, int color) {
        if (!gpu()) { Theme.fill(ctx, Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2), color); return; }
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            float cy = (y1 + y2) / 2f, t = (float) Math.tan(Math.toRadians(Tokens.SLANT_DEG));
            m.translate(0, cy);
            m.mul(new Matrix3x2f(1, 0, -t, 1, 0, 0));
            UiShape.shape(x1, y1 - cy, x2, y2 - cy).radius(0.5f).fill(color).draw(ctx);
        } finally {
            m.popMatrix();
        }
    }

    /** A dashed rounded outline (empty slots: a key chip with no key, an empty recent colour). */
    public static void dashed(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
        int dash = 3, gap = 2;
        for (int x = x1 + 2; x < x2 - 2; x += dash + gap) {
            Theme.fill(ctx, x, y1, Math.min(x + dash, x2 - 2), y1 + 1, color);
            Theme.fill(ctx, x, y2 - 1, Math.min(x + dash, x2 - 2), y2, color);
        }
        for (int y = y1 + 2; y < y2 - 2; y += dash + gap) {
            Theme.fill(ctx, x1, y, x1 + 1, Math.min(y + dash, y2 - 2), color);
            Theme.fill(ctx, x2 - 1, y, x2, Math.min(y + dash, y2 - 2), color);
        }
    }

    /** A full-screen scrim: n0 from 0.55 at the top to 0.78 at the bottom with a faint accent tint, dithered. */
    public static void scrim(DrawContext ctx, int w, int h, float strength) {
        int top = Theme.alpha(Tokens.N0, 0.55f * strength), bottom = Theme.alpha(Theme.lerpColor(Tokens.N0, Tokens.A900, 0.10f), 0.78f * strength);
        vgradient(ctx, 0, 0, w, h, 0, top, bottom);
    }

    /**
     * Hover tilt: a pseudo-rotation of at most 1.2 degrees toward the cursor for the card at (x1, y1)-(x2, y2), as a
     * scale and shear about its centre. {@code h} is the hover spring (0..1). Push before drawing the card, then
     * {@link #endTilt}. No tilt below Full motion; always pair the calls.
     */
    public static void beginTilt(Kit k, float x1, float y1, float x2, float y2, float h) {
        var m = k.ctx.getMatrices();
        m.pushMatrix();
        if (h <= 0.001f || !net.evadeclient.gui.Motion.decorative()) return;
        float cx = (x1 + x2) / 2f, cy = (y1 + y2) / 2f;
        float px = Math.max(-1, Math.min(1, (k.mx - cx) / Math.max(1f, (x2 - x1) / 2f)));
        float py = Math.max(-1, Math.min(1, (k.my - cy) / Math.max(1f, (y2 - y1) / 2f)));
        float sc = 1f + 0.012f * h;
        m.translate(cx, cy);
        m.mul(new Matrix3x2f(sc, 0.006f * px * h, 0.010f * py * h, sc, 0, 0));
        m.translate(-cx, -cy);
    }

    public static void endTilt(Kit k) { k.ctx.getMatrices().popMatrix(); }
}
