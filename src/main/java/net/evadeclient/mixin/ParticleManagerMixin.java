package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
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

/**
 * The particle manager's side of particle filtering (Particles mod and Performance).
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
}
