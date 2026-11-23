package net.evadeclient.module.visuals.tooltip;

import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipData;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * BetterTooltips' graphical container preview (shulker boxes, chests, the remembered ender chest), turned into a
 * {@link ContainerPreviewComponent} by the TooltipComponentCallback BetterTooltips registers.
 *
 * @param stacks  one entry per cell, empty stacks for empty slots (Grid) or one stack per item kind (Compact)
 * @param counts  the count text to draw on each cell instead of the stack's own count, or null for the normal count
 * @param columns cells per row
 * @param tint    ARGB colour the grid is dyed with (a shulker box's colour), or 0 for the plain vanilla slot look
 * @param header  a dim line drawn above the cells ("Last seen 4m ago"), or null
 * @param slots   true to draw vanilla slot frames (Grid), false for the frameless Compact row
 */
public record ContainerPreviewData(List<ItemStack> stacks, @Nullable List<String> counts, int columns, int tint,
                                   @Nullable Text header, boolean slots) implements TooltipData {
    public ContainerPreviewData {
        stacks = List.copyOf(stacks);
        counts = counts == null ? null : List.copyOf(counts);
        columns = Math.max(1, columns);
    }

    public int rows() {
        return Math.max(1, (stacks.size() + columns - 1) / columns);
    }
}
