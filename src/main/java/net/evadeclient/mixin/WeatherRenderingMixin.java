package net.evadeclient.mixin;

import net.evadeclient.module.mods.Performance;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WeatherRendering;
import net.minecraft.client.render.state.WeatherRenderState;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WeatherRendering.class)
public abstract class WeatherRenderingMixin {
    /** Performance "Hide Rain & Snow": skip drawing precipitation (rain sounds and the sky darkening stay). */
    @Inject(method = "renderPrecipitation", at = @At("HEAD"), cancellable = true)
    private void evade$hideWeather(VertexConsumerProvider consumers, Vec3d pos, WeatherRenderState state, CallbackInfo ci) {
        Performance p = Performance.active();
        if (p != null && p.hideWeather()) ci.cancel();
    }
}
