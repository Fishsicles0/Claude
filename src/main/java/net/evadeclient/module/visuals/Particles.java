package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.minecraft.client.particle.FireworksSparkParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;

/**
 * Hides particle clutter for a cleaner screen: combat particles (crits, sharpness sparks, damage hearts, sweeps),
 * explosions, totem pops, fireworks, the cracks that fly off a block you're mining and your own potion swirls.
 * Everything here only changes what your client draws.
 *
 * Particles are filtered where the game only spawns them (ClientWorld) or, for the few callers that use the
 * particle right after creating it (firework explosions), by creating it as usual and never adding it to the
 * world. Nothing ever hands a caller "no particle", which crashed the game when a firework went off.
 */
public class Particles extends Module {
    public final BooleanSetting crits = add(new BooleanSetting("Hide Crits", true));
    public final BooleanSetting sharpness = add(new BooleanSetting("Hide Sharpness", true));
    public final BooleanSetting damage = add(new BooleanSetting("Hide Damage Hearts", true));
    public final BooleanSetting sweep = add(new BooleanSetting("Hide Sweeps", false));
    public final BooleanSetting explosions = add(new BooleanSetting("Hide Explosions", false));
    public final BooleanSetting totems = add(new BooleanSetting("Hide Totem Pops", false));
    public final BooleanSetting fireworks = add(new BooleanSetting("Hide Fireworks", false));
    public final BooleanSetting mining = add(new BooleanSetting("Hide Mining Particles", false));
    public final BooleanSetting ownPotions = add(new BooleanSetting("Hide Own Potion Particles", false));

    public Particles() {
        super("Particles", "Hides crits, sharpness sparks, totem pops, fireworks and other particle clutter.", Category.VISUALS);
    }

    /** The enabled module, or null. */
    private static Particles on() {
        if (EvadeClient.modules == null) return null;
        Particles p = EvadeClient.modules.get(Particles.class);
        return p != null && p.isEnabled() ? p : null;
    }

    /** True if a particle of this type should not be spawned (or, once created, not be added to the world). */
    public static boolean blocks(ParticleEffect effect) {
        if (effect == null) return false;
        Particles p = on();
        if (p == null) return false;
        var t = effect.getType();
        return (p.crits.get() && t == ParticleTypes.CRIT)
                || (p.sharpness.get() && t == ParticleTypes.ENCHANTED_HIT)
                || (p.damage.get() && t == ParticleTypes.DAMAGE_INDICATOR)
                || (p.sweep.get() && t == ParticleTypes.SWEEP_ATTACK)
                || (p.explosions.get() && (t == ParticleTypes.EXPLOSION || t == ParticleTypes.EXPLOSION_EMITTER))
                || (p.totems.get() && t == ParticleTypes.TOTEM_OF_UNDYING)
                || (p.fireworks.get() && (t == ParticleTypes.FIREWORK || t == ParticleTypes.FLASH));
    }

    /**
     * For particles the game creates directly instead of from a particle type: a firework's sparks spawn their own
     * trail sparks this way. (Particles created from a type were already checked by {@link #blocks}.) The
     * firework's invisible starter is kept, so the bang and the crackle still play.
     */
    public static boolean blocksCreated(Particle particle) {
        Particles p = on();
        return p != null && p.fireworks.get() && isFireworkVisual(particle)
                && !(particle instanceof FireworksSparkParticle.FireworkParticle);
    }

    /** "Hide Explosions" is on: the debris cloud an explosion throws out is skipped too, not just the blast. */
    public static boolean hidesExplosions() {
        Particles p = on();
        return p != null && p.explosions.get();
    }

    /** "Hide Mining Particles" is on: no cracks fly off the block you're breaking. */
    public static boolean hidesMining() {
        Particles p = on();
        return p != null && p.mining.get();
    }

    /** "Hide Own Potion Particles" is on: your own effect swirls are skipped while you look through your own eyes. */
    public static boolean hidesOwnPotions() {
        Particles p = on();
        return p != null && p.ownPotions.get();
    }

    /**
     * A firework's explosion sparks are a class the game keeps private, so they're recognised by being nested in
     * FireworksSparkParticle (the starter, the sparks and the flash all are). Cached per class.
     */
    private static final ClassValue<Boolean> FIREWORK_CLASS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null && c != Particle.class; c = c.getSuperclass()) {
                try {
                    if (c.getEnclosingClass() == FireworksSparkParticle.class) return true;
                } catch (Throwable ignored) {
                    return false;
                }
            }
            return false;
        }
    };

    /** A firework's explosion: its starter, its sparks (and their trails) or its flash. */
    public static boolean isFireworkVisual(Particle particle) {
        return particle != null && FIREWORK_CLASS.get(particle.getClass());
    }
}
