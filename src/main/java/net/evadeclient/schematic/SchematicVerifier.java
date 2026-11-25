package net.evadeclient.schematic;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.EmptyBlockView;

import java.util.Arrays;
import java.util.Set;

/**
 * Compares a placement with the blocks the client already has, a slice at a time, and builds the ghost mesh.
 *
 * <p>One pass walks the whole placed box ({@link #BUDGET} positions per tick, so a big schematic never costs a frame)
 * and sorts each position into one of the statuses below, using only chunks the client has loaded (anything else is
 * {@link #UNLOADED}). Positions within the ghost range and the visible layers become faces in a {@link GhostMesh}:
 * the nearest {@code maxBlocks} only, with faces between two neighbouring full blocks left out. When a pass finishes,
 * its counts and mesh replace the previous ones, so the picture never flickers while a pass runs.
 *
 * <p>Read-only: nothing here changes the world or sends anything to the server.
 */
public final class SchematicVerifier {
    public static final int NONE = 0, CORRECT = 1, MISSING = 2, WRONG = 3, WRONG_STATE = 4, EXTRA = 5, UNLOADED = 6;
    public static final int STATUS_COUNT = 7;
    /** Positions looked at per tick. */
    public static final int BUDGET = 32768;
    /** Most positions a pass keeps as mesh candidates before it picks the nearest. */
    private static final int MAX_CANDIDATES = 2_500_000;
    /** States whose value changes on its own in the world (leaf distance, redstone power, crop age...): ignored. */
    private static final Set<String> IGNORED_PROPERTIES = Set.of("distance", "persistent", "power", "moisture", "age", "stage");

    /** What to draw, fixed for a pass. */
    public record Options(boolean ghost, boolean verify, boolean faintCorrect, boolean showExtra, int layerMode, int layer,
                          int range, int maxBlocks, int ghostAlpha, int missingColor, int wrongColor, int stateColor,
                          int extraColor, int correctColor) {
        public static final int LAYERS_ALL = 0, LAYERS_SINGLE = 1, LAYERS_BELOW = 2, LAYERS_ABOVE = 3;

        /** Whether placed layer {@code dy} (0 = the bottom) is shown. */
        public boolean layerShown(int dy) {
            return switch (layerMode) {
                case LAYERS_SINGLE -> dy == layer;
                case LAYERS_BELOW -> dy <= layer;
                case LAYERS_ABOVE -> dy >= layer;
                default -> true;
            };
        }
    }

    /** The result of a finished pass. */
    public record Result(int[] counts, int[] placedByPalette, GhostMesh mesh, int revision) {
        public int count(int status) { return counts[status]; }
    }

    private Placement placement;
    private Options options;
    private int passRevision;
    private long cursor, volume;
    private int[] counts = new int[STATUS_COUNT];
    private int[] placedByPalette = new int[0];
    private long[] cand = new long[1024];
    private int[] candDist = new int[1024];
    private int candCount;
    private int cx, cy, cz;
    private boolean running;
    private Result last;
    private int passes;
    private final BlockPos.Mutable pos = new BlockPos.Mutable();
    private final int[] xz = new int[2];
    /** Shape box (6 floats, or null for a full cube) and ghost colour per placed palette index. */
    private float[][] shapes;
    private boolean[] full;
    private int[] colors;
    private BlockState[] shapesFor;

    /** The latest finished pass (null before the first). */
    public Result last() { return last; }
    public boolean running() { return running; }
    /** Finished passes since the verifier was made (for tests). */
    public int passes() { return passes; }
    /** The placement the last result belongs to. */
    public Placement placement() { return placement; }

    /** Forget everything (schematic unloaded). */
    public void clear() {
        placement = null;
        running = false;
        last = null;
    }

    /** Starts a new pass from the beginning (the previous result stays shown until it finishes). */
    public void start(Placement p, Options o, double px, double py, double pz) {
        if (p != placement) last = null;
        placement = p;
        options = o;
        passRevision = p.revision();
        cursor = 0;
        volume = p.schematic.volume();
        counts = new int[STATUS_COUNT];
        placedByPalette = new int[p.schematic.palette.length];
        candCount = 0;
        cx = (int) Math.floor(px);
        cy = (int) Math.floor(py);
        cz = (int) Math.floor(pz);
        running = true;
        if (shapesFor != p.placedPalette()) buildShapes(p);
    }

