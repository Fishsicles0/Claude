package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
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
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.memory.ObjectPool;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow @Final private ObjectPool pool;

    @Shadow public abstract boolean isRenderingPanorama();

    /** Whether the world was drawn this frame (Motion Blur only runs on frames that have one). */
    @Unique private boolean evade$worldDrawn;

    // ------------------------------------------------------------------ FOV: FOV Changer and Zoom

    /**
     * FOV Changer: its base FOV of this frame (its own FOV or the game's, the Alt FOV, the third-person offset,
     * eased by Smooth Switch) replaces the options value. Effects still apply on top. The fraction lost to the int
     * here is put back in {@link #evade$zoom}.
     */
    @ModifyExpressionValue(method = "getFov", at = @At(value = "INVOKE", target = "Ljava/lang/Integer;intValue()I"))
    private int evade$baseFov(int optionsFov) {
        float base = FovChanger.baseFov();
        return Float.isNaN(base) ? optionsFov : Math.round(base);
    }

    /**
     * FOV Changer: the speed/sprint/flying/bow/spyglass multiplier the game eases towards each tick, with each
     * effect weighted by the module's settings. Done on the target so the change itself is eased like vanilla's.
     */
    @WrapOperation(method = "updateFovMultiplier", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/network/AbstractClientPlayerEntity;getFovMultiplier(ZF)F"))
    private float evade$fovEffects(AbstractClientPlayerEntity player, boolean firstPerson, float effectScale, Operation<Float> original) {
        FovChanger m = FovChanger.active();
        return m != null ? m.multiplier(player, firstPerson, effectScale) : original.call(player, firstPerson, effectScale);
    }

    /** FOV Changer Transition Speed: how far the effect multiplier eases each tick (vanilla's 0.1..1.5 clamp stays). */
    @ModifyConstant(method = "updateFovMultiplier", constant = @Constant(floatValue = 0.5F))
    private float evade$fovTransition(float vanilla) {
        return FovChanger.transition(vanilla);
    }

    /** FOV Changer Underwater FOV off: the underwater/lava narrowing is skipped (only this FOV check sees NONE). */
    @ModifyExpressionValue(method = "getFov", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/Camera;getSubmersionType()Lnet/minecraft/block/enums/CameraSubmersionType;"))
    private CameraSubmersionType evade$underwaterFov(CameraSubmersionType type) {
        return FovChanger.underwater() ? type : CameraSubmersionType.NONE;
    }

    /** FOV Changer Death Zoom off: no slow zoom in while dead. */
    @ModifyExpressionValue(method = "getFov", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;isDead()Z"))
    private boolean evade$deathZoom(boolean dead) {
        return FovChanger.deathZoom(dead);
    }

    /**
     * Zoom narrows the world's FOV (changingFov). The hand (changingFov == false) keeps vanilla's 70, or FOV
     * Changer's Hand FOV, and only zooms when FOV Changer's Hand FOV Ignores Zoom is off. The panorama keeps its 90.
     * Death and underwater changes are already in {@code fov}.
     */
    @ModifyReturnValue(method = "getFov", at = @At("RETURN"))
    private float evade$zoom(float fov, @Local(argsOnly = true) boolean changingFov) {
        if (EvadeClient.modules == null || isRenderingPanorama() || !Float.isFinite(fov)) return fov;
        Zoom zoom = EvadeClient.modules.get(Zoom.class);
        double zoomMult = zoom != null && zoom.isEnabled() ? zoom.fovMultiplier() : 1;
        if (!changingFov) return Math.max(1f, Math.min(179f, FovChanger.handFov(fov, zoomMult)));
        float base = FovChanger.baseFov();
        if (Float.isFinite(base)) {
            int rounded = Math.round(base);
            if (rounded != 0) fov *= base / rounded;
        }
        fov *= (float) zoomMult;
        return Math.max(0.1f, Math.min(179f, fov));
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
        FovChanger fov = EvadeClient.modules.get(FovChanger.class);
        if (fov != null) fov.frame();
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

    /** No Hurt Cam: the world view's share of the hurt tilt (World Shake). The call always runs (death tilt). */
    @WrapOperation(method = "renderWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/GameRenderer;tiltViewWhenHurt(Lnet/minecraft/client/util/math/MatrixStack;F)V"))
    private void evade$worldHurtTilt(GameRenderer self, MatrixStack matrices, float tickProgress, Operation<Void> original) {
        evade$hurtTilt(false, self, matrices, tickProgress, original);
    }

    /** No Hurt Cam: the first-person hand's share of the hurt tilt (Hand Shake). */
    @WrapOperation(method = "renderHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/GameRenderer;tiltViewWhenHurt(Lnet/minecraft/client/util/math/MatrixStack;F)V"))
    private void evade$handHurtTilt(GameRenderer self, MatrixStack matrices, float tickProgress, Operation<Void> original) {
        evade$hurtTilt(true, self, matrices, tickProgress, original);
    }

    @Unique
    private static void evade$hurtTilt(boolean hand, GameRenderer self, MatrixStack matrices, float tickProgress, Operation<Void> original) {
        NoHurtCam m = NoHurtCam.active();
        if (m == null) {
            original.call(self, matrices, tickProgress);
            return;
        }
        NoHurtCam.setScale(m.shake(hand));
        try {
            original.call(self, matrices, tickProgress);
        } finally {
            NoHurtCam.setScale(1f);
        }
    }

    /** No Hurt Cam: vanilla's Damage Tilt strength (the unboxed option value) times the share of this call. */
    @ModifyExpressionValue(method = "tiltViewWhenHurt", at = @At(value = "INVOKE", target = "Ljava/lang/Double;doubleValue()D"))
    private double evade$hurtTiltStrength(double strength) {
        return NoHurtCam.tiltStrength(strength);
    }

    /** No Hurt Cam with Death Tilt off: no sideways tilt while dead. */
    @ModifyExpressionValue(method = "tiltViewWhenHurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;isDead()Z"))
    private boolean evade$deathTilt(boolean dead) {
        return NoHurtCam.deathTilt(dead);
    }

    /** Zoom Hide Hand: skip only the first-person item/arm while zoomed (the rest of the hand pass still runs). */
    @WrapWithCondition(method = "renderHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/network/ClientPlayerEntity;I)V"))
    private boolean evade$zoomHideHand(HeldItemRenderer renderer, float tickProgress, MatrixStack matrices,
                                       OrderedRenderCommandQueue queue, ClientPlayerEntity player, int light) {
        if (EvadeClient.modules == null) return true;
        Zoom zoom = EvadeClient.modules.get(Zoom.class);
        return zoom == null || !zoom.hidesHand();
    }

    /**
     * Zoom Overlay (vignette or spyglass scope), drawn right before the in-game HUD so the HUD stays on top, like
     * vanilla's own spyglass. Runs with the HUD hidden too: InGameHud draws nothing then, but this still does.
     */
    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/InGameHud;render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V"))
    private void evade$zoomOverlay(RenderTickCounter counter, boolean tick, CallbackInfo ci, @Local DrawContext context) {
        if (EvadeClient.modules == null) return;
        Zoom zoom = EvadeClient.modules.get(Zoom.class);
        if (zoom != null) zoom.renderOverlay(context, counter.getDynamicDeltaTicks());
    }

    /** Zoom Level Indicator: right after the in-game HUD (over it), also with the HUD hidden. */
    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/InGameHud;render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            shift = At.Shift.AFTER))
    private void evade$zoomIndicator(RenderTickCounter counter, boolean tick, CallbackInfo ci, @Local DrawContext context) {
        if (EvadeClient.modules == null) return;
        Zoom zoom = EvadeClient.modules.get(Zoom.class);
        if (zoom != null) zoom.renderIndicator(context);
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
