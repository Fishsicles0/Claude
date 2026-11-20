package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;

/** Keeps you sprinting whenever you're moving, so you never have to hold or tap sprint. */
public class AutoSprint extends Module {
    private final BooleanSetting omni = add(new BooleanSetting("All Directions", true));
    private final BooleanSetting keepHunger = add(new BooleanSetting("Even While Hungry", false));

    public AutoSprint() {
        super("AutoSprint", "Always sprint while moving.", Category.MOVEMENT);
    }

    @Override
    public void onTick() {
        if (mc.player.isUsingItem() || mc.player.horizontalCollision) return;
        if (!keepHunger.get() && mc.player.getHungerManager().getFoodLevel() <= 6) return;
        var in = mc.player.input.getMovementInput();
        boolean moving = omni.get() ? (Math.abs(in.x) > 0.01 || Math.abs(in.y) > 0.01) : in.y > 0.01;
        if (moving && !mc.player.isSneaking()) mc.player.setSprinting(true);
    }
}
