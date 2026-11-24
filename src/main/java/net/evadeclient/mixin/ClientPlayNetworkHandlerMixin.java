package net.evadeclient.mixin;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Module;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

    /** Modules whose onServerPacket already failed once (logged once each, never rethrown). */
    @Unique
    private static final Set<String> evade$failed = ConcurrentHashMap.newKeySet();

    /**
     * Hands a server packet vanilla has just applied to every module's {@link Module#onServerPacket}, on the client
     * thread only. A module that throws is logged once (and recorded in ModuleManager.CRASHED for the tests) but
     * stays on: it must never break the network handler.
     */
    @Unique
    private static void evade$dispatch(Packet<?> packet) {
        if (EvadeClient.modules == null || !MinecraftClient.getInstance().isOnThread()) return;
        for (Module m : EvadeClient.modules.all()) {
            try {
                m.onServerPacket(packet);
            } catch (Throwable t) {
                if (evade$failed.add(m.getName())) {
                    EvadeClient.LOG.error("Module {} failed handling a server packet", m.getName(), t);
                    net.evadeclient.module.ModuleManager.CRASHED.add(m.getName() + " (server packet): " + t);
                }
            }
        }
    }

    /**
     * Totem pops (entity status 35), after vanilla has handled the packet on the main thread. Notifier only hears
     * about your own pops; Clips decides for itself which pops are worth a clip.
     */
    @Inject(method = "onEntityStatus", at = @At("TAIL"))
    private void evade$pop(net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket packet, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (packet.getStatus() == 35 && mc.world != null && mc.isOnThread()) {
            var e = packet.getEntity(mc.world);
            if (e != null && e == mc.player) net.evadeclient.module.misc.Notifier.onPop(e);
            net.evadeclient.module.misc.Clips.onPop(e);
        }
    }

    /**
     * Server time: modules see it first (Clock, Server and Time Changer read the real time here), then Time Changer
     * re-applies our time straight after the server's update.
     */
    @Inject(method = "onWorldTimeUpdate", at = @At("TAIL"))
    private void evade$keepTime(net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket packet, CallbackInfo ci) {
        evade$dispatch(packet);
        if (EvadeClient.modules == null || !MinecraftClient.getInstance().isOnThread()) return;
        var tc = EvadeClient.modules.get(net.evadeclient.module.mods.TimeChanger.class);
        if (tc != null && tc.isEnabled()) tc.apply();
    }

    /** Damage dealt to any entity, after vanilla has applied it. */
    @Inject(method = "onEntityDamage", at = @At("TAIL"))
    private void evade$entityDamage(EntityDamageS2CPacket packet, CallbackInfo ci) {
        evade$dispatch(packet);
    }

    /** The local player's health, food and saturation, after vanilla has applied them. */
    @Inject(method = "onHealthUpdate", at = @At("TAIL"))
    private void evade$healthUpdate(HealthUpdateS2CPacket packet, CallbackInfo ci) {
        evade$dispatch(packet);
    }

    /** Rain, thunder, game mode and other game state changes, after vanilla has applied them. */
    @Inject(method = "onGameStateChange", at = @At("TAIL"))
    private void evade$gameStateChange(GameStateChangeS2CPacket packet, CallbackInfo ci) {
        evade$dispatch(packet);
    }
}
