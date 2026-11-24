package net.evadeclient.util;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.evadeclient.EvadeClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

/**
 * How Render Scale brings the smaller world image up to the window. Picks the pipeline and the sampler filter for
 * {@link RenderScale#endWorld}; falls back to a plain filtered copy if a shader fails to compile on this driver.
 *
 * <ul>
 *   <li>{@link Mode#EVADE}: Evade's own edge-aware upscaler ({@code evade:core/evade_upscale}): a 12-tap Lanczos-like
 *       filter stretched along edges and squeezed across them, de-ringed, with a contrast-adaptive sharpen. It reads
 *       exact texels, so the sampler filter doesn't matter. The best-looking choice at 50 to 80%.</li>
 *   <li>{@link Mode#SMOOTH}: bilinear, with the light 5-tap sharpen when Sharpen is on.</li>
 *   <li>{@link Mode#SHARP}: nearest neighbour (blocky, pixel-exact).</li>
 * </ul>
 */
public final class Upscaler {
    private Upscaler() {}

    public enum Mode {
        SMOOTH("Smooth"), SHARP("Sharp"), EVADE("Evade (best)");

        public final String label;

        Mode(String label) { this.label = label; }

        /** The mode for an Upscale setting value; Smooth for anything unknown. */
        public static Mode of(String label) {
            for (Mode m : values()) if (m.label.equalsIgnoreCase(label)) return m;
            return SMOOTH;
        }
    }

    /** The setting values, in menu order. */
    public static final String[] LABELS = {Mode.EVADE.label, Mode.SMOOTH.label, Mode.SHARP.label};

    /** Sharpening strength of the Evade upscaler with Sharpen on, in percent. */
    static final int EVADE_SHARPEN_PCT = 55;

    private static RenderPipeline evade(boolean sharpen) {
        return RenderPipeline.builder()
                .withLocation(Identifier.of("evade", sharpen ? "pipeline/evade_upscale_sharp" : "pipeline/evade_upscale"))
                .withVertexShader("core/screenquad")
                .withFragmentShader(Identifier.of("evade", "core/evade_upscale"))
                .withShaderDefine("SHARPEN_PCT", sharpen ? EVADE_SHARPEN_PCT : 0)
                .withSampler("InSampler")
                .withDepthWrite(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
                .build();
    }

    private static final RenderPipeline EVADE_SHARP = evade(true), EVADE_SOFT = evade(false);
    /** Light contrast-adaptive sharpen over a bilinear upscale (Smooth + Sharpen). */
    private static final RenderPipeline SMOOTH_SHARPEN = RenderPipeline.builder()
            .withLocation(Identifier.of("evade", "pipeline/render_scale_sharpen"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.of("evade", "core/render_scale_sharpen"))
            .withSampler("InSampler")
            .withDepthWrite(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .build();
    /** A plain full-screen copy (vanilla's own blit pipeline: no blending, writes colour and alpha). */
    private static final RenderPipeline PLAIN = RenderPipelines.TRACY_BLIT;

    private static boolean evadeBroken, sharpenBroken;
    /** The pipeline used for the last upscale (tests and the status line). */
    private static String lastUsed = "";

    /** The pipeline for {@code mode}: a shader that failed to compile once is never tried again this session. */
    static RenderPipeline pipeline(GpuDevice device, Mode mode, boolean sharpen) {
        if (mode == Mode.EVADE && !evadeBroken) {
            RenderPipeline p = sharpen ? EVADE_SHARP : EVADE_SOFT;
            // Cached after the first frame (one map lookup); invalid only if the shader failed to compile.
            if (device.precompilePipeline(p).isValid()) { lastUsed = "evade"; return p; }
            evadeBroken = true;
            EvadeClient.LOG.warn("Render Scale: the Evade upscale shader didn't compile, using the smooth upscale");
        }
        if (mode != Mode.SHARP && sharpen && !sharpenBroken) {
            if (device.precompilePipeline(SMOOTH_SHARPEN).isValid()) { lastUsed = "sharpen"; return SMOOTH_SHARPEN; }
            sharpenBroken = true;
            EvadeClient.LOG.warn("Render Scale: the sharpen shader didn't compile, scaling without it");
        }
        lastUsed = mode == Mode.SHARP ? "nearest" : "bilinear";
        return PLAIN;
    }

    /** The sampler filter for the source image (the Evade shader reads exact texels, so either works there). */
    static FilterMode filter(Mode mode) {
        return mode == Mode.SHARP ? FilterMode.NEAREST : FilterMode.LINEAR;
    }

    /** "evade", "sharpen", "bilinear" or "nearest": what drew the last scaled frame ("" before the first). */
    public static String lastUsed() { return lastUsed; }

    /** Whether the Evade upscaler is usable (it compiled, or hasn't been tried yet). */
    public static boolean evadeAvailable() { return !evadeBroken; }
}
