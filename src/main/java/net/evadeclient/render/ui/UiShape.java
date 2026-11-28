package net.evadeclient.render.ui;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.platform.DestFactor;
import com.mojang.blaze3d.platform.SourceFactor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.evadeclient.EvadeClient;
import net.evadeclient.mixin.BufferBuilderAccessor;
import net.evadeclient.util.Theme;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;
import org.lwjgl.system.MemoryUtil;

/**
 * Evade's GPU shape primitive: one quad per shape, drawn per pixel by {@code evade:core/ui_shape} as an analytic
 * signed-distance rounded rectangle. Corners, rings and 1-device-pixel borders are exact at every GUI scale and
 * window size (no corner textures to magnify), shadows are true Gaussians, and every gradient is dithered.
 *
 * <p>Shapes are GUI elements ({@code ctx.state.addSimpleElement}) like vanilla fills, so they are clipped by the
 * scissor stack, layered by overlap and batched: all the shapes between two text runs are one draw call. Every shape
 * parameter rides in the vertex attributes of a custom vertex format, so there are no per-draw uniforms.
 *
 * <p>Use {@link #available()} first: it is false in the Classic theme, inside {@link #legacyScope} (the HUD), when a
 * test forces the fallback, or when the shader did not compile on this driver. Callers then draw the texture-based
 * shapes in {@link Theme} instead (which is what {@code Theme}'s own methods do automatically).
 *
 * <p>Coordinates and radii are in the current matrix's units (GUI pixels), floats allowed; border widths are given
 * in device pixels ({@link Shape#border}) or GUI pixels ({@link Shape#borderGui}). Colours are ARGB with straight
 * alpha and are multiplied by the group {@link Theme#fade} unless {@link Shape#raw()} is set. Render thread only.
 */
public final class UiShape {
    private UiShape() {}

    /** Shader modes (the low two bits of the flags). */
    static final int M_FILL = 0, M_SHADOW = 1, M_GLOW = 2, M_ARC = 3, F_KNOCKOUT = 4;
    /** Gradient kinds: none, linear, radial, mirrored linear (centre colour A, both ends colour B). */
    static final int G_NONE = 0, G_LINEAR = 1, G_RADIAL = 2, G_MIRROR = 3;

    // ------------------------------------------------------------------ vertex format and pipelines

    private static VertexFormatElement E_A, E_R, E_G, E_L, E_CB, E_CC;
    private static final VertexFormat FORMAT;
    private static final RenderPipeline PIPE, PIPE_ADD;

    static {
        VertexFormat format = null;
        RenderPipeline pipe = null, add = null;
        try {
            E_A = element(VertexFormatElement.Type.FLOAT, 4);
            E_R = element(VertexFormatElement.Type.FLOAT, 4);
            E_G = element(VertexFormatElement.Type.FLOAT, 4);
            E_L = element(VertexFormatElement.Type.FLOAT, 4);
            E_CB = element(VertexFormatElement.Type.UBYTE, 4);
            E_CC = element(VertexFormatElement.Type.UBYTE, 4);
            format = VertexFormat.builder()
                    .add("Position", VertexFormatElement.POSITION)
                    .add("Color", VertexFormatElement.COLOR)
                    .add("UV0", VertexFormatElement.UV0)
                    .add("ShapeA", E_A)
                    .add("ShapeR", E_R)
                    .add("ShapeG", E_G)
                    .add("ShapeL", E_L)
                    .add("ColorB", E_CB)
                    .add("ColorC", E_CC)
                    .build();
            pipe = pipeline("pipeline/ui_shape", format, BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA);
            // Glows add light (premultiplied colour, dst alpha kept).
            add = pipeline("pipeline/ui_shape_add", format,
                    new BlendFunction(SourceFactor.ONE, DestFactor.ONE, SourceFactor.ZERO, DestFactor.ONE));
        } catch (Throwable t) {
            EvadeClient.LOG.warn("Evade GUI shapes: could not set up the shape vertex format, using the texture shapes", t);
            format = null;
        }
        FORMAT = format;
        PIPE = pipe;
        PIPE_ADD = add;
    }

