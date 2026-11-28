package net.evadeclient.mixin;

import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.tooltip.FocusedTooltipPositioner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The focused widget a keyboard-focus tooltip belongs to (the kit tooltip is placed above it, never over it). */
@Mixin(FocusedTooltipPositioner.class)
public interface FocusedTooltipPositionerAccessor {
    @Accessor("focus")
    ScreenRect evade$focus();
}
