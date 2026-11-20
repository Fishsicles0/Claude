package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.minecraft.util.math.Box;

/** Jumps automatically at the very edge of a block. */
public class Parkour extends Module {
    public Parkour() { super("Parkour", "Jumps at the edge of blocks.", Category.MOVEMENT); }

    @Override
    public void onTick() {
        if (!mc.player.isOnGround() || mc.player.isSneaking() || mc.options.jumpKey.isPressed()) return;
        Box next = mc.player.getBoundingBox().offset(0, -0.5, 0).expand(-0.001, 0, -0.001)
                .offset(mc.player.getVelocity().x, 0, mc.player.getVelocity().z);
        if (mc.world.isSpaceEmpty(mc.player, next)) mc.player.jump();
    }
}
