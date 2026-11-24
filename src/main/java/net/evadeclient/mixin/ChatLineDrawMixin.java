package net.evadeclient.mixin;

import net.evadeclient.chat.ChatLines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chat Heads: the sender's face, drawn in the space in front of a message's first line, by both of chat's on-screen
 * drawers (the HUD while playing, and the open chat box). The click-test drawer draws nothing and is left alone.
 */
@Mixin(targets = {"net.minecraft.client.gui.hud.ChatHud$Hud", "net.minecraft.client.gui.hud.ChatHud$Interactable"})
public abstract class ChatLineDrawMixin {
    @Shadow @Final private DrawContext context;
    @org.spongepowered.asm.mixin.Unique
    private static boolean evade$warned;

    @Inject(method = "text(IFLnet/minecraft/text/OrderedText;)Z", at = @At("HEAD"))
    private void evade$head(int y, float opacity, OrderedText text, CallbackInfoReturnable<Boolean> cir) {
        try {
            ChatLines.drawHead(context, y, opacity, text);
        } catch (RuntimeException e) {
            if (!evade$warned) net.evadeclient.EvadeClient.LOG.error("Chat Heads could not draw a face", e);
            evade$warned = true;
        }
    }
}
