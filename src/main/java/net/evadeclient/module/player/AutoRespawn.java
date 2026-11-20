package net.evadeclient.module.player;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.minecraft.client.gui.screen.DeathScreen;

/** Respawns instantly when you die. */
public class AutoRespawn extends Module {
    public AutoRespawn() { super("AutoRespawn", "Respawns instantly when you die.", Category.PLAYER); }

    @Override
    public void onTick() {
        if (mc.currentScreen instanceof DeathScreen) {
            mc.player.requestRespawn();
            mc.setScreen(null);
        }
    }
}
