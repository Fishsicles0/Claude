package net.evadeclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** An ARGB colour chosen from a palette of swatches. */
public class ColorSetting extends Setting<Integer> {
    public static final int[] PALETTE = {
            0xFFE11D2E, 0xFFFF4D5E, 0xFF9B111E, 0xFFFFFFFF, 0xFFB8B8C0, 0xFF5A5A66,
            0xFFFF8A1F, 0xFFFFD21F, 0xFF3BE37A, 0xFF29D3F0, 0xFF3D7BFF, 0xFFA95CFF, 0xFFFF5CC8};

    /** Hit Color's old default blue, which isn't a swatch; saved copies of it load as the palette blue. */
    private static final int LEGACY_BLUE = 0xFF3B82F6, PALETTE_BLUE = 0xFF3D7BFF;

    public ColorSetting(String name, int argb) { super(name, argb); }

    @Override public JsonElement toJson() { return new JsonPrimitive(value); }

    /** Reads an ARGB int, or a "#RRGGBB" / "#AARRGGBB" string from a hand-edited config. */
    @Override
    public void fromJson(JsonElement json) {
        int argb;
        JsonPrimitive p = json.getAsJsonPrimitive();
        if (p.isString()) {
            String hex = p.getAsString().trim();
            if (hex.startsWith("#")) hex = hex.substring(1);
            else if (hex.regionMatches(true, 0, "0x", 0, 2)) hex = hex.substring(2);
            if (hex.length() != 6 && hex.length() != 8) throw new IllegalArgumentException("Not a colour: " + p);
            argb = (int) Long.parseLong(hex, 16);
            if (hex.length() == 6) argb |= 0xFF000000;
        } else {
            argb = p.getAsInt();
        }
        // Only while the default isn't the old blue itself, so Reset still sticks if it is.
        if (argb == LEGACY_BLUE && getDefault() != LEGACY_BLUE) argb = PALETTE_BLUE;
        value = argb;
    }

    @Override public String display() { return String.format("#%06X", value & 0xFFFFFF); }
}
