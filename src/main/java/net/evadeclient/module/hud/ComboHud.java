package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;

/** Hits landed in a row without being hit back. */
public class ComboHud extends TextHudModule {
    private int combo;
    private long lastHit;
    private int lastHurt;

    public ComboHud() { super("Combo Counter", "Hits in a row without being hit.", 4, 280); }

    @Override
    public void onAttack(Entity target) {
        if (target instanceof LivingEntity) {
            combo++;
            lastHit = System.currentTimeMillis();
        }
    }

    @Override
    public void onTick() {
        int hurt = mc.player.hurtTime;
        if (hurt > lastHurt) combo = 0; // we got hit
        lastHurt = hurt;
        if (System.currentTimeMillis() - lastHit > 2500) combo = 0;
    }

    @Override
    protected List<String> lines(boolean preview) {
        return new ArrayList<>(List.of(combo == 0 ? "No combo" : combo + " Combo"));
    }
}
