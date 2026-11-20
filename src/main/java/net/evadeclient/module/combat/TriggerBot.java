package net.evadeclient.module.combat;

import net.evadeclient.mixin.MinecraftClientAccessor;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Targets;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.util.hit.EntityHitResult;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Attacks the moment your crosshair is on a valid target and your weapon is charged.
 * Uses a randomised reaction time and charge threshold, skips shield-blocking players unless you're holding an axe,
 * and works with AutoCritical to time hits for crits.
 */
public class TriggerBot extends Module {
    private final NumberSetting minDelay = add(new NumberSetting("Min Delay ms", 40, 0, 400, 5));
    private final NumberSetting maxDelay = add(new NumberSetting("Max Delay ms", 90, 0, 400, 5));
    private final NumberSetting charge = add(new NumberSetting("Charge %", 92, 50, 100, 1));
    private final BooleanSetting weaponOnly = add(new BooleanSetting("Weapon Only", true));
    private final BooleanSetting shieldCheck = add(new BooleanSetting("Shield Check", true));
    private final BooleanSetting pauseUsing = add(new BooleanSetting("Pause Using", true));
    private final BooleanSetting holdOnly = add(new BooleanSetting("Hold Attack", false));
    private final Targets targets = new Targets();

    private Entity seen;
    private long fireAt;
    private float chargeRoll = 0.92f;

    public TriggerBot() {
        super("TriggerBot", "Attacks automatically when aiming at a target.", Category.COMBAT);
        targets.addTo(this::add);
    }

    @Override
    public void onTick() {
        Entity target = currentTarget();
        if (target == null) { seen = null; return; }
        long now = System.currentTimeMillis();
        if (target != seen) {
            seen = target;
            fireAt = now + randomDelay();
        }
        if (now < fireAt) return;
        if (mc.player.getAttackCooldownProgress(0f) < chargeRoll) return;
        if (!AutoCritical.allow()) return;

        ((MinecraftClientAccessor) mc).evade$doAttack();
        fireAt = now + randomDelay();
        float c = (float) (charge.get() / 100.0);
        chargeRoll = Math.min(1f, c + (float) ThreadLocalRandom.current().nextDouble(0, 0.05));
    }

    /** True if the crosshair is on something TriggerBot would hit (used by AutoCritical to pre-jump). */
    public boolean wouldAttack() { return mc.player != null && currentTarget() != null; }

    private Entity currentTarget() {
        if (mc.currentScreen != null) return null;
        if (holdOnly.get() && !mc.options.attackKey.isPressed()) return null;
        if (pauseUsing.get() && mc.player.isUsingItem()) return null;
        if (weaponOnly.get() && !Targets.isWeapon(mc.player.getMainHandStack())) return null;
        if (!(mc.crosshairTarget instanceof EntityHitResult ehr)) return null;
        Entity e = ehr.getEntity();
        if (!targets.isValid(e)) return null;
        if (shieldCheck.get() && e instanceof PlayerEntity p && p.isBlocking()
                && !(mc.player.getMainHandStack().getItem() instanceof AxeItem)) return null;
        return e instanceof LivingEntity ? e : null;
    }

    private long randomDelay() {
        int lo = (int) Math.min(minDelay.get(), maxDelay.get());
        int hi = (int) Math.max(minDelay.get(), maxDelay.get());
        return lo == hi ? lo : ThreadLocalRandom.current().nextInt(lo, hi + 1);
    }
}
