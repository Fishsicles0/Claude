package net.evadeclient.mixin;

import net.evadeclient.replay.ReplayOverrides;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.world.ClientWorld;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replays, green screen: the frame is cleared to the fog colour, so that becomes pure green. */
@Mixin(FogRenderer.class)
public abstract class ReplayFogMixin {
    @Inject(method = "applyFog(Lnet/minecraft/client/render/Camera;ILnet/minecraft/client/render/RenderTickCounter;FLnet/minecraft/client/world/ClientWorld;)Lorg/joml/Vector4f;",
            at = @At("RETURN"), cancellable = true)
    private void evade$green(Camera camera, int viewDistance, RenderTickCounter counter, float skyDarkness, ClientWorld world, CallbackInfoReturnable<Vector4f> cir) {
        if (ReplayOverrides.greenScreen()) cir.setReturnValue(new Vector4f(0f, 1f, 0f, 1f));
    }
}
