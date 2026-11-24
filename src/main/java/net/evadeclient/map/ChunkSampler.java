package net.evadeclient.map;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.MapColor;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.FoliageColors;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Turns one loaded chunk into 256 map columns, in two steps.
 *
 * <p>{@link #sample} runs on the main thread and only reads the chunk the client already has: the surface block of
 * each column (the vanilla WORLD_SURFACE height map, looking through glass the way a vanilla map does), its map
 * colour, how deep the water is (up to 31 blocks, for the depth tint) and the biome there. Nothing below the
 * surface is kept, and nothing about entities.
 *
 * <p>{@link #colour} is pure arithmetic on that snapshot and runs on the map worker thread: biome grass, foliage
 * and water colours (from the biome's own values, no world access) and a shade code per column:
 * {@code 0x80 | depth} for water, {@code 64 + (height - height of the column to the north)} for land. The shade
 * code goes in the top byte of the packed value, so 0 always means "never seen" and the views can choose their own
 * shading ({@link MapShading}) without re-reading the world.
 */
final class ChunkSampler {
    private ChunkSampler() {}

    static final byte K_PLAIN = 0, K_VOID = 1, K_WATER = 2, K_GRASS = 3, K_FOLIAGE = 4, K_SPRUCE = 5, K_BIRCH = 6, K_MANGROVE = 7;
    /** Colour of an explored column with nothing on it (the End's void). */
    static final int VOID_RGB = 0x0B0B0E;
    private static final int NO_HEIGHT = Integer.MIN_VALUE;

    /** What {@link #sample} read from one chunk (handed to the worker; never touched again on the main thread). */
    static final class Snapshot {
        final int cx, cz;
        final byte[] kind = new byte[256];
        final int[] rgb = new int[256];
        final int[] height = new int[256];
        final byte[] depth = new byte[256];
        final int[] north = new int[16];
        @SuppressWarnings("unchecked")
        final RegistryEntry<Biome>[] biome = new RegistryEntry[256];

        Snapshot(int cx, int cz) { this.cx = cx; this.cz = cz; }
    }

    private static final BlockPos.Mutable POS = new BlockPos.Mutable();

    /** Main thread: reads the surface of {@code chunk}. */
    static Snapshot sample(ClientWorld world, ClientChunkManager cm, WorldChunk chunk, int cx, int cz) {
        Snapshot s = new Snapshot(cx, cz);
        int bottom = world.getBottomY();
        int x0 = cx << 4, z0 = cz << 4;
        BlockPos.Mutable pos = POS;
        WorldChunk north = cm.getChunk(cx, cz - 1, ChunkStatus.FULL, false);
        for (int lx = 0; lx < 16; lx++) s.north[lx] = north != null ? surface(world, north, x0 + lx, (cz << 4) - 1, lx, 15, bottom) : NO_HEIGHT;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int i = lz * 16 + lx;
                int x = x0 + lx, z = z0 + lz;
                int y = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, lx, lz);
                if (y < bottom) { s.kind[i] = K_VOID; s.height[i] = NO_HEIGHT; continue; }
                pos.set(x, y, z);
                BlockState state = chunk.getBlockState(pos);
                MapColor color = state.getMapColor(world, pos);
                // See-through blocks (glass, barriers) show what's under them, as on a vanilla map.
                for (int steps = 0; color == MapColor.CLEAR && y > bottom && steps < 16; steps++) {
                    pos.setY(--y);
                    state = chunk.getBlockState(pos);
                    color = state.getMapColor(world, pos);
                }
                s.height[i] = y;
                FluidState fluid = state.getFluidState();
                if (!fluid.isEmpty() && fluid.isIn(FluidTags.WATER)) {
                    int depth = 1;
                    while (depth < 31 && y - depth > bottom) {
                        pos.setY(y - depth);
                        if (!chunk.getBlockState(pos).getFluidState().isIn(FluidTags.WATER)) break;
                        depth++;
                    }
                    s.kind[i] = K_WATER;
                    s.depth[i] = (byte) depth;
                    s.biome[i] = chunk.getBiomeForNoiseGen(x >> 2, y >> 2, z >> 2);
                    continue;
                }
                if (color == MapColor.CLEAR) { s.kind[i] = K_VOID; continue; }
                byte kind = tintKind(state.getBlock());
                s.kind[i] = kind;
                s.rgb[i] = color.color;
                if (kind == K_GRASS || kind == K_FOLIAGE) s.biome[i] = chunk.getBiomeForNoiseGen(x >> 2, y >> 2, z >> 2);
            }
        }
        return s;
    }

    /** Height of the surface the way {@link #sample} finds it (for the row north of a chunk). */
    private static int surface(ClientWorld world, WorldChunk c, int x, int z, int lx, int lz, int bottom) {
        int y = c.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, lx, lz);
        if (y < bottom) return NO_HEIGHT;
        BlockPos.Mutable pos = POS;
        pos.set(x, y, z);
        for (int steps = 0; y > bottom && steps < 16 && c.getBlockState(pos).getMapColor(world, pos) == MapColor.CLEAR; steps++) pos.setY(--y);
        return y;
    }

    private static byte tintKind(Block b) {
        if (b == Blocks.GRASS_BLOCK || b == Blocks.SHORT_GRASS || b == Blocks.TALL_GRASS || b == Blocks.FERN
                || b == Blocks.LARGE_FERN || b == Blocks.BUSH || b == Blocks.SUGAR_CANE) return K_GRASS;
        if (b == Blocks.OAK_LEAVES || b == Blocks.JUNGLE_LEAVES || b == Blocks.ACACIA_LEAVES || b == Blocks.DARK_OAK_LEAVES
                || b == Blocks.VINE) return K_FOLIAGE;
        if (b == Blocks.SPRUCE_LEAVES) return K_SPRUCE;
        if (b == Blocks.BIRCH_LEAVES) return K_BIRCH;
        if (b == Blocks.MANGROVE_LEAVES) return K_MANGROVE;
        return K_PLAIN;
    }

    /** Worker thread: the packed columns for a snapshot (see the class comment). */
    static int[] colour(Snapshot s) {
        int[] out = new int[256];
        int x0 = s.cx << 4, z0 = s.cz << 4;
        for (int i = 0; i < 256; i++) {
            int rgb, code;
            switch (s.kind[i]) {
                case K_VOID -> { out[i] = (64 << 24) | VOID_RGB; continue; }
                case K_WATER -> {
                    out[i] = ((0x80 | Math.max(1, s.depth[i])) << 24) | (biomeWater(s.biome[i]) & 0xFFFFFF);
                    continue;
                }
                case K_GRASS -> rgb = scale(grass(s.biome[i], x0 + (i & 15), z0 + (i >> 4)), 0.86f);
                case K_FOLIAGE -> rgb = scale(s.biome[i] == null ? FoliageColors.DEFAULT : s.biome[i].value().getFoliageColor(), 0.78f);
                case K_SPRUCE -> rgb = scale(FoliageColors.SPRUCE, 0.78f);
                case K_BIRCH -> rgb = scale(FoliageColors.BIRCH, 0.78f);
                case K_MANGROVE -> rgb = scale(FoliageColors.MANGROVE, 0.78f);
                default -> rgb = s.rgb[i];
            }
            int y = s.height[i];
            int northY = (i < 16) ? s.north[i] : s.height[i - 16];
            int dy = northY == NO_HEIGHT || y == NO_HEIGHT ? 0 : Math.max(-31, Math.min(31, y - northY));
            code = 64 + dy;
            out[i] = (code << 24) | (rgb & 0xFFFFFF);
        }
        return out;
    }

    private static int grass(RegistryEntry<Biome> b, int x, int z) {
        return b == null ? 0x91BD59 : b.value().getGrassColorAt(x, z);
    }

    private static int biomeWater(RegistryEntry<Biome> b) {
        return b == null ? 0x3F76E4 : b.value().getWaterColor();
    }

    static int scale(int rgb, float f) {
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * f));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((rgb & 0xFF) * f));
        return (r << 16) | (g << 8) | b;
    }
}
