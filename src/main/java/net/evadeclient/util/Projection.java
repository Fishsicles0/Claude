package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Projects world positions onto the 2D GUI, for HUD overlays like Nametags. */
public final class Projection {
    /** Last field of view used for rendering (set by the FOV mixin each frame, so zoom is included). */
    public static float fov = 70f;

    private Projection() {}

    /** Returns {x, y} in scaled GUI pixels, or null if the point is behind the camera. */
    public static double[] toScreen(Vec3d world) {
        MinecraftClient mc = MinecraftClient.getInstance();
        var cam = mc.gameRenderer.getCamera();
        Vec3d rel = world.subtract(cam.getCameraPos());
        Vector3f v = new Vector3f((float) rel.x, (float) rel.y, (float) rel.z);
        cam.getRotation().conjugate(new Quaternionf()).transform(v);
        var window = mc.getWindow();
        float aspect = (float) window.getFramebufferWidth() / Math.max(1, window.getFramebufferHeight());
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(fov), aspect, 0.05f, 4096f);
        Vector4f clip = proj.transform(new Vector4f(v, 1f));
        if (clip.w <= 0.01f) return null;
        double nx = clip.x / clip.w, ny = clip.y / clip.w;
        int w = window.getScaledWidth(), h = window.getScaledHeight();
        return new double[]{(nx + 1) / 2 * w, (1 - ny) / 2 * h};
    }
}
