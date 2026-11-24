package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.evadeclient.module.visuals.NoRender;
import net.minecraft.client.render.GameRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * NoRender Screen Wobble: the nausea and portal strengths {@code GameRenderer.renderWorld} reads for its screen warp
 * (the lerped portal intensity and the Nausea effect's fade) read as 0, so the projection's rotate/scale block is
 * skipped, whatever Distortion Effects is set to. Only the world projection: InGameHud's own reads (the purple portal
 * and green nausea overlays) are untouched, so NoRender's "Nausea & Portal" overlay switch stays independent.
 *
 * <p>Value modifiers rather than redirects, so they chain with other mods that read the same values.
 */
@Mixin(GameRenderer.class)
public abstract class NoRenderGameRendererMixin {
    @ModifyExpressionValue(method = "renderWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;getEffectFadeFactor(Lnet/minecraft/registry/entry/RegistryEntry;F)F"))
    private float evade$noNauseaWobble(float fade) {
        return NoRender.hide(n -> n.wobble) ? 0f : fade;
    }

    @ModifyExpressionValue(method = "renderWorld", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;nauseaIntensity:F"))
    private float evade$noPortalWobble(float intensity) {
        return NoRender.hide(n -> n.wobble) ? 0f : intensity;
    }

    @ModifyExpressionValue(method = "renderWorld", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;lastNauseaIntensity:F"))
    private float evade$noPortalWobbleLast(float intensity) {
        return NoRender.hide(n -> n.wobble) ? 0f : intensity;
    }
}
