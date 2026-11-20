package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.entity.attribute.EntityAttributes;

/** Walk straight up blocks without jumping. */
public class Step extends Module {
    private final NumberSetting height = add(new NumberSetting("Height", 1, 0.6, 3, 0.5));

    public Step() { super("Step", "Walk up full blocks without jumping.", Category.MOVEMENT); }

    @Override
    public void onTick() {
        var a = mc.player.getAttributeInstance(EntityAttributes.STEP_HEIGHT);
        if (a != null) a.setBaseValue(mc.player.isSneaking() ? 0.6 : height.get());
    }

    @Override
    protected void onDisable() {
        var a = mc.player.getAttributeInstance(EntityAttributes.STEP_HEIGHT);
        if (a != null) a.setBaseValue(0.6);
    }
}
