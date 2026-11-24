package net.evadeclient.api;

import com.google.gson.JsonObject;
import net.evadeclient.render.RenderContext;
import net.evadeclient.util.ColorUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

/**
 * A custom border a server draws ({@code border.set / clear}): a rectangle from (minX, minZ) to (maxX, maxZ), shown
 * like the world border as see-through walls with stripes, which fade in as you come close. Terrain hides it like any
 * other block. Purely visual: it doesn't stop anyone.
 */
public final class ApiBorder {
    /** Walls show within this many blocks of an edge, fully from {@link #FULL} blocks. */
    private static final double SHOW = 48, FULL = 8;
    /** Half the length of wall drawn along an edge around you, and half its height around your eyes. */
    private static final double HALF_SPAN = 48, HALF_HEIGHT = 32;
    private static final int DEFAULT_COLOR = 0xFF29D3F0;

    private static boolean active;
    private static double minX, minZ, maxX, maxZ;
    private static int color;

    private ApiBorder() {}

    static boolean handle(String type, JsonObject o) {
        switch (type) {
            case "border.set" -> {
                double x1 = ApiJson.num(o, "minX", Double.NaN), z1 = ApiJson.num(o, "minZ", Double.NaN);
                double x2 = ApiJson.num(o, "maxX", Double.NaN), z2 = ApiJson.num(o, "maxZ", Double.NaN);
                if (Double.isNaN(x1) || Double.isNaN(z1) || Double.isNaN(x2) || Double.isNaN(z2)) return false;
                double lim = ApiJson.MAX_XZ;
                if (Math.abs(x1) > lim || Math.abs(x2) > lim || Math.abs(z1) > lim || Math.abs(z2) > lim) return false;
                minX = Math.min(x1, x2);
                maxX = Math.max(x1, x2);
                minZ = Math.min(z1, z2);
                maxZ = Math.max(z1, z2);
                if (maxX - minX < 1 || maxZ - minZ < 1) return false;
                color = ApiJson.color(o, "color", DEFAULT_COLOR) | 0xFF000000;
                active = true;
                return true;
            }
            case "border.clear" -> {
                boolean was = active;
                active = false;
                return was;
            }
            default -> { return false; }
        }
    }

    /** Whether a border is set (for tests). */
    public static boolean active() { return active; }

    static void render(RenderContext ctx) {
        if (!active) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        Vec3d cam = ctx.cameraPos();
        double y1 = Math.max(mc.world.getBottomY(), cam.y - HALF_HEIGHT);
        double y2 = Math.min(mc.world.getTopYInclusive() + 1, cam.y + HALF_HEIGHT);
        if (y2 <= y1) return;
        // West and east walls run along Z, north and south along X.
        wall(ctx, true, minX, cam.x, cam.z, minZ, maxZ, y1, y2);
        wall(ctx, true, maxX, cam.x, cam.z, minZ, maxZ, y1, y2);
        wall(ctx, false, minZ, cam.z, cam.x, minX, maxX, y1, y2);
        wall(ctx, false, maxZ, cam.z, cam.x, minX, maxX, y1, y2);
    }

    /**
     * One wall at {@code at} on the X axis ({@code alongZ}) or Z axis, spanning {@code from..to} along the other axis
     * near the camera. {@code camAcross} is the camera's coordinate across the wall, {@code camAlong} along it.
     */
    private static void wall(RenderContext ctx, boolean alongZ, double at, double camAcross, double camAlong,
                             double from, double to, double y1, double y2) {
        double dist = Math.abs(camAcross - at);
        if (dist > SHOW) return;
        double a0 = Math.max(from, camAlong - HALF_SPAN), a1 = Math.min(to, camAlong + HALF_SPAN);
        if (a1 <= a0) return;
        float fade = (float) Math.min(1, Math.max(0, (SHOW - dist) / (SHOW - FULL)));
        if (fade <= 0.01f) return;
        int fill = ColorUtil.withAlpha(color, Math.round(0x38 * fade));
        int stripe = ColorUtil.withAlpha(color, Math.round(0xA0 * fade));
        double t = 0.01;
        if (alongZ) ctx.boxFilledDepth(at - t, y1, a0, at + t, y2, a1, fill);
        else ctx.boxFilledDepth(a0, y1, at - t, a1, y2, at + t, fill);
        // A grid on the wall: vertical lines every two blocks, horizontal ones every four.
        for (double a = Math.ceil(a0 / 2) * 2; a <= a1; a += 2) {
            if (alongZ) ctx.lineDepth(at, y1, a, at, y2, a, stripe, 1.5f);
            else ctx.lineDepth(a, y1, at, a, y2, at, stripe, 1.5f);
        }
        for (double y = Math.ceil(y1 / 4) * 4; y <= y2; y += 4) {
            if (alongZ) ctx.lineDepth(at, y, a0, at, y, a1, ColorUtil.withAlpha(color, Math.round(0x50 * fade)), 1f);
            else ctx.lineDepth(a0, y, at, a1, y, at, ColorUtil.withAlpha(color, Math.round(0x50 * fade)), 1f);
        }
    }

    static void clear() { active = false; }
}
