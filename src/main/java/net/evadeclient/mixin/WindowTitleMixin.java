package net.evadeclient.mixin;

import net.evadeclient.EvadeClient;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The game window is titled "Evade Client", keeping the game's own detail (version, singleplayer/multiplayer). */
@Mixin(MinecraftClient.class)
public abstract class WindowTitleMixin {
    @Inject(method = "getWindowTitle", at = @At("RETURN"), cancellable = true)
    private void evade$title(CallbackInfoReturnable<String> cir) {
        String vanilla = cir.getReturnValue();
        if (vanilla == null || vanilla.startsWith("Evade")) return;
        cir.setReturnValue("Evade Client " + EvadeClient.VERSION + "  ·  " + vanilla.replace("Minecraft*", "Minecraft"));
    }
}
