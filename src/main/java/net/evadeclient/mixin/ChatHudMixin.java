package net.evadeclient.mixin;

import net.evadeclient.module.misc.BetterChat;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
    @Shadow @Final private List<ChatHudLine> messages;
    @Shadow private void refresh() {}

    /** BetterChat: stack repeats (drop the previous identical line) and add a timestamp. */
    @ModifyVariable(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
            at = @At("HEAD"), argsOnly = true)
    private Text evade$betterChat(Text message) {
        BetterChat bc = BetterChat.active();
        if (bc == null) return message;
        if (bc.repeatsLast(message) && !messages.isEmpty()) {
            messages.removeFirst(); // newest line is first
            refresh();
        }
        return bc.decorate(message);
    }
}
