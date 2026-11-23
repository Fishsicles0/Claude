package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.mixin.DisconnectedScreenAccessor;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.NumberSetting;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.realms.gui.screen.RealmsMainScreen;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reconnects to the server you were connecting to or playing on after you get disconnected, with a countdown on the
 * disconnect screen. It never rejoins after a ban, a whitelist refusal, an AFK/idle kick, an operator kick or a login
 * from elsewhere, and it gives up after a few attempts, waiting longer before each one.
 */
public class AutoReconnect extends Module {
    private final NumberSetting delay = add(new NumberSetting("Delay (s)", 5, 1, 60, 1));
    private final NumberSetting maxAttempts = add(new NumberSetting("Max Attempts", 5, 1, 20, 1)
            .describe("Reconnects in a row before giving up. Each one waits twice as long as the last (up to 60 s)."));

    /** Longest wait between attempts, unless Delay itself is longer. */
    private static final double MAX_WAIT_S = 60;
    /** How long you must stay connected before failed attempts are forgotten. */
    private static final long STABLE_MS = 30_000;

    /** The server the last connection attempt was for (null: nothing to reconnect to). */
    private static ServerInfo last;
    /** When the countdown on the current disconnect screen started (-1: not counting). */
    private static long disconnectedAt = -1;
    /** Reconnects made since you last stayed connected for {@link #STABLE_MS}. */
    private static int attempts;
    /** True while AutoReconnect itself is starting a connection. */
    private static boolean autoConnecting;
    /** When you last joined a server (-1: not since the last reset). */
    private static long joinedAt = -1;
    /** The disconnect screen whose reason was last read, and why it must not be retried (null: it may). */
    private static Screen inspected;
    private static String blockedReason;

