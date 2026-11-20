package net.evadeclient.module.player;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.util.InvUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

/** One press swaps your chestplate for an elytra (or back again), then turns itself off. Give it a keybind. */
public class ChestSwap extends Module {
    public ChestSwap() {
        super("ChestSwap", "Swaps between your chestplate and elytra.", Category.UTILITY);
    }

    @Override
    protected void onEnable() {
        swap();
        setEnabled(false);
    }

    public boolean swap() {
        if (!InvUtil.canClick()) return false;
        boolean wearingElytra = mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
        int best = -1;
        double bestScore = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            double score;
            if (wearingElytra) {
                var eq = s.get(DataComponentTypes.EQUIPPABLE);
                if (eq == null || eq.slot() != EquipmentSlot.CHEST || s.isOf(Items.ELYTRA)) continue;
                score = armor(s);
            } else {
                if (!s.isOf(Items.ELYTRA)) continue;
                score = s.getMaxDamage() - s.getDamage();
            }
            if (score > bestScore) { bestScore = score; best = i; }
        }
        if (best == -1) return false;
        int sync = mc.player.playerScreenHandler.syncId, from = InvUtil.toHandlerSlot(best);
        mc.interactionManager.clickSlot(sync, from, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(sync, 6, 0, SlotActionType.PICKUP, mc.player); // chest armour slot
        mc.interactionManager.clickSlot(sync, from, 0, SlotActionType.PICKUP, mc.player);
        return true;
    }

    private static double armor(ItemStack s) {
        double a = 0;
        var mods = s.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS, net.minecraft.component.type.AttributeModifiersComponent.DEFAULT);
        for (var e : mods.modifiers()) {
            if (e.attribute().equals(net.minecraft.entity.attribute.EntityAttributes.ARMOR)) a += e.modifier().value() * 10;
            if (e.attribute().equals(net.minecraft.entity.attribute.EntityAttributes.ARMOR_TOUGHNESS)) a += e.modifier().value() * 4;
        }
        return a + 0.01;
    }
}
