package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;

/** Jumps much further: adds a burst of forward speed when you jump. */
public class LongJump extends Module {
    private final NumberSetting boost = add(new NumberSetting("Boost", 1.2, 0.5, 3, 0.1));
    private final BooleanSetting autoDisable = add(new BooleanSetting("Disable After Jump", true));
    private boolean wasOnGround, boosted;

    public LongJump() { super("LongJump", "Jump much further.", Category.MOVEMENT); }

    @Override
    protected void onEnable() { boosted = false; }

    @Override
    public void onTick() {
        boolean ground = mc.player.isOnGround();
        if (wasOnGround && !ground && mc.player.getVelocity().y > 0.3) {
            double yaw = Math.toRadians(mc.player.getYaw());
            var v = mc.player.getVelocity();
            mc.player.setVelocity(-Math.sin(yaw) * boost.get(), v.y, Math.cos(yaw) * boost.get());
            boosted = true;
        } else if (boosted && ground && autoDisable.get()) {
            setEnabled(false);
        }
        wasOnGround = ground;
    }
}
