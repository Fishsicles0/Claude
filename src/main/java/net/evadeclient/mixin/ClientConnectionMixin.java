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
    /** EVADE_DEBUG_PACKETS=1 logs every packet sent (both sides of a singleplayer world), for debugging tests. */
    @org.spongepowered.asm.mixin.Unique
    private static final boolean DEBUG_PACKETS = System.getenv("EVADE_DEBUG_PACKETS") != null;

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"), cancellable = true)
    private void evade$onSend(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (DEBUG_PACKETS) EvadeClient.LOG.info("[packets] {} send {} on {}", (Object) this.hashCode(), packet.getClass().getSimpleName(), Thread.currentThread().getName());
        if (EvadeClient.modules == null || PacketHooks.bypassing()) return;
        var nh = MinecraftClient.getInstance().getNetworkHandler();
        if (nh == null || (Object) this != nh.getConnection()) return; // only the player's game connection
        if (EvadeClient.modules.onSendPacket(packet)) ci.cancel();
    }
}
