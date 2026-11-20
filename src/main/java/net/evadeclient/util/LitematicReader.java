package net.evadeclient.util;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Litematica's .litematic schematic format (gzip NBT) without needing Litematica installed.
 * Each region stores a block-state palette and a packed long array; entries may span two longs.
 */
public final class LitematicReader {
    /** A loaded schematic: block states keyed by position relative to the schematic origin (air omitted). */
    public record Schematic(String name, Map<BlockPos, BlockState> blocks, BlockPos min, BlockPos max) {
        public int size() { return blocks.size(); }
    }

    private LitematicReader() {}

    public static Schematic read(Path file) throws IOException {
        NbtCompound root;
        try (InputStream in = Files.newInputStream(file)) {
            root = NbtIo.readCompressed(in, NbtSizeTracker.ofUnlimitedBytes());
        }
        return parse(file.getFileName().toString().replaceFirst("\\.litematic$", ""), root);
    }

    public static Schematic parse(String name, NbtCompound root) throws IOException {
        NbtCompound regions = root.getCompoundOrEmpty("Regions");
        if (regions.isEmpty()) throw new IOException("No regions in schematic (is this a .litematic file?)");
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (String key : regions.getKeys()) {
            NbtCompound r = regions.getCompoundOrEmpty(key);
            BlockPos pos = vec(r.getCompoundOrEmpty("Position"));
            BlockPos size = vec(r.getCompoundOrEmpty("Size"));
            int sx = Math.abs(size.getX()), sy = Math.abs(size.getY()), sz = Math.abs(size.getZ());
            if (sx == 0 || sy == 0 || sz == 0) continue;
            // Negative sizes extend towards negative coordinates from the region position.
            int ox = pos.getX() + (size.getX() < 0 ? size.getX() + 1 : 0);
            int oy = pos.getY() + (size.getY() < 0 ? size.getY() + 1 : 0);
            int oz = pos.getZ() + (size.getZ() < 0 ? size.getZ() + 1 : 0);

            NbtList paletteNbt = r.getListOrEmpty("BlockStatePalette");
            List<BlockState> palette = new ArrayList<>(paletteNbt.size());
            for (int i = 0; i < paletteNbt.size(); i++) {
                palette.add(NbtHelper.toBlockState(Registries.BLOCK, paletteNbt.getCompoundOrEmpty(i)));
            }
            if (palette.isEmpty()) continue;
            long[] data = r.getLongArray("BlockStates").orElse(new long[0]);
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            long mask = (1L << bits) - 1L;
            int layer = sx * sz;
            int volume = layer * sy;

            for (int i = 0; i < volume; i++) {
                long startBit = (long) i * bits;
                int startLong = (int) (startBit >> 6);
                int endLong = (int) (((long) (i + 1) * bits - 1) >> 6);
                int offset = (int) (startBit & 63);
                if (endLong >= data.length) break;
                long value = startLong == endLong
                        ? (data[startLong] >>> offset) & mask
                        : ((data[startLong] >>> offset) | (data[endLong] << (64 - offset))) & mask;
                if (value >= palette.size()) continue;
                BlockState state = palette.get((int) value);
                if (state.isAir()) continue;
                int y = i / layer, rem = i % layer, z = rem / sx, x = rem % sx;
                BlockPos p = new BlockPos(ox + x, oy + y, oz + z);
                blocks.put(p, state);
                minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ());
                maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY()); maxZ = Math.max(maxZ, p.getZ());
            }
        }
        if (blocks.isEmpty()) return new Schematic(name, Collections.emptyMap(), BlockPos.ORIGIN, BlockPos.ORIGIN);
        return new Schematic(name, blocks, new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
    }

    private static BlockPos vec(NbtCompound c) {
        return new BlockPos(c.getInt("x", 0), c.getInt("y", 0), c.getInt("z", 0));
    }

    /**
     * Writes a single-region .litematic (used by tests and ".printer save"). Same packing as Litematica:
     * index = (y * sizeZ + z) * sizeX + x, entries may span two longs.
     */
    public static NbtCompound write(String name, Map<BlockPos, BlockState> blocks, BlockPos size) {
        List<BlockState> palette = new ArrayList<>();
        palette.add(Blocks.AIR.getDefaultState());
        Map<BlockState, Integer> ids = new HashMap<>();
        ids.put(palette.getFirst(), 0);
        for (BlockState s : blocks.values()) ids.computeIfAbsent(s, k -> { palette.add(k); return palette.size() - 1; });
        int sx = size.getX(), sy = size.getY(), sz = size.getZ();
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        long volume = (long) sx * sy * sz;
        long[] data = new long[(int) ((volume * bits + 63) / 64)];
        for (var e : blocks.entrySet()) {
            BlockPos p = e.getKey();
            long i = ((long) p.getY() * sz + p.getZ()) * sx + p.getX();
            long v = ids.get(e.getValue());
            long startBit = i * bits;
            int startLong = (int) (startBit >> 6), endLong = (int) (((i + 1) * bits - 1) >> 6), offset = (int) (startBit & 63);
            data[startLong] |= v << offset;
            if (startLong != endLong) data[endLong] |= v >>> (64 - offset);
        }
        NbtList pal = new NbtList();
        for (BlockState s : palette) pal.add(NbtHelper.fromBlockState(s));
        NbtCompound region = new NbtCompound();
        region.put("Position", vecNbt(BlockPos.ORIGIN));
        region.put("Size", vecNbt(size));
        region.put("BlockStatePalette", pal);
        region.putLongArray("BlockStates", data);
        region.put("TileEntities", new NbtList());
        region.put("Entities", new NbtList());
        NbtCompound regions = new NbtCompound();
        regions.put(name, region);
        NbtCompound meta = new NbtCompound();
        meta.putString("Name", name);
        meta.putString("Author", "Evade");
        meta.put("EnclosingSize", vecNbt(size));
        meta.putInt("RegionCount", 1);
        meta.putInt("TotalBlocks", blocks.size());
        meta.putInt("TotalVolume", (int) volume);
        NbtCompound root = new NbtCompound();
        root.putInt("Version", 7);
        root.putInt("MinecraftDataVersion", net.minecraft.SharedConstants.getGameVersion().dataVersion().id());
        root.put("Metadata", meta);
        root.put("Regions", regions);
        return root;
    }

    private static NbtElement vecNbt(BlockPos p) {
        NbtCompound c = new NbtCompound();
        c.putInt("x", p.getX());
        c.putInt("y", p.getY());
        c.putInt("z", p.getZ());
        return c;
    }
}
