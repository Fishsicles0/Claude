package net.evadeclient.module.world;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.mixin.InteractionManagerAccessor;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;

/**
 * Mines faster: removes the pause between blocks, and can finish a block early once it's mostly broken
 * (the "InstaMine" idea). Finishing very early is easy for servers to detect.
 */
public class FastBreak extends Module {
    private final BooleanSetting noDelay = add(new BooleanSetting("No Delay", true));
    private final NumberSetting finishAt = add(new NumberSetting("Finish At %", 85, 50, 100, 5));

    public FastBreak() { super("FastBreak", "Mine blocks faster.", Category.WORLD); }

    @Override
    public void onTick() {
        InteractionManagerAccessor im = (InteractionManagerAccessor) mc.interactionManager;
        if (noDelay.get()) im.evade$setBlockBreakingCooldown(0);
        if (finishAt.get() < 100 && im.evade$getBreakingProgress() >= finishAt.get() / 100.0) im.evade$setBreakingProgress(1f);
    }
}
