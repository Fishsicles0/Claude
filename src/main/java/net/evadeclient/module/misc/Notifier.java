package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.command.CommandManager;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.util.Notifications;
import net.minecraft.entity.Entity;

/**
 * Tells you when your own totem pops, with how many you have popped since your last death. Only ever looks at you:
 * other players' pops and whereabouts are none of its business.
 */
public class Notifier extends Module {
    private final BooleanSetting pops = add(new BooleanSetting("Totem Pops", true)
            .describe("A chat line and a toast each time your own totem pops."));
    /** Your pops since you last died or changed world. */
    private int popCount;
    private Object world;

    public Notifier() {
        super("Notifier", "Tells you when your own totem pops.", Category.MISC);
    }

    /** Called from the network mixin when the local player plays the totem-pop status (35). */
    public static void onPop(Entity e) {
        if (EvadeClient.modules == null || e == null || e != EvadeClient.mc().player) return;
        Notifier n = EvadeClient.modules.get(Notifier.class);
        if (n == null || !n.isEnabled() || !n.pops.get()) return;
        n.syncWorld();
        int c = ++n.popCount;
        String what = c == 1 ? "a totem" : c + " totems";
        CommandManager.print("§cYou§7 popped " + what);
        Notifications.push("Totem pop", "You popped " + what, false);
    }

    /** How many totems you have popped since your last death or world change (for the tests). */
    public int pops() { return popCount; }

    @Override
    protected void onDisable() { popCount = 0; }

    @Override
    public void onTick() {
        syncWorld();
        if (mc.player != null && mc.player.isDead()) popCount = 0; // the count restarts with each life
    }

    private void syncWorld() {
        if (world != mc.world) {
            world = mc.world;
            popCount = 0;
        }
    }
}
