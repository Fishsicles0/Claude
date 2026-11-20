package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.util.math.Box;

/** Pulls you straight down when you walk off a small ledge, instead of floating down. */
public class ReverseStep extends Module {
    private final NumberSetting height = add(new NumberSetting("Height", 2, 1, 4, 0.5));
    private final NumberSetting speed = add(new NumberSetting("Speed", 1, 0.3, 3, 0.1));
    private boolean wasOnGround;

    public ReverseStep() {
        super("ReverseStep", "Drops you down small ledges instantly.", Category.MOVEMENT);
    }

    @Override
    public void onTick() {
        boolean onGround = mc.player.isOnGround();
        if (wasOnGround && !onGround && mc.player.getVelocity().y <= 0 && !mc.options.jumpKey.isPressed()
                && !mc.player.isTouchingWater() && !mc.player.isGliding() && !mc.player.getAbilities().flying) {
            Box below = mc.player.getBoundingBox().offset(0, -height.get(), 0).stretch(0, height.get() - 0.01, 0);
            if (!mc.world.isSpaceEmpty(mc.player, below)) // there is ground within reach below
                mc.player.setVelocity(mc.player.getVelocity().x, -speed.get(), mc.player.getVelocity().z);
        }
        wasOnGround = onGround;
    }
}
