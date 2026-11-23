package net.evadeclient.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Zoom's Spyglass overlay: vanilla's own spyglass scope drawing (texture, sizing and black borders). */
@Mixin(InGameHud.class)
public interface InGameHudInvoker {
    @Invoker("renderSpyglassOverlay")
    void evade$renderSpyglassOverlay(DrawContext context, float scale);
}
