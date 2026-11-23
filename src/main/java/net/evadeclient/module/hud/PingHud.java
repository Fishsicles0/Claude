package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;

import java.util.ArrayList;
import java.util.List;

/**
 * Your latency as the server lists it in the tab list (the same number vanilla shows there). Purely a readout:
 * it never sends anything to measure it.
 */
public class PingHud extends TextHudModule {
    private final BooleanSetting colorByLatency = add(new BooleanSetting("Color By Latency", false)
            .describe("Green under 80 ms, yellow under 150 ms, red above."));

    /** The latency shown this tick, or -1 when there is none to show (singleplayer, not listed yet). */
    private int shown = -1;

    public PingHud() { super("Ping", "Your latency to the server.", 4, 72); }

    @Override
    protected List<String> lines(boolean preview) {
        shown = -1;
        if (mc.isInSingleplayer()) return new ArrayList<>(List.of("Local"));
        var nh = mc.getNetworkHandler();
        var entry = nh == null || mc.player == null ? null : nh.getPlayerListEntry(mc.player.getUuid());
        if (entry == null) return new ArrayList<>(List.of("? ms"));
        shown = entry.getLatency();
        return new ArrayList<>(List.of(shown + " ms"));
    }

    @Override
    protected int lineColor(int index, int base) {
        if (!colorByLatency.get() || shown < 0) return base;
        return shown < 80 ? 0xFF3DDC84 : shown < 150 ? 0xFFFFB020 : 0xFFFF4D5E;
    }
}
