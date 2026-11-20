package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;

import java.util.ArrayList;
import java.util.List;

public class FpsHud extends TextHudModule {
    public FpsHud() {
        super("FPS", "Frames per second.", 4, 120);
        setEnabled(true);
    }

    @Override
    protected List<String> lines(boolean preview) {
        return new ArrayList<>(List.of(mc.getCurrentFps() + " FPS"));
    }
}
