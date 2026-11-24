package net.evadeclient.mixin;

import net.evadeclient.module.visuals.PackTweaks;
import net.minecraft.client.render.entity.FishingBobberEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Pack Tweaks Fishing Line Color: the colour each fishing line segment is drawn in (vanilla: black). */
@Mixin(FishingBobberEntityRenderer.class)
public abstract class FishingLineMixin {
    @ModifyArg(method = "renderFishingLine", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/VertexConsumer;color(I)Lnet/minecraft/client/render/VertexConsumer;"))
    private static int evade$lineColor(int color) {
        return PackTweaks.fishingLineColor(color);
    }
}
