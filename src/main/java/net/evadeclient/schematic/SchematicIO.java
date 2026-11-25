package net.evadeclient.schematic;

import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.world.BlockView;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and writes schematic files, written from the public descriptions of each format:
 * <ul>
 *   <li>{@code .litematic}: gzipped NBT with one or more named regions under {@code Regions}. Each region has a
 *       {@code Position} and a {@code Size} (a negative size extends the other way), a {@code BlockStatePalette}
 *       list and {@code BlockStates}, a long array with each position's palette index packed in
 *       {@code max(2, ceil(log2(palette size)))} bits, values running on across long boundaries, positions x
 *       fastest, then z, then y, from the region's lowest corner.</li>
 *   <li>Sponge {@code .schem} version 2 (fields at the root) and version 3 (under {@code Schematic}, blocks under
 *       {@code Blocks}): {@code Width}/{@code Height}/{@code Length}, a {@code Palette} of block-state strings to
 *       ids, and the ids as unsigned LEB128 varints in a byte array, same order.</li>
 *   <li>Vanilla structure {@code .nbt}: {@code size}, a {@code palette} (or the first of {@code palettes}) and a
 *       {@code blocks} list of {@code pos} + {@code state}; positions not listed are left alone.</li>
 * </ul>
 * Everything is bounded: file size, decompressed NBT size, box size and palette size are capped, and a file that
 * breaks any rule is refused with a {@link BadFile} that says why. Nothing here touches the game world.
 */
public final class SchematicIO {
    /** Biggest file read from disk. */
    public static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    /** Biggest NBT tree a file may unpack to (vanilla's own accounting). */
    private static final long MAX_NBT_BYTES = 512L * 1024 * 1024;
    private static final int MAX_REGIONS = 256;

    /** A file Evade can't load, with a reason that reads after "Couldn't load it: ". */
    public static final class BadFile extends RuntimeException {
        public BadFile(String why) { super(why, null, false, false); }
    }

    private SchematicIO() {}

    /** Whether Evade can read files with this name (by extension). */
    public static boolean supported(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        return n.endsWith(".litematic") || n.endsWith(".schem") || n.endsWith(".schematic") || n.endsWith(".nbt");
    }

    /** The file name without its extension. */
    public static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    // ------------------------------------------------------------------ reading

