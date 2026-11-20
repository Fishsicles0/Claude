package net.evadeclient.module.world;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.minecraft.util.hit.HitResult;

/** Keeps mining whatever block you're looking at, so you don't have to hold the mouse. */
public class AutoMine extends Module {
    public AutoMine() { super("AutoMine", "Keeps mining the block you look at.", Category.WORLD); }

    @Override
    public void onTick() {
        boolean onBlock = mc.currentScreen == null && mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK;
        mc.options.attackKey.setPressed(onBlock);
    }

    @Override
    protected void onDisable() { mc.options.attackKey.setPressed(false); }
}
