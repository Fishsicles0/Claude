package net.evadeclient.mixin;

import net.minecraft.client.gui.hud.PlayerListHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Whether the tab player list is open, so the Direction HUD can step aside for it. Read only. */
@Mixin(PlayerListHud.class)
public interface PlayerListHudAccessor {
    @Accessor("visible")
    boolean evade$isVisible();
}
