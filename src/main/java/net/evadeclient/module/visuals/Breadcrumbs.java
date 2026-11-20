package net.evadeclient.module.visuals;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;

/** Leaves a red trail behind you so you can find your way back. Cleared when you change world. */
public class Breadcrumbs extends Module {
    private final NumberSetting length = add(new NumberSetting("Length", 1000, 50, 5000, 50));
    private final NumberSetting spacing = add(new NumberSetting("Spacing", 0.5, 0.1, 4, 0.1));
    private final Deque<Vec3d> points = new ArrayDeque<>();
    private Object world;

    public Breadcrumbs() {
        super("Breadcrumbs", "Draws a trail where you've been.", Category.VISUALS);
    }

    @Override
    public void onTick() {
        if (world != mc.world) { points.clear(); world = mc.world; }
        Vec3d here = mc.player.getEntityPos().add(0, 0.1, 0);
        if (points.isEmpty() || points.peekLast().squaredDistanceTo(here) >= spacing.get() * spacing.get()) points.addLast(here);
        while (points.size() > length.getInt()) points.removeFirst();
    }

    @Override
    public void onRender(RenderContext ctx) {
        Vec3d prev = null;
        int i = 0, n = points.size();
        for (Vec3d p : points) {
            if (prev != null && prev.squaredDistanceTo(p) < 64) { // skip teleports
                int alpha = 60 + (int) (195.0 * i / Math.max(1, n));
                ctx.line(prev, p, (alpha << 24) | 0xE11D2E, 2f);
            }
            prev = p;
            i++;
        }
    }

    public int size() { return points.size(); }

    @Override
    protected void onDisable() { points.clear(); }
}
