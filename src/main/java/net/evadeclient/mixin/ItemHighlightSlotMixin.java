package net.evadeclient.mixin;

import net.evadeclient.module.visuals.ItemHighlight;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Item Highlight: a tint behind slots holding one of your chosen items, in any open inventory or container. */
@Mixin(HandledScreen.class)
public abstract class ItemHighlightSlotMixin {
    @Inject(method = "drawSlot(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/screen/slot/Slot;II)V", at = @At("HEAD"))
    private void evade$highlight(DrawContext ctx, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        ItemHighlight.drawSlot(ctx, slot.getStack(), slot.x, slot.y);
    }
}
