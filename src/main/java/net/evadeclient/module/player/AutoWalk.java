package net.evadeclient.module.player;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;

/** Keeps walking forward for you. */
public class AutoWalk extends Module {
    public AutoWalk() { super("AutoWalk", "Walks forward for you.", Category.MOVEMENT); }

    @Override
    public void onTick() { mc.options.forwardKey.setPressed(true); }

    @Override
    protected void onDisable() { mc.options.forwardKey.setPressed(false); }
}
