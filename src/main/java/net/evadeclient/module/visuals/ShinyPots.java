package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;

/** Gives potions (and tipped arrows) the enchantment shine again, so they stand out in your hotbar. */
public class ShinyPots extends Module {
    public ShinyPots() {
        super("ShinyPots", "Makes potions glint in your inventory and hotbar.", Category.VISUALS);
    }

    public static boolean on() {
        if (EvadeClient.modules == null) return false;
        ShinyPots s = EvadeClient.modules.get(ShinyPots.class);
        return s != null && s.isEnabled();
    }
}
