package net.evadeclient.module.visuals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.ParticleColorDuck;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.FireworksSparkParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.component.EnchantmentEffectComponentTypes;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.ParticleTypes;

/**
 * Hides particle clutter for a cleaner screen (combat particles, explosions, totem pops, fireworks, mining cracks,
 * block-break bursts, potion swirls) and styles the hit particles you do keep: more of them per hit, their own colour
 * and size. Everything here only changes what your client draws; nothing is sent to the server.
 *
 * Particles are filtered where the game only spawns them (ClientWorld) or, for the few callers that use the
 * particle right after creating it (firework explosions), by creating it as usual and never adding it to the
 * world. Nothing ever hands a caller "no particle", which crashed the game when a firework went off.
 */
public class Particles extends Module {
    /** "Only My Hits": a crit/sharpness burst on your target counts as yours this many ticks after you swung. */
    private static final int OWN_HIT_TICKS = 10;
    /** Vanilla's hit-particle emitter lifetime (ParticleManager.addEmitter without a duration). */
    private static final int EMITTER_TICKS = 3;

    public final BooleanSetting crits = add(new BooleanSetting("Hide Crits", true));
    public final BooleanSetting sharpness = add(new BooleanSetting("Hide Sharpness", true));
    public final BooleanSetting damage = add(new BooleanSetting("Hide Damage Hearts", true));
    public final BooleanSetting sweep = add(new BooleanSetting("Hide Sweeps", false));
    public final BooleanSetting explosions = add(new BooleanSetting("Hide Explosions", false));
    public final BooleanSetting totems = add(new BooleanSetting("Hide Totem Pops", false));
    public final BooleanSetting fireworks = add(new BooleanSetting("Hide Fireworks", false));
    public final BooleanSetting mining = add(new BooleanSetting("Hide Mining Particles", false));
    public final BooleanSetting blockBreak = add(new BooleanSetting("Hide Block Break Particles", false)
            .describe("No burst of block pieces when a block breaks (yours or anyone's). The break sound stays."));
    public final ModeSetting potionSwirls = add(new ModeSetting("Potion Swirls", "Show", "Show", "Hide Own", "Hide All")
            .describe("Hide Own: your own effect swirls are skipped while you look through your own eyes. "
                    + "Hide All: nobody's effect swirls are drawn."));

    public final NumberSetting critMultiplier = add(new NumberSetting("Crit Multiplier", 1, 1, 10, 1)
            .describe("How many crit bursts each critical hit shows (1 = vanilla). Off while Hide Crits is on."));
    public final NumberSetting sharpnessMultiplier = add(new NumberSetting("Sharpness Multiplier", 1, 1, 10, 1)
            .describe("How many sharpness bursts each enchanted hit shows (1 = vanilla). Off while Hide Sharpness is on."));
    public final BooleanSetting onlyMyHits = add(new BooleanSetting("Only My Hits", true)
            .describe("Multipliers apply only to hits you just landed, not to other players' fights."));
    public final BooleanSetting alwaysCrits = add(new BooleanSetting("Always Crits", false)
            .describe("Show crit particles on every hit you land, not only on real critical hits. Cosmetic only."));
    public final BooleanSetting alwaysSharpness = add(new BooleanSetting("Always Sharpness", false)
            .describe("Show sharpness sparks on every hit you land, even without a damage enchantment. Cosmetic only."));
    public final BooleanSetting customCritColor = add(new BooleanSetting("Custom Crit Color", false)
            .describe("Paint crit particles (melee crits and critical arrow trails) in Crit Color."));
    public final ColorSetting critColor = add(new ColorSetting("Crit Color", 0xFFFFD21F));
    public final BooleanSetting customSharpnessColor = add(new BooleanSetting("Custom Sharpness Color", false)
            .describe("Paint sharpness sparks in Sharpness Color."));
    public final ColorSetting sharpnessColor = add(new ColorSetting("Sharpness Color", 0xFF29D3F0));
    public final NumberSetting hitParticleSize = add(new NumberSetting("Hit Particle Size %", 100, 50, 300, 10)
            .describe("Size of crit and sharpness particles (100 = vanilla)."));

    private static Particles instance;

    /** The entity you last hit and when ({@link EvadeClient#ticks}), for "Only My Hits". */
    private int lastTargetId = Integer.MIN_VALUE;
    private long lastAttackTick = Long.MIN_VALUE;

    public Particles() {
        super("Particles", "Hides particle clutter and restyles your hit particles: more of them, your colour, your size.", Category.VISUALS);
        instance = this;
    }

    /** The enabled module, or null. Called for every particle, so it is a field read, not a module lookup. */
    private static Particles on() {
        Particles p = instance;
        return p != null && p.isEnabled() ? p : null;
    }

