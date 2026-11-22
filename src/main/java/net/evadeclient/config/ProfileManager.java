package net.evadeclient.config;

import net.evadeclient.EvadeClient;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Named config presets, like Lunar's profiles. Each profile is a full snapshot of every module's
 * state, settings, keybinds and HUD layout. The active profile stays in sync with your live edits,
 * and switching saves the current one before loading the next.
 */
public class ProfileManager {
    private final ConfigManager config;
    private final Path dir = FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("profiles");
    private final Path pointer = dir.resolve("active.txt");

    public ProfileManager(ConfigManager config) { this.config = config; }

    /** After config.json has loaded, seed a Default profile or apply the active one over it. */
    public void init() {
        try { Files.createDirectories(dir); } catch (Exception ignored) {}
        if (list().isEmpty()) {
            config.saveTo(file("Default"));
            setActive("Default");
        } else {
            String a = active();
            if (Files.exists(file(a))) config.loadFrom(file(a));
        }
    }

    /** Profile names, Default first, then alphabetical. */
    public List<String> list() {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.toString().endsWith(".json"))
             .map(p -> p.getFileName().toString())
             .map(n -> n.substring(0, n.length() - 5))
             .sorted(String.CASE_INSENSITIVE_ORDER)
             .forEach(out::add);
        } catch (Exception ignored) {}
        out.remove("Default");
        if (Files.exists(file("Default"))) out.add(0, "Default");
        return out;
    }

    public String active() {
        try { if (Files.exists(pointer)) return Files.readString(pointer).trim(); } catch (Exception ignored) {}
        return "Default";
    }

    private void setActive(String name) {
        try { Files.createDirectories(dir); Files.writeString(pointer, name); } catch (Exception ignored) {}
    }

    public Path file(String name) { return dir.resolve(sanitize(name) + ".json"); }

    public boolean exists(String name) { return Files.exists(file(name)); }

    /** Keep the active profile in step with every live save (called from ConfigManager.save). */
    public void mirrorFrom(Path live) {
        try { Files.copy(live, file(active()), java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignored) {}
    }

    /** Persist the current profile, then load {@code name} and make it active. */
    public void switchTo(String name) {
        if (name == null || name.equals(active()) || !exists(name)) return;
        config.saveTo(file(active()));
        setActive(name);
        config.loadFrom(file(name));
        config.save();
    }

    /** Save the current state as a new profile and switch to it. Returns false if the name is taken or blank. */
    public boolean create(String name) {
        String clean = sanitize(name);
        if (clean.isEmpty() || exists(clean)) return false;
        config.saveTo(file(clean));
        setActive(clean);
        return true;
    }

    /** Delete a profile. The active profile and the last remaining profile can't be deleted. */
    public boolean delete(String name) {
        if (name.equals(active()) || list().size() <= 1) return false;
        try {
            Files.deleteIfExists(file(name));
            var links = serverLinks();
            if (links.values().removeIf(name::equals)) saveLinks(links);
            return true;
        } catch (Exception e) { return false; }
    }

    // ------------------------------------------------------------------ share codes

    private static final String CODE_PREFIX = "EVADE1:";

    /** A copy-paste code for a profile: its name and settings, gzipped and base64url-encoded. */
    public String shareCode(String name) {
        try {
            if (name.equals(active())) config.saveTo(file(name)); // include unsaved tweaks
            var json = new com.google.gson.JsonObject();
            json.addProperty("name", name);
            json.add("config", com.google.gson.JsonParser.parseString(Files.readString(file(name))));
            var bytes = new java.io.ByteArrayOutputStream();
            try (var gz = new java.util.zip.GZIPOutputStream(bytes)) { gz.write(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            return CODE_PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    /** Import a share code as a new profile (named after the original, numbered if taken). Returns its name, or null. */
    public String importCode(String code) {
        try {
            code = code == null ? "" : code.trim();
            if (!code.startsWith(CODE_PREFIX) || code.length() > 400_000) return null;
            byte[] raw = java.util.Base64.getUrlDecoder().decode(code.substring(CODE_PREFIX.length()));
            String text;
            try (var gz = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(raw))) {
                text = new String(gz.readNBytes(1_000_000), java.nio.charset.StandardCharsets.UTF_8);
            }
            var json = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            var cfg = json.get("config");
            if (cfg == null || !cfg.isJsonObject()) return null;
            String base = sanitize(json.has("name") ? json.get("name").getAsString() : "Imported");
            if (base.isEmpty()) base = "Imported";
            String name = base;
            for (int i = 2; exists(name); i++) name = sanitize(base.length() > 20 ? base.substring(0, 20) : base) + " " + i;
            Files.createDirectories(dir);
            Files.writeString(file(name), cfg.toString());
            return name;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ per-server profiles

    private final Path servers = dir.resolve("servers.json");

    private java.util.Map<String, String> serverLinks() {
        try {
            if (Files.exists(servers)) {
                var obj = com.google.gson.JsonParser.parseString(Files.readString(servers)).getAsJsonObject();
                var out = new java.util.TreeMap<String, String>();
                for (var e : obj.entrySet()) out.put(e.getKey(), e.getValue().getAsString());
                return out;
            }
        } catch (Exception ignored) {}
        return new java.util.TreeMap<>();
    }

    private void saveLinks(java.util.Map<String, String> links) {
        var obj = new com.google.gson.JsonObject();
        links.forEach(obj::addProperty);
        try { Files.createDirectories(dir); Files.writeString(servers, obj.toString()); } catch (Exception ignored) {}
    }

    private static String normServer(String address) { return address == null ? "" : address.trim().toLowerCase(java.util.Locale.ROOT); }

    /** The servers that switch to this profile when you join them. */
    public List<String> serversFor(String profile) {
        List<String> out = new ArrayList<>();
        serverLinks().forEach((srv, prof) -> { if (prof.equals(profile)) out.add(srv); });
        return out;
    }

    /** Link this profile to a server (or unlink it if it already is). Returns true if now linked. */
    public boolean toggleServer(String profile, String address) {
        String srv = normServer(address);
        if (srv.isEmpty()) return false;
        var links = serverLinks();
        boolean linked = profile.equals(links.get(srv));
        if (linked) links.remove(srv); else links.put(srv, profile);
        saveLinks(links);
        return !linked;
    }

    /** Called when you join a server: switch to its linked profile, if any. Returns the profile switched to. */
    public String onJoinServer(String address) {
        String prof = serverLinks().get(normServer(address));
        if (prof == null || !exists(prof) || prof.equals(active())) return null;
        switchTo(prof);
        return prof;
    }

    /** A filesystem-safe profile name: letters, digits, spaces, dashes and underscores, trimmed. */
    public static String sanitize(String name) {
        if (name == null) return "";
        String s = name.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        return s.length() > 24 ? s.substring(0, 24) : s;
    }
}
