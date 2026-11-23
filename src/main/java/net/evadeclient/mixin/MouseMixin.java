package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.mods.Freelook;
import net.evadeclient.module.mods.Zoom;
import net.evadeclient.module.visuals.CameraClip;
import net.evadeclient.util.ClickTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public abstract class MouseMixin {
    /**
     * Freelook steals mouse movement for the camera while it is held or toggled on (not during its smooth return, so
     * aiming works again at once); zoom lowers sensitivity (also while it eases back out).
     */
    @WrapOperation(method = "updateMouse", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;changeLookDirection(DD)V"))
    private void evade$look(ClientPlayerEntity player, double dx, double dy, Operation<Void> original) {
        if (EvadeClient.modules != null) {
            Zoom zoom = EvadeClient.modules.get(Zoom.class);
            if (zoom != null && zoom.isEnabled()) {
                double s = zoom.sensitivity();
                if (s < 1) {
                    dx *= s;
                    dy *= s;
                }
            }
            Freelook fl = EvadeClient.modules.get(Freelook.class);
            if (fl != null && fl.capturing()) {
                fl.look(dx, dy);
                return;
            }
        }
        original.call(player, dx, dy);
    }

    /**
     * The mouse wheel in game: Zoom's scroll-to-zoom first (while zooming), then Camera Distance's Scroll To Adjust
     * (in third person). Either one that uses the scroll skips vanilla's hotbar scroll.
     */
    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void evade$scrollZoom(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (EvadeClient.modules == null || vertical == 0) return;
        if (window != MinecraftClient.getInstance().getWindow().getHandle()) return;
        Zoom zoom = EvadeClient.modules.get(Zoom.class);
        if (zoom != null && zoom.onScroll(vertical)) {
            ci.cancel();
            return;
        }
        CameraClip camera = EvadeClient.modules.get(CameraClip.class);
        if (camera != null && camera.onScroll(vertical)) ci.cancel();
    }

    /** CPS counter and Keystrokes: count every physical press as GLFW reports it, not once per frame. */
    @Inject(method = "onMouseButton", at = @At("HEAD"))
    private void evade$countClick(long window, MouseInput input, int action, CallbackInfo ci) {
        if (window != MinecraftClient.getInstance().getWindow().getHandle()) return;
        ClickTracker.onButton(input.button(), action);
    }
}
