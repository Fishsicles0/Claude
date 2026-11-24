package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.evadeclient.util.RenderScale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Render Scale: while the world pass runs, the game's main framebuffer is the smaller scaled one. Outside it (and
 * always at 100%) this is one static boolean read and the real framebuffer is returned.
 */
@Mixin(MinecraftClient.class)
public abstract class RenderScaleClientMixin {
    @ModifyReturnValue(method = "getFramebuffer", at = @At("RETURN"))
    private Framebuffer evade$renderScale(Framebuffer main) {
        return RenderScale.redirect(main);
    }
}
