package net.evadeclient.module.world;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.util.Hand;

import java.util.HashMap;
import java.util.Map;

/** Feeds every grown animal near you its breeding food from your hotbar. Waits out the 5-minute breeding cooldown. */
public class AutoBreed extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 4, 2, 5, 0.5));
    private final NumberSetting delay = add(new NumberSetting("Delay", 4, 1, 20, 1));
    private final Map<Integer, Long> fed = new HashMap<>();
    private int wait;

    public AutoBreed() {
        super("AutoBreed", "Feeds nearby animals so they breed.", Category.WORLD);
    }

    @Override
    public void onTick() {
        if (wait-- > 0 || mc.currentScreen != null) return;
        long now = System.currentTimeMillis();
        fed.values().removeIf(t -> now - t > 305_000);
        double r = range.get() * range.get();
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof AnimalEntity a) || a.isBaby() || a.isInLove() || fed.containsKey(a.getId())) continue;
            if (mc.player.squaredDistanceTo(a) > r) continue;
            int slot = -1;
            for (int i = 0; i < 9 && slot == -1; i++) if (a.isBreedingItem(mc.player.getInventory().getStack(i))) slot = i;
            if (slot == -1) continue;
            int prev = mc.player.getInventory().getSelectedSlot();
            mc.player.getInventory().setSelectedSlot(slot);
            mc.interactionManager.interactEntity(mc.player, a, Hand.MAIN_HAND);
            mc.player.getInventory().setSelectedSlot(prev);
            fed.put(a.getId(), now);
            wait = delay.getInt();
            return;
        }
    }

    public int fedCount() { return fed.size(); }
}
