package net.evadeclient.mixin;

import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The world's own rain and thunder strength, exactly as the server last set them. Weather Changer reads these to
 * fade from and back to the real weather: the public getters run through WorldWeatherMixin, which may be answering
 * with Weather Changer's own values at that moment.
 */
@Mixin(World.class)
public interface WorldWeatherAccessor {
    @Accessor("rainGradient")
    float evade$rainGradient();

    /** The raw thunder strength; vanilla's getter multiplies it by the rain strength. */
    @Accessor("thunderGradient")
    float evade$thunderGradient();
}
