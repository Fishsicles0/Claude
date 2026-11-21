package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;

/** Hides combat particle spam (crits, sharpness sparks, damage hearts, sweeps) for a cleaner screen. */
public class Particles extends Module {
    public final BooleanSetting crits = add(new BooleanSetting("Hide Crits", true));
    public final BooleanSetting sharpness = add(new BooleanSetting("Hide Sharpness", true));
    public final BooleanSetting damage = add(new BooleanSetting("Hide Damage Hearts", true));
    public final BooleanSetting sweep = add(new BooleanSetting("Hide Sweeps", false));
    public final BooleanSetting explosions = add(new BooleanSetting("Hide Explosions", false));

    public Particles() {
        super("Particles", "Hides crit, sharpness and other combat particles.", Category.VISUALS);
    }

    /** True if this particle should not be spawned. */
    public static boolean blocks(ParticleEffect effect) {
        if (EvadeClient.modules == null || effect == null) return false;
        Particles p = EvadeClient.modules.get(Particles.class);
        if (p == null || !p.isEnabled()) return false;
        var t = effect.getType();
        return (p.crits.get() && t == ParticleTypes.CRIT)
                || (p.sharpness.get() && t == ParticleTypes.ENCHANTED_HIT)
                || (p.damage.get() && t == ParticleTypes.DAMAGE_INDICATOR)
                || (p.sweep.get() && t == ParticleTypes.SWEEP_ATTACK)
                || (p.explosions.get() && (t == ParticleTypes.EXPLOSION || t == ParticleTypes.EXPLOSION_EMITTER));
    }
}
