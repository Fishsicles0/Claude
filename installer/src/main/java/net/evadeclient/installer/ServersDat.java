package net.evadeclient.installer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The game's Multiplayer list ({@code servers.dat} in an instance's game folder): uncompressed NBT, a root compound
 * holding a list "servers" of compounds {@code {name, ip, icon?, acceptTextures?, ...}}. The launcher puts the
 * player's launcher servers at the top and keeps every other entry exactly as the game wrote it (unknown fields
 * included). Entries the launcher added earlier and the player has since removed from the launcher are taken out.
 */
final class ServersDat {
    private ServersDat() {}

    /** A launcher server to put in the list ({@code icon}: base64 PNG without the data: prefix, or null). */
    record Entry(String name, String ip, String icon) {}

    // NBT tag ids
    private static final int END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6, BYTE_ARRAY = 7,
            STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

    /** A list tag: its element type and values. */
    record NbtList(int type, List<Object> items) {}

    static String key(String ip) {
        String a = ip == null ? "" : ip.strip().toLowerCase(Locale.ROOT);
        return a.endsWith(":25565") ? a.substring(0, a.length() - 6) : a;
    }

    /** Next to servers.dat: the addresses the launcher added there (so it only ever removes its own entries). */
    static final String OWNED_FILE = "evade-servers.json";

    /**
     * Puts the launcher's servers at the top of the instance's Multiplayer list ({@code gameDir/servers.dat}). The
     * player's own entries stay, in their order; an entry with the same address as a launcher server is replaced by
     * it (keeping its other fields), never listed twice. Entries the launcher itself added before
     * ({@value #OWNED_FILE}) and that aren't launcher servers any more are taken out; the player's own never are.
     */
    static void sync(Path gameDir, List<Entry> ours) throws IOException {
        Path file = gameDir.resolve("servers.dat"), ownedFile = gameDir.resolve(OWNED_FILE);
        Set<String> owned = new java.util.LinkedHashSet<>();
        var o = InstallerCore.readJsonObject(ownedFile);
        if (o.has("added") && o.get("added").isJsonArray()) for (var e : o.getAsJsonArray("added")) if (e.isJsonPrimitive()) owned.add(key(e.getAsString()));
        if (ours.isEmpty() && owned.isEmpty()) return;
        Set<String> now = merge(file, ours, owned);
        var out = new com.google.gson.JsonObject();
        var arr = new com.google.gson.JsonArray();
        for (String a : now) arr.add(a);
        out.add("added", arr);
        InstallerCore.writeAtomic(ownedFile, InstallerCore.GSON.toJson(out));
    }

