package net.evadeclient.schematic;

import net.minecraft.block.BlockState;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;

/**
 * Where and how a schematic sits in the world: its lowest corner ({@link #origin}), a quarter-turn count around the
 * vertical axis and an optional mirror. The mirror is applied first, then the rotation (the order vanilla uses for
 * structures), and the result is shifted so the placed box always starts at the origin and runs towards +X, +Y, +Z.
 */
public final class Placement {
    public static final int MIRROR_NONE = 0, MIRROR_X = 1, MIRROR_Z = 2;

    public final Schematic schematic;
    /** The file it came from (name only, inside the schematics folder). */
    public final String file;
    private BlockPos origin;
    private int rotation;
    private int mirror;
    /** The palette with rotation and mirror applied, rebuilt when they change. */
    private BlockState[] placedPalette;
    /** Bumped on every change, so the verifier knows to look again. */
    private int revision;

    public Placement(Schematic schematic, String file, BlockPos origin) {
        this.schematic = schematic;
        this.file = file;
        this.origin = origin.toImmutable();
        rebuildPalette();
    }

    public BlockPos origin() { return origin; }
    /** Quarter turns clockwise (seen from above), 0..3. */
    public int rotation() { return rotation; }
    public int mirror() { return mirror; }
    public int revision() { return revision; }

    public void setOrigin(BlockPos p) {
        if (p.equals(origin)) return;
        origin = p.toImmutable();
        revision++;
    }

    public void move(int dx, int dy, int dz) { setOrigin(origin.add(dx, dy, dz)); }

    public void setRotation(int quarterTurns) {
        int r = Math.floorMod(quarterTurns, 4);
        if (r == rotation) return;
        rotation = r;
        rebuildPalette();
        revision++;
    }

    public void rotate(boolean clockwise) { setRotation(rotation + (clockwise ? 1 : -1)); }

    public void setMirror(int m) {
        int v = m < 0 || m > 2 ? MIRROR_NONE : m;
        if (v == mirror) return;
        mirror = v;
        rebuildPalette();
        revision++;
    }

    public static String mirrorName(int m) { return m == MIRROR_X ? "Front-Back" : m == MIRROR_Z ? "Left-Right" : "None"; }

    private void rebuildPalette() {
        BlockState[] src = schematic.palette;
        BlockState[] out = new BlockState[src.length];
        BlockMirror bm = mirror == MIRROR_X ? BlockMirror.FRONT_BACK : mirror == MIRROR_Z ? BlockMirror.LEFT_RIGHT : BlockMirror.NONE;
        BlockRotation br = switch (rotation) {
            case 1 -> BlockRotation.CLOCKWISE_90;
            case 2 -> BlockRotation.CLOCKWISE_180;
            case 3 -> BlockRotation.COUNTERCLOCKWISE_90;
            default -> BlockRotation.NONE;
        };
        for (int i = 1; i < src.length; i++) {
            BlockState s = src[i];
            if (s == null) continue;
            try {
                out[i] = s.mirror(bm).rotate(br);
            } catch (RuntimeException e) {
                out[i] = s; // a block whose rotation code fails keeps its state
            }
        }
        placedPalette = out;
    }

    /** The palette as placed (rotated and mirrored). Index 0 is null. */
    public BlockState[] placedPalette() { return placedPalette; }

    /** Width along X of the placed box. */
    public int sizeX() { return (rotation & 1) == 0 ? schematic.sizeX : schematic.sizeZ; }
    public int sizeY() { return schematic.sizeY; }
    /** Depth along Z of the placed box. */
    public int sizeZ() { return (rotation & 1) == 0 ? schematic.sizeZ : schematic.sizeX; }

    /**
     * The offset from the origin of the schematic's own position {@code (x, z)}, as {@code {dx, dz}}: mirrored,
     * then turned, then shifted into the 0.. range.
     */
    public void placedXZ(int x, int z, int[] out) {
        int sx = schematic.sizeX, sz = schematic.sizeZ;
        if (mirror == MIRROR_X) x = sx - 1 - x;
        else if (mirror == MIRROR_Z) z = sz - 1 - z;
        int px, pz;
        switch (rotation) {
            case 1 -> { px = sz - 1 - z; pz = x; }       // clockwise: (x, z) -> (-z, x)
            case 2 -> { px = sx - 1 - x; pz = sz - 1 - z; }
            case 3 -> { px = z; pz = sx - 1 - x; }       // counter-clockwise: (x, z) -> (z, -x)
            default -> { px = x; pz = z; }
        }
        out[0] = px;
        out[1] = pz;
    }

    /** The schematic's own position for a placed offset {@code (dx, dz)} from the origin ({@link #placedXZ} undone). */
    public void localXZ(int dx, int dz, int[] out) {
        int sx = schematic.sizeX, sz = schematic.sizeZ;
        int x, z;
        switch (rotation) {
            case 1 -> { x = dz; z = sz - 1 - dx; }
            case 2 -> { x = sx - 1 - dx; z = sz - 1 - dz; }
            case 3 -> { x = sx - 1 - dz; z = dx; }
            default -> { x = dx; z = dz; }
        }
        if (mirror == MIRROR_X) x = sx - 1 - x;
        else if (mirror == MIRROR_Z) z = sz - 1 - z;
        out[0] = x;
        out[1] = z;
    }

    /** The placed state at a world position, or null where the schematic has nothing (or outside it). */
    public BlockState stateAtWorld(int wx, int wy, int wz) {
        int dx = wx - origin.getX(), dy = wy - origin.getY(), dz = wz - origin.getZ();
        if (dx < 0 || dy < 0 || dz < 0 || dx >= sizeX() || dy >= sizeY() || dz >= sizeZ()) return null;
        int[] l = new int[2];
        localXZ(dx, dz, l);
        return placedPalette[schematic.paletteAt(l[0], dy, l[1])];
    }

    /** Whether a world position is inside the placed box. */
    public boolean contains(int wx, int wy, int wz) {
        int dx = wx - origin.getX(), dy = wy - origin.getY(), dz = wz - origin.getZ();
        return dx >= 0 && dy >= 0 && dz >= 0 && dx < sizeX() && dy < sizeY() && dz < sizeZ();
    }
}
