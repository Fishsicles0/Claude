package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.util.Hand;

/** Releases your bow automatically once it's charged as far as you set, so holding right click fires again and again. */
public class BowSpam extends Module {
    private final NumberSetting charge = add(new NumberSetting("Charge Ticks", 4, 3, 20, 1));

    public BowSpam() {
        super("BowSpam", "Fires your bow automatically while you hold right click.", Category.COMBAT);
    }

    @Override
    public void onTick() {
        if (!mc.player.isUsingItem() || !(mc.player.getActiveItem().getItem() instanceof BowItem)) return;
        if (mc.player.getActiveItem().getItem() instanceof CrossbowItem) return;
        if (mc.player.getItemUseTime() >= charge.getInt()) {
            mc.interactionManager.stopUsingItem(mc.player);
            if (mc.options.useKey.isPressed()) mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        }
    }
}
