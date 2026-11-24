package net.evadeclient.mixin;

import net.evadeclient.replay.ReplayRender;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replay renders: a fixed time step per frame, and each finished frame handed to the encoder. */
@Mixin(MinecraftClient.class)
public abstract class ReplayClientMixin {
    @ModifyArg(method = "render(Z)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/RenderTickCounter$Dynamic;beginRenderTick(JZ)I"), index = 0)
    private long evade$renderClock(long time) {
        return ReplayRender.clock(time);
    }

    @Inject(method = "render(Z)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/util/Window;swapBuffers(Lnet/minecraft/client/util/tracy/TracyFrameCapturer;)V"))
    private void evade$frameDrawn(boolean tick, CallbackInfo ci) {
        ReplayRender.onFrameDrawn();
    }
}
