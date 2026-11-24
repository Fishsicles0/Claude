package net.evadeclient.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.evadeclient.render.RenderContext;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.ServerRules;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.C2SPlayChannelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

/**
 * The Evade Server API: lets a server show things to Evade players, all purely visual or informational and all
 * chosen by the server. One plugin channel, {@code evade:api} (server to client), carries a raw UTF-8 JSON body
 * (no length prefix, like {@code evade:rules}): one message {@code {"type": "...", ...}} or an array of them.
 * When the player joins, and the server has registered {@code evade:hello}, Evade sends one hello on it so the server
 * knows Evade is there: {@code {"client":"evade","version":"2.0.1","api":1,"features":[...]}}.
 *
 * <p>Message types: {@code waypoint.add/remove/clear}, {@code cooldown.set/clear}, {@code notification},
 * {@code title}, {@code team.set/clear}, {@code border.set/clear}, {@code beam.add/remove},
 * {@code hologram.add/remove}, {@code rules}, {@code discord} and {@code reset}. Everything is forgotten when the
 * player disconnects or the network moves them to another server.
 *
 * <p>Nothing here can show more than the player could see for themselves: team markers and holograms need a clear
 * line of sight, the minimap only shows team mates the client already has loaded, and no message can switch a mod on.
 */
public final class ServerApi {
    public static final Identifier CHANNEL = Identifier.of("evade", "api");
    public static final Identifier HELLO_CHANNEL = Identifier.of("evade", "hello");
    /** Version of the message format; bumped only for changes that old servers would misread. */
    public static final int API_VERSION = 1;
    /** What this client understands, as sent in the hello. */
    public static final List<String> FEATURES = List.of("waypoint", "cooldown", "notification", "title", "team",
            "border", "beam", "hologram", "rules", "settings", "discord");
    /** Most messages handled from one plugin message (an array). */
    static final int MAX_BATCH = 128;
    /** Server notifications: at most this many in {@link #NOTIFY_WINDOW_MS}. */
    private static final int NOTIFY_BURST = 4;
    private static final long NOTIFY_WINDOW_MS = 2000;

    /** S2C {@code evade:api}: plain bytes, no length prefix, so any server plugin can send it. */
    public record ApiPayload(byte[] body) implements CustomPayload {
        public static final Id<ApiPayload> ID = new Id<>(CHANNEL);
        public static final PacketCodec<PacketByteBuf, ApiPayload> CODEC = PacketCodec.of(
                (p, buf) -> buf.writeBytes(p.body()),
                buf -> { byte[] b = new byte[Math.min(buf.readableBytes(), 65536)]; buf.readBytes(b); buf.skipBytes(buf.readableBytes()); return new ApiPayload(b); });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** C2S {@code evade:hello}: the same raw UTF-8 JSON format. */
    public record HelloPayload(byte[] body) implements CustomPayload {
        public static final Id<HelloPayload> ID = new Id<>(HELLO_CHANNEL);
        public static final PacketCodec<PacketByteBuf, HelloPayload> CODEC = PacketCodec.of(
                (p, buf) -> buf.writeBytes(p.body()),
                buf -> { byte[] b = new byte[Math.min(buf.readableBytes(), 65536)]; buf.readBytes(b); buf.skipBytes(buf.readableBytes()); return new HelloPayload(b); });
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** The connection the hello went out on (one hello per connection, however often channels are registered). */
    private static ClientPlayNetworkHandler helloSentOn;
    /** Server-provided Discord status, for a Discord presence mod to read; null when the server sent none. */
    private static String discordText, discordDetails;
    private static final ArrayDeque<Long> recentNotifications = new ArrayDeque<>();
    /** Bad messages logged on this connection (only the first few are). */
    private static int warnings;
    /** Drawing or tick steps that have failed once (logged once each, then left alone). */
    private static final java.util.Set<String> failed = new java.util.HashSet<>();

    private ServerApi() {}

