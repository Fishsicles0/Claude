package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adds a full contents summary to shulker boxes, plus durability numbers and food values to tooltips. */
public class BetterTooltips extends Module {
    private final BooleanSetting shulkers = add(new BooleanSetting("Shulker Contents", true));
    private final BooleanSetting durability = add(new BooleanSetting("Durability", true));
    private final BooleanSetting food = add(new BooleanSetting("Food Values", true));

    public BetterTooltips() {
        super("BetterTooltips", "Shulker contents, durability and food values in tooltips.", Category.VISUALS);
        setEnabled(true);
    }

    /** Registered once from EvadeClient with Fabric's tooltip event. */
    public static void append(ItemStack stack, List<Text> lines) {
        if (EvadeClient.modules == null) return;
        BetterTooltips bt = EvadeClient.modules.get(BetterTooltips.class);
        if (bt == null || !bt.isEnabled()) return;
        bt.lines(stack, lines);
    }

    public void lines(ItemStack stack, List<Text> lines) {
        ContainerComponent c = stack.get(DataComponentTypes.CONTAINER);
        if (shulkers.get() && c != null) {
            Map<Item, Integer> totals = new LinkedHashMap<>();
            Map<Item, Text> names = new LinkedHashMap<>();
            int stacks = 0;
            for (ItemStack s : c.iterateNonEmpty()) {
                totals.merge(s.getItem(), s.getCount(), Integer::sum);
                names.putIfAbsent(s.getItem(), s.getName());
                stacks++;
            }
            if (stacks > 0) {
                lines.add(Text.literal("Contents (" + stacks + "/27 slots):").formatted(Formatting.RED));
                totals.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(e ->
                        lines.add(Text.literal("  " + e.getValue() + "x ").formatted(Formatting.GRAY).append(names.get(e.getKey()).copy().formatted(Formatting.WHITE))));
            }
        }
        if (durability.get() && stack.isDamageable()) {
            int left = stack.getMaxDamage() - stack.getDamage();
            float pct = left / (float) stack.getMaxDamage();
            Formatting col = pct > 0.5f ? Formatting.GREEN : pct > 0.2f ? Formatting.YELLOW : Formatting.RED;
            lines.add(Text.literal("Durability: ").formatted(Formatting.GRAY).append(Text.literal(left + "/" + stack.getMaxDamage()).formatted(col)));
        }
        FoodComponent f = stack.get(DataComponentTypes.FOOD);
        if (food.get() && f != null) {
            lines.add(Text.literal("Food: " + f.nutrition() + "  Saturation: " + String.format("%.1f", f.saturation())).formatted(Formatting.GRAY));
        }
    }
}
