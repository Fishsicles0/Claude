package net.evadeclient.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.LayeringTransform;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

/**
 * Overlay layers with depth testing disabled, for chunk borders, waypoints and block outlines, so the overlay is
 * never hidden by terrain. The {@code *_DEPTH} twins are depth-tested like ordinary world geometry (terrain in
 * front hides them) and never write depth, so they can't hide anything drawn after them either.
 */
public final class EvadeRenderLayers {
    private EvadeRenderLayers() {}

    private static final RenderPipeline LINES_PIPELINE = RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
            .withLocation(Identifier.of("evade", "pipeline/overlay_lines"))
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .build();

    private static final RenderPipeline QUADS_PIPELINE = RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
            .withLocation(Identifier.of("evade", "pipeline/overlay_quads"))
            .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withCull(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .build();

    private static final RenderPipeline LINES_DEPTH_PIPELINE = RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
            .withLocation(Identifier.of("evade", "pipeline/overlay_lines_depth"))
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(false)
            .build();

    /** Culled: only the faces turned towards the camera are drawn, so a translucent box isn't tinted twice. */
    private static final RenderPipeline QUADS_DEPTH_PIPELINE = RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
            .withLocation(Identifier.of("evade", "pipeline/overlay_quads_depth"))
            .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withCull(true)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withDepthWrite(false)
            .build();

    public static final RenderLayer LINES = RenderLayer.of("evade_overlay_lines",
            RenderSetup.builder(LINES_PIPELINE).translucent().build());

    public static final RenderLayer QUADS = RenderLayer.of("evade_overlay_quads",
            RenderSetup.builder(QUADS_PIPELINE).translucent().build());

    public static final RenderLayer LINES_DEPTH = RenderLayer.of("evade_overlay_lines_depth",
            RenderSetup.builder(LINES_DEPTH_PIPELINE).translucent().build());

    /** Nudged a hair towards the camera (like vanilla's debug boxes) so a fill flush with a block face never flickers. */
    public static final RenderLayer QUADS_DEPTH = RenderLayer.of("evade_overlay_quads_depth",
            RenderSetup.builder(QUADS_DEPTH_PIPELINE).translucent()
                    .layeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING).build());
}
