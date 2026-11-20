package net.evadeclient.module.player;

import net.evadeclient.mixin.MinecraftClientAccessor;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;

public class FastPlace extends Module {
    public FastPlace() {
        super("FastPlace", "Removes the delay between placing blocks.", Category.PLAYER);
    }

    @Override
    public void onTick() {
        ((MinecraftClientAccessor) mc).evade$setItemUseCooldown(0);
    }
}
