package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;

/** Jumps whenever you land, for bunny hopping. Optionally only while moving. */
public class AutoJump extends Module {
    private final BooleanSetting moving = add(new BooleanSetting("Only Moving", true));

    public AutoJump() {
        super("AutoJump", "Jumps automatically every time you land.", Category.MOVEMENT);
    }

    @Override
    public void onTick() {
        if (!mc.player.isOnGround() || mc.player.isSneaking() || mc.player.isTouchingWater()) return;
        if (moving.get() && mc.player.input.getMovementInput().lengthSquared() < 0.01f) return;
        mc.player.jump();
    }
}
