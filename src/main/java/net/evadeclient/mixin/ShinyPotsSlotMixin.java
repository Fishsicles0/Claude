package net.evadeclient.mixin;

import net.evadeclient.module.visuals.ShinyPots;
import net.evadeclient.util.Guard;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ShinyPots "Slot Tint": the potion colour behind a potion's slot in any inventory, drawn before the item. */
@Mixin(HandledScreen.class)
public abstract class ShinyPotsSlotMixin {
    @Inject(method = "drawSlot(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/screen/slot/Slot;II)V", at = @At("HEAD"))
    private void evade$shinyPotsTint(DrawContext ctx, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        Guard.run(ShinyPots.class, "slot", () -> {
            ShinyPots.drawSlotTint(ctx, slot.getStack(), slot.x, slot.y);
        });
    }
}
