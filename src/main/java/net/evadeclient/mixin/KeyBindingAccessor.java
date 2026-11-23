package net.evadeclient.mixin;

import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Presses one key binding without touching the others on the same key (Voice Chat opens Simple Voice Chat's menu
 * this way). {@code KeyBinding.onKeyPressed(key)} would press every binding on that key.
 */
@Mixin(KeyBinding.class)
public interface KeyBindingAccessor {
    @Accessor("timesPressed")
    int evade$getTimesPressed();

    @Accessor("timesPressed")
    void evade$setTimesPressed(int timesPressed);
}
