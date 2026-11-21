package net.evadeclient.mixin;

import net.evadeclient.module.visuals.NickHider;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.player.SkinTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayerEntity.class)
public abstract class AbstractClientPlayerEntityMixin {
    /** Nick Hider: show the default skin for yourself only. */
    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void evade$hideSkin(CallbackInfoReturnable<SkinTextures> cir) {
        NickHider n = NickHider.active();
        if (n == null || !n.hideSkin.get()) return;
        var self = (AbstractClientPlayerEntity) (Object) this;
        var mc = MinecraftClient.getInstance();
        if (mc.player != null && self.getUuid().equals(mc.player.getUuid())) {
            cir.setReturnValue(DefaultSkinHelper.getSkinTextures(self.getUuid()));
        }
    }
}
