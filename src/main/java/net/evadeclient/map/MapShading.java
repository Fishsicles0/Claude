package net.evadeclient.map;

/**
 * Turns packed map columns ({@link ChunkSampler}) into colours for a view. Land is shaded by how much higher or
 * lower it is than the column to its north (light from the north-west, like a vanilla map): Off, Classic (the three
 * vanilla map steps) or Smooth (a step per block of slope). Water gets darker with depth when Water Depth is on.
 * One lookup table per style, so shading a 16 x 16 chunk is 256 multiplies.
 */
public final class MapShading {
    public static final int OFF = 0, CLASSIC = 1, SMOOTH = 2;
    public static final String[] MODES = {"Off", "Classic", "Smooth"};

    /** Brightness per shade code (0..255), per [mode][water depth on]. */
    private static final float[][][] LUT = new float[3][2][256];

    static {
        for (int mode = 0; mode < 3; mode++) {
            for (int wd = 0; wd < 2; wd++) {
                float[] t = LUT[mode][wd];
                for (int code = 1; code < 256; code++) {
                    if ((code & 0x80) != 0) {
                        int d = code & 0x7F;
                        t[code] = wd == 0 ? 0.92f : d <= 1 ? 1.0f : d <= 3 ? 0.9f : d <= 6 ? 0.8f : d <= 10 ? 0.7f : d <= 16 ? 0.62f : 0.55f;
                    } else {
                        int dy = code - 64;
                        t[code] = switch (mode) {
                            case OFF -> 0.94f;
                            case CLASSIC -> dy > 0 ? 1.0f : dy == 0 ? 0.86f : 0.71f;
                            default -> Math.max(0.64f, Math.min(1.06f, 0.88f + 0.045f * dy));
                        };
                    }
                }
            }
        }
    }

    private MapShading() {}

    public static int mode(String name) {
        for (int i = 0; i < MODES.length; i++) if (MODES[i].equalsIgnoreCase(name)) return i;
        return SMOOTH;
    }

    /** ARGB for a packed column, or {@code unknown} for one never seen. */
    public static int argb(int packed, int mode, boolean waterDepth, int unknown) {
        int code = packed >>> 24;
        if (code == 0) return unknown;
        float f = LUT[mode < 0 || mode > 2 ? SMOOTH : mode][waterDepth ? 1 : 0][code];
        int r = Math.min(255, (int) (((packed >> 16) & 0xFF) * f));
        int g = Math.min(255, (int) (((packed >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((packed & 0xFF) * f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** {@code argb} darkened (for chunk grid lines). */
    public static int darken(int argb, float f) {
        return (argb & 0xFF000000) | ChunkSampler.scale(argb & 0xFFFFFF, f);
    }
}
