package net.evadeclient.mixin;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Intentionally empty. Nick Hider used to swap your skin here, in {@code getSkin}, but that method only reads the
 * skin from your player list entry, which {@link PlayerListEntryMixin} already swaps (for the tab list too, which
 * this hook never reached), so the hook only did the same work a second time every frame.
 * Kept only so the mixin config entry stays valid; remove both together.
 */
@Mixin(AbstractClientPlayerEntity.class)
public abstract class AbstractClientPlayerEntityMixin {
}