    /** Looks at up to {@code budget} more positions; returns true when the pass finished on this call. */
    public boolean step(ClientWorld world, int budget) {
        if (!running || placement == null || world == null) return false;
        Placement p = placement;
        Schematic s = p.schematic;
        BlockState[] pal = p.placedPalette();
        Options o = options;
        BlockPos origin = p.origin();
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        int sx = s.sizeX, sz = s.sizeZ;
        long end = Math.min(volume, cursor + budget);
        int range2 = o.range() * o.range();
        int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
        boolean loaded = false;
        int bottom = world.getBottomY(), top = world.getTopYInclusive();
        for (long i = cursor; i < end; i++) {
            int idx = (int) i;
            int pi = s.blocks[idx];
            if (pi == 0) continue;
            int lx = idx % sx, rest = idx / sx, lz = rest % sz, ly = rest / sz;
            p.placedXZ(lx, lz, xz);
            int dx = xz[0], dz = xz[1];
            int wx = ox + dx, wy = oy + ly, wz = oz + dz;
            BlockState want = pal[pi];
            boolean wantAir = want.isAir();
            int status;
            if (wy < bottom || wy > top) {
                status = UNLOADED;
            } else {
                int ccx = wx >> 4, ccz = wz >> 4;
                if (ccx != lastCx || ccz != lastCz) {
                    lastCx = ccx;
                    lastCz = ccz;
                    loaded = chunkLoaded(world, ccx, ccz);
                }
                if (!loaded) status = UNLOADED;
                else status = compare(want, world.getBlockState(pos.set(wx, wy, wz)));
            }
            if (wantAir && status != EXTRA) continue; // air that is air (or unloaded): nothing to count or draw
            counts[status]++;
            if (status == CORRECT || status == WRONG_STATE) placedByPalette[pi]++;
            if (!o.layerShown(ly) || !drawn(status, o)) continue;
            int ddx = wx - cx, ddy = wy - cy, ddz = wz - cz;
            int d2 = ddx * ddx + ddy * ddy + ddz * ddz;
            if (d2 > range2 || candCount >= MAX_CANDIDATES) continue;
            if (candCount == cand.length) {
                cand = Arrays.copyOf(cand, cand.length * 2);
                candDist = Arrays.copyOf(candDist, candDist.length * 2);
            }
            cand[candCount] = (long) dx | ((long) ly << 12) | ((long) dz << 24) | ((long) status << 36) | ((long) pi << 40);
            candDist[candCount++] = d2;
        }
        cursor = end;
        if (cursor < volume) return false;
        running = false;
        passes++;
        last = new Result(counts, placedByPalette, buildMesh(), passRevision);
        return true;
    }

