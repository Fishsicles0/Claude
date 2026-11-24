package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.command.CommandManager;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StringSetting;
import net.evadeclient.util.Notifications;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs Hypixel's "/tip all" on a timer while you play there. Does nothing in singleplayer or on a server outside
 * Server Filter. The first tip comes Join Delay after you join (or a whole interval, with Tip On Join off), so it
 * never fires the moment you connect, and the replies can be folded into one toast.
 */
public class AutoTip extends Module {
    /** Hypixel's replies to /tip all; group 2 is how many players were tipped. */
    private static final Pattern REPLY = Pattern.compile(
            "^(You tipped (\\d+) players?.*|You already tipped everyone.*|No one has a network booster active.*|You've already tipped .*)$");
    /** Replies only count this long after a tip we sent. */
    private static final long REPLY_WINDOW_MS = 5000;

    public final NumberSetting minutes = add(new NumberSetting("Every (min)", 15, 1, 60, 1)
            .describe("How often to tip while you stay on the server."));
    private final StringSetting serverFilter = add(new StringSetting("Server Filter", "hypixel.net", "addresses split by |")
            .describe("Only tip on servers whose address contains one of these, split with | (case doesn't matter)."));
    private final ModeSetting command = add(new ModeSetting("Command", "tip all", "tip all")
            .describe("The command sent each time. Only tip commands can be chosen."));
    private final BooleanSetting tipOnJoin = add(new BooleanSetting("Tip On Join", true)
            .describe("Tip once Join Delay after you join, instead of waiting a whole interval first."));
    private final NumberSetting joinDelay = add(new NumberSetting("Join Delay (s)", 30, 10, 120, 5)
            .describe("How long after joining the first tip waits, with Tip On Join on."));
    private final BooleanSetting hideResponses = add(new BooleanSetting("Hide Responses", true)
            .describe("Keep the server's replies to the tip out of chat."));
    private final ModeSetting summary = add(new ModeSetting("Summary", "Toast", "Off", "Chat", "Toast")
            .describe("One short note per tip: a toast, or a chat line in place of the hidden replies."));
    private final BooleanSetting showCount = add(new BooleanSetting("Show Count", false)
            .describe("Number the summary with how many tips you've sent this session (Tip #3)."));

    /** When the last tip was sent; 0 means the timer hasn't started for this connection yet. */
    private long lastSent;
    /** When we really sent the last tip (lastSent can be moved for Tip On Join), for matching its replies. */
    private long sentAt;
    /** Whether the last tip's summary was shown (one per tip). */
    private boolean summarized = true;
    /** Tips sent since you connected (reset on disconnect, not on Hypixel's lobby and game switches). */
    private int sessionTips;
    /** The connection the timer belongs to (survives Hypixel's lobby and game switches, not a reconnect). */
    private Object connection;

    public AutoTip() {
        super("AutoTip", "Tips everyone on a timer with /tip all. Hypixel only.", Category.SERVER);
    }

    private static AutoTip instance() {
        return EvadeClient.modules == null ? null : EvadeClient.modules.get(AutoTip.class);
    }

    /**
     * Toggling on never tips at once: the timer starts over on the next tick. A join timer that is still well
     * ahead (Tip On Join, when the module's enable was waiting for the first world) is kept.
     */
    @Override
    protected void onEnable() {
        if (lastSent == 0 || interval() - (System.currentTimeMillis() - lastSent) < 10_000) lastSent = 0;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        // Never in singleplayer, and only where Server Filter allows.
        if (mc.isInSingleplayer() || !allowedOn(mc.getCurrentServerEntry())) return;
        Object conn = mc.getNetworkHandler().getConnection();
        if (conn != connection) { connection = conn; lastSent = 0; } // a connection onJoin didn't see
        long now = System.currentTimeMillis();
        if (lastSent == 0) { lastSent = now; return; } // start the timer: the first tip waits a whole interval
        if (now - lastSent < interval()) return;
        lastSent = now;
        sentAt = now;
        summarized = false;
        sessionTips++;
        mc.player.networkHandler.sendChatCommand(command.get());
    }

