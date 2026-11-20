package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class RotationUtil {
    private RotationUtil() {}

    /** {yaw, pitch} needed to look from {@code from} at {@code to}. */
    public static float[] rotationsTo(Vec3d from, Vec3d to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, h));
        return new float[]{MathHelper.wrapDegrees(yaw), MathHelper.clamp(pitch, -90f, 90f)};
    }

    /** Angle in degrees between the player's view and the direction to {@code point}. */
    public static double angleTo(Vec3d eye, Vec3d look, Vec3d point) {
        Vec3d dir = point.subtract(eye).normalize();
        double dot = MathHelper.clamp(look.dotProduct(dir), -1, 1);
        return Math.toDegrees(Math.acos(dot));
    }

    /**
     * The point of {@code box} closest to the player's line of sight. Aiming here moves the crosshair
     * the minimum amount needed to land on the hitbox, which looks far more natural than snapping to the centre.
     */
    public static Vec3d closestPointToRay(Box box, Vec3d eye, Vec3d look) {
        Vec3d center = box.getCenter();
        double t = Math.max(0, center.subtract(eye).dotProduct(look));
        Vec3d onRay = eye.add(look.multiply(t));
        return new Vec3d(
                MathHelper.clamp(onRay.x, box.minX, box.maxX),
                MathHelper.clamp(onRay.y, box.minY, box.maxY),
                MathHelper.clamp(onRay.z, box.minZ, box.maxZ));
    }

    /**
     * Snap a rotation delta to what a real mouse could produce at the current sensitivity
     * (the same quantisation vanilla applies to mouse input).
     */
    public static double gcd(double delta) {
        double sens = MinecraftClient.getInstance().options.getMouseSensitivity().getValue();
        double f = sens * 0.6 + 0.2;
        double step = f * f * f * 8.0 * 0.15;
        return Math.round(delta / step) * step;
    }
}
