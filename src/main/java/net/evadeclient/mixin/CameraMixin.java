package net.evadeclient.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.evadeclient.EvadeClient;
import net.evadeclient.gui.EvadeMenu;
import net.evadeclient.module.mods.Freelook;
import net.evadeclient.module.visuals.CameraClip;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freelook's free camera and smooth return, and Camera Distance (distance, front distance, smooth camera, shoulder
 * offset). Both only change where the camera wants to be: vanilla's own wall raycasts in {@code clipToSpace} always
 * run on the result (except Camera Distance's Wall Mode Clip, and that only in spectator mode), and the shoulder
 * offset is raycast before vanilla pulls the camera back from it, so the camera never ends up inside a block.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    /** Margin kept between the shifted (shoulder) pivot and a block, on top of the camera's own 0.1 corners. */
    @Unique private static final double EVADE$PIVOT_MARGIN = 0.1;
    /** Frames further apart than this (seconds) restart the smoothing instead of easing across the gap. */
    @Unique private static final double EVADE$STALE = 0.25;

    @Shadow private Vec3d pos;
    @Shadow private World area;
    @Shadow private Entity focusedEntity;
    @Shadow @Final private Quaternionf rotation;

    @Shadow protected abstract void setPos(Vec3d pos);

    /** Whether this frame's {@code update} is the front view (the shoulder offset is for the back view only). */
    @Unique private boolean evade$inverseView;
    /** Smooth camera: the eased wanted distance and the distance shown last frame (-1 = start over). */
    @Unique private float evade$wantDistance = -1, evade$shownDistance = -1;
    /** Shoulder offset: the eased sideways/vertical shift and the eased share of it that fits (-1 = start over). */
    @Unique private float evade$sway, evade$heave, evade$reach = -1;
    /** When the camera was last placed in third person ({@link EvadeMenu#clock} ms). */
    @Unique private double evade$lastFrame = Double.NaN;

    @Inject(method = "update", at = @At("HEAD"))
    private void evade$frame(World area, Entity focused, boolean thirdPerson, boolean inverseView, float tickProgress, CallbackInfo ci) {
        evade$inverseView = inverseView;
    }

    /**
     * Freelook: replace the entity's rotation with the free camera's, or with the smooth return's animated angles
     * (which head for the rotation vanilla passes here). Only the two calls that copy the entity's yaw/pitch
     * (minecart and normal path) are wrapped; the front-view flip that follows still works on top of them, and the
     * sleeping view stays vanilla.
     */
    @WrapOperation(method = "update", require = 2, at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setRotation(FF)V", ordinal = 0),
            @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setRotation(FF)V", ordinal = 1)})
    private void evade$freelook(Camera camera, float yaw, float pitch, Operation<Void> original) {
        Freelook fl = evade$freelookModule();
        if (fl != null) {
            if (fl.capturing()) {
                original.call(camera, fl.yaw(), fl.pitch());
                return;
            }
            if (fl.returning()) {
                fl.animateReturn(yaw, pitch);
                original.call(camera, fl.yaw(), fl.pitch());
                return;
            }
        }
        original.call(camera, yaw, pitch);
    }

    /**
     * The third-person camera distance. {@code desired} is vanilla's (your scale times your camera distance
     * attribute, or your mount's if larger). Camera Distance swaps in its own distance (scaled the same way with
     * Follow Player Scale), Freelook's Camera Distance % shrinks it while freelooking, and vanilla's raycasts then
     * pull it in front of any wall. Smooth Camera eases changes of the wanted distance (scrolling, freelook, a new
     * mount) both ways and eases back out after a wall, but snaps in at once when a wall gets in the way, so the
     * camera is never further back than vanilla's raycasts allow.
     */
    @WrapMethod(method = "clipToSpace")
    private float evade$distance(float desired, Operation<Float> original) {
        Freelook fl = evade$freelookModule();
        float factor = fl == null ? 1f : Math.min(1f, fl.distanceFactor());
        CameraClip cc = CameraClip.active();
        if (cc == null) {
            evade$wantDistance = -1;
            evade$shownDistance = -1;
            evade$reach = -1;
            evade$lastFrame = Double.NaN;
            return original.call(desired * factor);
        }

        double now = EvadeMenu.clock.getAsDouble();
        double dt = (now - evade$lastFrame) / 1000.0;
        evade$lastFrame = now;
        // First frame in third person, a long pause or a clock that jumped: no easing from stale values.
        boolean fresh = !(dt >= 0 && dt <= EVADE$STALE);
        float ease = !cc.smooth() || fresh ? 1f : (float) (1 - Math.exp(-cc.smoothSpeed() * Math.min(dt, 0.1)));

        evade$shoulder(cc, ease);

        float want = cc.target(desired) * factor;
        boolean restart = ease >= 1f || evade$wantDistance < 0 || evade$shownDistance < 0
                || !Float.isFinite(evade$wantDistance) || !Float.isFinite(evade$shownDistance);
        evade$wantDistance = restart ? want : evade$wantDistance + (want - evade$wantDistance) * ease;
        // Vanilla's raycasts on the (eased) wanted distance; Clip (spectators only) skips them.
        float raw = cc.clipThrough() ? evade$wantDistance : original.call(evade$wantDistance);
        if (restart) {
            evade$shownDistance = raw;
            return raw;
        }
        // Never further back than the raycasts allow: snaps in at walls, eases back out once they are gone.
        float out = Math.min(raw, evade$shownDistance + (raw - evade$shownDistance) * ease);
        evade$shownDistance = out;
        return out;
    }

    /**
     * Shoulder and Height Offset (back view only): shift the point the camera is pulled back from. The shift is
     * raycast from the eyes with the camera's 0.1 corners plus a margin and stops at the first block, always (Wall
     * Mode included), so vanilla's raycast that follows starts in open space. The aim is not moved: what you hit
     * is still what the player faces, not the centre of the shifted view.
     */
    @Unique
    private void evade$shoulder(CameraClip cc, float ease) {
        if (evade$inverseView) {
            // Front view: no offset, and none to ease back from when the back view returns.
            evade$reach = -1;
            return;
        }
        float wantSway = cc.shoulderOffset();
        float wantHeave = cc.heightOffset();
        if (evade$reach < 0) {
            evade$sway = wantSway;
            evade$heave = wantHeave;
        } else {
            evade$sway += (wantSway - evade$sway) * ease;
            evade$heave += (wantHeave - evade$heave) * ease;
        }
        if (Math.abs(evade$sway) < 1e-3f && Math.abs(evade$heave) < 1e-3f) {
            evade$reach = 1f;
            return;
        }
        if (area == null) return;

        // Camera-local right/up, exactly as Camera.moveBy turns them (x = right, y = up).
        Vector3f off = new Vector3f(evade$sway, evade$heave, 0f).rotate(rotation);
        double len = Math.sqrt((double) off.x * off.x + (double) off.y * off.y + (double) off.z * off.z);
        if (len < 1e-4 || !Double.isFinite(len)) return;
        Vec3d dir = new Vec3d(off.x / len, off.y / len, off.z / len);

        // Like vanilla's clipToSpace: one ray per corner of a 0.2 box around the eyes, keep the shortest.
        double free = len + EVADE$PIVOT_MARGIN;
        for (int i = 0; i < 8; i++) {
            double cx = ((i & 1) * 2 - 1) * 0.1, cy = ((i >> 1 & 1) * 2 - 1) * 0.1, cz = ((i >> 2 & 1) * 2 - 1) * 0.1;
            Vec3d start = pos.add(cx, cy, cz);
            Vec3d end = start.add(dir.multiply(len + EVADE$PIVOT_MARGIN));
            HitResult hit = area.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.VISUAL,
                    RaycastContext.FluidHandling.NONE, focusedEntity));
            if (hit.getType() != HitResult.Type.MISS) free = Math.min(free, hit.getPos().subtract(start).dotProduct(dir));
        }
        float fits = (float) Math.max(0, Math.min(1, (free - EVADE$PIVOT_MARGIN) / len));
        // Snap in at once when a block gets in the way, ease back out like the distance does.
        float reach = evade$reach < 0 ? fits : Math.min(fits, evade$reach + (fits - evade$reach) * ease);
        evade$reach = reach;
        if (reach <= 0) return;
        setPos(pos.add(dir.multiply(len * reach)));
    }

    @Unique
    private static Freelook evade$freelookModule() {
        return EvadeClient.modules == null ? null : EvadeClient.modules.get(Freelook.class);
    }
}