    /**
     * Whether the client really has chunk {@code (cx, cz)}: the client hands out an empty placeholder chunk for
     * anything it doesn't have, which the world's own check counts as loaded.
     */
    public static boolean chunkLoaded(ClientWorld world, int cx, int cz) {
        var c = world.getChunkManager().getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, false);
        return c != null && !(c instanceof net.minecraft.world.chunk.EmptyChunk);
    }

    /** Runs a whole pass at once (for small schematics and tests). */
    public Result runNow(Placement p, Options o, ClientWorld world, double px, double py, double pz) {
        start(p, o, px, py, pz);
        while (running) step(world, Integer.MAX_VALUE);
        return last;
    }

    private static boolean drawn(int status, Options o) {
        return switch (status) {
            case MISSING -> o.ghost() || o.verify();
            case CORRECT -> o.verify() && o.faintCorrect();
            case WRONG, WRONG_STATE -> o.verify();
            case EXTRA -> o.verify() && o.showExtra();
            default -> false;
        };
    }

    /** How the world's block {@code have} compares with the wanted {@code want}. */
    public static int compare(BlockState want, BlockState have) {
        if (want == have) return want.isAir() ? NONE : CORRECT;
        if (want.isAir()) return have.isAir() || have.isReplaceable() ? NONE : EXTRA;
        if (have.isAir() || (have.isReplaceable() && have.getBlock() != want.getBlock())) return MISSING;
        if (have.getBlock() != want.getBlock()) return WRONG;
        for (Property<?> prop : want.getProperties()) {
            if (IGNORED_PROPERTIES.contains(prop.getName())) continue;
            if (!want.get(prop).equals(have.get(prop))) return WRONG_STATE;
        }
        return CORRECT;
    }

    // ------------------------------------------------------------------ mesh

    private void buildShapes(Placement p) {
        BlockState[] pal = p.placedPalette();
        shapes = new float[pal.length][];
        full = new boolean[pal.length];
        colors = new int[pal.length];
        for (int i = 1; i < pal.length; i++) {
            BlockState s = pal[i];
            if (s == null || s.isAir()) continue;
            float[] box = null;
            boolean isFull = true;
            try {
                VoxelShape shape = s.getOutlineShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
                if (shape.isEmpty()) {
                    box = new float[]{0.3f, 0.3f, 0.3f, 0.7f, 0.7f, 0.7f};
                    isFull = false;
                } else {
                    Box b = shape.getBoundingBox();
                    isFull = b.minX <= 0 && b.minY <= 0 && b.minZ <= 0 && b.maxX >= 1 && b.maxY >= 1 && b.maxZ >= 1;
                    if (!isFull) box = new float[]{(float) b.minX, (float) b.minY, (float) b.minZ, (float) b.maxX, (float) b.maxY, (float) b.maxZ};
                }
            } catch (RuntimeException e) {
                box = null;
                isFull = true;
            }
            shapes[i] = box;
            full[i] = isFull;
            int c;
            try {
                MapColor mc = s.getMapColor(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
                c = mc == null || mc == MapColor.CLEAR || mc.color == 0 ? 0xC8D2DC : mc.color;
            } catch (RuntimeException e) {
                c = 0xB8B8C0;
            }
            colors[i] = c & 0xFFFFFF;
        }
        shapesFor = pal;
    }

    private GhostMesh buildMesh() {
        Options o = options;
        int n = candCount;
        int[] order;
        if (n > o.maxBlocks()) {
            long[] keyed = new long[n];
            for (int i = 0; i < n; i++) keyed[i] = ((long) candDist[i] << 32) | i;
            Arrays.sort(keyed);
            n = o.maxBlocks();
            order = new int[n];
            for (int i = 0; i < n; i++) order[i] = (int) keyed[i];
        } else {
            order = new int[n];
            for (int i = 0; i < n; i++) order[i] = i;
        }
        // Full blocks drawn this pass, by placed position, so faces between two of them are left out.
        LongOpenHashSet solid = new LongOpenHashSet(Math.max(16, n * 2));
        for (int k : order) {
            long c = cand[k];
            int status = (int) ((c >>> 36) & 0xF), pi = (int) ((c >>> 40) & 0x7FFF);
            if (fullBox(status, pi)) solid.add(c & 0xFFFFFFFFFL);
        }
        GhostMesh mesh = new GhostMesh(n * 3);
        for (int k : order) {
            long c = cand[k];
            int dx = (int) (c & 0xFFF), dy = (int) ((c >>> 12) & 0xFFF), dz = (int) ((c >>> 24) & 0xFFF);
            int status = (int) ((c >>> 36) & 0xF), pi = (int) ((c >>> 40) & 0x7FFF);
            int color = color(status, pi, o);
            float x1, y1, z1, x2, y2, z2;
            boolean cullable = fullBox(status, pi);
            if (status == WRONG || status == WRONG_STATE || status == EXTRA) {
                // Over the block that's there: a hair bigger so it shows on top of it.
                x1 = dx - 0.01f; y1 = dy - 0.01f; z1 = dz - 0.01f; x2 = dx + 1.01f; y2 = dy + 1.01f; z2 = dz + 1.01f;
            } else {
                float[] b = shapes[pi];
                float g = status == CORRECT ? 0.005f : 0f;
                if (b == null) { x1 = dx - g; y1 = dy - g; z1 = dz - g; x2 = dx + 1 + g; y2 = dy + 1 + g; z2 = dz + 1 + g; }
                else { x1 = dx + b[0] - g; y1 = dy + b[1] - g; z1 = dz + b[2] - g; x2 = dx + b[3] + g; y2 = dy + b[4] + g; z2 = dz + b[5] + g; }
            }
            int faces = 0x3F;
            if (cullable) {
                if (solid.contains(key(dx, dy - 1, dz))) faces &= ~GhostMesh.DOWN;
                if (solid.contains(key(dx, dy + 1, dz))) faces &= ~GhostMesh.UP;
                if (solid.contains(key(dx, dy, dz - 1))) faces &= ~GhostMesh.NORTH;
                if (solid.contains(key(dx, dy, dz + 1))) faces &= ~GhostMesh.SOUTH;
                if (solid.contains(key(dx - 1, dy, dz))) faces &= ~GhostMesh.WEST;
                if (solid.contains(key(dx + 1, dy, dz))) faces &= ~GhostMesh.EAST;
            }
            mesh.box(x1, y1, z1, x2, y2, z2, color, faces);
        }
        mesh.blocks = n;
        return mesh;
    }

    private boolean fullBox(int status, int pi) {
        return status != CORRECT && (status != MISSING || full[pi]);
    }

    private static long key(int dx, int dy, int dz) {
        if (dx < 0 || dy < 0 || dz < 0 || dx > 0xFFF || dy > 0xFFF || dz > 0xFFF) return -1;
        return (long) dx | ((long) dy << 12) | ((long) dz << 24);
    }

    private int color(int status, int pi, Options o) {
        int a = Math.max(0, Math.min(255, o.ghostAlpha()));
        return switch (status) {
            case MISSING -> o.verify() ? withAlpha(o.missingColor(), a) : (a << 24) | colors[pi];
            case WRONG -> withAlpha(o.wrongColor(), Math.max(a, 0x70));
            case WRONG_STATE -> withAlpha(o.stateColor(), Math.max(a, 0x70));
            case EXTRA -> withAlpha(o.extraColor(), Math.max(a, 0x70));
            case CORRECT -> withAlpha(o.correctColor(), 0x30);
            default -> 0;
        };
    }

    private static int withAlpha(int rgb, int a) { return (a << 24) | (rgb & 0xFFFFFF); }
}
