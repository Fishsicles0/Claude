package net.evadeclient.mixin;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.visuals.NoFog;
import net.minecraft.client.render.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    private static boolean evade$noFog() {
        if (EvadeClient.modules == null) return false;
        NoFog nf = EvadeClient.modules.get(NoFog.class);
        return nf != null && nf.isEnabled();
    }

    // applyFog(buffer, offset, color, envStart, envEnd, renderStart, renderEnd, skyEnd, cloudEnd): push every distance far away.
    @ModifyVariable(method = "applyFog(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private float evade$envStart(float v) { return evade$noFog() ? 1e6f : v; }

    @ModifyVariable(method = "applyFog(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V", at = @At("HEAD"), ordinal = 1, argsOnly = true)
    private float evade$envEnd(float v) { return evade$noFog() ? 2e6f : v; }

    @ModifyVariable(method = "applyFog(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V", at = @At("HEAD"), ordinal = 2, argsOnly = true)
    private float evade$renderStart(float v) { return evade$noFog() ? 1e6f : v; }

    @ModifyVariable(method = "applyFog(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V", at = @At("HEAD"), ordinal = 3, argsOnly = true)
    private float evade$renderEnd(float v) { return evade$noFog() ? 2e6f : v; }
}
