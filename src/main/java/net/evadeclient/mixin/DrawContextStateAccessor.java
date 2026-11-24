package net.evadeclient.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.render.state.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** HUD cache: the frame's GUI render state a draw context adds to (replayed widgets are added straight to it). */
@Mixin(DrawContext.class)
public interface DrawContextStateAccessor {
    @Accessor("state")
    GuiRenderState evade$state();
}
