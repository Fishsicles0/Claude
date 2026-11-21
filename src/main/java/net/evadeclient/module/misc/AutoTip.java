package net.evadeclient.module.misc;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;

/** Automatically runs a tip command (e.g. /tip all on Hypixel) on a timer while you play. */
public class AutoTip extends Module {
    public final NumberSetting minutes = add(new NumberSetting("Every (min)", 15, 1, 60, 1));
    private long lastSent;

    public AutoTip() {
        super("AutoTip", "Sends a tip command on a timer, e.g. /tip all on Hypixel.", Category.MISC);
    }

    @Override
    protected void onEnable() { lastSent = System.currentTimeMillis(); } // don't fire instantly on toggle

    @Override
    public void onTick() {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        long interval = (long) (minutes.get() * 60_000);
        if (System.currentTimeMillis() - lastSent < interval) return;
        lastSent = System.currentTimeMillis();
        mc.player.networkHandler.sendChatCommand("tip all");
    }
}
