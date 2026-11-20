package net.evadeclient.hud;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.HudEditorScreen;
import net.evadeclient.module.Category;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.Module;
import net.evadeclient.module.client.HudModule;
import net.evadeclient.util.ClickTracker;
import net.evadeclient.util.ColorUtil;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.Comparator;
import java.util.List;

public final class Hud {
    private Hud() {}

    public static void render(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClickTracker.update();
        if (mc.player == null || mc.getDebugHud().shouldShowDebugHud()) return;
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();

        // The HUD editor draws the elements itself (with drag handles).
        if (!(mc.currentScreen instanceof HudEditorScreen)) {
            for (HudElementModule el : EvadeClient.modules.hudElements()) {
                if (el.isEnabled()) el.renderScaled(ctx, sw, sh, false);
            }
        }
        var tags = EvadeClient.modules.get(net.evadeclient.module.visuals.Nametags.class);
        if (tags != null && tags.isEnabled() && !mc.options.hudHidden) tags.draw(ctx, mc.getRenderTickCounter().getTickProgress(true));
        Notifications.render(ctx);

        HudModule hud = EvadeClient.modules.get(HudModule.class);
        if (hud == null || !hud.isEnabled()) return;
        TextRenderer tr = mc.textRenderer;

        if (hud.watermark.get()) {
            int mark = 17;
            net.evadeclient.util.Logo.lockup(ctx, sw - 4 - net.evadeclient.util.Logo.lockupWidth(mark), 3, mark);
        }

        if (hud.arrayList.get()) {
            List<Module> active = EvadeClient.modules.all().stream()
                    .filter(m -> m.isEnabled() && m.isVisible())
                    .filter(m -> !hud.cheatsOnly.get() || m.getCategory() == Category.COMBAT || m.getCategory() == Category.VISUALS)
                    .sorted(Comparator.comparingInt((Module m) -> tr.getWidth(label(m))).reversed())
                    .toList();
            int y = hud.watermark.get() ? 18 : 2;
            for (int i = 0; i < active.size(); i++) {
                String text = label(active.get(i));
                int w = tr.getWidth(text);
                int x = sw - w - 4;
                int color = switch (hud.colorMode.get()) {
                    case "Rainbow" -> ColorUtil.rainbow(i * 150L, 0.6f, 1f);
                    case "Static Red" -> Theme.RED;
                    case "White" -> 0xFFFFFFFF;
                    default -> redWave(i);
                };
                ctx.fill(x - 3, y - 1, sw, y + tr.fontHeight, 0x90000000);
                ctx.fill(sw - 1, y - 1, sw, y + tr.fontHeight, color);
                ctx.drawTextWithShadow(tr, text, x, y, color);
                y += tr.fontHeight + 1;
            }
        }
    }

    /** Red that pulses between bright and deep red down the list. */
    private static int redWave(int index) {
        double t = (Math.sin((System.currentTimeMillis() / 600.0) - index * 0.45) + 1) / 2;
        return Theme.lerpColor(0xFF8E0F1C, 0xFFFF3B4C, (float) t);
    }

    private static String label(Module m) {
        String suffix = m.getSuffix();
        return suffix == null ? m.getName() : m.getName() + " \u00a77" + suffix;
    }
}
