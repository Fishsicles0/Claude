package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.NetStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Server not responding": appears only when nothing has arrived from the server for a while (servers normally send
 * something many times a second), counting the seconds. It watches your own connection and nothing else.
 */
public class ConnectionWatchHud extends TextHudModule {
    private final NumberSetting after = add(new NumberSetting("Warn After", 2.0, 1.0, 10.0, 0.5)
            .describe("Seconds without anything from the server before the warning shows."));
    private final BooleanSetting singleplayer = add(new BooleanSetting("In Singleplayer", false)
            .describe("Also watch singleplayer's built-in server (it stops while the game is paused)."));
    private final BooleanSetting pulse = add(new BooleanSetting("Pulse", true)
            .describe("The warning fades in and out so it catches your eye."));
    private final ColorSetting warnColor = add(new ColorSetting("Warning Color", 0xFFFF4D5E));

    public ConnectionWatchHud() {
        super("Connection Watch", "Warns \"Server not responding\" with a seconds count when the server goes quiet.", CENTER, 60);
    }

    /** Seconds the server has been silent, or 0 when it isn't (or this situation isn't watched). */
    public double silence() {
        if (mc.isInSingleplayer() && (!singleplayer.get() || mc.isPaused())) return 0;
        double s = NetStats.silentSeconds();
        return s >= after.get() ? s : 0;
    }

    @Override
    protected List<String> lines(boolean preview) {
        double s = silence();
        if (s <= 0 && preview) s = 4.2;
        if (s <= 0) return new ArrayList<>();
        return new ArrayList<>(List.of(String.format(Locale.ROOT, "Server not responding: %.1fs", s)));
    }

    @Override
    protected int lineColor(int index, int base) {
        int c = warnColor.get();
        if (!pulse.get()) return c;
        float a = 0.6f + 0.4f * (float) Math.abs(Math.sin(System.currentTimeMillis() / 400.0));
        return Math.round((c >>> 24) * a) << 24 | (c & 0xFFFFFF);
    }
}
