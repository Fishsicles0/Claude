package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;

/** Bounces you back up if you fall into the void. */
public class AntiVoid extends Module {
    private final NumberSetting margin = add(new NumberSetting("Trigger Depth", 5, 1, 30, 1));

    public AntiVoid() { super("AntiVoid", "Stops you falling into the void.", Category.MOVEMENT); }

    @Override
    public void onTick() {
        if (mc.player.getY() < mc.world.getBottomY() + margin.get() && mc.player.getVelocity().y < 0) {
            var v = mc.player.getVelocity();
            mc.player.setVelocity(v.x, 0.8, v.z);
        }
    }
}
