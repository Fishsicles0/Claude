package net.evadeclient.module.misc;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.network.ServerInfo;

import java.util.Locale;

/**
 * Runs Hypixel's "/tip all" on a timer while you play there. Does nothing on any other server or in singleplayer,
 * and the first tip waits a full interval after you join, so it never fires the moment you connect.
 */
public class AutoTip extends Module {
    public final NumberSetting minutes = add(new NumberSetting("Every (min)", 15, 1, 60, 1));
    /** When the last tip was sent; 0 means the timer hasn't started for this connection yet. */
    private long lastSent;
    /** The connection the timer belongs to (survives Hypixel's lobby and game switches, not a reconnect). */
    private Object connection;

    public AutoTip() {
        super("AutoTip", "Tips everyone on a timer with /tip all. Hypixel only.", Category.SERVER);
    }

    @Override
    protected void onEnable() { lastSent = 0; } // the timer starts on the next tick, so toggling never tips at once

    @Override
    public void onTick() {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        Object conn = mc.getNetworkHandler().getConnection();
        if (conn != connection) { connection = conn; lastSent = 0; }
        if (!onHypixel(mc.isInSingleplayer() ? null : mc.getCurrentServerEntry())) { lastSent = 0; return; }
        long now = System.currentTimeMillis();
        if (lastSent == 0) { lastSent = now; return; } // start the timer: the first tip waits a whole interval
        long interval = (long) (minutes.get() * 60_000);
        if (now - lastSent < interval) return;
        lastSent = now;
        mc.player.networkHandler.sendChatCommand("tip all");
    }

    /** Whether {@code server} is Hypixel: hypixel.net or any subdomain of it, with or without a port. */
    public static boolean onHypixel(ServerInfo server) {
        if (server == null || server.address == null || server.isRealm()) return false;
        return isHypixel(server.address);
    }

    /** Whether a typed server address points at Hypixel ("mc.hypixel.net:25565", "Hypixel.net."). */
    public static boolean isHypixel(String address) {
        String host = address.strip().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) return false; // an IPv6 literal
        int colon = host.lastIndexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host.equals("hypixel.net") || host.endsWith(".hypixel.net");
    }
}