    /** Registers the channels, connection events, tick and render hooks. Call once from the mod initializer. */
    public static void register() {
        try {
            PayloadTypeRegistry.playS2C().register(ApiPayload.ID, ApiPayload.CODEC);
            PayloadTypeRegistry.playC2S().register(HelloPayload.ID, HelloPayload.CODEC);
            ClientPlayNetworking.registerGlobalReceiver(ApiPayload.ID, (payload, ctx) -> ctx.client().execute(() -> handle(payload.body())));
        } catch (Exception e) {
            EvadeClient.LOG.warn("Server API: couldn't register {} / {}", CHANNEL, HELLO_CHANNEL, e);
        }
        // A new play connection (a join, or the network moving you to another server) starts clean and says hello.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            clear();
            sendHello(handler);
        }));
        // Servers often register their channels just after the join: say hello then (still only once).
        C2SPlayChannelEvents.REGISTER.register((handler, sender, client, channels) -> {
            if (channels.contains(HELLO_CHANNEL)) client.execute(() -> sendHello(handler));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            clear();
            helloSentOn = null;
        }));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                ApiTeams.tick();
                ApiHolograms.tick();
            } catch (Exception e) {
                failed("tick", e);
            }
        });
        // Markers and holograms sit with Evade's other world labels (under chat); the title where vanilla's goes.
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.of("evade", "server_api"),
                (ctx, tc) -> renderHud(ctx, tc.getTickProgress(true)));
        HudElementRegistry.attachElementAfter(VanillaHudElements.TITLE_AND_SUBTITLE, Identifier.of("evade", "server_title"),
                (ctx, tc) -> renderTitle(ctx));
        WorldRenderEvents.END_MAIN.register(ServerApi::renderWorld);
    }

    // ------------------------------------------------------------------ hello

    /** The hello body: {@code {"client":"evade","version":"…","api":1,"features":[…]}}. */
    public static String helloJson() {
        JsonObject o = new JsonObject();
        o.addProperty("client", "evade");
        o.addProperty("version", EvadeClient.VERSION);
        o.addProperty("api", API_VERSION);
        JsonArray f = new JsonArray();
        for (String s : FEATURES) f.add(s);
        o.add("features", f);
        return o.toString();
    }

    /** Sends the hello on {@code handler}'s connection once, and only if the server registered the channel. */
    private static void sendHello(ClientPlayNetworkHandler handler) {
        if (handler == null || helloSentOn == handler) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() != handler) return;
        try {
            if (!ClientPlayNetworking.canSend(HelloPayload.ID)) return;
            ClientPlayNetworking.send(new HelloPayload(helloJson().getBytes(StandardCharsets.UTF_8)));
            helloSentOn = handler;
        } catch (Exception e) {
            EvadeClient.LOG.warn("Server API: couldn't send hello", e);
        }
    }

    // ------------------------------------------------------------------ messages

    /** Handles a raw {@code evade:api} body (render thread). Returns how many messages were accepted. */
    public static int handle(byte[] body) {
        return handle(new String(body, StandardCharsets.UTF_8));
    }

    /**
     * Handles one message or an array of them, as JSON text (render thread). Returns how many were accepted; bad
     * JSON, unknown types and messages missing what they need are skipped.
     */
    public static int handle(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (Exception e) {
            warn("ignored a message that isn't valid JSON");
            return 0;
        }
        if (root.isJsonObject()) return handle(root.getAsJsonObject()) ? 1 : 0;
        if (!root.isJsonArray()) {
            warn("ignored a message that isn't a JSON object or array");
            return 0;
        }
        int n = 0, seen = 0;
        for (JsonElement e : root.getAsJsonArray()) {
            if (++seen > MAX_BATCH) break;
            if (e.isJsonObject() && handle(e.getAsJsonObject())) n++;
        }
        return n;
    }

    /** Handles one message object (render thread). Returns whether it was accepted. */
    public static boolean handle(JsonObject o) {
        String type = ApiJson.str(o, "type", "", 32).toLowerCase(Locale.ROOT);
        try {
            boolean ok = switch (type) {
                case "waypoint.add", "waypoint.remove", "waypoint.clear" -> ApiWaypoints.handle(type, o);
                case "cooldown.set", "cooldown.clear" -> ApiCooldowns.handle(type, o);
                case "team.set", "team.clear" -> ApiTeams.handle(type, o);
                case "border.set", "border.clear" -> ApiBorder.handle(type, o);
                case "beam.add", "beam.remove" -> ApiBeams.handle(type, o);
                case "hologram.add", "hologram.remove" -> ApiHolograms.handle(type, o);
                case "notification" -> notification(o);
                case "title" -> ApiTitle.handle(o);
                case "rules" -> rules(o);
                case "discord" -> discord(o);
                case "reset" -> { clear(); yield true; }
                default -> {
                    warn("ignored a message of unknown type \"" + type + "\"");
                    yield false;
                }
            };
            return ok;
        } catch (RuntimeException e) {
            warn("ignored a \"" + type + "\" message it couldn't read (" + e.getMessage() + ")");
            return false;
        }
    }

    /** {@code notification}: title, text, seconds (default 4, up to 15) and level (info, success or warn). */
    private static boolean notification(JsonObject o) {
        String title = ApiJson.str(o, "title", "", ApiJson.MAX_NAME);
        String text = ApiJson.str(o, "text", "", ApiJson.MAX_TEXT);
        if (title.isEmpty() && text.isEmpty()) return false;
        long now = System.currentTimeMillis();
        while (!recentNotifications.isEmpty() && now - recentNotifications.peekFirst() > NOTIFY_WINDOW_MS) recentNotifications.pollFirst();
        if (recentNotifications.size() >= NOTIFY_BURST) return false; // a flood: dropped
        recentNotifications.addLast(now);
        Notifications.Level level = switch (ApiJson.str(o, "level", "info", 16).toLowerCase(Locale.ROOT)) {
            case "success", "good", "ok" -> Notifications.Level.SUCCESS;
            case "warn", "warning", "error" -> Notifications.Level.WARN;
            default -> Notifications.Level.INFO;
        };
        double secs = ApiJson.num(o, "seconds", 4);
        Notifications.push(title, text, level, Math.round(secs * 1000));
        return true;
    }

    /**
     * {@code rules}: {@code "disable"} works exactly like an {@code evade:rules} message; {@code "settings"} limits
     * number settings for this session (see {@link ApiSettings}). Either may be left out to keep what was set before.
     */
    private static boolean rules(JsonObject o) {
        boolean any = false;
        JsonElement disable = o.get("disable");
        if (disable != null && disable.isJsonArray()) {
            JsonObject r = new JsonObject();
            r.add("disable", disable);
            ServerRules.apply(r.toString().getBytes(StandardCharsets.UTF_8));
            any = true;
        }
        JsonElement settings = o.get("settings");
        if (settings != null && settings.isJsonObject()) {
            int n = ApiSettings.apply(settings.getAsJsonObject());
            if (n > 0) Notifications.push("Server rules", n + (n == 1 ? " setting is" : " settings are") + " limited by this server", false);
            any = true;
        }
        return any;
    }

    /** {@code discord}: status text (and optional details) for the Discord presence; empty text clears it. */
    private static boolean discord(JsonObject o) {
        String text = ApiJson.str(o, "text", "", 128);
        String details = ApiJson.str(o, "details", "", 128);
        discordText = text.isEmpty() ? null : text;
        discordDetails = text.isEmpty() || details.isEmpty() ? null : details;
        return true;
    }

    /** The server's Discord status text, or null. */
    public static String discordText() { return discordText; }

    /** The server's Discord details line, or null. */
    public static String discordDetails() { return discordDetails; }

    private static void failed(String where, Exception e) {
        if (failed.add(where)) EvadeClient.LOG.warn("Server API: {} failed", where, e);
    }

    private static void warn(String what) {
        if (warnings++ < 5) EvadeClient.LOG.warn("Server API: {}", what);
    }

    /** Forgets everything the server sent (leaving, a new connection, or a {@code reset} message). */
    public static void clear() {
        ApiWaypoints.clear();
        ApiCooldowns.clear();
        ApiTeams.clear();
        ApiBorder.clear();
        ApiBeams.clear();
        ApiHolograms.clear();
        ApiTitle.clear();
        ApiSettings.clear();
        discordText = discordDetails = null;
        recentNotifications.clear();
        warnings = 0;
    }

    // ------------------------------------------------------------------ drawing

    private static void renderHud(DrawContext ctx, float td) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.options.hudHidden) return;
        try {
            ApiHolograms.render(ctx);
            ApiTeams.render(ctx, td);
        } catch (Exception e) {
            failed("HUD drawing", e);
        }
    }

    private static void renderTitle(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        try {
            ApiTitle.render(ctx);
        } catch (Exception e) {
            failed("title drawing", e);
        }
    }

    private static long lastFrame = System.nanoTime();

    private static void renderWorld(WorldRenderContext context) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || (!ApiBorder.active() && ApiBeams.count() == 0)) return;
        long now = System.nanoTime();
        float frameTime = Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
        lastFrame = now;
        RenderContext ctx = new RenderContext(context.matrices(), context.consumers(),
                mc.getRenderTickCounter().getTickProgress(true), frameTime);
        try {
            ApiBorder.render(ctx);
            ApiBeams.render(ctx);
        } catch (Exception e) {
            failed("world drawing", e);
        }
        ctx.flush();
    }
}
