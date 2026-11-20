package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.ModeSetting;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;

/** Switches to the hotbar weapon that hits hardest (or an axe against shields) right before each attack. */
public class AutoWeapon extends Module {
    private final ModeSetting prefer = add(new ModeSetting("Prefer", "Damage", "Damage", "Sword", "Axe"));

    public AutoWeapon() {
        super("AutoWeapon", "Switches to your best weapon when you attack.", Category.COMBAT);
    }

    @Override
    public void onAttack(Entity target) {
        boolean shield = target instanceof LivingEntity le && le.isBlocking();
        int best = -1;
        double bestScore = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            double score = damage(s);
            if (score <= 1) continue;
            boolean axe = s.getItem() instanceof AxeItem;
            if (shield && axe) score += 100; // axes disable shields
            if (prefer.is("Sword") && s.isIn(ItemTags.SWORDS)) score += 50;
            if (prefer.is("Axe") && axe) score += 50;
            if (score > bestScore) { bestScore = score; best = i; }
        }
        if (best != -1) mc.player.getInventory().setSelectedSlot(best);
    }

    /** Attack damage the item adds in the main hand (1 = bare hand). */
    public static double damage(ItemStack s) {
        AttributeModifiersComponent mods = s.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT);
        double d = 1;
        for (var e : mods.modifiers()) if (e.attribute().equals(EntityAttributes.ATTACK_DAMAGE)) d += e.modifier().value();
        return d;
    }
}
