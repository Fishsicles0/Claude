package net.evadeclient.mixin;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.render.ESP;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "hasOutline", at = @At("HEAD"), cancellable = true)
    private void evade$espOutline(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (EvadeClient.modules == null) return;
        ESP esp = EvadeClient.modules.get(ESP.class);
        if (esp != null && esp.isEnabled() && esp.shouldOutline(entity)) {
            cir.setReturnValue(true);
        }
    }
}
