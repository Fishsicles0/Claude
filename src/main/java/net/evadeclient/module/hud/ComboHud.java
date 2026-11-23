package net.evadeclient.module.hud;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Hits landed in a row without being hit back. A swing only counts once the target visibly takes the hit (its
 * hurt animation restarts), so spam clicks during its damage cooldown, misses the server rejects and armour
 * stands don't inflate the count.
 */
public class ComboHud extends TextHudModule {
    /** Ticks to wait for the target's hurt animation after a swing. */
    private static final int CONFIRM_TICKS = 10;

    private final BooleanSetting hideWhenZero = add(new BooleanSetting("Hide When Zero", false)
            .describe("Hide the counter until you land a hit."));
    private final NumberSetting resetAfter = add(new NumberSetting("Reset After", 2.5, 1, 5, 0.5)
            .describe("Seconds without a landed hit before the combo ends."));

    private int combo;
    private long lastHit;
    private int lastHurt;
    /** The swing waiting to be confirmed: target entity id, its hurt time when swung at, last tick to confirm. */
    private int pendingId = -1, pendingHurt;
    private long pendingUntil;

    public ComboHud() { super("Combo Counter", "Hits in a row without being hit.", 4, 136); }

    @Override
    protected void onEnable() {
        combo = 0;
        pendingId = -1;
        lastHurt = mc.player == null ? 0 : mc.player.hurtTime;
    }

    @Override
    public void onAttack(Entity target) {
        if (!(target instanceof LivingEntity living) || target instanceof ArmorStandEntity) return;
        pendingId = target.getId();
        pendingHurt = living.hurtTime;
        pendingUntil = EvadeClient.ticks + CONFIRM_TICKS;
    }

    @Override
    public void onTick() {
        int hurt = mc.player.hurtTime;
        if (hurt > lastHurt) { combo = 0; pendingId = -1; } // we got hit
        lastHurt = hurt;
        if (pendingId >= 0 && mc.world != null) {
            Entity e = mc.world.getEntityById(pendingId);
            if (e instanceof LivingEntity living && living.hurtTime > pendingHurt) {
                combo++;
                lastHit = System.currentTimeMillis();
                pendingId = -1;
            } else if (e == null || EvadeClient.ticks > pendingUntil) {
                pendingId = -1;
            } else if (e instanceof LivingEntity living) {
                pendingHurt = Math.min(pendingHurt, living.hurtTime); // still fading: any restart counts
            }
        }
        if (combo > 0 && System.currentTimeMillis() - lastHit > resetAfter.get() * 1000) combo = 0;
    }

    public int combo() { return combo; }

    @Override
    protected List<String> lines(boolean preview) {
        if (combo == 0 && hideWhenZero.get() && !preview) return new ArrayList<>();
        if (combo == 0 && preview) return new ArrayList<>(List.of("3 Combo"));
        return new ArrayList<>(List.of(combo == 0 ? "No combo" : combo + " Combo"));
    }
}
