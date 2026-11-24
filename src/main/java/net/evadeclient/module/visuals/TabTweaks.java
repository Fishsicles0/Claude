package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.network.PlayerListEntry;

/**
 * Small player list (tab) improvements: ping as a coloured number instead of signal bars, and hiding the server's
 * header or footer text. Purely how the list is drawn; nothing is sent to the server.
 */
public class TabTweaks extends Module {
    /** Vanilla's width for the ping bars plus the gap before them (the {@code 13} in {@code PlayerListHud.render}). */
    public static final int VANILLA_PING_SPACE = 13;

    public final BooleanSetting numericPing = add(new BooleanSetting("Numeric Ping", true)
            .describe("Show each player's ping in milliseconds, coloured by quality, instead of signal bars."));
    public final BooleanSetting hideHeader = add(new BooleanSetting("Hide Header", false)
            .describe("Hide the text the server puts above the player list."));
    public final BooleanSetting hideFooter = add(new BooleanSetting("Hide Footer", false)
            .describe("Hide the text the server puts below the player list."));

    private static TabTweaks instance;

    public TabTweaks() {
        super("Tab Tweaks", "Numeric ping in the player list, and hide its header or footer.", Category.VISUALS);
    }

    public static TabTweaks active() {
        TabTweaks t = instance;
        if (t == null) {
            if (EvadeClient.modules == null) return null;
            t = instance = EvadeClient.modules.get(TabTweaks.class);
            if (t == null) return null;
        }
        return t.isEnabled() ? t : null;
    }

    /** Whether pings are drawn as numbers right now. */
    public static boolean numeric() {
        TabTweaks t = active();
        return t != null && t.numericPing.get();
    }

    /** Whether the player list header is hidden right now. */
    public static boolean headerHidden() {
        TabTweaks t = active();
        return t != null && t.hideHeader.get();
    }

    /** Whether the player list footer is hidden right now. */
    public static boolean footerHidden() {
        TabTweaks t = active();
        return t != null && t.hideFooter.get();
    }

    /** "87ms"; ten seconds or more as "12s" so a lagging player doesn't stretch every column. */
    public static String pingText(int ms) {
        return ms >= 10_000 ? (ms / 1000) + "s" : ms + "ms";
    }

    /** Green under 150 ms, yellow under 300, gold under 600, red above (the same steps as the vanilla bars). */
    public static int pingColor(int ms) {
        if (ms < 150) return 0xFF55FF55;
        if (ms < 300) return 0xFFFFFF55;
        if (ms < 600) return 0xFFFFAA00;
        return 0xFFFF5555;
    }

    /**
     * Space the ping column needs: the widest number in the list, plus one pixel at the right edge and two before
     * it, like the bars. Never narrower than vanilla, so the unknown-ping icon still fits.
     */
    public static int pingSpace() {
        MinecraftClient mc = MinecraftClient.getInstance();
        var handler = mc.getNetworkHandler();
        if (handler == null) return VANILLA_PING_SPACE;
        TextRenderer tr = mc.textRenderer;
        int widest = 0;
        for (PlayerListEntry e : handler.getListedPlayerListEntries()) {
            if (e.getLatency() >= 0) widest = Math.max(widest, tr.getWidth(pingText(e.getLatency())));
        }
        return Math.max(VANILLA_PING_SPACE, widest + 3);
    }
}
