package net.evadeclient.mixin;

import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(HeldItemRenderer.class)
public interface HeldItemRendererInvoker {
    @Invoker("applyEatOrDrinkTransformation")
    void evade$applyEatOrDrink(MatrixStack matrices, float tickDelta, Arm arm, ItemStack stack, PlayerEntity player);
}
