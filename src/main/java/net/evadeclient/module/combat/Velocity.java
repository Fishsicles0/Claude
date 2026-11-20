package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.util.math.Vec3d;

/** Reduces knockback from hits and explosions. 0% takes no knockback, 100% is vanilla. */
public class Velocity extends Module {
    private final NumberSetting horizontal = add(new NumberSetting("Horizontal %", 0, 0, 100, 5));
    private final NumberSetting vertical = add(new NumberSetting("Vertical %", 0, 0, 100, 5));

    public Velocity() {
        super("Velocity", "Reduces the knockback you take.", Category.COMBAT);
    }

    /** {@code before} is your motion before the server's knockback, {@code after} is with it. */
    public Vec3d filter(Vec3d before, Vec3d after) {
        double h = horizontal.get() / 100.0, v = vertical.get() / 100.0;
        return new Vec3d(before.x + (after.x - before.x) * h, before.y + (after.y - before.y) * v, before.z + (after.z - before.z) * h);
    }

    @Override
    public String getSuffix() { return horizontal.getInt() + "% " + vertical.getInt() + "%"; }
}
