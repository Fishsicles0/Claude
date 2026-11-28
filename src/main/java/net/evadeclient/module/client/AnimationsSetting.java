package net.evadeclient.module.client;

import com.google.gson.JsonElement;
import net.evadeclient.setting.ModeSetting;

/**
 * Client Settings' Animations: Full, Reduced or Off. Reduced keeps functional motion (progress, loading) but turns
 * every transition into a short crossfade and drops parallax, tilt, stagger and ambient motion; Off snaps.
 *
 * <p>Older configs saved this setting as an on/off switch: {@code true} loads as Full and {@code false} as Off.
 */
public class AnimationsSetting extends ModeSetting {
    public static final String FULL = "Full", REDUCED = "Reduced", OFF = "Off";

    public AnimationsSetting(String name) {
        super(name, FULL, FULL, REDUCED, OFF);
    }

    /** Whether anything animates (Full or Reduced): what the old on/off switch meant. */
    public boolean on() { return !is(OFF); }

    /** Full motion: parallax, tilt, stagger and ambient motion allowed. */
    public boolean full() { return is(FULL); }

    @Override
    public void fromJson(JsonElement json) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isBoolean()) {
            value = json.getAsBoolean() ? FULL : OFF;
            return;
        }
        super.fromJson(json);
    }
}
