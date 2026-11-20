package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.util.ClickTracker;

import java.util.ArrayList;
import java.util.List;

public class CpsHud extends TextHudModule {
    private final BooleanSetting right = add(new BooleanSetting("Show Right", true));

    public CpsHud() {
        super("CPS", "Clicks per second.", 4, 136);
        setEnabled(true);
    }

    @Override
    protected List<String> lines(boolean preview) {
        String s = right.get() ? ClickTracker.leftCps() + " | " + ClickTracker.rightCps() + " CPS" : ClickTracker.leftCps() + " CPS";
        return new ArrayList<>(List.of(s));
    }
}
