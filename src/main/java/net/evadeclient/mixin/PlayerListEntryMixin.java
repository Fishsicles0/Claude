package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.evadeclient.module.visuals.NickHider;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.SkinTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Nick Hider skins: Own Skin, Hide Cape and Hide Others' Skins. The tab list's heads, the player model
 * ({@code AbstractClientPlayerEntity.getSkin} delegates to the entry) and the spectator and social menus all take a
 * player's skin from their list entry, so swapping it here covers every place a skin is drawn. Your screen only:
 * nothing about skins is ever sent to the server.
 */
@Mixin(PlayerListEntry.class)
public abstract class PlayerListEntryMixin {
    @ModifyReturnValue(method = "getSkinTextures", at = @At("RETURN"))
    private SkinTextures evade$nickSkin(SkinTextures original) {
        if (NickHider.active() == null) return original;
        return NickHider.skin(((PlayerListEntry) (Object) this).getProfile().id(), original);
    }
}
