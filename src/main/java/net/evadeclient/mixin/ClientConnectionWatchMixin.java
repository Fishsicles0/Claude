package net.evadeclient.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.evadeclient.util.NetStats;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Connection Watch: notes when a packet last arrived on a connection (network thread; one volatile write). */
@Mixin(ClientConnection.class)
public abstract class ClientConnectionWatchMixin {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"))
    private void evade$packetArrived(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        NetStats.onReceive((ClientConnection) (Object) this);
    }
}
