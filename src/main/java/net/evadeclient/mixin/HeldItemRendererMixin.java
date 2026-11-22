package net.evadeclient.mixin;

import net.evadeclient.module.visuals.OldAnimations;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {
    @Shadow protected abstract void applySwingOffset(MatrixStack matrices, Arm arm, float swingProgress);

    private static final String RENDER_FIRST_PERSON =
            "renderFirstPersonItem(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFLnet/minecraft/util/Hand;FLnet/minecraft/item/ItemStack;FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V";

    /** 1.7 item position: hold items a little lower, like older versions. */
    @Inject(method = RENDER_FIRST_PERSON, at = @At("HEAD"))
    private void evade$oldPosition(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand, float swingProgress,
                                   ItemStack item, float equipProgress, MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                                   CallbackInfo ci) {
        OldAnimations o = OldAnimations.active();
        if (o != null && o.oldPosition.get() && !item.isEmpty()) matrices.translate(0, -o.lower.get(), 0);
    }

    /** 1.7 swing while eating/drinking: apply the swing on top of the eat/drink pose. */
    @Redirect(method = RENDER_FIRST_PERSON, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/item/HeldItemRenderer;applyEatOrDrinkTransformation(Lnet/minecraft/client/util/math/MatrixStack;FLnet/minecraft/util/Arm;Lnet/minecraft/item/ItemStack;Lnet/minecraft/entity/player/PlayerEntity;)V"))
    private void evade$swingWhileEating(HeldItemRenderer self, MatrixStack matrices, float tickDelta, Arm arm, ItemStack stack, PlayerEntity p,
                                        AbstractClientPlayerEntity player, float td, float pitch, Hand hand, float swingProgress,
                                        ItemStack item, float equipProgress, MatrixStack m2, OrderedRenderCommandQueue queue, int light) {
        ((HeldItemRendererInvoker) self).evade$applyEatOrDrink(matrices, tickDelta, arm, stack, p);
        OldAnimations o = OldAnimations.active();
        if (o != null && o.swingWhileUsing.get() && swingProgress > 0) applySwingOffset(matrices, arm, swingProgress);
    }
}
