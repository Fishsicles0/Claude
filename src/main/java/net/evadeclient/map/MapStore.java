package net.evadeclient.map;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.hud.MinimapHud;
import net.evadeclient.module.visuals.WorldMap;
import net.evadeclient.module.visuals.Waypoints;
import net.evadeclient.util.ServerRules;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The explored-terrain cache shared by the Minimap and the World Map: the surface of every chunk this client has
 * actually loaded, kept per server (or singleplayer world folder) and dimension, in 512 x 512 regions.
 *
 * <p><b>Only what you've seen.</b> A chunk is mapped when the client loads it (Fabric's chunk-load event) and again
 * when a block near its surface changes ({@code MapBlockChangeMixin}); nothing else is ever read, so unexplored
 * ground stays blank. Dimensions with a ceiling (the Nether) are never mapped: their "surface" is the bedrock roof,
 * and anything below it would be a cave view. A server that switches the Minimap off with {@code evade:rules}
 * switches this off too (see {@link ServerRules}).
 *
 * <p><b>Cheap.</b> Work is incremental and budgeted: a queue of changed chunks is sampled on the main thread for at
 * most {@link #BUDGET_NANOS} per tick (a few dozen chunks), colours are worked out on one background thread, regions
 * are written to disk in batches every 30 seconds (and when you leave), and at most Memory Limit regions stay in
 * memory (least recently used ones are saved and dropped). Files: {@code config/evade/maps/<server>/<dimension>/
 * r.<x>.<z>.evmap}, gzip-compressed, only explored chunks stored.
 */
public final class MapStore {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final int FILE_MAGIC = 0x45564D31; // "EVM1"
    private static final long BUDGET_NANOS = 1_000_000L;
    private static final int MAX_CHUNKS_PER_TICK = 48;
    private static final int SAVE_EVERY_TICKS = 20 * 30;

    private static boolean registered;
    private static ExecutorService worker;

    // ---- the current world (main thread)
    private static ClientWorld world;
    private static String worldId = "", dimId = "";
    private static Path dir;
    /** Whether this world is mapped at all (false under a ceiling, or while the server blocks it). */
    private static boolean mapping;
    private static boolean persist;
    /** Bumps whenever the world changes, so late worker results for the old one are dropped. */
    private static int generation;
    private static int revision;
    private static long tick, lastTick = Long.MIN_VALUE, lastSave;

    private static final Long2ObjectOpenHashMap<MapRegion> regions = new Long2ObjectOpenHashMap<>();
    /** Regions that exist on disk for this world (so a read never creates an empty region for nothing). */
    private static final LongOpenHashSet onDisk = new LongOpenHashSet();
    /** Chunks waiting to be (re)mapped, oldest first. */
    private static final LongLinkedOpenHashSet pending = new LongLinkedOpenHashSet();
    /** Work handed back from the worker, run on the main thread. */
    private static final ConcurrentLinkedQueue<Runnable> results = new ConcurrentLinkedQueue<>();
    private static MapRegion lastRegion;

    // ---- stats (for the tests and the report)
    private static long statNanos, statTicks, statChunks;

    private MapStore() {}

    // ================================================================== lifecycle

    private static void register() {
        if (registered) return;
        registered = true;
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Evade Map");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        ClientChunkEvents.CHUNK_LOAD.register((w, chunk) -> onChunkLoad(w, chunk.getPos()));
        ClientPlayConnectionEvents.DISCONNECT.register((h, client) -> client.execute(() -> leave(false)));
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> leave(true));
    }

    /** Whether anything uses the map right now (the World Map or the Minimap is on, and the server allows it). */
    private static boolean wanted() {
        if (EvadeClient.modules == null) return false;
        WorldMap wm = EvadeClient.modules.get(WorldMap.class);
        MinimapHud mini = EvadeClient.modules.get(MinimapHud.class);
        boolean used = (wm != null && wm.isEnabled()) || (mini != null && mini.isEnabled());
        return used && !ServerRules.isDisabled(mini);
    }

    /** Whether the server switched the map off (the Minimap or the World Map, by {@code evade:rules}). */
    private static boolean blocked() {
        if (EvadeClient.modules == null) return false;
        return ServerRules.isDisabled(EvadeClient.modules.get(MinimapHud.class)) || !exploredAllowed();
    }

    /** Whether explored tiles beyond the loaded chunks may be shown and saved (false while the server blocks the World Map). */
    public static boolean exploredAllowed() {
        if (EvadeClient.modules == null) return false;
        return !ServerRules.isDisabled(EvadeClient.modules.get(WorldMap.class));
    }

    /**
     * One tick of mapping (called by the World Map and the Minimap; runs once per client tick whoever calls it):
     * follows world changes, samples queued chunks within the time budget, lands worker results, saves in batches
     * and keeps memory under the cap.
     */
    public static void tick() {
        if (EvadeClient.ticks == lastTick) return;
        lastTick = EvadeClient.ticks;
        register();
        tick++;
        if (!wanted() || mc.world == null || mc.player == null) {
            // Blocked by the server: drop what this visit mapped instead of saving it.
            if (world != null) leave(false, !blocked());
            return;
        }
        if (mc.world != world) enter(mc.world);
        persist = mapping && WorldMap.saveToDisk() && exploredAllowed();
        long start = System.nanoTime();
        drainResults();
        if (mapping) sampleQueued(start);
        if (tick - lastSave >= SAVE_EVERY_TICKS) { lastSave = tick; saveDirty(); }
        enforceMemory();
        statNanos += System.nanoTime() - start;
        statTicks++;
    }

    /** A new world or dimension: save the old one, then start on this one with every chunk already loaded queued. */
    private static void enter(ClientWorld w) {
        leave(false);
        world = w;
        generation++;
        Waypoints wp = EvadeClient.modules.get(Waypoints.class);
        worldId = wp != null ? wp.worldId() : (mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "unknown");
        dimId = w.getRegistryKey().getValue().toString();
        mapping = !w.getDimension().hasCeiling();
        persist = mapping && WorldMap.saveToDisk() && exploredAllowed();
        dir = FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("maps").resolve(folder(worldId)).resolve(folder(dimId));
        listDisk();
        if (!mapping) return;
        // Everything already loaded, nearest first.
        ClientChunkManager cm = w.getChunkManager();
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
        int r = mc.options.getClampedViewDistance() + 3;
        for (int d = 0; d <= r; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dz = -d; dz <= d; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                    if (cm.getChunk(pcx + dx, pcz + dz, ChunkStatus.FULL, false) != null) pending.add(ChunkPos.toLong(pcx + dx, pcz + dz));
                }
            }
        }
    }

    /** Leaving a world (or the game): write what changed and forget it. {@code wait}: block until it's on disk. */
    private static void leave(boolean wait) { leave(wait, true); }

    private static void leave(boolean wait, boolean save) {
        if (world == null && regions.isEmpty()) return;
        if (save) saveDirty();
        regions.clear();
        onDisk.clear();
        pending.clear();
        results.clear();
        lastRegion = null;
        world = null;
        generation++;
        if (wait && worker != null) {
            try {
                Future<?> f = worker.submit(() -> {});
                f.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                EvadeClient.LOG.warn("World Map: couldn't finish saving the map in time", e);
            }
        }
    }

    /** A module stopped using the map: if nobody else does, save and free everything. */
    public static void released() {
        if (!wanted()) leave(false);
    }

    // ================================================================== what changed

    private static void onChunkLoad(ClientWorld w, ChunkPos p) {
        if (w != world || !mapping) return;
        pending.add(p.toLong());
        // Its first row shades against this chunk: re-map the one to the south if we have it.
        if (w.getChunkManager().getChunk(p.x, p.z + 1, ChunkStatus.FULL, false) != null) pending.add(ChunkPos.toLong(p.x, p.z + 1));
    }

    /** From {@code MapBlockChangeMixin}: a block changed. Only changes near the surface can change the map. */
    public static void onBlockChanged(ClientWorld w, BlockPos pos) {
        if (w != world || !mapping) return;
        int x = pos.getX(), z = pos.getZ();
        WorldChunk c = w.getChunkManager().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        if (c == null) return;
        // Water depth is counted up to 31 blocks down; anything deeper never shows.
        if (pos.getY() < c.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 15, z & 15) - 32) return;
        pending.add(ChunkPos.toLong(x >> 4, z >> 4));
        if ((z & 15) == 15) pending.add(ChunkPos.toLong(x >> 4, (z >> 4) + 1));
    }

    private static void sampleQueued(long start) {
        ClientChunkManager cm = world.getChunkManager();
        int n = 0, gen = generation;
        while (!pending.isEmpty() && n < MAX_CHUNKS_PER_TICK && System.nanoTime() - start < BUDGET_NANOS) {
            long key = pending.removeFirstLong();
            int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
            WorldChunk chunk = cm.getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk == null) continue; // unloaded meanwhile: keep what we had
            ChunkSampler.Snapshot s = ChunkSampler.sample(world, cm, chunk, cx, cz);
            n++;
            worker.execute(() -> {
                int[] px = ChunkSampler.colour(s);
                results.add(() -> { if (gen == generation) put(cx, cz, px); });
            });
        }
        statChunks += n;
    }

    private static void drainResults() {
        Runnable r;
        int n = 0;
        while (n++ < 512 && (r = results.poll()) != null) r.run();
    }

    /** Stores one chunk's packed columns. */
    private static void put(int cx, int cz, int[] px) {
        MapRegion reg = regionFor(cx >> 5, cz >> 5, true);
        int c = MapRegion.chunkIndex(cx, cz);
        System.arraycopy(px, 0, reg.px, c * 256, 256);
        reg.rev[c] = ++revision;
        reg.dirty = true;
    }

    // ================================================================== regions

    private static long regionKey(int rx, int rz) { return ChunkPos.toLong(rx, rz); }

    /**
     * The region at (rx, rz): in memory, or (with {@code create}, or when it exists on disk) made now and filled
     * from disk in the background. Null when there's nothing to show there.
     */
    private static MapRegion regionFor(int rx, int rz, boolean create) {
        MapRegion last = lastRegion;
        if (last != null && last.rx == rx && last.rz == rz) { last.lastUsed = tick; return last; }
        long key = regionKey(rx, rz);
        MapRegion reg = regions.get(key);
        if (reg == null) {
            boolean stored = persist && onDisk.contains(key);
            if (!create && !stored) return null;
            reg = new MapRegion(rx, rz);
            regions.put(key, reg);
            if (stored) load(reg);
        }
        reg.lastUsed = tick;
        lastRegion = reg;
        return reg;
    }

    /** Keeps at most Memory Limit regions: the least recently used ones are saved and dropped. */
    private static void enforceMemory() {
        int max = Math.max(4, WorldMap.memoryRegions());
        if (regions.size() <= max) return;
        List<MapRegion> all = new ArrayList<>(regions.values());
        all.sort((a, b) -> Long.compare(a.lastUsed, b.lastUsed));
        for (MapRegion reg : all) {
            if (regions.size() <= max) break;
            if (reg.lastUsed >= tick - 1 || reg.loading) continue; // in use right now
            if (reg.dirty) save(reg);
            regions.remove(regionKey(reg.rx, reg.rz));
            if (lastRegion == reg) lastRegion = null;
        }
    }

    // ================================================================== disk

    private static void listDisk() {
        onDisk.clear();
        if (!persist || !Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "r.*.evmap")) {
            for (Path p : ds) {
                String[] parts = p.getFileName().toString().split("\\.");
                if (parts.length != 4) continue;
                try {
                    onDisk.add(regionKey(Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
                } catch (NumberFormatException ignored) {
                    // not one of ours
                }
            }
        } catch (IOException e) {
            EvadeClient.LOG.warn("World Map: couldn't list {}", dir, e);
        }
    }

    private static Path file(Path d, int rx, int rz) { return d.resolve("r." + rx + "." + rz + ".evmap"); }

    private static void load(MapRegion reg) {
        reg.loading = true;
        Path f = file(dir, reg.rx, reg.rz);
        int gen = generation;
        worker.execute(() -> {
            int[] px = null;
            long[] mask = null;
            try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(f), 1 << 16))) {
                if (in.readInt() != FILE_MAGIC) throw new IOException("not an Evade map file");
                in.readInt(); // format version
                mask = new long[16];
                for (int i = 0; i < 16; i++) mask[i] = in.readLong();
                px = new int[MapRegion.COLUMNS];
                for (int c = 0; c < 1024; c++) {
                    if ((mask[c >> 6] & (1L << (c & 63))) == 0) continue;
                    for (int i = 0; i < 256; i++) px[c * 256 + i] = in.readInt();
                }
            } catch (IOException | RuntimeException e) {
                EvadeClient.LOG.warn("World Map: couldn't read {} ({}); it starts empty", f.getFileName(), e.toString());
                px = null;
            }
            int[] fpx = px;
            long[] fmask = mask;
            results.add(() -> {
                reg.loading = false;
                if (gen != generation || fpx == null) return;
                // Chunks mapped this session (while the file was read) are newer: keep them.
                for (int c = 0; c < 1024; c++) {
                    if ((fmask[c >> 6] & (1L << (c & 63))) == 0 || reg.rev[c] != 0) continue;
                    System.arraycopy(fpx, c * 256, reg.px, c * 256, 256);
                    reg.rev[c] = ++revision;
                }
            });
        });
    }

    private static void saveDirty() {
        for (MapRegion reg : regions.values()) if (reg.dirty) save(reg);
    }

    /** Copies the region and writes it on the worker (to a temp file, then moved over the old one). */
    private static void save(MapRegion reg) {
        reg.dirty = false;
        if (!persist || reg.loading) { if (reg.loading) reg.dirty = true; return; }
        long[] mask = new long[16];
        int explored = 0;
        for (int c = 0; c < 1024; c++) if (reg.rev[c] != 0) { mask[c >> 6] |= 1L << (c & 63); explored++; }
        if (explored == 0) return;
        int[] px = reg.px.clone();
        Path d = dir;
        onDisk.add(regionKey(reg.rx, reg.rz));
        worker.execute(() -> write(d, reg.rx, reg.rz, mask, px));
    }

    private static void write(Path d, int rx, int rz, long[] mask, int[] px) {
        Path f = file(d, rx, rz);
        Path tmp = d.resolve(f.getFileName() + ".tmp");
        try {
            Files.createDirectories(d);
            try (OutputStream os = Files.newOutputStream(tmp);
                 DataOutputStream out = new DataOutputStream(new GZIPOutputStream(os, 1 << 16))) {
                out.writeInt(FILE_MAGIC);
                out.writeInt(1);
                for (long m : mask) out.writeLong(m);
                for (int c = 0; c < 1024; c++) {
                    if ((mask[c >> 6] & (1L << (c & 63))) == 0) continue;
                    for (int i = 0; i < 256; i++) out.writeInt(px[c * 256 + i]);
                }
            }
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            EvadeClient.LOG.warn("World Map: couldn't save {}", f, e);
        }
    }

    /** "play.example.net_25565": a file-system-safe folder name (with a short hash when characters were replaced). */
    static String folder(String key) {
        String k = key == null || key.isBlank() ? "unknown" : key.strip();
        StringBuilder sb = new StringBuilder();
        for (char ch : k.toLowerCase(Locale.ROOT).toCharArray()) sb.append((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '.' || ch == '-' ? ch : '_');
        String s = sb.length() > 64 ? sb.substring(0, 64) : sb.toString();
        if (s.startsWith(".")) s = "_" + s.substring(1);
        if (!s.equals(k)) s += "-" + Integer.toHexString(k.hashCode());
        return s;
    }

    // ================================================================== reading (views)

    /** Whether the current world is mapped (false outside a world, under a ceiling or while blocked). */
    public static boolean mapping() { return world != null && mapping && world == mc.world; }

    /** The dimension being mapped ("minecraft:overworld"), or "". */
    public static String dimension() { return world == null ? "" : dimId; }

    /**
     * The revision of chunk (cx, cz): it changes whenever the chunk does, and is 0 while it holds nothing (never
     * explored, or its region is still being read from disk).
     */
    public static int revision(int cx, int cz) {
        if (!mapping()) return 0;
        MapRegion reg = regionFor(cx >> 5, cz >> 5, false);
        return reg == null ? 0 : reg.rev[MapRegion.chunkIndex(cx, cz)];
    }

    /** Copies chunk (cx, cz)'s 256 packed columns ({@code lz * 16 + lx}) into {@code out}; false if it holds nothing. */
    public static boolean copyChunk(int cx, int cz, int[] out) {
        if (!mapping()) return false;
        MapRegion reg = regionFor(cx >> 5, cz >> 5, false);
        if (reg == null) return false;
        int c = MapRegion.chunkIndex(cx, cz);
        if (reg.rev[c] == 0) return false;
        System.arraycopy(reg.px, c * 256, out, 0, 256);
        return true;
    }

    /** The packed column at block (x, z), or 0 when it was never seen. */
    public static int column(int x, int z) {
        if (!mapping()) return 0;
        MapRegion reg = regionFor(x >> 9, z >> 9, false);
        if (reg == null) return 0;
        int c = MapRegion.chunkIndex(x >> 4, z >> 4);
        return reg.rev[c] == 0 ? 0 : reg.px[c * 256 + (z & 15) * 16 + (x & 15)];
    }

    /** Region (rx, rz)'s per-chunk revisions and columns for a view's texture, or null (see {@link #revision}). */
    static MapRegion region(int rx, int rz) {
        return mapping() ? regionFor(rx, rz, false) : null;
    }

    /** Bumps when the world changes (views drop their textures). */
    public static int generation() { return generation; }

    /** Whether region (rx, rz) has anything to show (in memory or on disk), without loading it. */
    public static boolean hasRegion(int rx, int rz) {
        long k = regionKey(rx, rz);
        return mapping() && (regions.containsKey(k) || (persist && onDisk.contains(k)));
    }

    /** The memory cap in regions (the World Map limits its zoom so a full screen fits). */
    public static int capacity() { return Math.max(4, WorldMap.memoryRegions()); }

    // ================================================================== commands and tests

    /** {@code .map clear}: forgets this world's map for the current dimension, in memory and on disk. */
    public static int clearCurrent() {
        if (world == null) return 0;
        int n = 0;
        for (MapRegion r : regions.values()) n += r.exploredChunks();
        regions.clear();
        lastRegion = null;
        pending.clear();
        generation++;
        Path d = dir;
        LongOpenHashSet files = new LongOpenHashSet(onDisk);
        onDisk.clear();
        if (d != null) worker.execute(() -> {
            for (long k : files) {
                try { Files.deleteIfExists(file(d, ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k))); } catch (IOException ignored) { }
            }
        });
        return n;
    }

    /** Test hook: explored chunks in memory. */
    public static int exploredChunks() {
        int n = 0;
        for (MapRegion r : regions.values()) n += r.exploredChunks();
        return n;
    }

    /** Test hook: chunks still waiting to be mapped. */
    public static int pendingChunks() { return pending.size(); }

    /** Test hook: the folder this world and dimension are saved in. */
    public static Path folderForTest() { return dir; }

    /** Test hook: writes everything and waits for the disk. */
    public static void flushForTest() {
        saveDirty();
        waitWorker();
    }

    /** Test hook: forgets everything in memory and reads it back from disk (waiting for it). */
    public static void reloadForTest() {
        saveDirty();
        waitWorker();
        regions.clear();
        lastRegion = null;
        generation++;
        listDisk();
    }

    /** Test hook: waits for the worker, then lands its results. */
    public static void settleForTest() {
        waitWorker();
        drainResults();
    }

    private static void waitWorker() {
        if (worker == null) return;
        try {
            worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            EvadeClient.LOG.warn("World Map: worker didn't finish", e);
        }
    }

    /** "avg µs per tick, chunks mapped" since the last call. */
    public static String takeStats() {
        String s = String.format(Locale.ROOT, "%.1f us/tick over %d ticks, %d chunks mapped", statTicks == 0 ? 0 : statNanos / 1000.0 / statTicks, statTicks, statChunks);
        statNanos = statTicks = statChunks = 0;
        return s;
    }

    /** Test hook: region files this world and dimension have on disk. */
    public static int filesOnDisk() { return onDisk.size(); }
}
