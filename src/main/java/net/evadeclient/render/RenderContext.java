package net.evadeclient.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Per-frame world drawing helpers. Coordinates are absolute world coordinates; the camera offset is applied for you.
 *
 * <p>The plain methods draw on the overlay layers ({@link EvadeRenderLayers#LINES}, {@link EvadeRenderLayers#QUADS}),
 * which skip the depth test so the overlay is never hidden by terrain. The {@code *Depth} methods are depth-tested
 * like ordinary world geometry. The world-render pass flushes the two overlay layers after every module has drawn;
 * code that uses a depth layer, or draws from another hook, calls {@link #flush()} when it is done.
 */
public final class RenderContext {
    private final MatrixStack matrices;
    private final VertexConsumerProvider consumers;
    private final Vec3d cam;
    public final float tickDelta;
    /** Seconds since the previous frame (clamped), for frame-rate independent smoothing. */
    public final float frameTime;

    public RenderContext(MatrixStack matrices, VertexConsumerProvider consumers, float tickDelta, float frameTime) {
        this.matrices = matrices;
        this.consumers = consumers;
        this.cam = MinecraftClient.getInstance().gameRenderer.getCamera().getCameraPos();
        this.tickDelta = tickDelta;
        this.frameTime = frameTime;
    }

    public Vec3d cameraPos() { return cam; }

    /** The pass's matrices and buffers, for modules that stream a prebuilt mesh (e.g. Schematics' ghost blocks). */
    public MatrixStack matrices() { return matrices; }
    public VertexConsumerProvider consumers() { return consumers; }

    // ------------------------------------------------------------------ lines

    public void line(Vec3d a, Vec3d b, int color, float width) {
        line(a.x, a.y, a.z, b.x, b.y, b.z, color, width);
    }

    /** A line on the overlay layer, from primitive coordinates (nothing is allocated). */
    public void line(double ax, double ay, double az, double bx, double by, double bz, int color, float width) {
        lineTo(consumers.getBuffer(EvadeRenderLayers.LINES), ax, ay, az, bx, by, bz, color, width);
    }

    /** A depth-tested line: terrain in front of it hides it. */
    public void lineDepth(double ax, double ay, double az, double bx, double by, double bz, int color, float width) {
        lineTo(consumers.getBuffer(EvadeRenderLayers.LINES_DEPTH), ax, ay, az, bx, by, bz, color, width);
    }

    private void lineTo(VertexConsumer vc, double ax, double ay, double az, double bx, double by, double bz,
                        int color, float width) {
        // Subtract in double first: far from the origin a float can't hold the world coordinate itself.
        float x1 = (float) (ax - cam.x), y1 = (float) (ay - cam.y), z1 = (float) (az - cam.z);
        float x2 = (float) (bx - cam.x), y2 = (float) (by - cam.y), z2 = (float) (bz - cam.z);
        float nx = x2 - x1, ny = y2 - y1, nz = z2 - z1;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) return;
        nx /= len; ny /= len; nz /= len;
        MatrixStack.Entry e = matrices.peek();
        vc.vertex(e, x1, y1, z1).color(color).normal(e, nx, ny, nz).lineWidth(width);
        vc.vertex(e, x2, y2, z2).color(color).normal(e, nx, ny, nz).lineWidth(width);
    }

    // ------------------------------------------------------------------ boxes

    public void boxOutline(Box b, int color, float width) {
        boxOutline(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, color, width);
    }

    /** The twelve edges of a box on the overlay layer. */
    public void boxOutline(double x1, double y1, double z1, double x2, double y2, double z2, int color, float width) {
        // bottom
        line(x1, y1, z1, x2, y1, z1, color, width); line(x2, y1, z1, x2, y1, z2, color, width);
        line(x2, y1, z2, x1, y1, z2, color, width); line(x1, y1, z2, x1, y1, z1, color, width);
        // top
        line(x1, y2, z1, x2, y2, z1, color, width); line(x2, y2, z1, x2, y2, z2, color, width);
        line(x2, y2, z2, x1, y2, z2, color, width); line(x1, y2, z2, x1, y2, z1, color, width);
        // verticals
        line(x1, y1, z1, x1, y2, z1, color, width); line(x2, y1, z1, x2, y2, z1, color, width);
        line(x2, y1, z2, x2, y2, z2, color, width); line(x1, y1, z2, x1, y2, z2, color, width);
    }

    /** A filled box on the overlay layer (every face drawn, never hidden by terrain). */
    public void boxFilled(Box b, int color) {
        filled(EvadeRenderLayers.QUADS, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, color);
    }

    /**
     * A filled box that terrain in front of it hides, and whose far faces are culled so the tint is even. Grow the
     * box a little past the surface it covers (e.g. {@code expand(0.002)}) so its faces sit in front of that surface.
     */
    public void boxFilledDepth(Box b, int color) {
        boxFilledDepth(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, color);
    }

    public void boxFilledDepth(double x1, double y1, double z1, double x2, double y2, double z2, int color) {
        filled(EvadeRenderLayers.QUADS_DEPTH, x1, y1, z1, x2, y2, z2, color);
    }

    /** Six faces, each wound counter-clockwise seen from outside the box (front faces for culling). */
    private void filled(RenderLayer layer, double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                        int color) {
        VertexConsumer vc = consumers.getBuffer(layer);
        MatrixStack.Entry e = matrices.peek();
        float x1 = (float) (minX - cam.x), y1 = (float) (minY - cam.y), z1 = (float) (minZ - cam.z);
        float x2 = (float) (maxX - cam.x), y2 = (float) (maxY - cam.y), z2 = (float) (maxZ - cam.z);
        quad(vc, e, color, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2); // bottom
        quad(vc, e, color, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1); // top
        quad(vc, e, color, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1); // north
        quad(vc, e, color, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2); // south
        quad(vc, e, color, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1); // west
        quad(vc, e, color, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2); // east
    }

    /** A flat horizontal rectangle (used for chunk highlights). */
    public void flatQuad(double x1, double z1, double x2, double z2, double y, int color) {
        VertexConsumer vc = consumers.getBuffer(EvadeRenderLayers.QUADS);
        MatrixStack.Entry e = matrices.peek();
        float fy = (float) (y - cam.y);
        float ax = (float) (x1 - cam.x), az = (float) (z1 - cam.z), bx = (float) (x2 - cam.x), bz = (float) (z2 - cam.z);
        quad(vc, e, color, ax, fy, az, ax, fy, bz, bx, fy, bz, bx, fy, az);
    }

    private static void quad(VertexConsumer vc, MatrixStack.Entry e, int color,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4) {
        vc.vertex(e, x1, y1, z1).color(color);
        vc.vertex(e, x2, y2, z2).color(color);
        vc.vertex(e, x3, y3, z3).color(color);
        vc.vertex(e, x4, y4, z4).color(color);
    }

    // ------------------------------------------------------------------ flushing

    /**
     * Draws everything queued on the Evade layers now: fills before lines, depth-tested before overlay, so overlay
     * lines end up on top. Only needed when drawing on a depth layer or outside the world-render pass (which flushes
     * the overlay layers itself); does nothing when the consumers aren't an immediate buffer.
     */
    public void flush() {
        if (!(consumers instanceof VertexConsumerProvider.Immediate immediate)) return;
        immediate.draw(EvadeRenderLayers.QUADS_DEPTH);
        immediate.draw(EvadeRenderLayers.LINES_DEPTH);
        immediate.draw(EvadeRenderLayers.QUADS);
        immediate.draw(EvadeRenderLayers.LINES);
    }
}
