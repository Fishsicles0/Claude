package net.evadeclient.mixin;

import net.evadeclient.module.visuals.NickHider;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Nick Hider: screens that draw a raw {@code String} (sign editing, the social interactions list, Evade's own
 * menus) go through here. Paired with {@link NickHiderTextHandlerMixin#evade$nickWidth}, so text measured with
 * {@code getWidth(String)} and drawn here (e.g. centred text) still lines up.
 */
@Mixin(DrawContext.class)
public abstract class NickHiderDrawContextMixin {
    @ModifyVariable(method = "drawText(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;IIIZ)V",
            at = @At("HEAD"), argsOnly = true)
    private String evade$nick(String text) {
        return NickHider.replace(text);
    }
}
