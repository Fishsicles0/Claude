package net.evadeclient.mixin;

import net.evadeclient.module.misc.EvadeIcons;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {
    /**
     * Evade Icons: prefix the tab-list name with the player's icon glyph. Because the icon is part of the name,
     * the tab list measures it itself, so wider entries never overlap the name or the ping bars.
     */
    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void evade$icon(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        Text decorated = EvadeIcons.decorate(entry.getProfile().id(), cir.getReturnValue());
        if (decorated != cir.getReturnValue()) cir.setReturnValue(decorated);
    }
}
