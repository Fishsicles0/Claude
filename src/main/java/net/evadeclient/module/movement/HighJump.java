package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;

/** Jumps higher. */
public class HighJump extends Module {
    private final NumberSetting multiplier = add(new NumberSetting("Multiplier", 1.6, 1.1, 4, 0.1));

    public HighJump() { super("HighJump", "Jump higher.", Category.MOVEMENT); }

    public float multiplier() { return multiplier.getFloat(); }
}
