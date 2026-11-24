package net.evadeclient.mixin;

import net.evadeclient.module.mods.ToggleSprint;
import net.evadeclient.util.Guard;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Toggle Sprint: once the game has read the movement keys for this tick, a toggled sprint or sneak counts as held,
 * exactly as if the key were down. The key bindings themselves are never pressed, so they always match the real
 * keys and the game's own Sprint/Sneak: Toggle options keep working; turning the module off lets go at once.
 * Movement (forward, strafe, jump) is untouched.
 */
@Mixin(KeyboardInput.class)
public abstract class ToggleSprintInputMixin extends Input {
    @Inject(method = "tick", at = @At("TAIL"))
    private void evade$toggleSprint(CallbackInfo ci) {
        Guard.run(ToggleSprint.class, "input", () -> {
            PlayerInput in = this.playerInput;
            boolean sneak = ToggleSprint.sneakInput(in.sneak()), sprint = ToggleSprint.sprintInput(in.sprint());
            if (sneak == in.sneak() && sprint == in.sprint()) return;
            this.playerInput = new PlayerInput(in.forward(), in.backward(), in.left(), in.right(), in.jump(), sneak, sprint);
        });
    }
}
