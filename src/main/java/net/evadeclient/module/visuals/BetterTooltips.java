package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.TooltipDisplayComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Adds a full contents summary to shulker boxes and other containers, plus durability numbers and food values to
 * tooltips. With Shulker Contents on, vanilla's short "x5, and 3 more..." list is replaced (ContainerComponentMixin),
 * so the contents aren't listed twice. Anything a server hid from the tooltip stays hidden.
 */
public class BetterTooltips extends Module {
    /** Vanilla's durability line in advanced tooltips (F3+H), "Durability: 12 / 250". */
    private static final String VANILLA_DURABILITY_KEY = "item.durability";
    /** A shulker box holds 27 stacks; more distinct entries than this only come from commands. */
    private static final int MAX_ENTRIES = 27;

    private final BooleanSetting shulkers = add(new BooleanSetting("Shulker Contents", true)
            .describe("Lists everything inside shulker boxes and other containers, totalled per item."));
    private final BooleanSetting durability = add(new BooleanSetting("Durability", true)
            .describe("Shows uses left on tools and armour."));
    private final BooleanSetting food = add(new BooleanSetting("Food Values", true)
            .describe("Shows the hunger and saturation a food restores."));

    public BetterTooltips() {
        super("BetterTooltips", "Shulker contents, durability and food values in tooltips.", Category.VISUALS);
        setEnabled(true);
    }

    /** Registered once from EvadeClient with Fabric's tooltip event. */
    public static void append(ItemStack stack, List<Text> lines) {
        BetterTooltips bt = active();
        if (bt != null) bt.lines(stack, lines);
    }

    /** Whether vanilla's own container list should be left out (this module lists the contents instead). */
    public static boolean replacesContainerList() {
        BetterTooltips bt = active();
        return bt != null && bt.shulkers.get();
    }

    private static BetterTooltips active() {
        if (EvadeClient.modules == null) return null;
        BetterTooltips bt = EvadeClient.modules.get(BetterTooltips.class);
        return bt != null && bt.isEnabled() ? bt : null;
    }

    public void lines(ItemStack stack, List<Text> lines) {
        TooltipDisplayComponent display = stack.getOrDefault(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplayComponent.DEFAULT);
        List<Text> added = new ArrayList<>();

        ContainerComponent c = stack.get(DataComponentTypes.CONTAINER);
        if (shulkers.get() && c != null && display.shouldDisplay(DataComponentTypes.CONTAINER)) contents(stack, c, added);

        // In advanced tooltips vanilla already shows the durability of a damaged item.
        if (durability.get() && stack.isDamageable() && display.shouldDisplay(DataComponentTypes.DAMAGE)
                && indexOfVanillaDurability(lines) < 0) {
            int max = stack.getMaxDamage(), left = max - stack.getDamage();
            float pct = left / (float) max;
            Formatting col = pct > 0.5f ? Formatting.GREEN : pct > 0.2f ? Formatting.YELLOW : Formatting.RED;
            added.add(Text.literal("Durability: ").formatted(Formatting.GRAY).append(Text.literal(left + "/" + max).formatted(col)));
        }

        FoodComponent f = stack.get(DataComponentTypes.FOOD);
        if (food.get() && f != null && display.shouldDisplay(DataComponentTypes.FOOD)) {
            added.add(Text.literal("Food: " + f.nutrition() + "  Saturation: " + String.format(Locale.ROOT, "%.1f", f.saturation()))
                    .formatted(Formatting.GRAY));
        }

        if (added.isEmpty()) return;
        int at = advancedSectionStart(stack, lines);
        if (at < 0) lines.addAll(added); else lines.addAll(at, added);
    }

    /** Every non-empty stack, totalled per identical item (same item and components), most first. */
    private static void contents(ItemStack container, ContainerComponent c, List<Text> out) {
        List<ItemStack> kinds = new ArrayList<>();
        List<int[]> totals = new ArrayList<>();
        int stacks = 0;
        for (ItemStack s : c.iterateNonEmpty()) {
            stacks++;
            int i = 0;
            while (i < kinds.size() && !ItemStack.areItemsAndComponentsEqual(kinds.get(i), s)) i++;
            if (i == kinds.size()) { kinds.add(s); totals.add(new int[]{0}); }
            totals.get(i)[0] += s.getCount();
        }
        if (stacks == 0) return;

        boolean shulker = container.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
        out.add(Text.literal(shulker ? "Contents (" + stacks + "/27 slots):" : "Contents (" + stacks + (stacks == 1 ? " stack):" : " stacks):"))
                .formatted(Formatting.RED));

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < kinds.size(); i++) order.add(i);
        order.sort((a, b) -> Integer.compare(totals.get(b)[0], totals.get(a)[0])); // stable: ties keep slot order
        int shown = Math.min(order.size(), MAX_ENTRIES);
        for (int k = 0; k < shown; k++) {
            int i = order.get(k);
            out.add(Text.literal("  " + totals.get(i)[0] + "x ").formatted(Formatting.GRAY)
                    .append(kinds.get(i).getName().copy().formatted(Formatting.WHITE)));
        }
        if (order.size() > shown)
            out.add(Text.literal("  and " + (order.size() - shown) + " more...").formatted(Formatting.GRAY, Formatting.ITALIC));
    }

    /**
     * Where vanilla's advanced (F3+H) section starts: its durability line, else the dark-gray item id. Our lines go
     * above it so the id stays last, as in vanilla. -1 when the tooltip isn't advanced.
     */
    private static int advancedSectionStart(ItemStack stack, List<Text> lines) {
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        for (int i = lines.size() - 1; i > 0; i--) {
            if (!id.equals(lines.get(i).getString())) continue;
            return isVanillaDurability(lines.get(i - 1)) ? i - 1 : i;
        }
        return -1;
    }

    private static int indexOfVanillaDurability(List<Text> lines) {
        for (int i = lines.size() - 1; i > 0; i--) if (isVanillaDurability(lines.get(i))) return i;
        return -1;
    }

    private static boolean isVanillaDurability(Text line) {
        return line.getContent() instanceof TranslatableTextContent t && VANILLA_DURABILITY_KEY.equals(t.getKey());
    }
}
