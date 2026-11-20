package net.evadeclient.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

/** Render layers with depth testing disabled, so ESP/tracers draw through walls. */
public final class EvadeRenderLayers {
    private EvadeRenderLayers() {}

    private static final RenderPipeline LINES_PIPELINE = RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
            .withLocation(Identifier.of("evade", "pipeline/esp_lines"))
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .build();

    private static final RenderPipeline QUADS_PIPELINE = RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
            .withLocation(Identifier.of("evade", "pipeline/esp_quads"))
            .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withCull(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .build();

    public static final RenderLayer LINES = RenderLayer.of("evade_esp_lines",
            RenderSetup.builder(LINES_PIPELINE).translucent().build());

    public static final RenderLayer QUADS = RenderLayer.of("evade_esp_quads",
            RenderSetup.builder(QUADS_PIPELINE).translucent().build());
}
