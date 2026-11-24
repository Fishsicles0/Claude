package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ShieldItem;
import net.minecraft.util.Hand;
import net.minecraft.util.math.RotationAxis;

/**
 * How your held items look in first person: position, rotation and size per hand, how fast the swing animation
 * plays, the equip dip and the shield. Drawing only: nothing here touches attack timing, cooldowns, reach or what is
 * sent to the server. Shares HeldItemRendererMixin with 1.7 Animations (both can be on; Item View's offsets apply on
 * top of 1.7's lowered position). The fire overlay height and lowering the shield live in Pack Tweaks.
 */
public class ItemView extends Module {
    // ---- main hand
    public final NumberSetting mainX = add(new NumberSetting("Main Hand X", 0, -0.5, 0.5, 0.01)
            .describe("Moves the item in your main hand left (negative) or right, in blocks."));
    public final NumberSetting mainY = add(new NumberSetting("Main Hand Y", 0, -0.5, 0.5, 0.01)
            .describe("Moves the item in your main hand down (negative) or up, in blocks."));
    public final NumberSetting mainZ = add(new NumberSetting("Main Hand Z", 0, -0.5, 0.5, 0.01)
            .describe("Moves the item in your main hand closer (positive) or further away, in blocks."));
    public final NumberSetting mainPitch = add(new NumberSetting("Main Hand Tilt", 0, -90, 90, 5)
            .describe("Tips the item in your main hand forward or back, in degrees."));
    public final NumberSetting mainYaw = add(new NumberSetting("Main Hand Turn", 0, -90, 90, 5)
            .describe("Turns the item in your main hand left or right, in degrees."));
    public final NumberSetting mainRoll = add(new NumberSetting("Main Hand Roll", 0, -90, 90, 5)
            .describe("Rolls the item in your main hand sideways, in degrees."));
    public final NumberSetting mainScale = add(new NumberSetting("Main Hand Size %", 100, 25, 200, 5)
            .describe("How big the item in your main hand is drawn."));
    // ---- off hand
    public final NumberSetting offX = add(new NumberSetting("Off Hand X", 0, -0.5, 0.5, 0.01)
            .describe("Moves the item in your off hand left (negative) or right, in blocks."));
    public final NumberSetting offY = add(new NumberSetting("Off Hand Y", 0, -0.5, 0.5, 0.01)
            .describe("Moves the item in your off hand down (negative) or up, in blocks."));
    public final NumberSetting offZ = add(new NumberSetting("Off Hand Z", 0, -0.5, 0.5, 0.01)
            .describe("Moves the item in your off hand closer (positive) or further away, in blocks."));
    public final NumberSetting offPitch = add(new NumberSetting("Off Hand Tilt", 0, -90, 90, 5)
            .describe("Tips the item in your off hand forward or back, in degrees."));
    public final NumberSetting offYaw = add(new NumberSetting("Off Hand Turn", 0, -90, 90, 5)
            .describe("Turns the item in your off hand left or right, in degrees."));
    public final NumberSetting offRoll = add(new NumberSetting("Off Hand Roll", 0, -90, 90, 5)
            .describe("Rolls the item in your off hand sideways, in degrees."));
    public final NumberSetting offScale = add(new NumberSetting("Off Hand Size %", 100, 25, 200, 5)
            .describe("How big the item in your off hand is drawn."));
    // ---- animation
    public final NumberSetting swingSpeed = add(new NumberSetting("Swing Speed %", 100, 25, 300, 5)
            .describe("How fast your hand's swing animation plays on your screen. Looks only: attack speed and cooldown don't change."));
    public final BooleanSetting equipAnimation = add(new BooleanSetting("Equip Animation", true)
            .describe("The dip when you switch items or after an attack. Off: held items stay put."));
    public final ModeSetting shield = add(new ModeSetting("Shield", "Normal", "Normal", "Hide When Idle", "Hide")
            .describe("Hide your shield in first person, or only while you aren't blocking. It still blocks as normal."));

