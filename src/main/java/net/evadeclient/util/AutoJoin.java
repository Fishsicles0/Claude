package net.evadeclient.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.evadeclient.EvadeClient;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Quick Play from the Evade Launcher: the launcher writes {@code config/evade/autojoin.json}
 * ({@code {"address": "play.example.net", "expires": <epoch ms>}}) before starting the game. The first time the main
 * menu opens, Evade joins that server once and deletes the file, so it never joins again on its own. An expired or
 * unreadable file is just removed.
 */
public final class AutoJoin {
    private static boolean done;

    private AutoJoin() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (done || client.world != null || !isMainMenu(screen)) return;
            done = true;
            String address = take();
            if (address != null) client.execute(() -> join(client, screen, address));
        });
    }

    private static boolean isMainMenu(Screen screen) {
        return screen instanceof TitleScreen || screen.getClass().getName().endsWith("EvadeTitleScreen");
    }

    static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("autojoin.json");
    }

    /** Reads and deletes the request; returns the address if it is still fresh. */
    static String take() {
        Path f = file();
        if (!Files.exists(f)) return null;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            String address = o.has("address") ? o.get("address").getAsString().strip() : "";
            long expires = o.has("expires") ? o.get("expires").getAsLong() : 0;
            if (address.isEmpty() || address.length() > 255 || System.currentTimeMillis() > expires) return null;
            return address;
        } catch (Exception e) {
            EvadeClient.LOG.warn("Auto-join: couldn't read {}", f, e);
            return null;
        } finally {
            try { Files.deleteIfExists(f); } catch (Exception ignored) { }
        }
    }

    private static void join(MinecraftClient client, Screen parent, String address) {
        if (!ServerAddress.isValid(address)) {
            Notifications.push("Quick Play", "Can't join \"" + address + "\": not a valid address", false);
            return;
        }
        ServerInfo info = new ServerInfo(address, address, ServerInfo.ServerType.OTHER);
        ConnectScreen.connect(parent, client, ServerAddress.parse(address), info, false, null);
    }
}
