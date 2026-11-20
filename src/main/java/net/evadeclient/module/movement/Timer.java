package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;

/** Speeds up (or slows down) the game on your side. */
public class Timer extends Module {
    private final NumberSetting speed = add(new NumberSetting("Speed", 1.2, 0.1, 5, 0.05));

    public Timer() { super("Timer", "Run the game faster on your side.", Category.MOVEMENT); }

    public float speed() { return speed.getFloat(); }

    @Override
    public String getSuffix() { return String.format("%.2fx", speed.get()); }
}
