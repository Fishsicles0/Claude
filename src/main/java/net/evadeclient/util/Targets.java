package net.evadeclient.util;

import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MaceItem;
import net.minecraft.item.TridentItem;
import net.minecraft.registry.tag.ItemTags;

/** Small shared item helper (kept for the HUD's weapon/cooldown readouts). */
public final class Targets {
    private Targets() {}

    public static boolean isWeapon(ItemStack s) {
        return s.isIn(ItemTags.SWORDS) || s.getItem() instanceof AxeItem || s.getItem() instanceof MaceItem
                || s.getItem() instanceof TridentItem || s.isIn(ItemTags.SPEARS);
    }
}
