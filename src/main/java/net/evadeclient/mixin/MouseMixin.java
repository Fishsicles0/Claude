package net.evadeclient.mixin;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.mods.Freelook;
import net.evadeclient.module.mods.Zoom;
import net.minecraft.client.Mouse;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public abstract class MouseMixin {
    /** Freelook steals mouse movement for the camera; zoom lowers sensitivity. */
    @Redirect(method = "updateMouse", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;changeLookDirection(DD)V"))
    private void evade$look(ClientPlayerEntity player, double dx, double dy) {
        if (EvadeClient.modules != null) {
            Zoom zoom = EvadeClient.modules.get(Zoom.class);
            if (zoom != null && zoom.zooming()) {
                double s = zoom.sensitivity();
                dx *= s;
                dy *= s;
            }
            var fc = EvadeClient.modules.get(net.evadeclient.module.visuals.Freecam.class);
            if (fc != null && fc.isEnabled()) {
                fc.look(dx, dy);
                return;
            }
            Freelook fl = EvadeClient.modules.get(Freelook.class);
            if (fl != null && fl.active()) {
                fl.look(dx, dy);
                return;
            }
        }
        player.changeLookDirection(dx, dy);
    }

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void evade$scrollZoom(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (EvadeClient.modules == null || vertical == 0) return;
        Zoom zoom = EvadeClient.modules.get(Zoom.class);
        if (zoom != null && zoom.onScroll(vertical)) ci.cancel();
    }
}
