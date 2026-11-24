package net.evadeclient.mixin;

import net.evadeclient.module.visuals.MenuBlur;
import net.evadeclient.util.Guard;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Menu Blur: vanilla only blurs behind a menu while the game's Menu Background Blur is at least 1. For the menus Menu
 * Blur covers, the blur is asked for directly (its strength comes from GameRendererBlurMixin); everything else, and
 * every menu while the module is off, keeps vanilla's behaviour.
 */
@Mixin(Screen.class)
public abstract class MenuBlurScreenMixin {
    @Inject(method = "applyBlur", at = @At("HEAD"), cancellable = true)
    private void evade$menuBlur(DrawContext ctx, CallbackInfo ci) {
        Guard.run(MenuBlur.class, "blur", () -> {
            if (MenuBlur.applyBlur((Screen) (Object) this, ctx)) ci.cancel();
        });
    }
}
