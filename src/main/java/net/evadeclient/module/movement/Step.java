package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;

public class Step extends Module {
    private static final double DEFAULT_STEP = 0.6;
    private final NumberSetting height = add(new NumberSetting("Height", 1.0, 0.6, 3.0, 0.5));

    public Step() {
        super("Step", "Walk up full blocks without jumping.", Category.MOVEMENT);
    }

    @Override
    public void onTick() {
        EntityAttributeInstance attr = mc.player.getAttributeInstance(EntityAttributes.STEP_HEIGHT);
        if (attr != null) attr.setBaseValue(height.get());
    }

    @Override
    protected void onDisable() {
        EntityAttributeInstance attr = mc.player.getAttributeInstance(EntityAttributes.STEP_HEIGHT);
        if (attr != null) attr.setBaseValue(DEFAULT_STEP);
    }
}
