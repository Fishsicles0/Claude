package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.WrapWithCondition;
import net.evadeclient.module.visuals.Particles;
import net.evadeclient.util.Guard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Particles "Hide Own Potion Particles": while you look through your own eyes, your own status-effect swirls are
 * not spawned (they drift right past the camera). Only the local player's own spawn is skipped, everyone else's
 * swirls and your own in third person are untouched.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityOwnPotionParticlesMixin {
    @WrapWithCondition(method = "tickStatusEffects", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/World;addParticleClient(Lnet/minecraft/particle/ParticleEffect;DDDDDD)V"))
    private boolean evade$ownPotionParticles(World world, ParticleEffect effect, double x, double y, double z,
                                             double vx, double vy, double vz) {
        return Guard.test(Particles.class, "own potion particles", () -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if ((Object) this != mc.player || mc.getCameraEntity() != mc.player
                    || mc.options == null || !mc.options.getPerspective().isFirstPerson()) return true;
            return !Particles.hidesOwnPotions();
        }, true);
    }
}
