package net.evadeclient.mixin;

import net.minecraft.client.option.SimpleOption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets Fullbright push the gamma option past its normal 0..1 range. */
@Mixin(SimpleOption.class)
public interface SimpleOptionAccessor {
    @Accessor("value")
    void evade$setValue(Object value);
}
