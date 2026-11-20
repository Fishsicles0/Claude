package net.evadeclient.module.combat;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.concurrent.ThreadLocalRandom;

public class AutoClicker extends Module {
    private final NumberSetting minCps = add(new NumberSetting("Min CPS", 8, 1, 20, 1));
    private final NumberSetting maxCps = add(new NumberSetting("Max CPS", 12, 1, 20, 1));
    private long nextClick;

    public AutoClicker() {
        super("AutoClicker", "Clicks for you while holding attack on an entity.", Category.COMBAT);
    }

    @Override
    public void onTick() {
        if (!mc.options.attackKey.isPressed() || mc.currentScreen != null) return;
        long now = System.currentTimeMillis();
        if (now < nextClick) return;

        HitResult hit = mc.crosshairTarget;
        if (hit instanceof EntityHitResult entityHit) {
            mc.interactionManager.attackEntity(mc.player, entityHit.getEntity());
            mc.player.swingHand(Hand.MAIN_HAND);
        }

        int lo = Math.min(minCps.getInt(), maxCps.getInt());
        int hi = Math.max(minCps.getInt(), maxCps.getInt());
        int cps = ThreadLocalRandom.current().nextInt(lo, hi + 1);
        nextClick = now + 1000L / cps;
    }
}
