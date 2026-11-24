package net.evadeclient.mixin;

import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Render Scale: the entity-outline framebuffer follows the scaled world pass's size. */
@Mixin(WorldRenderer.class)
public interface RenderScaleWorldRendererAccessor {
    @Accessor("entityOutlineFramebuffer") Framebuffer evade$entityOutlineFramebuffer();
}
