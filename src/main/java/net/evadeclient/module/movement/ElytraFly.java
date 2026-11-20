package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.util.math.Vec3d;

/** Control your elytra like flight: hold direction keys to move, jump/sneak to go up/down, no stalling. */
public class ElytraFly extends Module {
    private final NumberSetting speed = add(new NumberSetting("Speed", 1.6, 0.2, 5, 0.1));
    private final NumberSetting vertical = add(new NumberSetting("Vertical", 0.8, 0.1, 3, 0.1));
    private final BooleanSetting autoOpen = add(new BooleanSetting("Auto Open", true));

    public ElytraFly() { super("ElytraFly", "Fly freely while wearing an elytra.", Category.MOVEMENT); }

    @Override
    public void onTick() {
        if (!mc.player.isGliding()) {
            if (autoOpen.get() && !mc.player.isOnGround() && mc.player.getVelocity().y < -0.1 && mc.player.checkGliding()) {
                mc.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket(mc.player,
                        net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            }
            return;
        }
        double s = speed.get();
        double f = (mc.options.forwardKey.isPressed() ? 1 : 0) - (mc.options.backKey.isPressed() ? 1 : 0);
        double st = (mc.options.leftKey.isPressed() ? 1 : 0) - (mc.options.rightKey.isPressed() ? 1 : 0);
        double v = (mc.options.jumpKey.isPressed() ? 1 : 0) - (mc.options.sneakKey.isPressed() ? 1 : 0);
        double yaw = Math.toRadians(mc.player.getYaw());
        mc.player.setVelocity(new Vec3d((-Math.sin(yaw) * f + Math.cos(yaw) * st) * s, v * vertical.get(), (Math.cos(yaw) * f + Math.sin(yaw) * st) * s));
    }
}