    /** A generic vertex element on the highest free element id (other mods, Iris included, take the next free low one). */
    private static VertexFormatElement element(VertexFormatElement.Type type, int count) {
        for (int id = VertexFormatElement.MAX_COUNT - 1; id > 6; id--)
            if (VertexFormatElement.byId(id) == null)
                return VertexFormatElement.register(id, 0, type, VertexFormatElement.Usage.GENERIC, count);
        throw new IllegalStateException("no free vertex format element id");
    }

    private static RenderPipeline pipeline(String location, VertexFormat format, BlendFunction blend) {
        return RenderPipeline.builder()
                .withLocation(Identifier.of("evade", location))
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withVertexShader(Identifier.of("evade", "core/ui_shape"))
                .withFragmentShader(Identifier.of("evade", "core/ui_shape"))
                .withBlend(blend)
                .withDepthWrite(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withVertexFormat(format, VertexFormat.DrawMode.QUADS)
                .build();
    }

    // ------------------------------------------------------------------ availability

    private static boolean forced, valid, warned;
    private static long checkedAt = Long.MIN_VALUE;
    private static int legacyDepth;
    /** Shapes submitted since launch (tests and the perf check read the difference). */
    public static long submitted;

    /**
     * Whether shapes can be drawn by the shader right now: the pipelines compiled (re-checked a few times a second,
     * so a resource reload that fixes or breaks the shader is picked up), not the Classic theme, not inside
     * {@link #legacyScope}, and not {@link #forceFallback forced off}.
     */
    public static boolean available() {
        if (forced || legacyDepth > 0 || Theme.square || FORMAT == null) return false;
        return compiled();
    }

    /** Whether the shape pipelines compiled on this driver (independent of the theme and scopes). */
    public static boolean compiled() {
        if (FORMAT == null) return false;
        long now = System.nanoTime();
        if (now - checkedAt < 250_000_000L && checkedAt != Long.MIN_VALUE) return valid;
        checkedAt = now;
        try {
            var device = RenderSystem.getDevice();
            valid = device.precompilePipeline(PIPE).isValid() && device.precompilePipeline(PIPE_ADD).isValid();
        } catch (Throwable t) {
            valid = false;
        }
        if (!valid && !warned) {
            var mc = net.minecraft.client.MinecraftClient.getInstance();
            // While the first resource load runs the shader source may simply not be there yet: no warning then.
            if (mc != null && mc.getOverlay() == null) {
                warned = true;
                EvadeClient.LOG.warn("Evade GUI shapes: the ui_shape shader didn't compile on this driver, using the texture shapes");
            }
        }
        return valid;
    }

    /** Tests and diagnostics: draw every shape with the texture fallback while {@code on}. */
    public static void forceFallback(boolean on) { forced = on; }

    /** Whether the fallback is forced (see {@link #forceFallback}). */
    public static boolean fallbackForced() { return forced; }

    /** Enter / leave a scope (nestable) in which {@link #available()} is false: the HUD keeps the cheap texture path. */
    public static void legacyScope(boolean enter) {
        legacyDepth = Math.max(0, legacyDepth + (enter ? 1 : -1));
    }

    // ------------------------------------------------------------------ the builder

    private static final Shape SCRATCH = new Shape();

    /**
     * Start a shape covering (x1, y1)-(x2, y2). Configure it and call {@link Shape#draw}. The returned builder is a
     * shared scratch object (render thread only): don't keep it across another {@code shape} call.
     */
    public static Shape shape(float x1, float y1, float x2, float y2) {
        return SCRATCH.reset(x1, y1, x2, y2);
    }

    /** A filled rounded rect (radius {@code r}, clamped to half the shorter side). */
    public static void round(DrawContext ctx, float x1, float y1, float x2, float y2, int color, float r) {
        shape(x1, y1, x2, y2).radius(r).fill(color).draw(ctx);
    }

    /**
     * A soft Gaussian shadow of the rounded rect, {@code sigma} GUI px wide and pushed down {@code offY} GUI px.
     * {@code knockout} cuts the rect's own body out, so it can sit under a translucent panel without darkening it.
     */
    public static void shadow(DrawContext ctx, float x1, float y1, float x2, float y2, float r, float sigma, float offY,
                              int color, boolean knockout) {
        shape(x1, y1, x2, y2).radius(r).fill(color).asShadow(sigma, offY, knockout).draw(ctx);
    }

    /** An additive Gaussian glow around the rounded rect (light, not paint: it brightens what is under it). Only the
     *  outside glows: the rect's own body is knocked out, so it never tints what sits inside the shape. */
    public static void glow(DrawContext ctx, float x1, float y1, float x2, float y2, float r, float sigma, int color) {
        shape(x1, y1, x2, y2).radius(r).fill(color).asGlow(sigma, true).draw(ctx);
    }

    /**
     * A round-capped ring arc centred on (cx, cy): outer radius {@code radius}, {@code thickness} wide, from
     * {@code startDeg} (0 = top, clockwise) sweeping {@code sweepDeg}. 360 draws a full ring.
     */
    public static void arc(DrawContext ctx, float cx, float cy, float radius, float thickness, float startDeg, float sweepDeg, int color) {
        Shape s = shape(cx - radius, cy - radius, cx + radius, cy + radius).fill(color).mode(M_ARC);
        s.arcStart = (float) Math.toRadians(startDeg);
        s.arcSweep = (float) Math.toRadians(Math.max(0f, Math.min(360f, sweepDeg)));
        s.arcThick = thickness;
        s.draw(ctx);
    }

    /** One shape being configured (see {@link #shape}). All setters return {@code this}. */
    public static final class Shape {
        float x1, y1, x2, y2;
        float rTL, rTR, rBR, rBL;
        int colA, colB, colC;
        int mode, grad;
        float gx, gy, gz;          // gradient vector / centre (GUI px, relative to the shape centre) and end/start/radius
        boolean gradDevice;        // gx..gz already in device px
        float borderPx, borderGui;
        float rim, shade, grain, rimX;
        float sigma, offY;
        float arcStart, arcSweep, arcThick;
        boolean raw;

        Shape reset(float x1, float y1, float x2, float y2) {
            this.x1 = Math.min(x1, x2); this.y1 = Math.min(y1, y2);
            this.x2 = Math.max(x1, x2); this.y2 = Math.max(y1, y2);
            rTL = rTR = rBR = rBL = 0;
            colA = colB = colC = 0;
            mode = M_FILL; grad = G_NONE;
            gx = gy = gz = 0; gradDevice = false;
            borderPx = borderGui = 0;
            rim = shade = grain = 0; rimX = Float.NaN;
            sigma = offY = 0;
            arcStart = arcSweep = arcThick = 0;
            raw = false;
            return this;
        }

        /** Every corner rounded by {@code r} GUI px. */
        public Shape radius(float r) { rTL = rTR = rBR = rBL = Math.max(0, r); return this; }

        /** Each corner on its own (GUI px): top-left, top-right, bottom-right, bottom-left. */
        public Shape radii(float tl, float tr, float br, float bl) {
            rTL = Math.max(0, tl); rTR = Math.max(0, tr); rBR = Math.max(0, br); rBL = Math.max(0, bl);
            return this;
        }

        /** Fully rounded ends (radius half the shorter side). */
        public Shape pill() { return radius(Math.min(x2 - x1, y2 - y1) / 2f); }

        /** A flat fill (or the shadow / glow colour). */
        public Shape fill(int argb) { colA = argb; return this; }

        /** Top-to-bottom gradient. */
        public Shape vertical(int top, int bottom) { return linear(top, bottom, 0, (y2 - y1), 1f); }

        /** Left-to-right gradient. */
        public Shape horizontal(int left, int right) { return linear(left, right, (x2 - x1), 0, 1f); }

        /**
         * A linear gradient from {@code a} to {@code b} along (dx, dy) GUI px, centred on the shape, reaching
         * {@code b} at fraction {@code end} of the way (1 = the far end; 0.45 = a sheen over the first 45%).
         */
        public Shape linear(int a, int b, float dx, float dy, float end) {
            colA = a; colB = b; grad = G_LINEAR; gx = dx; gy = dy; gz = end; return this;
        }

        /** A radial gradient: {@code centre} at (cx, cy) (GUI px from the shape's top-left) fading to {@code edge} at {@code r}. */
        public Shape radial(int centre, int edge, float cx, float cy, float r) {
            colA = centre; colB = edge; grad = G_RADIAL;
            gx = cx - (x2 - x1) / 2f; gy = cy - (y2 - y1) / 2f; gz = r;
            return this;
        }

        /** Colour {@code centre} in the middle, {@code ends} at both ends of the horizontal (or vertical) axis,
         *  starting to change {@code start} (0..1) of the way out. A fade line is {@code mirror(c, alpha(c, 0), true, 0.5f)}. */
        public Shape mirror(int centre, int ends, boolean horizontal, float start) {
            colA = centre; colB = ends; grad = G_MIRROR;
            gx = horizontal ? (x2 - x1) : 0; gy = horizontal ? 0 : (y2 - y1); gz = start;
            return this;
        }

        /** A border ring inside the edge, {@code devicePx} device pixels wide (1 = a true hairline at any scale). */
        public Shape border(int argb, float devicePx) { colC = argb; borderPx = Math.max(0, devicePx); borderGui = 0; return this; }

        /** A border ring inside the edge, {@code guiPx} GUI pixels wide. */
        public Shape borderGui(int argb, float guiPx) { colC = argb; borderGui = Math.max(0, guiPx); borderPx = 0; return this; }

        /** The specular top rim: a 1 device px white light catch at {@code alpha} (0.06..0.22). */
        public Shape rim(float alpha) { rim = Math.max(0, alpha); return this; }

        /** Where the rim is brightest, in GUI px from the shape's left edge (default: the middle). Cursor-follow light. */
        public Shape rimAt(float x) { rimX = x; return this; }

        /** An inner shade: {@code s} > 0 darkens inside the bottom edge (raised), {@code s} < 0 inside the top (sunken). */
        public Shape shade(float s) { shade = Math.max(-1, Math.min(1, s)); return this; }

        /** Static grain over the fill (0.015 = 1.5%), for large translucent glass panels. */
        public Shape grain(float amount) { grain = Math.max(0, amount); return this; }

        /** Colours already carry the group fade (Theme's own methods): don't apply it again. */
        public Shape raw() { raw = true; return this; }

        /** Draw a soft Gaussian shadow of this shape instead ({@code sigma} GUI px, pushed down {@code offY}); with
         *  {@code knockout} the shape's own body is cut out so it can sit under a translucent panel. */
        public Shape asShadow(float sigma, float offY, boolean knockout) {
            return mode(M_SHADOW | (knockout ? F_KNOCKOUT : 0)).sigma(sigma, offY);
        }

        /** Draw an additive Gaussian glow of this shape instead ({@code sigma} GUI px): light added to what is below.
         *  With {@code knockout} only the outside glows (halos, focus glows); without it the whole shape is a soft light
         *  (a radial ember or logo glow drawn as a small circle with a large sigma). */
        public Shape asGlow(float sigma, boolean knockout) { return mode(M_GLOW | (knockout ? F_KNOCKOUT : 0)).sigma(sigma, 0); }

        Shape mode(int m) { mode = m; return this; }

        Shape sigma(float s, float offsetY) { sigma = Math.max(0.05f, s); offY = offsetY; return this; }

        /** Submit the shape. Draws nothing when it is empty or fully transparent. */
        public void draw(DrawContext ctx) {
            if (FORMAT == null || !(x2 > x1) || !(y2 > y1)) return;
            int a = raw ? colA : Theme.fa(colA), b = raw ? colB : Theme.fa(colB), c = raw ? colC : Theme.fa(colC);
            boolean visible = (a >>> 24) != 0 || (grad != G_NONE && (b >>> 24) != 0)
                    || ((c >>> 24) != 0 && (borderPx > 0 || borderGui > 0)) || rim > 0 || shade != 0;
            if (!visible) return;
            float k = Theme.deviceScale(ctx);
            if (!(k > 0) || !Float.isFinite(k)) k = 1f;
            float hw = (x2 - x1) / 2f, hh = (y2 - y1) / 2f, lim = Math.min(hw, hh);
            int m = mode & 3;
            float reach; // how far the quad reaches outside the rect, GUI px
            if (m == M_SHADOW || m == M_GLOW) reach = sigma * 3f + Math.abs(offY) + 1f / k;
            else reach = 1f / k;
            ShapeElement e = new ShapeElement();
            e.pipeline = m == M_GLOW ? PIPE_ADD : PIPE;
            e.pose = new Matrix3x2f(ctx.getMatrices());
            e.qx1 = x1 - reach; e.qy1 = y1 - reach; e.qx2 = x2 + reach; e.qy2 = y2 + reach;
            e.cx = (x1 + x2) / 2f; e.cy = (y1 + y2) / 2f; e.k = k;
            e.colA = a; e.colB = b; e.colC = c;
            e.p[0] = hw * k; e.p[1] = hh * k;
            e.p[2] = borderPx > 0 ? borderPx : borderGui * k;
            e.p[3] = mode;
            e.p[4] = Math.min(rTL, lim) * k; e.p[5] = Math.min(rTR, lim) * k;
            e.p[6] = Math.min(rBR, lim) * k; e.p[7] = Math.min(rBL, lim) * k;
            if (m == M_ARC) {
                e.p[8] = arcStart; e.p[9] = arcSweep; e.p[10] = arcThick * k; e.p[11] = 0;
            } else {
                e.p[8] = gx * k; e.p[9] = gy * k;
                e.p[10] = grad == G_RADIAL ? gz * k : gz;
                e.p[11] = grad;
            }
            if (m == M_SHADOW || m == M_GLOW) {
                e.p[12] = 0; e.p[13] = 0; e.p[14] = sigma * k; e.p[15] = offY * k;
            } else {
                e.p[12] = rim; e.p[13] = shade; e.p[14] = grain;
                e.p[15] = Float.isNaN(rimX) ? 0 : (rimX - hw) * k;
            }
            // Large flat fills and bare rings: only the edge bands need the per-pixel shader. The flat inside is a
            // plain fill (or nothing, for a ring), which keeps big panels cheap on slow GPUs and software renderers.
            // Knocked-out shadows and glows likewise skip the body they are cut out of.
            boolean knocked = (m == M_SHADOW || m == M_GLOW) && (mode & F_KNOCKOUT) != 0;
            if ((m == M_FILL && grad == G_NONE && grain == 0) || knocked) {
                float maxR = Math.max(Math.max(e.p[4], e.p[5]), Math.max(e.p[6], e.p[7]));
                float inset = (knocked ? maxR + 1f : Math.max(maxR, e.p[2] + 3f)) / k;
                int ix1 = (int) Math.ceil(x1 + inset), iy1 = (int) Math.ceil(y1 + inset);
                int ix2 = (int) Math.floor(x2 - inset), iy2 = (int) Math.floor(y2 - inset);
                if (ix2 - ix1 >= 16 && iy2 - iy1 >= 16 && (long) (ix2 - ix1) * (iy2 - iy1) >= 1024) {
                    if (!knocked && (a >>> 24) != 0) ctx.fill(ix1, iy1, ix2, iy2, a);
                    band(ctx, e, e.qx1, e.qy1, e.qx2, iy1);
                    band(ctx, e, e.qx1, iy2, e.qx2, e.qy2);
                    band(ctx, e, e.qx1, iy1, ix1, iy2);
                    band(ctx, e, ix2, iy1, e.qx2, iy2);
                    return;
                }
            }
            if (!e.place(ctx)) return;
            ctx.state.addSimpleElement(e);
            submitted++;
        }

        /** One edge band of a split shape: the same shape, its quad cut to (bx1, by1)-(bx2, by2). */
        private static void band(DrawContext ctx, ShapeElement whole, float bx1, float by1, float bx2, float by2) {
            if (!(bx2 > bx1) || !(by2 > by1)) return;
            ShapeElement e = new ShapeElement();
            e.pipeline = whole.pipeline;
            e.pose = whole.pose;
            e.qx1 = bx1; e.qy1 = by1; e.qx2 = bx2; e.qy2 = by2;
            e.cx = whole.cx; e.cy = whole.cy; e.k = whole.k;
            e.colA = whole.colA; e.colB = whole.colB; e.colC = whole.colC;
            System.arraycopy(whole.p, 0, e.p, 0, e.p.length);
            if (!e.place(ctx)) return;
            ctx.state.addSimpleElement(e);
            submitted++;
        }
    }

    // ------------------------------------------------------------------ the GUI element

    private static final Vector2f TMP = new Vector2f();

    /** One shape as recorded for this frame's GUI render: the quad, its pose and the packed shader parameters. */
    static final class ShapeElement implements SimpleGuiElementRenderState {
        RenderPipeline pipeline;
        Matrix3x2f pose;
        float qx1, qy1, qx2, qy2, cx, cy, k;
        int colA, colB, colC;
        final float[] p = new float[16];
        ScreenRect scissor, bounds;

        /** Work out the screen bounds (clipped to the scissor); false when nothing is visible. */
        boolean place(DrawContext ctx) {
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < 4; i++) {
                pose.transformPosition(i < 2 ? qx1 : qx2, (i & 1) == 0 ? qy1 : qy2, TMP);
                minX = Math.min(minX, TMP.x); minY = Math.min(minY, TMP.y);
                maxX = Math.max(maxX, TMP.x); maxY = Math.max(maxY, TMP.y);
            }
            if (!Float.isFinite(minX) || !Float.isFinite(minY) || !Float.isFinite(maxX) || !Float.isFinite(maxY)) return false;
            int x0 = (int) Math.floor(minX), y0 = (int) Math.floor(minY);
            ScreenRect r = new ScreenRect(x0, y0, Math.max(1, (int) Math.ceil(maxX) - x0), Math.max(1, (int) Math.ceil(maxY) - y0));
            scissor = ctx.scissorStack.peekLast();
            if (scissor != null) {
                r = scissor.intersection(r);
                if (r == null) return false;
            }
            bounds = r;
            return true;
        }

        @Override public RenderPipeline pipeline() { return pipeline; }
        @Override public TextureSetup textureSetup() { return TextureSetup.empty(); }
        @Override public ScreenRect scissorArea() { return scissor; }
        @Override public ScreenRect bounds() { return bounds; }

        @Override
        public void setupVertices(VertexConsumer vertices) {
            if (!(vertices instanceof BufferBuilderAccessor b)) return;
            corner(vertices, b, qx1, qy1);
            corner(vertices, b, qx1, qy2);
            corner(vertices, b, qx2, qy2);
            corner(vertices, b, qx2, qy1);
        }

        private void corner(VertexConsumer v, BufferBuilderAccessor b, float x, float y) {
            v.vertex(pose, x, y).color(colA).texture((x - cx) * k, (y - cy) * k);
            put4(b.evade$beginElement(E_A), p[0], p[1], p[2], p[3]);
            put4(b.evade$beginElement(E_R), p[4], p[5], p[6], p[7]);
            put4(b.evade$beginElement(E_G), p[8], p[9], p[10], p[11]);
            put4(b.evade$beginElement(E_L), p[12], p[13], p[14], p[15]);
            putColor(b.evade$beginElement(E_CB), colB);
            putColor(b.evade$beginElement(E_CC), colC);
        }

        private static void put4(long ptr, float a, float b, float c, float d) {
            if (ptr == -1L) return;
            MemoryUtil.memPutFloat(ptr, a);
            MemoryUtil.memPutFloat(ptr + 4, b);
            MemoryUtil.memPutFloat(ptr + 8, c);
            MemoryUtil.memPutFloat(ptr + 12, d);
        }

        private static void putColor(long ptr, int argb) {
            if (ptr == -1L) return;
            MemoryUtil.memPutByte(ptr, (byte) (argb >> 16));
            MemoryUtil.memPutByte(ptr + 1, (byte) (argb >> 8));
            MemoryUtil.memPutByte(ptr + 2, (byte) argb);
            MemoryUtil.memPutByte(ptr + 3, (byte) (argb >>> 24));
        }
    }
}
