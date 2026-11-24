package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.module.visuals.NickHider;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.minecraft.client.gui.DrawContext;

/**
 * One switch for streaming and recording: Evade's own widgets stop showing your coordinates and the server address,
 * Nick Hider turns on, friends' servers and message previews are hidden, and waypoint coordinates stay out of chat
 * shares and labels. Only what Evade itself draws is changed (the game's F3 screen is untouched). A small "Streamer"
 * tag in the corner shows it is on.
 */
public class StreamerMode extends Module {
    public final BooleanSetting hideCoords = add(new BooleanSetting("Hide Coordinates", true)
            .describe("Evade's Coordinates widget, waypoint labels and shares leave out your coordinates."));
    public final BooleanSetting hideServer = add(new BooleanSetting("Hide Server Address", true)
            .describe("The Server widget shows the server's name from your list instead of its address."));
    public final BooleanSetting nickHider = add(new BooleanSetting("Turn On Nick Hider", true)
            .describe("Switch Nick Hider on with Streamer Mode (and off again after, if it was off before)."));
    public final BooleanSetting hideFriends = add(new BooleanSetting("Hide Friend Servers", true)
            .describe("Friends' servers and message previews are hidden in Evade's friends list and toasts."));
    public final BooleanSetting indicator = add(new BooleanSetting("Indicator", true)
            .describe("A small Streamer tag in the top-right corner while this is on."));

    private static StreamerMode instance;
    /** Whether Streamer Mode switched Nick Hider on (so it switches it off again). */
    private boolean turnedNickOn;

    public StreamerMode() {
        super("Streamer Mode", "Hides your coordinates, server address and friends' servers from Evade's HUD and turns on Nick Hider.", Category.MISC);
        instance = this;
    }

    @Override protected boolean alwaysRunHooks() { return true; }

    private static StreamerMode on() {
        StreamerMode s = instance;
        return s != null && s.isEnabled() ? s : null;
    }

    public static boolean active() { return on() != null; }
    public static boolean hidesCoordinates() { StreamerMode s = on(); return s != null && s.hideCoords.get(); }
    public static boolean hidesServerAddress() { StreamerMode s = on(); return s != null && s.hideServer.get(); }
    public static boolean hidesFriendServers() { StreamerMode s = on(); return s != null && s.hideFriends.get(); }
    /** Whether toasts and lists should leave out the text of friends' messages. */
    public static boolean hidesMessagePreviews() { return hidesFriendServers(); }

    /** A friend's server as Evade should show it: the address, or "a server" while it is hidden. */
    public static String friendServer(String server) {
        return server == null ? null : hidesFriendServers() ? "a server" : server;
    }

    @Override
    protected void onEnable() {
        turnedNickOn = false;
        if (nickHider.get() && EvadeClient.modules != null) {
            NickHider nh = EvadeClient.modules.get(NickHider.class);
            if (nh != null && !nh.isEnabled()) {
                nh.setEnabled(true);
                turnedNickOn = nh.isEnabled();
            }
        }
    }

    @Override
    protected void onDisable() {
        if (turnedNickOn && EvadeClient.modules != null) {
            NickHider nh = EvadeClient.modules.get(NickHider.class);
            if (nh != null) nh.setEnabled(false);
        }
        turnedNickOn = false;
    }

    @Override
    public void onHud(DrawContext ctx, float tickDelta) {
        if (!indicator.get() || mc.options.hudHidden) return;
        String label = "STREAMER";
        boolean smooth = true;
        var cs = EvadeClient.modules.get(net.evadeclient.module.client.ClientSettings.class);
        if (cs != null) smooth = cs.smoothFont.get();
        int w = UiFont.width(mc.textRenderer, label, UiFont.Role.TINY, smooth) + 14;
        int x = ctx.getScaledWindowWidth() - w - 3, y = 3;
        Theme.pill(ctx, x, y, x + w, y + 11, Theme.alpha(0xFF000000, 0.55f));
        Theme.disc(ctx, x + 4, y + 3, 5, Theme.RED);
        UiFont.draw(ctx, mc.textRenderer, label, x + 11, y + 3, Theme.TEXT, UiFont.Role.TINY, smooth);
    }
}
