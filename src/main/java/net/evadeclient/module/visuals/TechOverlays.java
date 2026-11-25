package net.evadeclient.module.visuals;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StringSetting;
import net.evadeclient.util.SlimeChunks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.structure.Structure;

import java.util.Map;

/**
 * World overlays for technical players, each switchable and drawn only from what your own game already has:
 * slime chunks (from a seed you really have, see {@link SlimeChunks}), the random-tick range around you, region-file
 * borders, a block grid at your feet, the effect area of a beacon or conduit you look at, and in singleplayer only the
 * bounding boxes of generated structures (read from your own integrated server, never from a multiplayer server).
 * A server can switch the whole module off through Evade's server rules.
 */
public class TechOverlays extends Module {
    private final StringSetting seed = add(new StringSetting("Seed", "", "e.g. your own server's seed")
            .describe("For slime chunks on a server: a world seed you know, like your own server's. Never guessed. Singleplayer uses its own seed."));
    private final BooleanSetting slimeChunks = add(new BooleanSetting("Slime Chunks", true)
            .describe("Tints slime chunks around you in the Overworld. Needs your singleplayer world or a typed Seed."));
    private final NumberSetting slimeRadius = add(new NumberSetting("Slime Radius", 4, 1, 8, 1)
            .describe("How many chunks around you are checked for slime chunks."));
    private final ColorSetting slimeColor = add(new ColorSetting("Slime Color", 0xFF5BD75B)
            .describe("Colour of the slime chunk outlines and tint."));
    private final BooleanSetting randomTicks = add(new BooleanSetting("Random Tick Range", false)
            .describe("The 128-block circle around you where crops grow and leaves decay, and the server's simulation distance."));
    private final ColorSetting tickColor = add(new ColorSetting("Random Tick Color", 0xFF3DA5FF)
            .describe("Colour of the random tick circle and simulation square."));
    private final BooleanSetting regionBorders = add(new BooleanSetting("Region Borders", false)
            .describe("Where the 512 by 512 block region files meet, near you."));
    private final ColorSetting regionColor = add(new ColorSetting("Region Color", 0xFFB45CFF)
            .describe("Colour of the region borders."));
    private final BooleanSetting blockGrid = add(new BooleanSetting("Block Grid", false)
            .describe("A grid of block edges on the layer you stand on, for lining builds up."));
    private final NumberSetting gridRadius = add(new NumberSetting("Grid Radius", 8, 2, 16, 1)
            .describe("How many blocks the grid reaches from you."));
    private final ColorSetting gridColor = add(new ColorSetting("Grid Color", 0x80FFFFFF)
            .describe("Colour of the block grid."));
    private final BooleanSetting beaconRange = add(new BooleanSetting("Beacon Range", true)
            .describe("Look at a beacon or conduit to see the area its effects reach (shown for a few seconds)."));
    private final ColorSetting beaconColor = add(new ColorSetting("Beacon Color", 0xFFFFD21F)
            .describe("Colour of the beacon and conduit range."));
    private final BooleanSetting structures = add(new BooleanSetting("Structure Boxes", false)
            .describe("Singleplayer only: outlines of villages, temples, monuments and other generated structures near you."));
    private final NumberSetting structureRadius = add(new NumberSetting("Structure Radius", 6, 2, 12, 1)
            .describe("How many chunks around you are searched for structures."));
    private final BooleanSetting structurePieces = add(new BooleanSetting("Structure Pieces", true)
            .describe("Also outline each piece of a structure, not just its whole box."));

    /** Random ticks reach chunks whose centre is within this many blocks of a player. */
    public static final int RANDOM_TICK_RADIUS = 128;
    private static final int CIRCLE_SEGMENTS = 96;
    private static final int MAX_STRUCTURE_BOXES = 1500;
    private static final int BEACON_LINGER_TICKS = 60;
    private static final Block[] CONDUIT_FRAME = {Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.SEA_LANTERN, Blocks.DARK_PRISMARINE};

    // slime chunk cache: the chunks the last check found, as pairs of chunk x, z
    private int[] slime = new int[0];
    private int slimeCount;
    private long slimeKeyCenter = Long.MIN_VALUE;
    private Long slimeKeySeed;
    private int slimeKeyRadius;

    // beacon / conduit being shown
    private BlockPos rangePos;
    private boolean rangeConduit;
    private int rangeRadius;
    private int rangeTicks;

    // structure boxes from the integrated server: minX, minY, minZ, maxX, maxY, maxZ, colour, piece flag
    private volatile int[] boxes = new int[0];
    private int structureTicks;
    private volatile boolean structureBusy;

