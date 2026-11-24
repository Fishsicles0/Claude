package net.evadeclient.module.visuals;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.EntityVisualsDuck;
import net.minecraft.client.render.entity.state.ItemEntityRenderState;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Dropped items behave like objects instead of hovering and spinning: they tumble through the air (rolling the way
 * they fly), tip over flat when they land and rest there; in water they float with a gentle sway. "Flat" just lays
 * them on the ground. Visual only: where an item is, and when it can be picked up, is entirely vanilla.
 *
 * Each item entity has a {@link Motion} (its current orientation, integrated every frame from the entity's own
 * velocity and ground/water state). The renderer reads it through the render state (ItemEntityRendererMixin).
 */
public class ItemPhysics extends Module {
    /** Air tumble: degrees per tick at rest, plus this many per block/tick of horizontal speed. */
    private static final float SPIN_BASE = 8f, SPIN_PER_SPEED = 60f;
    /** How quickly a landed item tips over to its resting side (per tick; ~90% within 6 ticks). */
    private static final float SETTLE_RATE = 0.4f;
    /** Thinner than this (model units) and vanilla treats the item as flat: it lies down; thicker ones stand. */
    public static final float FLAT_MAX_THICKNESS = 0.0625f;

    public final ModeSetting mode = add(new ModeSetting("Mode", "Physics", "Physics", "Flat")
            .describe("Physics: items tumble through the air and tip over flat when they land. Flat: they just lie on the ground."));
    public final NumberSetting spinSpeed = add(new NumberSetting("Spin Speed", 1.0, 0.25, 3.0, 0.25)
            .describe("How fast items tumble while they fly (Physics)."));
    public final BooleanSetting floatInWater = add(new BooleanSetting("Float In Water", true)
            .describe("Items in water bob and sway gently instead of lying still."));
    public final NumberSetting itemSize = add(new NumberSetting("Item Size", 1.0, 0.5, 2.0, 0.1)
            .describe("Size of dropped items (1 = vanilla)."));
    public final BooleanSetting randomRotation = add(new BooleanSetting("Random Rotation", true)
            .describe("Each item lies at its own angle. Off: they all line up."));

    private static ItemPhysics instance;

    /** Per item entity; weak keys, so an item that is gone from the world is forgotten with it. Render thread only. */
    private final Map<ItemEntity, Motion> motions = new WeakHashMap<>();
    private final Quaternionf scratch = new Quaternionf();
    private final Vector3f axis = new Vector3f(), probe = new Vector3f();

    public ItemPhysics() {
        super("Item Physics", "Dropped items tumble, land flat and float in water instead of spinning in place.", Category.VISUALS);
        instance = this;
    }

    /** The enabled module, or null (render thread, every item, every frame: a field read). */
    public static ItemPhysics active() {
        ItemPhysics p = instance;
        return p != null && p.isEnabled() ? p : null;
    }

    public static boolean on() { return active() != null; }

    @Override
    protected void onDisable() { motions.clear(); }

    public boolean physics() { return mode.is("Physics"); }

    /** Item Size as a float. */
    public float size() { return itemSize.getFloat(); }

    /** The item's resting yaw: its own random angle, or 0 when Random Rotation is off. */
    public float yaw(ItemEntityRenderState state) {
        return randomRotation.get() ? state.uniqueOffset * 6.2831855f : 0f;
    }

    /** One item entity's motion: where it is in its tumble, and whether it is floating. */
    public static final class Motion {
        /** World-space orientation applied on top of the resting pose (identity = lying at rest). */
        public final Quaternionf orientation = new Quaternionf();
        /** Floating in water this frame (and Float In Water is on). */
        public boolean floating;
        /** Horizontal tumble axis (unit), kept while the item has no horizontal speed. */
        private float axisX, axisZ;
        private float lastTime = Float.NaN;
        /** At rest in its resting pose: nothing to do until it moves through the air again. */
        private boolean settled = true;

