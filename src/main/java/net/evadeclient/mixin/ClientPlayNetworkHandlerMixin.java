package net.evadeclient.mixin;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.combat.Velocity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Unique private Vec3d evade$before;

    @Inject(method = "onEntityVelocityUpdate", at = @At("HEAD"))
    private void evade$velHead(EntityVelocityUpdateS2CPacket packet, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        evade$before = mc.isOnThread() && mc.player != null && packet.getEntityId() == mc.player.getId() ? mc.player.getVelocity() : null;
    }

    @Inject(method = "onEntityVelocityUpdate", at = @At("TAIL"))
    private void evade$velTail(EntityVelocityUpdateS2CPacket packet, CallbackInfo ci) {
        if (evade$before != null) evade$apply();
    }

    @Inject(method = "onExplosion", at = @At("HEAD"))
    private void evade$expHead(ExplosionS2CPacket packet, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        evade$before = mc.isOnThread() && mc.player != null ? mc.player.getVelocity() : null;
    }

    @Inject(method = "onExplosion", at = @At("TAIL"))
    private void evade$expTail(ExplosionS2CPacket packet, CallbackInfo ci) {
        if (evade$before != null) evade$apply();
    }

    @Unique
    private void evade$apply() {
        Velocity v = EvadeClient.modules == null ? null : EvadeClient.modules.get(Velocity.class);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (v != null && v.isEnabled()) mc.player.setVelocity(v.filter(evade$before, mc.player.getVelocity()));
        evade$before = null;
    }

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
