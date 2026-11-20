package net.evadeclient.mixin;

import io.netty.channel.ChannelFutureListener;
import net.evadeclient.EvadeClient;
import net.evadeclient.util.PacketHooks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin {
    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"), cancellable = true)
    private void evade$onSend(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (EvadeClient.modules == null || PacketHooks.bypassing()) return;
        var nh = MinecraftClient.getInstance().getNetworkHandler();
        if (nh == null || (Object) this != nh.getConnection()) return; // only the player's game connection
        if (EvadeClient.modules.onSendPacket(packet)) ci.cancel();
    }
}
