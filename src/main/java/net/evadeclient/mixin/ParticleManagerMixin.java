package net.evadeclient.mixin;

import net.evadeclient.module.mods.Performance;
import net.evadeclient.module.visuals.Particles;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.particle.ParticleEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ParticleManager.class)
public abstract class ParticleManagerMixin {
    /** Particles spawned by type: the Particles mod's filters, then Performance (type filters, distance, budget). */
    @Inject(method = "addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;",
            at = @At("HEAD"), cancellable = true)
    private void evade$hideParticles(ParticleEffect effect, double x, double y, double z, double vx, double vy, double vz,
                                     CallbackInfoReturnable<Particle> cir) {
        if (Particles.blocks(effect)) { cir.setReturnValue(null); return; }
        var perf = Performance.active();
        if (perf != null && perf.skipParticle(effect, x, y, z)) cir.setReturnValue(null);
    }

    /** Particles created directly, like the dust when a block breaks: distance and dust reduction. */
    @Inject(method = "addParticle(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void evade$hideDirect(Particle particle, CallbackInfo ci) {
        var perf = Performance.active();
        if (perf != null && perf.skipParticle(particle)) ci.cancel();
    }
}
