package net.evadeclient.module.visuals.tooltip;

import net.minecraft.item.tooltip.TooltipData;
import net.minecraft.text.Text;

/**
 * BetterTooltips' "Book Preview": the first page of a written book or a book and quill, and how many pages there
 * are. Drawn by {@link BookPreviewComponent}.
 */
public record BookPreviewData(Text firstPage, int pages) implements TooltipData {
}
