package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.mixin.DisconnectedScreenAccessor;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.StringSetting;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.client.realms.gui.screen.RealmsMainScreen;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reconnects to the server you were connecting to or playing on after you get disconnected, with a countdown (and
 * Cancel / Reconnect Now buttons) on the disconnect screen. It never rejoins after a ban, a whitelist refusal, an
 * AFK/idle kick, an operator kick, a login from elsewhere or a kick whose reason has one of Ignore Words, and it
 * gives up after Max Attempts, waiting longer before each one. Can also put a "Rejoin" button on the title screen.
 */
public class AutoReconnect extends Module {
    private final NumberSetting delay = add(new NumberSetting("Delay (s)", 5, 1, 60, 1)
            .describe("How long to wait before the first reconnect."));
    private final NumberSetting maxAttempts = add(new NumberSetting("Max Attempts", 5, 0, 20, 1)
            .describe("Reconnects in a row before giving up (0: never give up)."));
    private final BooleanSetting backoff = add(new BooleanSetting("Back-off", true)
            .describe("Wait twice as long before each new attempt, up to Max Delay."));
    private final NumberSetting maxDelay = add(new NumberSetting("Max Delay (s)", 60, 10, 300, 5)
            .describe("The longest Back-off waits between attempts (never shorter than Delay)."));
    private final StringSetting ignoreWords = add(new StringSetting("Ignore Words",
            "banned|suspended|whitelist|outdated|duplicate|logged in from another location", "words split by |")
            .describe("Don't reconnect when the kick message contains one of these, split with | (case doesn't matter). Bans, whitelist refusals and AFK kicks are always skipped."));
    private final BooleanSetting showButtons = add(new BooleanSetting("Show Buttons", true)
            .describe("Cancel and Reconnect Now buttons on the disconnect screen."));
    private final ColorSetting countdownColor = add(new ColorSetting("Countdown Color", 0xFFFF3B4C)
            .describe("Colour of the reconnect countdown on the disconnect screen."));
    private final ModeSetting countdownStyle = add(new ModeSetting("Countdown Style", "Text", "Text", "Text + Bar", "Hidden")
            .describe("How the countdown shows on the disconnect screen. Hidden still reconnects."));
    private final BooleanSetting titleButton = add(new BooleanSetting("Title Button", false)
            .describe("A Rejoin button on the title screen for the last server you played on."));
    /** The last server you were on, as "name|address", for the title screen's Rejoin button. */
    private final StringSetting lastServer = add(new StringSetting("Last Server", "", "").hidden());
    /** The server entry Last Server was last written for, and what was written (see {@link #rememberServer}). */
    private String seenName, seenAddress, written;

    /** How long you must stay connected before failed attempts are forgotten. */
    private static final long STABLE_MS = 30_000;
    /** Where the title screen's Rejoin button goes: right of the Replays button (6, 6, 70 wide). */
    private static final int TITLE_X = 80, TITLE_Y = 6, TITLE_W = 120;
    /** Whole words in a server's own kick text that always mean "don't come back", whatever Ignore Words says. */
    private static final Pattern NO_RETRY_WORDS = Pattern.compile(
            "\\b(banned|blacklisted|white-?list(?:ed)?|idle|idling|afk|inactivity)\\b|another location");

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
    private static String skipReason;
    /** Cancel was pressed on this disconnect screen. */
    private static boolean cancelled;
    /** Reconnect Now / Try anyway was pressed: connect on the next tick, whatever the countdown or the reason. */
    private static boolean force;

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

    /** Why the current disconnect isn't retried, or null (for the tests). */
    public static String skipReason() { return skipReason; }

    private static void onJoin() {
        MinecraftClient mc = EvadeClient.mc();
        ServerInfo entry = mc.getCurrentServerEntry();
        // Keeps the target right even for a connection the mixin didn't see; Realms and singleplayer are never rejoined.
        last = mc.isInSingleplayer() || entry == null || entry.isRealm() ? null : entry;
        joinedAt = System.currentTimeMillis();
        inspected = null;
        skipReason = null;
        disconnectedAt = -1;
        cancelled = false;
        force = false;
    }

    // ------------------------------------------------------------------ tick

