package net.evadeclient.module.visuals;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.gui.DrawContext;

/**
 * Fades a coloured vignette in around the screen edges as your health drops, so low health is hard to miss.
 * Drawn by its own HUD layer just under the hotbar (see EvadeClient), so hearts and hotbar stay readable.
 */
public class DamageTint extends Module {
    public final NumberSetting threshold = add(new NumberSetting("Hearts", 5, 1, 10, 1));
    public final NumberSetting strength = add(new NumberSetting("Strength %", 55, 10, 100, 5));
    public final ColorSetting color = add(new ColorSetting("Color", 0xFFE11D2E));

    /** Side bands are drawn in this many steps; each step is a solid band, so the fade has no gaps. */
    private static final int STEPS = 12;

    public DamageTint() {
        super("Damage Tint", "A red screen vignette when your health gets low.", Category.VISUALS);
    }

    /** Draws the vignette for the current health (nothing above the threshold). */
    public void draw(DrawContext ctx) {
        if (mc.player == null || mc.player.isDead() || mc.player.isSpectator()) return;
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
        // top and bottom: vertical gradients across the full width
        ctx.fillGradient(0, 0, w, by, edge, clear);
        ctx.fillGradient(0, h - by, w, h, clear, edge);
        // left and right: contiguous bands stepping down in alpha towards the centre
        for (int i = 0; i < STEPS; i++) {
            int a = (int) (maxA * (1f - i / (float) STEPS));
            if (a <= 0) continue;
            int col = (a << 24) | rgb;
            int x0 = i * bx / STEPS, x1 = (i + 1) * bx / STEPS;
            if (x1 <= x0) continue;
            ctx.fill(x0, by, x1, h - by, col);
            ctx.fill(w - x1, by, w - x0, h - by, col);
        }
    }
}