    /**
     * Merges {@code ours} into {@code file}, removing {@code ownedBefore} entries that aren't in {@code ours}.
     * Returns the addresses the launcher owns afterwards (its earlier ones still listed, plus ones it newly added).
     */
    static Set<String> merge(Path file, List<Entry> ours, Set<String> ownedBefore) throws IOException {
        Map<String, Object> root = Files.exists(file) ? read(Files.readAllBytes(file)) : new LinkedHashMap<>();
        List<Object> old = new ArrayList<>();
        if (root.get("servers") instanceof NbtList l && l.type() == COMPOUND) old.addAll(l.items());
        Map<String, Map<String, Object>> existing = new LinkedHashMap<>();
        for (Object x : old) {
            @SuppressWarnings("unchecked") Map<String, Object> c = (Map<String, Object>) x;
            if (c.get("ip") instanceof String ip) existing.putIfAbsent(key(ip), c);
        }
        List<Object> out = new ArrayList<>();
        Set<String> placed = new java.util.LinkedHashSet<>(), owned = new java.util.LinkedHashSet<>();
        for (Entry e : ours) {
            String k = key(e.ip());
            if (k.isEmpty() || !placed.add(k)) continue;
            boolean had = existing.containsKey(k);
            boolean ours2 = !had || ownedBefore.contains(k);
            if (ours2) owned.add(k);
            Map<String, Object> c = had ? new LinkedHashMap<>(existing.get(k)) : new LinkedHashMap<>();
            // The player's own entry for the same server only moves to the top: its name stays theirs.
            if (ours2) {
                c.put("name", e.name() == null || e.name().isBlank() ? e.ip().strip() : e.name());
                c.put("ip", e.ip().strip());
            }
            if (e.icon() != null && !e.icon().isBlank() && e.icon().length() < 60_000 && (ours2 || !c.containsKey("icon"))) c.put("icon", e.icon());
            out.add(c);
        }
        for (Object x : old) {
            @SuppressWarnings("unchecked") Map<String, Object> c = (Map<String, Object>) x;
            String k = c.get("ip") instanceof String ip ? key(ip) : "";
            if (!k.isEmpty() && (placed.contains(k) || ownedBefore.contains(k))) continue;
            out.add(c);
        }
        root.put("servers", new NbtList(COMPOUND, out));
        Files.createDirectories(file.toAbsolutePath().getParent());
        if (Files.exists(file)) {
            try { Files.copy(file, file.resolveSibling("servers.dat_old"), java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) { }
        }
        InstallerCore.writeAtomic(file, write(root));
        return owned;
    }

    /** The addresses in a servers.dat, in order (for tests and checks). */
    static List<String> addresses(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        Map<String, Object> root = read(Files.readAllBytes(file));
        if (root.get("servers") instanceof NbtList l) for (Object o : l.items())
            if (o instanceof Map<?, ?> c && c.get("ip") instanceof String ip) out.add(ip);
        return out;
    }

    // ------------------------------------------------------------------ NBT

    static Map<String, Object> read(byte[] data) throws IOException {
        if (data.length >= 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
            data = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(data)).readAllBytes();
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int type = in.readUnsignedByte();
        if (type != COMPOUND) throw new IOException("servers.dat doesn't start with a compound");
        in.readUTF(); // root name
        return unwrapCompound(readCompound(in, 0));
    }

    private static Map<String, Object> readCompound(DataInputStream in, int depth) throws IOException {
        if (depth > 64) throw new IOException("NBT nested too deep");
        Map<String, Object> m = new LinkedHashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == END) return m;
            String name = in.readUTF();
            m.put(name, new Tagged(type, readPayload(in, type, depth + 1)));
        }
    }

    /** A value with its NBT type (so numbers keep their exact type when written back). */
    record Tagged(int type, Object value) {}

    private static Object readPayload(DataInputStream in, int type, int depth) throws IOException {
        return switch (type) {
            case BYTE -> in.readByte();
            case SHORT -> in.readShort();
            case INT -> in.readInt();
            case LONG -> in.readLong();
            case FLOAT -> in.readFloat();
            case DOUBLE -> in.readDouble();
            case BYTE_ARRAY -> { byte[] b = new byte[checkLen(in.readInt())]; in.readFully(b); yield b; }
            case STRING -> in.readUTF();
            case LIST -> {
                int et = in.readUnsignedByte(), n = checkLen(in.readInt());
                List<Object> items = new ArrayList<>(n);
                for (int i = 0; i < n; i++) items.add(unwrap(et, readPayload(in, et, depth + 1)));
                yield new NbtList(et, items);
            }
            case COMPOUND -> unwrapCompound(readCompound(in, depth + 1));
            case INT_ARRAY -> { int[] a = new int[checkLen(in.readInt())]; for (int i = 0; i < a.length; i++) a[i] = in.readInt(); yield a; }
            case LONG_ARRAY -> { long[] a = new long[checkLen(in.readInt())]; for (int i = 0; i < a.length; i++) a[i] = in.readLong(); yield a; }
            default -> throw new IOException("Unknown NBT tag " + type);
        };
    }

    private static int checkLen(int n) throws IOException {
        if (n < 0 || n > 16_000_000) throw new IOException("Bad NBT length");
        return n;
    }

    private static Object unwrap(int type, Object v) { return v; }

    /** Compounds keep strings and nested lists/compounds as plain values; other numbers stay {@link Tagged}. */
    private static Map<String, Object> unwrapCompound(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : m.entrySet()) {
            Tagged t = (Tagged) e.getValue();
            out.put(e.getKey(), switch (t.type()) {
                case STRING, LIST, COMPOUND -> t.value();
                default -> t;
            });
        }
        return out;
    }

    static byte[] write(Map<String, Object> root) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        out.writeByte(COMPOUND);
        out.writeUTF("");
        writeCompound(out, root.entrySet().stream().collect(LinkedHashMap::new,
                (m, e) -> m.put(e.getKey(), e.getValue() instanceof Tagged t && (t.type() == STRING || t.type() == LIST || t.type() == COMPOUND) ? t.value() : e.getValue()),
                Map::putAll));
        out.flush();
        return b.toByteArray();
    }

    private static void writeCompound(DataOutputStream out, Map<?, ?> m) throws IOException {
        for (var e : m.entrySet()) {
            Object v = e.getValue();
            int type = typeOf(v);
            out.writeByte(type);
            out.writeUTF(String.valueOf(e.getKey()));
            writePayload(out, type, v instanceof Tagged t ? t.value() : v);
        }
        out.writeByte(END);
    }

    private static int typeOf(Object v) {
        if (v instanceof Tagged t) return t.type();
        if (v instanceof String) return STRING;
        if (v instanceof NbtList) return LIST;
        if (v instanceof Map) return COMPOUND;
        if (v instanceof Byte) return BYTE;
        if (v instanceof Integer) return INT;
        throw new IllegalArgumentException("Not an NBT value: " + v);
    }

    private static void writePayload(DataOutputStream out, int type, Object v) throws IOException {
        switch (type) {
            case BYTE -> out.writeByte((Byte) v);
            case SHORT -> out.writeShort((Short) v);
            case INT -> out.writeInt((Integer) v);
            case LONG -> out.writeLong((Long) v);
            case FLOAT -> out.writeFloat((Float) v);
            case DOUBLE -> out.writeDouble((Double) v);
            case BYTE_ARRAY -> { byte[] a = (byte[]) v; out.writeInt(a.length); out.write(a); }
            case STRING -> out.writeUTF((String) v);
            case LIST -> {
                NbtList l = (NbtList) v;
                out.writeByte(l.items().isEmpty() && l.type() == END ? END : l.type());
                out.writeInt(l.items().size());
                for (Object o : l.items()) writePayload(out, l.type(), o instanceof Tagged t ? t.value() : o);
            }
            case COMPOUND -> writeCompound(out, (Map<?, ?>) v);
            case INT_ARRAY -> { int[] a = (int[]) v; out.writeInt(a.length); for (int x : a) out.writeInt(x); }
            case LONG_ARRAY -> { long[] a = (long[]) v; out.writeInt(a.length); for (long x : a) out.writeLong(x); }
            default -> throw new IOException("Unknown NBT tag " + type);
        }
    }
}
