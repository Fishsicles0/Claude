package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.hit.EntityHitResult;

/**
 * Makes your hits critical.
 * <ul>
 *   <li><b>Jump</b>: hops when you're about to hit and holds TriggerBot/AutoClicker swings until you're falling,
 *       so every hit lands as a real crit.</li>
 *   <li><b>Packet</b>: sends a tiny fake hop before each hit while standing still on the ground.</li>
 * </ul>
 */
public class AutoCritical extends Module {
    private final ModeSetting mode = add(new ModeSetting("Mode", "Jump", "Jump", "Packet"));
    private final BooleanSetting onlyWhenReady = add(new BooleanSetting("Only When Ready", true));
    private final BooleanSetting stopSprint = add(new BooleanSetting("Stop Sprint", true));

    public AutoCritical() {
        super("AutoCritical", "Turns your hits into critical hits.", Category.COMBAT);
    }

    @Override
    public void onTick() {
        if (!mode.is("Jump") || !canCritHere() || !mc.player.isOnGround()) return;
        if (!(mc.crosshairTarget instanceof EntityHitResult ehr) || !(ehr.getEntity() instanceof LivingEntity)) return;

        boolean intendsToHit = mc.options.attackKey.isPressed() || attackerModuleActive();
        if (!intendsToHit) return;
        // Jump early enough that we're falling when the weapon is charged.
        if (onlyWhenReady.get() && mc.player.getAttackCooldownProgress(0f) < 0.55f) return;
        mc.player.jump();
    }

    @Override
    public void onAttack(Entity target) {
        if (!(target instanceof LivingEntity)) return;
        if (stopSprint.get() && mc.player.isSprinting()) {
            // Sprinting cancels crits; tell the server we stopped before the hit lands.
            mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.STOP_SPRINTING));
            mc.player.setSprinting(false);
        }
        if (!mode.is("Packet") || !mc.player.isOnGround() || !canCritHere()) return;
        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        boolean hc = mc.player.horizontalCollision;
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y + 0.0625, z, false, hc));
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, false, hc));
    }

    /**
     * Called by TriggerBot/AutoClicker before swinging. In Jump mode this delays the swing
     * until the player is on the way down so the hit crits.
     */
    public boolean allowAttack() {
        if (!isEnabled() || !mode.is("Jump") || !canCritHere()) return true;
        if (mc.player.isOnGround()) return false;           // we're about to jump
        return mc.player.getVelocity().y < -0.05 && mc.player.fallDistance > 0;
    }

    private boolean canCritHere() {
        return !mc.player.isTouchingWater() && !mc.player.isInLava() && !mc.player.isClimbing()
                && !mc.player.hasVehicle() && !mc.player.hasStatusEffect(StatusEffects.BLINDNESS)
                && !mc.player.getAbilities().flying && !mc.player.isGliding();
    }

    private boolean attackerModuleActive() {
        var modules = net.evadeclient.EvadeClient.modules;
        TriggerBot tb = modules.get(TriggerBot.class);
        AutoClicker ac = modules.get(AutoClicker.class);
        return (tb != null && tb.isEnabled() && tb.wouldAttack()) || (ac != null && ac.isEnabled() && mc.options.attackKey.isPressed());
    }

    @Override
    public String getSuffix() { return mode.get(); }

    public static boolean allow() {
        AutoCritical ac = net.evadeclient.EvadeClient.modules.get(AutoCritical.class);
        return ac == null || ac.allowAttack();
    }

}
