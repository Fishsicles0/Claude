package net.evadeclient.mixin;

import net.evadeclient.module.misc.AutoReconnect;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.CookieStorage;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AutoReconnect: remembers the server every connection attempt is for, the moment it starts, so a connection that
 * fails before you spawn is retried against the server you were trying to join, never the one before it.
 */
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
    @Inject(method = "connect(Lnet/minecraft/client/gui/screen/Screen;Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;Lnet/minecraft/client/network/ServerInfo;ZLnet/minecraft/client/network/CookieStorage;)V",
            at = @At("HEAD"))
    private static void evade$rememberTarget(Screen screen, MinecraftClient client, ServerAddress address, ServerInfo info,
                                             boolean quickPlay, CookieStorage cookieStorage, CallbackInfo ci) {
        // Vanilla ignores a second connect while one is in progress, so that one isn't the target either.
        if (client.currentScreen instanceof ConnectScreen) return;
        AutoReconnect.setTarget(info);
    }
}
