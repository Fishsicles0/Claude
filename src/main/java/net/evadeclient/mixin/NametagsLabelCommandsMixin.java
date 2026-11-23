package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.evadeclient.module.visuals.Nametags;
import net.minecraft.client.render.command.LabelCommandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Nametags "Custom Background": the label queue reads the background opacity from the game options for every
 * label; while a player's tag is being queued (flagged by PlayerEntityRendererMixin) it reads the module's value.
 * Which labels get a background, and which show through walls, is unchanged.
 */
@Mixin(LabelCommandRenderer.Commands.class)
public abstract class NametagsLabelCommandsMixin {
    @ModifyExpressionValue(method = "add", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/option/GameOptions;getTextBackgroundOpacity(F)F"))
    private float evade$nametagBackground(float vanilla) {
        return Nametags.backgroundOpacity(vanilla);
    }
}
