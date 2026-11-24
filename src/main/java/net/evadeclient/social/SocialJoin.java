package net.evadeclient.social;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.evadeclient.EvadeClient;
import net.evadeclient.util.Notifications;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * "Join friend" and "Bring party here". Joining is always the player's own choice: a Join button (the Friends page, or
 * [Join] in chat after a friend-online or party-summon message) opens a confirmation that shows the address, and only
 * Yes connects. Nothing here ever connects on its own.
 *
 * <p>The [Join] chat buttons run the local client command {@code /evadesocial join <code>}, where the code is a random
 * one-time token for one offer this game made: a server can't put its own address behind a button that looks like
 * ours, because a code it makes up matches nothing.
 */
public final class SocialJoin {
    public static final String COMMAND = "evadesocial";
    /** Public host names and addresses with an optional port; the server normalised it already (no LAN, no paths). */
    private static final Pattern ADDRESS = Pattern.compile("^(?:[a-z0-9._-]{1,253}|\\[[0-9a-f:.]{2,45}])(?::\\d{1,5})?$");
    private static final long OFFER_MS = 10 * 60_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** An address someone offered you, behind a one-time code. */
    public record Offer(String who, String address, boolean summon, long at) {}

    private static final Map<String, Offer> OFFERS = new LinkedHashMap<>();
    private static boolean registered;

    private SocialJoin() {}

