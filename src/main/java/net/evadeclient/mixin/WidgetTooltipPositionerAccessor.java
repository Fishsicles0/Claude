package net.evadeclient.mixin;

import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.tooltip.WidgetTooltipPositioner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The widget a widget tooltip belongs to (the kit tooltip is placed above it, never over it). */
@Mixin(WidgetTooltipPositioner.class)
public interface WidgetTooltipPositionerAccessor {
    @Accessor("focus")
    ScreenRect evade$focus();
}
