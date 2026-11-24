package net.evadeclient.mixin;

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reads how many ticks a hand swing lasts (haste, mining fatigue and the held item change it). Read only. */
@Mixin(LivingEntity.class)
public interface LivingEntitySwingInvoker {
    @Invoker("getHandSwingDuration")
    int evade$swingDuration();
}
