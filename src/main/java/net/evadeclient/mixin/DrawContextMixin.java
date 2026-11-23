package net.evadeclient.mixin;

import net.evadeclient.module.visuals.ShinyPots;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ShinyPots "Badge": level and time-left badges drawn over a potion after its count and bars, wherever a slot is
 * drawn (inventories, hotbar, container previews). The four-argument overload calls this one.
 */
@Mixin(DrawContext.class)
public abstract class DrawContextMixin {
    @Inject(method = "drawStackOverlay(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/item/ItemStack;IILjava/lang/String;)V",
            at = @At("TAIL"))
    private void evade$shinyPotsBadge(TextRenderer textRenderer, ItemStack stack, int x, int y, @Nullable String countText,
                                      CallbackInfo ci) {
        ShinyPots.drawBadge((DrawContext) (Object) this, textRenderer, stack, x, y);
    }
}
