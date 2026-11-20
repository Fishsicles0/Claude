package net.evadeclient.module.player;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.item.BlockItem;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

/** Place blocks in mid-air where you're looking, with no block behind them. Shows where the block will go. */
public class AirPlace extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 4, 1, 5, 0.5));
    private boolean wasDown;

    public AirPlace() {
        super("AirPlace", "Place blocks in mid-air.", Category.PLAYER);
    }

    public BlockPos target() {
        if (mc.crosshairTarget != null && mc.crosshairTarget.getType() != HitResult.Type.MISS) return null;
        if (!(mc.player.getMainHandStack().getItem() instanceof BlockItem)) return null;
        BlockPos p = BlockPos.ofFloored(mc.player.getEyePos().add(mc.player.getRotationVec(1f).multiply(range.get())));
        return mc.world.getBlockState(p).isReplaceable() ? p : null;
    }

    @Override
    public void onTick() {
        boolean down = mc.options.useKey.isPressed() && mc.currentScreen == null;
        if (down && !wasDown) place();
        wasDown = down;
    }

    public boolean place() {
        BlockPos p = target();
        if (p == null) return false;
        var res = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(p.toCenterPos(), Direction.UP, p, false));
        if (res.isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);
        return res.isAccepted();
    }

    @Override
    public void onRender(RenderContext ctx) {
        BlockPos p = target();
        if (p != null) ctx.boxOutline(new Box(p), 0xC0FF3B4C, 1.5f);
    }
}
