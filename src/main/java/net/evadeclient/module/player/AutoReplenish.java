package net.evadeclient.module.player;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.InvUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

/**
 * Keeps your hotbar stocked: when a stack runs low (or runs out) it's topped up from the rest of your
 * inventory with the same item, so blocks, pearls and food never run out mid-fight.
 */
public class AutoReplenish extends Module {
    private final NumberSetting threshold = add(new NumberSetting("Refill Below", 8, 1, 63, 1));
    private final NumberSetting delay = add(new NumberSetting("Delay", 2, 0, 10, 1));
    private final ItemStack[] last = new ItemStack[9];
    private int wait;

    public AutoReplenish() {
        super("AutoReplenish", "Refills hotbar stacks from your inventory.", Category.PLAYER);
    }

    @Override
    protected void onEnable() { for (int i = 0; i < 9; i++) last[i] = mc.player.getInventory().getStack(i).copy(); }

    @Override
    public void onTick() {
        if (wait-- > 0) return;
        if (!InvUtil.canClick()) { snapshot(); return; }
        var inv = mc.player.getInventory();
        for (int h = 0; h < 9; h++) {
            ItemStack cur = inv.getStack(h);
            ItemStack ref = cur.isEmpty() ? last[h] : cur;
            if (ref == null || ref.isEmpty() || !ref.isStackable()) continue;
            if (!cur.isEmpty() && cur.getCount() >= Math.min(threshold.getInt(), cur.getMaxCount())) continue;
            int from = -1;
            for (int i = 9; i < 36 && from == -1; i++)
                if (ItemStack.areItemsAndComponentsEqual(inv.getStack(i), ref)) from = i;
            if (from == -1) continue;
            int sync = mc.player.playerScreenHandler.syncId;
            if (cur.isEmpty()) {
                InvUtil.swap(from, h);
            } else {
                // pick up the spare stack, drop it onto the hotbar stack (merges), put any leftover back
                mc.interactionManager.clickSlot(sync, from, 0, SlotActionType.PICKUP, mc.player);
                mc.interactionManager.clickSlot(sync, InvUtil.toHandlerSlot(h), 0, SlotActionType.PICKUP, mc.player);
                if (!mc.player.playerScreenHandler.getCursorStack().isEmpty())
                    mc.interactionManager.clickSlot(sync, from, 0, SlotActionType.PICKUP, mc.player);
            }
            wait = delay.getInt();
            break; // one refill per tick keeps it smooth
        }
        snapshot();
    }

    private void snapshot() {
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (!s.isEmpty()) last[i] = s.copy();
        }
    }
}
