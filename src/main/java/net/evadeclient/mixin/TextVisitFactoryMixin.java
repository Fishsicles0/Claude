package net.evadeclient.mixin;

import net.minecraft.text.TextVisitFactory;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Intentionally empty. Nick Hider used to swap your name here, inside {@code visitFormatted}, but the indices that
 * method reports then belonged to the swapped string while callers (line wrapping, trimming) cut the original one:
 * the real name could show split across chat lines and a long nickname threw StringIndexOutOfBoundsException.
 * The swap now happens where text comes from (see {@link PlayerLiteralTextMixin} and the NickHider*Mixin classes).
 * Kept only so the mixin config entry stays valid; remove both together.
 */
@Mixin(TextVisitFactory.class)
public abstract class TextVisitFactoryMixin {
}
