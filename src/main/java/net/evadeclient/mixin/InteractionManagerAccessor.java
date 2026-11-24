package net.evadeclient.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only view of the block you are mining, for Block Outline's Break Progress colour and fill animation. Getters
 * only: nothing here changes how or how fast a block breaks.
 */
@Mixin(ClientPlayerInteractionManager.class)
public interface InteractionManagerAccessor {
    /** The block being mined (only meaningful while {@code isBreakingBlock()} is true). */
    @Accessor("currentBreakingPos")
    BlockPos evade$getBreakingPos();

    /** How far the current block is mined, 0 to 1 (vanilla only exposes it in tenths). */
    @Accessor("currentBreakingProgress")
    float evade$getBreakingProgress();
}
