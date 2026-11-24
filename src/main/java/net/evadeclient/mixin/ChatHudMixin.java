package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.evadeclient.EvadeClient;
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
    /** Its indicator as received (BetterChat may swap in a mention bar): what latest.log tags the line with. */
    @Unique
    private MessageIndicator evade$rawIndicator;
    @Unique
    private static boolean evade$warned;

    /**
     * BetterChat: stack repeats (the stored copy is removed and the new line carries the counter), add the timestamp,
     * copy button, mention colours and bar, or collapse a filtered line. Off, or on any error, the message is added
     * exactly as vanilla would.
     */
    @WrapMethod(method = ADD_MESSAGE)
    private void evade$betterChat(Text received, MessageSignatureData signature, MessageIndicator indicator, Operation<Void> original) {
        // Friend Colours and Hypixel Stats' chat stat first (display only), then BetterChat.
        Text message = received;
        try {
            message = net.evadeclient.module.misc.HypixelStats.chatDecorate(net.evadeclient.social.FriendColors.chat(received));
        } catch (RuntimeException e) {
            if (!evade$warned) EvadeClient.LOG.error("Could not colour friends in a chat line", e);
            evade$warned = true;
        }
        BetterChat bc = message == null ? null : BetterChat.active();
        BetterChat.Prepared p = null;
        if (bc != null) {
            try {
                p = bc.prepare(message, signature, indicator, messages);
            } catch (RuntimeException e) {
                if (!evade$warned) EvadeClient.LOG.error("BetterChat could not decorate a chat line", e);
                evade$warned = true;
            }
        }
        if (p == null) {
            if (message == received) {
                original.call(message, signature, indicator);
                return;
            }
            Text prevRaw = evade$undecorated;
            MessageIndicator prevIndicator = evade$rawIndicator;
            evade$undecorated = received;
            evade$rawIndicator = indicator;
            try {
                original.call(message, signature, indicator);
            } finally {
                evade$undecorated = prevRaw;
                evade$rawIndicator = prevIndicator;
            }
            return;
        }
        if (p.stackIndex() >= 0 && p.stackIndex() < messages.size() && messages.get(p.stackIndex()) == p.replaces()) {
            evade$removeEntry(p.stackIndex());
        }
        ChatHudLine before = messages.isEmpty() ? null : messages.getFirst();
        Text prevRaw = evade$undecorated;
        MessageIndicator prevIndicator = evade$rawIndicator;
        evade$undecorated = received;
        evade$rawIndicator = indicator;
        try {
            original.call(p.text(), signature, p.indicator());
        } finally {
            evade$undecorated = prevRaw;
            evade$rawIndicator = prevIndicator;
        }
        // Remember the stored line (unless something else stopped it being added), so only it is ever stacked onto.
        if (!messages.isEmpty() && messages.getFirst() != before) bc.added(messages.getFirst(), p);
    }

    /** BetterChat: latest.log keeps the message as received, without our timestamp, counter, buttons or bar. */
    @WrapOperation(method = ADD_MESSAGE, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/ChatHud;logChatMessage(Lnet/minecraft/client/gui/hud/ChatHudLine;)V"))
    private void evade$logUndecorated(ChatHud self, ChatHudLine line, Operation<Void> original) {
        Text raw = evade$undecorated;
        original.call(self, raw == null ? line : new ChatHudLine(line.creationTick(), raw, line.signature(), evade$rawIndicator));
    }

    /**
     * Removes stored message {@code index} (newest first) and its wrapped lines. Each message's lines sit together
     * in the visible list, its last line (marked endOfEntry) first, so message i is the i-th group starting at such a
     * line. A scrolled-up view stays on the same lines instead of jumping.
     */
    @Unique
    private void evade$removeEntry(int index) {
        messages.remove(index);
        int group = -1, start = -1, end = visibleMessages.size();
        for (int i = 0; i < visibleMessages.size(); i++) {
            if (!visibleMessages.get(i).endOfEntry()) continue;
            group++;
            if (group == index) {
                start = i;
            } else if (group == index + 1) {
                end = i;
                break;
            }
        }
        if (start < 0) return; // already scrolled out of the visible lines
        int removed = end - start;
        visibleMessages.subList(start, end).clear();
        if (scrolledLines >= end) scrolledLines -= removed;
        else if (scrolledLines > start) scrolledLines = start;
        scrolledLines = Math.max(0, scrolledLines);
    }

    /**
     * Chat is being cleared: forget BetterChat's lines (even while it's off), or keep everything when it's a server
     * switch and Keep Chat On Server Switch is on. (The InGameHud mixin keeps chat before it gets here on a
     * disconnect; this covers the switch on its own.)
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