    public ItemView() {
        super("Item View", "Position, rotation and size of held items per hand, swing speed and shield.", Category.VISUALS);
    }

    public static ItemView active() {
        if (EvadeClient.modules == null) return null;
        ItemView v = EvadeClient.modules.get(ItemView.class);
        return v != null && v.isEnabled() ? v : null;
    }

    // ------------------------------------------------------------------ hooks (HeldItemRendererMixin)

    /** Right after vanilla's push for this hand: the per-hand offset, in view space (x is mirrored for the left arm). */
    public void applyOffset(MatrixStack m, Hand hand, boolean rightArm) {
        boolean main = hand == Hand.MAIN_HAND;
        float x = (main ? mainX : offX).getFloat(), y = (main ? mainY : offY).getFloat(), z = (main ? mainZ : offZ).getFloat();
        if (x == 0 && y == 0 && z == 0) return;
        m.translate(rightArm ? x : -x, y, z);
    }

    /** Right before the item is drawn: rotation and size around the item itself (mirrored for the left arm). */
    public void applyItemTransform(MatrixStack m, Hand hand, boolean rightArm) {
        boolean main = hand == Hand.MAIN_HAND;
        float pitch = (main ? mainPitch : offPitch).getFloat();
        float yaw = (main ? mainYaw : offYaw).getFloat();
        float roll = (main ? mainRoll : offRoll).getFloat();
        float side = rightArm ? 1 : -1;
        if (yaw != 0) m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(side * yaw));
        if (pitch != 0) m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch));
        if (roll != 0) m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(side * roll));
        float k = (main ? mainScale : offScale).getFloat() / 100f;
        if (Math.abs(k - 1) > 0.001f) m.scale(k, k, k);
    }

    /** Whether this hand's item is skipped in first person (Shield: Hide / Hide When Idle). */
    public boolean hides(ClientPlayerEntity player, Hand hand, ItemStack item) {
        if (shield.is("Normal") || !(item.getItem() instanceof ShieldItem)) return false;
        if (shield.is("Hide")) return true;
        return !(player.isUsingItem() && player.getActiveHand() == hand);
    }

    // ------------------------------------------------------------------ visual swing

    /** When the visual swing began (System.nanoTime), and whether one is playing. */
    private long swingStartNs;
    private boolean swinging;
    /** Vanilla's swing progress last frame, to see a new swing start (it drops back) or begin from rest. */
    private float lastReal;

    /**
     * The swing progress to draw (0..1), given vanilla's {@code real} progress and swing length in ticks. At 100% it
     * is vanilla's own; otherwise a separate clock that starts when vanilla's swing starts and runs at Swing Speed.
     * The game's swing (and everything that reads it) is never changed.
     */
    public float visualSwing(float real, int durationTicks) {
        return visualSwing(real, durationTicks, System.nanoTime());
    }

    /** {@link #visualSwing(float, int)} at a given time (System.nanoTime scale; also the test hook). */
    public float visualSwing(float real, int durationTicks, long now) {
        float speed = swingSpeed.getFloat() / 100f;
        if (Math.abs(speed - 1) < 0.001f) {
            swinging = false;
            lastReal = real;
            return real;
        }
        long lengthNs = (long) (Math.max(1, durationTicks) * 50_000_000L / speed);
        boolean started = real > 0 && (lastReal <= 0 || real < lastReal - 0.02f);
        lastReal = real;
        if (started) {
            // Start where vanilla's swing already is, so a slower swing never jumps back at its first frame.
            swingStartNs = now - (long) (real * lengthNs);
            swinging = true;
        }
        if (!swinging) return 0;
        float p = (now - swingStartNs) / (float) lengthNs;
        if (p >= 1f) {
            swinging = false;
            return 0;
        }
        return Math.max(0, p);
    }
}
