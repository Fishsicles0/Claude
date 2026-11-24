package net.evadeclient.mixin;

import net.evadeclient.module.visuals.NickHider;
import net.minecraft.client.font.TextHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Nick Hider for raw strings that are measured or wrapped. Only entry points whose whole result is built from the
 * string they were given are swapped: the width, and the list form of {@code wrapLines} (its lines are cut from
 * the swapped string itself). Methods that hand string indices back to the caller ({@code getEndingIndex},
 * {@code trimToWidth(String)}, the consumer form of {@code wrapLines}) are deliberately left alone: text fields and
 * edit boxes apply those indices to their own, unswapped text, and swapping there brings back the
 * out-of-bounds crash this design exists to prevent.
 */
@Mixin(TextHandler.class)
public abstract class NickHiderTextHandlerMixin {
    @ModifyVariable(method = "getWidth(Ljava/lang/String;)F", at = @At("HEAD"), argsOnly = true)
    private String evade$nickWidth(String text) {
        return NickHider.replace(text);
    }

    @ModifyVariable(method = "wrapLines(Ljava/lang/String;ILnet/minecraft/text/Style;)Ljava/util/List;", at = @At("HEAD"), argsOnly = true)
    private String evade$nickWrap(String text) {
        return NickHider.replace(text);
    }
}
