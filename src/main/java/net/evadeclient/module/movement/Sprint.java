package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;

public class Sprint extends Module {
    public Sprint() {
        super("Sprint", "Always sprint when moving forward.", Category.MOVEMENT);
    }

    @Override
    public void onTick() {
        boolean canSprint = mc.options.forwardKey.isPressed()
                && !mc.player.isSneaking()
                && !mc.player.horizontalCollision
                && (mc.player.getHungerManager().getFoodLevel() > 6 || mc.player.getAbilities().allowFlying);
        if (canSprint) mc.player.setSprinting(true);
    }
}
