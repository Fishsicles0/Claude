package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.entity.player.PlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Your horizontal speed, averaged over the last few ticks so sprint-jumping reads as a steady number instead of
 * flickering with every hop. Teleports (more than 5 blocks in one tick) are left out.
 */
public class SpeedHud extends TextHudModule {
    private static final int RING = 20;
    /** A tick moving further than this is a teleport, respawn or dimension change, not speed. */
    private static final double MAX_STEP = 5.0;

    private final NumberSetting smoothing = add(new NumberSetting("Smoothing", 10, 1, 20, 1)
            .describe("How many ticks (20 a second) the speed is averaged over. 1 shows every tick as it is."));
    private final ModeSetting unit = add(new ModeSetting("Unit", "m/s", "m/s", "km/h"));

    private final double[] steps = new double[RING];
    private int head, count;
    private PlayerEntity lastPlayer;

    public SpeedHud() { super("Speed", "Horizontal speed in blocks per second.", 4, 120); }

    @Override
    protected void onEnable() { clear(); }

    private void clear() {
        head = 0;
        count = 0;
        lastPlayer = mc.player;
    }

    @Override
    public void onTick() {
        var p = mc.player;
        if (p == null) return;
        if (p != lastPlayer) { clear(); return; }
        double dx = p.getX() - p.lastX, dz = p.getZ() - p.lastZ;
        double step = Math.sqrt(dx * dx + dz * dz);
        if (step > MAX_STEP) return;
        steps[head] = step;
        head = (head + 1) % RING;
        count = Math.min(count + 1, RING);
    }

    /** Average horizontal speed in blocks per second over the Smoothing window. */
    public double speed() {
        int n = Math.min(count, Math.max(1, smoothing.getInt()));
        if (n == 0) return 0;
        double total = 0;
        for (int i = 0; i < n; i++) total += steps[Math.floorMod(head - 1 - i, RING)];
        return total / n * 20;
    }

    @Override
    protected List<String> lines(boolean preview) {
        double v = speed();
        String s = unit.is("km/h") ? String.format(Locale.ROOT, "%.1f km/h", v * 3.6) : String.format(Locale.ROOT, "%.2f m/s", v);
        return new ArrayList<>(List.of(s));
    }
}