    public AutoReconnect() {
        super("AutoReconnect", "Rejoins the server after you get disconnected.", Category.SERVER);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(AutoReconnect::onJoin));
    }

    @Override
    protected boolean alwaysRunHooks() { return true; }

    private static AutoReconnect instance() {
        return EvadeClient.modules == null ? null : EvadeClient.modules.get(AutoReconnect.class);
    }

    /**
     * From the connect-screen mixin: a connection to {@code info} is starting. One you start yourself (not one of
     * ours) also clears the attempt counter.
     */
    public static void setTarget(ServerInfo info) {
        last = info == null || info.isRealm() ? null : info;
        if (!autoConnecting) attempts = 0;
    }

    /** The server AutoReconnect would rejoin, or null (for the tests). */
    public static ServerInfo target() { return last; }

    /** Reconnects made in a row so far (for the tests). */
    public static int attempts() { return attempts; }

    private static void onJoin() {
        MinecraftClient mc = EvadeClient.mc();
        ServerInfo entry = mc.getCurrentServerEntry();
        // Keeps the target right even for a connection the mixin didn't see; Realms and singleplayer are never rejoined.
        last = mc.isInSingleplayer() || entry == null || entry.isRealm() ? null : entry;
        joinedAt = System.currentTimeMillis();
        inspected = null;
        blockedReason = null;
        disconnectedAt = -1;
    }

    /** Runs every client tick, in or out of a world (registered in EvadeClient). */
    public static void clientTick(MinecraftClient mc) {
        long now = System.currentTimeMillis();
        if (mc.isIntegratedServerRunning()) last = null; // singleplayer: nothing to rejoin
        if (mc.player != null && joinedAt > 0 && now - joinedAt >= STABLE_MS) { attempts = 0; joinedAt = -1; }
        Screen screen = mc.currentScreen;
        if (!(screen instanceof DisconnectedScreen)) {
            // Back in the menus (left the disconnect screen, or disconnected yourself): forget the server.
            if (screen instanceof TitleScreen || screen instanceof MultiplayerScreen || screen instanceof RealmsMainScreen
                    || (inspected != null && !(screen instanceof ConnectScreen))) {
                last = null;
                attempts = 0;
            }
            inspected = null;
            blockedReason = null;
            disconnectedAt = -1;
            return;
        }
        inspect(screen);
        AutoReconnect ar = instance();
        if (ar == null || !ar.isEnabled() || last == null || blockedReason != null) return;
        if (attempts >= ar.maxAttempts.getInt()) return;
        if (disconnectedAt < 0) disconnectedAt = now;
        if (now - disconnectedAt < ar.waitMillis()) return;
        disconnectedAt = -1;
        attempts++;
        ServerInfo info = last;
        autoConnecting = true;
        try {
            ConnectScreen.connect(new MultiplayerScreen(new TitleScreen()), mc, ServerAddress.parse(info.address), info, false, null);
        } finally {
            autoConnecting = false;
        }
    }

    /** How long to wait before the next attempt: Delay, doubled for every attempt already made, capped at 60 s. */
    private long waitMillis() {
        double d = delay.get();
        return Math.round(Math.min(d * Math.pow(2, Math.min(attempts, 16)), Math.max(MAX_WAIT_S, d)) * 1000);
    }

    /** Reads the reason on a disconnect screen we haven't looked at yet, and decides whether it may be retried. */
    private static void inspect(Screen screen) {
        if (screen == inspected) return;
        inspected = screen;
        disconnectedAt = -1;
        Text reason = null;
        try {
            if (screen instanceof DisconnectedScreenAccessor a) {
                DisconnectionInfo info = a.evade$getInfo();
                if (info != null) reason = info.reason();
            } else {
                reason = screen.getNarratedTitle(); // title + reason, if the accessor isn't there
            }
        } catch (RuntimeException e) {
            EvadeClient.LOG.warn("AutoReconnect couldn't read the disconnect reason", e);
        }
        blockedReason = reason == null ? null : blockReason(reason);
    }

    /** Whole words in a server's own kick text that mean "don't come back". */
    private static final Pattern NO_RETRY_WORDS = Pattern.compile(
            "\\b(banned|blacklisted|white-?list(?:ed)?|idle|idling|afk|inactivity)\\b|another location");

    /**
     * Why a disconnect with this reason must not be retried ("banned", "AFK kick", ...), or null when retrying is
     * fine (a timeout, a restart, a lost connection). Looks at vanilla's translation keys first, then the plain text
     * servers write themselves.
     */
    public static String blockReason(Text reason) {
        if (reason == null) return null;
        String byKey = byKey(reason, 0);
        if (byKey != null) return byKey;
        Matcher m = NO_RETRY_WORDS.matcher(reason.getString().toLowerCase(Locale.ROOT));
        if (!m.find()) return null;
        String w = m.group(1);
        if (w == null) return "logged in from another location";
        if (w.equals("banned") || w.equals("blacklisted")) return "banned";
        if (w.startsWith("white")) return "not whitelisted";
        return "AFK kick";
    }

    private static String byKey(Text t, int depth) {
        if (t == null || depth > 8) return null;
        if (t.getContent() instanceof TranslatableTextContent tr) {
            String label = keyLabel(tr.getKey());
            if (label != null) return label;
            for (Object arg : tr.getArgs()) {
                if (arg instanceof Text at) {
                    String r = byKey(at, depth + 1);
                    if (r != null) return r;
                }
            }
        }
        for (Text sibling : t.getSiblings()) {
            String r = byKey(sibling, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private static String keyLabel(String key) {
        if (key == null) return null;
        if (key.equals("multiplayer.disconnect.banned") || key.startsWith("multiplayer.disconnect.banned.")
                || key.startsWith("multiplayer.disconnect.banned_ip") || key.equals("multiplayer.disconnect.ip_banned")
                || key.equals("disconnect.loginFailedInfo.userBanned")) return "banned";
        return switch (key) {
            case "multiplayer.disconnect.idling" -> "AFK kick";
            case "multiplayer.disconnect.not_whitelisted" -> "not whitelisted";
            case "multiplayer.disconnect.kicked" -> "kicked by an operator";
            case "multiplayer.disconnect.duplicate_login" -> "logged in from another location";
            case "multiplayer.disconnect.name_taken" -> "name already taken";
            case "multiplayer.disconnect.code_of_conduct" -> "Code of Conduct not accepted";
            case "multiplayer.disconnect.transfers_disabled" -> "server doesn't accept transfers";
            case "multiplayer.disconnect.outdated_client", "multiplayer.disconnect.outdated_server",
                 "multiplayer.disconnect.incompatible" -> "incompatible version";
            case "disconnect.loginFailedInfo.insufficientPrivileges" -> "multiplayer is off for your account";
            case "disconnect.loginFailedInfo.invalidSession" -> "invalid session, restart the game";
            default -> null;
        };
    }

    /** The countdown (or why there isn't one) drawn on the disconnect screen. */
    public static void renderOverlay(DrawContext ctx, int width) {
        AutoReconnect ar = instance();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (ar == null || !ar.isEnabled() || last == null) return;
        if (mc.currentScreen instanceof DisconnectedScreen) inspect(mc.currentScreen);
        int max = ar.maxAttempts.getInt();
        String s;
        int color = 0xFFAAAAAA;
        if (blockedReason != null) {
            s = "Not reconnecting: " + blockedReason;
        } else if (attempts >= max) {
            s = "Not reconnecting: gave up after " + attempts + (attempts == 1 ? " attempt" : " attempts");
        } else {
            if (disconnectedAt < 0) return;
            double left = Math.max(0, (ar.waitMillis() - (System.currentTimeMillis() - disconnectedAt)) / 1000.0);
            s = "Reconnecting to " + last.address + " in " + String.format(Locale.ROOT, "%.1f", left) + "s"
                    + (attempts > 0 ? " (attempt " + (attempts + 1) + "/" + max + ")" : "");
            color = 0xFFFF3B4C;
        }
        var tr = mc.textRenderer;
        ctx.drawTextWithShadow(tr, s, (width - tr.getWidth(s)) / 2, 12, color);
    }
}
