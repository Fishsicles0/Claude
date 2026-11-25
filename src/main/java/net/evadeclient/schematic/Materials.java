package net.evadeclient.schematic;

import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * The material list: the items a schematic needs, how many are already placed (from the verifier's last pass, so
 * only blocks the client can see count), and how many you carry. A double slab counts two slabs; the second half of
 * a door, bed or tall plant counts nothing; blocks without an item (fluids, fire) are left out.
 */
public final class Materials {
    /** One line of the list. {@code left} = needed - placed; {@code missing} = what you still have to get. */
    public record Entry(Item item, int needed, int placed, int inventory) {
        public int left() { return Math.max(0, needed - placed); }
        public int missing() { return Math.max(0, left() - inventory); }
    }

    private Materials() {}

    /** The item and how many of it one block of {@code s} takes (0 for none). */
    static int itemCount(BlockState s) {
        if (s == null || s.isAir()) return 0;
        if (s.contains(Properties.DOUBLE_BLOCK_HALF) && s.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return 0;
        if (s.contains(Properties.BED_PART) && s.get(Properties.BED_PART) == BedPart.HEAD) return 0;
        if (s.contains(Properties.SLAB_TYPE) && s.get(Properties.SLAB_TYPE) == SlabType.DOUBLE) return 2;
        return s.getBlock().asItem() == Items.AIR ? 0 : 1;
    }

    /** How many positions use each palette index. */
    public static int[] paletteCounts(Schematic s) {
        int[] counts = new int[s.palette.length];
        for (short b : s.blocks) counts[b]++;
        return counts;
    }

    /**
     * The list, most still to place first (then by name). {@code placedByPalette} may be null (nothing checked yet);
     * {@code inventory} counts an item in your inventory (or returns 0).
     */
    public static List<Entry> list(Schematic s, int[] paletteCounts, int[] placedByPalette, ToIntFunction<Item> inventory) {
        Map<Item, int[]> totals = new LinkedHashMap<>();
        for (int i = 1; i < s.palette.length; i++) {
            BlockState st = s.palette[i];
            int per = itemCount(st);
            if (per == 0 || paletteCounts[i] == 0) continue;
            int[] t = totals.computeIfAbsent(st.getBlock().asItem(), k -> new int[2]);
            t[0] += paletteCounts[i] * per;
            if (placedByPalette != null && i < placedByPalette.length) t[1] += placedByPalette[i] * per;
        }
        List<Entry> out = new ArrayList<>(totals.size());
        for (var e : totals.entrySet()) out.add(new Entry(e.getKey(), e.getValue()[0], e.getValue()[1], inventory.applyAsInt(e.getKey())));
        out.sort(Comparator.comparingInt(Entry::left).reversed().thenComparing(e -> name(e.item())));
        return out;
    }

    public static String name(Item item) { return item.getName().getString(); }

    /** Plain text for the clipboard: one "Item: needed (placed, left, in inventory)" line each, plus totals. */
    public static String text(String schematicName, List<Entry> list) {
        StringBuilder b = new StringBuilder();
        b.append("Materials for ").append(schematicName).append('\n');
        b.append("Item | Needed | Placed | Left | In inventory | Still to get\n");
        int needed = 0, left = 0, missing = 0;
        for (Entry e : list) {
            b.append(name(e.item())).append(" | ").append(e.needed()).append(" | ").append(e.placed()).append(" | ")
                    .append(e.left()).append(" | ").append(e.inventory()).append(" | ").append(e.missing())
                    .append(stacks(e.missing()).isEmpty() ? "" : " (" + stacks(e.missing()) + ")").append('\n');
            needed += e.needed();
            left += e.left();
            missing += e.missing();
        }
        b.append(String.format(Locale.ROOT, "Total: %d needed, %d left to place, %d still to get%n", needed, left, missing));
        return b.toString();
    }

    /** "2 stacks + 5" for 133 (empty for 0). */
    public static String stacks(int n) {
        if (n < 64) return "";
        int st = n / 64, rest = n % 64;
        return st + (st == 1 ? " stack" : " stacks") + (rest > 0 ? " + " + rest : "");
    }

    /** Sum of {@link Entry#left()} over a list. */
    public static int totalLeft(List<Entry> list) {
        int n = 0;
        for (Entry e : list) n += e.left();
        return n;
    }
}
