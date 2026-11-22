package net.evadeclient.mixin;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.OverlayTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("overlayTexture")
    OverlayTexture evade$overlay();
}
