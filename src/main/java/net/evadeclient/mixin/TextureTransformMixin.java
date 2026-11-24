package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.evadeclient.module.visuals.EnchantGlint;
import net.evadeclient.util.Guard;
import net.minecraft.client.render.TextureTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * EnchantGlint "Speed" and "Scale": the glint's scrolling texture matrix (items, entities and armour all build theirs
 * here). Both are 1 while the module is off, which leaves vanilla's matrix exactly as it was.
 */
@Mixin(TextureTransform.class)
public abstract class TextureTransformMixin {
    /** Bigger Scale = a bigger pattern, so the texture coordinates are scaled down by it. */
    @ModifyVariable(method = "getGlintTransformation", at = @At("HEAD"), argsOnly = true)
    private static float evade$glintScale(float scale) {
        return Guard.getFloat(EnchantGlint.class, "glint scale", () -> scale / EnchantGlint.patternScale(), scale);
    }

    /** The game's own Glint Speed option, times Speed (0 freezes the pattern). */
    @ModifyExpressionValue(method = "getGlintTransformation",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/option/SimpleOption;getValue()Ljava/lang/Object;"))
    private static Object evade$glintSpeed(Object value) {
        return Guard.get(EnchantGlint.class, "glint speed", () -> {
            float speed = EnchantGlint.speed();
            return speed == 1f || !(value instanceof Double d) ? value : (Object) (d * speed);
        }, value);
    }
}
