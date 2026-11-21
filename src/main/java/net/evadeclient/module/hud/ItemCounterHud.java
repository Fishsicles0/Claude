package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.ModeSetting;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Counts a kind of item across your inventory: whatever you're holding, or a chosen category. */
public class ItemCounterHud extends TextHudModule {
    private final ModeSetting track = add(new ModeSetting("Track", "Held Item", "Held Item", "Blocks", "Arrows", "Food", "Gapples", "Pearls"));

    public ItemCounterHud() {
        super("Item Counter", "Counts items in your inventory.", -4, 160);
    }

    private Predicate<ItemStack> matcher() {
        return switch (track.get()) {
            case "Blocks" -> s -> s.getItem() instanceof BlockItem;
            case "Arrows" -> s -> s.isOf(Items.ARROW) || s.isOf(Items.SPECTRAL_ARROW) || s.isOf(Items.TIPPED_ARROW);
            case "Food" -> s -> s.contains(DataComponentTypes.FOOD);
            case "Gapples" -> s -> s.isOf(Items.GOLDEN_APPLE) || s.isOf(Items.ENCHANTED_GOLDEN_APPLE);
            case "Pearls" -> s -> s.isOf(Items.ENDER_PEARL);
            default -> {
                ItemStack held = mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandStack();
                yield s -> !held.isEmpty() && s.isOf(held.getItem());
            }
        };
    }

    public int count() {
        if (mc.player == null) return 0;
        Predicate<ItemStack> p = matcher();
        int n = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.size(); i++) if (p.test(inv.getStack(i))) n += inv.getStack(i).getCount();
        return n;
    }

    @Override
    protected List<String> lines(boolean preview) {
        if (preview) return new ArrayList<>(List.of("64x Blocks"));
        String label = track.get().equals("Held Item")
                ? (mc.player != null && !mc.player.getMainHandStack().isEmpty() ? mc.player.getMainHandStack().getName().getString() : "Item")
                : track.get();
        return new ArrayList<>(List.of(count() + "x " + label));
    }
}
