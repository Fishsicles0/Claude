package net.evadeclient.mixin;

import net.evadeclient.gui.kit.Button;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Plain vanilla buttons ({@code ButtonWidget.builder}) on Evade's own screens are painted as kit Secondary buttons
 * with the smooth font, instead of grey stone with pixel text. Vanilla screens and the Classic theme keep vanilla's
 * look. Only the painting changes: size, focus, narration and clicks are vanilla's.
 */
@Mixin(ButtonWidget.Text.class)
public abstract class ButtonWidgetKitMixin extends ButtonWidget {
    protected ButtonWidgetKitMixin(int x, int y, int w, int h, net.minecraft.text.Text message, PressAction onPress, NarrationSupplier narration) {
        super(x, y, w, h, message, onPress, narration);
    }

    @Inject(method = "drawIcon", at = @At("HEAD"), cancellable = true)
    private void evade$kitButton(DrawContext context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (!Button.evadeScreen()) return;
        Button.vanilla(context, getX(), getY(), getWidth(), getHeight(), getMessage().getString(), active, isSelected(), alpha);
        ci.cancel();
    }
}
