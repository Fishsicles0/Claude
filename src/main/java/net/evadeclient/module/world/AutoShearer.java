package net.evadeclient.module.world;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;

/** Shears every woolly sheep in reach with the shears from your hotbar. */
public class AutoShearer extends Module {
    private final NumberSetting delay = add(new NumberSetting("Delay", 3, 1, 20, 1));
    private int wait;

    public AutoShearer() {
        super("AutoShearer", "Shears nearby sheep automatically.", Category.WORLD);
    }

    @Override
    public void onTick() {
        if (wait-- > 0 || mc.currentScreen != null) return;
        int slot = -1;
        for (int i = 0; i < 9 && slot == -1; i++) if (mc.player.getInventory().getStack(i).isOf(Items.SHEARS)) slot = i;
        if (slot == -1) return;
        double r = Math.pow(mc.player.getEntityInteractionRange(), 2);
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof SheepEntity s) || !s.isShearable() || mc.player.squaredDistanceTo(s) > r) continue;
            int prev = mc.player.getInventory().getSelectedSlot();
            mc.player.getInventory().setSelectedSlot(slot);
            mc.interactionManager.interactEntity(mc.player, s, Hand.MAIN_HAND);
            mc.player.getInventory().setSelectedSlot(prev);
            wait = delay.getInt();
            return;
        }
    }
}
