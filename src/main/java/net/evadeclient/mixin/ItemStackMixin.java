package net.evadeclient.mixin;

import net.evadeclient.module.visuals.ShinyPots;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    /** ShinyPots: potions (and the other kinds it's set to) that pass its filters glint where it's set to show. */
    @Inject(method = "hasGlint", at = @At("RETURN"), cancellable = true)
    private void evade$shinyPots(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && ShinyPots.shouldGlint((ItemStack) (Object) this)) cir.setReturnValue(true);
    }
}
