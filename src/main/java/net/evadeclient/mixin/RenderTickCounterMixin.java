package net.evadeclient.mixin;

import it.unimi.dsi.fastutil.floats.FloatUnaryOperator;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.movement.Timer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RenderTickCounter.Dynamic.class)
public abstract class RenderTickCounterMixin {
    /** Timer: shorten the length of a game tick so the client runs faster (or slower). */
    @Redirect(method = "beginRenderTick(J)I", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/floats/FloatUnaryOperator;apply(F)F"))
    private float evade$timer(FloatUnaryOperator op, float tickTime) {
        float ms = op.apply(tickTime);
        if (EvadeClient.modules == null) return ms;
        Timer t = EvadeClient.modules.get(Timer.class);
        return t != null && t.isEnabled() ? ms / t.speed() : ms;
    }
}
