package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.util.math.Vec3d;

/** Moves faster on the ground. Strafe keeps full speed in any direction; Bhop adds a hop each time you land. */
public class Speed extends Module {
    private final ModeSetting mode = add(new ModeSetting("Mode", "Strafe", "Strafe", "Bhop"));
    private final NumberSetting speed = add(new NumberSetting("Speed", 0.36, 0.2, 1.0, 0.01));

    public Speed() { super("Speed", "Move faster on the ground.", Category.MOVEMENT); }

    @Override
    public void onTick() {
        if (mc.player.isTouchingWater() || mc.player.isInLava() || mc.player.isGliding() || mc.player.getAbilities().flying) return;
        double f = (mc.options.forwardKey.isPressed() ? 1 : 0) - (mc.options.backKey.isPressed() ? 1 : 0);
        double st = (mc.options.leftKey.isPressed() ? 1 : 0) - (mc.options.rightKey.isPressed() ? 1 : 0);
        if (f == 0 && st == 0) return;
        if (mode.is("Bhop") && mc.player.isOnGround()) mc.player.jump();
        double yaw = Math.toRadians(mc.player.getYaw());
        double x = -Math.sin(yaw) * f + Math.cos(yaw) * st, z = Math.cos(yaw) * f + Math.sin(yaw) * st;
        double len = Math.sqrt(x * x + z * z);
        double s = speed.get();
        Vec3d v = mc.player.getVelocity();
        mc.player.setVelocity(x / len * s, v.y, z / len * s);
    }

    @Override
    public String getSuffix() { return mode.get(); }
}
