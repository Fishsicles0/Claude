package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.evadeclient.chat.ChatScreenExtras;
import net.evadeclient.chat.Emoji;
import net.evadeclient.module.misc.ChatEmoji;
import net.evadeclient.module.misc.ChatTabs;
import net.evadeclient.module.misc.SmoothChat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Chat Tabs, Emoji and Smooth Chat in the open chat box: the tab bar, the emoji button and grid, shortcodes turned
 * into emoji in chat messages (never in commands), Keep Open After Sending and the input box's opacity. All local.
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenV25Mixin extends Screen {
    @Shadow protected TextFieldWidget chatField;
    @Shadow private int messageHistoryIndex;

    /** What was in the box when Enter was pressed (Keep Open only keeps a box that sent something). */
    @Unique private String evade$typed = "";
    /** Set while Emoji rewrites the box, so the change it makes doesn't start another. */
    @Unique private boolean evade$converting;

    protected ChatScreenV25Mixin(Text title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void evade$init(CallbackInfo ci) {
        ChatScreenExtras.onOpen();
        ChatEmoji e = ChatEmoji.active();
        // Leave room at the end of the box for the emoji button.
        if (e != null && e.showsPicker() && chatField != null) chatField.setWidth(Math.max(40, width - 4 - 16));
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screen/ChatInputSuggestor;render(Lnet/minecraft/client/gui/DrawContext;II)V"))
    private void evade$tabs(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ChatScreenExtras.renderTabs(ctx, textRenderer, height, mouseX, mouseY);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void evade$emoji(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ChatScreenExtras.renderEmoji(ctx, textRenderer, width, height, mouseX, mouseY);
    }

    /** Smooth Chat: Input Opacity % for the box you type in. */
    @ModifyExpressionValue(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/option/GameOptions;getTextBackgroundColor(I)I"))
    private int evade$inputOpacity(int original) {
        return SmoothChat.inputColor(original);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void evade$click(Click click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        if (ChatScreenExtras.click(textRenderer, chatField, width, height, click.x(), click.y())) cir.setReturnValue(true);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void evade$keyHead(KeyInput input, CallbackInfoReturnable<Boolean> cir) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE && ChatScreenExtras.escape()) {
            cir.setReturnValue(true);
            return;
        }
        evade$typed = chatField == null ? "" : chatField.getText();
    }

    /** Smooth Chat Keep Open After Sending: Enter sends and empties the box instead of closing it. */
    @WrapOperation(method = "keyPressed", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/MinecraftClient;setScreen(Lnet/minecraft/client/gui/screen/Screen;)V"))
    private void evade$keepOpen(MinecraftClient mc, Screen screen, Operation<Void> original) {
        // Only while this box is still the screen: a command that opened another one wins.
        if (screen == null && SmoothChat.keepsOpen() && !evade$typed.isBlank() && mc.currentScreen == (Object) this && chatField != null) {
            chatField.setText("");
            messageHistoryIndex = mc.inGameHud.getChatHud().getMessageHistory().size();
            return;
        }
        original.call(mc, screen);
    }

    /** Emoji: a shortcode becomes its emoji the moment its closing colon is typed (chat messages only). */
    @Inject(method = "onChatFieldUpdate", at = @At("TAIL"))
    private void evade$liveEmoji(String text, CallbackInfo ci) {
        if (evade$converting || chatField == null) return;
        ChatEmoji e = ChatEmoji.active();
        if (e == null || !e.converts() || !ChatEmoji.isMessage(text)) return;
        Emoji.Edit edit = Emoji.atCursor(text, chatField.getCursor());
        if (edit == null) return;
        evade$converting = true;
        try {
            chatField.setText(edit.text());
            chatField.setCursor(edit.cursor(), false);
        } finally {
            evade$converting = false;
        }
    }

    /** Emoji: any shortcode still in a chat message is converted as it is sent; nothing else changes. */
    @ModifyVariable(method = "sendMessage", at = @At("HEAD"), argsOnly = true)
    private String evade$sendEmoji(String chatText) {
        return ChatEmoji.onSend(chatText);
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void evade$closed(CallbackInfo ci) {
        ChatTabs t = ChatTabs.active();
        if (t != null) t.onChatClosed();
    }
}
