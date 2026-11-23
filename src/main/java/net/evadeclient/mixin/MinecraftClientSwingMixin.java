package net.evadeclient.mixin;

import net.evadeclient.module.visuals.OldAnimations;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.7 Animations "Swing While Eating": while you use an item (eat, drink, block), vanilla throws away attack presses,
 * so the hand never swings and the eat pose never shows a swing. This plays the swing on your own screen only: the client-side
 * swing animation, with no packet, no attack and no block breaking (others see nothing, exactly as in vanilla).
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientSwingMixin {
    @Shadow public ClientPlayerEntity player;
    @Shadow @Final public GameOptions options;
    @Shadow @Final public Mouse mouse;
    @Shadow public Screen currentScreen;

    /** Runs just before vanilla checks {@code player.isUsingItem()} and drains the attack key in that branch. */
    @Inject(method = "handleInputEvents", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;isUsingItem()Z", ordinal = 0))
    private void evade$swingWhileUsing(CallbackInfo ci) {
        ClientPlayerEntity p = this.player;
        if (p == null || !p.isUsingItem() || !this.options.useKey.isPressed()) return;
        OldAnimations o = OldAnimations.active();
        if (o == null || !o.swingWhileUsing.get()) return;
        // A click starts a swing (vanilla drains any further presses right after this).
        boolean clicked = this.options.attackKey.wasPressed();
        // Holding attack keeps swinging, one full swing after another, like 1.7.
        boolean held = this.currentScreen == null && this.mouse.isCursorLocked() && this.options.attackKey.isPressed() && !p.handSwinging;
        // LivingEntity.swingHand(hand, false): the local animation only. ClientPlayerEntity.swingHand(hand) would
        // also send a HandSwingC2SPacket, which this deliberately never does.
        if (clicked || held) p.swingHand(Hand.MAIN_HAND, false);
    }
}
