package net.evadeclient.mixin;

import net.evadeclient.cosmetics.CosmeticsEngine;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cosmetics: your item skin in place of your own sword in first person (your screen only). */
@Mixin(HeldItemRenderer.class)
public abstract class CosmeticsHeldItemMixin {
    @Inject(method = "renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemDisplayContext;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V",
            at = @At("HEAD"), cancellable = true)
    private void evade$itemSkin(LivingEntity entity, ItemStack stack, ItemDisplayContext ctx, MatrixStack matrices,
                                OrderedRenderCommandQueue queue, int light, CallbackInfo ci) {
        if (CosmeticsEngine.drawItemSkin(entity, stack, ctx, matrices, queue, light)) ci.cancel();
    }
}
