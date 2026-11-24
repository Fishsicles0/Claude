package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Projects world positions onto the 2D GUI, for HUD overlays like Waypoints and TNT Timer. */
public final class Projection {
    /**
     * Field of view of the last world frame (zoom and FOV Changer included, the hand's FOV never). Kept for code
     * that only needs the angle; {@link #toScreen} uses the full projection matrix of that frame instead.
     */
    public static float fov = 70f;

    /** The world projection of the last frame: FOV, view bobbing, hurt tilt and the nausea warp. */
    private static final Matrix4f WORLD = new Matrix4f();
    private static boolean captured;

    private Projection() {}

    /**
     * Called by GameRendererMixin with the matrix vanilla uploads for the world, once per frame before the world is
     * drawn. Labels drawn on the HUD later in the same frame then sit exactly on their target.
     */
    public static void capture(Matrix4fc worldProjection) {
        WORLD.set(worldProjection);
        captured = true;
    }

    /** Returns {x, y} in scaled GUI pixels, or null if the point is behind the camera. */
    public static double[] toScreen(Vec3d world) {
        MinecraftClient mc = MinecraftClient.getInstance();
        var cam = mc.gameRenderer.getCamera();
        Vec3d rel = world.subtract(cam.getCameraPos());
        Vector3f v = new Vector3f((float) rel.x, (float) rel.y, (float) rel.z);
        cam.getRotation().conjugate(new Quaternionf()).transform(v);
        var window = mc.getWindow();
        Matrix4f proj;
        if (captured) {
            proj = WORLD;
        } else {
            // No world frame drawn yet: a plain perspective is close enough.
            float aspect = (float) window.getFramebufferWidth() / Math.max(1, window.getFramebufferHeight());
            proj = new Matrix4f().perspective((float) Math.toRadians(fov), aspect, 0.05f, 4096f);
        }
        Vector4f clip = proj.transform(new Vector4f(v, 1f));
        if (clip.w <= 0.01f) return null;
        double nx = clip.x / clip.w, ny = clip.y / clip.w;
        int w = window.getScaledWidth(), h = window.getScaledHeight();
        return new double[]{(nx + 1) / 2 * w, (1 - ny) / 2 * h};
    }
}
