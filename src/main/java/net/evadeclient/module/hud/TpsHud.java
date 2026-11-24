package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.util.TpsEstimator;
import net.minecraft.network.packet.Packet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The server's ticks per second on its own line, estimated from the time updates every server sends about once a
 * second (in singleplayer, the built-in server's real tick time). Marked as an estimate: network jitter moves it a
 * little, and it only reads what the server already sends.
 */
public class TpsHud extends TextHudModule {
    private final ModeSetting smoothing = add(new ModeSetting("Smoothing", "Normal", "Fast", "Normal", "Steady")
            .describe("How many seconds the estimate averages over: Fast reacts sooner, Steady jumps less."));
    private final BooleanSetting colorByTps = add(new BooleanSetting("Color By TPS", true)
            .describe("Green at full speed, yellow when the server slows down, red when it lags."));
    private final BooleanSetting decimals = add(new BooleanSetting("Decimals", true)
            .describe("Show one decimal place (19.8) instead of whole numbers."));
    private final ModeSetting label = add(new ModeSetting("Label", "TPS (est.)", "TPS (est.)", "~TPS", "Server TPS")
            .describe("How the line is labelled. It's always an estimate from what the server sends."));

    private final TpsEstimator fast = new TpsEstimator(3), normal = new TpsEstimator(6), steady = new TpsEstimator(12);
    /** The value shown this tick (NaN while there is none), for the colour. */
    private float shown = Float.NaN;

    public TpsHud() { super("Server TPS", "An estimate of the server's ticks per second, from its time updates.", 4, 270); }

    @Override
    public void onServerPacket(Packet<?> packet) {
        fast.onPacket(packet);
        normal.onPacket(packet);
        steady.onPacket(packet);
    }

    private TpsEstimator estimator() {
        return switch (smoothing.get()) {
            case "Fast" -> fast;
            case "Steady" -> steady;
            default -> normal;
        };
    }

    /** The current estimate (NaN until two time updates have arrived). */
    public float tps() { return estimator().tps(); }

    /** Test hook: the estimator the lines read. */
    public TpsEstimator estimatorForTest() { return estimator(); }

    @Override
    protected List<String> lines(boolean preview) {
        float tps = tps();
        if (Float.isNaN(tps) && preview) tps = 20f;
        shown = tps;
        String value = Float.isNaN(tps) ? "--" : decimals.get() ? String.format(Locale.ROOT, "%.1f", tps) : String.valueOf(Math.round(tps));
        if (TpsEstimator.frozen()) value += " (frozen)";
        String line = switch (label.get()) {
            case "~TPS" -> "TPS: ~" + value;
            case "Server TPS" -> "Server TPS: " + value;
            default -> "TPS (est.): " + value;
        };
        return new ArrayList<>(List.of(line));
    }

    @Override
    protected int lineColor(int index, int base) {
        if (!colorByTps.get() || Float.isNaN(shown)) return base;
        return TpsEstimator.color(shown);
    }
}
