package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Vec3d;

/** Draws a small dark label on the HUD at a world position (used by Waypoints). */
public final class Label {
    private Label() {}

    public static void draw(DrawContext ctx, Vec3d world, String text, int color) {
        double[] s = Projection.toScreen(world);
        if (s == null) return;
        var tr = MinecraftClient.getInstance().textRenderer;
        int w = tr.getWidth(text);
        int x = (int) s[0] - w / 2, y = (int) s[1] - 5;
        ctx.fill(x - 3, y - 2, x + w + 3, y + 10, 0xA0000000);
        ctx.fill(x - 3, y + 10, x + w + 3, y + 11, color);
        ctx.drawTextWithShadow(tr, text, x, y, 0xFFFFFFFF);
    }
}
