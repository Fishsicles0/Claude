package net.evadeclient.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.misc.EvadeIcons;
import net.fabricmc.loader.api.FabricLoader;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Opt-in cloud backup of your Evade setup: config.json (every mod's settings and the HUD layout), your profiles,
 * per-server profile choices and waypoints. Nothing is uploaded until you press Back up.
 *
 * <p>The files are zipped and encrypted on this PC with AES-256-GCM, using a key made from a passphrase only you
 * know (PBKDF2-SHA256, {@link #ITERATIONS} rounds, a random salt), bound to your Minecraft account. evadeclient.com
 * stores the encrypted bytes under the account Evade Icons proves with Mojang (no password, no extra sign-in), so it
 * can't read them, and without the passphrase nobody can restore them. One backup per account, 256 KB at most,
 * deleted after 90 days without a backup or restore.
 */
public final class CloudBackup {
    public static final String API = "https://evadeclient.com/api/backup/";
    public static final int ITERATIONS = 210_000, FORMAT = 1, MAX_BYTES = 256 * 1024, MIN_PASSPHRASE = 8;
    /** Uncompressed limit when restoring (a backup can't unpack into something huge). */
    static final int MAX_UNPACKED = 8 * 1024 * 1024, MAX_FILES = 200;
    private static final Pattern PROFILE = Pattern.compile("profiles/[A-Za-z0-9 _\\-]{1,40}\\.json");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile String status = "Off (click to set up)";

    /** A failed backup step, with a message to show. */
    public static final class BackupException extends RuntimeException {
        public final int status;
        public BackupException(String message, int status) { super(message, null, false, false); this.status = status; }
    }

    private CloudBackup() {}

    /** One line for the Friends settings: "Off", "Backed up 12 Nov", ... */
    public static String statusLine() { return status; }

    static Path root() { return FabricLoader.getInstance().getConfigDir().resolve("evade"); }

    // ------------------------------------------------------------------ the files

    /** The files a backup holds, by name relative to config/evade. Reads them now (any thread). */
    public static Map<String, byte[]> collect(Path root) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (String n : new String[]{"config.json", "waypoints.json", "servers.json"}) {
            Path f = root.resolve(n);
            if (Files.isRegularFile(f)) out.put(n, Files.readAllBytes(f));
        }
        Path profiles = root.resolve("profiles");
        if (Files.isDirectory(profiles)) {
            try (Stream<Path> s = Files.list(profiles)) {
                for (Path p : s.sorted().toList()) {
                    String n = "profiles/" + p.getFileName();
                    if (Files.isRegularFile(p) && PROFILE.matcher(n).matches() && out.size() < MAX_FILES) out.put(n, Files.readAllBytes(p));
                }
            }
        }
        return out;
    }

    /** Zips {@code files}. */
    public static byte[] pack(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.setLevel(9);
            for (var e : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /**
     * Unzips a backup, keeping only the files a backup can hold (config.json, waypoints.json, servers.json and
     * profiles/NAME.json), each valid JSON, within {@link #MAX_UNPACKED} bytes and {@link #MAX_FILES} files.
     */
    public static Map<String, byte[]> unpack(byte[] zipBytes) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zip.getNextEntry()) != null) {
                String n = e.getName();
                boolean ok = n.equals("config.json") || n.equals("waypoints.json") || n.equals("servers.json") || PROFILE.matcher(n).matches();
                if (!ok || e.isDirectory()) continue;
                if (out.size() >= MAX_FILES) throw new IOException("too many files in the backup");
                ByteArrayOutputStream one = new ByteArrayOutputStream();
                int r;
                while ((r = zip.read(buf)) > 0) {
                    total += r;
                    if (total > MAX_UNPACKED) throw new IOException("the backup is too large to unpack");
                    one.write(buf, 0, r);
                }
                byte[] data = one.toByteArray();
                try {
                    JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
                } catch (RuntimeException bad) {
                    throw new IOException(n + " in the backup isn't valid");
                }
                out.put(n, data);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ encryption

    /** AES-256 key from the passphrase (PBKDF2-SHA256). */
    static SecretKeySpec key(char[] passphrase, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, 256);
        try {
            byte[] k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(k, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    /** IV (12 bytes) then AES-GCM ciphertext and tag; the account UUID is bound in as associated data. */
    public static byte[] encrypt(byte[] plain, char[] passphrase, byte[] salt, int iterations, String uuid) throws GeneralSecurityException {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations), new GCMParameterSpec(128, iv));
        c.updateAAD(("evade-backup:" + uuid).getBytes(StandardCharsets.UTF_8));
        byte[] ct = c.doFinal(plain);
        byte[] out = Arrays.copyOf(iv, 12 + ct.length);
        System.arraycopy(ct, 0, out, 12, ct.length);
        return out;
    }

    /** The reverse of {@link #encrypt}; throws for a wrong passphrase, another account's backup or damaged bytes. */
    public static byte[] decrypt(byte[] blob, char[] passphrase, byte[] salt, int iterations, String uuid) throws GeneralSecurityException {
        if (blob.length < 12 + 16) throw new GeneralSecurityException("too short");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations), new GCMParameterSpec(128, blob, 0, 12));
        c.updateAAD(("evade-backup:" + uuid).getBytes(StandardCharsets.UTF_8));
        return c.doFinal(blob, 12, blob.length - 12);
    }

    // ------------------------------------------------------------------ evadeclient.com

    private static CompletableFuture<JsonObject> call(String op, JsonObject body) {
        String token = EvadeIcons.socialToken();
        if (token == null) {
            EvadeIcons.requestSignIn();
            return CompletableFuture.failedFuture(new BackupException("Checking your Minecraft account (" + EvadeIcons.signInStatus() + "). Try again in a moment", 0));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(API + op)).timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .header("user-agent", "EvadeClient/" + EvadeClient.VERSION)
                .header("authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).handle((r, err) -> {
            if (err != null) throw new BackupException("Can't reach evadeclient.com", 0);
            JsonObject o;
            try { o = JsonParser.parseString(r.body()).getAsJsonObject(); } catch (RuntimeException e) { o = new JsonObject(); }
            if (r.statusCode() == 401) EvadeIcons.forgetSocialToken();
            if (r.statusCode() != 200) {
                String msg = o.has("error") && o.get("error").isJsonPrimitive() ? o.get("error").getAsString() : "Something went wrong (" + r.statusCode() + ")";
                throw new BackupException(msg, r.statusCode());
            }
            return o;
        });
    }

    /** The account's backup: {exists, size, updated, label, expires, ...}. */
    public static CompletableFuture<JsonObject> info() {
        return call("info", new JsonObject()).thenApply(r -> {
            JsonObject b = r.has("backup") && r.get("backup").isJsonObject() ? r.getAsJsonObject("backup") : new JsonObject();
            noteInfo(b);
            return b;
        });
    }

    private static void noteInfo(JsonObject b) {
        boolean exists = b.has("exists") && b.get("exists").getAsBoolean();
        status = exists ? "Backed up " + date(b.get("updated").getAsLong()) : "No backup yet (click)";
    }

    /** "12 Nov 2026". */
    public static String date(long ms) {
        return DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms));
    }

    /** Your account's UUID as the server knows it (32 hex), or null before you've played. */
    private static String uuid() {
        var s = EvadeClient.mc().getSession();
        return s == null || s.getUuidOrNull() == null ? null : s.getUuidOrNull().toString().replace("-", "");
    }

    /** Encrypts and uploads the current files. Completes with the encrypted size in bytes. */
    public static CompletableFuture<Integer> backup(char[] passphrase, String label) {
        if (passphrase == null || passphrase.length < MIN_PASSPHRASE)
            return CompletableFuture.failedFuture(new BackupException("Use a passphrase of at least " + MIN_PASSPHRASE + " characters", 400));
        String uuid = uuid();
        if (uuid == null) return CompletableFuture.failedFuture(new BackupException("No Minecraft account", 0));
        if (EvadeClient.config != null) EvadeClient.config.save(); // back up what's on screen
        char[] pass = passphrase.clone();
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] salt = new byte[16];
                RANDOM.nextBytes(salt);
                byte[] blob = encrypt(pack(collect(root())), pass, salt, ITERATIONS, uuid);
                if (blob.length > MAX_BYTES) throw new BackupException("Your setup is " + blob.length / 1024 + " KB packed; cloud backup holds 256 KB. Delete unused profiles and try again", 413);
                JsonObject b = new JsonObject();
                b.addProperty("blob", Base64.getEncoder().encodeToString(blob));
                b.addProperty("salt", Base64.getEncoder().encodeToString(salt));
                b.addProperty("iters", ITERATIONS);
                b.addProperty("format", FORMAT);
                b.addProperty("label", label == null ? "" : label);
                return b;
            } catch (IOException | GeneralSecurityException e) {
                throw new BackupException("Couldn't pack your files: " + e.getMessage(), 0);
            } finally {
                Arrays.fill(pass, '\0');
            }
        }).thenCompose(b -> call("put", b)).thenApply(r -> {
            JsonObject b = r.getAsJsonObject("backup");
            noteInfo(b);
            return b.get("size").getAsInt();
        });
    }

    /**
     * Downloads and decrypts the backup, keeps a copy of your current files (config/evade/before-restore.zip), writes
     * the backed-up ones and reloads the settings. Completes with the number of files restored.
     */
    public static CompletableFuture<Integer> restore(char[] passphrase) {
        String uuid = uuid();
        if (uuid == null) return CompletableFuture.failedFuture(new BackupException("No Minecraft account", 0));
        char[] pass = passphrase == null ? new char[0] : passphrase.clone();
        return call("get", new JsonObject()).thenApply(r -> {
            try {
                JsonObject info = r.getAsJsonObject("backup");
                byte[] blob = Base64.getDecoder().decode(r.get("blob").getAsString());
                byte[] salt = Base64.getDecoder().decode(info.get("salt").getAsString());
                byte[] plain;
                try {
                    plain = decrypt(blob, pass, salt, info.get("iters").getAsInt(), uuid);
                } catch (GeneralSecurityException e) {
                    throw new BackupException("Wrong passphrase", 403);
                }
                return unpack(plain);
            } catch (IOException | RuntimeException e) {
                if (e instanceof BackupException be) throw be;
                throw new BackupException("The backup couldn't be read: " + e.getMessage(), 0);
            } finally {
                Arrays.fill(pass, '\0');
            }
        }).thenApplyAsync(files -> {
            apply(files);
            return files.size();
        }, EvadeClient.mc());
    }

    /** Writes restored files and reloads what's live. Render thread. */
    static void apply(Map<String, byte[]> files) {
        Path root = root();
        try {
            Files.write(root.resolve("before-restore.zip"), pack(collect(root)));
            for (var e : files.entrySet()) ConfigManager.writeAtomic(root.resolve(e.getKey()), new String(e.getValue(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new BackupException("Couldn't write your files: " + e.getMessage(), 0);
        }
        if (EvadeClient.config != null && files.containsKey("config.json")) EvadeClient.config.load();
        reloadWaypoints();
    }

    /** Waypoints read their file once: make the next use read the restored one. */
    private static void reloadWaypoints() {
        try {
            var wp = EvadeClient.modules == null ? null : EvadeClient.modules.get(net.evadeclient.module.visuals.Waypoints.class);
            if (wp == null) return;
            var f = net.evadeclient.module.visuals.Waypoints.class.getDeclaredField("loaded");
            f.setAccessible(true);
            f.setBoolean(wp, false);
        } catch (ReflectiveOperationException | RuntimeException e) {
            EvadeClient.LOG.info("Cloud backup: waypoints are restored after a restart ({})", e.toString());
        }
    }

    /** Deletes the backup from evadeclient.com. */
    public static CompletableFuture<Boolean> delete() {
        return call("delete", new JsonObject()).thenApply(r -> {
            status = "No backup (click)";
            return r.has("deleted") && r.get("deleted").getAsBoolean();
        });
    }

    /** The message to show for a failed step. */
    public static String message(Throwable t) {
        while (t instanceof java.util.concurrent.CompletionException && t.getCause() != null) t = t.getCause();
        if (t instanceof BackupException b) return b.getMessage();
        return "Something went wrong";
    }
}
