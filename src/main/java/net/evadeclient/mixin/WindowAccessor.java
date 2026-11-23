package net.evadeclient.mixin;

import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets replay renders draw at any resolution (the game renders into a framebuffer of this size). */
@Mixin(Window.class)
public interface WindowAccessor {
    @Accessor("framebufferWidth") void evade$setFramebufferWidth(int width);
    @Accessor("framebufferHeight") void evade$setFramebufferHeight(int height);
}
