package net.evadeclient.mixin;

import net.evadeclient.module.mods.Performance;
import net.evadeclient.module.visuals.Particles;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.particle.ParticleEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Particle filtering for the Particles mod and Performance.
 *
 * Particles are never refused at creation (addParticle(effect...) still returns the particle), because some
 * callers use the result right away: a firework explosion sets colours on every spark it spawns, and returning
 * null there crashes the game. A filtered particle is created as usual but never added to the world.
 */
@Mixin(ParticleManager.class)
public abstract class ParticleManagerMixin {
    @Unique private boolean evade$dropNext;

    @Inject(method = "addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;", at = @At("HEAD"))
    private void evade$filterByType(ParticleEffect effect, double x, double y, double z, double vx, double vy, double vz,
                                    CallbackInfoReturnable<Particle> cir) {
        var perf = Performance.active();
        evade$dropNext = Particles.blocks(effect) || (perf != null && perf.skipParticle(effect, x, y, z));
    }

    @Inject(method = "addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;", at = @At("RETURN"))
    private void evade$filterDone(ParticleEffect effect, double x, double y, double z, double vx, double vy, double vz,
                                  CallbackInfoReturnable<Particle> cir) {
        evade$dropNext = false; // in case no particle was created
    }

    /** Every particle passes here (also ones created directly, like block-break dust): skip the filtered ones. */
    @Inject(method = "addParticle(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void evade$filter(Particle particle, CallbackInfo ci) {
        if (evade$dropNext) {
            evade$dropNext = false;
            ci.cancel();
            return;
        }
        var perf = Performance.active();
        if (perf != null && perf.skipParticle(particle)) ci.cancel();
    }
}
