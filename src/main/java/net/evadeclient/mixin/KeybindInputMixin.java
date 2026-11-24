package net.evadeclient.mixin;

import net.evadeclient.EvadeClient;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds key presses to Evade's module keybinds as they happen, so a tap shorter than a game tick still toggles.
 * Only presses made in game (no screen open) count, and never while F3 is held (F3 + key are debug shortcuts).
 * Nothing is cancelled: the game sees every key as usual.
 */
@Mixin(Keyboard.class)
public abstract class KeybindInputMixin {
    @Inject(method = "onKey", at = @At("HEAD"))
    private void evade$queueBind(long window, int action, KeyInput input, CallbackInfo ci) {
        if (action != GLFW.GLFW_PRESS || EvadeClient.modules == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (window != mc.getWindow().getHandle() || mc.currentScreen != null || mc.getOverlay() != null) return;
        if (mc.options.debugModifierKey.isPressed()) return;
        EvadeClient.modules.queueBindPress(input.key());
    }
}
