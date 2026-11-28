package net.evadeclient.mixin;

import net.evadeclient.gui.kit.Button;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.PressableWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The background of other vanilla pressable widgets (cycling buttons, icon buttons) on Evade's own screens: the kit
 * Secondary surface instead of grey stone. Their labels stay vanilla's. Vanilla screens and Classic are unchanged.
 */
@Mixin(PressableWidget.class)
public abstract class PressableWidgetKitMixin {
    @Inject(method = "drawButton", at = @At("HEAD"), cancellable = true)
    private void evade$kitBackground(DrawContext context, CallbackInfo ci) {
        if (!Button.evadeScreen()) return;
        PressableWidget self = (PressableWidget) (Object) this;
        Button.vanilla(context, self.getX(), self.getY(), self.getWidth(), self.getHeight(), null, self.active, self.isSelected(),
                ((ClickableWidgetAlphaAccessor) self).evade$alpha());
        ci.cancel();
    }
}
