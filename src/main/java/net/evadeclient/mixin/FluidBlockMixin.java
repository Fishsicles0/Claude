package net.evadeclient.mixin;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.movement.Jesus;
import net.minecraft.block.BlockState;
import net.minecraft.block.EntityShapeContext;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FluidBlock.class)
public abstract class FluidBlockMixin {
    /** Jesus: water/lava is solid underfoot for the local player (unless sneaking to dive). */
    @Inject(method = "getCollisionShape", at = @At("HEAD"), cancellable = true)
    private void evade$jesus(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx, CallbackInfoReturnable<VoxelShape> cir) {
        if (EvadeClient.modules == null || !(ctx instanceof EntityShapeContext esc)) return;
        var player = MinecraftClient.getInstance().player;
        if (player == null || esc.getEntity() != player) return;
        Jesus j = EvadeClient.modules.get(Jesus.class);
        if (j == null || !j.isEnabled() || player.isSneaking() || player.isTouchingWater() || player.isInLava()) return;
        if (player.getY() >= pos.getY() + 1 - 0.001 && world.getFluidState(pos.up()).isEmpty()) cir.setReturnValue(VoxelShapes.fullCube());
    }
}
