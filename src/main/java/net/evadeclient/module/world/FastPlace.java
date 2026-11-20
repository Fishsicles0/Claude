package net.evadeclient.module.world;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.mixin.MinecraftClientCooldownAccessor;

/** Removes the short delay between placing blocks or using items. */
public class FastPlace extends Module {
    public FastPlace() { super("FastPlace", "No delay between placing blocks.", Category.UTILITY); }

    @Override
    public void onTick() { ((MinecraftClientCooldownAccessor) mc).evade$setItemUseCooldown(0); }
}
