package net.evadeclient.mixin;

import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.render.BufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets {@link net.evadeclient.render.ui.UiShape} write its own vertex attributes (the shape parameters of the
 * {@code evade:ui_shape} vertex format): {@code beginElement} returns the element's address in the current vertex,
 * or -1 when the format has no such element or it was already written.
 */
@Mixin(BufferBuilder.class)
public interface BufferBuilderAccessor {
    @Invoker("beginElement")
    long evade$beginElement(VertexFormatElement element);
}
