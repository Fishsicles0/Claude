package net.evadeclient.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.evadeclient.module.visuals.ClearWater;
import net.evadeclient.module.visuals.NoFog;
import net.evadeclient.util.Guard;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.fog.FogData;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.world.ClientWorld;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NoFog and Clear Water.
 *
 * <p>Each frame vanilla's {@code applyFog(Camera, ...)} fills a {@link FogData} from the first fog modifier that
 * applies (lava, powder snow, Blindness, Darkness, water, then open air), sets the render-distance fade, and only then
 * copies the FogData into the fog buffer. Sodium's terrain shader does not read that buffer: its own hook on the
 * buffer write reads the FogData fields. So the distances are changed on the FogData itself, right after vanilla
 * (and Sodium Extra, which hooks the {@code renderDistanceEnd} write just above) filled it in and before anyone reads
 * it, which covers vanilla shaders, Sodium terrain and Sodium Extra alike. The point sits before the
 * {@code RenderSystem.getDevice()} call that opens the buffer; priority 1500 keeps this hook after Sodium Extra's
 * (1300) even if the two ever land on the same instruction.
 *
 * <p>Only the two clear environments change: open air (NoFog, see {@link NoFog#apply}) and water (Clear Water).
 * Lava, powder snow, Blindness and Darkness fog are gameplay effects and always stay. NoFog thins the terrain fog,
 * softens the render-distance fade and can open up the sky fog; Clear Water only lifts the underwater fog and keeps
 * vanilla's render-distance fade.
 */
@Mixin(value = FogRenderer.class, priority = 1500)
public abstract class FogRendererMixin {
    @Inject(method = "applyFog(Lnet/minecraft/client/render/Camera;ILnet/minecraft/client/render/RenderTickCounter;FLnet/minecraft/client/world/ClientWorld;)Lorg/joml/Vector4f;",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;getDevice()Lcom/mojang/blaze3d/systems/GpuDevice;"))
    private void evade$clearFog(Camera camera, int viewDistance, RenderTickCounter tickCounter, float skyDarkness, ClientWorld world,
                                CallbackInfoReturnable<Vector4f> cir, @Local FogData data, @Local CameraSubmersionType submersion) {
        Guard.run(NoFog.class, "fog", () -> {
            // NoFog decides for itself whether this frame's fog may change (open air only, never under Blindness or
            // Darkness, per dimension). `submersion` is vanilla's own pick for this frame (open air reads ATMOSPHERIC).
            if (!NoFog.apply(data, camera, world, viewDistance)
                    && submersion == CameraSubmersionType.WATER
                    && ClearWater.active() != null
                    // Blindness and Darkness win over water in vanilla's modifier order; keep their fog.
                    && !NoFog.effectFog(camera.getFocusedEntity())) {
                data.environmentalStart = 1e6f;
                data.environmentalEnd = 2e6f;
            }
            NoFog.LAST_ENV_START = data.environmentalStart;
            NoFog.LAST_ENV_END = data.environmentalEnd;
            NoFog.LAST_RENDER_START = data.renderDistanceStart;
            NoFog.LAST_RENDER_END = data.renderDistanceEnd;
        });
    }
}
