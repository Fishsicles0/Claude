package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.evadeclient.EvadeClient;
import net.evadeclient.chat.ChatLines;
import net.evadeclient.module.misc.ChatTabs;
import net.evadeclient.module.misc.SmoothChat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Chat Heads, Chat Tabs and Smooth Chat in the chat HUD: records each message and puts the head's space in front of
 * it, shows only the selected tab's lines, slides new lines in and sets the line background's opacity. Everything
 * is on screen only; with the three modules off each hook leaves vanilla's behaviour as it is.
 */
@Mixin(ChatHud.class)
public abstract class ChatHudV25Mixin {
    @Unique
    private static final String ADD_MESSAGE =
            "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V";
    @Unique
    private static final String ADD_VISIBLE = "Lnet/minecraft/client/gui/hud/ChatHud;addVisibleMessage(Lnet/minecraft/client/gui/hud/ChatHudLine;)V";
    @Unique
    private static boolean evade$warned;

    @Shadow @Final MinecraftClient client;
    @Shadow private int scrolledLines;

    /** The text as it was before Chat Heads put its space in front (what latest.log should record). */
    @Unique
    private Text evade$beforeHead;
    /** The text with the head's space, as stored. */
    @Unique
    private Text evade$headed;

    /** Records the message for Chat Heads and Chat Tabs; with a head, the stored text gets its space in front. */
    @ModifyVariable(method = ADD_MESSAGE, at = @At("HEAD"), argsOnly = true)
    private Text evade$record(Text message) {
        try {
            Text out = ChatLines.onAdd(message);
            evade$beforeHead = out != message ? message : null;
            evade$headed = out != message ? out : null;
            return out;
        } catch (RuntimeException e) {
            if (!evade$warned) EvadeClient.LOG.error("Chat Heads / Chat Tabs could not read a chat line", e);
            evade$warned = true;
            evade$beforeHead = null;
            evade$headed = null;
            return message;
        }
    }

    /** latest.log keeps the line without the head's space. */
    @WrapOperation(method = ADD_MESSAGE, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/ChatHud;logChatMessage(Lnet/minecraft/client/gui/hud/ChatHudLine;)V"))
    private void evade$logWithoutHead(ChatHud self, ChatHudLine line, Operation<Void> original) {
        Text before = evade$beforeHead, headed = evade$headed;
        evade$beforeHead = null;
        evade$headed = null;
        // Only the stored line itself: BetterChat may already have swapped in the undecorated message.
        boolean ours = before != null && line.content() == headed;
        original.call(self, ours ? new ChatHudLine(line.creationTick(), before, line.signature(), line.indicator()) : line);
    }

    /** A new message: shown only if the selected chat tab has it; its lines may slide in. */
    @WrapOperation(method = ADD_MESSAGE, at = @At(value = "INVOKE", target = ADD_VISIBLE))
    private void evade$tabNew(ChatHud self, ChatHudLine line, Operation<Void> original) {
        if (!ChatTabs.shows(line)) return;
        ChatLines.setFresh(scrolledLines == 0);
        try {
            original.call(self, line);
        } finally {
            ChatLines.setFresh(false);
        }
    }

    /** Re-wrapping every line (resize, tab switch, deleted message): only the selected tab's. */
    @WrapOperation(method = "refresh", at = @At(value = "INVOKE", target = ADD_VISIBLE))
    private void evade$tabRefresh(ChatHud self, ChatHudLine line, Operation<Void> original) {
        if (ChatTabs.shows(line)) original.call(self, line);
    }

    /** The screen lines a message was wrapped into: the first one carries the head. */
    @WrapOperation(method = "addVisibleMessage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/ChatHudLine;breakLines(Lnet/minecraft/client/font/TextRenderer;I)Ljava/util/List;"))
    private List<OrderedText> evade$rows(ChatHudLine line, TextRenderer tr, int width, Operation<List<OrderedText>> original) {
        List<OrderedText> rows = original.call(line, tr, width);
        try {
            ChatLines.rows(line, rows);
        } catch (RuntimeException e) {
            if (!evade$warned) EvadeClient.LOG.error("Chat Heads could not mark a chat line", e);
            evade$warned = true;
        }
        return rows;
    }

    /** Smooth Chat: new lines slide up into place (the whole chat is drawn a little lower, easing back to 0). */
    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/font/TextRenderer;IIIZZ)V",
            at = @At(value = "INVOKE", target = "Lorg/joml/Matrix3x2fStack;pushMatrix()Lorg/joml/Matrix3x2fStack;", shift = At.Shift.AFTER))
    private void evade$slide(DrawContext ctx, TextRenderer tr, int tick, int mx, int my, boolean interactable, boolean shift, CallbackInfo ci) {
        if (scrolledLines != 0) return;
        double spacing = client.options.getChatLineSpacing().getValue();
        double lineHeight = (int) (9.0 * (spacing + 1.0)) * client.options.getChatScale().getValue();
        float dy = SmoothChat.slideOffset(lineHeight);
        if (dy > 0.01f) ctx.getMatrices().translate(0, dy);
    }

    /** Smooth Chat: Background Opacity % for the lines' background (vanilla's Text Background Opacity otherwise). */
    @ModifyExpressionValue(method = "render(Lnet/minecraft/client/gui/hud/ChatHud$Backend;IIZ)V",
            at = @At(value = "INVOKE", target = "Ljava/lang/Double;floatValue()F", ordinal = 1))
    private float evade$background(float original) {
        return SmoothChat.backgroundOpacity(original);
    }
}
