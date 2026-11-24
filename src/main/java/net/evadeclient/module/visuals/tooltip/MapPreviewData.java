package net.evadeclient.module.visuals.tooltip;

import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.tooltip.TooltipData;

/**
 * BetterTooltips' "Map Preview": a filled map's picture, from the map data your game already has (maps you've held or
 * seen in an item frame). Drawn by {@link MapPreviewComponent}.
 */
public record MapPreviewData(MapIdComponent id, int size) implements TooltipData {
}
