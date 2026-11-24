package net.evadeclient.module.visuals.tooltip;

import net.minecraft.component.type.BannerPatternsComponent;
import net.minecraft.item.tooltip.TooltipData;
import net.minecraft.util.DyeColor;

/**
 * BetterTooltips' "Banner Preview": a banner (or what a banner pattern item looks like on a white banner) as a large
 * flag. Drawn by {@link BannerPreviewComponent}.
 */
public record BannerPreviewData(DyeColor base, BannerPatternsComponent patterns) implements TooltipData {
}
