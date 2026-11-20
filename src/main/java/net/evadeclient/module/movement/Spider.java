package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;

/** Climb walls like a spider: push into a wall to go up. */
public class Spider extends Module {
    private final NumberSetting speed = add(new NumberSetting("Speed", 0.2, 0.1, 0.6, 0.05));

    public Spider() { super("Spider", "Climb up walls.", Category.MOVEMENT); }

    @Override
    public void onTick() {
        if (mc.player.horizontalCollision && !mc.player.isSneaking()) {
            var v = mc.player.getVelocity();
            mc.player.setVelocity(v.x, Math.max(v.y, speed.get()), v.z);
        }
    }
}
