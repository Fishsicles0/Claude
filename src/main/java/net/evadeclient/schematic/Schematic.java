package net.evadeclient.schematic;

import net.minecraft.block.BlockState;

/**
 * A loaded schematic: a box of {@code sizeX * sizeY * sizeZ} positions, each holding an index into {@link #palette}.
 * Index 0 is "nothing here" (outside every region of a multi-region file, or a structure void): the verifier
 * ignores it. Every other index is a block state the file asks for, air included (air means "keep this empty").
 *
 * <p>Positions are stored x fastest, then z, then y: {@code index = (y * sizeZ + z) * sizeX + x}.
 */
public final class Schematic {
    /** Largest box Evade loads or saves (positions, about 32 MB of memory at the cap). */
    public static final long MAX_VOLUME = 16L * 1024 * 1024;
    /** Largest side of the box, in blocks. */
    public static final int MAX_SIDE = 4096;
    /** Most different block states one schematic may use. */
    public static final int MAX_PALETTE = 32767;

    public final String name;
    /** "Litematic", "Sponge v2", "Sponge v3" or "Structure". */
    public final String format;
    public final String author;
    public final int sizeX, sizeY, sizeZ;
    /** Index 0 is always null ("nothing here"). */
    public final BlockState[] palette;
    public final short[] blocks;
    /** How many regions the file had (1 for single-box formats). */
    public final int regions;
    /** Block names the file used that this game doesn't know (they load as air), or 0. */
    public final int unknownStates;

    public Schematic(String name, String format, String author, int sizeX, int sizeY, int sizeZ, BlockState[] palette,
                     short[] blocks, int regions, int unknownStates) {
        this.name = name;
        this.format = format;
        this.author = author == null ? "" : author;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.palette = palette;
        this.blocks = blocks;
        this.regions = regions;
        this.unknownStates = unknownStates;
    }

    public long volume() { return (long) sizeX * sizeY * sizeZ; }

    public int index(int x, int y, int z) { return (y * sizeZ + z) * sizeX + x; }

    /** The palette index at a position inside the box (0 = nothing here). */
    public int paletteAt(int x, int y, int z) { return blocks[index(x, y, z)]; }

    /** The state at a position, or null for "nothing here" (and outside the box). */
    public BlockState stateAt(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) return null;
        return palette[blocks[index(x, y, z)]];
    }

    /** Positions that hold a block other than air. */
    public int solidBlocks() {
        boolean[] solid = new boolean[palette.length];
        for (int i = 1; i < palette.length; i++) solid[i] = palette[i] != null && !palette[i].isAir();
        int n = 0;
        for (short b : blocks) if (solid[b]) n++;
        return n;
    }

    /** Checks the size against the caps; throws {@link SchematicIO.BadFile} if it can't be loaded. */
    static void checkSize(int x, int y, int z) {
        if (x <= 0 || y <= 0 || z <= 0) throw new SchematicIO.BadFile("it has an empty size (" + x + " x " + y + " x " + z + ")");
        if (x > MAX_SIDE || y > MAX_SIDE || z > MAX_SIDE || (long) x * y * z > MAX_VOLUME)
            throw new SchematicIO.BadFile("it is too big (" + x + " x " + y + " x " + z + "; the most is "
                    + MAX_VOLUME / (1024 * 1024) + " million blocks)");
    }
}
