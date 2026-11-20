package net.evadeclient.module.player;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.util.Hand;

import java.util.concurrent.ThreadLocalRandom;

/** Stops you being kicked for being AFK: jumps, swings and turns a little now and then. */
public class AntiAFK extends Module {
    private final NumberSetting interval = add(new NumberSetting("Every Seconds", 30, 5, 300, 5));
    private int timer;

    public AntiAFK() { super("AntiAFK", "Stops AFK kicks.", Category.PLAYER); }

    @Override
    public void onTick() {
        if (++timer < interval.get() * 20) return;
        timer = 0;
        var r = ThreadLocalRandom.current();
        if (mc.player.isOnGround()) mc.player.jump();
        mc.player.swingHand(Hand.MAIN_HAND);
        mc.player.setYaw(mc.player.getYaw() + r.nextInt(-30, 31));
    }
}
