package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.util.math.Vec3d;

/** Fly in survival. Vanilla mode uses creative-style flight; Velocity mode drives your motion directly. */
public class Fly extends Module {
    private final ModeSetting mode = add(new ModeSetting("Mode", "Vanilla", "Vanilla", "Velocity"));
    private final NumberSetting speed = add(new NumberSetting("Speed", 1, 0.1, 5, 0.1));
    private final BooleanSetting antiKick = add(new BooleanSetting("Anti Kick", true));
    private int ticks;

    public Fly() { super("Fly", "Lets you fly in survival.", Category.MOVEMENT); }

    @Override
    public void onTick() {
        ticks++;
        if (mode.is("Vanilla")) {
            mc.player.getAbilities().flying = true;
            mc.player.getAbilities().setFlySpeed(0.05f * speed.getFloat());
        } else {
            double s = speed.get();
            double f = (mc.options.forwardKey.isPressed() ? 1 : 0) - (mc.options.backKey.isPressed() ? 1 : 0);
            double st = (mc.options.leftKey.isPressed() ? 1 : 0) - (mc.options.rightKey.isPressed() ? 1 : 0);
            double v = (mc.options.jumpKey.isPressed() ? 1 : 0) - (mc.options.sneakKey.isPressed() ? 1 : 0);
            double yaw = Math.toRadians(mc.player.getYaw());
            mc.player.setVelocity(new Vec3d((-Math.sin(yaw) * f + Math.cos(yaw) * st) * s, v * s * 0.5, (Math.cos(yaw) * f + Math.sin(yaw) * st) * s));
        }
        // Vanilla servers kick players who hover too long; dip slightly every couple of seconds.
        if (antiKick.get() && ticks % 40 == 0 && !mc.player.isOnGround()) {
            Vec3d vel = mc.player.getVelocity();
            mc.player.setVelocity(vel.x, Math.min(vel.y, -0.04), vel.z);
        }
    }

    @Override
    protected void onDisable() {
        var a = mc.player.getAbilities();
        a.setFlySpeed(0.05f);
        if (!mc.player.isCreative() && !mc.player.isSpectator()) a.flying = false;
    }

    @Override
    public String getSuffix() { return mode.get(); }
}
