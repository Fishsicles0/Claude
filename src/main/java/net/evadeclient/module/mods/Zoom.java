package net.evadeclient.module.mods;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.NumberSetting;
import org.lwjgl.glfw.GLFW;

/** Hold a key to zoom. Scroll while zoomed to change the level. Smooth in and out, lower sensitivity while zoomed. */
public class Zoom extends Module {
    private final KeySetting key = add(new KeySetting("Zoom Key", GLFW.GLFW_KEY_C));
    private final NumberSetting level = add(new NumberSetting("Zoom Level", 4, 1.5, 50, 0.5));
    private final BooleanSetting scroll = add(new BooleanSetting("Scroll To Zoom", true));
    private final BooleanSetting smooth = add(new BooleanSetting("Smooth Zoom", true));
    private final BooleanSetting cinematic = add(new BooleanSetting("Cinematic Camera", false));

    private double current = 1, scrollMult = 1;
    private long lastFrame = net.evadeclient.gui.EvadeMenu.clock.getAsLong();
    private boolean savedSmooth, wasZooming;

    public Zoom() {
        super("Zoom", "Hold a key to zoom in.", Category.MODS);
        setEnabled(true);
    }

    public boolean zooming() { return isEnabled() && key.isDown(); }

    /** Called from the FOV mixin every frame. */
    public double fovMultiplier() {
        long now = net.evadeclient.gui.EvadeMenu.clock.getAsLong(); // ms; tests swap in a fixed-step clock
        double dt = Math.max(0, Math.min(0.1, (now - lastFrame) / 1000.0)); // never negative if the clock jumps back
        lastFrame = now;
        boolean z = zooming();
        if (z != wasZooming) {
            if (cinematic.get()) {
                if (z) { savedSmooth = mc.options.smoothCameraEnabled; mc.options.smoothCameraEnabled = true; }
                else mc.options.smoothCameraEnabled = savedSmooth;
            }
            if (!z) scrollMult = 1;
            wasZooming = z;
        }
        double target = z ? 1.0 / Math.max(1, level.get() * scrollMult) : 1;
        current = smooth.get() ? current + (target - current) * (1 - Math.exp(-14 * dt)) : target;
        if (!Double.isFinite(current)) current = target;
        return current;
    }

    /** Mouse sensitivity scale so aiming stays controllable while zoomed. */
    public double sensitivity() { return isEnabled() ? Math.min(1, current * 1.2) : 1; }

    /** @return true if the scroll was used for zoom. */
    public boolean onScroll(double amount) {
        if (!zooming() || !scroll.get()) return false;
        scrollMult = Math.max(0.25, Math.min(8, scrollMult * (amount > 0 ? 1.15 : 1 / 1.15)));
        return true;
    }
}
