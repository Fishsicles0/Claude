package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Vec3d;

/** Draws a small dark label on the HUD at a world position (used by Waypoints). */
public final class Label {
    private Label() {}

    public static void draw(DrawContext ctx, Vec3d world, String text, int color) {
        draw(ctx, world, text, color, 1f);
    }

    /** As {@link #draw(DrawContext, Vec3d, String, int)}, {@code scale} times the size, grown about its anchor. */
    public static void draw(DrawContext ctx, Vec3d world, String text, int color, float scale) {
        double[] s = Projection.toScreen(world);
        if (s == null) return;
        drawAt(ctx, s[0], s[1], text, color, scale);
    }

    /**
     * The label centred on a screen position (scaled GUI pixels) the caller has already projected, so a position
     * that is also tested for being on screen is projected once. {@code color} is the underline; the text is white
     * (section codes in {@code text} colour parts of it).
     */
    public static void drawAt(DrawContext ctx, double sx, double sy, String text, int color, float scale) {
        var tr = MinecraftClient.getInstance().textRenderer;
        int ax = (int) sx, ay = (int) sy;
        if (!(scale > 0f) || Math.abs(scale - 1f) < 0.001f) {
            box(ctx, tr, ax, ay, text, color);
            return;
        }
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            m.translate(ax, ay);
            m.scale(scale, scale);
            box(ctx, tr, 0, 0, text, color);
        } finally {
            m.popMatrix();
        }
    }

    private static void box(DrawContext ctx, TextRenderer tr, int cx, int cy, String text, int color) {
        int w = tr.getWidth(text);
        int x = cx - w / 2, y = cy - 5;
        ctx.fill(x - 3, y - 2, x + w + 3, y + 10, 0xA0000000);
        ctx.fill(x - 3, y + 10, x + w + 3, y + 11, color);
        ctx.drawTextWithShadow(tr, text, x, y, 0xFFFFFFFF);
    }
}
