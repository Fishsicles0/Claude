package net.evadeclient.module.visuals;

import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.gui.EvadeMenu;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

/**
 * Camera Distance: set how far back the third-person camera sits (4 is vanilla), separately for the front view, with
 * an optional over-the-shoulder offset, smooth movement and scroll-wheel adjusting.
 *
 * <p>Server-safe by design: the camera always stops in front of blocks exactly like vanilla's (CameraMixin keeps
 * vanilla's own wall raycasts and only changes the distance they start from), the distance is capped at 8, and
 * the shoulder offset is raycast too, so it never enters a wall. Wall Mode "Clip" only does anything in spectator
 * mode, where the player can already fly through blocks.
 */
public class CameraClip extends Module {
    /** Vanilla's camera distance at normal size ({@code Camera.BASE_CAMERA_DISTANCE}). */
    private static final float VANILLA = 4f;

    private final NumberSetting distance = add(new NumberSetting("Distance", 4, 1, 8, 0.25)
            .describe("Blocks behind you at normal size; 4 is the vanilla distance."));
    private final NumberSetting frontDistance = add(new NumberSetting("Front Distance", 4, 1, 8, 0.25)
            .describe("Blocks in front of you in the front view (press F5 twice)."));
    private final BooleanSetting followScale = add(new BooleanSetting("Follow Player Scale", true)
            .describe("Grow and shrink the distance with your size and your mount's, like vanilla (a big mount pulls the camera further back)."));
    private final ModeSetting wallMode = add(new ModeSetting("Wall Mode", "Collide", "Collide", "Clip")
            .describe("Collide stops the camera in front of blocks, like vanilla. Clip lets it pass through blocks, but only in spectator mode; everywhere else it collides."));
    private final BooleanSetting smooth = add(new BooleanSetting("Smooth Camera", true)
            .describe("Ease the camera to a new distance instead of jumping. It still snaps in at once when a wall gets in the way."));
    private final NumberSetting smoothSpeed = add(new NumberSetting("Smoothing Speed", 10, 2, 30, 1)
            .describe("How quickly the smooth camera catches up; higher is snappier."));
    private final BooleanSetting scrollAdjust = add(new BooleanSetting("Scroll To Adjust", false)
            .describe("Scroll the mouse wheel in third person to move the camera closer or further. Off keeps hotbar scrolling as normal."));
    private final KeySetting scrollKey = add(new KeySetting("Scroll Key", GLFW.GLFW_KEY_UNKNOWN, true)
            .describe("Only adjust the distance while this key is held, so scrolling still changes your hotbar slot otherwise. None: always adjust in third person."));
    private final NumberSetting scrollStep = add(new NumberSetting("Scroll Step", 0.5, 0.25, 2.0, 0.25)
            .describe("Blocks the camera moves per scroll notch."));
    private final NumberSetting shoulder = add(new NumberSetting("Shoulder Offset", 0, -2.0, 2.0, 0.1)
            .describe("Shift the back view sideways for an over-the-shoulder camera: positive right, negative left. "
                    + "Your aim does not move, so a third-person crosshair no longer lines up exactly with what you hit."));
    private final NumberSetting heightOffset = add(new NumberSetting("Height Offset", 0, -1.0, 1.0, 0.1)
            .describe("Raise or lower the back view. It stops at ceilings and floors."));
    private final KeySetting swapKey = add(new KeySetting("Swap Shoulder Key", GLFW.GLFW_KEY_UNKNOWN, true)
            .describe("Press to move the camera to your other shoulder."));

    /** Part of a scroll notch not applied yet (smooth-scrolling touchpads send fractions of a notch). */
    private double scrollRemainder;
    /** When ({@link EvadeMenu#clock} ms) a scroll change should be written to the config, or NaN if nothing is pending. */
    private double saveAt = Double.NaN;
    private boolean swapWasDown;

    public CameraClip() {
        super("Camera Distance", "Set your third-person camera distance.", Category.VISUALS);
    }

    // ------------------------------------------------------------------ CameraMixin API

    /** The module if it is on, else null. */
    public static CameraClip active() {
        if (EvadeClient.modules == null) return null;
        CameraClip c = EvadeClient.modules.get(CameraClip.class);
        return c != null && c.isEnabled() ? c : null;
    }

