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

    private final net.evadeclient.gui.kit.Kit kit = new net.evadeclient.gui.kit.Kit();

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        kit.begin(ctx, mouseX, mouseY);
        net.evadeclient.gui.kit.Paint.vgradient(ctx, 0, 0, width, height, 0, net.evadeclient.util.Tokens.N50 | 0xFF000000,
                net.evadeclient.util.Tokens.N0 | 0xFF000000);
        int target = Math.max(player.seekTarget(), player.startTick());
        double p = target <= 0 ? 0 : Math.min(1, player.tick() / (double) target);
        int w = Math.min(300, width - 40), x = (width - w) / 2, y = height / 2 - 30;
        kit.text("Loading replay", x, y, net.evadeclient.util.Tokens.N950, net.evadeclient.util.UiFont.Role.HEADLINE);
        kit.text(kit.ellipsize(player.meta.name, net.evadeclient.util.UiFont.Role.BODY, w), x, y + 26, net.evadeclient.util.Tokens.N700,
                net.evadeclient.util.UiFont.Role.BODY);
        net.evadeclient.gui.kit.Progress.linear(kit, x, y + 46, w, (float) p, player.seeking() ? "Fast-forwarding" : "Reading the recording");
        kit.end();
    }

    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public boolean shouldPause() { return false; }
}
