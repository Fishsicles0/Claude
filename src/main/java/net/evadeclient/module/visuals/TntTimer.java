package net.evadeclient.module.visuals;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Projection;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.TntEntity;

import java.util.Locale;

/** Shows a countdown above every primed TNT, coloured from green to red as it gets close. */
public class TntTimer extends Module {
    public final NumberSetting range = add(new NumberSetting("Range", 48, 8, 128, 4));
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
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof TntEntity tnt) || e.squaredDistanceTo(mc.player) > r2) continue;
            var pos = e.getLerpedPos(tickDelta).add(0, e.getHeight() + 0.5, 0);
            double[] s = Projection.toScreen(pos);
            if (s == null) continue;
            float secs = Math.max(0, tnt.getFuse() - tickDelta) / 20f;
            String text = String.format(Locale.ROOT, "%.1fs", secs);
            float t = Math.min(1f, secs / 4f); // 4s+ green, 0s red
            int r = (int) (255 * (1 - t)), g = (int) (255 * t);
            int color = 0xFF000000 | (r << 16) | (g << 8) | 0x30;
            int w = mc.textRenderer.getWidth(text);
            int x = (int) s[0] - w / 2, y = (int) s[1] - 4;
            ctx.fill(x - 3, y - 2, x + w + 3, y + 10, 0x90000000);
            ctx.drawTextWithShadow(mc.textRenderer, text, x, y, color);
            shown++;
        }
    }
}
