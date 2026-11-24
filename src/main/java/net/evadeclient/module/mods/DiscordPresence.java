package net.evadeclient.module.mods;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.discord.DiscordIpc;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Discord Rich Presence: shows "Playing Evade Client" on your Discord profile with what you're
 * doing, how long for, and a button to join the Evade Discord. Talks to the Discord desktop app
 * directly; does nothing if Discord isn't running and reconnects when it starts.
 */
public class DiscordPresence extends Module {
    /** The Evade Client Discord application. */
    private static final String APP_ID = "1573553858158923958";
    private static final String LOGO = "https://cdn.discordapp.com/app-icons/" + APP_ID + "/d969d19052fdba42c6b4310df645f3f4.png?size=512";
    private static final String DISCORD_INVITE = "https://discord.gg/BxXAv6kvtw";
    private static final long RETRY_MS = 30_000;

    public final BooleanSetting showServer = add(new BooleanSetting("Show Server Address", false));
    public final BooleanSetting showTime = add(new BooleanSetting("Show Time Played", true));
    public final BooleanSetting showButton = add(new BooleanSetting("Show Discord Button", true));

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Evade Discord Presence");
        t.setDaemon(true);
        return t;
    });
    private DiscordIpc ipc;              // only touched on the worker thread
    private volatile String lastSent;    // JSON of the last presence Discord accepted
    private volatile boolean busy;
    private long nextAttempt;
    private String lastState = "";
    private long stateSince = System.currentTimeMillis() / 1000;
    private int ticks;
    private volatile String status = "Starting";
    private volatile String lastError = "";

    public DiscordPresence() {
        super("Discord Presence", "Shows Evade Client on your Discord profile.", Category.MODS);
        setEnabled(true);
    }

    @Override
    protected boolean alwaysRunHooks() { return true; }

    /** Shown next to the module name in the menu, so it's obvious whether it's working. */
    @Override
    public String getSuffix() { return status; }

    @Override
    protected void onDisable() {
        submit(null);
    }

    /** Runs every client tick, in menus too. */
    public static void clientTick(MinecraftClient client) {
        if (EvadeClient.modules == null) return;
        DiscordPresence p = EvadeClient.modules.get(DiscordPresence.class);
        if (p != null && p.isEnabled() && ++p.ticks % 40 == 0) p.update(client);
    }

    /** Clears the presence when the game closes. */
    public static void shutdown() {
        if (EvadeClient.modules == null) return;
        DiscordPresence p = EvadeClient.modules.get(DiscordPresence.class);
        if (p == null) return;
        p.worker.submit(() -> {
            if (p.ipc != null) {
                try { p.ipc.setActivity(null); } catch (Exception ignored) { }
                p.ipc.close();
                p.ipc = null;
            }
        });
        p.worker.shutdown();
    }

    private void update(MinecraftClient client) {
        String details, state;
        if (client.world == null) {
            details = "In the menus";
            state = null;
        } else if (client.isInSingleplayer()) {
            details = "Playing Singleplayer";
            state = null;
        } else {
            details = "Playing Multiplayer";
            var entry = client.getCurrentServerEntry();
            state = showServer.get() && entry != null ? entry.address : null;
        }
        String key = details + "|" + state;
        if (!key.equals(lastState)) {
            lastState = key;
            stateSince = System.currentTimeMillis() / 1000;
        }

        JsonObject activity = new JsonObject();
        activity.addProperty("type", 0); // "Playing"
        activity.addProperty("details", details);
        if (state != null) activity.addProperty("state", state);
        if (showTime.get()) {
            JsonObject ts = new JsonObject();
            ts.addProperty("start", stateSince);
            activity.add("timestamps", ts);
        }
        JsonObject assets = new JsonObject();
        assets.addProperty("large_image", LOGO);
        assets.addProperty("large_text", "Evade Client " + version("evade") + " · Minecraft " + version("minecraft"));
        activity.add("assets", assets);
        if (showButton.get()) {
            JsonArray buttons = new JsonArray();
            JsonObject b = new JsonObject();
            b.addProperty("label", "Join the Discord");
            b.addProperty("url", DISCORD_INVITE);
            buttons.add(b);
            activity.add("buttons", buttons);
        }

        String json = activity.toString();
        boolean connected = lastSent != null;
        if (busy || (json.equals(lastSent))) return;
        if (!connected && System.currentTimeMillis() < nextAttempt) return;
        submit(activity);
    }

    private void submit(JsonObject activity) {
        if (worker.isShutdown()) return;
        busy = true;
        worker.submit(() -> {
            try {
                if (activity == null) {
                    if (ipc != null) ipc.setActivity(null);
                    lastSent = null;
                    return;
                }
                if (ipc == null) {
                    ipc = DiscordIpc.connect(APP_ID);
                    EvadeClient.LOG.info("[Discord Presence] Connected to Discord");
                }
                try {
                    ipc.setActivity(activity);
                } catch (DiscordIpc.Rejected rejected) {
                    // Discord refused part of it (usually the image or button). Fall back to the basics.
                    report("Discord rejected the full presence, using a simple one: " + rejected.getMessage());
                    JsonObject basic = activity.deepCopy();
                    basic.remove("assets");
                    basic.remove("buttons");
                    ipc.setActivity(basic);
                }
                lastSent = activity.toString();
                status = "Connected";
                lastError = "";
            } catch (Exception e) {
                // Discord closed or not running: drop the connection and try again later.
                if (ipc != null) ipc.close();
                ipc = null;
                lastSent = null;
                nextAttempt = System.currentTimeMillis() + RETRY_MS;
                status = e.getMessage() != null && e.getMessage().contains("isn't running") ? "Discord not found" : "Error";
                report(e.toString());
            } finally {
                busy = false;
            }
        });
    }

    /** Logs each distinct problem once, so latest.log explains what's wrong without spamming it. */
    private void report(String message) {
        if (message.equals(lastError)) return;
        lastError = message;
        EvadeClient.LOG.warn("[Discord Presence] {}", message);
    }

    private static String version(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("");
    }
}
