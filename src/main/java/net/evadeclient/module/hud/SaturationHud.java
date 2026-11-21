package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;

import java.util.ArrayList;
import java.util.List;

/** Your hidden hunger saturation value (how long until your food bar starts dropping). */
public class SaturationHud extends TextHudModule {
    public SaturationHud() {
        super("Saturation", "Your hunger saturation.", 4, 184);
    }

    public float saturation() { return mc.player == null ? 0 : mc.player.getHungerManager().getSaturationLevel(); }

    @Override
    protected List<String> lines(boolean preview) {
        return new ArrayList<>(List.of(String.format("%.1f Sat", preview ? 7.5f : saturation())));
    }
}
