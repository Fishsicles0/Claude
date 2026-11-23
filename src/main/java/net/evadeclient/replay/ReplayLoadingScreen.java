package net.evadeclient.replay;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Shown while a replay loads or jumps (fast-forwarding through the recording). */
public final class ReplayLoadingScreen extends Screen {
    private final ReplayPlayer player;

    public ReplayLoadingScreen(ReplayPlayer player) {
        super(Text.literal("Loading replay"));
        this.player = player;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xFF0B0B0F);
        int target = Math.max(player.seekTarget(), player.startTick());
        double p = target <= 0 ? 0 : Math.min(1, player.tick() / (double) target);
        String title = "Loading replay";
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 24, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer, player.meta.name, width / 2, height / 2 - 10, 0xFF9A9AA6);
        int w = Math.min(260, width - 40), x = (width - w) / 2, y = height / 2 + 8;
        ctx.fill(x, y, x + w, y + 4, 0xFF2A2A33);
        ctx.fill(x, y, x + (int) (w * p), y + 4, 0xFFE5484D);
    }

    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public boolean shouldPause() { return false; }
}
