package net.evadeclient.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.Module;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.Setting;
import net.evadeclient.util.Notifications;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.network.ServerAddress;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Named config presets. Each profile is a full snapshot of every module's state, settings, keybinds and HUD
 * layout. The active profile stays in sync with your live edits, and switching saves the current one before
 * loading the next. Profiles can be linked to servers (switched in when you join one, and back when you leave)
 * and shared as copy-paste codes.
 */
public class ProfileManager {
    private final ConfigManager config;
    private final Path root = FabricLoader.getInstance().getConfigDir().resolve("evade");
    private final Path dir = root.resolve("profiles");
    private final Path pointer = dir.resolve("active.txt");
    /** Server links ({"address": "profile"}); outside the profiles folder so they can never pass for a profile. */
    private final Path servers = root.resolve("servers.json");
    /** Where server links were kept before, where they showed up as a profile called "servers". */
    private final Path legacyServers = dir.resolve("servers.json");
    /**
     * The profile to go back to after a server-linked session. Written while a server's profile is in, so closing
     * the game on that server still puts your own profile back on the next start.
     */
    private final Path returnFile = root.resolve("profile-return.txt");

    /** The active profile's name (cached; the pointer file is only read once). */
    private String active;
    /** Server links, keyed by {@link #normServer} address (cached; read once, written on change). */
    private Map<String, String> links;
    /** The profile list, re-read from disk at most once a second (the menu asks every frame). */
    private List<String> listCache;
    private long listReadAt;
    /** The profile that was active before joining a linked server switched it out; null when none did. */
    private String autoSwitchedFrom;
    private boolean eventsRegistered;

    public ProfileManager(ConfigManager config) { this.config = config; }

