package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;

import java.util.ArrayList;
import java.util.List;

public class SpeedHud extends TextHudModule {
    private double speed;

    public SpeedHud() { super("Speed", "Horizontal speed in blocks per second.", 4, 248); }

    @Override
    public void onTick() {
        var p = mc.player;
        double dx = p.getX() - p.lastX, dz = p.getZ() - p.lastZ;
        speed = Math.sqrt(dx * dx + dz * dz) * 20;
    }

    @Override
    protected List<String> lines(boolean preview) {
        return new ArrayList<>(List.of(String.format("%.2f m/s", speed)));
    }
}
