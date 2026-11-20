package net.evadeclient.module.player;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.InvUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/** Eats the best safe food in your hotbar when you get hungry, then switches back. */
public class AutoEat extends Module {
    private final NumberSetting hunger = add(new NumberSetting("Eat Below Hunger", 16, 1, 19, 1));
    private final BooleanSetting gapples = add(new BooleanSetting("Allow Gapples", false));
    private final BooleanSetting pauseCombat = add(new BooleanSetting("Pause When Attacking", true));

    private int eatingSlot = -1, prevSlot = -1;

    public AutoEat() { super("AutoEat", "Eats food automatically when hungry.", Category.UTILITY); }

    @Override
    public void onTick() {
        if (eatingSlot != -1) {
            if (!mc.player.isUsingItem() || mc.player.getInventory().getSelectedSlot() != eatingSlot) stop();
            return;
        }
        if (mc.player.getHungerManager().getFoodLevel() > hunger.get() || mc.currentScreen != null) return;
        if (pauseCombat.get() && mc.options.attackKey.isPressed()) return;
        int best = -1, bestHunger = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            FoodComponent f = s.get(DataComponentTypes.FOOD);
            if (f == null || !safe(s)) continue;
            if (f.nutrition() > bestHunger) { best = i; bestHunger = f.nutrition(); }
        }
        if (best == -1) return;
        prevSlot = mc.player.getInventory().getSelectedSlot();
        eatingSlot = best;
        mc.player.getInventory().setSelectedSlot(best);
        mc.options.useKey.setPressed(true);
    }

    private void stop() {
        mc.options.useKey.setPressed(false);
        if (prevSlot != -1) mc.player.getInventory().setSelectedSlot(prevSlot);
        eatingSlot = prevSlot = -1;
    }

    private boolean safe(ItemStack s) {
        if (s.isOf(Items.ROTTEN_FLESH) || s.isOf(Items.SPIDER_EYE) || s.isOf(Items.POISONOUS_POTATO) || s.isOf(Items.PUFFERFISH)
                || s.isOf(Items.CHORUS_FRUIT) || s.isOf(Items.SUSPICIOUS_STEW) || s.isOf(Items.CHICKEN)) return false;
        return gapples.get() || !(s.isOf(Items.GOLDEN_APPLE) || s.isOf(Items.ENCHANTED_GOLDEN_APPLE));
    }

    @Override
    protected void onDisable() { if (eatingSlot != -1) stop(); }

    public boolean eating() { return isEnabled() && eatingSlot != -1; }
}