    /** Registers {@code /evadesocial}. Safe to call more than once. */
    public static synchronized void init() {
        if (registered) return;
        registered = true;
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                ClientCommandManager.literal(COMMAND)
                        .then(ClientCommandManager.literal("join").then(ClientCommandManager.argument("code", StringArgumentType.word())
                                .executes(ctx -> { runOffer(StringArgumentType.getString(ctx, "code"), true); return 1; })))
                        .then(ClientCommandManager.literal("dismiss").then(ClientCommandManager.argument("code", StringArgumentType.word())
                                .executes(ctx -> { runOffer(StringArgumentType.getString(ctx, "code"), false); return 1; })))));
    }

    /** Whether {@code address} is something Join will connect to. */
    public static boolean validAddress(String address) {
        return address != null && ADDRESS.matcher(address).matches() && ServerAddress.isValid(address);
    }

    /** Whether you're on {@code address} right now. Render thread. */
    public static boolean onServer(String address) {
        String here = SocialClient.currentServer(MinecraftClient.getInstance());
        return here != null && here.equals(SocialClient.normalize(address));
    }

    // ------------------------------------------------------------------ offers in chat

    /** Files an offer and returns its code. */
    static synchronized String offer(String who, String address, boolean summon) {
        long now = System.currentTimeMillis();
        OFFERS.values().removeIf(o -> now - o.at() > OFFER_MS);
        while (OFFERS.size() >= 20) OFFERS.remove(OFFERS.keySet().iterator().next());
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        String code = HexFormat.of().formatHex(b);
        OFFERS.put(code, new Offer(who, address, summon, now));
        return code;
    }

    private static synchronized Offer take(String code) {
        Offer o = OFFERS.remove(code);
        return o == null || System.currentTimeMillis() - o.at() > OFFER_MS ? null : o;
    }

    private static void runOffer(String code, boolean join) {
        Offer o = take(code);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (o == null) {
            if (join) Notifications.push("Join", "That invite has expired", Notifications.Level.WARN, 3000);
            return;
        }
        if (!join) {
            Notifications.push("Join", "Dismissed", false);
            return;
        }
        // After the chat screen has closed, or the confirmation would be replaced at once.
        mc.send(() -> ask(o.who(), o.address()));
    }

    /**
     * A chat line offering to join {@code address}: "[Evade] Steve is on play.example.net [Join]" or, for a party
     * summon, "... wants the party on ... [Join] [Dismiss]". Shown only in game. Render thread.
     */
    public static void postOffer(String who, String address, boolean summon) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud == null || mc.world == null || !validAddress(address) || onServer(address)) return;
        String code = offer(who, address, summon);
        MutableText line = Text.literal("[Evade] ").formatted(Formatting.DARK_GRAY);
        line.append(Text.literal(who).formatted(Formatting.WHITE));
        line.append(Text.literal(summon ? " wants the party on " : " is playing on ").formatted(Formatting.GRAY));
        // Streamer Mode: "a server" instead of the address (the confirmation still names it before you join).
        line.append(Text.literal(net.evadeclient.module.misc.StreamerMode.friendServer(address)).formatted(Formatting.AQUA));
        line.append(Text.literal(" "));
        line.append(button("[Join]", Formatting.GREEN, "/" + COMMAND + " join " + code, "Join " + net.evadeclient.module.misc.StreamerMode.friendServer(address) + " (you'll confirm first)"));
        if (summon) {
            line.append(Text.literal(" "));
            line.append(button("[Dismiss]", Formatting.GRAY, "/" + COMMAND + " dismiss " + code, "Stay here"));
        }
        mc.inGameHud.getChatHud().addMessage(line);
        lastOfferLine = line.getString();
    }

    /**
     * Open to Friends: "[Evade] Steve invited you to their world "Island" [Join] [Dismiss]". The address isn't shown
     * (it's a private tunnel); the confirmation names whose world it is. Render thread.
     */
    public static void postWorldInvite(String who, String address, String world) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud == null || mc.world == null || !validAddress(address)) return;
        String code = offer(who + "'s world", address, false);
        MutableText line = Text.literal("[Evade] ").formatted(Formatting.DARK_GRAY);
        line.append(Text.literal(who).formatted(Formatting.WHITE));
        line.append(Text.literal(" invited you to their world").formatted(Formatting.GRAY));
        if (world != null && !world.isBlank()) line.append(Text.literal(" " + world).formatted(Formatting.AQUA));
        line.append(Text.literal(" "));
        line.append(button("[Join]", Formatting.GREEN, "/" + COMMAND + " join " + code, "Join " + who + "'s world (you'll confirm first)"));
        line.append(Text.literal(" "));
        line.append(button("[Dismiss]", Formatting.GRAY, "/" + COMMAND + " dismiss " + code, "Stay here"));
        mc.inGameHud.getChatHud().addMessage(line);
        lastOfferLine = line.getString();
    }

    private static String lastOfferLine = "";

    /** Test hook: the text of the newest [Join] line posted. */
    public static String lastOfferLineForTest() { return lastOfferLine; }

    private static Text button(String label, Formatting color, String command, String hint) {
        return Text.literal(label).setStyle(Style.EMPTY.withColor(color).withBold(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(hint))));
    }

    // ------------------------------------------------------------------ joining

    /**
     * Asks "Join Steve on play.example.net?" and connects only on Yes (leaving the current world or server first).
     * Render thread. Does nothing for an invalid address or the server you're already on.
     */
    public static void ask(String who, String address) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!validAddress(address)) {
            Notifications.push("Join", "That server address can't be joined", Notifications.Level.WARN, 3000);
            return;
        }
        if (onServer(address)) {
            Notifications.push("Join", "You're already on " + address, false);
            return;
        }
        Screen back = mc.currentScreen;
        String leaving = mc.world == null ? "" : mc.isInSingleplayer() ? " Your world will be saved and closed first."
                : " You'll leave this server first.";
        mc.setScreen(new ConfirmScreen(yes -> {
            if (yes) connect(address);
            else mc.setScreen(back);
        }, Text.literal("Join " + who + "?"), Text.literal("Connect to " + address + "." + leaving),
                Text.literal("Join"), Text.literal("Cancel")));
    }

    /** Leaves the current world or server, then connects to {@code address} (with its server-list entry when there is one). */
    public static void connect(String address) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world != null) {
            boolean sp = mc.isInSingleplayer();
            mc.world.disconnect(Text.literal("Joining a friend"));
            if (sp) mc.disconnectWithSavingScreen(); else mc.disconnectWithProgressScreen();
        }
        ServerInfo info = null;
        try {
            ServerList list = new ServerList(mc);
            list.loadFile();
            info = list.get(address);
        } catch (RuntimeException e) {
            EvadeClient.LOG.warn("Friends: couldn't read the server list", e);
        }
        if (info == null) info = new ServerInfo(address, address, ServerInfo.ServerType.OTHER);
        ConnectScreen.connect(new MultiplayerScreen(new TitleScreen()), mc, ServerAddress.parse(address), info, false, null);
    }

    // ------------------------------------------------------------------ bring party here

    /** Party leader: offers the party the server you're on. Fails at once off a multiplayer server or not leading. */
    public static CompletableFuture<JsonObject> summonParty() {
        MinecraftClient mc = MinecraftClient.getInstance();
        String server = SocialClient.isLoopback() ? "play.example.net" : SocialClient.currentServer(mc);
        SocialState s = SocialClient.state();
        if (!SocialClient.isLoopback()) {
            if (!s.isLeader()) return CompletableFuture.failedFuture(new SocialClient.SocialException("Only the party leader can bring the party", 403, 0));
            if (server == null) return CompletableFuture.failedFuture(new SocialClient.SocialException("Join a multiplayer server first", 400, 0));
        }
        JsonObject b = new JsonObject();
        b.addProperty("server", server);
        return SocialClient.call("party.summon", b);
    }

    /** A party.summon event (any thread): a toast and a [Join] [Dismiss] line in chat. */
    static void summoned(JsonObject o) {
        JsonObject from = SocialState.obj(o, "from");
        UUID u = SocialState.uuid(SocialState.str(from, "uuid"));
        String server = SocialClient.normalize(SocialState.str(o, "server"));
        if (u == null || !validAddress(server)) return;
        String name = MojangNames.shown(u, SocialState.str(from, "name"));
        MinecraftClient.getInstance().execute(() -> {
            SocialState s = SocialClient.state();
            if (s.blocked().stream().anyMatch(p -> p.uuid().equals(u))) return;
            if (onServer(server)) return;
            synchronized (SocialJoin.class) { summon = new Offer(name, server, true, System.currentTimeMillis()); }
            boolean inGame = MinecraftClient.getInstance().world != null;
            Notifications.push("Party", name + " wants the party on " + net.evadeclient.module.misc.StreamerMode.friendServer(server) + (inGame ? ": [Join] in chat" : ": Join on the Party tab"),
                    Notifications.Level.SUCCESS, 8000);
            postOffer(name, server, true);
        });
    }

    /** The leader's latest "Bring party here" (10 minutes), for the Party tab, or null. */
    public static synchronized Offer pendingSummon() {
        Offer o = summon;
        if (o != null && (System.currentTimeMillis() - o.at() > OFFER_MS || onServer(o.address()))) summon = o = null;
        return o;
    }

    /** Dismisses the pending summon (the Party tab's Dismiss). */
    public static synchronized void dismissSummon() { summon = null; }

    private static Offer summon;

    /** Test hook: the number of live offers. */
    public static synchronized int offersForTest() { return OFFERS.size(); }
}
