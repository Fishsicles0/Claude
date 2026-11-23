package net.evadeclient.mixin;

import net.evadeclient.module.visuals.BetterTooltips;
import net.minecraft.component.ComponentsAccess;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.Item;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * BetterTooltips "Shulker Contents" lists a container's full contents itself, so vanilla's short list (the first five
 * stacks and "and N more...") is left out instead of showing the same items twice. Only while that setting is on.
 */
@Mixin(ContainerComponent.class)
public abstract class ContainerComponentMixin {
    @Inject(method = "appendTooltip", at = @At("HEAD"), cancellable = true)
    private void evade$replacedByBetterTooltips(Item.TooltipContext context, Consumer<Text> textConsumer, TooltipType type,
                                                ComponentsAccess components, CallbackInfo ci) {
        if (BetterTooltips.replacesContainerList()) ci.cancel();
    }
}
