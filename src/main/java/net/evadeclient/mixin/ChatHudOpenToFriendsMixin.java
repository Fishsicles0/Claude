package net.evadeclient.mixin;

import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Open to Friends: e4mc announces the world's address only as a chat line ("Local game hosted on domain [...]"), so
 * lines the client adds are shown to {@link net.evadeclient.social.OpenToFriends#onChat} first. Read only: the line
 * itself is never changed.
 */
@Mixin(ChatHud.class)
public abstract class ChatHudOpenToFriendsMixin {
    @Inject(method = "addMessage(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void evade$openToFriends(Text message, CallbackInfo ci) {
        try {
            net.evadeclient.social.OpenToFriends.onChat(message);
        } catch (RuntimeException ignored) {
            // never in the way of chat
        }
    }
}
