package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Counts a kind of item across your inventory (hotbar, main inventory and offhand; worn armour doesn't count):
 * whatever you're holding, or a chosen category.
 */
public class ItemCounterHud extends TextHudModule {
    private final ModeSetting track = add(new ModeSetting("Track", "Held Item", "Held Item", "Blocks", "Arrows", "Food",
            "Gapples", "Pearls", "Totems", "Fireworks", "XP Bottles", "Potions")
            .describe("Held Item counts exact copies of what's in your hand (same potion, same enchantments)."));
    private final BooleanSetting showIcon = add(new BooleanSetting("Show Icon", true));
    private final BooleanSetting hideWhenZero = add(new BooleanSetting("Hide When Zero", true)
            .describe("Hide the counter while you have none."));

    /** The icon drawn next to the count, picked with the text once a tick. */
    private ItemStack icon = ItemStack.EMPTY;

    public ItemCounterHud() {
        super("Item Counter", "Counts items in your inventory.", 4, 232);
    }

    private Predicate<ItemStack> matcher() {
        return switch (track.get()) {
            case "Blocks" -> s -> s.getItem() instanceof BlockItem;
            case "Arrows" -> s -> s.isOf(Items.ARROW) || s.isOf(Items.SPECTRAL_ARROW) || s.isOf(Items.TIPPED_ARROW);
            case "Food" -> s -> s.contains(DataComponentTypes.FOOD);
            case "Gapples" -> s -> s.isOf(Items.GOLDEN_APPLE) || s.isOf(Items.ENCHANTED_GOLDEN_APPLE);
            case "Pearls" -> s -> s.isOf(Items.ENDER_PEARL);
            case "Totems" -> s -> s.isOf(Items.TOTEM_OF_UNDYING);
            case "Fireworks" -> s -> s.isOf(Items.FIREWORK_ROCKET);
            case "XP Bottles" -> s -> s.isOf(Items.EXPERIENCE_BOTTLE);
            case "Potions" -> s -> s.isOf(Items.POTION) || s.isOf(Items.SPLASH_POTION) || s.isOf(Items.LINGERING_POTION);
            default -> {
                ItemStack held = mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandStack();
                yield s -> !held.isEmpty() && ItemStack.areItemsAndComponentsEqual(s, held);
            }
        };
    }

    /** The item shown for a category when none is in the inventory. */
    private Item fallbackIcon() {
        return switch (track.get()) {
            case "Blocks" -> Items.GRASS_BLOCK;
            case "Arrows" -> Items.ARROW;
            case "Food" -> Items.COOKED_BEEF;
            case "Gapples" -> Items.GOLDEN_APPLE;
            case "Pearls" -> Items.ENDER_PEARL;
            case "Totems" -> Items.TOTEM_OF_UNDYING;
            case "Fireworks" -> Items.FIREWORK_ROCKET;
            case "XP Bottles" -> Items.EXPERIENCE_BOTTLE;
            case "Potions" -> Items.SPLASH_POTION;
            default -> Items.AIR;
        };
    }

    /** Matching items in the hotbar, main inventory and offhand. */
    public int count() {
        if (mc.player == null) return 0;
        Predicate<ItemStack> p = matcher();
        PlayerInventory inv = mc.player.getInventory();
        int n = 0;
        ItemStack first = ItemStack.EMPTY;
        for (ItemStack s : inv.getMainStacks()) {
            if (!s.isEmpty() && p.test(s)) { n += s.getCount(); if (first.isEmpty()) first = s; }
        }
        ItemStack off = inv.getStack(PlayerInventory.OFF_HAND_SLOT);
        if (!off.isEmpty() && p.test(off)) { n += off.getCount(); if (first.isEmpty()) first = off; }
        icon = first.isEmpty() ? new ItemStack(fallbackIcon()) : first.copyWithCount(1);
        return n;
    }

    private String label() {
        if (!track.is("Held Item")) return track.get();
        return mc.player != null && !mc.player.getMainHandStack().isEmpty() ? mc.player.getMainHandStack().getName().getString() : "Item";
    }

    @Override
    protected List<String> lines(boolean preview) {
        int n = count();
        if (preview && n == 0) {
            if (icon.isEmpty()) icon = new ItemStack(track.is("Held Item") ? Items.COBBLESTONE : fallbackIcon());
            return new ArrayList<>(List.of("64x " + (track.is("Held Item") ? "Item" : track.get())));
        }
        if (n == 0 && hideWhenZero.get()) return new ArrayList<>();
        return new ArrayList<>(List.of(n + "x " + label()));
    }

    @Override
    protected int leadingWidth() { return showIcon.get() ? 18 : 0; }

    @Override
    protected int minHeight() { return showIcon.get() ? 20 : 0; }

    @Override
    protected void drawLeading(DrawContext ctx, int x, int y, int h) {
        if (showIcon.get() && !icon.isEmpty()) ctx.drawItem(icon, x, y + (h - 16) / 2);
    }
}
