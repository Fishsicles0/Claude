package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Targets;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** Makes targets' hitboxes bigger for your crosshair, so hits register more easily. */
public class Hitbox extends Module {
    private final NumberSetting expand = add(new NumberSetting("Expand", 0.25, 0.05, 1.5, 0.05));
    private final BooleanSetting show = add(new BooleanSetting("Show Boxes", false));
    private final Targets targets = new Targets();

    public Hitbox() {
        super("Hitbox", "Expands enemy hitboxes.", Category.COMBAT);
        targets.addTo(this::add);
    }

    public boolean applies(Entity e) { return targets.isValid(e); }

    public float expansion() { return expand.getFloat(); }

    @Override
    public void onRender(RenderContext ctx) {
        if (!show.get()) return;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (!applies(p) || mc.player.squaredDistanceTo(p) > 64 * 64) continue;
            Vec3d pos = p.getLerpedPos(ctx.tickDelta);
            Box box = p.getBoundingBox().offset(pos.subtract(p.getEntityPos())).expand(expansion());
            ctx.boxOutline(box, 0x90FFFFFF, 1.5f);
        }
    }

    @Override
    public String getSuffix() { return String.format("%.2f", expand.get()); }
}
