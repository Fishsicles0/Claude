package net.evadeclient.util;

import net.evadeclient.module.misc.AutoTip;
import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Small read-only helpers for the Hypixel tools: whether the player is connected to Hypixel right now, and the
 * plain text of the sidebar scoreboard the server shows (title and lines, formatting codes stripped). Nothing here
 * sends anything. Client thread only.
 */
public final class Hypixel {
    private Hypixel() {}

    /** Whether the client is connected to Hypixel (a multiplayer server whose address is hypixel.net or a subdomain). */
    public static boolean connected() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null || mc.isInSingleplayer()) return false;
        return AutoTip.onHypixel(mc.getCurrentServerEntry());
    }

    private static ScoreboardObjective sidebar() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return null;
        return mc.world.getScoreboard().getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
    }

    /** The sidebar's title without formatting (e.g. "BED WARS"), or "" when there is no sidebar. */
    public static String sidebarTitle() {
        ScoreboardObjective o = sidebar();
        return o == null ? "" : strip(o.getDisplayName().getString());
    }

    /** The sidebar's lines top to bottom without formatting (team prefixes and suffixes included); empty when none. */
    public static List<String> sidebarLines() {
        ScoreboardObjective o = sidebar();
        if (o == null) return List.of();
        Scoreboard board = o.getScoreboard();
        List<ScoreboardEntry> entries = new ArrayList<>(board.getScoreboardEntries(o));
        entries.removeIf(ScoreboardEntry::hidden);
        entries.sort(Comparator.comparingInt(ScoreboardEntry::value).reversed()
                .thenComparing(ScoreboardEntry::owner, String.CASE_INSENSITIVE_ORDER));
        List<String> out = new ArrayList<>(Math.min(15, entries.size()));
        for (int i = 0; i < entries.size() && i < 15; i++) {
            ScoreboardEntry e = entries.get(i);
            out.add(strip(Team.decorateName(board.getScoreHolderTeam(e.owner()), e.name()).getString()));
        }
        return out;
    }

    /** {@code s} without § formatting codes and surrounding whitespace (null becomes ""). */
    public static String strip(String s) {
        if (s == null) return "";
        String plain = Formatting.strip(s);
        return plain == null ? "" : plain.strip();
    }
}
