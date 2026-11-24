package net.evadeclient.installer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Drag-and-drop install: a file dropped on the launcher window goes where it belongs in the selected instance.
 * <ul>
 *   <li>a Fabric mod (.jar with fabric.mod.json, made for Minecraft {@value InstallerCore#MC_VERSION}) into mods/;</li>
 *   <li>a resource pack (.zip with pack.mcmeta and assets/) into resourcepacks/, a shader pack (.zip with shaders/)
 *       into shaderpacks/;</li>
 *   <li>a Modrinth modpack (.mrpack) or an exported Evade instance (.zip) becomes a new instance.</li>
 * </ul>
 * Anything else is refused with the reason (a Forge mod, another Minecraft version, a data pack, which goes into a
 * world). Mods and packs are looked up on Modrinth by hash, so they show as Modrinth content with update checks.
 */
final class DropInstall {
    private DropInstall() {}

    enum Kind { MOD, RESOURCEPACK, SHADER, MRPACK, INSTANCE_ZIP, REJECT }

    /** What a dropped file is: its kind, a name to show, and why it can't be used (REJECT). */
    record Verdict(Kind kind, String name, String reason) {
        static Verdict no(String name, String reason) { return new Verdict(Kind.REJECT, name, reason); }
    }

    /** Looks inside the file (never runs or extracts anything) and says what it is. */
    static Verdict classify(Path f) {
        String name = f.getFileName() == null ? f.toString() : f.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (Files.isDirectory(f)) return Verdict.no(name, "Folders can't be dropped: drop the .jar or .zip itself");
        if (!Files.isRegularFile(f)) return Verdict.no(name, "Couldn't read the file");
        if (lower.endsWith(".mrpack")) return new Verdict(Kind.MRPACK, name, null);
        if (!lower.endsWith(".jar") && !lower.endsWith(".zip")) return Verdict.no(name, "Only mods (.jar), packs (.zip) and modpacks (.mrpack) can be dropped here");
        try (ZipFile z = new ZipFile(f.toFile())) {
            if (lower.endsWith(".jar")) return classifyJar(z, name);
            return classifyZip(z, name);
        } catch (IOException | RuntimeException e) {
            return Verdict.no(name, "It isn't a readable " + (lower.endsWith(".jar") ? "mod" : "zip") + " file");
        }
    }

    private static Verdict classifyJar(ZipFile z, String name) throws IOException {
        ZipEntry fmj = z.getEntry("fabric.mod.json");
        if (fmj == null) {
            if (z.getEntry("META-INF/neoforge.mods.toml") != null) return Verdict.no(name, "It's a NeoForge mod: Evade runs on Fabric");
            if (z.getEntry("META-INF/mods.toml") != null || z.getEntry("mcmod.info") != null) return Verdict.no(name, "It's a Forge mod: Evade runs on Fabric");
            if (z.getEntry("quilt.mod.json") != null) return Verdict.no(name, "It's a Quilt-only mod: Evade runs on Fabric");
            return Verdict.no(name, "It isn't a Fabric mod (no fabric.mod.json)");
        }
        JsonObject o;
        try (InputStream in = z.getInputStream(fmj)) {
            JsonElement e = JsonParser.parseString(new String(in.readNBytes(1 << 20), StandardCharsets.UTF_8));
            if (!e.isJsonObject()) return Verdict.no(name, "Its fabric.mod.json is damaged");
            o = e.getAsJsonObject();
        } catch (RuntimeException e) {
            return Verdict.no(name, "Its fabric.mod.json is damaged");
        }
        String title = InstallerCore.str(o, "name") != null ? InstallerCore.str(o, "name") : InstallerCore.str(o, "id") != null ? InstallerCore.str(o, "id") : name;
        String env = InstallerCore.str(o, "environment");
        if ("server".equals(env)) return Verdict.no(title, "It's a server-only mod");
        if (o.has("depends") && o.get("depends").isJsonObject() && o.getAsJsonObject("depends").has("minecraft")) {
            JsonElement mc = o.getAsJsonObject("depends").get("minecraft");
            if (!VersionCheck.allows(mc, InstallerCore.MC_VERSION))
                return Verdict.no(title, "It's made for Minecraft " + VersionCheck.describe(mc) + ", not " + InstallerCore.MC_VERSION);
        }
        String id = InstallerCore.str(o, "id");
        if ("evade".equals(id) || "evadeclient".equals(id) || "fabric-api".equals(id) || "fabric".equals(id))
            return Verdict.no(title, "Evade installs and updates that one itself");
        return new Verdict(Kind.MOD, title, null);
    }

    private static Verdict classifyZip(ZipFile z, String name) {
        boolean mcmeta = false, assets = false, data = false, shaders = false, instance = false;
        for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
            String n = en.nextElement().getName().replace('\\', '/');
            String top = n.contains("/") ? n.substring(n.indexOf('/') + 1) : n; // also one folder down (zips made by "send to")
            if (n.equals(InstanceZip.MANIFEST) || top.equals(InstanceZip.MANIFEST)) instance = true;
            if (n.equals("pack.mcmeta")) mcmeta = true;
            if (n.startsWith("assets/")) assets = true;
            if (n.startsWith("data/")) data = true;
            if (n.startsWith("shaders/") || top.startsWith("shaders/")) shaders = true;
        }
        if (instance) return new Verdict(Kind.INSTANCE_ZIP, name, null);
        if (mcmeta && assets) return new Verdict(Kind.RESOURCEPACK, name, null);
        if (mcmeta && data) return Verdict.no(name, "It's a data pack: add it to a world under Mods › Worlds");
        if (shaders) return new Verdict(Kind.SHADER, name, null);
        if (mcmeta) return new Verdict(Kind.RESOURCEPACK, name, null);
        return Verdict.no(name, "It isn't a resource pack, shader pack or Evade instance");
    }

    /**
     * Copies a mod or pack into the instance (replacing a file of the same name), then records it when Modrinth knows
     * its hash. Returns where it went. Instances and modpacks are imported by the caller (they make new instances).
     */
    static Path install(Path gameDir, Path f, Verdict v) throws IOException {
        String folder = switch (v.kind()) {
            case MOD -> "mods";
            case RESOURCEPACK -> "resourcepacks";
            case SHADER -> "shaderpacks";
            default -> throw new IOException(v.name() + " isn't added this way");
        };
        String name = f.getFileName().toString();
        if (!InstallerCore.plainName(name)) throw new IOException("That file name can't be used");
        Path dir = gameDir.resolve(folder);
        Files.createDirectories(dir);
        Path target = dir.resolve(name);
        if (target.toAbsolutePath().normalize().equals(f.toAbsolutePath().normalize())) return target; // dropped from its own folder
        if (v.kind() == Kind.MOD) {
            // the same mod switched off: drop replaces it
            Files.deleteIfExists(dir.resolve(name + ".disabled"));
            JsonObject marker = InstallerCore.readMarker(gameDir);
            String option = InstallerCore.optionForJar(marker, name);
            if (option != null) throw new IOException(v.name() + " comes with " + option + ": switch " + option + " on in Instances");
        }
        Path tmp = dir.resolve(name + ".part");
        Files.copy(f, tmp, StandardCopyOption.REPLACE_EXISTING);
        InstallerCore.move(tmp, target);
        remember(gameDir, target, v);
        return target;
    }

    /** Best effort: when Modrinth knows the file, note its project so it shows as Modrinth content with updates. */
    private static void remember(Path gameDir, Path target, Verdict v) {
        try {
            String sha1 = ContentApi.sha1(target);
            Map<String, ContentApi.Version> ids = ContentApi.identify(List.of(sha1));
            ContentApi.Version ver = ids.get(sha1);
            if (ver == null || ver.projectId() == null) return;
            if (v.kind() == Kind.MOD) {
                JsonObject rec = InstallerCore.readJsonObject(gameDir.resolve(Mods.RECORD));
                JsonObject o = new JsonObject();
                o.addProperty("file", target.getFileName().toString());
                o.addProperty("version", ver.number());
                rec.add(ver.projectId(), o);
                InstallerCore.writeAtomic(gameDir.resolve(Mods.RECORD), InstallerCore.GSON.toJson(rec));
            } else {
                Content.remember(gameDir, v.kind() == Kind.SHADER ? Content.Type.SHADER : Content.Type.RESOURCEPACK, ver.projectId(),
                        target.getFileName().toString(), ver.number(), v.name());
            }
        } catch (IOException | RuntimeException ignored) { /* offline: it shows as a manual file */ }
    }

    /** "2 mods and a resource pack" for a toast. */
    static String summary(List<Verdict> done) {
        int mods = 0, packs = 0, shaders = 0;
        for (Verdict v : done) {
            if (v.kind() == Kind.MOD) mods++;
            else if (v.kind() == Kind.RESOURCEPACK) packs++;
            else if (v.kind() == Kind.SHADER) shaders++;
        }
        List<String> p = new ArrayList<>();
        if (mods > 0) p.add(mods == 1 ? "a mod" : mods + " mods");
        if (packs > 0) p.add(packs == 1 ? "a resource pack" : packs + " resource packs");
        if (shaders > 0) p.add(shaders == 1 ? "a shader pack" : shaders + " shader packs");
        if (p.isEmpty()) return "nothing";
        if (p.size() == 1) return p.get(0);
        return String.join(", ", p.subList(0, p.size() - 1)) + " and " + p.get(p.size() - 1);
    }

    /**
     * Fabric's version predicates for the "minecraft" dependency of a mod: a string, or a list where any may match.
     * Supports {@code *}, {@code 1.21.x} / {@code 1.21.*}, {@code =}, {@code >=}, {@code <=}, {@code >}, {@code <},
     * {@code ~} (same minor), {@code ^} (same major) and space-separated combinations (all must hold).
     */
    static final class VersionCheck {
        private VersionCheck() {}

        static boolean allows(JsonElement e, String version) {
            if (e == null || e.isJsonNull()) return true;
            if (e.isJsonArray()) {
                if (e.getAsJsonArray().isEmpty()) return true;
                for (JsonElement x : e.getAsJsonArray()) if (x.isJsonPrimitive() && allows(x.getAsString(), version)) return true;
                return false;
            }
            return !e.isJsonPrimitive() || allows(e.getAsString(), version);
        }

        static boolean allows(String predicate, String version) {
            String p = predicate.strip();
            if (p.isEmpty() || p.equals("*")) return true;
            for (String part : p.split("\\s+")) if (!one(part, version)) return false;
            return true;
        }

        private static boolean one(String p, String v) {
            if (p.equals("*")) return true;
            String op = "";
            for (String o : new String[]{">=", "<=", ">", "<", "=", "~", "^"}) if (p.startsWith(o)) { op = o; break; }
            String rest = p.substring(op.length()).strip();
            if (rest.endsWith(".x") || rest.endsWith(".*") || rest.endsWith(".X")) {
                String base = rest.substring(0, rest.length() - 2);
                return op.isEmpty() || op.equals("=") ? v.equals(base) || v.startsWith(base + ".") : compare(v, base) >= 0;
            }
            int c = compare(v, rest);
            return switch (op) {
                case ">=" -> c >= 0;
                case "<=" -> c <= 0;
                case ">" -> c > 0;
                case "<" -> c < 0;
                case "~" -> c >= 0 && compare(v, bump(rest, 1)) < 0;
                case "^" -> c >= 0 && compare(v, bump(rest, 0)) < 0;
                default -> c == 0;
            };
        }

        /** "1.21.4" with part {@code i} raised by one and the rest dropped ("1.22" for i = 1). */
        private static String bump(String v, int i) {
            String[] n = core(v).split("\\.");
            StringBuilder b = new StringBuilder();
            for (int k = 0; k <= i; k++) {
                long x = k < n.length ? num(n[k]) : 0;
                if (k == i) x++;
                if (k > 0) b.append('.');
                b.append(x);
            }
            return b.toString();
        }

        /** Compares versions part by part; a pre-release ("1.21.11-rc.1") sorts before its release. */
        static int compare(String a, String b) {
            String[] x = core(a).split("\\."), y = core(b).split("\\.");
            for (int i = 0; i < Math.max(x.length, y.length); i++) {
                long p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
                if (p != q) return Long.compare(p, q);
            }
            boolean pa = a.contains("-"), pb = b.contains("-");
            return pa == pb ? 0 : pa ? -1 : 1;
        }

        private static String core(String v) {
            String s = v.strip();
            int cut = s.length();
            for (char c : new char[]{'-', '+'}) { int k = s.indexOf(c); if (k >= 0) cut = Math.min(cut, k); }
            return s.substring(0, cut);
        }

        private static long num(String s) {
            try { return Long.parseLong(s.replaceAll("\\D.*$", "")); } catch (NumberFormatException e) { return 0; }
        }

        /** The predicate in words for a message ("1.20.1", "1.21.4 or newer"). */
        static String describe(JsonElement e) {
            String s = e.isJsonArray() ? (e.getAsJsonArray().isEmpty() ? "*" : e.getAsJsonArray().get(0).getAsString()) : e.getAsString();
            s = s.strip();
            if (s.startsWith(">=")) return s.substring(2).strip() + " or newer";
            if (s.startsWith("~") || s.startsWith("=") || s.startsWith("^")) return s.substring(1).strip();
            if (s.startsWith("<")) return "versions before " + s.replaceAll("^<=?", "").strip();
            return s.split("\\s+")[0];
        }
    }
}
