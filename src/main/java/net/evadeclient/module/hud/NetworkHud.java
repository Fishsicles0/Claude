package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.util.NetStats;

import java.util.ArrayList;
import java.util.List;

/**
 * Packets per second on your own connection, in from the server and out from you, from the game's own counters
 * (the numbers F3 used to show). Handy for spotting a server flooding you or a stalled connection.
 */
public class NetworkHud extends TextHudModule {
    private final ModeSetting show = add(new ModeSetting("Show", "In + Out", "In + Out", "In", "Out")
            .describe("Which directions to show."));
    private final ModeSetting layout = add(new ModeSetting("Layout", "Lines", "Lines", "Inline")
            .describe("One line per direction, or both on one line."));

    public NetworkHud() { super("Network", "Packets per second in and out on your own connection.", 4, 286); }

    @Override
    protected List<String> lines(boolean preview) {
        float in = NetStats.packetsIn(), out = NetStats.packetsOut();
        if (in < 0 && preview) { in = 184; out = 23; }
        List<String> lines = new ArrayList<>(2);
        if (in < 0) return lines;
        String inText = "In: " + Math.round(in) + " p/s", outText = "Out: " + Math.round(out) + " p/s";
        boolean wantIn = !show.is("Out"), wantOut = !show.is("In");
        if (layout.is("Inline") && wantIn && wantOut) {
            lines.add("Packets: " + Math.round(in) + " in / " + Math.round(out) + " out");
            return lines;
        }
        if (wantIn) lines.add(inText);
        if (wantOut) lines.add(outText);
        return lines;
    }
}
