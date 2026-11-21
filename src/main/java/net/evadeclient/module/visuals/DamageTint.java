package net.evadeclient.module.visuals;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.gui.DrawContext;

/** Fades a coloured vignette in around the screen edges as your health drops, so low health is hard to miss. */
public class DamageTint extends Module {
    public final NumberSetting threshold = add(new NumberSetting("Hearts", 5, 1, 10, 1));
    public final NumberSetting strength = add(new NumberSetting("Strength %", 55, 10, 100, 5));
    public final ColorSetting color = add(new ColorSetting("Color", 0xFFE11D2E));

    public DamageTint() {
        super("Damage Tint", "A red screen vignette when your health gets low.", Category.VISUALS);
    }

    @Override
    public void onHud(DrawContext ctx, float tickDelta) {
        if (mc.player == null || mc.player.isDead()) return;
        float hp = mc.player.getHealth();
        float limit = threshold.get().floatValue() * 2f; // hearts -> half-hearts
        if (hp >= limit || limit <= 0) return;
        float intensity = (limit - hp) / limit; // 0 at threshold, 1 at empty
        int maxA = (int) (255 * (strength.get() / 100.0) * intensity);
        if (maxA <= 2) return;

        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        int rgb = color.get() & 0xFFFFFF;
        int edge = (maxA << 24) | rgb;
        int clear = rgb; // alpha 0
        int bx = Math.max(24, w / 6), by = Math.max(18, h / 6);
        // top, bottom (vertical gradients), then left, right (via thin steps)
        ctx.fillGradient(0, 0, w, by, edge, clear);
        ctx.fillGradient(0, h - by, w, h, clear, edge);
        int steps = 12;
        for (int i = 0; i < steps; i++) {
            int a = (int) (maxA * (1f - i / (float) steps));
            int col = (a << 24) | rgb;
            int x = i * bx / steps;
            ctx.fill(x, by, x + 1, h - by, col);
            ctx.fill(w - x - 1, by, w - x, h - by, col);
        }
    }
}
