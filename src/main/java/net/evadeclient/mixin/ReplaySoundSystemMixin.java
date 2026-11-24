package net.evadeclient.mixin;

import net.evadeclient.replay.ReplayAudio;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Keeps the game from switching back to your speakers in the middle of a render. */
@Mixin(SoundSystem.class)
public abstract class ReplaySoundSystemMixin {
    @Inject(method = "shouldReloadSounds", at = @At("HEAD"), cancellable = true)
    private void evade$keepLoopback(CallbackInfoReturnable<Boolean> cir) {
        if (ReplayAudio.device() != 0L) cir.setReturnValue(false);
    }

    /**
     * A sound only starts once its file has loaded (in the background). While rendering, keep hold of that so the
     * render can wait for it: the sound then starts on the frame it was played on, not a few frames late.
     */
    @Redirect(method = "play(Lnet/minecraft/client/sound/SoundInstance;)Lnet/minecraft/client/sound/SoundSystem$PlayResult;",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;thenAccept(Ljava/util/function/Consumer;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> evade$trackLoad(CompletableFuture<Object> load, Consumer<Object> start) {
        CompletableFuture<Void> f = load.thenAccept(start);
        if (ReplayAudio.device() != 0L) ReplayAudio.pending(f);
        return f;
    }
}
