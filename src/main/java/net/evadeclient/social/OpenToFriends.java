package net.evadeclient.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.util.Notifications;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.util.NetworkUtils;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Open to Friends: share your singleplayer world with Evade friends and your party, through e4mc (an MIT-licensed
 * tunnel mod the Evade launcher installs when "Open to Friends" is switched on; off by default). Evade runs no relay
 * of its own: e4mc gives the world a public address, and Evade sends that address only to the friends and party
 * members you pick, as an invite with a Join button they have to click (and confirm). It never goes anywhere public.
 *
 * <p>The pause menu gets an "Open to Friends" button in singleplayer. Without e4mc it explains how to turn it on in the
 * launcher. The address is read from e4mc's own "Local game hosted on domain [...]" chat line (e4mc has no API).
 */
public final class OpenToFriends {
    /** e4mc tunnel hosts: "name.region.e4mc.link" (evadeclient.com accepts exactly these). */
    private static final Pattern E4MC = Pattern.compile("^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.){1,4}e4mc\\.link(?::\\d{1,5})?$");
    private static final String KEY_ASSIGNED = "text.e4mc_minecraft.domainAssigned", KEY_CLOSED = "text.e4mc_minecraft.closeServer";
    private static final long INVITE_MS = 10 * 60_000L;

    /** An invite to a friend's world (10 minutes). */
    public record Invite(UUID from, String fromName, String address, String world, long at) {}

    private static volatile String address;
    private static volatile long startedAt;
    private static final List<Invite> INVITES = new ArrayList<>();
    private static boolean registered;
    private static Boolean e4mcForTest;

    private OpenToFriends() {}

