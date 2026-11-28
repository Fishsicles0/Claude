package net.evadeclient.mixin;

import net.minecraft.client.gui.widget.ClickableWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A widget's fade-in alpha (the kit paints vanilla buttons on Evade screens with it). */
@Mixin(ClickableWidget.class)
public interface ClickableWidgetAlphaAccessor {
    @Accessor("alpha")
    float evade$alpha();
}
