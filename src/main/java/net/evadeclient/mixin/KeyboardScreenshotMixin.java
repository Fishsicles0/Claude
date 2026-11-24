package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.evadeclient.module.client.HudToggle;
import net.minecraft.client.Keyboard;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.io.File;
import java.util.function.Consumer;

/** HUD Toggle's Hide In Screenshots: F2 waits one frame, drawn without Evade's HUD, before the shot is taken. */
@Mixin(Keyboard.class)
public abstract class KeyboardScreenshotMixin {
    @WrapOperation(method = "onKey", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/util/ScreenshotRecorder;saveScreenshot(Ljava/io/File;Lnet/minecraft/client/gl/Framebuffer;Ljava/util/function/Consumer;)V"))
    private void evade$screenshotWithoutHud(File dir, Framebuffer framebuffer, Consumer<Text> receiver, Operation<Void> original) {
        if (HudToggle.deferScreenshot(dir, receiver)) return;
        original.call(dir, framebuffer, receiver);
    }
}
