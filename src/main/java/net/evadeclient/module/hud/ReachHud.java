package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Distance of your last hit, measured eye-to-hitbox like the server does. */
public class ReachHud extends TextHudModule {
    private double last = -1;
    private long when;

    public ReachHud() { super("Reach Display", "Distance of your last hit.", 4, 264); }

    @Override
    public void onAttack(Entity target) {
        Vec3d eye = mc.player.getEyePos();
        var box = target.getBoundingBox();
        double x = Math.max(box.minX, Math.min(eye.x, box.maxX));
        double y = Math.max(box.minY, Math.min(eye.y, box.maxY));
        double z = Math.max(box.minZ, Math.min(eye.z, box.maxZ));
        last = eye.distanceTo(new Vec3d(x, y, z));
        when = System.currentTimeMillis();
    }

    @Override
    protected List<String> lines(boolean preview) {
        if (last < 0 || System.currentTimeMillis() - when > 3000) return new ArrayList<>(List.of("Hasn't attacked"));
        return new ArrayList<>(List.of(String.format("%.2f blocks", last)));
    }
}
