package net.evadeclient.mixin;

import net.evadeclient.util.ParticleColorDuck;
import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.DamageParticle;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Particles "Custom Crit Color" / "Custom Sharpness Color": crit, sharpness and damage-heart particles are all
 * DamageParticles, which fade their green and blue a little every tick (white crits turn yellow, then orange). A
 * particle given a colour through {@link ParticleColorDuck} gets that colour back after every tick instead.
 */
@Mixin(DamageParticle.class)
public abstract class DamageParticleMixin extends BillboardParticle implements ParticleColorDuck {
    /** The kept colour, or false while the particle uses vanilla's colours. */
    @Unique private boolean evade$keep;
    @Unique private float evade$red, evade$green, evade$blue;

    protected DamageParticleMixin(ClientWorld world, double x, double y, double z, Sprite sprite) {
        super(world, x, y, z, sprite);
    }

    @Override
    public void evade$keepColor(int rgb) {
        // Vanilla gives each particle a random brightness of 0.6-0.9 (after one tick of fading, its brightest
        // channel still shows it). Keep a gentler version of that variation so a burst doesn't look flat.
        float brightest = Math.max(red, Math.max(green, blue));
        float shade = Math.max(0.7f, Math.min(1f, brightest / 0.9f));
        evade$red = (rgb >> 16 & 255) / 255f * shade;
        evade$green = (rgb >> 8 & 255) / 255f * shade;
        evade$blue = (rgb & 255) / 255f * shade;
        evade$keep = true;
        setColor(evade$red, evade$green, evade$blue);
    }

    /** Undo this tick's fade for a particle with a kept colour. */
    @Inject(method = "tick", at = @At("TAIL"))
    private void evade$restoreColor(CallbackInfo ci) {
        if (evade$keep) setColor(evade$red, evade$green, evade$blue);
    }
}
