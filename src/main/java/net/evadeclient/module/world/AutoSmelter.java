package net.evadeclient.module.world;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.gui.screen.ingame.AbstractFurnaceScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Set;

/**
 * While a furnace, smoker or blast furnace is open: takes out what's done, tops up fuel and loads
 * ores, raw food, sand, cobblestone, logs and clay from your inventory.
 */
public class AutoSmelter extends Module {
    private static final Set<Item> SMELTABLE = Set.of(
            Items.RAW_IRON, Items.RAW_GOLD, Items.RAW_COPPER, Items.IRON_ORE, Items.GOLD_ORE, Items.COPPER_ORE,
            Items.DEEPSLATE_IRON_ORE, Items.DEEPSLATE_GOLD_ORE, Items.DEEPSLATE_COPPER_ORE, Items.ANCIENT_DEBRIS,
            Items.BEEF, Items.PORKCHOP, Items.CHICKEN, Items.MUTTON, Items.RABBIT, Items.COD, Items.SALMON, Items.POTATO, Items.KELP,
            Items.SAND, Items.RED_SAND, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.CLAY_BALL, Items.NETHERRACK,
            Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG, Items.JUNGLE_LOG, Items.ACACIA_LOG, Items.DARK_OAK_LOG,
            Items.MANGROVE_LOG, Items.CHERRY_LOG, Items.CACTUS, Items.WET_SPONGE);
    private final NumberSetting delay = add(new NumberSetting("Delay", 3, 1, 20, 1));
    private int wait;

    public AutoSmelter() {
        super("AutoSmelter", "Fills and empties furnaces for you.", Category.WORLD);
    }

    @Override
    public void onTick() {
        if (!(mc.currentScreen instanceof AbstractFurnaceScreen<?>)
                || !(mc.player.currentScreenHandler instanceof AbstractFurnaceScreenHandler h)) return;
        if (wait-- > 0 || !h.getCursorStack().isEmpty()) return;
        wait = delay.getInt();
        if (!h.getSlot(2).getStack().isEmpty()) { click(h, 2); return; } // take the output
        if (h.getSlot(1).getStack().isEmpty()) {
            int fuel = find(h, s -> mc.world.getFuelRegistry().isFuel(s) && !SMELTABLE.contains(s.getItem()) && !s.isOf(Items.BUCKET));
            if (fuel != -1) { move(h, fuel, 1); return; }
        }
        if (h.getSlot(0).getStack().isEmpty()) {
            int in = find(h, s -> SMELTABLE.contains(s.getItem()));
            if (in != -1) move(h, in, 0);
        }
    }

    private int find(AbstractFurnaceScreenHandler h, java.util.function.Predicate<ItemStack> p) {
        for (int i = 3; i < h.slots.size(); i++) if (p.test(h.getSlot(i).getStack())) return i;
        return -1;
    }

    private void click(AbstractFurnaceScreenHandler h, int slot) {
        mc.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
    }

    /** Pick up from the player inventory and drop into the furnace slot (exact, unlike shift-click). */
    private void move(AbstractFurnaceScreenHandler h, int from, int to) {
        mc.interactionManager.clickSlot(h.syncId, from, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(h.syncId, to, 0, SlotActionType.PICKUP, mc.player);
        if (!h.getCursorStack().isEmpty()) mc.interactionManager.clickSlot(h.syncId, from, 0, SlotActionType.PICKUP, mc.player);
    }
}
