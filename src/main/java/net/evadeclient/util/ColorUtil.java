package net.evadeclient.util;

import java.awt.Color;

public final class ColorUtil {
    public static final int ACCENT = 0xFF8A5CF6;      // Evade violet
    public static final int PANEL_BG = 0xE0141418;
    public static final int HEADER_BG = 0xFF1E1B2E;
    public static final int HOVER = 0x30FFFFFF;
    public static final int TEXT = 0xFFE6E6F0;
    public static final int TEXT_DIM = 0xFF8E8EA0;

    private ColorUtil() {}

    /** Rainbow colour offset by {@code offsetMs} so lines in a list form a wave. */
    public static int rainbow(long offsetMs, float saturation, float brightness) {
        float hue = ((System.currentTimeMillis() + offsetMs) % 4000L) / 4000f;
        return 0xFF000000 | (Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF);
    }
}
