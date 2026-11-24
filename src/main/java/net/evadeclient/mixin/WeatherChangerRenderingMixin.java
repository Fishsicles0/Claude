package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.evadeclient.module.mods.WeatherChanger;
import net.evadeclient.util.Guard;
import net.minecraft.client.render.WeatherRendering;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Weather Changer's precipitation type and rain volume. WeatherRendering decides per column whether rain or snow
 * falls (for drawing it, for splash particles and for rain sounds) with its private {@code getPrecipitationAt}; nothing
 * that plays by the server's rules reads it. (WeatherRenderingMixin is Performance's hook on the same class.)
 */
@Mixin(WeatherRendering.class)
public abstract class WeatherChangerRenderingMixin {
    /** Snow modes and Precipitation: rain or snow everywhere it would fall. Dry biomes stay dry. */
    @Inject(method = "getPrecipitationAt", at = @At("RETURN"), cancellable = true)
    private void evade$precipitation(World world, BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> cir) {
        Guard.run(WeatherChanger.class, "precipitation", () -> {
            Biome.Precipitation original = cir.getReturnValue();
            Biome.Precipitation shown = WeatherChanger.precipitation(world, original);
            if (shown != original) cir.setReturnValue(shown);
        });
    }

    /** Rain Volume: both rain sounds (on you, and on a roof above you) are scaled, and not played at all at 0%. */
    @WrapOperation(method = "addParticlesAndSound", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/world/ClientWorld;playSoundAtBlockCenterClient(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/sound/SoundEvent;Lnet/minecraft/sound/SoundCategory;FFZ)V"))
    private void evade$rainVolume(ClientWorld world, BlockPos pos, SoundEvent sound, SoundCategory category, float volume,
                                  float pitch, boolean useDistance, Operation<Void> original) {
        float v = WeatherChanger.rainVolume(volume);
        if (v > 0f) original.call(world, pos, sound, category, v, pitch, useDistance);
    }
}
