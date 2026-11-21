package net.evadeclient.mixin;

import net.evadeclient.EvadeClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

    /** Notifier: totem pops (entity status 35), after vanilla has handled the packet on the main thread. */
    @Inject(method = "onEntityStatus", at = @At("TAIL"))
    private void evade$pop(net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket packet, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (packet.getStatus() == 35 && mc.world != null && mc.isOnThread()) net.evadeclient.module.misc.Notifier.onPop(packet.getEntity(mc.world));
    }

    /** Time Changer: re-apply our time straight after the server's time update. */
    @Inject(method = "onWorldTimeUpdate", at = @At("TAIL"))
    private void evade$keepTime(net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket packet, CallbackInfo ci) {
        if (EvadeClient.modules == null || !MinecraftClient.getInstance().isOnThread()) return;
        var tc = EvadeClient.modules.get(net.evadeclient.module.mods.TimeChanger.class);
        if (tc != null && tc.isEnabled()) tc.apply();
    }
}