    /** Runs every client tick, in or out of a world (registered in EvadeClient). */
    public static void clientTick(MinecraftClient mc) {
        long now = System.currentTimeMillis();
        if (mc.isIntegratedServerRunning()) last = null; // singleplayer: nothing to rejoin
        // Attempts are forgotten once you've stayed on for a while (not on the join itself, so a server that kicks
        // straight after login can't make an endless loop).
        if (mc.player != null && joinedAt > 0 && now - joinedAt >= STABLE_MS) { attempts = 0; joinedAt = -1; }
        AutoReconnect ar = instance();
        if (ar != null && ar.isEnabled() && mc.player != null) ar.rememberServer(mc);
        Screen screen = mc.currentScreen;
        if (!(screen instanceof DisconnectedScreen)) {
            // Back in the menus (left the disconnect screen, or disconnected yourself): forget the server.
            if (screen instanceof TitleScreen || screen instanceof MultiplayerScreen || screen instanceof RealmsMainScreen
                    || (inspected != null && !(screen instanceof ConnectScreen))) {
                last = null;
                attempts = 0;
            }
            inspected = null;
            skipReason = null;
            disconnectedAt = -1;
            cancelled = false;
            force = false;
            return;
        }
        inspect(screen);
        if (ar == null || !ar.isEnabled() || last == null) return;
        if (force) {
            connect(mc);
            return;
        }
        if (cancelled || skipReason != null || ar.gaveUp()) return;
        if (disconnectedAt < 0) disconnectedAt = now;
        if (now - disconnectedAt < ar.waitMillis()) return;
        connect(mc);
    }

    private static void connect(MinecraftClient mc) {
        force = false;
        cancelled = false;
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

    /** Whether Max Attempts has been used up (0 never runs out). */
    private boolean gaveUp() {
        int max = maxAttempts.getInt();
        return max > 0 && attempts >= max;
    }

    /** How long to wait before the next attempt: Delay, doubled for every attempt already made with Back-off. */
    private long waitMillis() {
        double d = delay.get();
        if (!backoff.get()) return Math.round(d * 1000);
        double cap = Math.max(maxDelay.get(), d);
        return Math.round(Math.min(d * (1L << Math.min(attempts, 10)), cap) * 1000);
    }

    /** Keeps Last Server up to date while you play on a server (saved only when it changes). */
    private void rememberServer(MinecraftClient mc) {
        if (mc.isInSingleplayer()) return;
        ServerInfo entry = mc.getCurrentServerEntry();
        if (entry == null || entry.isRealm() || entry.address == null || entry.address.isBlank()) return;
        String cur = lastServer.get();
        // This runs every tick: nothing to do while it's the same server and nothing (a profile switch) changed the value.
        if (entry.name != null && entry.name.equals(seenName) && entry.address.equals(seenAddress) && Objects.equals(cur, written)) return;
        seenName = entry.name;
        seenAddress = entry.address;
        String address = entry.address.strip();
        String name = entry.name == null || entry.name.isBlank() ? address : entry.name.strip();
        lastServer.set(name + "|" + address);
        written = lastServer.get();
        if (!Objects.equals(cur, written) && EvadeClient.config != null) EvadeClient.config.save();
    }

    // ------------------------------------------------------------------ disconnect reason

    /** Reads the reason on a disconnect screen we haven't looked at yet, and decides whether it may be retried. */
    private static void inspect(Screen screen) {
        if (screen == inspected) return;
        inspected = screen;
        disconnectedAt = -1;
        cancelled = false;
        Text reason = null;
        try {
            if (screen instanceof DisconnectedScreenAccessor a) {
                DisconnectionInfo info = a.evade$getInfo();
                if (info != null) reason = info.reason();
            } else {
                reason = screen.getNarratedTitle(); // title + reason, if the accessor isn't registered
            }
        } catch (RuntimeException e) {
            EvadeClient.LOG.warn("AutoReconnect couldn't read the disconnect reason", e);
        }
        String why = reason == null ? null : blockReason(reason);
        if (why == null && reason != null) {
            AutoReconnect ar = instance();
            if (ar != null) why = ar.ignoredWord(reason.getString());
        }
        skipReason = why;
    }

    /** The first of Ignore Words in {@code reason} (at the start of a word, case ignored), or null. */
    private String ignoredWord(String reason) {
        String text = reason.toLowerCase(Locale.ROOT);
        String words = ignoreWords.get() == null ? "" : ignoreWords.get();
        for (String part : words.split("\\|")) {
            String w = part.strip().toLowerCase(Locale.ROOT);
            if (w.isEmpty()) continue;
            if (Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(w)).matcher(text).find()) return part.strip();
        }
        return null;
    }

