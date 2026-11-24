package net.evadeclient.mixin;

import net.evadeclient.command.ChatCompletion;
import net.evadeclient.util.ChatCoordinates;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Evade's chat extras, all local: a grey ghost suggestion and Tab / Shift+Tab completion for dot commands (vanilla
 * keeps Tab for everything else), and clickable coordinates in chat lines that save a waypoint. Nothing is sent.
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenEvadeMixin extends Screen {
    @Shadow protected TextFieldWidget chatField;

    /** The text Tab last put in the field, the options it came from, and which one it is. */
    @Unique private String evade$applied;
    @Unique private ChatCompletion.Result evade$result;
    @Unique private int evade$index;

    protected ChatScreenEvadeMixin(Text title) { super(title); }

    @Inject(method = "onChatFieldUpdate", at = @At("TAIL"))
    private void evade$ghost(String text, CallbackInfo ci) {
        if (chatField == null || text == null || !text.startsWith(".")) return;
        if (text.equals(evade$applied)) { chatField.setSuggestion(null); return; }
        chatField.setSuggestion(ChatCompletion.complete(text).ghost());
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void evade$tab(KeyInput input, CallbackInfoReturnable<Boolean> cir) {
        if (input.key() != GLFW.GLFW_KEY_TAB || chatField == null) return;
        String text = chatField.getText();
        if (!text.startsWith(".") || text.startsWith("..")) return;
        boolean back = (input.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        if (evade$result != null && text.equals(evade$applied)) {
            evade$index += back ? -1 : 1;
        } else {
            evade$result = ChatCompletion.complete(text);
            if (evade$result.options().isEmpty()) { evade$result = null; cir.setReturnValue(true); return; }
            evade$index = back ? -1 : 0;
            // A word already typed in full moves straight on to the next option.
            if (evade$result.apply(text, evade$index).equals(text) && evade$result.options().size() > 1) evade$index += back ? -1 : 1;
        }
        String next = evade$result.apply(text, evade$index);
        evade$applied = next;
        chatField.setText(next);
        chatField.setCursorToEnd(false);
        cir.setReturnValue(true);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void evade$coordsClick(Click click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT || client == null) return;
        ChatCoordinates.Hit hit = ChatCoordinates.at(client, textRenderer, click.x(), click.y());
        if (hit != null && ChatCoordinates.save(hit)) cir.setReturnValue(true);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void evade$coordsHover(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (client == null) return;
        ChatCoordinates.renderHover(ctx, client, textRenderer, mouseX, mouseY);
    }
}
