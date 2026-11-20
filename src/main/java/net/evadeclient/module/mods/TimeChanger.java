package net.evadeclient.module.mods;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;

/** Client-side time of day. Only changes what you see. */
public class TimeChanger extends Module {
    private final ModeSetting preset = add(new ModeSetting("Time", "Day", "Day", "Sunset", "Night", "Midnight", "Custom"));
    private final NumberSetting custom = add(new NumberSetting("Custom Ticks", 6000, 0, 23999, 100));

    public TimeChanger() { super("Time Changer", "Change the time of day you see.", Category.MODS); }

    @Override
    public void onTick() { apply(); }

    /** Also called right after the server sends its own time, so the server never wins for a frame. */
    public void apply() {
        if (mc.world == null) return;
        long t = switch (preset.get()) {
            case "Sunset" -> 12500;
            case "Night" -> 14500;
            case "Midnight" -> 18000;
            case "Custom" -> custom.getInt();
            default -> 6000;
        };
        mc.world.getLevelProperties().setTimeOfDay(t);
    }

    @Override
    public String getSuffix() { return preset.get(); }
}
