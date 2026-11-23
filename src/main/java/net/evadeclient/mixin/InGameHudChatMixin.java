package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.evadeclient.module.misc.BetterChat;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * BetterChat's Keep On Disconnect (and Keep Chat On Server Switch): {@code InGameHud.clear()} runs when you leave a
 * server or world and when a network moves you between its servers; this skips only its chat clear, so the player
 * list, boss bars, titles and toasts still reset. Clearing chat yourself (F3 + D) is untouched.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudChatMixin {
    @WrapWithCondition(method = "clear()V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/ChatHud;clear(Z)V"))
    private boolean evade$keepChat(ChatHud chat, boolean clearHistory) {
        return !BetterChat.keepChatOnHudClear(chat);
    }
}
