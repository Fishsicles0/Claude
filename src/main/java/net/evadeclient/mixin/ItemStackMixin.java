package net.evadeclient.mixin;

import net.evadeclient.module.visuals.ShinyPots;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    /** ShinyPots: anything carrying potion contents (potions, splash, lingering, tipped arrows) glints. */
    @Inject(method = "hasGlint", at = @At("RETURN"), cancellable = true)
    private void evade$shinyPots(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() || !ShinyPots.on()) return;
        var self = (ItemStack) (Object) this;
        var pc = self.get(DataComponentTypes.POTION_CONTENTS);
        if (pc != null && pc.hasEffects()) cir.setReturnValue(true);
    }
}
