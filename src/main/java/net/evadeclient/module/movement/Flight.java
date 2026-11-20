package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.util.math.Vec3d;

public class Flight extends Module {
    private final ModeSetting mode = add(new ModeSetting("Mode", "Vanilla", "Vanilla", "Velocity"));
    private final NumberSetting speed = add(new NumberSetting("Speed", 1.0, 0.1, 5.0, 0.1));

    public Flight() {
        super("Flight", "Lets you fly in survival.", Category.MOVEMENT);
    }

    @Override
    public void onTick() {
        if (mode.is("Vanilla")) {
            mc.player.getAbilities().flying = true;
            mc.player.getAbilities().setFlySpeed(0.05f * speed.getFloat());
            return;
        }

        // Velocity mode: drive motion directly from the movement keys.
        double s = speed.get();
        double forward = (mc.options.forwardKey.isPressed() ? 1 : 0) - (mc.options.backKey.isPressed() ? 1 : 0);
        double strafe = (mc.options.leftKey.isPressed() ? 1 : 0) - (mc.options.rightKey.isPressed() ? 1 : 0);
        double vertical = (mc.options.jumpKey.isPressed() ? 1 : 0) - (mc.options.sneakKey.isPressed() ? 1 : 0);

        double yaw = Math.toRadians(mc.player.getYaw());
        double x = (-Math.sin(yaw) * forward + Math.cos(yaw) * strafe) * s;
        double z = (Math.cos(yaw) * forward + Math.sin(yaw) * strafe) * s;
        mc.player.setVelocity(new Vec3d(x, vertical * s * 0.5, z));
    }

    @Override
    protected void onDisable() {
        var abilities = mc.player.getAbilities();
        abilities.setFlySpeed(0.05f);
        if (!mc.player.isCreative() && !mc.player.isSpectator()) {
            abilities.flying = false;
        }
    }

    @Override
    public String getSuffix() { return mode.get(); }
}
