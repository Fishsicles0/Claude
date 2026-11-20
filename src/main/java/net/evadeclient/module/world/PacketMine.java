package net.evadeclient.module.world;

import net.evadeclient.mixin.InteractionManagerAccessor;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

/**
 * Remembers one block and keeps breaking it even when you look away, so you can mine and move at the same time.
 * Left-click a block to set it; it finishes on its own and, with Auto Rebind, picks up the next block you hit.
 */
public class PacketMine extends Module {
    private final BooleanSetting rebind = add(new BooleanSetting("Auto Rebind", true));
    private BlockPos target;
    private Direction face;

    public PacketMine() {
        super("PacketMine", "Keeps breaking one block while you look elsewhere.", Category.WORLD);
    }

    /** Point PacketMine at a block (e.g. the one you're looking at). Used by the keybind and tests. */
    public void setTarget(BlockPos pos, Direction side) {
        target = pos;
        face = side;
    }

    @Override
    public void onTick() {
        if (mc.currentScreen != null) return;
        if ((target == null || rebind.get()) && mc.options.attackKey.isPressed()
                && mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            target = hit.getBlockPos();
            face = hit.getSide();
        }
        if (target == null) return;
        if (mc.world.getBlockState(target).isAir()) { target = null; return; } // done
        ((InteractionManagerAccessor) mc.interactionManager).evade$setBlockBreakingCooldown(0);
        Direction d = face == null ? Direction.UP : face;
        if (mc.player.isCreative()) mc.interactionManager.attackBlock(target, d);
        else mc.interactionManager.updateBlockBreakingProgress(target, d);
        mc.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
    }

    @Override
    public void onRender(RenderContext ctx) {
        if (target != null) ctx.boxOutline(new Box(target), 0xFFE11D2E, 2f);
    }

    @Override
    protected void onDisable() { target = null; }

    public BlockPos target() { return target; }
}