    /** Configs from before "Potion Swirls" had a plain "Hide Own Potion Particles" switch. */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        JsonElement old = settings.get("Hide Own Potion Particles");
        if (old == null || !old.isJsonPrimitive() || !potionSwirls.isDefault()) return;
        try {
            if (old.getAsBoolean()) potionSwirls.set("Hide Own");
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    protected void onDisable() {
        lastTargetId = Integer.MIN_VALUE;
        lastAttackTick = Long.MIN_VALUE;
    }

    // ------------------------------------------------------------------ hiding

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

    /** "Hide Block Break Particles" is on: no burst of block pieces when a block breaks. */
    public static boolean hidesBlockBreak() {
        Particles p = on();
        return p != null && p.blockBreak.get();
    }

    /**
     * "Potion Swirls" is Hide Own or Hide All: your own effect swirls are skipped while you look through your own
     * eyes (Hide All also hides them in third person, see {@link #hidesAllPotionSwirls}).
     */
    public static boolean hidesOwnPotions() {
        Particles p = on();
        return p != null && !p.potionSwirls.is("Show");
    }

    /** "Potion Swirls" is Hide All: no living entity's status-effect swirls are spawned, yours included. */
    public static boolean hidesAllPotionSwirls() {
        Particles p = on();
        return p != null && p.potionSwirls.is("Hide All");
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

    // ------------------------------------------------------------------ hit particles

    /**
     * Your attack, just before it is sent: remembered for "Only My Hits", and the place where Always Crits / Always
     * Sharpness add the burst the server won't send for this hit. Only ever adds client-side particles.
     */
    @Override
    public void onAttack(Entity target) {
        ClientPlayerEntity player = mc.player;
        if (player == null || target == null) return;
        lastTargetId = target.getId();
        lastAttackTick = EvadeClient.ticks;
        if (!(target instanceof LivingEntity living) || !living.isAlive()) return;
        // The server already sends sparks for a damage-enchanted weapon (when the enchantment applies to the target).
        if (alwaysSharpness.get() && !sharpness.get()
                && !EnchantmentHelper.hasAnyEnchantmentsWith(player.getWeaponStack(), EnchantmentEffectComponentTypes.DAMAGE)) {
            player.addEnchantedHitParticles(target);
        }
        if (alwaysCrits.get() && !crits.get() && !isCritical(player)) player.addCritParticles(target);
    }

    /** Vanilla's melee crit rule (PlayerEntity.attack), checked before the swing resets the cooldown. */
    private static boolean isCritical(ClientPlayerEntity p) {
        return p.getAttackCooldownProgress(0.5f) > 0.9f
                && p.fallDistance > 0
                && !p.isOnGround()
                && !p.isClimbing()
                && !p.isTouchingWater()
                && !p.hasStatusEffect(StatusEffects.BLINDNESS)
                && !p.hasVehicle()
                && !p.isSprinting();
    }

    /**
     * How many extra hit-particle emitters to add after vanilla added one for {@code entity}: Crit / Sharpness
     * Multiplier minus one, 0 for other particle types, while the type is hidden, or (with "Only My Hits") when the
     * burst isn't on the entity you just hit.
     */
    public static int extraEmitters(Entity entity, ParticleEffect effect) {
        Particles p = on();
        if (p == null || entity == null || effect == null) return 0;
        ParticleType<?> t = effect.getType();
        int mult;
        if (t == ParticleTypes.CRIT) {
            if (p.crits.get()) return 0;
            mult = p.critMultiplier.getInt();
        } else if (t == ParticleTypes.ENCHANTED_HIT) {
            if (p.sharpness.get()) return 0;
            mult = p.sharpnessMultiplier.getInt();
        } else {
            return 0;
        }
        if (mult <= 1) return 0;
        if (p.onlyMyHits.get()) {
            long since = EvadeClient.ticks - p.lastAttackTick;
            if (entity.getId() != p.lastTargetId || since < 0 || since > OWN_HIT_TICKS) return 0;
        }
        return Math.min(mult, 10) - 1;
    }

    /** The emitter lifetime vanilla uses for hit particles (for the extra emitters). */
    public static int emitterTicks() { return EMITTER_TICKS; }

    /**
     * A crit or sharpness particle was just created: give it Crit / Sharpness Color and Hit Particle Size. The colour
     * is kept for the particle's whole life (vanilla fades it to yellow/orange every tick).
     */
    public static void styleHitParticle(ParticleEffect effect, Particle particle) {
        if (effect == null || !(particle instanceof BillboardParticle)) return;
        Particles p = on();
        if (p == null) return;
        ParticleType<?> t = effect.getType();
        boolean crit = t == ParticleTypes.CRIT;
        if (!crit && t != ParticleTypes.ENCHANTED_HIT) return;
        boolean custom = crit ? p.customCritColor.get() : p.customSharpnessColor.get();
        if (custom && particle instanceof ParticleColorDuck duck) {
            duck.evade$keepColor((crit ? p.critColor.get() : p.sharpnessColor.get()) & 0xFFFFFF);
        }
        double size = p.hitParticleSize.get();
        if (Math.abs(size - 100) > 0.5) particle.scale((float) (size / 100.0));
    }
}
