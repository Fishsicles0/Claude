package net.evadeclient.social;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player cards: the players in the tab list (names the game already shows you) and "Hide this player", which stops
 * drawing someone on your screen until you close the game. Hiding is never saved or sent anywhere.
 */
public final class PlayerCards {
    /** A player as the tab list knows them. */
    public record Player(UUID uuid, String name) {}

    private static final Set<UUID> HIDDEN = ConcurrentHashMap.newKeySet();

    private PlayerCards() {}

    /** Whether {@code u} is hidden on your screen (render thread, every entity: kept cheap). */
    public static boolean isHidden(UUID u) { return !HIDDEN.isEmpty() && u != null && HIDDEN.contains(u); }

    public static void hide(UUID u) { if (u != null && !isYou(u)) HIDDEN.add(u); }

    public static void show(UUID u) { HIDDEN.remove(u); }

    public static int hiddenCount() { return HIDDEN.size(); }

    public static void showAll() { HIDDEN.clear(); }

    private static boolean isYou(UUID u) {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && u.equals(mc.player.getUuid());
    }

    /** The players in the tab list (not you), by name. Render thread. */
    public static List<Player> tabList() {
        MinecraftClient mc = MinecraftClient.getInstance();
        List<Player> out = new ArrayList<>();
        if (mc.getNetworkHandler() == null) return out;
        for (PlayerListEntry e : mc.getNetworkHandler().getPlayerList()) {
            var p = e.getProfile();
            if (p == null || p.id() == null || p.name() == null || p.name().isBlank() || isYou(p.id())) continue;
            out.add(new Player(p.id(), p.name()));
        }
        out.sort(Comparator.comparing(p -> p.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** A tab-list player by name (ignoring case), or null. */
    public static Player find(String name) {
        if (name == null) return null;
        for (Player p : tabList()) if (p.name().equalsIgnoreCase(name)) return p;
        return null;
    }
}
