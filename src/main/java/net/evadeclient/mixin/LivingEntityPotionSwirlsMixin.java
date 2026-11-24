package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.WrapWithCondition;
import net.evadeclient.module.visuals.Particles;
import net.evadeclient.util.Guard;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Particles "Potion Swirls: Hide All": no living entity spawns its status-effect swirls on this client. (Hide Own,
 * your own swirls in first person only, is {@link LivingEntityOwnPotionParticlesMixin}.) Only the client-side spawn
 * in tickStatusEffects is skipped; the effects themselves, splash potions, lingering clouds and tipped-arrow trails
 * are untouched.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityPotionSwirlsMixin {
    @WrapWithCondition(method = "tickStatusEffects", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/World;addParticleClient(Lnet/minecraft/particle/ParticleEffect;DDDDDD)V"))
    private boolean evade$allPotionSwirls(World world, ParticleEffect effect, double x, double y, double z,
                                          double vx, double vy, double vz) {
        return Guard.test(Particles.class, "potion swirls", () -> !Particles.hidesAllPotionSwirls(), true);
    }
}
