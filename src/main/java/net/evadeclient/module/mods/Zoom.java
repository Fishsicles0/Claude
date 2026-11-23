package net.evadeclient.module.mods;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import org.lwjgl.glfw.GLFW;

/**
 * Hold (or tap, in Toggle mode) a key to zoom. Scroll while zoomed to change the level. Smooth in and out, with
 * lower mouse sensitivity while zoomed. Only the world zooms: the first-person hand keeps its normal size.
 */
public class Zoom extends Module {
    private final KeySetting key = add(new KeySetting("Zoom Key", GLFW.GLFW_KEY_C, true));
    private final ModeSetting mode = add(new ModeSetting("Mode", "Hold", "Hold", "Toggle")
            .describe("Hold the key to zoom, or tap it to zoom in and tap again to zoom out."));
    private final NumberSetting level = add(new NumberSetting("Zoom Level", 4, 1.5, 50, 0.5));
    private final NumberSetting zoomSensitivity = add(new NumberSetting("Zoom Sensitivity %", 100, 10, 100, 5)
            .describe("Mouse sensitivity while zoomed, on top of the automatic slowdown."));
    private final BooleanSetting scroll = add(new BooleanSetting("Scroll To Zoom", true));
    private final BooleanSetting smooth = add(new BooleanSetting("Smooth Zoom", true));
    private final BooleanSetting cinematic = add(new BooleanSetting("Cinematic Camera", false));

    /** FOV multiplier (1 = no zoom) and how far zoomed in we are (0..1), both eased the same way. */
    private double current = 1, amount = 0, scrollMult = 1;
    private double lastFrame = net.evadeclient.gui.EvadeMenu.clock.getAsDouble();
    /** Whether we switched Cinematic Camera on for this zoom, and what it was before. */
    private boolean cinematicApplied, savedSmooth;
    private boolean wasZooming, toggled, keyWasDown;

    public Zoom() {
        super("Zoom", "Hold a key to zoom in.", Category.MODS);
        setEnabled(true);
    }

    public boolean zooming() {
        if (!isEnabled()) return false;
        return mode.is("Toggle") ? toggled : key.isDown();
    }

    /**
     * Advances the zoom once per frame (GameRendererMixin calls it before the world is drawn, enabled or not, so a
     * zoom that was cut off by disabling the module is always cleaned up).
     */
    public void frame() {
        double now = net.evadeclient.gui.EvadeMenu.clock.getAsDouble(); // ms, sub-ms precise; tests swap in a fixed-step clock
        double dt = Math.max(0, Math.min(0.1, (now - lastFrame) / 1000.0)); // never negative if the clock jumps back
        lastFrame = now;
        if (!isEnabled()) {
            if (wasZooming || toggled || cinematicApplied || current != 1) reset();
            return;
        }
        boolean down = key.isDown();
        if (mode.is("Toggle")) {
            if (down && !keyWasDown) toggled = !toggled;
        } else {
            toggled = false;
        }
        keyWasDown = down;

        boolean z = zooming();
        if (z != wasZooming) {
            if (z) {
                if (cinematic.get() && !cinematicApplied) {
                    savedSmooth = mc.options.smoothCameraEnabled;
                    mc.options.smoothCameraEnabled = true;
                    cinematicApplied = true;
                }
            } else {
                restoreCinematic();
                scrollMult = 1;
            }
            wasZooming = z;
        }
        double target = z ? 1.0 / Math.max(1, level.get() * scrollMult) : 1;
        double k = smooth.get() ? 1 - Math.exp(-14 * dt) : 1;
        current += (target - current) * k;
        amount += ((z ? 1 : 0) - amount) * k;
        if (!Double.isFinite(current)) current = target;
        if (!Double.isFinite(amount)) amount = z ? 1 : 0;
        // Snap the last sliver so "not zoomed" is exactly 1 (sensitivity and the FOV are then untouched).
        if (!z && Math.abs(current - 1) < 1e-4) {
            current = 1;
            amount = 0;
        }
    }

    /** FOV multiplier for this frame (1 = no zoom). */
    public double fovMultiplier() { return current; }

    /** Mouse sensitivity scale so aiming stays controllable while zoomed (1 when not zoomed). */
    public double sensitivity() {
        if (!isEnabled()) return 1;
        double s = Math.min(1, current * 1.2);
        double extra = zoomSensitivity.get() / 100.0;
        return s * (1 + (extra - 1) * Math.max(0, Math.min(1, amount)));
    }

    /** @return true if the scroll was used for zoom. */
    public boolean onScroll(double amount) {
        if (mc.currentScreen != null || !zooming() || !scroll.get()) return false;
        double steps = mc.options.getDiscreteMouseScroll().getValue() ? Math.signum(amount) : amount;
        steps = Math.max(-10, Math.min(10, steps));
        scrollMult = Math.max(0.25, Math.min(8, scrollMult * Math.pow(1.15, steps)));
        return true;
    }

    @Override
    protected void onDisable() {
        reset();
    }

    /** Back to no zoom at once, handing Cinematic Camera back if we turned it on. */
    private void reset() {
        restoreCinematic();
        wasZooming = false;
        toggled = false;
        keyWasDown = false;
        scrollMult = 1;
        current = 1;
        amount = 0;
    }

    /** Undo our Cinematic Camera switch, unless the player turned it off themselves in the meantime. */
    private void restoreCinematic() {
        if (!cinematicApplied) return;
        cinematicApplied = false;
        if (mc.options != null && mc.options.smoothCameraEnabled) mc.options.smoothCameraEnabled = savedSmooth;
    }
}
