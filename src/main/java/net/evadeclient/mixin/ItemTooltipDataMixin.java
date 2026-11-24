package net.evadeclient.mixin;

import net.evadeclient.module.visuals.BetterTooltips;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * BetterTooltips' graphical tooltip parts: a container's slot grid or compact row, the remembered ender chest, or
 * food shanks, in that order. Vanilla's Item.getTooltipData returns nothing; items with their own (bundles) override
 * it and are left alone. The data is turned into a component by the TooltipComponentCallback BetterTooltips registers.
 */
@Mixin(Item.class)
public abstract class ItemTooltipDataMixin {
    @Inject(method = "getTooltipData", at = @At("HEAD"), cancellable = true)
    private void evade$betterTooltipsPreview(ItemStack stack, CallbackInfoReturnable<Optional<TooltipData>> cir) {
        TooltipData data = BetterTooltips.tooltipData(stack);
        if (data != null) cir.setReturnValue(Optional.of(data));
    }
}