    /** Loads a file. Throws {@link BadFile} (or IOException) with the reason it can't be loaded. */
    public static Schematic read(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) throw new BadFile("the file is larger than " + MAX_FILE_BYTES / (1024 * 1024) + " MB");
        byte[] bytes = Files.readAllBytes(file);
        return read(bytes, file.getFileName().toString());
    }

    /** Loads a file's bytes; {@code fileName} picks the format (by extension) and names the schematic. */
    public static Schematic read(byte[] bytes, String fileName) {
        NbtCompound root;
        try {
            root = parseNbt(bytes);
        } catch (IOException | RuntimeException e) {
            throw new BadFile("it isn't a readable NBT file (" + e.getClass().getSimpleName() + ")");
        }
        String name = baseName(fileName);
        String n = fileName.toLowerCase(Locale.ROOT);
        try {
            if (n.endsWith(".litematic")) return readLitematic(root, name);
            if (n.endsWith(".nbt")) return readStructure(root, name);
            if (n.endsWith(".schem") || n.endsWith(".schematic")) return readSponge(root, name);
            // Unknown extension: guess from the contents.
            if (root.contains("Regions")) return readLitematic(root, name);
            if (root.contains("blocks") && root.contains("size")) return readStructure(root, name);
            return readSponge(root, name);
        } catch (BadFile e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BadFile("its data is damaged (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Gzipped (the usual) or plain NBT, with vanilla's size accounting so a crafted file can't eat the memory. */
    static NbtCompound parseNbt(byte[] bytes) throws IOException {
        if (bytes.length < 3) throw new IOException("too short");
        NbtSizeTracker tracker = NbtSizeTracker.of(MAX_NBT_BYTES);
        boolean gzip = (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
        if (gzip) return NbtIo.readCompressed(new ByteArrayInputStream(bytes), tracker);
        return NbtIo.readCompound(new DataInputStream(new ByteArrayInputStream(bytes)), tracker);
    }

    /** Collects block states into one palette (index 0 stays "nothing here"). */
    private static final class PaletteBuilder {
        final Map<BlockState, Integer> ids = new LinkedHashMap<>();
        int unknown;

        int id(BlockState s) {
            if (s == null) return 0;
            Integer have = ids.get(s);
            if (have != null) return have;
            if (ids.size() + 1 > Schematic.MAX_PALETTE) throw new BadFile("it uses too many different blocks");
            int id = ids.size() + 1;
            ids.put(s, id);
            return id;
        }

        BlockState[] build() {
            BlockState[] out = new BlockState[ids.size() + 1];
            for (var e : ids.entrySet()) out[e.getValue()] = e.getKey();
            return out;
        }

        /** A palette entry: the named state, or air (counted as unknown) for a block this game doesn't have. */
        BlockState state(String name, Map<String, String> props) {
            BlockState s = parseState(name, props);
            if (s == null) { unknown++; return Blocks.AIR.getDefaultState(); }
            return s;
        }
    }

    private static Schematic readLitematic(NbtCompound root, String name) {
        NbtCompound regions = root.getCompoundOrEmpty("Regions");
        if (regions.isEmpty()) throw new BadFile("it has no regions");
        if (regions.getSize() > MAX_REGIONS) throw new BadFile("it has more than " + MAX_REGIONS + " regions");
        NbtCompound meta = root.getCompoundOrEmpty("Metadata");
        String title = meta.getString("Name", "");
        String author = meta.getString("Author", "");

        // Each region's lowest corner and size, then the box around all of them.
        record Region(NbtCompound tag, int x, int y, int z, int sx, int sy, int sz) {}
        List<Region> list = new java.util.ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        long maxX = Long.MIN_VALUE, maxY = Long.MIN_VALUE, maxZ = Long.MIN_VALUE;
        for (String key : regions.getKeys()) {
            NbtCompound r = regions.getCompoundOrEmpty(key);
            NbtCompound pos = r.getCompoundOrEmpty("Position"), size = r.getCompoundOrEmpty("Size");
            int px = pos.getInt("x", 0), py = pos.getInt("y", 0), pz = pos.getInt("z", 0);
            int sx = size.getInt("x", 0), sy = size.getInt("y", 0), sz = size.getInt("z", 0);
            if (sx == 0 || sy == 0 || sz == 0) continue;
            if (Math.abs((long) sx) > Schematic.MAX_SIDE || Math.abs((long) sy) > Schematic.MAX_SIDE || Math.abs((long) sz) > Schematic.MAX_SIDE)
                throw new BadFile("region \"" + key + "\" is too big");
            if (Math.abs((long) px) > 1_000_000 || Math.abs((long) py) > 1_000_000 || Math.abs((long) pz) > 1_000_000)
                throw new BadFile("region \"" + key + "\" is too far from the others");
            // A negative size runs from Position towards lower coordinates.
            int lx = sx < 0 ? px + sx + 1 : px, ly = sy < 0 ? py + sy + 1 : py, lz = sz < 0 ? pz + sz + 1 : pz;
            int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
            list.add(new Region(r, lx, ly, lz, ax, ay, az));
            minX = Math.min(minX, lx); minY = Math.min(minY, ly); minZ = Math.min(minZ, lz);
            maxX = Math.max(maxX, (long) lx + ax); maxY = Math.max(maxY, (long) ly + ay); maxZ = Math.max(maxZ, (long) lz + az);
        }
        if (list.isEmpty()) throw new BadFile("all of its regions are empty");
        long bx = maxX - minX, by = maxY - minY, bz = maxZ - minZ;
        if (bx > Schematic.MAX_SIDE || by > Schematic.MAX_SIDE || bz > Schematic.MAX_SIDE) throw new BadFile("its regions span too large an area");
        int sizeX = (int) bx, sizeY = (int) by, sizeZ = (int) bz;
        Schematic.checkSize(sizeX, sizeY, sizeZ);

        PaletteBuilder pb = new PaletteBuilder();
        short[] blocks = new short[sizeX * sizeY * sizeZ];
        for (Region r : list) {
            NbtList pal = r.tag().getListOrEmpty("BlockStatePalette");
            if (pal.isEmpty()) throw new BadFile("a region has no block palette");
            if (pal.size() > Schematic.MAX_PALETTE) throw new BadFile("a region uses too many different blocks");
            int[] map = new int[pal.size()];
            for (int i = 0; i < pal.size(); i++) {
                NbtCompound e = pal.getCompoundOrEmpty(i);
                map[i] = pb.id(pb.state(e.getString("Name", ""), props(e.getCompoundOrEmpty("Properties"))));
            }
            long[] packed = r.tag().getLongArray("BlockStates").orElse(new long[0]);
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(pal.size() - 1));
            long volume = (long) r.sx() * r.sy() * r.sz();
            if ((long) packed.length * 64 < volume * bits) throw new BadFile("a region's block data is cut short");
            long mask = (1L << bits) - 1;
            int ox = r.x() - minX, oy = r.y() - minY, oz = r.z() - minZ;
            int i = 0;
            for (int y = 0; y < r.sy(); y++) {
                for (int z = 0; z < r.sz(); z++) {
                    int row = ((oy + y) * sizeZ + oz + z) * sizeX + ox;
                    for (int x = 0; x < r.sx(); x++, i++) {
                        int v = (int) unpack(packed, i, bits, mask);
                        if (v < map.length) blocks[row + x] = (short) map[v];
                    }
                }
            }
        }
        return new Schematic(title.isBlank() ? name : title.strip(), "Litematic", author, sizeX, sizeY, sizeZ,
                pb.build(), blocks, list.size(), pb.unknown);
    }

    /** Value {@code i} of a tightly packed array ({@code bits} per value, running on across longs). */
    public static long unpack(long[] data, long i, int bits, long mask) {
        long bit = i * bits;
        int start = (int) (bit >>> 6), end = (int) ((bit + bits - 1) >>> 6);
        int off = (int) (bit & 63);
        if (start == end) return (data[start] >>> off) & mask;
        return ((data[start] >>> off) | (data[end] << (64 - off))) & mask;
    }

    /** Packs values tightly into longs (the inverse of {@link #unpack}). */
    public static long[] pack(int[] values, int bits) {
        long[] out = new long[(int) (((long) values.length * bits + 63) >>> 6)];
        long mask = (1L << bits) - 1;
        for (int i = 0; i < values.length; i++) {
            long v = values[i] & mask;
            long bit = (long) i * bits;
            int start = (int) (bit >>> 6), end = (int) ((bit + bits - 1) >>> 6);
            int off = (int) (bit & 63);
            out[start] |= v << off;
            if (start != end) out[end] |= v >>> (64 - off);
        }
        return out;
    }

    private static Schematic readSponge(NbtCompound root, String name) {
        // Version 3 keeps everything under "Schematic"; version 2 has it at the root.
        NbtCompound s = root.getCompound("Schematic").orElse(root);
        int version = s.getInt("Version", 2);
        int w = s.getShort("Width", (short) 0) & 0xFFFF, h = s.getShort("Height", (short) 0) & 0xFFFF, l = s.getShort("Length", (short) 0) & 0xFFFF;
        if (w == 0 && h == 0 && l == 0) throw new BadFile("it isn't a Sponge schematic (no Width, Height and Length)");
        Schematic.checkSize(w, h, l);
        NbtCompound blocksTag = version >= 3 ? s.getCompoundOrEmpty("Blocks") : s;
        NbtCompound palette = blocksTag.getCompoundOrEmpty("Palette");
        byte[] data = blocksTag.getByteArray(version >= 3 ? "Data" : "BlockData").orElse(null);
        if (palette.isEmpty() || data == null) {
            if (s.contains("Blocks") && version < 3 && s.get("Blocks") instanceof net.minecraft.nbt.NbtByteArray)
                throw new BadFile("it is an old (pre-1.13) schematic, which uses numeric block ids");
            throw new BadFile("it has no block palette or block data");
        }
        if (palette.getSize() > Schematic.MAX_PALETTE) throw new BadFile("it uses too many different blocks");
        PaletteBuilder pb = new PaletteBuilder();
        Map<Integer, Integer> map = new HashMap<>();
        for (String key : palette.getKeys()) {
            int id = palette.getInt(key, -1);
            if (id < 0) continue;
            String[] parsed = splitStateString(key);
            map.put(id, pb.id(pb.state(parsed[0], propsFromString(parsed[1]))));
        }
        int volume = w * h * l;
        short[] blocks = new short[volume];
        int pos = 0, i = 0;
        while (i < volume) {
            if (pos >= data.length) throw new BadFile("its block data is cut short");
            int value = 0, shift = 0;
            while (true) {
                if (pos >= data.length) throw new BadFile("its block data is cut short");
                int b = data[pos++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 28) throw new BadFile("its block data is damaged");
            }
            Integer id = map.get(value);
            blocks[i++] = (short) (id == null ? 0 : id);
        }
        // Sponge stores x fastest, then z, then y: the same order Evade keeps.
        return new Schematic(name, "Sponge v" + Math.min(3, Math.max(2, version)), metaAuthor(s), w, h, l, pb.build(), blocks, 1, pb.unknown);
    }

    private static String metaAuthor(NbtCompound s) {
        NbtCompound meta = s.getCompoundOrEmpty("Metadata");
        return meta.getString("Author", "");
    }

    private static Schematic readStructure(NbtCompound root, String name) {
        NbtList size = root.getListOrEmpty("size");
        if (size.size() != 3) throw new BadFile("it isn't a structure file (no size)");
        int sx = size.getInt(0, 0), sy = size.getInt(1, 0), sz = size.getInt(2, 0);
        Schematic.checkSize(sx, sy, sz);
        NbtList palette = root.getListOrEmpty("palette");
        NbtList palettes = root.getListOrEmpty("palettes");
        if (palette.isEmpty() && !palettes.isEmpty()) palette = palettes.getListOrEmpty(0);
        if (palette.isEmpty()) throw new BadFile("it has no block palette");
        if (palette.size() > Schematic.MAX_PALETTE) throw new BadFile("it uses too many different blocks");
        PaletteBuilder pb = new PaletteBuilder();
        int[] map = new int[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            NbtCompound e = palette.getCompoundOrEmpty(i);
            String n = e.getString("Name", "");
            // Structure voids mean "leave this alone": the same as a position not listed at all.
            map[i] = n.equals("minecraft:structure_void") ? 0 : pb.id(pb.state(n, props(e.getCompoundOrEmpty("Properties"))));
        }
        short[] blocks = new short[sx * sy * sz];
        NbtList list = root.getListOrEmpty("blocks");
        if (list.size() > sx * sy * sz) throw new BadFile("it lists more blocks than fit in its size");
        for (int i = 0; i < list.size(); i++) {
            NbtCompound b = list.getCompoundOrEmpty(i);
            NbtList pos = b.getListOrEmpty("pos");
            if (pos.size() != 3) continue;
            int x = pos.getInt(0, -1), y = pos.getInt(1, -1), z = pos.getInt(2, -1);
            int st = b.getInt("state", -1);
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz || st < 0 || st >= map.length) continue;
            blocks[(y * sz + z) * sx + x] = (short) map[st];
        }
        String author = root.getString("author", "");
        return new Schematic(name, "Structure", author, sx, sy, sz, pb.build(), blocks, 1, pb.unknown);
    }

    // ------------------------------------------------------------------ block states

    private static Map<String, String> props(NbtCompound tag) {
        if (tag.isEmpty()) return Map.of();
        Map<String, String> out = new HashMap<>();
        for (String k : tag.getKeys()) out.put(k, tag.getString(k, ""));
        return out;
    }

    /** "minecraft:stairs[facing=east]" → {"minecraft:stairs", "facing=east"} (second part "" without properties). */
    static String[] splitStateString(String s) {
        int open = s.indexOf('[');
        if (open < 0) return new String[]{s.strip(), ""};
        int close = s.lastIndexOf(']');
        return new String[]{s.substring(0, open).strip(), s.substring(open + 1, close > open ? close : s.length())};
    }

    private static Map<String, String> propsFromString(String s) {
        if (s.isBlank()) return Map.of();
        Map<String, String> out = new HashMap<>();
        for (String part : s.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) out.put(part.substring(0, eq).strip(), part.substring(eq + 1).strip());
        }
        return out;
    }

    /** The block state named {@code name} with {@code props} applied (unknown properties ignored), or null. */
    public static BlockState parseState(String name, Map<String, String> props) {
        Identifier id = Identifier.tryParse(name == null ? "" : name.strip().toLowerCase(Locale.ROOT));
        if (id == null) return null;
        Block block = Registries.BLOCK.getOptionalValue(id).orElse(null);
        if (block == null) return null;
        BlockState state = block.getDefaultState();
        for (var e : props.entrySet()) {
            Property<?> p = block.getStateManager().getProperty(e.getKey());
            if (p != null) state = with(state, p, e.getValue());
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> p, String value) {
        return p.parse(value).map(v -> s.with(p, v)).orElse(s);
    }

    /** "minecraft:oak_stairs[facing=east,half=bottom]". */
    public static String stateString(BlockState s) {
        StringBuilder b = new StringBuilder(Registries.BLOCK.getId(s.getBlock()).toString());
        if (!s.getEntries().isEmpty()) {
            b.append('[');
            boolean first = true;
            for (var e : s.getEntries().entrySet()) {
                if (!first) b.append(',');
                first = false;
                b.append(e.getKey().getName()).append('=').append(valueName(e.getKey(), e.getValue()));
            }
            b.append(']');
        }
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String valueName(Property<T> p, Comparable<?> v) {
        return p.name((T) v);
    }

    private static NbtCompound stateTag(BlockState s) {
        NbtCompound t = new NbtCompound();
        t.putString("Name", Registries.BLOCK.getId(s.getBlock()).toString());
        if (!s.getEntries().isEmpty()) {
            NbtCompound p = new NbtCompound();
            for (var e : s.getEntries().entrySet()) p.putString(e.getKey().getName(), valueName(e.getKey(), e.getValue()));
            t.put("Properties", p);
        }
        return t;
    }

    // ------------------------------------------------------------------ capturing and writing

    /**
     * Copies the blocks in the box between two corners out of {@code world} (what the client already has). Throws
     * {@link BadFile} for a box that's too big. The caller checks the chunks are loaded.
     */
    public static Schematic capture(BlockView world, String name, String author, int x1, int y1, int z1, int x2, int y2, int z2) {
        int minX = Math.min(x1, x2), minY = Math.min(y1, y2), minZ = Math.min(z1, z2);
        int sx = Math.abs(x2 - x1) + 1, sy = Math.abs(y2 - y1) + 1, sz = Math.abs(z2 - z1) + 1;
        Schematic.checkSize(sx, sy, sz);
        PaletteBuilder pb = new PaletteBuilder();
        pb.id(Blocks.AIR.getDefaultState()); // air first, as the formats expect
        short[] blocks = new short[sx * sy * sz];
        net.minecraft.util.math.BlockPos.Mutable p = new net.minecraft.util.math.BlockPos.Mutable();
        int i = 0;
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++)
                    blocks[i++] = (short) pb.id(world.getBlockState(p.set(minX + x, minY + y, minZ + z)));
        return new Schematic(name, "Litematic", author, sx, sy, sz, pb.build(), blocks, 1, 0);
    }

    /** Writes {@code s} as a one-region {@code .litematic} (positions with nothing in them are written as air). */
    public static void writeLitematic(Schematic s, Path file) throws IOException {
        write(litematicTag(s), file);
    }

    public static NbtCompound litematicTag(Schematic s) {
        // Local palette: air first, then the states in use.
        Map<BlockState, Integer> local = new LinkedHashMap<>();
        local.put(Blocks.AIR.getDefaultState(), 0);
        int[] remap = new int[s.palette.length];
        for (int i = 1; i < s.palette.length; i++) remap[i] = local.computeIfAbsent(s.palette[i], k -> local.size());
        int[] values = new int[s.blocks.length];
        for (int i = 0; i < values.length; i++) values[i] = remap[s.blocks[i]];
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(local.size() - 1));

        NbtCompound region = new NbtCompound();
        region.put("Position", vec(0, 0, 0));
        region.put("Size", vec(s.sizeX, s.sizeY, s.sizeZ));
        NbtList pal = new NbtList();
        for (BlockState st : local.keySet()) pal.add(stateTag(st));
        region.put("BlockStatePalette", pal);
        region.putLongArray("BlockStates", pack(values, bits));
        region.put("TileEntities", new NbtList());
        region.put("Entities", new NbtList());
        region.put("PendingBlockTicks", new NbtList());
        region.put("PendingFluidTicks", new NbtList());
        NbtCompound regions = new NbtCompound();
        regions.put(s.name.isBlank() ? "Main" : s.name, region);

        long now = System.currentTimeMillis();
        NbtCompound meta = new NbtCompound();
        meta.putString("Name", s.name);
        meta.putString("Author", s.author);
        meta.putString("Description", "");
        meta.putInt("RegionCount", 1);
        meta.putLong("TotalVolume", s.volume());
        meta.putLong("TotalBlocks", s.solidBlocks());
        meta.putLong("TimeCreated", now);
        meta.putLong("TimeModified", now);
        meta.put("EnclosingSize", vec(s.sizeX, s.sizeY, s.sizeZ));

        NbtCompound root = new NbtCompound();
        root.putInt("Version", 7);
        root.putInt("SubVersion", 1);
        root.putInt("MinecraftDataVersion", SharedConstants.WORLD_VERSION);
        root.put("Metadata", meta);
        root.put("Regions", regions);
        return root;
    }

    /** Writes {@code s} as a vanilla structure {@code .nbt} (positions with nothing in them are left out). */
    public static void writeStructure(Schematic s, Path file) throws IOException {
        write(structureTag(s), file);
    }

    public static NbtCompound structureTag(Schematic s) {
        NbtList palette = new NbtList();
        int[] remap = new int[s.palette.length];
        for (int i = 1; i < s.palette.length; i++) {
            remap[i] = palette.size();
            palette.add(stateTag(s.palette[i]));
        }
        NbtList blocks = new NbtList();
        int i = 0;
        for (int y = 0; y < s.sizeY; y++)
            for (int z = 0; z < s.sizeZ; z++)
                for (int x = 0; x < s.sizeX; x++, i++) {
                    int p = s.blocks[i];
                    if (p == 0) continue;
                    NbtCompound b = new NbtCompound();
                    b.put("pos", intList(x, y, z));
                    b.putInt("state", remap[p]);
                    blocks.add(b);
                }
        NbtCompound root = new NbtCompound();
        root.putInt("DataVersion", SharedConstants.WORLD_VERSION);
        root.put("size", intList(s.sizeX, s.sizeY, s.sizeZ));
        root.put("palette", palette);
        root.put("blocks", blocks);
        root.put("entities", new NbtList());
        return root;
    }

    /** A Sponge v3 tag (used by the tests to check the reader; Evade saves .litematic and .nbt). */
    public static NbtCompound spongeTag(Schematic s, int version) {
        Map<BlockState, Integer> ids = new LinkedHashMap<>();
        ids.put(Blocks.AIR.getDefaultState(), 0);
        java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
        for (short b : s.blocks) {
            BlockState st = s.palette[b] == null ? Blocks.AIR.getDefaultState() : s.palette[b];
            int v = ids.computeIfAbsent(st, k -> ids.size());
            while ((v & ~0x7F) != 0) { data.write((v & 0x7F) | 0x80); v >>>= 7; }
            data.write(v);
        }
        NbtCompound palette = new NbtCompound();
        for (var e : ids.entrySet()) palette.putInt(stateString(e.getKey()), e.getValue());
        NbtCompound body = new NbtCompound();
        body.putInt("Version", version);
        body.putInt("DataVersion", SharedConstants.WORLD_VERSION);
        body.putShort("Width", (short) s.sizeX);
        body.putShort("Height", (short) s.sizeY);
        body.putShort("Length", (short) s.sizeZ);
        body.putIntArray("Offset", new int[3]);
        if (version >= 3) {
            NbtCompound blocks = new NbtCompound();
            blocks.put("Palette", palette);
            blocks.putByteArray("Data", data.toByteArray());
            blocks.put("BlockEntities", new NbtList());
            body.put("Blocks", blocks);
            NbtCompound root = new NbtCompound();
            root.put("Schematic", body);
            return root;
        }
        body.putInt("PaletteMax", ids.size());
        body.put("Palette", palette);
        body.putByteArray("BlockData", data.toByteArray());
        body.put("BlockEntities", new NbtList());
        return body;
    }

    /** Gzipped NBT bytes of a tag (what every format stores). */
    public static byte[] gzip(NbtCompound tag) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, out);
        return out.toByteArray();
    }

    /** Writes through a temporary file, so a failed save never leaves half a file behind. */
    private static void write(NbtCompound tag, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            NbtIo.writeCompressed(tag, out);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static NbtCompound vec(int x, int y, int z) {
        NbtCompound c = new NbtCompound();
        c.putInt("x", x);
        c.putInt("y", y);
        c.putInt("z", z);
        return c;
    }

    private static NbtList intList(int... v) {
        NbtList l = new NbtList();
        for (int i : v) l.add(NbtInt.of(i));
        return l;
    }
}
