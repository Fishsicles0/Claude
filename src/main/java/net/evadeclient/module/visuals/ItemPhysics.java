package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;

/** Dropped items lie flat on the ground instead of bobbing and spinning. Visual only. */
public class ItemPhysics extends Module {
    public ItemPhysics() {
        super("Item Physics", "Dropped items lie flat on the ground instead of spinning.", Category.VISUALS);
    }

    public static boolean on() {
        if (EvadeClient.modules == null) return false;
        ItemPhysics p = EvadeClient.modules.get(ItemPhysics.class);
        return p != null && p.isEnabled();
    }
}