        Motion(float uniqueOffset) {
            double a = uniqueOffset * 6.2831855;
            axisX = (float) Math.cos(a);
            axisZ = (float) Math.sin(a);
        }
    }

    /**
     * Called when the item's render state is built (render thread, once per item per frame): advances its motion to
     * this frame and hands it to the renderer through the state.
     */
    public static void update(ItemEntity entity, ItemEntityRenderState state, float tickDelta) {
        ItemPhysics p = active();
        if (p == null || state.itemRenderState.isEmpty()) return;
        Motion m = p.motions.get(entity);
        if (m == null) p.motions.put(entity, m = new Motion(state.uniqueOffset));
        p.advance(m, entity, state, tickDelta);
        if (state instanceof EntityVisualsDuck duck) duck.evade$setItemMotion(m);
    }

    private void advance(Motion m, ItemEntity entity, ItemEntityRenderState state, float tickDelta) {
        float now = entity.age + tickDelta;
        float dt = Float.isNaN(m.lastTime) ? 0f : now - m.lastTime;
        if (dt < 0f || dt > 20f) dt = 0f; // a reset age or a long pause: don't integrate across it
        m.lastTime = now;
        boolean water = entity.isTouchingWater();
        m.floating = water && floatInWater.get();
        if (!physics()) {
            m.orientation.identity();
            m.settled = true;
            return;
        }
        if (dt == 0f) return;
        boolean resting = entity.isOnGround() || water || entity.isInLava();
        if (!resting) {
            // Roll the way it flies: the axis is horizontal and square to the horizontal velocity.
            Vec3d v = entity.getVelocity();
            double hs = Math.sqrt(v.x * v.x + v.z * v.z);
            if (hs > 1.0e-3) {
                m.axisX = (float) (v.z / hs);
                m.axisZ = (float) (-v.x / hs);
            }
            float degrees = spinSpeed.getFloat() * (SPIN_BASE + SPIN_PER_SPEED * (float) Math.min(hs, 1.0)) * dt;
            scratch.rotationAxis((float) Math.toRadians(degrees), m.axisX, 0f, m.axisZ);
            m.orientation.premul(scratch).normalize();
            m.settled = false;
        } else {
            settle(m, state, dt);
        }
    }

    /**
     * Tip over towards the nearest resting pose: flat items onto either face, block-like items onto whichever side
     * is closest to facing up. Eases in, then snaps once it is within a fraction of a degree.
     */
    private void settle(Motion m, ItemEntityRenderState state, float dt) {
        if (m.settled) return;
        Box box = state.itemRenderState.getModelBoundingBox();
        boolean flat = box.getLengthZ() <= FLAT_MAX_THICKNESS;
        // The orientation is applied on top of the yaw; find the model axis (after yaw) that is closest to vertical.
        // Lying down, a flat item's thin side is its Y; a block may rest on any of its three axes.
        Quaternionf full = scratch.set(m.orientation).rotateY(yaw(state));
        float bestY = -1f;
        for (int i = flat ? 1 : 0; i < (flat ? 2 : 3); i++) {
            full.transform(i == 0 ? 1f : 0f, i == 1 ? 1f : 0f, i == 2 ? 1f : 0f, probe);
            if (Math.abs(probe.y) > bestY) {
                bestY = Math.abs(probe.y);
                axis.set(probe);
            }
        }
        // The smallest rotation that stands that axis straight up (or down), applied on top of the current tumble.
        Quaternionf target = scratch.rotationTo(axis.x, axis.y, axis.z, 0f, axis.y < 0f ? -1f : 1f, 0f).mul(m.orientation);
        if (bestY > 0.99999f) {
            m.orientation.set(target).normalize();
            m.settled = true;
            return;
        }
        float t = 1f - (float) Math.exp(-SETTLE_RATE * dt);
        m.orientation.slerp(target, t).normalize();
    }
}
