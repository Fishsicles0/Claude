package net.evadeclient.installer;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * The player shown on Home: the Minecraft name and UUID of the account that's active in the official Minecraft
 * Launcher (launcher_accounts.json, or launcher_accounts_microsoft_store.json for the Microsoft Store launcher), and
 * their skin's face. The accounts file is read with a streaming reader that only keeps the active account's id and
 * each account's profile name and id: sign-in tokens are skipped unread and never stored.
 */
final class Profile {
    private Profile() {}

    record Account(String name, String uuid) {}

    /** The active account in {@code minecraftDir}, or null when there's none (or no file). */
    static Account read(Path minecraftDir) {
        Account best = null;
        long bestTime = -1;
        for (String f : new String[]{"launcher_accounts_microsoft_store.json", "launcher_accounts.json"}) {
            Path p = minecraftDir.resolve(f);
            if (!Files.isRegularFile(p)) continue;
            try {
                Account a = parse(Files.newBufferedReader(p, StandardCharsets.UTF_8));
                long t = Files.getLastModifiedTime(p).toMillis();
                if (a != null && t > bestTime) { best = a; bestTime = t; }
            } catch (Exception ignored) { /* unreadable: try the other file */ }
        }
        return best;
    }

    /** Reads {"accounts": {id: {"minecraftProfile": {"id","name"}}}, "activeAccountLocalId": id}; nothing else is kept. */
    static Account parse(Reader r) throws IOException {
        Map<String, Account> accounts = new HashMap<>();
        String active = null;
        try (JsonReader in = new JsonReader(r)) {
            in.beginObject();
            while (in.hasNext()) {
                String k = in.nextName();
                if (k.equals("activeAccountLocalId") && in.peek() == JsonToken.STRING) active = in.nextString();
                else if (k.equals("accounts") && in.peek() == JsonToken.BEGIN_OBJECT) {
                    in.beginObject();
                    while (in.hasNext()) {
                        String id = in.nextName();
                        Account a = account(in);
                        if (a != null) accounts.put(id, a);
                    }
                    in.endObject();
                } else in.skipValue();
            }
            in.endObject();
        }
        if (active != null && accounts.containsKey(active)) return accounts.get(active);
        return accounts.size() == 1 ? accounts.values().iterator().next() : null;
    }

    private static Account account(JsonReader in) throws IOException {
        if (in.peek() != JsonToken.BEGIN_OBJECT) { in.skipValue(); return null; }
        String name = null, uuid = null;
        in.beginObject();
        while (in.hasNext()) {
            String k = in.nextName();
            if (k.equals("minecraftProfile") && in.peek() == JsonToken.BEGIN_OBJECT) {
                in.beginObject();
                while (in.hasNext()) {
                    String pk = in.nextName();
                    if (pk.equals("name") && in.peek() == JsonToken.STRING) name = in.nextString();
                    else if (pk.equals("id") && in.peek() == JsonToken.STRING) uuid = in.nextString();
                    else in.skipValue();
                }
                in.endObject();
            } else in.skipValue(); // tokens, e-mail, etc.: never read
        }
        in.endObject();
        return name == null || name.isBlank() ? null : new Account(name, uuid == null ? "" : uuid.replace("-", ""));
    }

    /**
     * The face of {@code uuid}'s skin (64×64): from the cache if it's under a day old, else fetched (5 s timeout,
     * falling back to a second service). Null when offline and nothing is cached. Call off the UI thread.
     */
    static BufferedImage face(String uuid) {
        if (uuid == null || !uuid.matches("[0-9a-fA-F]{32}")) return null;
        Path cache = Launcher.dataDir().resolve("heads").resolve(uuid.toLowerCase() + ".png");
        try {
            if (Files.exists(cache) && System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < 86_400_000L)
                return ImageIO.read(cache.toFile());
        } catch (IOException ignored) { }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();
        for (String url : new String[]{"https://mc-heads.net/avatar/" + uuid + "/64", "https://crafatar.com/avatars/" + uuid + "?size=64&overlay"}) {
            try {
                HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                        .header("user-agent", "EvadeLauncher").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() != 200) continue;
                BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(res.body()));
                if (img == null) continue;
                try {
                    Files.createDirectories(cache.getParent());
                    Files.write(cache, res.body());
                } catch (IOException ignored) { }
                return img;
            } catch (Exception ignored) { }
        }
        try { return Files.exists(cache) ? ImageIO.read(cache.toFile()) : null; } catch (IOException e) { return null; }
    }
}
