package net.evadeclient.mixin;

import net.evadeclient.module.visuals.NickHider;
import net.minecraft.client.font.TextRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Nick Hider: {@code TextRenderer.draw(String, ...)} (raw strings drawn in the world or into a buffer) prepares the
 * glyphs here. Only the glyph positions come out, never string indices, so swapping the whole string is safe.
 */
@Mixin(TextRenderer.class)
public abstract class NickHiderTextRendererMixin {
    @ModifyVariable(method = "prepare(Ljava/lang/String;FFIZI)Lnet/minecraft/client/font/TextRenderer$GlyphDrawable;",
            at = @At("HEAD"), argsOnly = true)
    private String evade$nick(String text) {
        return NickHider.replace(text);
    }
}
