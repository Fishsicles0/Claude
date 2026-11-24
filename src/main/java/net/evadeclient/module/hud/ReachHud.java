package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.util.Theme;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Distance of your last hit, measured eye-to-hitbox like the server does. Dims once the hit is a few seconds old. */
public class ReachHud extends TextHudModule {
    private static final long FRESH_MS = 3000;

    private double last = -1;
    private long when;

    public ReachHud() { super("Reach Display", "Distance of your last hit.", 4, 152); }

    @Override
    public void onAttack(Entity target) {
        Vec3d eye = mc.player.getEyePos();
        var box = target.getBoundingBox();
        double x = Math.max(box.minX, Math.min(eye.x, box.maxX));
        double y = Math.max(box.minY, Math.min(eye.y, box.maxY));
        double z = Math.max(box.minZ, Math.min(eye.z, box.maxZ));
        last = Math.sqrt(eye.squaredDistanceTo(x, y, z));
        when = System.currentTimeMillis();
    }

    private boolean stale() { return last < 0 || System.currentTimeMillis() - when > FRESH_MS; }

    @Override
    protected List<String> lines(boolean preview) {
        if (last < 0) return new ArrayList<>(List.of(preview ? "3.00 blocks" : "-.-- blocks"));
        return new ArrayList<>(List.of(String.format(Locale.ROOT, "%.2f blocks", last)));
    }

    @Override
    protected int lineColor(int index, int base) {
        return stale() ? Theme.alpha(base, 0.5f) : base;
    }
}
