package net.evadeclient.module.visuals;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Projection;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.vehicle.TntMinecartEntity;

import java.util.Locale;

/**
 * Shows a countdown above every primed TNT (and primed TNT minecart), coloured from green to red as it gets close.
 * A cart's fuse is only known roughly on the client, so its time is shown as an estimate ("~3.2s").
 */
public class TntTimer extends Module {
    public final NumberSetting range = add(new NumberSetting("Range", 48, 8, 128, 4));
    public final BooleanSetting minecarts = add(new BooleanSetting("Minecarts", true)
            .describe("Also time primed TNT minecarts (an estimate, shown with ~)."));
    public final BooleanSetting showTicks = add(new BooleanSetting("Show Ticks", false)
            .describe("Count down in game ticks (20 per second) instead of seconds."));
    public final NumberSetting scale = add(new NumberSetting("Scale", 1, 0.5, 2, 0.1));
    private int shown;

    public TntTimer() {
        super("TNT Timer", "Shows a countdown above primed TNT.", Category.VISUALS);
        setEnabled(true);
    }

    /** How many timers were drawn last frame (for tests). */
    public int shown() { return shown; }

    @Override
    public void onHud(DrawContext ctx, float tickDelta) {
        shown = 0;
        if (mc.world == null || mc.player == null) return;
        double r2 = range.get() * range.get();
        float s = scale.getFloat();
        for (Entity e : mc.world.getEntities()) {
            int fuse;
            boolean estimate;
            if (e instanceof TntEntity tnt) {
                fuse = tnt.getFuse();
                estimate = false;
            } else if (e instanceof TntMinecartEntity cart && minecarts.get() && cart.isPrimed()) {
                fuse = cart.getFuseTicks();
                estimate = true;
            } else {
                continue;
            }
            if (e.squaredDistanceTo(mc.player) > r2) continue;
            var pos = e.getLerpedPos(tickDelta).add(0, e.getHeight() + 0.5, 0);
            double[] p = Projection.toScreen(pos);
            if (p == null) continue;
            float ticks = Math.max(0, fuse - tickDelta);
            float secs = ticks / 20f;
            String text = (estimate ? "~" : "") + (showTicks.get()
                    ? (int) Math.ceil(ticks) + "t"
                    : String.format(Locale.ROOT, "%.1fs", secs));
            float t = Math.min(1f, secs / 4f); // 4s+ green, 0s red
            int r = (int) (255 * (1 - t)), g = (int) (255 * t);
            int color = 0xFF000000 | (r << 16) | (g << 8) | 0x30;
            int w = mc.textRenderer.getWidth(text);
            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate((int) p[0], (int) p[1]); // whole pixels keep the text crisp at scale 1
            ctx.getMatrices().scale(s, s);
            int x = -w / 2, y = -4;
            ctx.fill(x - 3, y - 2, x + w + 3, y + 10, 0x90000000);
            ctx.drawTextWithShadow(mc.textRenderer, text, x, y, color);
            ctx.getMatrices().popMatrix();
            shown++;
        }
    }
}
