package net.evadeclient.replay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.MinecraftClient;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The replay file format ({@code .evadereplay}): a zip with
 * <ul>
 *   <li>{@code meta.json}: what was recorded, where, when, how long, markers, paused stretches, camera paths</li>
 *   <li>{@code stream.bin}: a zlib stream of records, each {@code kind, tick, length, bytes}: the packets the
 *       server sent (exactly as the game encodes them) and the local player's own movement and actions</li>
 * </ul>
 * Ticks count client ticks (20 a second) since the recording joined the world or server.
 */
public final class ReplayFile {
    private ReplayFile() {}

    public static final String EXT = "evadereplay";
    public static final int FORMAT = 1;

    /** A configuration-phase packet (registries, tags, features...). */
    public static final byte CONFIG = 1;
    /** A play-phase packet. */
    public static final byte PLAY = 2;
    /** Where the local player was this tick (they never receive their own movement from the server). */
    public static final byte SELF = 3;
    /** The local player swung a hand. */
    public static final byte SWING = 4;
    /** The local player's held items or armour changed. */
    public static final byte EQUIP = 5;

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Path dir() { return MinecraftClient.getInstance().runDirectory.toPath().resolve("evade").resolve("replays"); }

    /** What a replay is, shown in the browser and used to play it back. */
    public static final class Meta {
        public int format = FORMAT;
        public String minecraft = "", evade = "";
        public String name = "", server = "", world = "";
        public long recordedAt;
        /** Ticks in the stream (the whole session from joining). */
        public int durationTicks;
        /** The part the player chose to keep: playback starts at {@code startTick} and ends at {@code endTick}. */
        public int startTick, endTick;
        public String playerName = "", playerUuid = "";
        public List<Marker> markers = new ArrayList<>();
        /** Paused stretches [from, to) that playback skips over. */
        public List<int[]> pauses = new ArrayList<>();
        /** Camera paths and scene settings saved from the editor (JSON written by {@link ReplayProject}). */
        public com.google.gson.JsonObject project;
        public long sizeBytes;

        public int keptTicks() {
            int t = Math.max(0, endTick - startTick);
            for (int[] p : pauses) t -= Math.max(0, Math.min(p[1], endTick) - Math.max(p[0], startTick));
            return Math.max(0, t);
        }
    }

    public record Marker(int tick, String label) {}

    public record Record(byte kind, int tick, byte[] data) {}

    // ------------------------------------------------------------------ writing (while recording)

    /** The live record stream of a session, written to a temporary file as it happens. */
    public static final class StreamWriter implements AutoCloseable {
        private final Path file;
        private final DeflaterOutputStream deflate;
        private final DataOutputStream out;
        private long records;

