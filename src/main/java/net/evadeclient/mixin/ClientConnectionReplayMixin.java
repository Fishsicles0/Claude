package net.evadeclient.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.evadeclient.replay.ReplayRecorder;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands every packet the client receives to the replay recorder, before the game handles it. */
@Mixin(ClientConnection.class)
public abstract class ClientConnectionReplayMixin {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"))
    private void evade$recordPacket(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        ReplayRecorder.onPacket((ClientConnection) (Object) this, packet);
    }
}
