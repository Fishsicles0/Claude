package net.evadeclient.module.player;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.mixin.FishingBobberAccessor;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.item.FishingRodItem;
import net.minecraft.util.Hand;

/** Reels in when a fish bites and casts again. */
public class AutoFish extends Module {
    private final NumberSetting recastDelay = add(new NumberSetting("Recast Delay", 15, 5, 60, 1));
    private int recast = -1;

    public AutoFish() { super("AutoFish", "Catches fish automatically.", Category.PLAYER); }

    @Override
    public void onTick() {
        if (!(mc.player.getMainHandStack().getItem() instanceof FishingRodItem)) return;
        var bobber = mc.player.fishHook;
        if (bobber != null && ((FishingBobberAccessor) bobber).evade$caughtFish()) {
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND); // reel in
            recast = recastDelay.getInt();
            return;
        }
        if (recast > 0 && --recast == 0 && mc.player.fishHook == null) {
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND); // cast again
        }
    }
}
