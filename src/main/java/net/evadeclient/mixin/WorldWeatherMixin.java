package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.evadeclient.module.mods.WeatherChanger;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Weather Changer: the client world reports the chosen weather only to the code that draws it (GameRendererMixin
 * marks those spans), so gameplay checks such as Riptide's "is it raining on me" keep the server's real weather.
 */
@Mixin(World.class)
public abstract class WorldWeatherMixin {
    @ModifyReturnValue(method = "getRainGradient", at = @At("RETURN"))
    private float evade$rain(float value) {
        return WeatherChanger.rain((World) (Object) this, value);
    }

    @ModifyReturnValue(method = "getThunderGradient", at = @At("RETURN"))
    private float evade$thunder(float value) {
        return WeatherChanger.thunder((World) (Object) this, value);
    }
}
