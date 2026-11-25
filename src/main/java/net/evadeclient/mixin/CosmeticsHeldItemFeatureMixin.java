package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.evadeclient.cosmetics.CosmeticsEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.state.ArmedEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Cosmetics: your item skin on your own player model in third person and in the inventory (your screen only). */
@Mixin(HeldItemFeatureRenderer.class)
public abstract class CosmeticsHeldItemFeatureMixin {
    @WrapOperation(method = "renderItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/item/ItemRenderState;render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;III)V"))
    private void evade$itemSkin(ItemRenderState itemState, MatrixStack matrices, OrderedRenderCommandQueue queue, int light, int overlay,
                                int outline, Operation<Void> original, @Local(argsOnly = true) ArmedEntityRenderState state,
                                @Local(argsOnly = true) ItemStack stack, @Local(argsOnly = true) Arm arm) {
        var mc = MinecraftClient.getInstance();
        if (state instanceof PlayerEntityRenderState ps && mc.player != null && ps.id == mc.player.getId()) {
            ItemDisplayContext ctx = arm == Arm.LEFT ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
            if (CosmeticsEngine.drawItemSkin(mc.player, stack, ctx, matrices, queue, light)) return;
        }
        original.call(itemState, matrices, queue, light, overlay, outline);
    }
}