    /** After config.json has loaded, seed a Default profile or apply the active one over it. */
    public void init() {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            EvadeClient.LOG.warn("Couldn't create the profiles folder {}", dir, e);
        }
        migrateLegacyFiles();
        registerEvents();
        String back = readReturn();
        clearReturn();
        if (list().isEmpty()) {
            config.saveTo(file("Default"));
            setActive("Default");
            invalidateList();
            return;
        }
        String a = active();
        // The game closed while a server's linked profile was in: go back to the one you had before it.
        if (back != null && !back.equals(a) && exists(back)) {
            setActive(back);
            a = back;
        }
        if (exists(a)) {
            config.loadFrom(file(a));
        } else {
            // The active profile's file is gone: recreate it from config.json so it shows up in the list again.
            config.saveTo(file(a));
            invalidateList();
        }
    }

    /** Leaving a server puts back the profile you had before a linked server switched it out. */
    private void registerEvents() {
        if (eventsRegistered) return;
        eventsRegistered = true;
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            String back = onLeaveServer();
            if (back != null) Notifications.push("Profile", "Switched back to " + back, true);
        }));
    }

    // ------------------------------------------------------------------ profiles

    /** Profile names, Default first, then alphabetical. */
    public List<String> list() {
        long now = System.nanoTime();
        if (listCache == null || now - listReadAt > 1_000_000_000L) {
            listCache = scan();
            listReadAt = now;
        }
        return new ArrayList<>(listCache);
    }

    private List<String> scan() {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
             .map(p -> p.getFileName().toString())
             .filter(n -> n.endsWith(".json") && n.length() > 5)
             .map(n -> n.substring(0, n.length() - 5))
             // Only names a profile can have: reserved names such as "servers" are never profiles.
             .filter(n -> n.equals(sanitize(n)))
             .sorted(String.CASE_INSENSITIVE_ORDER)
             .forEach(out::add);
        } catch (Exception ignored) {
        }
        if (out.remove("Default")) out.add(0, "Default");
        return out;
    }

    private void invalidateList() { listCache = null; }

    public String active() {
        if (active == null) {
            String a = "";
            try {
                if (Files.exists(pointer)) a = Files.readString(pointer, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
            a = sanitize(a);
            active = a.isEmpty() ? "Default" : a;
        }
        return active;
    }

    private void setActive(String name) {
        active = sanitize(name);
        try {
            ConfigManager.writeAtomic(pointer, active);
        } catch (IOException e) {
            EvadeClient.LOG.warn("Couldn't save the active profile", e);
        }
    }

    public Path file(String name) { return dir.resolve(sanitize(name) + ".json"); }

    public boolean exists(String name) {
        String clean = sanitize(name);
        return !clean.isEmpty() && Files.isRegularFile(file(clean));
    }

    /** Keep the active profile in step with a live save (called from ConfigManager.save with the saved JSON). */
    void mirror(String json) {
        Path f = file(active());
        boolean created = !Files.exists(f);
        try {
            ConfigManager.writeAtomic(f, json);
        } catch (IOException e) {
            EvadeClient.LOG.warn("Couldn't save profile {}", active(), e);
        }
        if (created) invalidateList();
    }

    /** Keep the active profile in step with every live save. */
    public void mirrorFrom(Path live) {
        try {
            mirror(Files.readString(live, StandardCharsets.UTF_8));
        } catch (IOException e) {
            EvadeClient.LOG.warn("Couldn't copy {} to the active profile", live, e);
        }
    }

    /**
     * Persist the current profile, then load {@code name} and make it active. A profile you pick yourself stays
     * when you leave a server, even if joining it had switched profiles. Returns whether it switched.
     */
    public boolean switchTo(String name) {
        if (name == null) return false;
        String clean = sanitize(name);
        if (clean.equals(active()) || !exists(clean)) return false;
        if (!load(clean)) return false;
        forgetReturn();
        return true;
    }

    /** Save the current profile, then apply {@code name} (which exists). False, with a toast, if it can't be read. */
    private boolean load(String name) {
        JsonObject cfg = config.read(file(name));
        if (cfg == null) {
            Notifications.push("Profile", "Couldn't read " + name, false);
            return false;
        }
        config.saveTo(file(active()));
        setActive(name);
        config.apply(cfg);
        config.save();
        return true;
    }

    /** Save the current state as a new profile and switch to it. Returns false if the name is taken or blank. */
    public boolean create(String name) {
        String clean = sanitize(name);
        if (clean.isEmpty() || exists(clean)) return false;
        config.saveTo(file(clean));
        setActive(clean);
        invalidateList();
        forgetReturn();
        return true;
    }

    /** Delete a profile. The active profile and the last remaining profile can't be deleted. */
    public boolean delete(String name) {
        if (name == null) return false;
        String clean = sanitize(name);
        if (clean.isEmpty() || clean.equals(active()) || !exists(clean) || list().size() <= 1) return false;
        try {
            Files.deleteIfExists(file(clean));
        } catch (IOException e) {
            EvadeClient.LOG.warn("Couldn't delete profile {}", clean, e);
            return false;
        }
        invalidateList();
        if (links().values().removeIf(clean::equals)) saveLinks();
        if (clean.equals(autoSwitchedFrom)) forgetReturn();
        return true;
    }

    /**
     * A filesystem-safe profile name: letters, digits, spaces, dashes and underscores, trimmed, at most 24
     * characters. Names a profile can't have ("servers", "active", and names Windows reserves such as CON or
     * COM1) get " profile" added.
     */
    public static String sanitize(String name) {
        if (name == null) return "";
        String s = name.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        if (s.length() > 24) s = s.substring(0, 24).trim();
        if (isReserved(s)) s = s + " profile";
        return s;
    }

    private static boolean isReserved(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return switch (n) {
            case "servers", "active", "con", "prn", "aux", "nul" -> true;
            default -> n.length() == 4 && (n.startsWith("com") || n.startsWith("lpt")) && n.charAt(3) >= '1' && n.charAt(3) <= '9';
        };
    }

    /**
     * One-time clean-up of files from older versions: server links move out of the profiles folder, and profile
     * files the menu couldn't address (reserved names, characters a name can't have) get the name they'd get today.
     */
    private void migrateLegacyFiles() {
        boolean linksStuck = false;
        if (Files.isRegularFile(legacyServers)) {
            JsonObject legacy = config.read(legacyServers);
            if (legacy != null && isLinkMap(legacy)) {
                try {
                    if (!Files.exists(servers)) {
                        Files.move(legacyServers, servers);
                    } else {
                        JsonObject current = config.read(servers);
                        if (current == null || !isLinkMap(current)) current = new JsonObject();
                        for (var e : legacy.entrySet()) if (!current.has(e.getKey())) current.add(e.getKey(), e.getValue());
                        ConfigManager.writeAtomic(servers, ConfigManager.pretty(current));
                        Files.delete(legacyServers);
                    }
                } catch (IOException e) {
                    linksStuck = true; // left where it is (list() still never shows it), and never renamed below
                    EvadeClient.LOG.warn("Couldn't move the server links to {}", servers, e);
                }
            }
            // Otherwise it is a profile someone named "servers": renamed with the others below.
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                String fn = p.getFileName().toString();
                if (!fn.endsWith(".json") || !Files.isRegularFile(p)) continue;
                if (linksStuck && sameFile(p, legacyServers)) continue;
                String n = fn.substring(0, fn.length() - 5);
                String clean = sanitize(n);
                if (clean.isEmpty() || clean.equals(n) || Files.exists(file(clean))) continue;
                try {
                    Files.move(p, file(clean));
                    EvadeClient.LOG.info("Renamed profile \"{}\" to \"{}\"", n, clean);
                } catch (IOException e) {
                    EvadeClient.LOG.warn("Couldn't rename profile \"{}\"", n, e);
                }
            }
        } catch (Exception e) {
            EvadeClient.LOG.warn("Couldn't tidy the profiles folder", e);
        }
        invalidateList();
    }

    private static boolean sameFile(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isLinkMap(JsonObject obj) {
        for (var e : obj.entrySet()) {
            if (!e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isString()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ share codes

    private static final String CODE_PREFIX = "EVADE1:";
    /**
     * Version of the JSON inside a code. 2: only what differs from a fresh install, without keybinds or hidden
     * settings. Codes with a higher version are refused rather than half-read.
     */
    private static final int CODE_VERSION = 2;
    private static final Pattern CODE_PREFIX_PATTERN = Pattern.compile("(?i)EVADE(\\d{1,3}):");
    private static final int MAX_CODE_CHARS = 400_000, MAX_JSON_BYTES = 1_000_000;
    /** Longest text value, and most unknown keys per module, kept from an imported code. */
    private static final int MAX_IMPORTED_STRING = 256, MAX_UNKNOWN_KEYS = 32;

    /** Why a code couldn't be imported, with a ready-made toast. */
    public enum ImportError {
        EMPTY("Nothing to import", "Copy an Evade profile code first"),
        NOT_A_CODE("Not a profile code", "Copy an Evade profile code first"),
        /** An Evade Launcher instance code (EVI1-, or EVADE1- from launchers before 2.5): a different kind of code. */
        INSTANCE_CODE("That's an instance code", "Paste it in the Evade Launcher: Mods, Modpacks & sharing"),
        INVALID("Invalid profile code", "It looks damaged or cut off; copy it again"),
        TOO_LARGE("Invalid profile code", "That code is too large to be a profile"),
        NEWER("Profile from a newer Evade", "Update Evade to import this code"),
        SAVE_FAILED("Couldn't import", "The profile couldn't be saved");

        /** Toast title and text for this failure. */
        public final String title, detail;

        ImportError(String title, String detail) {
            this.title = title;
            this.detail = detail;
        }
    }

    /** The outcome of {@link #importProfile}: the new profile's name, or why there is none. */
    public record ImportResult(String name, ImportError error) {
        public boolean ok() { return error == null; }

        static ImportResult fail(ImportError error) { return new ImportResult(null, error); }
    }

    /**
     * A copy-paste code for a profile: its name and only the settings that differ from a fresh install, gzipped
     * and base64url-encoded. Keybinds and hidden settings (state a module remembers for you, such as the last
     * server joined) stay on this PC. Null if the profile can't be read.
     */
    public String shareCode(String name) {
        try {
            String clean = sanitize(name);
            if (clean.equals(active())) config.saveTo(file(clean)); // include unsaved tweaks
            if (!exists(clean)) return null;
            JsonObject cfg = config.read(file(clean));
            if (cfg == null) return null;
            config.migrate(cfg);
            JsonObject json = new JsonObject();
            json.addProperty("v", CODE_VERSION);
            json.addProperty("name", clean);
            json.add("config", sparse(cfg));
            return CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(gzip(json.toString()));
        } catch (Exception e) {
            EvadeClient.LOG.warn("Couldn't make a share code for {}", name, e);
            return null;
        }
    }

    /** The part of a (current-format) config that differs from a fresh install, minus keybinds and hidden settings. */
    private JsonObject sparse(JsonObject cfg) {
        JsonObject out = new JsonObject();
        out.addProperty(ConfigManager.FORMAT_KEY, ConfigManager.format(cfg));
        for (Module m : EvadeClient.modules.all()) {
            JsonObject src = ConfigManager.object(cfg, m.getName());
            if (src == null) continue;
            JsonObject e = new JsonObject();
            boolean defOn = config.defaultEnabled(m);
            boolean on = ConfigManager.bool(src.get("enabled"), defOn);
            if (on != defOn) e.addProperty("enabled", on);
            JsonObject settings = ConfigManager.object(src, "settings");
            if (settings != null) {
                JsonObject changed = new JsonObject();
                for (Setting<?> s : m.getSettings()) {
                    if (!travels(s)) continue;
                    JsonElement v = settings.get(s.getName());
                    if (v == null || s.isDefaultJson(v)) continue;
                    changed.add(s.getName(), s.normalizeJson(v));
                }
                if (!changed.isEmpty()) e.add("settings", changed);
            }
            if (m instanceof HudElementModule h && src.has("x") && src.has("y")) {
                int[] def = config.defaultPosition(h);
                int x = ConfigManager.coord(src.get("x"), def[0]), y = ConfigManager.coord(src.get("y"), def[1]);
                if (x != def[0] || y != def[1]) {
                    e.addProperty("x", x);
                    e.addProperty("y", y);
                }
            }
            if (!e.isEmpty()) out.add(m.getName(), e);
        }
        return out;
    }

    /** Whether a setting goes into share codes: not hidden state and not a keybind (those stay with the importer). */
    static boolean travels(Setting<?> s) {
        return !s.isHidden() && !(s instanceof KeySetting);
    }

    static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes) {
            { def.setLevel(Deflater.BEST_COMPRESSION); }
        }) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    /** Import a share code as a new profile (named after the original, numbered if taken). Returns its name, or null. */
    public String importCode(String code) {
        return importProfile(code).name();
    }

    /**
     * Import a share code as a new profile (named after the original, numbered if taken). The code may be
     * surrounded by other text and broken over lines, as chat apps tend to do. Everything in it is checked: unknown
     * modules are dropped, values are clamped and capped, and your own keybinds and hidden settings are kept.
     */
    public ImportResult importProfile(String code) {
        if (code == null || code.isBlank()) return ImportResult.fail(ImportError.EMPTY);
        if (code.length() > MAX_CODE_CHARS * 4) return ImportResult.fail(ImportError.TOO_LARGE);
        Matcher m = CODE_PREFIX_PATTERN.matcher(code);
        if (!m.find()) return ImportResult.fail(code.contains("EVI1-") || code.contains("EVADE1-") ? ImportError.INSTANCE_CODE : ImportError.NOT_A_CODE);
        int version = Integer.parseInt(m.group(1));
        if (version > 1) return ImportResult.fail(ImportError.NEWER);
        if (version < 1) return ImportResult.fail(ImportError.NOT_A_CODE);

        // Chat apps break long codes over lines, and people paste them with words around them. The code is the
        // run of base64 pieces after the prefix: try all of them joined, then fewer, until one decodes.
        List<String> parts = payloadParts(code, m.end());
        if (parts.isEmpty()) return ImportResult.fail(ImportError.INVALID);
        JsonObject json = null;
        for (int k = parts.size(); k >= 1 && json == null; k--) {
            String payload = String.join("", parts.subList(0, k));
            if (payload.length() > MAX_CODE_CHARS) {
                if (k == 1) return ImportResult.fail(ImportError.TOO_LARGE);
                continue;
            }
            Decoded d = decode(payload);
            if (d.json() != null) json = d.json();
            else if (d.error() != ImportError.INVALID) return ImportResult.fail(d.error());
        }
        if (json == null) return ImportResult.fail(ImportError.INVALID);
        JsonObject cfg = ConfigManager.object(json, "config");

        JsonObject clean;
        try {
            clean = cleanImported(cfg);
        } catch (RuntimeException e) {
            EvadeClient.LOG.warn("Couldn't read an imported profile", e);
            return ImportResult.fail(ImportError.INVALID);
        }

        JsonElement n = json.get("name");
        String base = sanitize(n != null && n.isJsonPrimitive() ? n.getAsString() : "");
        if (base.isEmpty()) base = "Imported";
        String name = base;
        String stem = (base.length() > 20 ? base.substring(0, 20) : base).trim();
        for (int i = 2; exists(name); i++) {
            if (i > 999) return ImportResult.fail(ImportError.SAVE_FAILED);
            name = sanitize(stem + " " + i);
        }
        try {
            ConfigManager.writeAtomic(file(name), ConfigManager.pretty(clean));
        } catch (IOException e) {
            EvadeClient.LOG.warn("Couldn't save imported profile {}", name, e);
            return ImportResult.fail(ImportError.SAVE_FAILED);
        }
        invalidateList();
        return new ImportResult(name, null);
    }

    /** The base64 pieces after a code's prefix, split where there was whitespace, up to the first other character. */
    static List<String> payloadParts(String text, int from) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = from; i < text.length() && parts.size() < 64; i++) {
            char c = text.charAt(i);
            boolean b64 = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '+' || c == '/' || c == '=';
            if (b64) {
                cur.append(c);
            } else if (Character.isWhitespace(c)) {
                if (!cur.isEmpty()) { parts.add(cur.toString()); cur.setLength(0); }
            } else {
                break;
            }
        }
        if (!cur.isEmpty() && parts.size() < 64) parts.add(cur.toString());
        return parts;
    }

    private record Decoded(JsonObject json, ImportError error) {}

    /** A code's payload as its JSON ({"v", "name", "config"}), or why it isn't one. */
    private static Decoded decode(String payload) {
        byte[] raw = decodeBase64(payload);
        if (raw == null) return new Decoded(null, ImportError.INVALID);
        String text;
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            byte[] bytes = gz.readNBytes(MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES) return new Decoded(null, ImportError.TOO_LARGE);
            text = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new Decoded(null, ImportError.INVALID); // cut off, or not gzip at all
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) return new Decoded(null, ImportError.INVALID);
            JsonObject json = parsed.getAsJsonObject();
            if (ConfigManager.object(json, "config") == null) return new Decoded(null, ImportError.INVALID);
            JsonElement v = json.get("v");
            if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && v.getAsDouble() > CODE_VERSION)
                return new Decoded(null, ImportError.NEWER);
            return new Decoded(json, null);
        } catch (RuntimeException e) {
            return new Decoded(null, ImportError.INVALID);
        }
    }

    /** Base64url (what codes use), falling back to standard Base64 for codes that went through other tools. */
    static byte[] decodeBase64(String payload) {
        try {
            return Base64.getUrlDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            try {
                return Base64.getDecoder().decode(payload);
            } catch (IllegalArgumentException e2) {
                return null;
            }
        }
    }

    /**
     * A complete, checked profile from an imported config: every module this client has, with the code's state
     * where it has one and the fresh-install state where it doesn't. Keybinds and hidden settings are the
     * importer's own. Unknown modules are dropped; unknown setting keys (old names a module may migrate) are kept
     * only as short plain values.
     */
    private JsonObject cleanImported(JsonObject cfg) {
        config.migrate(cfg);
        JsonObject out = new JsonObject();
        out.addProperty(ConfigManager.FORMAT_KEY, ConfigManager.format(cfg));
        for (Module m : EvadeClient.modules.all()) {
            JsonObject src = ConfigManager.object(cfg, m.getName());
            JsonObject e = new JsonObject();
            e.addProperty("enabled", ConfigManager.bool(src == null ? null : src.get("enabled"), config.defaultEnabled(m)));
            e.addProperty("key", m.getKey()); // codes never carry keybinds: yours stay
            if (m.isHoldBind()) e.addProperty("bindMode", "hold");
            if (m.getBindMods() != 0) e.addProperty("bindMods", m.getBindMods());
            JsonObject from = ConfigManager.object(src, "settings");
            JsonObject settings = new JsonObject();
            Set<String> known = new HashSet<>();
            for (Setting<?> s : m.getSettings()) {
                known.add(s.getName());
                JsonElement v = from == null ? null : from.get(s.getName());
                JsonElement value;
                if (!travels(s)) value = s.toJson();
                else if (v == null) value = s.defaultJson();
                else {
                    // Short plain values are kept as written (loading checks them; a module may migrate an old one),
                    // anything else goes through the setting: long text is cut, junk becomes the default.
                    value = bounded(v);
                    if (value == null) value = s.normalizeJson(v);
                }
                settings.add(s.getName(), value);
            }
            if (from != null) {
                int extra = 0;
                for (var kv : from.entrySet()) {
                    if (known.contains(kv.getKey()) || kv.getKey().length() > 64 || extra >= MAX_UNKNOWN_KEYS) continue;
                    JsonElement b = bounded(kv.getValue());
                    if (b == null) continue;
                    settings.add(kv.getKey(), b);
                    extra++;
                }
            }
            e.add("settings", settings);
            if (m instanceof HudElementModule h) {
                int[] def = config.defaultPosition(h);
                boolean has = src != null && src.has("x") && src.has("y");
                e.addProperty("x", has ? ConfigManager.coord(src.get("x"), def[0]) : def[0]);
                e.addProperty("y", has ? ConfigManager.coord(src.get("y"), def[1]) : def[1]);
            }
            out.add(m.getName(), e);
        }
        return out;
    }

    /** {@code v} if it is a short plain value (a boolean, a number, or text up to 256 characters), else null. */
    static JsonElement bounded(JsonElement v) {
        if (v == null || !v.isJsonPrimitive()) return null;
        JsonPrimitive p = v.getAsJsonPrimitive();
        if (p.isString()) return p.getAsString().length() <= MAX_IMPORTED_STRING ? p : null;
        if (p.isNumber()) return p.getAsString().length() <= 32 ? p : null;
        return p;
    }

    // ------------------------------------------------------------------ per-server profiles

    private Map<String, String> links() {
        if (links == null) {
            Map<String, String> out = new TreeMap<>();
            boolean changed = false;
            JsonObject saved = Files.exists(servers) ? config.read(servers) : null;
            if (saved != null) {
                for (var e : saved.entrySet()) {
                    String srv = normServer(e.getKey());
                    JsonElement v = e.getValue();
                    String raw = v.isJsonPrimitive() ? v.getAsString() : "";
                    String prof = sanitize(raw);
                    // Old entries such as "play.example.net:25565" or "Play.Example.net." are re-keyed once.
                    if (!srv.equals(e.getKey()) || !prof.equals(raw)) changed = true;
                    if (srv.isEmpty() || prof.isEmpty()) continue;
                    if (out.putIfAbsent(srv, prof) != null) changed = true;
                }
            }
            links = out;
            if (changed) saveLinks();
        }
        return links;
    }

    private void saveLinks() {
        JsonObject obj = new JsonObject();
        links().forEach(obj::addProperty);
        try {
            ConfigManager.writeAtomic(servers, ConfigManager.pretty(obj));
        } catch (IOException e) {
            EvadeClient.LOG.warn("Couldn't save the server links", e);
        }
    }

    /**
     * A server address as links are keyed: lower-case host without a trailing dot, with ":port" only when it
     * isn't the default 25565, so "Play.Example.net.", "play.example.net" and "play.example.net:25565" are one
     * server. Addresses Minecraft can't parse are just trimmed and lower-cased.
     */
    public static String normServer(String address) {
        if (address == null) return "";
        String raw = address.trim();
        if (raw.isEmpty()) return "";
        String fallback = raw.toLowerCase(Locale.ROOT);
        try {
            ServerAddress parsed = ServerAddress.parse(raw);
            String host = parsed.getAddress().toLowerCase(Locale.ROOT);
            // parse() answers "server.invalid" for anything it can't read.
            if (host.equals("server.invalid") && !fallback.startsWith("server.invalid")) return fallback;
            while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (host.isEmpty()) return fallback;
            if (host.indexOf(':') >= 0) host = "[" + host + "]"; // IPv6
            int port = parsed.getPort();
            return port == 25565 ? host : host + ":" + port;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Whether joining {@code address} switches to {@code profile}. */
    public boolean isLinked(String profile, String address) {
        String srv = normServer(address);
        return !srv.isEmpty() && profile != null && sanitize(profile).equals(links().get(srv));
    }

    /** The servers that switch to this profile when you join them. */
    public List<String> serversFor(String profile) {
        String clean = sanitize(profile);
        List<String> out = new ArrayList<>();
        links().forEach((srv, prof) -> { if (prof.equals(clean)) out.add(srv); });
        return out;
    }

    /** Link this profile to a server (or unlink it if it already is). Returns true if now linked. */
    public boolean toggleServer(String profile, String address) {
        String srv = normServer(address);
        String clean = sanitize(profile);
        if (srv.isEmpty() || clean.isEmpty()) return false;
        Map<String, String> l = links();
        boolean linked = clean.equals(l.get(srv));
        if (linked) l.remove(srv); else l.put(srv, clean);
        saveLinks();
        return !linked;
    }

    /**
     * Called when you join a server: switch to its linked profile, if any, remembering the one you had so leaving
     * puts it back. Joining a server without a link puts it back too. Returns the profile switched to.
     */
    public String onJoinServer(String address) {
        String prof = links().get(normServer(address));
        if (prof == null || !exists(prof)) {
            String back = onLeaveServer();
            if (back != null) Notifications.push("Profile", "Switched back to " + back, true);
            return null;
        }
        if (prof.equals(active())) return null;
        // Hopping between linked servers keeps the profile you had before the first one.
        String from = autoSwitchedFrom == null ? active() : autoSwitchedFrom;
        if (!load(prof)) return null;
        autoSwitchedFrom = from;
        writeReturn(from);
        return prof;
    }

    /**
     * Called when you leave a server: if joining it switched profiles, switch back to the one you had before.
     * Returns that profile's name, or null if nothing changed.
     */
    public String onLeaveServer() {
        String back = autoSwitchedFrom;
        forgetReturn();
        if (back == null || back.equals(active()) || !exists(back)) return null;
        return load(back) ? back : null;
    }

    private void forgetReturn() {
        autoSwitchedFrom = null;
        clearReturn();
    }

    private String readReturn() {
        try {
            if (!Files.exists(returnFile)) return null;
            String name = sanitize(Files.readString(returnFile, StandardCharsets.UTF_8));
            return name.isEmpty() ? null : name;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeReturn(String name) {
        try {
            ConfigManager.writeAtomic(returnFile, name);
        } catch (IOException e) {
            EvadeClient.LOG.warn("Couldn't remember the profile to switch back to", e);
        }
    }

    private void clearReturn() {
        try {
            Files.deleteIfExists(returnFile);
        } catch (IOException ignored) {
        }
    }
}