    /** Adds the pause-menu button. Safe to call more than once. */
    public static synchronized void init() {
        if (registered) return;
        registered = true;
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof GameMenuScreen && client.isIntegratedServerRunning() && client.getServer() != null
                    && !net.evadeclient.replay.ReplayPlayer.active()) {
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal(buttonLabel()),
                                b -> client.setScreen(new net.evadeclient.gui.OpenToFriendsScreen(screen)))
                        .dimensions(w - 6 - 130, 6, 130, 20)
                        .tooltip(Tooltip.of(Text.literal(e4mcInstalled()
                                ? "Invite Evade friends and your party to this world. Only the people you pick get the address."
                                : "Needs the Open to Friends extra: turn it on in the Evade launcher"))).build());
            }
        });
    }

    /** "Open to Friends", or "3 friends in your world" while it's open. */
    public static String buttonLabel() {
        if (!open()) return "Open to Friends";
        int n = friendsInWorld();
        return n == 0 ? "Open to Friends: on" : n + (n == 1 ? " friend" : " friends") + " in your world";
    }

    /** Whether e4mc is installed (the launcher's "Open to Friends" extra). */
    public static boolean e4mcInstalled() {
        if (e4mcForTest != null) return e4mcForTest;
        FabricLoader l = FabricLoader.getInstance();
        return l.isModLoaded("e4mc") || l.isModLoaded("e4mc_minecraft");
    }

    /** Whether this world is open to friends right now (e4mc gave it an address). */
    public static boolean open() {
        MinecraftClient mc = MinecraftClient.getInstance();
        IntegratedServer s = mc.getServer();
        if (address != null && (s == null || !s.isRemote())) address = null; // world closed or left
        return address != null;
    }

    /** The world's e4mc address, or null. Only ever shown to you and sent to the people you invite. */
    public static String address() { return open() ? address : null; }

    /** Whether we asked e4mc for an address in the last 30 seconds and are still waiting. */
    public static boolean starting() { return address == null && startedAt > 0 && System.currentTimeMillis() - startedAt < 30_000; }

    /** Whether we asked and nothing came back in 30 seconds. */
    public static boolean timedOut() { return address == null && startedAt > 0 && !starting(); }

    /**
     * Opens the world to LAN, which e4mc turns into a public tunnel (keeping your game mode; commands as the world has
     * them). Render thread. Returns an error to show, or null.
     */
    public static String start() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!e4mcInstalled()) return "e4mc isn't installed";
        IntegratedServer s = mc.getServer();
        if (s == null || !mc.isIntegratedServerRunning()) return "Open a singleplayer world first";
        startedAt = System.currentTimeMillis();
        if (s.isRemote()) {
            // Already open to LAN (e4mc started with it): its address came by in chat, unless that was before we listened.
            if (address == null) return "This world is already open to LAN. Close and reopen the world to share it with friends.";
            return null;
        }
        GameMode mode = mc.interactionManager == null ? GameMode.SURVIVAL : mc.interactionManager.getCurrentGameMode();
        boolean cheats = s.getSaveProperties().areCommandsAllowed();
        try {
            if (!s.openToLan(mode, cheats, NetworkUtils.findLocalPort())) return "The world couldn't be opened";
        } catch (RuntimeException e) {
            EvadeClient.LOG.warn("Open to Friends: couldn't open the world", e);
            return "The world couldn't be opened";
        }
        return null;
    }

    /** Stops sharing (e4mc's own "/e4mc stop"; the world stays open to LAN until you leave it). */
    public static void stop() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && mc.player.networkHandler != null) mc.player.networkHandler.sendChatCommand("e4mc stop");
        address = null;
        startedAt = 0;
    }

    /** Evade friends and party members playing in your world right now. Render thread. */
    public static int friendsInWorld() {
        MinecraftClient mc = MinecraftClient.getInstance();
        IntegratedServer s = mc.getServer();
        if (s == null) return 0;
        SocialState st = SocialClient.state();
        int n = 0;
        try {
            for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
                UUID u = p.getUuid();
                if (u.equals(st.me()) || (mc.player != null && u.equals(mc.player.getUuid()))) continue;
                if (st.isFriend(u) || (st.party() != null && st.party().has(u))) n++;
            }
        } catch (RuntimeException e) {
            return 0;
        }
        return n;
    }

    /** Friends and party members who are online, to pick from (party members first, then friends by name). */
    public static List<SocialState.Person> candidates() {
        SocialState st = SocialClient.state();
        List<SocialState.Person> out = new ArrayList<>();
        if (st.party() != null) for (SocialState.Person p : st.party().members()) if (p.online() && !p.uuid().equals(st.me())) out.add(p);
        for (SocialState.Person p : st.friends()) {
            if (p.online() && out.stream().noneMatch(q -> q.uuid().equals(p.uuid()))) out.add(p);
        }
        return out;
    }

    /** Sends the world's address to {@code who} (friends or party members) through evadeclient.com. */
    public static CompletableFuture<JsonObject> invite(Collection<UUID> who) {
        String a = address();
        if (a == null) return CompletableFuture.failedFuture(new SocialClient.SocialException("Open the world to friends first", 409, 0));
        if (who.isEmpty()) return CompletableFuture.failedFuture(new SocialClient.SocialException("Pick who to invite", 400, 0));
        JsonObject b = new JsonObject();
        JsonArray list = new JsonArray();
        for (UUID u : who) list.add(SocialState.hex(u));
        b.add("uuids", list);
        b.addProperty("address", a);
        MinecraftClient mc = MinecraftClient.getInstance();
        IntegratedServer s = mc.getServer();
        if (s != null) b.addProperty("world", s.getSaveProperties().getLevelName());
        return SocialClient.call("world.invite", b);
    }

    // ------------------------------------------------------------------ e4mc's chat line

    /** Every chat line the client adds (render thread): picks up e4mc's address when it announces one. */
    public static void onChat(Text message) {
        if (message == null || !(message.getContent() instanceof TranslatableTextContent t)) return;
        if (t.getKey().equals(KEY_CLOSED)) { address = null; startedAt = 0; return; }
        if (!t.getKey().equals(KEY_ASSIGNED)) return;
        String found = null;
        for (Object arg : t.getArgs()) {
            if (arg instanceof Text a) {
                found = domainIn(a);
                if (found != null) break;
            } else if (arg instanceof String str && valid(str)) {
                found = normal(str);
                break;
            }
        }
        if (found != null) {
            address = found;
            startedAt = 0;
        }
    }

    /** The domain in e4mc's argument: its copy-to-clipboard click (works when e4mc hides the domain), or its text. */
    private static String domainIn(Text a) {
        String[] hit = new String[1];
        a.visit((style, str) -> {
            if (style.getClickEvent() instanceof ClickEvent.CopyToClipboard c && valid(c.value())) { hit[0] = normal(c.value()); return java.util.Optional.of(Boolean.TRUE); }
            return java.util.Optional.empty();
        }, net.minecraft.text.Style.EMPTY);
        if (hit[0] != null) return hit[0];
        String s = a.getString();
        return valid(s) ? normal(s) : null;
    }

    private static String normal(String s) { return s.strip().toLowerCase(Locale.ROOT); }

    /** Whether {@code s} is an e4mc address. */
    public static boolean valid(String s) { return s != null && E4MC.matcher(normal(s)).matches(); }

    // ------------------------------------------------------------------ invites you get

    /** A world.invite event (any thread): a toast, and a [Join] line in chat when you're in a world. */
    static void received(JsonObject o) {
        JsonObject from = SocialState.obj(o, "from");
        UUID u = SocialState.uuid(SocialState.str(from, "uuid"));
        String addr = normal(SocialState.str(o, "address"));
        if (u == null || !valid(addr) || !SocialJoin.validAddress(addr)) return;
        String name = MojangNames.shown(u, SocialState.str(from, "name"));
        String world = SocialState.str(o, "world");
        MinecraftClient.getInstance().execute(() -> {
            SocialState s = SocialClient.state();
            if (s.blocked().stream().anyMatch(p -> p.uuid().equals(u))) return;
            synchronized (INVITES) {
                INVITES.removeIf(i -> i.from().equals(u) || System.currentTimeMillis() - i.at() > INVITE_MS);
                INVITES.add(new Invite(u, name, addr, world, System.currentTimeMillis()));
                while (INVITES.size() > 10) INVITES.remove(0);
            }
            boolean inGame = MinecraftClient.getInstance().world != null;
            String what = world.isBlank() ? "their world" : "their world \"" + world + "\"";
            Notifications.push("World invite", name + " invited you to " + what + (inGame ? ": [Join] in chat" : ": Join on the Friends page, Requests"),
                    Notifications.Level.SUCCESS, 10_000);
            if (inGame) SocialJoin.postWorldInvite(name, addr, world);
        });
    }

    /** World invites from the last 10 minutes, newest last. */
    public static List<Invite> invites() {
        synchronized (INVITES) {
            INVITES.removeIf(i -> System.currentTimeMillis() - i.at() > INVITE_MS);
            return List.copyOf(INVITES);
        }
    }

    /** Drops an invite (joined or dismissed). */
    public static void dismiss(Invite i) {
        synchronized (INVITES) { INVITES.remove(i); }
    }

    /** Asks "Join Steve's world?" and connects only on Yes. Render thread. */
    public static void join(Invite i) {
        dismiss(i);
        SocialJoin.ask(i.fromName() + "'s world", i.address());
    }

    // ------------------------------------------------------------------ test support

    /** Test hook: pretend e4mc is (or isn't) installed; null: ask Fabric. */
    public static void e4mcForTest(Boolean installed) { e4mcForTest = installed; }

    /** Test hook: the address e4mc last announced (even with no world open to LAN), or null. */
    public static String announcedForTest() { return address; }

    /** Test hook: clears the address and invites. */
    public static void resetForTest() {
        address = null;
        startedAt = 0;
        synchronized (INVITES) { INVITES.clear(); }
    }
}
