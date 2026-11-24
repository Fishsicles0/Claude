package net.evadeclient.mixin;

import net.evadeclient.module.misc.TitleScreenModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Title Screen module: while it is on, opening the plain vanilla title screen opens Evade's main menu instead.
 * Anything else (including other mods' TitleScreen subclasses and demo mode) passes through untouched, and with the
 * module off this changes nothing.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientTitleScreenMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen evade$evadeTitleScreen(Screen screen) {
        return TitleScreenModule.replace(screen);
    }
}
