package net.evadeclient.mixin;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** HUD cache: the fog buffer the game's own GUI pass draws with (the cached HUD is drawn the same way). */
@Mixin(GameRenderer.class)
public interface GameRendererFogAccessor {
    @Accessor("fogRenderer")
    FogRenderer evade$fogRenderer();
}
