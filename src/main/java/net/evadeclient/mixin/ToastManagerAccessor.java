package net.evadeclient.mixin;

import net.minecraft.client.toast.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.BitSet;

/** Which of the five 32 px toast slots in the top-right corner are taken, so Evade's corner HUD can make room. */
@Mixin(ToastManager.class)
public interface ToastManagerAccessor {
    @Accessor("occupiedSpaces")
    BitSet evade$occupiedSpaces();
}
