package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;

/** Climb ladders and vines faster. */
public class FastClimb extends Module {
    private final NumberSetting speed = add(new NumberSetting("Speed", 0.3, 0.12, 1, 0.02));

    public FastClimb() {
        super("FastClimb", "Climb ladders and vines faster.", Category.MOVEMENT);
    }

    @Override
    public void onTick() {
        if (mc.player.isClimbing() && (mc.player.horizontalCollision || mc.options.jumpKey.isPressed())
                && !mc.options.sneakKey.isPressed()) {
            var v = mc.player.getVelocity();
            mc.player.setVelocity(v.x, speed.get(), v.z);
        }
    }
}
