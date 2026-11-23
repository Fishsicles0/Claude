package net.evadeclient.mixin;

import net.evadeclient.module.visuals.Particles;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Particles "Hide Block Break Particles": the burst of block pieces a breaking block throws out (up to 64 per block,
 * yours or anyone's) is never spawned. The break sound plays as usual; the cracks while mining are "Hide Mining
 * Particles" ({@link ClientWorldParticlesMixin}).
 */
@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {
    @Inject(method = "addBlockBreakParticles(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)V",
            at = @At("HEAD"), cancellable = true)
    private void evade$hideBlockBreakParticles(BlockPos pos, BlockState state, CallbackInfo ci) {
        if (Particles.hidesBlockBreak()) ci.cancel();
    }
}
