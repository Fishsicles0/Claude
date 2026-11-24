package net.evadeclient.mixin;

import net.evadeclient.EvadeClient;
import net.evadeclient.setting.KeySetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the middle and side mouse buttons toggle modules, like keys do (see {@link KeybindInputMixin}).
 * Left and right click are never used for binds. Nothing is cancelled.
 */
@Mixin(Mouse.class)
public abstract class KeybindMouseInputMixin {
    @Inject(method = "onMouseButton", at = @At("HEAD"))
    private void evade$queueBind(long window, MouseInput input, int action, CallbackInfo ci) {
        if (action != GLFW.GLFW_PRESS || EvadeClient.modules == null) return;
        int button = input.button();
        if (button < KeySetting.FIRST_MOUSE_BUTTON || button > KeySetting.LAST_MOUSE_BUTTON) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (window != mc.getWindow().getHandle() || mc.currentScreen != null || mc.getOverlay() != null) return;
        if (mc.options.debugModifierKey.isPressed()) return;
        EvadeClient.modules.queueBindPress(KeySetting.mouseCode(button));
    }
}
