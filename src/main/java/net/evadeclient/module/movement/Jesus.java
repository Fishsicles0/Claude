package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;

/** Walk on water and lava as if they were solid. Sneak to go under. */
public class Jesus extends Module {
    public Jesus() { super("Jesus", "Walk on water and lava.", Category.MOVEMENT); }

    @Override
    public void onTick() {
        // If you're already in the liquid, float back up to the surface.
        if ((mc.player.isTouchingWater() || mc.player.isInLava()) && !mc.player.isSneaking() && !mc.player.isSubmergedInWater()) {
            var v = mc.player.getVelocity();
            mc.player.setVelocity(v.x, 0.11, v.z);
        }
    }
}
