package net.evadeclient.mixin;

import net.evadeclient.module.visuals.InventoryBlur;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inventory Blur's background. In-game screens that keep subtitles on top (inventories, containers, books, signs)
 * draw vanilla's dark gradient in renderInGameBackground; for the screens the module covers, its own Dim % and Tint
 * (faded in) replace it, and books and signs get their blur here too.
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Inject(method = "renderInGameBackground", at = @At("HEAD"), cancellable = true)
    private void evade$inventoryBlurBackground(DrawContext ctx, CallbackInfo ci) {
        if (InventoryBlur.renderInGameBackground((Screen) (Object) this, ctx)) ci.cancel();
    }
}
