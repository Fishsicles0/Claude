package net.evadeclient.module.misc;

import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.StatusSetting;
import net.evadeclient.setting.StringSetting;
import net.evadeclient.util.DiscordIpc;
import net.evadeclient.util.Notifications;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

/**
 * Discord Rich Presence: your Discord profile shows "Playing on &lt;server&gt;", "Singleplayer" or "In menus", with the
 * time elapsed. Talks to the Discord app on your own computer over its local IPC ({@link DiscordIpc}); nothing is sent
 * to the Minecraft server, and the client thread never waits for Discord.
 *
 * <p>Needs a Discord application's client id: {@link #DISCORD_APP_ID}, or the hidden App ID setting. Without one the
 * module says so and does nothing.
 */
public class DiscordStatus extends Module {
    /** Evade's Discord application id (Developer Portal: "Evade Client"). */
    public static final String DISCORD_APP_ID = "1573553858158923958";
    private static final String NO_ID = "Discord app ID not set";
    /** How often (client ticks) the presence is worked out again; it's only sent when it changed. */
    private static final int EVERY = 20;

    private final StatusSetting status = add(new StatusSetting("Status", this::statusText, "")
            .describe("Whether Evade is connected to the Discord app on this computer."));
    private final BooleanSetting hideAddress = add(new BooleanSetting("Hide Server Address", false)
            .describe("Show \"Playing multiplayer\" instead of the server's address, so friends don't see where you play."));
    private final BooleanSetting useListName = add(new BooleanSetting("Use Server List Name", false)
            .describe("Show the name you gave the server in your server list instead of its address."));
    private final BooleanSetting showTime = add(new BooleanSetting("Show Elapsed Time", true)
            .describe("Show how long you've been playing under your status."));
    private final ModeSetting timeFrom = add(new ModeSetting("Elapsed Since", "Current Activity", "Current Activity", "Game Start")
            .describe("Count the time from when you joined the current world or server, or from when the game started."));
    private final BooleanSetting showInMenus = add(new BooleanSetting("Show In Menus", true)
            .describe("Keep a status (\"In menus\") while you're on the title screen. Off: no status outside a world."));
    /** The Discord application id to use when {@link #DISCORD_APP_ID} is empty (never shown in the menu). */
    private final StringSetting appId = add(new StringSetting("App ID", "", "Discord application id").maxLength(32).hidden());

    private static final long GAME_START = System.currentTimeMillis() / 1000L;

    private DiscordIpc ipc;
    private String lastKey;
    private long activitySince = GAME_START;
    private int countdown;

    public DiscordStatus() {
        super("Discord Status", "Shows what you're playing on your Discord profile.", Category.MISC);
        ClientTickEvents.END_CLIENT_TICK.register(this::clientTick);
    }

    /** Runs on the title screen too (the status says "In menus"). */
    @Override
    protected boolean alwaysRunHooks() { return true; }

    /** The application id in use, or "" when there is none. */
    private String clientId() {
        String id = DISCORD_APP_ID.isBlank() ? appId.get() : DISCORD_APP_ID;
        id = id == null ? "" : id.strip();
        return id.matches("\\d{5,32}") ? id : "";
    }

    private String statusText() {
        if (clientId().isEmpty()) return NO_ID;
        if (!isEnabled()) return "Off";
        DiscordIpc i = ipc;
        return i == null ? "Starting" : i.status();
    }

    @Override
    protected void onEnable() {
        String id = clientId();
        if (id.isEmpty()) {
            Notifications.push(getName(), NO_ID, false);
            return;
        }
        lastKey = null;
        countdown = 0;
        ipc = new DiscordIpc(id);
        ipc.start();
    }

    @Override
    protected void onDisable() {
        DiscordIpc i = ipc;
        ipc = null;
        if (i != null) i.stop();
    }

    private void clientTick(MinecraftClient mc) {
        DiscordIpc i = ipc;
        if (i == null || !isEnabled()) return;
        if (--countdown > 0) return;
        countdown = EVERY;
        String details = details(mc);
        String key = details == null ? "" : details + "|" + showTime.get() + "|" + timeFrom.get();
        if (key.equals(lastKey)) return;
        // A new place to be (joined, left, switched server) restarts "Current Activity" time.
        String place = details == null ? "" : details;
        if (lastKey == null || !lastKey.startsWith(place + "|")) activitySince = System.currentTimeMillis() / 1000L;
        lastKey = key;
        i.update(details == null ? null : activity(details));
    }

    /** The first status line, or null for no status at all. */
    String details(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) return showInMenus.get() ? "In menus" : null;
        if (mc.isInSingleplayer()) return "Singleplayer";
        ServerInfo entry = mc.getCurrentServerEntry();
        if (entry == null) return "Playing multiplayer";
        if (entry.isRealm()) return "Playing on Realms";
        if (hideAddress.get()) return "Playing multiplayer";
        String shown = useListName.get() && entry.name != null && !entry.name.isBlank() ? entry.name : entry.address;
        if (shown == null || shown.isBlank()) return "Playing multiplayer";
        shown = shown.strip();
        if (shown.length() > 100) shown = shown.substring(0, 100);
        return "Playing on " + shown;
    }

    private JsonObject activity(String details) {
        JsonObject a = new JsonObject();
        a.addProperty("details", details);
        a.addProperty("state", "Minecraft " + net.minecraft.SharedConstants.getGameVersion().name());
        if (showTime.get()) {
            JsonObject ts = new JsonObject();
            ts.addProperty("start", timeFrom.is("Game Start") ? GAME_START : activitySince);
            a.add("timestamps", ts);
        }
        JsonObject assets = new JsonObject();
        assets.addProperty("large_image", "evade");
        assets.addProperty("large_text", EvadeClient.NAME + " Client " + EvadeClient.VERSION);
        a.add("assets", assets);
        a.addProperty("instance", false);
        return a;
    }
}