    private long interval() { return (long) (minutes.get() * 60_000); }

    /** From the JOIN listener: start the timer for a new connection (Join Delay with Tip On Join, else a full interval). */
    public static void onJoin(ServerInfo entry) {
        AutoTip t = instance();
        if (t == null) return;
        MinecraftClient mc = EvadeClient.mc();
        Object conn = mc.getNetworkHandler() == null ? null : mc.getNetworkHandler().getConnection();
        // A server switch behind a proxy joins again on the same connection: keep the timer running.
        if (conn != null && conn == t.connection) return;
        t.connection = conn;
        long now = System.currentTimeMillis();
        boolean here = !mc.isInSingleplayer() && t.allowedOn(entry);
        t.lastSent = here && t.tipOnJoin.get() ? now - t.interval() + (long) (t.joinDelay.get() * 1000) : now;
    }

    /** From the DISCONNECT listener: a new session starts counting from zero. */
    public static void onDisconnect() {
        AutoTip t = instance();
        if (t == null) return;
        t.sessionTips = 0;
        t.connection = null;
    }

    /**
     * From ALLOW_GAME: false (hide it) for the server's reply to a tip we sent in the last 5 s, while Hide Responses
     * is on. The first reply to each tip also shows the summary.
     */
    public static boolean allow(Text message, boolean overlay) {
        if (overlay || message == null) return true;
        AutoTip t = instance();
        if (t == null || !t.isEnabled() || t.sentAt == 0 || System.currentTimeMillis() - t.sentAt >= REPLY_WINDOW_MS) return true;
        Matcher m = REPLY.matcher(Formatting.strip(message.getString()).strip());
        if (!m.matches()) return true;
        boolean hide = t.hideResponses.get();
        if (!t.summarized) {
            t.summarized = true;
            t.summarize(m, hide);
        }
        return !hide;
    }

    private void summarize(Matcher m, boolean hidden) {
        String mode = summary.get();
        if (mode.equals("Off") || (mode.equals("Chat") && !hidden)) return; // the replies are in chat already
        String text;
        if (m.group(2) != null) {
            int n = parse(m.group(2));
            text = "Tipped " + n + (n == 1 ? " player" : " players");
        } else if (m.group(1).startsWith("You already tipped everyone")) {
            text = "Everyone is tipped already";
        } else if (m.group(1).startsWith("No one")) {
            text = "No boosters active right now";
        } else {
            text = "Already tipped recently";
        }
        if (showCount.get()) text = "Tip #" + sessionTips + ": " + text;
        if (mode.equals("Toast")) Notifications.push("AutoTip", text, true);
        else CommandManager.print("§7AutoTip: §f" + text);
    }

    private static int parse(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Whether tipping may run on {@code server}: a real server (not Realms) whose address matches Server Filter. */
    private boolean allowedOn(ServerInfo server) {
        if (server == null || server.address == null || server.isRealm()) return false;
        String address = server.address.strip().toLowerCase(Locale.ROOT);
        String filter = serverFilter.get() == null ? "" : serverFilter.get();
        for (String part : filter.split("\\|")) {
            String s = part.strip().toLowerCase(Locale.ROOT);
            if (!s.isEmpty() && address.contains(s)) return true;
        }
        return false;
    }

    /** Tips sent this session (for the tests). */
    public int sessionTips() { return sessionTips; }

    /** Whether {@code server} is Hypixel: hypixel.net or any subdomain of it, with or without a port. */
    public static boolean onHypixel(ServerInfo server) {
        if (server == null || server.address == null || server.isRealm()) return false;
        return isHypixel(server.address);
    }

    /** Whether a typed server address points at Hypixel ("mc.hypixel.net:25565", "Hypixel.net."). */
    public static boolean isHypixel(String address) {
        String host = address.strip().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) return false; // an IPv6 literal
        int colon = host.lastIndexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host.equals("hypixel.net") || host.endsWith(".hypixel.net");
    }
}
