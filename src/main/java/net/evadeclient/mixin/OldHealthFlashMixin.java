package net.evadeclient.mixin;

import net.evadeclient.module.visuals.OldAnimations;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 1.7 Animations "1.7 Health Flash": the hearts' white flash follows 1.7's rule, flashing every 3 ticks during the
 * first half of the hurt cooldown and never for healing. It reads only your own hurt cooldown.
 */
@Mixin(InGameHud.class)
public abstract class OldHealthFlashMixin {
    @ModifyArg(method = "renderStatusBars", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/InGameHud;renderHealthBar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/entity/player/PlayerEntity;IIIIFIIIZ)V"),
            index = 10)
    private boolean evade$oldHealthFlash(boolean blinking) {
        if (OldAnimations.with(o -> o.oldHealthFlash) == null) return blinking;
        var p = MinecraftClient.getInstance().player;
        if (p == null) return blinking;
        int t = p.timeUntilRegen;
        return t >= 10 && t / 3 % 2 == 1;
    }
}
