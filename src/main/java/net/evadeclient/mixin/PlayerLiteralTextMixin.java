package net.evadeclient.mixin;

import net.evadeclient.module.visuals.NickHider;
import net.minecraft.text.PlainTextContent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Nick Hider: player names reach the screen as literal text (chat, tab list, nametags, scoreboard, titles), so the
 * name is swapped where a literal hands its string to a styled visitor. Everything that lays text out (width,
 * wrapping, trimming, drawing) visits it this way, so all of them see the same swapped string and their indices
 * always agree. The unstyled visit behind {@code getString()} is left alone: that is what gets copied into edit
 * boxes and sent back to the server.
 */
@Mixin(PlainTextContent.Literal.class)
public abstract class PlayerLiteralTextMixin {
    @ModifyArg(method = "visit(Lnet/minecraft/text/StringVisitable$StyledVisitor;Lnet/minecraft/text/Style;)Ljava/util/Optional;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/text/StringVisitable$StyledVisitor;accept(Lnet/minecraft/text/Style;Ljava/lang/String;)Ljava/util/Optional;"),
            index = 1)
    private String evade$nick(String text) {
        return NickHider.replace(text);
    }
}
