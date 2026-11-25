package net.evadeclient.mixin;

import net.evadeclient.module.misc.TitleScreenModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Title Screen module: while it is on, opening the plain vanilla title screen opens Evade's main menu instead.
 * Anything else (including other mods' TitleScreen subclasses and demo mode) passes through untouched, and with the
 * module off this changes nothing.
 *
 * <p>Two ways reach the title screen: {@code setScreen(new TitleScreen())} (start-up, leaving a world, the
 * disconnect and death screens, Quick Play, mods) and {@code setScreen(null)} with no world loaded, which vanilla
 * turns into a {@code new TitleScreen()} inside the method (any screen closed with no parent, e.g. a mod's screen or
 * the gametest framework's {@code setScreen(() -> null)}). Both are swapped here, at the head of the method, so the
 * vanilla title screen is never even initialised.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientTitleScreenMixin {
    @Shadow private boolean disconnecting;

    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen evade$evadeTitleScreen(Screen screen) {
        // null with no world means "the title screen" (vanilla makes one further down); not while disconnecting,
        // where vanilla throws on purpose.
        TitleScreenModule.applyPending();
        if (screen == null && ((MinecraftClient) (Object) this).world == null && !disconnecting
                && TitleScreenModule.active() != null) {
            screen = new TitleScreen();
        }
        return TitleScreenModule.replace(screen);
    }
}
