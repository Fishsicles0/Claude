package net.evadeclient.map;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.joml.Vector2f;

/**
 * GUI quads at fractional positions for the World Map: a textured rectangle (a region of map) and plain
 * four-point polygons (the position arrow). Each call is one GUI element, clipped to the current scissor.
 */
public final class MapDraw {
    private static final Vector2f P = new Vector2f();

    private MapDraw() {}

    /** {@code tex} stretched over (x1, y1)-(x2, y2), sampled nearest-neighbour. */
    public static void texture(DrawContext ctx, GpuTextureView tex, float x1, float y1, float x2, float y2, int color) {
        if (tex == null) return;
        float[] v = {x1, y1, 0, 0, x1, y2, 0, 1, x2, y2, 1, 1, x2, y1, 1, 0};
        TextureSetup setup = TextureSetup.of(tex, RenderSystem.getSamplerCache().get(FilterMode.NEAREST));
        submit(ctx, RenderPipelines.GUI_TEXTURED, setup, v, true, color);
    }

    /** A filled four-point polygon (corners in order, either winding). */
    public static void quad(DrawContext ctx, float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy, int color) {
        float area = (ax * by - bx * ay) + (bx * cy - cx * by) + (cx * dy - dx * cy) + (dx * ay - ax * dy);
        float[] v = area > 0
                ? new float[]{ax, ay, 0, 0, dx, dy, 0, 0, cx, cy, 0, 0, bx, by, 0, 0}
                : new float[]{ax, ay, 0, 0, bx, by, 0, 0, cx, cy, 0, 0, dx, dy, 0, 0};
        submit(ctx, RenderPipelines.GUI, TextureSetup.empty(), v, false, color);
    }

    private static void submit(DrawContext ctx, RenderPipeline pipeline, TextureSetup setup, float[] v, boolean textured, int color) {
        if ((color >>> 24) == 0) return;
        Matrix3x2f pose = new Matrix3x2f(ctx.getMatrices());
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < v.length; i += 4) {
            pose.transformPosition(v[i], v[i + 1], P);
            minX = Math.min(minX, P.x); minY = Math.min(minY, P.y);
            maxX = Math.max(maxX, P.x); maxY = Math.max(maxY, P.y);
        }
        int x0 = (int) Math.floor(minX), y0 = (int) Math.floor(minY);
        ScreenRect bounds = new ScreenRect(x0, y0, Math.max(1, (int) Math.ceil(maxX) - x0), Math.max(1, (int) Math.ceil(maxY) - y0));
        ScreenRect scissor = ctx.scissorStack.peekLast();
        if (scissor != null) {
            bounds = scissor.intersection(bounds);
            if (bounds == null) return;
        }
        ctx.state.addSimpleElement(new Quad(pipeline, setup, pose, v, textured, color, scissor, bounds));
    }

    private record Quad(RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2fc pose, float[] v, boolean textured,
                        int color, ScreenRect scissorArea, ScreenRect bounds) implements SimpleGuiElementRenderState {
        @Override
        public void setupVertices(VertexConsumer vertices) {
            for (int i = 0; i + 3 < v.length; i += 4) {
                if (textured) vertices.vertex(pose, v[i], v[i + 1]).texture(v[i + 2], v[i + 3]).color(color);
                else vertices.vertex(pose, v[i], v[i + 1]).color(color);
            }
        }
    }
}
