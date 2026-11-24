package net.evadeclient.mixin;

import net.evadeclient.map.MapStore;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * World Map / Minimap: a block the client already has changed (any change the server sends, or your own), so the
 * explored map re-maps that chunk if the change is near its surface. Nothing is read beyond the changed position.
 */
@Mixin(ClientWorld.class)
public abstract class MapBlockChangeMixin {
    @Inject(method = "scheduleBlockRerenderIfNeeded", at = @At("HEAD"))
    private void evade$mapBlockChanged(BlockPos pos, BlockState old, BlockState updated, CallbackInfo ci) {
        MapStore.onBlockChanged((ClientWorld) (Object) this, pos);
    }
}
