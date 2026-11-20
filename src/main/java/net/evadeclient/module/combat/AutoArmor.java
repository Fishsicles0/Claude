package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.InvUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

/** Puts on the best armour in your inventory, one piece per action, with an option to keep an elytra on. */
public class AutoArmor extends Module {
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final NumberSetting delay = add(new NumberSetting("Delay Ticks", 2, 0, 10, 1));
    private final BooleanSetting keepElytra = add(new BooleanSetting("Keep Elytra", true));
    private final BooleanSetting ignoreBinding = add(new BooleanSetting("Skip Curse of Binding", true));

    private int wait;

    public AutoArmor() {
        super("AutoArmor", "Equips the best armour you have.", Category.UTILITY);
    }

    @Override
    public void onTick() {
        if (wait-- > 0 || !InvUtil.canClick()) return;
        for (EquipmentSlot slot : SLOTS) {
            ItemStack worn = mc.player.getEquippedStack(slot);
            if (slot == EquipmentSlot.CHEST && keepElytra.get() && worn.isOf(Items.ELYTRA)) continue;
            double wornScore = score(worn, slot);
            int best = -1;
            double bestScore = wornScore;
            for (int i = 0; i < 36; i++) {
                ItemStack s = mc.player.getInventory().getStack(i);
                double sc = score(s, slot);
                if (sc > bestScore) { bestScore = sc; best = i; }
            }
            if (best == -1) continue;
            int handler = InvUtil.toHandlerSlot(best);
            int armorHandler = switch (slot) { case HEAD -> 5; case CHEST -> 6; case LEGS -> 7; default -> 8; };
            int sync = mc.player.playerScreenHandler.syncId;
            // pick up the new piece, swap it into the armour slot, put the old piece where the new one was
            mc.interactionManager.clickSlot(sync, handler, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(sync, armorHandler, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(sync, handler, 0, SlotActionType.PICKUP, mc.player);
            wait = delay.getInt();
            return;
        }
    }

    private double score(ItemStack s, EquipmentSlot slot) {
        if (s.isEmpty()) return 0;
        EquippableComponent eq = s.get(DataComponentTypes.EQUIPPABLE);
        if (eq == null || eq.slot() != slot) return 0;
        if (ignoreBinding.get() && hasEnchant(s, Enchantments.BINDING_CURSE)) return -1;
        double armor = 0, tough = 0;
        AttributeModifiersComponent mods = s.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT);
        for (var e : mods.modifiers()) {
            if (e.attribute().equals(EntityAttributes.ARMOR)) armor += e.modifier().value();
            if (e.attribute().equals(EntityAttributes.ARMOR_TOUGHNESS)) tough += e.modifier().value();
        }
        int prot = level(s, Enchantments.PROTECTION);
        // tiny durability term so a fresh piece beats a nearly broken identical one
        double dur = s.getMaxDamage() > 0 ? 1 - s.getDamage() / (double) s.getMaxDamage() : 1;
        return armor * 10 + tough * 4 + prot * 3 + dur * 0.5 + 0.01;
    }

    private static boolean hasEnchant(ItemStack s, net.minecraft.registry.RegistryKey<net.minecraft.enchantment.Enchantment> key) {
        return level(s, key) > 0;
    }

    private static int level(ItemStack s, net.minecraft.registry.RegistryKey<net.minecraft.enchantment.Enchantment> key) {
        for (var e : s.getEnchantments().getEnchantmentEntries()) if (e.getKey().matchesKey(key)) return e.getIntValue();
        return 0;
    }
}
