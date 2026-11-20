package net.evadeclient.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Per-frame world drawing helpers. Everything drawn here ignores depth, so it shows through walls.
 * Coordinates are absolute world coordinates; the camera offset is applied for you.
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

    public void line(Vec3d a, Vec3d b, int color, float width) {
        VertexConsumer vc = consumers.getBuffer(EvadeRenderLayers.LINES);
        MatrixStack.Entry e = matrices.peek();
        float ax = (float) (a.x - cam.x), ay = (float) (a.y - cam.y), az = (float) (a.z - cam.z);
        float bx = (float) (b.x - cam.x), by = (float) (b.y - cam.y), bz = (float) (b.z - cam.z);
        float nx = bx - ax, ny = by - ay, nz = bz - az;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) return;
        nx /= len; ny /= len; nz /= len;
        vc.vertex(e, ax, ay, az).color(color).normal(e, nx, ny, nz).lineWidth(width);
        vc.vertex(e, bx, by, bz).color(color).normal(e, nx, ny, nz).lineWidth(width);
    }

    public void boxOutline(Box b, int color, float width) {
        Vec3d[] c = corners(b);
        // bottom
        line(c[0], c[1], color, width); line(c[1], c[2], color, width);
        line(c[2], c[3], color, width); line(c[3], c[0], color, width);
        // top
        line(c[4], c[5], color, width); line(c[5], c[6], color, width);
        line(c[6], c[7], color, width); line(c[7], c[4], color, width);
        // verticals
        for (int i = 0; i < 4; i++) line(c[i], c[i + 4], color, width);
    }

    public void boxFilled(Box b, int color) {
        VertexConsumer vc = consumers.getBuffer(EvadeRenderLayers.QUADS);
        MatrixStack.Entry e = matrices.peek();
        float x1 = (float) (b.minX - cam.x), y1 = (float) (b.minY - cam.y), z1 = (float) (b.minZ - cam.z);
        float x2 = (float) (b.maxX - cam.x), y2 = (float) (b.maxY - cam.y), z2 = (float) (b.maxZ - cam.z);
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

    private static Vec3d[] corners(Box b) {
        return new Vec3d[]{
                new Vec3d(b.minX, b.minY, b.minZ), new Vec3d(b.maxX, b.minY, b.minZ),
                new Vec3d(b.maxX, b.minY, b.maxZ), new Vec3d(b.minX, b.minY, b.maxZ),
                new Vec3d(b.minX, b.maxY, b.minZ), new Vec3d(b.maxX, b.maxY, b.minZ),
                new Vec3d(b.maxX, b.maxY, b.maxZ), new Vec3d(b.minX, b.maxY, b.maxZ),
        };
    }

    /** Point just in front of the camera along the view direction, for tracer origins. */
    public Vec3d crosshairPoint() {
        var camera = MinecraftClient.getInstance().gameRenderer.getCamera();
        return cam.add(Vec3d.fromPolar(camera.getPitch(), camera.getYaw()));
    }
}
