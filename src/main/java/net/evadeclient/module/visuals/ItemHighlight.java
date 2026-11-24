package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ItemListSetting;
import net.evadeclient.setting.ModeSetting;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;

/**
 * Tints the slots that hold items you choose, in any inventory or container you have open, so they stand out at a
 * glance. Only what is already on your screen: nothing is searched for, counted or moved.
 *
 * <p>Hook: ItemHighlightSlotMixin.
 */
public class ItemHighlight extends Module {
    private final ItemListSetting items = add(new ItemListSetting("Items",
            "minecraft:totem_of_undying,minecraft:enchanted_golden_apple,minecraft:ender_pearl", "click to pick")
            .describe("The items whose slots are highlighted. Click to pick them from a searchable grid."));
    private final ColorSetting color = add(new ColorSetting("Color", 0x80FFD21F)
            .describe("Colour of the highlight (its transparency too)."));
    private final ModeSetting style = add(new ModeSetting("Style", "Fill + Outline", "Fill", "Outline", "Fill + Outline")
            .describe("Fill the slot behind the item, outline it, or both."));

    public ItemHighlight() {
        super("Item Highlight", "Highlights slots holding the items you choose in open inventories and chests.", Category.VISUALS);
    }

    public static ItemHighlight active() {
        if (EvadeClient.modules == null) return null;
        ItemHighlight h = EvadeClient.modules.get(ItemHighlight.class);
        return h != null && h.isEnabled() ? h : null;
    }

    /** Whether {@code stack} is one of the chosen items. */
    public boolean highlights(ItemStack stack) {
        return stack != null && !stack.isEmpty() && items.contains(stack.getItem());
    }

    /** From ItemHighlightSlotMixin before a slot's item is drawn: the highlight behind it. */
    public static void drawSlot(DrawContext ctx, ItemStack stack, int x, int y) {
        ItemHighlight h = active();
        if (h == null || !h.highlights(stack)) return;
        int c = h.color.get();
        if (!h.style.is("Outline")) ctx.fill(x, y, x + 16, y + 16, c);
        if (!h.style.is("Fill")) {
            int edge = c | 0xFF000000;
            ctx.fill(x, y, x + 16, y + 1, edge);
            ctx.fill(x, y + 15, x + 16, y + 16, edge);
            ctx.fill(x, y + 1, x + 1, y + 15, edge);
            ctx.fill(x + 15, y + 1, x + 16, y + 15, edge);
        }
    }

    /** The items setting (for the tests). */
    public ItemListSetting itemsSetting() { return items; }
}
