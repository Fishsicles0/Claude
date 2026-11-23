package net.evadeclient.mixin;

import net.evadeclient.module.visuals.ShinyPots;
import net.minecraft.client.item.ItemModelManager;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.HeldItemContext;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ShinyPots "Show On": the glint is decided while an item's model is prepared (hasGlint is asked inside
 * clearAndUpdate), so this tells ShinyPots where the item is being drawn (inventory, a hand, the ground...) and whose
 * hand holds it, for the duration of that call. Nested calls restore the outer values.
 */
@Mixin(ItemModelManager.class)
public abstract class ItemModelManagerMixin {
    @Inject(method = "clearAndUpdate", at = @At("HEAD"))
    private void evade$shinyPotsEnter(ItemRenderState renderState, ItemStack stack, ItemDisplayContext displayContext,
                                      @Nullable World world, @Nullable HeldItemContext heldItemContext, int seed, CallbackInfo ci) {
        ShinyPots.enterRender(displayContext, heldItemContext);
    }

    @Inject(method = "clearAndUpdate", at = @At("RETURN"))
    private void evade$shinyPotsExit(ItemRenderState renderState, ItemStack stack, ItemDisplayContext displayContext,
                                     @Nullable World world, @Nullable HeldItemContext heldItemContext, int seed, CallbackInfo ci) {
        ShinyPots.exitRender();
    }
}
