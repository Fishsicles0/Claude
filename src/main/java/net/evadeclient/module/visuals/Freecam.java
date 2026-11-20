package net.evadeclient.module.visuals;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Detach the camera and fly it around freely while your player stays where it is. */
public class Freecam extends Module {
    private final NumberSetting speed = add(new NumberSetting("Speed", 1, 0.1, 5, 0.1));

    private Vec3d pos = Vec3d.ZERO, lastPos = Vec3d.ZERO;
    private float yaw, pitch;
    private PlayerInput input = PlayerInput.DEFAULT;

    public Freecam() { super("Freecam", "Fly the camera around freely.", Category.VISUALS); }

    @Override
    protected void onEnable() {
        pos = lastPos = mc.player.getCameraPosVec(1f);
        yaw = mc.player.getYaw();
        pitch = mc.player.getPitch();
    }

    @Override
    protected void onDisable() {
        mc.worldRenderer.scheduleTerrainUpdate();
    }

    public void setInput(PlayerInput in) { input = in; }

    public void look(double dx, double dy) {
        yaw += (float) (dx * 0.15);
        pitch = MathHelper.clamp(pitch + (float) (dy * 0.15), -90f, 90f);
    }

    @Override
    public void onTick() {
        lastPos = pos;
        double f = (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0);
        double s = (input.left() ? 1 : 0) - (input.right() ? 1 : 0);
        double v = (input.jump() ? 1 : 0) - (input.sneak() ? 1 : 0);
        double r = Math.toRadians(yaw);
        double sp = speed.get() * (input.sprint() ? 2 : 1);
        pos = pos.add((-Math.sin(r) * f + Math.cos(r) * s) * sp, v * sp, (Math.cos(r) * f + Math.sin(r) * s) * sp);
        mc.player.setVelocity(0, mc.player.getVelocity().y, 0);
    }

    public Vec3d cameraPos(float delta) { return lastPos.lerp(pos, delta); }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
}
