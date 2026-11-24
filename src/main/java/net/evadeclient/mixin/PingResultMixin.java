package net.evadeclient.mixin;

import net.evadeclient.util.NetStats;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.query.PingResultS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ping Graph: a vanilla ping query came back; its round trip goes into NetStats (network thread). */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class PingResultMixin {
    @Inject(method = "onPingResult", at = @At("TAIL"))
    private void evade$pingResult(PingResultS2CPacket packet, CallbackInfo ci) {
        NetStats.onPingResult(packet.startTime());
    }
}