        public StreamWriter(Path file) throws IOException {
            this.file = file;
            Files.createDirectories(file.getParent());
            this.deflate = new DeflaterOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16), new Deflater(5), 1 << 16, true);
            this.out = new DataOutputStream(deflate);
        }

        public synchronized void write(byte kind, int tick, byte[] data) throws IOException {
            out.writeByte(kind);
            writeVarInt(out, tick);
            writeVarInt(out, data.length);
            out.write(data);
            records++;
        }

        public long records() { return records; }

        /** Pushes everything written so far to disk (readable up to here), keeping the stream open. */
        public synchronized void sync() throws IOException { out.flush(); deflate.flush(); }

        public Path file() { return file; }

        @Override public synchronized void close() throws IOException { out.close(); }
    }

    /** Writes a finished replay: meta plus a copy of the stream so far (the session may carry on recording). */
    public static void save(Path target, Meta meta, Path stream) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        CRC32 crc = new CRC32();
        long size = Files.size(stream);
        try (InputStream in = Files.newInputStream(stream)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) crc.update(buf, 0, n);
        }
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            ZipEntry m = new ZipEntry("meta.json");
            zip.putNextEntry(m);
            meta.sizeBytes = size;
            zip.write(GSON.toJson(meta).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            // already compressed: stored as-is
            ZipEntry s = new ZipEntry("stream.bin");
            s.setMethod(ZipEntry.STORED);
            s.setSize(size);
            s.setCompressedSize(size);
            s.setCrc(crc.getValue());
            zip.putNextEntry(s);
            Files.copy(stream, zip);
            zip.closeEntry();
        }
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Rewrites only the meta of a saved replay (rename, markers, camera paths). */
    public static void updateMeta(Path file, Meta meta) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".part");
        try (ZipFile zf = new ZipFile(file.toFile());
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            zip.putNextEntry(new ZipEntry("meta.json"));
            zip.write(GSON.toJson(meta).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            ZipEntry old = zf.getEntry("stream.bin");
            ZipEntry s = new ZipEntry("stream.bin");
            s.setMethod(ZipEntry.STORED);
            s.setSize(old.getSize());
            s.setCompressedSize(old.getSize());
            s.setCrc(old.getCrc());
            zip.putNextEntry(s);
            try (InputStream in = zf.getInputStream(old)) { in.transferTo(zip); }
            zip.closeEntry();
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    // ------------------------------------------------------------------ reading

    public static Meta readMeta(Path file) throws IOException {
        try (ZipFile zf = new ZipFile(file.toFile())) {
            ZipEntry e = zf.getEntry("meta.json");
            if (e == null) throw new IOException("Not an Evade replay");
            try (InputStream in = zf.getInputStream(e)) {
                Meta m = GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), Meta.class);
                if (m.markers == null) m.markers = new ArrayList<>();
                if (m.pauses == null) m.pauses = new ArrayList<>();
                return m;
            }
        }
    }

    /** Reads the records of a replay, in order. */
    public static final class Reader implements AutoCloseable {
        private final ZipFile zip;
        private final DataInputStream in;
        private boolean done;

        public Reader(Path file) throws IOException {
            this.zip = new ZipFile(file.toFile());
            ZipEntry e = zip.getEntry("stream.bin");
            if (e == null) { zip.close(); throw new IOException("Replay has no recording in it"); }
            this.in = new DataInputStream(new BufferedInputStream(new InflaterInputStream(zip.getInputStream(e)), 1 << 16));
        }

        /** The next record, or null at the end (a stream saved mid-session simply stops). */
        public Record next() throws IOException {
            if (done) return null;
            try {
                int k = in.read();
                if (k < 0) { done = true; return null; }
                int tick = readVarInt(in);
                int len = readVarInt(in);
                if (len < 0 || len > (64 << 20)) throw new IOException("Corrupt replay record");
                byte[] data = new byte[len];
                in.readFully(data);
                return new Record((byte) k, tick, data);
            } catch (EOFException | java.util.zip.ZipException e) {
                done = true;
                return null;
            } catch (IOException e) {
                // "Unexpected end of ZLIB input stream": a recording saved while still running ends without a final block
                if (String.valueOf(e.getMessage()).contains("end of ZLIB")) { done = true; return null; }
                throw e;
            }
        }

        @Override public void close() throws IOException { zip.close(); }
    }

    // ------------------------------------------------------------------ helpers

    static void writeVarInt(OutputStream out, int v) throws IOException {
        while ((v & ~0x7F) != 0) { out.write((v & 0x7F) | 0x80); v >>>= 7; }
        out.write(v);
    }

    static int readVarInt(InputStream in) throws IOException {
        int v = 0, shift = 0, b;
        do {
            b = in.read();
            if (b < 0) throw new EOFException();
            v |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) throw new IOException("VarInt too big");
        } while ((b & 0x80) != 0);
        return v;
    }

    /** Saved replays, newest first. */
    public static List<Path> list() {
        try (var s = Files.list(dir())) {
            return s.filter(p -> p.toString().endsWith("." + EXT)).sorted((a, b) -> {
                try { return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)); } catch (IOException e) { return 0; }
            }).toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
