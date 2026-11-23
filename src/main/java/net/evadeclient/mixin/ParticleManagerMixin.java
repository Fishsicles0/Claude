package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.evadeclient.module.mods.Performance;
import net.evadeclient.module.visuals.Particles;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.entity.Entity;
import net.minecraft.particle.ParticleEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The particle manager's side of particle filtering (Particles mod and Performance) and of the Particles mod's hit
 * particle styling (multipliers, colour, size).
 *
 * Most particles are filtered earlier, in {@link ClientWorldParticlesMixin}, before they are even created. What
 * reaches the manager directly is handled here, and NEVER by handing the caller "no particle":
 * addParticle(effect, ...) always returns the particle it created, because a firework explosion colours every
 * spark right after creating it (returning null there crashed the game). A filtered particle is created as usual
 * and simply never added to the world.
 */
@Mixin(ParticleManager.class)
public abstract class ParticleManagerMixin {
    /** True only while addParticle(effect, ...) adds the particle it just created (already filtered by type). */
    @Unique private boolean evade$fromEffect;

    @Shadow public abstract void addEmitter(Entity entity, ParticleEffect parameters, int maxAge);

    /**
     * addParticle(effect, ...) adding the particle it created: skip the add for types the Particles mod hides. The
     * particle is still returned to the caller. Type filters only: the world already applied Performance's distance
     * and budget to what it spawns, and the one vanilla caller that comes here directly is a firework explosion,
     * which Performance never thins.
     */
    @WrapOperation(method = "addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleManager;addParticle(Lnet/minecraft/client/particle/Particle;)V"))
    private void evade$filterByType(ParticleManager self, Particle particle, Operation<Void> original,
                                    @Local(argsOnly = true) ParticleEffect effect) {
        if (Particles.blocks(effect)) return;
        evade$fromEffect = true;
        try {
            original.call(self, particle);
        } finally {
            evade$fromEffect = false;
        }
    }

    /** Particles "Custom Crit/Sharpness Color" and "Hit Particle Size": style a crit or sharpness particle once it exists. */
    @Inject(method = "addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;",
            at = @At("RETURN"))
    private void evade$styleHitParticle(ParticleEffect effect, double x, double y, double z, double vx, double vy, double vz,
                                        CallbackInfoReturnable<Particle> cir) {
        Particle particle = cir.getReturnValue();
        if (particle != null) Particles.styleHitParticle(effect, particle);
    }

    /** Particles created directly (block-break dust, firework sparks and their trails) are filtered here. */
    @Inject(method = "addParticle(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void evade$filter(Particle particle, CallbackInfo ci) {
        if (evade$fromEffect) return;
        if (Particles.blocksCreated(particle)) {
            ci.cancel();
            return;
        }
        Performance perf = Performance.active();
        if (perf != null && perf.skipParticle(particle)) ci.cancel();
    }

    /**
     * Particles "Crit Multiplier" / "Sharpness Multiplier": a hit's crit or sharpness burst (from the server's hit
     * animation, or from Always Crits / Always Sharpness) gets extra emitters of vanilla's length. Added with the
     * three-argument overload, which never comes back here, so this can't recurse. Their particles go through the
     * world like any other, so every hide filter still applies.
     */
    @Inject(method = "addEmitter(Lnet/minecraft/entity/Entity;Lnet/minecraft/particle/ParticleEffect;)V", at = @At("TAIL"))
    private void evade$multiplyHitParticles(Entity entity, ParticleEffect effect, CallbackInfo ci) {
        int extra = Particles.extraEmitters(entity, effect);
        for (int i = 0; i < extra; i++) addEmitter(entity, effect, Particles.emitterTicks());
    }
}
