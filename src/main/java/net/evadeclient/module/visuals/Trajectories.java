package net.evadeclient.module.visuals;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.EggItem;
import net.minecraft.item.EnderPearlItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SnowballItem;
import net.minecraft.item.TridentItem;
import net.minecraft.item.WindChargeItem;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/** Draws where your bow, pearl, snowball, trident or wind charge will land. */
public class Trajectories extends Module {
    public Trajectories() { super("Trajectories", "Shows where projectiles will land.", Category.VISUALS); }

    @Override
    public void onRender(RenderContext ctx) {
        ItemStack s = mc.player.getMainHandStack();
        double speed, gravity = 0.03, drag = 0.99;
        if (s.getItem() instanceof BowItem) {
            float p = mc.player.isUsingItem() ? BowItem.getPullProgress(mc.player.getItemUseTime()) : 1f;
            speed = p * 3.0; gravity = 0.05;
        } else if (s.getItem() instanceof CrossbowItem) { speed = 3.15; gravity = 0.05; }
        else if (s.getItem() instanceof TridentItem) { speed = 2.5; gravity = 0.05; }
        else if (s.getItem() instanceof EnderPearlItem || s.getItem() instanceof SnowballItem || s.getItem() instanceof EggItem) speed = 1.5;
        else if (s.getItem() instanceof WindChargeItem) { speed = 1.5; gravity = 0; drag = 1; }
        else return;

        Vec3d pos = mc.player.getCameraPosVec(ctx.tickDelta).add(0, -0.1, 0);
        Vec3d vel = mc.player.getRotationVec(ctx.tickDelta).multiply(speed);
        Vec3d prev = pos;
        for (int i = 0; i < 200; i++) {
            Vec3d next = pos.add(vel);
            BlockHitResult hit = mc.world.raycast(new RaycastContext(pos, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
            if (hit.getType() != HitResult.Type.MISS) {
                ctx.line(prev, hit.getPos(), 0xFFE11D2E, 2f);
                Box b = new Box(hit.getBlockPos());
                ctx.boxOutline(b, 0xFFFF3B4C, 2f);
                return;
            }
            if (i > 0) ctx.line(prev, next, 0xD0FFFFFF, 2f);
            prev = next;
            pos = next;
            vel = vel.multiply(drag).add(0, -gravity, 0);
            if (pos.y < mc.world.getBottomY() - 10) return;
        }
    }
}
