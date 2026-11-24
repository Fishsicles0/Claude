package net.evadeclient.mixin;

import net.evadeclient.module.visuals.InventoryBlur;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin extends Screen {
    protected HandledScreenMixin(Text title) { super(title); }

    /**
     * Inventory Blur: vanilla never blurs behind container screens. This asks for the blur directly (not through
     * Screen.applyBlur, which skips it when Menu Background Blur is 0), for the screen groups the module covers; its
     * strength comes from GameRendererBlurMixin, and the dim over it from ScreenMixin.
     */
    @Inject(method = "renderBackground", at = @At("HEAD"))
    private void evade$blur(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        InventoryBlur.blurBehind(this, ctx);
    }
}
