package net.evadeclient.mods;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * A jar in the mods folder, as its fabric.mod.json describes it.
 *
 * @param id       its Fabric mod id, or null when the jar has no fabric.mod.json
 * @param provides its id, the ids it declares in "provides", and those of the mods nested inside it (Fabric API's
 *                 modules, for example): everything another mod's "depends" can name it by
 * @param depends  the mod ids it requires (not minecraft, java or fabricloader)
 * @param icon     the icon file's bytes from inside the jar (small icons only), or null
 */
public record InstalledMod(Path path, String fileName, String id, String name, String version, String description,
                           Set<String> provides, Set<String> depends, String sha1, String sha512, byte[] icon) {

    private static final Set<String> PLATFORM = Set.of("minecraft", "java", "fabricloader", "fabric-loader");
    private static final long MAX_NESTED = 64L << 20, MAX_ICON = 512 << 10;

    public String displayName() {
        return name != null && !name.isBlank() ? name : id != null ? id : fileName;
    }

    /** Every jar directly in {@code mods}, sorted by name. Unreadable jars are listed with what is known (file name). */
    public static List<InstalledMod> scan(Path mods) {
        List<InstalledMod> out = new ArrayList<>();
        if (!Files.isDirectory(mods)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods, "*.jar")) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;
                out.add(read(p));
            }
        } catch (IOException ignored) {
            // an unreadable folder lists as empty
        }
        out.sort(Comparator.comparing(m -> m.displayName().toLowerCase(Locale.ROOT)));
        return out;
    }

    public static InstalledMod read(Path p) {
        String sha1 = null, sha512 = null;
        try {
            MessageDigest d1 = MessageDigest.getInstance("SHA-1"), d5 = ModrinthApi.sha512();
            try (InputStream in = Files.newInputStream(p)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) { d1.update(buf, 0, n); d5.update(buf, 0, n); }
            }
            sha1 = HexFormat.of().formatHex(d1.digest());
            sha512 = HexFormat.of().formatHex(d5.digest());
        } catch (Exception ignored) {
            // hashes stay null: no update check for this file
        }
        String id = null, name = null, version = null, desc = null;
        Set<String> provides = new LinkedHashSet<>(), depends = new LinkedHashSet<>();
        byte[] icon = null;
        try (ZipFile zf = new ZipFile(p.toFile())) {
            JsonObject o = modJson(zf);
            if (o != null) {
                id = ModrinthApi.str(o, "id");
                name = ModrinthApi.str(o, "name");
                version = ModrinthApi.str(o, "version");
                desc = ModrinthApi.str(o, "description");
                addIds(o, provides);
                if (o.has("depends") && o.get("depends").isJsonObject())
                    for (String k : o.getAsJsonObject("depends").keySet()) if (!PLATFORM.contains(k)) depends.add(k);
                icon = iconBytes(zf, o);
                if (o.has("jars") && o.get("jars").isJsonArray()) {
                    for (JsonElement j : o.getAsJsonArray("jars")) {
                        if (!j.isJsonObject()) continue;
                        String f = ModrinthApi.str(j.getAsJsonObject(), "file");
                        if (f != null) nestedIds(zf, f, provides);
                    }
                }
            }
        } catch (Exception ignored) {
            // not a readable jar: listed by file name only
        }
        return new InstalledMod(p, p.getFileName().toString(), id, name, version, desc, provides, depends, sha1, sha512, icon);
    }

    private static void addIds(JsonObject o, Set<String> into) {
        String id = ModrinthApi.str(o, "id");
        if (id != null) into.add(id);
        if (o.has("provides") && o.get("provides").isJsonArray())
            for (JsonElement e : o.getAsJsonArray("provides")) if (e.isJsonPrimitive()) into.add(e.getAsString());
    }

    private static JsonObject modJson(ZipFile zf) throws IOException {
        ZipEntry e = zf.getEntry("fabric.mod.json");
        if (e == null) return null;
        try (InputStream in = zf.getInputStream(e)) {
            return parse(new String(in.readNBytes(1 << 20), StandardCharsets.UTF_8));
        }
    }

    /** fabric.mod.json, read leniently (some mods ship comments or trailing commas). */
    static JsonObject parse(String s) {
        try {
            JsonReader r = new JsonReader(new StringReader(s));
            r.setStrictness(Strictness.LENIENT);
            JsonElement el = JsonParser.parseReader(r);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The ids of a jar nested in the jar (one level deep, which is where Fabric API keeps its modules). */
    private static void nestedIds(ZipFile zf, String path, Set<String> into) {
        ZipEntry e = zf.getEntry(path);
        if (e == null || e.getSize() > MAX_NESTED) return;
        try (ZipInputStream zin = new ZipInputStream(zf.getInputStream(e))) {
            ZipEntry inner;
            while ((inner = zin.getNextEntry()) != null) {
                if (!inner.getName().equals("fabric.mod.json")) continue;
                JsonObject o = parse(new String(zin.readNBytes(1 << 20), StandardCharsets.UTF_8));
                if (o != null) addIds(o, into);
                return;
            }
        } catch (IOException ignored) {
            // skip a broken nested jar
        }
    }

    private static byte[] iconBytes(ZipFile zf, JsonObject o) {
        String icon = null;
        JsonElement e = o.get("icon");
        if (e != null && e.isJsonPrimitive()) icon = e.getAsString();
        else if (e != null && e.isJsonObject()) {
            // a size map: the largest up to 128 px, else the smallest
            int best = -1;
            for (var en : e.getAsJsonObject().entrySet()) {
                int sz;
                try { sz = Integer.parseInt(en.getKey()); } catch (NumberFormatException ex) { continue; }
                if (!en.getValue().isJsonPrimitive()) continue;
                boolean better = best < 0 || (sz <= 128 ? best > 128 || sz > best : best > 128 && sz < best);
                if (better) { best = sz; icon = en.getValue().getAsString(); }
            }
        }
        if (icon == null) return null;
        ZipEntry ze = zf.getEntry(icon.startsWith("/") ? icon.substring(1) : icon);
        if (ze == null || ze.getSize() > MAX_ICON) return null;
        try (InputStream in = zf.getInputStream(ze)) {
            byte[] b = in.readNBytes((int) MAX_ICON + 1);
            return b.length > MAX_ICON ? null : b;
        } catch (IOException ex) {
            return null;
        }
    }

    /** The Fabric mod id inside a jar file, or null. */
    public static String fabricId(Path jar) {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            JsonObject o = modJson(zf);
            return o == null ? null : ModrinthApi.str(o, "id");
        } catch (IOException e) {
            return null;
        }
    }
}
