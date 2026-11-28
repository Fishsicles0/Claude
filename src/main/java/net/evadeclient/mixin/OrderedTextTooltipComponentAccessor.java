package net.evadeclient.mixin;

import net.minecraft.client.gui.tooltip.OrderedTextTooltipComponent;
import net.minecraft.text.OrderedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The text of a plain text tooltip line (the kit tooltip draws it in the smooth font). */
@Mixin(OrderedTextTooltipComponent.class)
public interface OrderedTextTooltipComponentAccessor {
    @Accessor("text")
    OrderedText evade$text();
}