    private int lines;

    public TechOverlays() {
        super("Tech Overlays", "Slime chunks, random tick range, region borders, block grid, beacon range and structure boxes.",
                Category.VISUALS);
    }

    /** The typed seed text (Seed setting), stripped. */
    public String typedSeedText() {
        String s = seed.get();
        return s == null ? "" : s.strip();
    }

    @Override
    protected void onDisable() {
        slimeCount = 0;
        slimeKeyCenter = Long.MIN_VALUE;
        rangePos = null;
        boxes = new int[0];
    }

    @Override
    public void onTick() {
        if (mc.world == null || mc.player == null) return;
        if (slimeChunks.get()) updateSlime();
        if (beaconRange.get()) updateRange(mc.world);
        else rangePos = null;
        if (structures.get()) {
            if (++structureTicks >= 40) { structureTicks = 0; requestStructures(); }
        } else if (boxes.length > 0) {
            boxes = new int[0];
        }
    }

    // ------------------------------------------------------------------ slime chunks

    private void updateSlime() {
        Long s = SlimeChunks.seed();
        ChunkPos c = mc.player.getChunkPos();
        int r = slimeRadius.getInt();
        if (s == null) { slimeCount = 0; slimeKeySeed = null; return; }
        if (c.toLong() == slimeKeyCenter && s.equals(slimeKeySeed) && r == slimeKeyRadius) return;
        slimeKeyCenter = c.toLong();
        slimeKeySeed = s;
        slimeKeyRadius = r;
        int side = 2 * r + 1;
        if (slime.length < side * side * 2) slime = new int[side * side * 2];
        slimeCount = 0;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            int cx = c.x + dx, cz = c.z + dz;
            if (!SlimeChunks.isSlimeChunk(s, cx, cz)) continue;
            slime[slimeCount * 2] = cx;
            slime[slimeCount * 2 + 1] = cz;
            slimeCount++;
        }
    }

    /** How many slime chunks the last check found around you (for the tests). */
    public int slimeChunksForTest() { return slimeChunks.get() ? slimeCount : 0; }

    // ------------------------------------------------------------------ beacon / conduit

    private void updateRange(ClientWorld w) {
        HitResult hit = mc.crosshairTarget;
        if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = bh.getBlockPos();
            BlockState st = w.getBlockState(pos);
            if (st.isOf(Blocks.BEACON)) {
                int level = beaconLevel(w, pos);
                rangePos = pos.toImmutable();
                rangeConduit = false;
                rangeRadius = level == 0 ? 0 : level * 10 + 10;
                rangeTicks = BEACON_LINGER_TICKS;
                return;
            }
            if (st.isOf(Blocks.CONDUIT)) {
                int frame = conduitFrame(w, pos);
                rangePos = pos.toImmutable();
                rangeConduit = true;
                rangeRadius = frame >= 16 ? frame / 7 * 16 : 0;
                rangeTicks = BEACON_LINGER_TICKS;
                return;
            }
        }
        if (rangeTicks > 0 && --rangeTicks == 0) rangePos = null;
    }

    /** The beacon's pyramid level (0-4), counted from the blocks under it the way the game does. */
    public static int beaconLevel(World w, BlockPos pos) {
        int level = 0;
        for (int layer = 1; layer <= 4; layer++) {
            int y = pos.getY() - layer;
            if (y < w.getBottomY()) break;
            for (int x = pos.getX() - layer; x <= pos.getX() + layer; x++)
                for (int z = pos.getZ() - layer; z <= pos.getZ() + layer; z++)
                    if (!w.getBlockState(new BlockPos(x, y, z)).isIn(BlockTags.BEACON_BASE_BLOCKS)) return level;
            level = layer;
        }
        return level;
    }

    /** How many prismarine-type frame blocks sit in a conduit's frame positions (16 or more activate it). */
    public static int conduitFrame(World w, BlockPos pos) {
        int count = 0;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = -2; x <= 2; x++) for (int y = -2; y <= 2; y++) for (int z = -2; z <= 2; z++) {
            int ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);
            if (ax <= 1 && ay <= 1 && az <= 1) continue;
            boolean ring = (x == 0 && (ay == 2 || az == 2)) || (y == 0 && (ax == 2 || az == 2)) || (z == 0 && (ax == 2 || ay == 2));
            if (!ring) continue;
            BlockState st = w.getBlockState(m.set(pos.getX() + x, pos.getY() + y, pos.getZ() + z));
            for (Block b : CONDUIT_FRAME) if (st.isOf(b)) { count++; break; }
        }
        return count;
    }

    /** The effect radius being shown (0 for an inactive one, -1 for none), for the tests. */
    public int rangeRadiusForTest() { return rangePos == null ? -1 : rangeRadius; }

    // ------------------------------------------------------------------ structures (singleplayer)

    /** Whether structure boxes can be shown here: only with your own integrated server. */
    public boolean structuresAvailable() {
        return mc.getServer() != null && mc.isIntegratedServerRunning();
    }

    /** Asks the integrated server, on its own thread, for the structures around you. */
    public void requestStructures() {
        IntegratedServer server = mc.getServer();
        if (!structuresAvailable() || server == null || mc.world == null || mc.player == null) { boxes = new int[0]; return; }
        if (structureBusy) return;
        structureBusy = true;
        var key = mc.world.getRegistryKey();
        ChunkPos center = mc.player.getChunkPos();
        int r = structureRadius.getInt();
        boolean pieces = structurePieces.get();
        server.execute(() -> {
            try {
                ServerWorld sw = server.getWorld(key);
                boxes = sw == null ? new int[0] : collectStructures(sw, center, r, pieces);
            } catch (RuntimeException e) {
                boxes = new int[0];
            } finally {
                structureBusy = false;
            }
        });
    }

    /** Server thread: the boxes of every structure that touches a chunk within {@code r} of {@code center}. */
    private static int[] collectStructures(ServerWorld sw, ChunkPos center, int r, boolean pieces) {
        var registry = sw.getRegistryManager().getOrThrow(RegistryKeys.STRUCTURE);
        LongSet seen = new LongOpenHashSet();
        int[] out = new int[64 * 8];
        int n = 0;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (!sw.getChunkManager().isChunkLoaded(center.x + dx, center.z + dz)) continue;
            Chunk chunk = sw.getChunk(center.x + dx, center.z + dz, ChunkStatus.STRUCTURE_REFERENCES, false);
            if (chunk == null) continue;
            for (Map.Entry<Structure, it.unimi.dsi.fastutil.longs.LongSet> e : chunk.getStructureReferences().entrySet()) {
                Structure structure = e.getKey();
                for (long ref : e.getValue()) {
                    Identifier id = registry.getId(structure);
                    long key = ref * 31 + (id == null ? 0 : id.hashCode());
                    if (!seen.add(key)) continue;
                    Chunk origin = sw.getChunk(ChunkPos.getPackedX(ref), ChunkPos.getPackedZ(ref), ChunkStatus.STRUCTURE_STARTS, false);
                    if (origin == null) continue;
                    StructureStart start = origin.getStructureStart(structure);
                    if (start == null || !start.hasChildren()) continue;
                    int color = colorFor(id);
                    if (n + 1 > MAX_STRUCTURE_BOXES) break;
                    out = put(out, n++, start.getBoundingBox(), color, 0);
                    if (pieces) for (StructurePiece piece : start.getChildren()) {
                        if (n + 1 > MAX_STRUCTURE_BOXES) break;
                        out = put(out, n++, piece.getBoundingBox(), color, 1);
                    }
                }
            }
        }
        return java.util.Arrays.copyOf(out, n * 8);
    }

    private static int[] put(int[] out, int i, BlockBox b, int color, int piece) {
        if (out.length < (i + 1) * 8) out = java.util.Arrays.copyOf(out, out.length * 2);
        int o = i * 8;
        out[o] = b.getMinX(); out[o + 1] = b.getMinY(); out[o + 2] = b.getMinZ();
        out[o + 3] = b.getMaxX() + 1; out[o + 4] = b.getMaxY() + 1; out[o + 5] = b.getMaxZ() + 1;
        out[o + 6] = color; out[o + 7] = piece;
        return out;
    }

    /** A steady colour per structure type, so all villages look alike. */
    private static int colorFor(Identifier id) {
        float hue = id == null ? 0f : (Math.floorMod(id.toString().hashCode(), 360)) / 360f;
        return 0xFF000000 | (java.awt.Color.HSBtoRGB(hue, 0.7f, 1f) & 0xFFFFFF);
    }

    /** How many structure boxes (whole structures and pieces) are being shown (for the tests). */
    public int structureBoxesForTest() { return boxes.length / 8; }

    // ------------------------------------------------------------------ drawing

    /** How many lines the last frame drew (for the tests). */
    public int linesForTest() { return lines; }

    @Override
    public void onRender(RenderContext ctx) {
        lines = 0;
        if (mc.player == null || mc.world == null) return;
        double py = mc.player.getY();
        int feet = (int) Math.floor(py);
        if (slimeChunks.get() && slimeCount > 0) drawSlime(ctx, feet);
        if (randomTicks.get()) drawRandomTicks(ctx, feet);
        if (regionBorders.get()) drawRegions(ctx, py);
        if (blockGrid.get()) drawGrid(ctx, feet);
        if (rangePos != null && beaconRange.get()) drawRange(ctx);
        int[] b = boxes;
        if (structures.get() && b.length > 0) drawStructures(ctx, b);
        ctx.flush();
    }

    private void line(RenderContext ctx, double ax, double ay, double az, double bx, double by, double bz, int color, float width) {
        ctx.line(ax, ay, az, bx, by, bz, color, width);
        lines++;
    }

    private void drawSlime(RenderContext ctx, int feet) {
        int c = slimeColor.get();
        int fill = (0x30 << 24) | (c & 0xFFFFFF);
        double y = feet + 0.02;
        for (int i = 0; i < slimeCount; i++) {
            double x0 = slime[i * 2] * 16.0, z0 = slime[i * 2 + 1] * 16.0, x1 = x0 + 16, z1 = z0 + 16;
            ctx.flatQuad(x0, z0, x1, z1, y, fill);
            line(ctx, x0, y, z0, x1, y, z0, c, 2f);
            line(ctx, x1, y, z0, x1, y, z1, c, 2f);
            line(ctx, x1, y, z1, x0, y, z1, c, 2f);
            line(ctx, x0, y, z1, x0, y, z0, c, 2f);
        }
    }

    private void drawRandomTicks(RenderContext ctx, int feet) {
        int c = tickColor.get();
        double cx = mc.player.getX(), cz = mc.player.getZ(), y = feet + 0.05;
        double prevX = cx + RANDOM_TICK_RADIUS, prevZ = cz;
        for (int i = 1; i <= CIRCLE_SEGMENTS; i++) {
            double a = i * Math.PI * 2 / CIRCLE_SEGMENTS;
            double x = cx + Math.cos(a) * RANDOM_TICK_RADIUS, z = cz + Math.sin(a) * RANDOM_TICK_RADIUS;
            line(ctx, prevX, y, prevZ, x, y, z, c, 2f);
            prevX = x;
            prevZ = z;
        }
        // The server's simulation distance, in chunks around yours: outside it nothing ticks at all.
        int sim = mc.world.getSimulationDistance();
        if (sim > 0) {
            ChunkPos p = mc.player.getChunkPos();
            double x0 = (p.x - sim) * 16.0, z0 = (p.z - sim) * 16.0, x1 = (p.x + sim + 1) * 16.0, z1 = (p.z + sim + 1) * 16.0;
            int faint = (0x90 << 24) | (c & 0xFFFFFF);
            line(ctx, x0, y, z0, x1, y, z0, faint, 1.5f);
            line(ctx, x1, y, z0, x1, y, z1, faint, 1.5f);
            line(ctx, x1, y, z1, x0, y, z1, faint, 1.5f);
            line(ctx, x0, y, z1, x0, y, z0, faint, 1.5f);
        }
    }

    private void drawRegions(RenderContext ctx, double py) {
        int c = regionColor.get();
        double px = mc.player.getX(), pz = mc.player.getZ();
        int reach = 128, half = 96, h = 24;
        int bottom = mc.world.getBottomY(), top = mc.world.getTopYInclusive() + 1;
        double y0 = Math.max(bottom, Math.floor(py) - h), y1 = Math.min(top, Math.floor(py) + h);
        int rx = Math.floorDiv((int) Math.floor(px), 512), rz = Math.floorDiv((int) Math.floor(pz), 512);
        for (int k = 0; k <= 1; k++) {
            double x = (rx + k) * 512.0;
            if (Math.abs(px - x) <= reach) fence(ctx, true, x, pz, half, y0, y1, c);
            double z = (rz + k) * 512.0;
            if (Math.abs(pz - z) <= reach) fence(ctx, false, z, px, half, y0, y1, c);
        }
    }

    /** A wall of lines along a region edge: posts every 16 blocks and rails every 8 blocks of height. */
    private void fence(RenderContext ctx, boolean alongZ, double at, double around, int half, double y0, double y1, int c) {
        double a0 = Math.floor((around - half) / 16) * 16, a1 = a0 + half * 2 + 16;
        int faint = (0x80 << 24) | (c & 0xFFFFFF);
        for (double a = a0; a <= a1; a += 16) {
            if (alongZ) line(ctx, at, y0, a, at, y1, a, c, 2f);
            else line(ctx, a, y0, at, a, y1, at, c, 2f);
        }
        for (double y = Math.ceil(y0 / 8) * 8; y <= y1; y += 8) {
            if (alongZ) line(ctx, at, y, a0, at, y, a1, faint, 1.5f);
            else line(ctx, a0, y, at, a1, y, at, faint, 1.5f);
        }
    }

    private void drawGrid(RenderContext ctx, int feet) {
        int c = gridColor.get(), r = gridRadius.getInt();
        int bx = (int) Math.floor(mc.player.getX()), bz = (int) Math.floor(mc.player.getZ());
        double y = feet + 0.01;
        for (int i = -r; i <= r + 1; i++) {
            ctx.lineDepth(bx + i, y, bz - r, bx + i, y, bz + r + 1, c, 1f);
            ctx.lineDepth(bx - r, y, bz + i, bx + r + 1, y, bz + i, c, 1f);
            lines += 2;
        }
    }

    private void drawRange(RenderContext ctx) {
        int c = beaconColor.get();
        BlockPos p = rangePos;
        int r = rangeRadius;
        if (r <= 0) {
            // Not active: just ring the block so you see it was read.
            int dim = (0x90 << 24) | 0xFF4D5E;
            ctx.boxOutline(p.getX() - 0.02, p.getY() - 0.02, p.getZ() - 0.02, p.getX() + 1.02, p.getY() + 1.02, p.getZ() + 1.02, dim, 2f);
            lines += 12;
            return;
        }
        if (!rangeConduit) {
            // The game's beacon box: the block grown by the range, stretched up through the world's height.
            int top = mc.world.getTopYInclusive() + 1;
            double y0 = Math.max(mc.world.getBottomY(), p.getY() - r), y1 = Math.min(top, p.getY() + 1 + r + mc.world.getHeight());
            ctx.boxOutline(p.getX() - r, y0, p.getZ() - r, p.getX() + 1 + r, y1, p.getZ() + 1 + r, c, 2f);
            lines += 12;
            // A ring at your height so the edge is easy to find on the ground.
            double y = Math.floor(mc.player.getY()) + 0.05;
            int faint = (0x60 << 24) | (c & 0xFFFFFF);
            ctx.flatQuad(p.getX() - r, p.getZ() - r, p.getX() + 1 + r, p.getZ() + 1 + r, y, (0x18 << 24) | (c & 0xFFFFFF));
            line(ctx, p.getX() - r, y, p.getZ() - r, p.getX() + 1 + r, y, p.getZ() - r, faint, 1.5f);
            line(ctx, p.getX() + 1 + r, y, p.getZ() - r, p.getX() + 1 + r, y, p.getZ() + 1 + r, faint, 1.5f);
            line(ctx, p.getX() + 1 + r, y, p.getZ() + 1 + r, p.getX() - r, y, p.getZ() + 1 + r, faint, 1.5f);
            line(ctx, p.getX() - r, y, p.getZ() + 1 + r, p.getX() - r, y, p.getZ() - r, faint, 1.5f);
        } else {
            // A conduit reaches players in water or rain within a sphere of the range: three great circles.
            double cx = p.getX() + 0.5, cy = p.getY() + 0.5, cz = p.getZ() + 0.5;
            for (int plane = 0; plane < 3; plane++) {
                double px = 0, py = 0, pz = 0;
                for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
                    double a = i * Math.PI * 2 / CIRCLE_SEGMENTS, s = Math.sin(a) * r, k = Math.cos(a) * r;
                    double x = cx + (plane == 2 ? 0 : k), y = cy + (plane == 0 ? 0 : s), z = cz + (plane == 0 ? s : plane == 2 ? k : 0);
                    if (i > 0) line(ctx, px, py, pz, x, y, z, c, 1.5f);
                    px = x; py = y; pz = z;
                }
            }
        }
    }

    private void drawStructures(RenderContext ctx, int[] b) {
        for (int o = 0; o + 7 < b.length; o += 8) {
            boolean piece = b[o + 7] == 1;
            int c = piece ? (0x70 << 24) | (b[o + 6] & 0xFFFFFF) : b[o + 6];
            ctx.boxOutline(b[o], b[o + 1], b[o + 2], b[o + 3], b[o + 4], b[o + 5], c, piece ? 1f : 2.5f);
            lines += 12;
        }
    }
}
