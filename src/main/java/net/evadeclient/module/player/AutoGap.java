package net.evadeclient.module.player;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.item.Items;

/** Eats a golden apple when your health gets low (enchanted ones first if you allow it), then switches back. */
public class AutoGap extends Module {
    private final NumberSetting health = add(new NumberSetting("Health", 12, 2, 19, 1));
    private final BooleanSetting enchanted = add(new BooleanSetting("Prefer Enchanted", true));
    private final BooleanSetting fireRes = add(new BooleanSetting("When On Fire", false));
    private int eatingSlot = -1, prevSlot = -1, grace;

    public AutoGap() {
        super("AutoGap", "Eats a golden apple when your health is low.", Category.PLAYER);
    }

    @Override
    public void onTick() {
        if (eatingSlot != -1) {
            if (grace-- > 0) return;
            if (!mc.player.isUsingItem() || mc.player.getInventory().getSelectedSlot() != eatingSlot) stop();
            return;
        }
        boolean low = mc.player.getHealth() <= health.get() || (fireRes.get() && mc.player.isOnFire());
        if (!low || mc.currentScreen != null || mc.player.isUsingItem()) return;
        int slot = -1;
        for (int i = 0; i < 9; i++) {
            var s = mc.player.getInventory().getStack(i);
            if (s.isOf(Items.ENCHANTED_GOLDEN_APPLE) && (enchanted.get() || slot == -1)) slot = i;
            else if (s.isOf(Items.GOLDEN_APPLE) && (slot == -1 || !enchanted.get())) slot = i;
        }
        if (slot == -1) return;
        prevSlot = mc.player.getInventory().getSelectedSlot();
        eatingSlot = slot;
        grace = 2;
        mc.player.getInventory().setSelectedSlot(slot);
        mc.options.useKey.setPressed(true);
    }

    private void stop() {
        mc.options.useKey.setPressed(false);
        if (prevSlot != -1) mc.player.getInventory().setSelectedSlot(prevSlot);
        eatingSlot = prevSlot = -1;
    }

    @Override
    protected void onDisable() { if (eatingSlot != -1) stop(); }
}
