package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.evadeclient.module.misc.BetterChat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
    @Unique
    private static final String ADD_MESSAGE =
            "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V";

    @Shadow @Final MinecraftClient client;
    @Shadow @Final private List<ChatHudLine> messages;
    @Shadow @Final private List<ChatHudLine.Visible> visibleMessages;
    @Shadow private int scrolledLines;

    /** The message as received, before BetterChat decorated it (null when it didn't): what latest.log records. */
    @Unique
    private Text evade$undecorated;

    /** BetterChat: stack repeats (drop the previous identical line) and add the timestamp, copy button and mention bar. */
    @ModifyVariable(method = ADD_MESSAGE, at = @At("HEAD"), argsOnly = true)
    private Text evade$betterChat(Text message) {
        evade$undecorated = null;
        BetterChat bc = BetterChat.active();
        if (bc == null || message == null) return message;
        if (bc.repeatsLast(message, messages.isEmpty() ? null : messages.getFirst())) evade$removeNewest();
        evade$undecorated = message;
        return bc.decorate(message);
    }

    /** BetterChat: remember the line just added, so only a repeat of that exact line is ever stacked. */
    @Inject(method = ADD_MESSAGE, at = @At("TAIL"))
    private void evade$rememberLine(Text message, MessageSignatureData signature, MessageIndicator indicator, CallbackInfo ci) {
        BetterChat bc = evade$undecorated == null ? null : BetterChat.active();
        if (bc != null && !messages.isEmpty()) bc.added(messages.getFirst());
        evade$undecorated = null;
    }

    /** BetterChat: latest.log keeps the message as received, without our timestamp, counter or buttons. */
    @WrapOperation(method = ADD_MESSAGE, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/ChatHud;logChatMessage(Lnet/minecraft/client/gui/hud/ChatHudLine;)V"))
    private void evade$logUndecorated(ChatHud self, ChatHudLine line, Operation<Void> original) {
        Text raw = evade$undecorated;
        original.call(self, raw == null ? line : new ChatHudLine(line.creationTick(), raw, line.signature(), line.indicator()));
    }

    /**
     * Removes the newest message and its wrapped lines (newest first: its last line, which ends the entry, then the
     * lines above it), keeping a scrolled-up view where it was instead of rebuilding all of chat.
     */
    @Unique
    private void evade$removeNewest() {
        messages.removeFirst();
        int removed = 0;
        if (!visibleMessages.isEmpty()) {
            visibleMessages.removeFirst();
            removed++;
        }
        while (!visibleMessages.isEmpty() && !visibleMessages.getFirst().endOfEntry()) {
            visibleMessages.removeFirst();
            removed++;
        }
        scrolledLines = Math.max(0, scrolledLines - removed);
    }

    /**
     * Chat is being cleared: forget BetterChat's last line (even while it's off), or keep everything when it's a
     * server switch and Keep Chat On Server Switch is on.
     */
    @Inject(method = "clear(Z)V", at = @At("HEAD"), cancellable = true)
    private void evade$clear(boolean clearHistory, CallbackInfo ci) {
        if (BetterChat.keepsChatNow()) {
            client.getMessageHandler().processAll(); // what vanilla would do first: show messages still queued
            ci.cancel();
            return;
        }
        BetterChat.onChatCleared();
    }

    /** BetterChat: History Lines (vanilla keeps 100 messages). Optional so another chat mod changing it can't crash. */
    @ModifyConstant(method = "addMessage(Lnet/minecraft/client/gui/hud/ChatHudLine;)V", constant = @Constant(intValue = 100), require = 0)
    private int evade$messageLimit(int limit) {
        return BetterChat.historyLimit(limit);
    }

    /** BetterChat: History Lines for the wrapped lines chat can scroll through. */
    @ModifyConstant(method = "addVisibleMessage(Lnet/minecraft/client/gui/hud/ChatHudLine;)V", constant = @Constant(intValue = 100), require = 0)
    private int evade$visibleLimit(int limit) {
        return BetterChat.historyLimit(limit);
    }
}
