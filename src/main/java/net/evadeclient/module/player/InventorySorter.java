package net.evadeclient.module.player;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Sorts your main inventory (not the hotbar) by item type whenever you open it, one move at a time. */
public class InventorySorter extends Module {
    public InventorySorter() { super("InventorySorter", "Sorts your inventory when you open it.", Category.UTILITY); }

    @Override
    public void onTick() {
        if (!(mc.currentScreen instanceof InventoryScreen) || !mc.player.currentScreenHandler.getCursorStack().isEmpty()) return;
        var inv = mc.player.getInventory();
        List<ItemStack> want = new ArrayList<>();
        for (int i = 9; i < 36; i++) want.add(inv.getStack(i));
        Comparator<ItemStack> order = Comparator.comparing((ItemStack s) -> s.isEmpty())
                .thenComparing(s -> Registries.ITEM.getId(s.getItem()).getPath())
                .thenComparing(s -> -s.getCount());
        want.sort(order);
        for (int i = 9; i < 36; i++) {
            ItemStack cur = inv.getStack(i), target = want.get(i - 9);
            if (ItemStack.areEqual(cur, target)) continue;
            // find where the wanted stack currently sits and swap it in
            for (int j = i + 1; j < 36; j++) {
                if (ItemStack.areEqual(inv.getStack(j), target)) {
                    int sync = mc.player.playerScreenHandler.syncId;
                    mc.interactionManager.clickSlot(sync, j, 0, SlotActionType.PICKUP, mc.player);
                    mc.interactionManager.clickSlot(sync, i, 0, SlotActionType.PICKUP, mc.player);
                    if (!mc.player.currentScreenHandler.getCursorStack().isEmpty())
                        mc.interactionManager.clickSlot(sync, j, 0, SlotActionType.PICKUP, mc.player);
                    return; // one swap per tick
                }
            }
            return;
        }
    }
}
