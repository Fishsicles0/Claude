package net.evadeclient.mixin;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.mods.NoHurtCam;
import net.evadeclient.module.mods.Zoom;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void evade$zoom(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        if (EvadeClient.modules == null) return;
        Zoom zoom = EvadeClient.modules.get(Zoom.class);
        if (zoom != null && zoom.isEnabled()) cir.setReturnValue((float) (cir.getReturnValue() * zoom.fovMultiplier()));
        net.evadeclient.util.Projection.fov = cir.getReturnValue();
    }

    @Inject(method = "tiltViewWhenHurt", at = @At("HEAD"), cancellable = true)
    private void evade$noHurtCam(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        if (EvadeClient.modules == null) return;
        NoHurtCam m = EvadeClient.modules.get(NoHurtCam.class);
        if (m != null && m.isEnabled()) ci.cancel();
    }

    /** NoRender: skip the big totem-pop animation. */
    @org.spongepowered.asm.mixin.injection.Inject(method = "showFloatingItem", at = @At("HEAD"), cancellable = true)
    private void evade$noTotem(net.minecraft.item.ItemStack stack, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (net.evadeclient.module.visuals.NoRender.hide(n -> n.totem)) ci.cancel();
    }
}
