package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.InvUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;

/**
 * Throws splash potions at your feet: healing when low, and optionally buffs
 * (strength, speed, fire resistance) whenever they've run out.
 */
public class AutoPot extends Module {
    private final NumberSetting health = add(new NumberSetting("Health", 12, 2, 19, 1));
    private final NumberSetting delay = add(new NumberSetting("Delay ms", 450, 100, 2000, 50));
    private final BooleanSetting regen = add(new BooleanSetting("Use Regen", true));
    private final BooleanSetting strength = add(new BooleanSetting("Strength", true));
    private final BooleanSetting speed = add(new BooleanSetting("Speed", true));
    private final BooleanSetting fireRes = add(new BooleanSetting("Fire Res", true));
    private final NumberSetting rebuff = add(new NumberSetting("Rebuff Secs", 3, 0, 30, 1));
    private final BooleanSetting refill = add(new BooleanSetting("Refill Hotbar", true));
    private final BooleanSetting onGround = add(new BooleanSetting("Ground Only", true));
    private final BooleanSetting pauseEating = add(new BooleanSetting("Pause Eating", true));

    private long lastThrow;

    public AutoPot() {
        super("AutoPot", "Throws healing and buff splash potions automatically.", Category.COMBAT);
    }

    @Override
    public void onTick() {
        if (System.currentTimeMillis() - lastThrow < delay.get()) return;
        if (onGround.get() && !mc.player.isOnGround()) return;
        if (pauseEating.get() && mc.player.isUsingItem()) return;
        if (mc.currentScreen != null) return;

        // Healing has priority.
        if (mc.player.getHealth() <= health.get()) {
            if (throwPotion(StatusEffects.INSTANT_HEALTH)) return;
            if (regen.get() && !mc.player.hasStatusEffect(StatusEffects.REGENERATION)
                    && throwPotion(StatusEffects.REGENERATION)) return;
        }
        if (strength.get() && needsBuff(StatusEffects.STRENGTH) && throwPotion(StatusEffects.STRENGTH)) return;
        if (speed.get() && needsBuff(StatusEffects.SPEED) && throwPotion(StatusEffects.SPEED)) return;
        if (fireRes.get() && (mc.player.isOnFire() || mc.player.isInLava())
                && needsBuff(StatusEffects.FIRE_RESISTANCE)) throwPotion(StatusEffects.FIRE_RESISTANCE);
    }

    private boolean needsBuff(RegistryEntry<StatusEffect> effect) {
        StatusEffectInstance active = mc.player.getStatusEffect(effect);
        return active == null || active.getDuration() <= rebuff.get() * 20;
    }

    private boolean throwPotion(RegistryEntry<StatusEffect> effect) {
        int slot = InvUtil.ensureInHotbar(s -> isSplashWith(s, effect), refill.get());
        if (slot == -1) return false;
        InvUtil.useHotbarItem(slot, 90f);
        lastThrow = System.currentTimeMillis();
        return true;
    }

    static boolean isSplashWith(ItemStack stack, RegistryEntry<StatusEffect> effect) {
        if (!stack.isOf(Items.SPLASH_POTION)) return false;
        PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
        if (contents == null) return false;
        for (StatusEffectInstance e : contents.getEffects()) {
            if (e.getEffectType().value() == effect.value()) return true;
        }
        return false;
    }

    @Override
    public String getSuffix() {
        if (mc.player == null) return null;
        return String.valueOf(InvUtil.count(s -> isSplashWith(s, StatusEffects.INSTANT_HEALTH)));
    }
}
