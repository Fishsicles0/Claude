package net.evadeclient.mixin;

import net.evadeclient.module.misc.ScreenshotTools;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Consumer;

/** Screenshot Tools: adds [Copy] [Folder] [Delete] to the "Saved screenshot as ..." chat message. */
@Mixin(ScreenshotRecorder.class)
public abstract class ScreenshotRecorderMixin {
    @ModifyVariable(method = "saveScreenshot(Ljava/io/File;Ljava/lang/String;Lnet/minecraft/client/gl/Framebuffer;ILjava/util/function/Consumer;)V",
            at = @At("HEAD"), argsOnly = true)
    private static Consumer<Text> evade$screenshotButtons(Consumer<Text> messageReceiver) {
        return ScreenshotTools.wrap(messageReceiver);
    }
}
