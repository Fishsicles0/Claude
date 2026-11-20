package net.evadeclient.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {
    /** Vanilla left-click: swings, attacks the crosshair entity or starts mining. */
    @Invoker("doAttack")
    boolean evade$doAttack();

    /** Vanilla right-click. */
    @Invoker("doItemUse")
    void evade$doItemUse();
}
