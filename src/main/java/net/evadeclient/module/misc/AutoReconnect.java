package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

/** Reconnects to the last server after you get disconnected, with a countdown on the disconnect screen. */
public class AutoReconnect extends Module {
    private final NumberSetting delay = add(new NumberSetting("Delay (s)", 5, 1, 60, 1));
    private static ServerInfo last;
    private static long disconnectedAt = -1;

    public AutoReconnect() {
        super("AutoReconnect", "Rejoins the server after you get disconnected.", Category.SERVER);
    }

    @Override
    protected boolean alwaysRunHooks() { return true; }

    /** Runs every client tick, in or out of a world (registered in EvadeClient). */
    public static void clientTick(MinecraftClient mc) {
        if (mc.player != null && mc.getCurrentServerEntry() != null && !mc.isInSingleplayer()) last = mc.getCurrentServerEntry();
        AutoReconnect ar = EvadeClient.modules == null ? null : EvadeClient.modules.get(AutoReconnect.class);
        if (!(mc.currentScreen instanceof DisconnectedScreen)) { disconnectedAt = -1; return; }
        if (ar == null || !ar.isEnabled() || last == null) return;
        if (disconnectedAt < 0) disconnectedAt = System.currentTimeMillis();
        if (System.currentTimeMillis() - disconnectedAt >= ar.delay.get() * 1000) {
            disconnectedAt = -1;
            ServerInfo info = last;
            ConnectScreen.connect(new MultiplayerScreen(new TitleScreen()), mc, ServerAddress.parse(info.address), info, false, null);
        }
    }

    /** Countdown text drawn on the disconnect screen. */
    public static void renderOverlay(DrawContext ctx, int width) {
        AutoReconnect ar = EvadeClient.modules == null ? null : EvadeClient.modules.get(AutoReconnect.class);
        if (ar == null || !ar.isEnabled() || last == null || disconnectedAt < 0) return;
        double left = Math.max(0, ar.delay.get() - (System.currentTimeMillis() - disconnectedAt) / 1000.0);
        String s = "Reconnecting to " + last.address + " in " + String.format("%.1f", left) + "s";
        var tr = MinecraftClient.getInstance().textRenderer;
        ctx.drawTextWithShadow(tr, s, (width - tr.getWidth(s)) / 2, 12, 0xFFFF3B4C);
    }
}
