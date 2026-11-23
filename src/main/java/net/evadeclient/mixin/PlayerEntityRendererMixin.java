package net.evadeclient.mixin;

import net.evadeclient.module.visuals.Nametags;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.PlayerLikeEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Nametags: your own tag in third person, and the size, shadow and background of player tags. */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {
    private static final String LABEL = "renderLabelIfPresent(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;"
            + "Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;"
            + "Lnet/minecraft/client/render/state/CameraRenderState;)V";

    /** Whether the label matrices were pushed for scaling and must be popped at the end. */
    @Unique private boolean evade$scaled;

    /**
     * The game never gives the camera's own entity a nametag. Asked only after the distance check, and only ever
     * turns the answer on for you, so no other player's tag changes.
     */
    @Inject(method = "hasLabel(Lnet/minecraft/entity/PlayerLikeEntity;D)Z", at = @At("RETURN"), cancellable = true)
    private void evade$ownNametag(PlayerLikeEntity entity, double squaredDistance, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && Nametags.showOwnTag(entity)) cir.setReturnValue(true);
    }

    /**
     * Scale both label lines (the name and the below-name score) around the point the tag hangs from, so a
     * bigger tag stays centred over the head and the gap between the lines grows with it.
     */
    @Inject(method = LABEL, at = @At("HEAD"))
    private void evade$labelHead(PlayerEntityRenderState state, MatrixStack matrices, OrderedRenderCommandQueue queue,
                                 CameraRenderState camera, CallbackInfo ci) {
        evade$scaled = false;
        if (Nametags.active() == null) return;
        Nametags.playerLabel = true;
        float s = Nametags.labelScale();
        Vec3d pos = state.nameLabelPos;
        if (pos == null || Math.abs(s - 1f) < 0.001f) return;
        // The same anchor the label queue translates to (half a block above the name tag attachment point).
        double ax = pos.x, ay = pos.y + 0.5, az = pos.z;
        matrices.push();
        matrices.translate(ax, ay, az);
        matrices.scale(s, s, s);
        matrices.translate(-ax, -ay, -az);
        evade$scaled = true;
    }

    @Inject(method = LABEL, at = @At("RETURN"))
    private void evade$labelTail(PlayerEntityRenderState state, MatrixStack matrices, OrderedRenderCommandQueue queue,
                                 CameraRenderState camera, CallbackInfo ci) {
        Nametags.playerLabel = false;
        if (evade$scaled) {
            evade$scaled = false;
            matrices.pop();
        }
    }

    /** Text Shadow: both label lines get a shadow colour in their style (labels are drawn without one). */
    @ModifyArg(method = LABEL, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;submitLabel(Lnet/minecraft/client/util/math/MatrixStack;"
                    + "Lnet/minecraft/util/math/Vec3d;ILnet/minecraft/text/Text;ZIDLnet/minecraft/client/render/state/CameraRenderState;)V"),
            index = 3)
    private Text evade$labelShadow(Text label) {
        return Nametags.styleLabel(label);
    }
}
