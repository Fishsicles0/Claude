package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;

import java.util.ArrayList;
import java.util.List;

public class PingHud extends TextHudModule {
    public PingHud() { super("Ping", "Your latency to the server.", 4, 152); }

    @Override
    protected List<String> lines(boolean preview) {
        var nh = mc.getNetworkHandler();
        var entry = nh == null ? null : nh.getPlayerListEntry(mc.player.getUuid());
        int ms = entry == null ? 0 : entry.getLatency();
        return new ArrayList<>(List.of(mc.isInSingleplayer() ? "0 ms" : ms + " ms"));
    }
}
