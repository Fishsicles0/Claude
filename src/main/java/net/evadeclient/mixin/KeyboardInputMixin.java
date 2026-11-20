package net.evadeclient.mixin;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.visuals.Freecam;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    /** Freecam: keys move the camera, so the player itself receives no input. */
    @Inject(method = "tick", at = @At("TAIL"))
    private void evade$freecamInput(CallbackInfo ci) {
        if (EvadeClient.modules == null) return;
        Freecam fc = EvadeClient.modules.get(Freecam.class);
        if (fc == null || !fc.isEnabled()) return;
        Input self = (Input) (Object) this;
        fc.setInput(self.playerInput);
        self.playerInput = PlayerInput.DEFAULT;
        ((InputAccessor) self).evade$setMovementVector(Vec2f.ZERO);
    }
}
