package net.evadeclient.mixin;

import net.evadeclient.module.visuals.NickHider;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.TranslatableTextContent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Nick Hider: when a translated message arrives from the server, plain unstyled text arguments are decoded as
 * strings rather than literal texts ({"translate": "...", "with": ["Notch"]}, or a whisper's message body), so
 * they never pass through the literal hook. Such arguments get a stand-in that shows the nickname when drawn and
 * the real text everywhere else. Translations are cached per language, so the stand-in decides on every visit.
 */
@Mixin(TranslatableTextContent.class)
public abstract class NickHiderTranslatableMixin {
    @Shadow @Final private Object[] args;

    @Inject(method = "getArg", at = @At("RETURN"), cancellable = true)
    private void evade$nickArgument(int index, CallbackInfoReturnable<StringVisitable> cir) {
        if (index >= 0 && index < args.length && args[index] instanceof String s) {
            cir.setReturnValue(NickHider.translationArgument(s));
        }
    }
}
