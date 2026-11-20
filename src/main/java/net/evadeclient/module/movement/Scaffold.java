package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.BlockUtil;
import net.minecraft.block.Block;
import net.minecraft.block.FallingBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

/** Places blocks under your feet as you walk, so you can bridge over gaps. Tower mode builds straight up with jump. */
public class Scaffold extends Module {
    private final NumberSetting extend = add(new NumberSetting("Extend", 0, 0, 3, 1));
    private final BooleanSetting tower = add(new BooleanSetting("Tower", true));
    private final BooleanSetting safe = add(new BooleanSetting("SafeWalk", true));
    private final BooleanSetting rotate = add(new BooleanSetting("Rotate", true));
    private final BooleanSetting swapBack = add(new BooleanSetting("Swap Back", true));

    public Scaffold() { super("Scaffold", "Places blocks under you as you walk.", Category.MOVEMENT); }

    public boolean safeWalk() { return safe.get(); }

    @Override
    public void onTick() {
        int slot = BlockUtil.findBlockInHotbar(Scaffold::good);
        if (slot == -1) return;
        BlockPos under = BlockPos.ofFloored(mc.player.getX(), mc.player.getY() - 0.5, mc.player.getZ());
        place(under, slot);
        double yaw = Math.toRadians(mc.player.getYaw());
        for (int i = 1; i <= extend.getInt(); i++) {
            place(BlockPos.ofFloored(mc.player.getX() - Math.sin(yaw) * i, mc.player.getY() - 0.5, mc.player.getZ() + Math.cos(yaw) * i), slot);
        }
        if (tower.get() && mc.options.jumpKey.isPressed() && !mc.options.forwardKey.isPressed() && mc.player.getVelocity().y < 0.1
                && !mc.world.getBlockState(under).isReplaceable()) {
            mc.player.setVelocity(0, 0.42, 0);
        }
    }

    private void place(BlockPos pos, int slot) {
        if (!BlockUtil.isReplaceable(pos)) return;
        BlockUtil.place(pos, slot, rotate.get(), swapBack.get());
    }

    private static boolean good(ItemStack s) {
        if (!(s.getItem() instanceof BlockItem bi)) return false;
        Block b = bi.getBlock();
        return !(b instanceof FallingBlock) && b.getDefaultState().isFullCube(net.minecraft.world.EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
    }
}
