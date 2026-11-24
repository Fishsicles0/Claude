package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.evadeclient.module.misc.BetterChat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * BetterChat's Keep Chat On Server Switch: marks the HUD clear that happens when a network (proxy) moves you to
 * another of its servers, or the server transfers you, so the chat mixin can keep chat through it. The flag only
 * lives for the duration of that one call.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientReconfigMixin {
    @WrapOperation(method = "enterReconfiguration",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/InGameHud;clear()V"))
    private void evade$keepChatOnSwitch(InGameHud hud, Operation<Void> original) {
        evade$clearSwitching(hud, original);
    }

    @WrapOperation(method = "disconnect(Lnet/minecraft/client/gui/screen/Screen;ZZ)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/InGameHud;clear()V"))
    private void evade$keepChatOnTransfer(InGameHud hud, Operation<Void> original, @Local(argsOnly = true, ordinal = 0) boolean transferring) {
        if (transferring) evade$clearSwitching(hud, original);
        else original.call(hud);
    }

    @Unique
    private static void evade$clearSwitching(InGameHud hud, Operation<Void> original) {
        BetterChat.setSwitchingServer(true);
        try {
            original.call(hud);
        } finally {
            BetterChat.setSwitchingServer(false);
        }
    }
}
