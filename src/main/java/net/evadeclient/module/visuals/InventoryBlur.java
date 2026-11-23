package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.gui.screen.ingame.HandledScreen;

/**
 * Blurs the world behind inventories and chests (vanilla only blurs other menus). Uses vanilla's own menu blur, at
 * this module's strength while a container screen is open, so it works even with the game's Menu Background Blur at 0.
 */
public class InventoryBlur extends Module {
    public final NumberSetting strength = add(new NumberSetting("Strength", 5, 1, 10, 1)
            .describe("How blurry the world behind inventories gets, on the same scale as the game's Menu Background Blur."));

    public InventoryBlur() {
        super("Inventory Blur", "Blurs the background behind open inventories.", Category.VISUALS);
    }

    public static InventoryBlur active() {
        if (EvadeClient.modules == null) return null;
        InventoryBlur b = EvadeClient.modules.get(InventoryBlur.class);
        return b != null && b.isEnabled() ? b : null;
    }

    public static boolean on() { return active() != null; }

    /**
     * The blur radius the frame's shaders get (GameRendererBlurMixin): this module's strength while an inventory or
     * container is open, otherwise the game's own Menu Background Blur ({@code vanilla}).
     */
    public static int blurRadius(int vanilla) {
        InventoryBlur b = active();
        if (b == null || !(mc.currentScreen instanceof HandledScreen<?>) || mc.world == null) return vanilla;
        return b.strength.getInt();
    }
}
