package net.evadeclient.module.hud;

import net.evadeclient.module.HudElementModule;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

/** A horizontal compass strip showing your heading. */
public class CompassHud extends HudElementModule {
    private static final String[] LABELS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

    public CompassHud() {
        super("Direction HUD", "Compass strip with your heading.", 0, 2);
        setEnabled(true);
    }

    @Override
    public int resolveX(int screenW) {
        // Default (x == 0) centres on screen; once dragged it behaves like any element.
        return x == 0 ? (screenW - scaledWidth()) / 2 : super.resolveX(screenW);
    }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        width = 180;
        height = 22;
        var tr = mc.textRenderer;
        if (background.get()) Theme.hudBox(ctx, left, top, width, height);
        float yaw = MathHelper.wrapDegrees(mc.player.getYaw());
        int center = left + width / 2;
        float pxPerDeg = 1.6f;
        ctx.enableScissor(left, top, left + width, top + height);
        for (int deg = -180; deg < 540; deg += 15) {
            float rel = MathHelper.wrapDegrees(deg - yaw);
            int px = Math.round(center + rel * pxPerDeg);
            if (px < left - 10 || px > left + width + 10) continue;
            if (deg % 45 == 0) {
                String l = LABELS[Math.floorMod(deg / 45, 8)];
                int c = l.length() == 1 ? Theme.TEXT : Theme.TEXT_DIM;
                ctx.drawTextWithShadow(tr, l, px - tr.getWidth(l) / 2, top + 4, l.equals("N") ? Theme.RED_BRIGHT : c);
            } else {
                ctx.fill(px, top + 6, px + 1, top + 10, Theme.TEXT_FAINT);
            }
        }
        ctx.disableScissor();
        String heading = String.valueOf(Math.floorMod(Math.round(yaw + 180), 360));
        ctx.fill(center, top + 1, center + 1, top + 4, Theme.RED);
        ctx.drawTextWithShadow(tr, heading, center - tr.getWidth(heading) / 2, top + 13, Theme.TEXT_DIM);
    }
}
