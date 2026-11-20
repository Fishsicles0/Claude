package net.evadeclient.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientPlayerInteractionManager.class)
public interface InteractionManagerAccessor {
    @Accessor("blockBreakingCooldown")
    void evade$setBlockBreakingCooldown(int ticks);

    @Accessor("currentBreakingProgress")
    float evade$getBreakingProgress();

    @Accessor("currentBreakingProgress")
    void evade$setBreakingProgress(float progress);
}
