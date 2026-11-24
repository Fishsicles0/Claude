package net.evadeclient.gui.title;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.social.SocialJoin;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Discover servers: a curated list served by evadeclient.com ({@code GET /api/discover}), shown from a Discover button
 * on the title screen. Each server is pinged by the game itself for players and ping; "Evade-enhanced" marks servers
 * that run the Evade Server API. Joining is always the player's click plus a confirmation, never automatic.
 */
public final class Discover {
    public static final String URL = "https://evadeclient.com/api/discover";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    private static final long CACHE_MS = 30 * 60_000L;
    static final int MAX = 24;

    /** One curated server. */
    public record Server(String name, String address, String description, List<String> tags, boolean evade) {}

    private static volatile List<Server> cached;
    private static volatile long fetchedAt;
    private static volatile CompletableFuture<List<Server>> inFlight;
    private static boolean registered;

    private Discover() {}

    /** Adds the Discover button to the title screens. Safe to call more than once. */
    public static synchronized void init() {
        if (registered) return;
        registered = true;
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            Tooltip tip = Tooltip.of(Text.literal("Servers picked by Evade, with live player counts. You choose if and when to join."));
            if (screen instanceof net.evadeclient.gui.EvadeTitleScreen) {
                // The Evade menu: a quiet link in the top-right corner, in the menu's own style.
                String label = "Discover servers";
                int lw = TitleArt.width(label, net.evadeclient.util.UiFont.Role.SMALL) + 16;
                TitleButton link = new TitleButton(w - 10 - lw, 8, lw, 12, Text.literal(label), TitleButton.Style.LINK, "server",
                        () -> client.setScreen(new DiscoverScreen(screen)));
                link.setTooltip(tip);
                Screens.getButtons(screen).add(link);
            } else if (screen instanceof TitleScreen) {
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("Discover"), b -> client.setScreen(new DiscoverScreen(screen)))
                        .dimensions(80, 6, 70, 20).tooltip(tip).build());
            }
        });
    }

    /** The list, from cache when fresh, else fetched. Completes with an empty list when evadeclient.com can't be reached. */
    public static CompletableFuture<List<Server>> list() {
        List<Server> c = cached;
        if (c != null && System.currentTimeMillis() - fetchedAt < CACHE_MS) return CompletableFuture.completedFuture(c);
        CompletableFuture<List<Server>> f = inFlight;
        if (f != null) return f;
        HttpRequest req = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(10))
                .header("user-agent", "EvadeClient/" + EvadeClient.VERSION).GET().build();
        f = HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).handle((r, err) -> {
            inFlight = null;
            if (err != null || r.statusCode() != 200) return c == null ? List.<Server>of() : c;
            try {
                List<Server> parsed = parse(JsonParser.parseString(r.body()).getAsJsonObject());
                cached = parsed;
                fetchedAt = System.currentTimeMillis();
                return parsed;
            } catch (RuntimeException e) {
                EvadeClient.LOG.warn("Discover: bad list from evadeclient.com", e);
                return c == null ? List.<Server>of() : c;
            }
        });
        inFlight = f;
        return f;
    }

    /** Reads {"servers": [...]}, skipping entries without a valid public address; at most {@link #MAX}. */
    public static List<Server> parse(JsonObject o) {
        List<Server> out = new ArrayList<>();
        if (!o.has("servers") || !o.get("servers").isJsonArray()) return out;
        for (JsonElement e : o.getAsJsonArray("servers")) {
            if (out.size() >= MAX || !e.isJsonObject()) continue;
            JsonObject s = e.getAsJsonObject();
            String address = str(s, "address", 120).toLowerCase(Locale.ROOT);
            String name = str(s, "name", 32);
            if (name.isEmpty() || !SocialJoin.validAddress(address)) continue;
            List<String> tags = new ArrayList<>();
            if (s.has("tags") && s.get("tags").isJsonArray()) for (JsonElement t : s.getAsJsonArray("tags")) {
                if (t.isJsonPrimitive() && tags.size() < 4) tags.add(t.getAsString().replaceAll("[^A-Za-z0-9 -]", "").strip());
            }
            out.add(new Server(name, address, str(s, "description", 90), List.copyOf(tags),
                    s.has("evade") && s.get("evade").isJsonPrimitive() && s.get("evade").getAsBoolean()));
        }
        return List.copyOf(out);
    }

    private static String str(JsonObject o, String k, int max) {
        if (!o.has(k) || !o.get(k).isJsonPrimitive()) return "";
        String s = o.get(k).getAsString().replaceAll("[\\p{Cntrl}§]", " ").strip();
        return s.length() > max ? s.substring(0, max) : s;
    }
}
