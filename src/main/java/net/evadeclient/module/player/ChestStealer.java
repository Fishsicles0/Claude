package net.evadeclient.module.player;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.screen.slot.SlotActionType;

/** Takes everything out of a chest, barrel or shulker box when you open it, then closes it. */
public class ChestStealer extends Module {
    private final NumberSetting delay = add(new NumberSetting("Delay Ticks", 1, 0, 10, 1));
    private final BooleanSetting autoClose = add(new BooleanSetting("Close When Empty", true));
    private int wait;

    public ChestStealer() { super("ChestStealer", "Empties containers into your inventory.", Category.UTILITY); }

    @Override
    public void onTick() {
        if (!(mc.currentScreen instanceof GenericContainerScreen || mc.currentScreen instanceof ShulkerBoxScreen)) return;
        if (wait-- > 0) return;
        var handler = ((HandledScreen<?>) mc.currentScreen).getScreenHandler();
        int containerSlots = handler.slots.size() - 36;
        for (int i = 0; i < containerSlots; i++) {
            if (!handler.getSlot(i).hasStack()) continue;
            mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
            wait = delay.getInt();
            return;
        }
        if (autoClose.get()) mc.player.closeHandledScreen();
    }
}
