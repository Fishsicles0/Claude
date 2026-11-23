package net.evadeclient.module.visuals.tooltip;

import net.minecraft.item.tooltip.TooltipData;

/**
 * BetterTooltips' "Food Display: Icons": the hunger a food restores as HUD shanks, plus its saturation when
 * {@code showSaturation}. Drawn by {@link FoodPreviewComponent}.
 */
public record FoodPreviewData(int nutrition, float saturation, boolean showSaturation) implements TooltipData {
}
