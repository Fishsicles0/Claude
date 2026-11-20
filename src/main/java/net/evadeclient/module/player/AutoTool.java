package net.evadeclient.module.player;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;

/** Switches to the fastest tool in your hotbar while you mine, and back when you stop. */
public class AutoTool extends Module {
    private final BooleanSetting swapBack = add(new BooleanSetting("Swap Back", true));
    private final BooleanSetting saveTools = add(new BooleanSetting("Avoid Breaking Tools", true));
    private int prev = -1;

    public AutoTool() { super("AutoTool", "Picks the best tool while mining.", Category.UTILITY); }

    @Override
    public void onTick() {
        boolean mining = mc.options.attackKey.isPressed() && mc.crosshairTarget instanceof BlockHitResult b && !mc.world.getBlockState(b.getBlockPos()).isAir();
        if (!mining) {
            if (swapBack.get() && prev != -1) mc.player.getInventory().setSelectedSlot(prev);
            prev = -1;
            return;
        }
        var state = mc.world.getBlockState(((BlockHitResult) mc.crosshairTarget).getBlockPos());
        int best = mc.player.getInventory().getSelectedSlot();
        float bestSpeed = speed(mc.player.getInventory().getStack(best), state);
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (saveTools.get() && s.isDamageable() && s.getMaxDamage() - s.getDamage() < 10) continue;
            float sp = speed(s, state);
            if (sp > bestSpeed) { bestSpeed = sp; best = i; }
        }
        int cur = mc.player.getInventory().getSelectedSlot();
        if (best != cur) {
            if (prev == -1) prev = cur;
            mc.player.getInventory().setSelectedSlot(best);
        }
    }

    private static float speed(ItemStack s, net.minecraft.block.BlockState state) {
        float base = s.getMiningSpeedMultiplier(state);
        return state.isToolRequired() && !s.isSuitableFor(state) ? base * 0.01f : base;
    }
}
