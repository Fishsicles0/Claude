package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;

import java.util.Comparator;
import java.util.stream.StreamSupport;

public class KillAura extends Module {
    private final NumberSetting range = add(new NumberSetting("Range", 3.5, 1.0, 6.0, 0.1));
    private final ModeSetting priority = add(new ModeSetting("Priority", "Distance", "Distance", "Health"));
    private final BooleanSetting players = add(new BooleanSetting("Players", true));
    private final BooleanSetting mobs = add(new BooleanSetting("Mobs", true));
    private final BooleanSetting animals = add(new BooleanSetting("Animals", false));
    private final BooleanSetting cooldown = add(new BooleanSetting("Wait Cooldown", true));

    public KillAura() {
        super("KillAura", "Automatically attacks nearby entities.", Category.COMBAT);
    }

    @Override
    public void onTick() {
        if (cooldown.get() && mc.player.getAttackCooldownProgress(0.5f) < 1.0f) return;

        double r = range.get();
        Comparator<LivingEntity> order = priority.is("Health")
                ? Comparator.comparingDouble(LivingEntity::getHealth)
                : Comparator.comparingDouble(e -> mc.player.squaredDistanceTo(e));

        StreamSupport.stream(mc.world.getEntities().spliterator(), false)
                .filter(e -> e instanceof LivingEntity)
                .map(e -> (LivingEntity) e)
                .filter(this::isValidTarget)
                .filter(e -> mc.player.squaredDistanceTo(e) <= r * r)
                .min(order)
                .ifPresent(target -> {
                    mc.interactionManager.attackEntity(mc.player, target);
                    mc.player.swingHand(Hand.MAIN_HAND);
                });
    }

    private boolean isValidTarget(LivingEntity e) {
        if (e == mc.player || !e.isAlive() || e.isInvisible()) return false;
        if (e instanceof PlayerEntity p) return players.get() && !p.isSpectator() && !p.isCreative();
        if (e instanceof Monster) return mobs.get();
        if (e instanceof AnimalEntity) return animals.get();
        return false;
    }

    @Override
    public String getSuffix() { return priority.get(); }

}
