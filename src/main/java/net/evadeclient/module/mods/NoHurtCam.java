package net.evadeclient.module.mods;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;

/**
 * Tones down the camera shake when you take damage: the world view and the first-person hand each keep their own
 * share of vanilla's tilt (none by default). The sideways tilt when you die is separate and stays unless turned off.
 */
public class NoHurtCam extends Module {
    public final NumberSetting worldShake = add(new NumberSetting("World Shake %", 0, 0, 100, 5)
            .describe("How much the camera still tilts when you're hurt (0 = none, 100 = vanilla)."));
    public final NumberSetting handShake = add(new NumberSetting("Hand Shake %", 0, 0, 100, 5)
            .describe("How much your hand and held item still shake when you're hurt (0 = none, 100 = vanilla)."));
    public final BooleanSetting deathTilt = add(new BooleanSetting("Death Tilt", true)
            .describe("Keep the camera's slow sideways tilt when you die."));

    /**
     * Share of the hurt tilt for the {@code tiltViewWhenHurt} call being made right now (1 = vanilla). Set by
     * GameRendererMixin around each call (the world's and the hand's) and back to 1 after it.
     */
    private static float scale = 1f;

    public NoHurtCam() {
        super("No Hurt Cam", "Removes the screen shake when hit.", Category.MODS);
        setEnabled(true);
    }

    /** The module when it is on, else null. */
    public static NoHurtCam active() {
        if (EvadeClient.modules == null) return null;
        NoHurtCam m = EvadeClient.modules.get(NoHurtCam.class);
        return m != null && m.isEnabled() ? m : null;
    }

    /** The share of the hurt tilt for the world view ({@code hand} false) or the first-person hand, 0..1. */
    public float shake(boolean hand) {
        return (float) ((hand ? handShake : worldShake).get() / 100.0);
    }

    /** Called by GameRendererMixin before and after each tilt call: the share to apply ({@code 1} restores vanilla). */
    public static void setScale(float s) {
        scale = Float.isFinite(s) ? Math.max(0f, Math.min(1f, s)) : 1f;
    }

    /** Vanilla's Damage Tilt strength scaled by the share of the call in progress. */
    public static double tiltStrength(double vanilla) {
        return vanilla * scale;
    }

    /** Whether vanilla's death tilt should run ({@code dead} is the player's real state). */
    public static boolean deathTilt(boolean dead) {
        if (!dead) return false;
        NoHurtCam m = active();
        return m == null || m.deathTilt.get();
    }
}
