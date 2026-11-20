package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;

/** Swim up automatically in water, like a dolphin, so you stay at the surface while moving. */
public class Dolphin extends Module {
    public Dolphin() { super("Dolphin", "Stay at the water's surface while swimming.", Category.MOVEMENT); }

    @Override
    public void onTick() {
        if (mc.player.isTouchingWater() && !mc.player.isSneaking() && !mc.options.sneakKey.isPressed()) {
            var v = mc.player.getVelocity();
            mc.player.setVelocity(v.x, Math.max(v.y, 0.04), v.z);
        }
    }
}
