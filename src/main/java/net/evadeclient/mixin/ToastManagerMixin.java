package net.evadeclient.mixin;

import net.evadeclient.module.visuals.NoRender;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NoRender toast filter: advancement, recipe and tutorial toasts (each on its own switch) are dropped before they are
 * queued, so they take no slot, play no sound and never push Evade's corner widgets down. System toasts always pass.
 * RecipeToast.show only adds a toast while no recipe toast is showing, so dropping {@code add} is enough there too.
 */
@Mixin(ToastManager.class)
public abstract class ToastManagerMixin {
    @Inject(method = "add(Lnet/minecraft/client/toast/Toast;)V", at = @At("HEAD"), cancellable = true)
    private void evade$filterToasts(Toast toast, CallbackInfo ci) {
        if (NoRender.hidesToast(toast)) ci.cancel();
    }
}