    /**
     * Why a disconnect with this reason must never be retried ("banned", "AFK kick", ...), or null when retrying is
     * fine (a timeout, a restart, a lost connection). Looks at vanilla's translation keys first, then the plain text
     * servers write themselves. Ignore Words come on top of this.
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

    // ------------------------------------------------------------------ disconnect screen

    /** From AFTER_INIT on a disconnect screen: the Cancel and Reconnect Now buttons along the bottom. */
    public static void addButtons(Screen screen) {
        AutoReconnect ar = instance();
        if (ar == null || !ar.isEnabled() || !ar.showButtons.get() || last == null) return;
        int x = screen.width / 2, y = screen.height - 28;
        ButtonWidget cancel = ButtonWidget.builder(Text.literal("Cancel"), b -> {
            cancelled = true;
            disconnectedAt = -1;
        }).dimensions(x - 100, y, 98, 20).tooltip(Tooltip.of(Text.literal("Stay on this screen instead of reconnecting"))).build();
        ButtonWidget now = ButtonWidget.builder(Text.literal("Reconnect Now"), b -> {
            cancelled = false;
            force = true;
            disconnectedAt = 0;
        }).dimensions(x + 2, y, 98, 20).build();
        var buttons = Screens.getButtons(screen);
        buttons.add(cancel);
        buttons.add(now);
        ScreenEvents.beforeRender(screen).register((s, ctx, mx, my, delta) -> {
            if (s instanceof DisconnectedScreen) inspect(s);
            boolean show = ar.isEnabled() && last != null;
            cancel.visible = now.visible = show;
            boolean blocked = skipReason != null || ar.gaveUp();
            now.setMessage(Text.literal(blocked ? "Try anyway" : "Reconnect Now"));
            now.active = !force;
            cancel.setMessage(Text.literal(cancelled ? "Cancelled" : "Cancel"));
            cancel.active = !cancelled && !blocked && !force;
        });
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
        float bar = -1;
        if (force) {
            s = "Reconnecting to " + last.address + "...";
            color = ar.countdownColor.get();
        } else if (skipReason != null) {
            s = "Auto reconnect skipped: " + skipReason;
        } else if (ar.gaveUp()) {
            s = "Gave up after " + attempts + (attempts == 1 ? " attempt" : " attempts");
        } else if (cancelled) {
            s = "Auto reconnect cancelled";
        } else {
            if (disconnectedAt < 0 || ar.countdownStyle.is("Hidden")) return;
            long wait = ar.waitMillis();
            long left = Math.max(0, wait - (System.currentTimeMillis() - disconnectedAt));
            s = "Reconnecting to " + last.address + " in " + String.format(Locale.ROOT, "%.1f", left / 1000.0) + "s"
                    + (attempts > 0 ? " (attempt " + (attempts + 1) + (max > 0 ? "/" + max : "") + ")" : "");
            color = ar.countdownColor.get();
            if (ar.countdownStyle.is("Text + Bar")) bar = wait <= 0 ? 0 : Math.min(1f, left / (float) wait);
        }
        TextRenderer tr = mc.textRenderer;
        ctx.drawTextWithShadow(tr, s, (width - tr.getWidth(s)) / 2, 12, color);
        if (bar >= 0) {
            int x0 = width / 2 - 100;
            ctx.fill(x0, 24, x0 + 200, 26, 0x40FFFFFF);
            ctx.fill(x0, 24, x0 + Math.round(200 * bar), 26, 0xFF000000 | color);
        }
    }

    // ------------------------------------------------------------------ title screen

    /** From AFTER_INIT on the title screen: "Rejoin <server>" for the last server, with Title Button on. */
    public static void addTitleButton(Screen screen) {
        AutoReconnect ar = instance();
        if (ar == null || !ar.isEnabled() || !ar.titleButton.get()) return;
        String value = ar.lastServer.get();
        int bar = value == null ? -1 : value.lastIndexOf('|');
        if (bar < 0) return;
        String address = value.substring(bar + 1).strip();
        String name = value.substring(0, bar).strip();
        if (address.isEmpty() || !ServerAddress.isValid(address)) return;
        if (name.isEmpty()) name = address;
        MinecraftClient mc = EvadeClient.mc();
        TextRenderer tr = mc.textRenderer;
        String prefix = "Rejoin ";
        int room = TITLE_W - 8 - tr.getWidth(prefix);
        String shown = tr.getWidth(name) <= room ? name : tr.trimToWidth(name, room - tr.getWidth("...")).strip() + "...";
        String serverName = name;
        Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal(prefix + shown), b -> rejoin(screen, mc, serverName, address))
                .dimensions(TITLE_X, TITLE_Y, TITLE_W, 20)
                .tooltip(Tooltip.of(Text.literal("Join " + serverName + " (" + address + ") again"))).build());
    }

    /** Connects to {@code address}, with its server list entry (and resource pack choice) when it has one. */
    private static void rejoin(Screen parent, MinecraftClient mc, String name, String address) {
        ServerInfo info = null;
        try {
            ServerList list = new ServerList(mc);
            list.loadFile();
            info = list.get(address);
        } catch (RuntimeException e) {
            EvadeClient.LOG.warn("AutoReconnect couldn't read the server list", e);
        }
        if (info == null) info = new ServerInfo(name, address, ServerInfo.ServerType.OTHER);
        ConnectScreen.connect(parent, mc, ServerAddress.parse(address), info, false, null);
    }
}
