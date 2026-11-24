package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.evadeclient.module.visuals.Hitboxes;
import net.evadeclient.util.Guard;
import net.minecraft.client.render.DrawStyle;
import net.minecraft.client.render.debug.EntityHitboxDebugRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.debug.gizmo.VisibilityConfigurable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hitboxes module: restyles vanilla's F3+B hitbox view. Only runs when the game is already drawing it (this renderer
 * is only called with F3+B on), and only changes colours, widths and which of vanilla's boxes are drawn.
 */
@Mixin(EntityHitboxDebugRenderer.class)
public abstract class EntityHitboxDebugRendererMixin {
    private static final String DRAW_HITBOX = "drawHitbox(Lnet/minecraft/entity/Entity;FZ)V";

    /** Type filters: Players, Items, Projectiles, Other Entities. */
    @Inject(method = DRAW_HITBOX, at = @At("HEAD"), cancellable = true)
    private void evade$filter(Entity entity, float tickProgress, boolean inLocalServer, CallbackInfo ci) {
        Guard.run(Hitboxes.class, "type filter", () -> {
            Hitboxes h = Hitboxes.active();
            if (h != null && !h.shows(entity)) ci.cancel();
        });
    }

    /** Box colour, eye line colour/visibility and line width, told apart by the colour vanilla passes. */
    @WrapOperation(method = DRAW_HITBOX, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/DrawStyle;stroked(I)Lnet/minecraft/client/render/DrawStyle;"))
    private DrawStyle evade$style(int stroke, Operation<DrawStyle> original) {
        Hitboxes h = Hitboxes.active();
        return h == null ? original.call(stroke) : h.restyle(stroke);
    }

    /** The entity's centre point follows Box Color. */
    @ModifyArg(method = DRAW_HITBOX, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/debug/gizmo/GizmoDrawing;point(Lnet/minecraft/util/math/Vec3d;IF)Lnet/minecraft/world/debug/gizmo/VisibilityConfigurable;"),
            index = 1)
    private int evade$pointColor(int color) {
        return Guard.getInt(Hitboxes.class, "point colour", () -> {
            Hitboxes h = Hitboxes.active();
            return h == null ? color : h.pointColor(color);
        }, color);
    }

    /** Look Vector off: skip the blue look arrow (the result is unused by vanilla). */
    @WrapOperation(method = DRAW_HITBOX, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/debug/gizmo/GizmoDrawing;arrow(Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;I)Lnet/minecraft/world/debug/gizmo/VisibilityConfigurable;"))
    private VisibilityConfigurable evade$lookVector(Vec3d start, Vec3d end, int color, Operation<VisibilityConfigurable> original) {
        Hitboxes h = Hitboxes.active();
        if (h != null && color == Hitboxes.VANILLA_LOOK && !h.lookVector.get()) return null;
        return original.call(start, end, color);
    }
}
