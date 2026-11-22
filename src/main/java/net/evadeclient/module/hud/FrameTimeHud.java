package net.evadeclient.module.hud;

import net.evadeclient.module.HudElementModule;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.FrameStats;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;

import java.util.Locale;

/**
 * Frame time graph: one bar per frame (green within a 60 FPS budget, yellow up to 30 FPS, red beyond),
 * with average FPS, the 1% low and the worst frame over the last ten seconds. Stutters show up as spikes.
 */
public class FrameTimeHud extends HudElementModule {
    public final NumberSetting bars = add(new NumberSetting("Frames Shown", 120, 60, 240, 30));

    public FrameTimeHud() { super("Frame Time Graph", "Live frame times with average FPS, 1% low and worst frame.", 4, 66); }

    @Override
    public void render(DrawContext ctx, int left, int top, boolean preview) {
        var tr = mc.textRenderer;
        int n = bars.getInt();
        float[] s = FrameStats.summary(FrameStats.SIZE);
        String head = preview && FrameStats.count() == 0 ? "144 FPS  1% 98  max 12.4ms"
                : String.format(Locale.ROOT, "%d FPS  1%% %d  max %.1fms", Math.round(s[0]), Math.round(s[1]), s[2]);
        width = Math.max(n + 8, tr.getWidth(head) + 8);
        height = 50;
        if (background.get()) Theme.hudBox(ctx, left, top, width, height);
        ctx.drawTextWithShadow(tr, head, left + 4, top + 3, 0xFFFFFFFF);

        int gx = left + (width - n) / 2, gy = top + 14, gh = 32, base = gy + gh;
        float scale = gh / 50f; // 50 ms fills the graph
        int budget = base - Math.round(16.7f * scale);
        ctx.fill(gx, budget, gx + n, budget + 1, 0x50FFFFFF); // 60 FPS line
        int shown = Math.min(n, FrameStats.count());
        for (int i = 0; i < shown; i++) {
            float ms = FrameStats.recent(i);
            int h = Math.max(1, Math.min(gh, Math.round(ms * scale)));
            int color = ms <= 16.7f ? 0xFF22C55E : ms <= 33.3f ? 0xFFEAB308 : 0xFFE11D2E;
            int x = gx + n - 1 - i;
            ctx.fill(x, base - h, x + 1, base, color);
        }
    }
}
