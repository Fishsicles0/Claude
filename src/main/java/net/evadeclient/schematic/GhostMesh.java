package net.evadeclient.schematic;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;

import java.util.Arrays;

/**
 * The ghost blocks of one verifier pass as a ready-made list of quads (corner positions relative to the placement's
 * origin, plus one colour per quad, already shaded per face). Built once per pass; drawing it is a straight copy into
 * the vertex buffer, with no per-block work left for the frame.
 */
public final class GhostMesh {
    public static final int DOWN = 1, UP = 2, NORTH = 4, SOUTH = 8, WEST = 16, EAST = 32;

    private float[] xyz;
    private int[] color;
    private int quads;
    /** How many blocks the mesh was built from. */
    int blocks;

    GhostMesh(int expectedQuads) {
        int n = Math.max(16, expectedQuads);
        xyz = new float[n * 12];
        color = new int[n];
    }

    public int quads() { return quads; }
    public int blocks() { return blocks; }

    /** The faces of a box picked by {@code faces}, each wound counter-clockwise seen from outside. */
    void box(float x1, float y1, float z1, float x2, float y2, float z2, int argb, int faces) {
        if ((faces & DOWN) != 0) quad(shade(argb, 0.55f), x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2);
        if ((faces & UP) != 0) quad(argb, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1);
        if ((faces & NORTH) != 0) quad(shade(argb, 0.8f), x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1);
        if ((faces & SOUTH) != 0) quad(shade(argb, 0.8f), x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2);
        if ((faces & WEST) != 0) quad(shade(argb, 0.65f), x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1);
        if ((faces & EAST) != 0) quad(shade(argb, 0.65f), x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2);
    }

    private void quad(int c, float... v) {
        if (quads == color.length) {
            xyz = Arrays.copyOf(xyz, xyz.length * 2);
            color = Arrays.copyOf(color, color.length * 2);
        }
        System.arraycopy(v, 0, xyz, quads * 12, 12);
        color[quads++] = c;
    }

    private static int shade(int argb, float f) {
        int r = (int) (((argb >> 16) & 0xFF) * f), g = (int) (((argb >> 8) & 0xFF) * f), b = (int) ((argb & 0xFF) * f);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /**
     * Writes every quad into {@code vc}, offset by {@code (ox, oy, oz)} (the origin minus the camera, so the floats stay
     * small and precise far from 0, 0).
     */
    public void emit(VertexConsumer vc, MatrixStack.Entry e, float ox, float oy, float oz) {
        float[] v = xyz;
        for (int q = 0; q < quads; q++) {
            int c = color[q], i = q * 12;
            vc.vertex(e, v[i] + ox, v[i + 1] + oy, v[i + 2] + oz).color(c);
            vc.vertex(e, v[i + 3] + ox, v[i + 4] + oy, v[i + 5] + oz).color(c);
            vc.vertex(e, v[i + 6] + ox, v[i + 7] + oy, v[i + 8] + oz).color(c);
            vc.vertex(e, v[i + 9] + ox, v[i + 10] + oy, v[i + 11] + oz).color(c);
        }
    }

    /** Lowest and highest placed layer (dy) any quad touches, as {min, max}, or null when empty (for tests). */
    public int[] layerSpan() {
        if (quads == 0) return null;
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int q = 0; q < quads; q++)
            for (int k = 0; k < 4; k++) {
                float y = xyz[q * 12 + k * 3 + 1];
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
            }
        return new int[]{(int) Math.floor(lo + 0.02f), (int) Math.ceil(hi - 0.02f) - 1};
    }
}
