package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.mods.FovChanger;
import net.evadeclient.module.mods.NoHurtCam;
import net.evadeclient.module.mods.WeatherChanger;
import net.evadeclient.module.mods.Zoom;
import net.evadeclient.module.visuals.MotionBlur;
import net.evadeclient.module.visuals.NoRender;
import net.evadeclient.util.Projection;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.memory.ObjectPool;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow @Final private ObjectPool pool;

    @Shadow public abstract boolean isRenderingPanorama();

    /** Whether the world was drawn this frame (Motion Blur only runs on frames that have one). */
    @Unique private boolean evade$worldDrawn;

    // ------------------------------------------------------------------ FOV: FOV Changer and Zoom

    /** FOV Changer with "Use Game FOV" off: its own FOV replaces the options value (effects still apply on top). */
    @ModifyExpressionValue(method = "getFov", at = @At(value = "INVOKE", target = "Ljava/lang/Integer;intValue()I"))
    private int evade$baseFov(int optionsFov) {
        Integer fov = FovChanger.baseFov();
        return fov != null ? fov : optionsFov;
    }

    /**
     * FOV Changer: the speed/sprint/flying multiplier the game eases towards each tick is held at 1 (the spyglass
     * and, if wanted, the bow still zoom). Done on the target so the change itself is eased like vanilla's.
     */
    @ModifyExpressionValue(method = "updateFovMultiplier", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/network/AbstractClientPlayerEntity;getFovMultiplier(ZF)F"))
    private float evade$fovEffects(float multiplier) {
        return FovChanger.effectMultiplier(multiplier);
    }

    /**
     * Zoom narrows the world's FOV only: the hand (changingFov == false) keeps vanilla's 70 so it never magnifies,
     * and the panorama keeps its 90. Death and underwater changes are already in {@code fov}.
     */
    @ModifyReturnValue(method = "getFov", at = @At("RETURN"))
    private float evade$zoom(float fov, @Local(argsOnly = true) boolean changingFov) {
        if (!changingFov || EvadeClient.modules == null || isRenderingPanorama()) return fov;
        Zoom zoom = EvadeClient.modules.get(Zoom.class);
        if (zoom != null && zoom.isEnabled()) fov *= (float) zoom.fovMultiplier();
        return fov;
    }

    // ------------------------------------------------------------------ world frame

    /** Once per world frame, before anything reads the FOV or the weather. */
    @Inject(method = "renderWorld", at = @At("HEAD"))
    private void evade$worldStart(RenderTickCounter counter, CallbackInfo ci) {
        evade$worldDrawn = true;
        WeatherChanger.beginVisuals();
        if (EvadeClient.modules == null) return;
        Zoom zoom = EvadeClient.modules.get(Zoom.class);
        if (zoom != null) zoom.frame();
    }

    @Inject(method = "renderWorld", at = @At("RETURN"))
    private void evade$worldEnd(RenderTickCounter counter, CallbackInfo ci) {
        WeatherChanger.endVisuals();
    }

    /** Projection: the world FOV of this frame (zoom and FOV Changer included). */
    @ModifyArg(method = "renderWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/GameRenderer;getBasicProjectionMatrix(F)Lorg/joml/Matrix4f;"))
    private float evade$captureFov(float fov) {
        Projection.fov = fov;
        return fov;
    }

    /**
     * Projection: the exact matrix the world is drawn with (FOV, view bobbing, hurt tilt, nausea), so HUD labels
     * from Waypoints, TNT Timer and Nametags stay glued to their targets.
     */
    @ModifyArg(method = "renderWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/RawProjectionMatrix;set(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"))
    private Matrix4f evade$captureProjection(Matrix4f projection) {
        Projection.capture(projection);
        return projection;
    }

    /**
     * NoRender "Nausea & Portal": a nausea strength of 0 skips vanilla's whole screen warp (the rotate/scale of the
     * projection), whatever Distortion Effects is set to. The overlays are hidden in InGameHudMixin.
     */
    @ModifyExpressionValue(method = "renderWorld", at = @At(value = "INVOKE", target = "Ljava/lang/Math;max(FF)F", ordinal = 0))
    private float evade$noNauseaWarp(float strength) {
        return NoRender.hide(n -> n.nausea) ? 0f : strength;
    }

    /** Weather Changer: rain particles and rain sounds follow the chosen weather too. */
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/WorldRenderer;tick(Lnet/minecraft/client/render/Camera;)V"))
    private void evade$weatherTick(WorldRenderer worldRenderer, Camera camera, Operation<Void> original) {
        WeatherChanger.beginVisuals();
        try {
            original.call(worldRenderer, camera);
        } finally {
            WeatherChanger.endVisuals();
        }
    }

    /** Motion Blur: after the world and vanilla's own post effects, before the HUD and menus are drawn. */
    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/fog/FogRenderer;rotate()V"))
    private void evade$motionBlur(RenderTickCounter counter, boolean tick, CallbackInfo ci) {
        boolean drawn = evade$worldDrawn;
        evade$worldDrawn = false;
        if (drawn && !isRenderingPanorama()) MotionBlur.afterWorld(pool);
        else MotionBlur.afterWorld(null);
    }

    // ------------------------------------------------------------------ other modules

    @Inject(method = "tiltViewWhenHurt", at = @At("HEAD"), cancellable = true)
    private void evade$noHurtCam(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        if (EvadeClient.modules == null) return;
        NoHurtCam m = EvadeClient.modules.get(NoHurtCam.class);
        if (m != null && m.isEnabled()) ci.cancel();
    }

    /** NoRender: skip the big totem-pop animation. */
    @Inject(method = "showFloatingItem", at = @At("HEAD"), cancellable = true)
    private void evade$noTotem(net.minecraft.item.ItemStack stack, CallbackInfo ci) {
        if (NoRender.hide(n -> n.totem)) ci.cancel();
    }

    /** Clips: grab the finished frame (world, HUD and screens) for the instant-replay buffer. */
    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At("RETURN"))
    private void evade$clipFrame(RenderTickCounter counter, boolean tick, CallbackInfo ci) {
        net.evadeclient.util.FrameStats.frameEnd();
        net.evadeclient.module.misc.Clips.onFrameEnd();
    }
}
