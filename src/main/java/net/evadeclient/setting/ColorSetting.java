package net.evadeclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** An ARGB colour chosen from a palette of swatches. */
public class ColorSetting extends Setting<Integer> {
    public static final int[] PALETTE = {
            0xFFE11D2E, 0xFFFF4D5E, 0xFF9B111E, 0xFFFFFFFF, 0xFFB8B8C0, 0xFF5A5A66,
            0xFFFF8A1F, 0xFFFFD21F, 0xFF3BE37A, 0xFF29D3F0, 0xFF3D7BFF, 0xFFA95CFF, 0xFFFF5CC8};

    public ColorSetting(String name, int argb) { super(name, argb); }

    @Override public JsonElement toJson() { return new JsonPrimitive(value); }
    @Override public void fromJson(JsonElement json) { value = json.getAsInt(); }
    @Override public String display() { return String.format("#%06X", value & 0xFFFFFF); }
}
