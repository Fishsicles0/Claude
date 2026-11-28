package net.evadeclient.mixin;

import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.Tooltip;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.OrderedTextTooltipComponent;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.client.gui.tooltip.TooltipPositioner;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Text tooltips on Evade's own screens are drawn as kit tooltips (smooth font, e5 surface, placed above the cursor
 * so they never cover the control they describe) instead of vanilla's pixel-font box under the cursor. Tooltips
 * with images (items, bundles) and every tooltip on vanilla screens or in the Classic theme stay vanilla.
 */
@Mixin(DrawContext.class)
public abstract class TooltipKitMixin {
    @Inject(method = "drawTooltipImmediately", at = @At("HEAD"), cancellable = true)
    private void evade$kitTooltip(TextRenderer textRenderer, List<TooltipComponent> components, int x, int y,
                                  TooltipPositioner positioner, Identifier texture, CallbackInfo ci) {
        if (components.isEmpty() || !Button.evadeScreen()) return;
        List<String> lines = new ArrayList<>(components.size());
        for (TooltipComponent c : components) {
            if (!(c instanceof OrderedTextTooltipComponent t)) return;
            StringBuilder b = new StringBuilder();
            ((OrderedTextTooltipComponentAccessor) t).evade$text().accept((index, style, codePoint) -> {
                b.appendCodePoint(codePoint);
                return true;
            });
            lines.add(b.toString());
        }
        // Widget and keyboard-focus tooltips know their widget: the kit tooltip goes above it, never over it.
        net.minecraft.client.gui.ScreenRect r = positioner instanceof net.minecraft.client.gui.tooltip.WidgetTooltipPositioner w
                ? ((WidgetTooltipPositionerAccessor) w).evade$focus()
                : positioner instanceof net.minecraft.client.gui.tooltip.FocusedTooltipPositioner f
                ? ((FocusedTooltipPositionerAccessor) f).evade$focus() : null;
        if (r != null) Tooltip.drawFor((DrawContext) (Object) this, lines, r.getLeft(), r.getTop(), r.getRight(), r.getBottom());
        else Tooltip.drawAtMouse((DrawContext) (Object) this, lines, x, y);
        ci.cancel();
    }
}
