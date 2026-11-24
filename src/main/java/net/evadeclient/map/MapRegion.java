package net.evadeclient.map;

/**
 * 32 x 32 chunks (512 x 512 columns) of explored map, in memory. Each chunk's 256 columns are stored together
 * ({@code px[chunk * 256 + lz * 16 + lx]}, chunk = {@code (cz & 31) * 32 + (cx & 31)}) as packed values from
 * {@link ChunkSampler}: 0 for a column never seen, else a shade code in the top byte and the colour below it.
 * Main thread only; the worker only ever gets copies.
 */
final class MapRegion {
    static final int CHUNKS = 32, COLUMNS = CHUNKS * CHUNKS * 256;

    final int rx, rz;
    final int[] px = new int[COLUMNS];
    /** Per chunk: {@link MapStore}'s revision of its last change, 0 while it holds nothing. */
    final int[] rev = new int[CHUNKS * CHUNKS];
    /** Changed since last written to disk. */
    boolean dirty;
    /** A read from disk is on its way; until it lands, chunks mapped this session win over the file's. */
    boolean loading;
    /** {@link MapStore}'s tick when this region was last read or written (for the memory cap). */
    long lastUsed;

    MapRegion(int rx, int rz) {
        this.rx = rx;
        this.rz = rz;
    }

    static int chunkIndex(int cx, int cz) { return (cz & 31) * CHUNKS + (cx & 31); }

    boolean explored(int chunk) { return rev[chunk] != 0; }

    int exploredChunks() {
        int n = 0;
        for (int r : rev) if (r != 0) n++;
        return n;
    }
}
