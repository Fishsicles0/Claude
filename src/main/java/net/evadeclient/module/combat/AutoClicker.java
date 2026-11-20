package net.evadeclient.module.combat;

import net.evadeclient.mixin.MinecraftClientAccessor;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Targets;
import net.minecraft.util.hit.HitResult;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Clicks for you while you hold a mouse button. Runs per frame (not per tick) so the click rate is
 * accurate, with gaussian-randomised gaps and occasional short pauses so the pattern isn't robotic.
 */
public class AutoClicker extends Module {
    private final NumberSetting minCps = add(new NumberSetting("Min CPS", 9, 1, 25, 1));
    private final NumberSetting maxCps = add(new NumberSetting("Max CPS", 13, 1, 25, 1));
    private final BooleanSetting left = add(new BooleanSetting("Left Click", true));
    private final BooleanSetting right = add(new BooleanSetting("Right Click", false));
    private final BooleanSetting smart = add(new BooleanSetting("Smart Cooldown", false));
    private final BooleanSetting weaponOnly = add(new BooleanSetting("Weapon Only", false));
    private final BooleanSetting breakBlocks = add(new BooleanSetting("Break Blocks", true));

    private long nextLeft, nextRight;

    public AutoClicker() {
        super("AutoClicker", "Clicks for you while holding a mouse button.", Category.COMBAT);
    }

    @Override
    public void onRender(RenderContext ctx) {
        if (mc.currentScreen != null) return;
        long now = System.currentTimeMillis();
        MinecraftClientAccessor client = (MinecraftClientAccessor) mc;

        if (left.get() && mc.options.attackKey.isPressed() && now >= nextLeft && canLeftClick()) {
            if (!smart.get() || mc.player.getAttackCooldownProgress(0f) >= 0.9f) {
                if (AutoCritical.allow()) {
                    client.evade$doAttack();
                    nextLeft = now + nextDelay();
                }
            }
        }
        if (right.get() && mc.options.useKey.isPressed() && now >= nextRight && !mc.player.isUsingItem()) {
            client.evade$doItemUse();
            nextRight = now + nextDelay();
        }
    }

    private boolean canLeftClick() {
        if (weaponOnly.get() && !Targets.isWeapon(mc.player.getMainHandStack())) return false;
        // Holding left on a block should mine it normally rather than spam-click it.
        return !(breakBlocks.get() && mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK);
    }

    private long nextDelay() {
        int lo = Math.min(minCps.getInt(), maxCps.getInt());
        int hi = Math.max(minCps.getInt(), maxCps.getInt());
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double mean = (lo + hi) / 2.0;
        double cps = Math.max(lo, Math.min(hi, mean + r.nextGaussian() * (hi - lo) / 4.0));
        long delay = Math.round(1000.0 / cps);
        if (r.nextInt(40) == 0) delay += r.nextInt(60, 140); // occasional human-like hesitation
        return delay;
    }

    @Override
    public String getSuffix() { return minCps.getInt() + "-" + maxCps.getInt(); }
}
