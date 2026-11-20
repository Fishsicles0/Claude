package net.evadeclient.hud;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Module;
import net.evadeclient.module.client.HudModule;
import net.evadeclient.util.ColorUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.Comparator;
import java.util.List;

public final class Hud {
    private Hud() {}

    public static void render(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        HudModule hud = EvadeClient.modules.get(HudModule.class);
        if (hud == null || !hud.isEnabled() || mc.player == null || mc.getDebugHud().shouldShowDebugHud()) return;

        TextRenderer tr = mc.textRenderer;
        int screenW = ctx.getScaledWindowWidth();
        int screenH = ctx.getScaledWindowHeight();

        if (hud.watermark.get()) {
            String first = EvadeClient.NAME.substring(0, 1);
            String rest = EvadeClient.NAME.substring(1) + " §7" + EvadeClient.VERSION;
            int color = hud.rainbow.get() ? ColorUtil.rainbow(0, 0.6f, 1f) : ColorUtil.ACCENT;
            ctx.drawTextWithShadow(tr, first, 4, 4, color);
            ctx.drawTextWithShadow(tr, rest, 4 + tr.getWidth(first), 4, ColorUtil.TEXT);
        }

        if (hud.arrayList.get()) {
            List<Module> active = EvadeClient.modules.all().stream()
                    .filter(m -> m.isEnabled() && m.isVisible())
                    .sorted(Comparator.comparingInt((Module m) -> tr.getWidth(label(m))).reversed())
                    .toList();
            int y = 2;
            for (int i = 0; i < active.size(); i++) {
                String text = label(active.get(i));
                int w = tr.getWidth(text);
                int x = screenW - w - 4;
                int color = hud.rainbow.get() ? ColorUtil.rainbow(i * 150L, 0.6f, 1f) : ColorUtil.ACCENT;
                ctx.fill(x - 2, y - 1, screenW, y + tr.fontHeight, 0x90000000);
                ctx.fill(screenW - 1, y - 1, screenW, y + tr.fontHeight, color);
                ctx.drawTextWithShadow(tr, text, x, y, color);
                y += tr.fontHeight + 1;
            }
        }

        int bottom = screenH - tr.fontHeight - 2;
        if (mc.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen) bottom -= 14;
        if (hud.coords.get()) {
            String c = String.format("§7XYZ §f%.1f %.1f %.1f", mc.player.getX(), mc.player.getY(), mc.player.getZ());
            ctx.drawTextWithShadow(tr, c, 4, bottom, ColorUtil.TEXT);
            bottom -= tr.fontHeight + 1;
        }
        if (hud.fps.get()) {
            ctx.drawTextWithShadow(tr, "§7FPS §f" + mc.getCurrentFps(), 4, bottom, ColorUtil.TEXT);
        }
    }

    private static String label(Module m) {
        String suffix = m.getSuffix();
        return suffix == null ? m.getName() : m.getName() + " §7" + suffix;
    }
}
