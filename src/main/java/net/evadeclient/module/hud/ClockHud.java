package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.ModeSetting;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class ClockHud extends TextHudModule {
    private final ModeSetting format = add(new ModeSetting("Format", "12h", "12h", "24h"));

    public ClockHud() { super("Clock", "Real-world time.", 4, 200); }

    @Override
    protected List<String> lines(boolean preview) {
        String pattern = format.is("12h") ? "h:mm a" : "HH:mm";
        return new ArrayList<>(List.of(LocalTime.now().format(DateTimeFormatter.ofPattern(pattern))));
    }
}
