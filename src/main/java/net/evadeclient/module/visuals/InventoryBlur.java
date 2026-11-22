package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;

/** Blurs the world behind inventories and chests. */
public class InventoryBlur extends Module {
    public InventoryBlur() {
        super("Inventory Blur", "Blurs the background behind open inventories.", Category.VISUALS);
    }

    public static boolean on() {
        if (EvadeClient.modules == null) return false;
        InventoryBlur b = EvadeClient.modules.get(InventoryBlur.class);
        return b != null && b.isEnabled();
    }
}