    /** The chosen back-view distance (vanilla is 4), or -1 when off. */
    public static float distance() {
        CameraClip c = active();
        return c != null ? c.distance.getFloat() : -1;
    }

    /**
     * How far back the camera should sit, given vanilla's own wanted distance ({@code desired}: the larger of your
     * scale times your camera distance attribute and your mount's, 4 at normal size on foot).
     */
    public float target(float desired) {
        float base = isFrontView() ? frontDistance.getFloat() : distance.getFloat();
        if (!followScale.get()) return base;
        if (!Float.isFinite(desired) || desired <= 0) return base;
        return base * desired / VANILLA;
    }

    /** Whether the camera may pass through blocks right now: Wall Mode Clip, and only while in spectator mode. */
    public boolean clipThrough() {
        return wallMode.is("Clip") && mc.player != null && mc.player.isSpectator();
    }

    public boolean smooth() { return smooth.get(); }

    /** Exponential smoothing rate (per second) for the smooth camera. */
    public float smoothSpeed() { return smoothSpeed.getFloat(); }

    /** Sideways shift of the back view in blocks (positive right, negative left). */
    public float shoulderOffset() { return shoulder.getFloat(); }

    /** Vertical shift of the back view in blocks. */
    public float heightOffset() { return heightOffset.getFloat(); }

    private boolean isFrontView() {
        return mc.options != null && mc.options.getPerspective() == Perspective.THIRD_PERSON_FRONT;
    }

    // ------------------------------------------------------------------ scroll to adjust

    /**
     * A mouse wheel event in game (MouseMixin, after Zoom had its turn). Returns true when it moved the camera, so
     * vanilla's hotbar scroll is skipped.
     */
    public boolean onScroll(double vertical) {
        if (!isEnabled() || !scrollAdjust.get() || vertical == 0 || !Double.isFinite(vertical)) return false;
        if (mc.player == null || mc.currentScreen != null || mc.getOverlay() != null) return false;
        if (mc.options.getPerspective().isFirstPerson()) return false;
        // The spectator menu (middle click) uses the wheel to pick an entry.
        if (mc.player.isSpectator() && mc.inGameHud.getSpectatorHud().isOpen()) return false;
        if (scrollKey.get() != GLFW.GLFW_KEY_UNKNOWN && !scrollKey.isDown()) return false;

        double amount = mc.options.getDiscreteMouseScroll().getValue() ? Math.signum(vertical) : vertical;
        amount *= mc.options.getMouseWheelSensitivity().getValue();
        if (scrollRemainder != 0 && Math.signum(amount) != Math.signum(scrollRemainder)) scrollRemainder = 0;
        scrollRemainder += amount;
        int notches = (int) scrollRemainder; // whole notches, toward zero
        if (notches == 0) return true;
        scrollRemainder -= notches;
        notches = MathHelper.clamp(notches, -10, 10);

        NumberSetting target = isFrontView() ? frontDistance : distance;
        // Wheel up brings the camera closer, like zooming in.
        target.set(target.get() - notches * scrollStep.get());
        saveAt = EvadeMenu.clock.getAsDouble() + 1500;
        return true;
    }

    // ------------------------------------------------------------------ module hooks

    @Override
    public void onTick() {
        boolean swapDown = swapKey.isDown();
        if (swapDown && !swapWasDown && shoulder.get() != 0) {
            shoulder.set(-shoulder.get());
            saveAt = EvadeMenu.clock.getAsDouble() + 1500;
        }
        swapWasDown = swapDown;

        double now = EvadeMenu.clock.getAsDouble();
        // A clock swapped by tests can jump back: save then rather than waiting for it to catch up.
        if (!Double.isNaN(saveAt) && (now >= saveAt || saveAt - now > 5000)) flushSave();
    }

    @Override
    protected void onDisable() {
        scrollRemainder = 0;
        swapWasDown = false;
        flushSave();
    }

    private void flushSave() {
        if (Double.isNaN(saveAt)) return;
        saveAt = Double.NaN;
        if (EvadeClient.config != null) EvadeClient.config.save();
    }

    /**
     * Configs saved before Wall Mode existed load as Collide (the camera stops at walls), and before Front Distance
     * existed the front view used Distance, so it starts out the same.
     */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        if (!settings.has(wallMode.getName())) wallMode.set("Collide");
        if (!settings.has(frontDistance.getName()) && settings.has(distance.getName())) frontDistance.set(distance.get());
    }
}
