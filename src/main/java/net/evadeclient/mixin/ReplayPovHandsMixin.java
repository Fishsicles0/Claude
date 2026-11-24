package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.evadeclient.replay.ReplayOverrides;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.world.GameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Replay player POV: the first-person hand is drawn although the replay camera is a spectator. */
@Mixin(GameRenderer.class)
public abstract class ReplayPovHandsMixin {
    @WrapOperation(method = "renderHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerInteractionManager;getCurrentGameMode()Lnet/minecraft/world/GameMode;"))
    private GameMode evade$povHands(ClientPlayerInteractionManager manager, Operation<GameMode> original) {
        return ReplayOverrides.povPlayer() != null ? GameMode.SURVIVAL : original.call(manager);
    }
}
