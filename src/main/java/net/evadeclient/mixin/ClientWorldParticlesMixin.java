package net.evadeclient.mixin;

import net.evadeclient.module.mods.Performance;
import net.evadeclient.module.visuals.Particles;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.BlockParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.util.collection.WeightedPool;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where the world spawns particles: the safe place to filter them (Particles mod and Performance). Nothing here
 * hands a particle back to anyone, so skipping one is just one fewer particle, and it is never even created.
 */
@Mixin(ClientWorld.class)
public abstract class ClientWorldParticlesMixin {
    /**
     * Every particle the world spawns by type goes through here (addParticleClient / addImportantParticleClient,
     * particles sent by the server, entity and block effects): type filters, then Performance's distance and budget.
     */
    @Inject(method = "addParticle(Lnet/minecraft/particle/ParticleEffect;ZZDDDDDD)V", at = @At("HEAD"), cancellable = true)
    private void evade$filterParticle(ParticleEffect effect, boolean force, boolean canSpawnOnMinimal, double x, double y, double z,
                                      double vx, double vy, double vz, CallbackInfo ci) {
        if (Particles.blocks(effect)) {
            ci.cancel();
            return;
        }
        Performance perf = Performance.active();
        if (perf != null && perf.skipParticle(effect, x, y, z)) ci.cancel();
    }

    /** Particles "Hide Explosions": also skip the debris cloud an explosion throws out (hundreds of puffs a tick). */
    @Inject(method = "addBlockParticleEffects", at = @At("HEAD"), cancellable = true)
    private void evade$hideExplosionDebris(Vec3d center, float radius, int blockCount, WeightedPool<BlockParticleEffect> particles,
                                           CallbackInfo ci) {
        if (Particles.hidesExplosions()) ci.cancel();
    }

    /** Particles "Hide Mining Particles": no cracks fly off the block you're breaking (the crack overlay stays). */
    @Inject(method = "spawnBlockBreakingParticle", at = @At("HEAD"), cancellable = true)
    private void evade$hideMiningParticles(BlockPos pos, Direction direction, CallbackInfo ci) {
        if (Particles.hidesMining()) ci.cancel();
    }
}
