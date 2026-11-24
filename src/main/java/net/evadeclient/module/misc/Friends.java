package net.evadeclient.module.misc;

import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.command.CommandManager;
import net.evadeclient.command.CommandManager.CommandError;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.StringSetting;
import net.evadeclient.social.SocialChat;
import net.evadeclient.social.SocialJoin;
import net.evadeclient.setting.StatusSetting;
import net.evadeclient.social.MojangNames;
import net.evadeclient.social.SocialClient;
import net.evadeclient.social.SocialState;
import net.evadeclient.util.Notifications;
import net.minecraft.client.MinecraftClient;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Friends and parties through evadeclient.com (the Friends page in the menu, {@code .friend} and {@code .party}).
 * Nothing is sent until you first use Friends ({@link #activate}); from then on it connects whenever you play,
 * once Evade Icons has proven your account. Switch this off to disconnect.
 */
public class Friends extends Module {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    public final BooleanSetting onlineToasts = add(new BooleanSetting("Online Toasts", true)
            .describe("A toast when a friend comes online or goes offline."));
    public final BooleanSetting requestToasts = add(new BooleanSetting("Request Toasts", true)
            .describe("Toasts for friend requests, party invites and people joining or leaving your party."));
    public final BooleanSetting joinPrompts = add(new BooleanSetting("Join Prompts", true)
            .describe("When a friend starts playing on a server they share, a [Join] button in chat. You always confirm before joining."));
    public final BooleanSetting messageToasts = add(new BooleanSetting("Message Toasts", true)
            .describe("A toast when a friend or your party sends you a message."));
    public final BooleanSetting messagesInChat = add(new BooleanSetting("Messages In Chat", true)
            .describe("Also show friend and party messages in your chat, marked [Evade]. Click one to reply. Only you see them."));
    public final BooleanSetting friendColors = add(new BooleanSetting("Friend Colours", true)
            .describe("Shows your friends' names in their own colour in chat and the tab list. Only on your screen."));
    public final ColorSetting friendColor = add(new ColorSetting("Friend Colour", 0xFF5CE6B0)
            .describe("The colour of friends' names."));
    public final StringSetting highlightedPlayers = add(new StringSetting("Highlighted Players", "", "names, separated by ,")
            .maxLength(400)
            .describe("Other players whose names also get the friend colour (they don't need Evade). Kept on this PC only."));
    public final StatusSetting connection = add(new StatusSetting("Connection", SocialClient::status, "auto"));
    /** Set the first time you use Friends: until then nothing is sent to evadeclient.com. */
    private final BooleanSetting activated = add(new BooleanSetting("Activated", false).hidden());

    public Friends() {
        super("Friends", "Friends and parties: see who's online and where, invite friends to a party and share pings.", Category.MISC);
        setVisible(false);
        setEnabled(true);
        SocialClient.init();
    }

    public static Friends get() {
        return EvadeClient.modules == null ? null : EvadeClient.modules.get(Friends.class);
    }

    @Override protected boolean alwaysRunHooks() { return true; }

    @Override protected void onDisable() { SocialClient.stop("Off"); }

    public boolean activated() { return activated.get(); }

    /** You used Friends: start connecting (and remember it). */
    public void activate() {
        if (!isEnabled()) setEnabled(true);
        if (activated.get()) return;
        activated.set(true);
        if (EvadeClient.config != null) EvadeClient.config.save();
    }

    /** Your social data was deleted: stay quiet until you use Friends again. */
    public void deactivate() {
        activated.set(false);
        if (EvadeClient.config != null) EvadeClient.config.save();
    }

    // ------------------------------------------------------------------ actions (shared by the menu and commands)

    /**
     * Sends a friend request to {@code name}: asks Mojang for that account's UUID first (names other players report
     * are never trusted), falling back to the name alone when Mojang can't be reached.
     */
    public static CompletableFuture<JsonObject> addFriend(String name) {
        if (name == null || !NAME.matcher(name).matches()) return CompletableFuture.failedFuture(new SocialClient.SocialException("Type a Minecraft name", 400, 0));
        return MojangNames.lookup(name).handle((uuid, err) -> {
            JsonObject b = new JsonObject();
            b.addProperty("name", name);
            if (err == null && uuid == null) throw new SocialClient.SocialException("There's no Minecraft player called " + name, 404, 0);
            if (uuid != null) b.addProperty("uuid", SocialState.hex(uuid));
            return b;
        }).thenCompose(b -> SocialClient.call("friend.request", b));
    }

    /** {@code op} with {"uuid": u}. */
    public static CompletableFuture<JsonObject> withUuid(String op, UUID u) {
        JsonObject b = new JsonObject();
        b.addProperty("uuid", SocialState.hex(u));
        return SocialClient.call(op, b);
    }

    /** Runs a request and reports how it went as a toast ({@code done} gets the answer on the game thread). */
    public static void run(CompletableFuture<JsonObject> f, String title, String success, Consumer<JsonObject> done) {
        f.whenComplete((r, e) -> MinecraftClient.getInstance().execute(() -> {
            if (e != null) Notifications.push(title, SocialClient.message(e), Notifications.Level.WARN, 4000);
            else {
                if (success != null) Notifications.push(title, success, true);
                if (done != null) done.accept(r);
            }
        }));
    }

    // ------------------------------------------------------------------ commands

    @Override public List<String> commandAliases() { return List.of("friend", "friends", "party", "msg", "pc", "reply", "join"); }

    @Override
    public List<String> commandHelp() {
        return List.of("§d.friend§7 add|remove|accept|decline|block|unblock <name>, list, requests - friends",
                "§d.party§7 invite|kick|leader <name>, accept|decline [name], leave, list, bring - parties",
                "§d.msg§7 <friend> <text>, §d.reply§7 <text>, §d.pc§7 <text> - message a friend or your party (through Evade, not the server)",
                "§d.join§7 <friend> - join the server a friend is on (you confirm first)");
    }

    @Override
    public void command(String[] a) {
        activate();
        String word = a[0].toLowerCase(Locale.ROOT);
        if (!SocialClient.ready()) {
            CommandManager.print("§7Friends: " + SocialClient.status() + (SocialClient.link() == SocialClient.Link.OFF ? "" : " §8(try again in a moment)"));
            return;
        }
        switch (word) {
            case "msg", "reply" -> { message(a, word.equals("reply")); return; }
            case "pc" -> {
                if (a.length < 2) throw new CommandError("Usage: .pc <message>");
                String text = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
                sendMessage(SocialChat.PARTY, "Party", text);
                return;
            }
            case "join" -> {
                SocialState s = SocialClient.state();
                UUID u = friend(s, need(a.length > 1 ? a[1] : null, ".join <friend>"));
                SocialState.Person p = s.friends().stream().filter(x -> x.uuid().equals(u)).findFirst().orElse(null);
                if (p == null || !p.online() || p.server() == null) throw new CommandError(a[1] + " isn't sharing a server right now");
                String name = shown(u, p.name()), server = p.server();
                MinecraftClient.getInstance().send(() -> SocialJoin.ask(name, server)); // once chat has closed
                return;
            }
            default -> { }
        }
        boolean party = a[0].equalsIgnoreCase("party");
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
        String arg = a.length > 2 ? a[2] : null;
        SocialState s = SocialClient.state();
        if (party) partyCommand(sub, arg, s); else friendCommand(sub, arg, a, s);
    }

    private void friendCommand(String sub, String arg, String[] a, SocialState s) {
        switch (sub) {
            case "add" -> {
                String n = need(arg, ".friend add <name>");
                say(addFriend(n), r -> SocialState.bool(r, "accepted") ? "You're now friends with " + n : "Friend request sent to " + n);
            }
            case "remove" -> say(withUuid("friend.remove", known(s, need(arg, ".friend remove <name>"))), r -> "Removed " + arg);
            case "accept" -> say(withUuid("friend.accept", known(s, need(arg, ".friend accept <name>"))), r -> "You're now friends with " + arg);
            case "decline" -> say(withUuid("friend.decline", known(s, need(arg, ".friend decline <name>"))), r -> "Declined " + arg);
            case "cancel" -> say(withUuid("friend.cancel", known(s, need(arg, ".friend cancel <name>"))), r -> "Cancelled your request to " + arg);
            case "block" -> {
                String n = need(arg, ".friend block <name>");
                UUID u = s.findByName(n);
                CompletableFuture<JsonObject> f = u != null ? withUuid("block", u)
                        : MojangNames.lookup(n).exceptionally(e -> null).thenCompose(id -> {
                            JsonObject b = new JsonObject();
                            if (id != null) b.addProperty("uuid", SocialState.hex(id)); else b.addProperty("name", n);
                            return SocialClient.call("block", b);
                        });
                say(f, r -> "Blocked " + n + ": no requests, invites, messages or pings from them");
            }
            case "unblock" -> say(withUuid("unblock", known(s, need(arg, ".friend unblock <name>"))), r -> "Unblocked " + arg);
            case "report" -> {
                UUID u = known(s, need(arg, ".friend report <name> <what happened>"));
                if (a.length < 4) throw new CommandError("Usage: .friend report <name> <what happened>");
                JsonObject b = new JsonObject();
                b.addProperty("uuid", SocialState.hex(u));
                b.addProperty("reason", String.join(" ", Arrays.copyOfRange(a, 3, a.length)));
                say(SocialClient.call("report", b), r -> "Report sent. Thanks");
            }
            case "requests" -> {
                if (s.incoming().isEmpty() && s.outgoing().isEmpty()) CommandManager.print("§7No friend requests");
                for (var r : s.incoming()) CommandManager.print("§d" + shown(r.uuid(), r.name()) + "§7 wants to be friends §8(.friend accept " + shown(r.uuid(), r.name()) + ")");
                for (var r : s.outgoing()) CommandManager.print("§7Waiting for §f" + shown(r.uuid(), r.name()));
            }
            case "list" -> {
                if (s.friends().isEmpty()) { CommandManager.print("§7No friends yet. §d.friend add <name>"); return; }
                CommandManager.print("§7Friends: §f" + s.onlineFriends() + "§7 of " + s.friends().size() + " online");
                for (var p : s.friends()) CommandManager.print((p.online() ? "§a● §f" : "§8● §7") + shown(p.uuid(), p.name())
                        + (p.server() != null ? " §8on §7" + p.server() : ""));
            }
            default -> throw new CommandError("Usage: .friend add|remove|accept|decline|cancel|block|unblock <name>, .friend list|requests");
        }
    }

    private void partyCommand(String sub, String arg, SocialState s) {
        switch (sub) {
            case "create" -> say(SocialClient.call("party.create", new JsonObject()), r -> "Party created. Invite friends with .party invite <name>");
            case "invite" -> say(withUuid("party.invite", friend(s, need(arg, ".party invite <name>"))), r -> "Invited " + arg);
            case "accept", "join" -> {
                SocialState.Invite inv = invite(s, arg);
                JsonObject b = new JsonObject();
                b.addProperty("party", inv.party());
                say(SocialClient.call("party.accept", b), r -> "You joined " + shown(inv.from(), inv.fromName()) + "'s party");
            }
            case "decline" -> {
                SocialState.Invite inv = invite(s, arg);
                JsonObject b = new JsonObject();
                b.addProperty("party", inv.party());
                say(SocialClient.call("party.decline", b), r -> "Declined");
            }
            case "leave" -> say(SocialClient.call("party.leave", new JsonObject()), r -> "You left the party");
            case "bring", "summon" -> say(SocialJoin.summonParty(), r -> "Asked your party to join you ("
                    + SocialState.num(r, "delivered") + " online)");
            case "kick" -> say(withUuid("party.kick", member(s, need(arg, ".party kick <name>"))), r -> "Removed " + arg + " from the party");
            case "leader", "transfer" -> say(withUuid("party.transfer", member(s, need(arg, ".party leader <name>"))), r -> arg + " leads the party now");
            case "list" -> {
                if (s.party() == null) {
                    CommandManager.print("§7You're not in a party. §d.party invite <friend>");
                    for (var i : s.invites()) CommandManager.print("§d" + shown(i.from(), i.fromName()) + "§7 invited you §8(.party accept " + shown(i.from(), i.fromName()) + ")");
                    return;
                }
                CommandManager.print("§7Party: §f" + s.party().members().size() + "§7 of 8");
                for (var p : s.party().members()) CommandManager.print((p.online() ? "§a● §f" : "§8● §7") + shown(p.uuid(), p.name())
                        + (p.leader() ? " §c(leader)" : "") + (p.server() != null ? " §8on §7" + p.server() : ""));
            }
            default -> throw new CommandError("Usage: .party invite|kick|leader <name>, .party accept|decline [name], .party leave|list");
        }
    }

    /** {@code .msg <friend> <text>} or {@code .reply <text>} (to whoever messaged you last). */
    private void message(String[] a, boolean reply) {
        SocialState s = SocialClient.state();
        UUID u;
        int from;
        if (reply) {
            u = SocialChat.lastFrom();
            if (u == null) throw new CommandError("Nobody has messaged you yet");
            from = 1;
        } else {
            u = friend(s, need(a.length > 1 ? a[1] : null, ".msg <friend> <message>"));
            from = 2;
        }
        if (a.length <= from) throw new CommandError(reply ? "Usage: .reply <message>" : "Usage: .msg <friend> <message>");
        String name = s.friends().stream().filter(p -> p.uuid().equals(u)).map(p -> shown(p.uuid(), p.name())).findFirst().orElse("them");
        sendMessage(u, name, String.join(" ", Arrays.copyOfRange(a, from, a.length)));
    }

    /** Sends a message and echoes it in chat, or prints why it couldn't go. */
    private static void sendMessage(UUID to, String name, String text) {
        String t = SocialChat.clean(text);
        if (t.isEmpty()) throw new CommandError("Type a message");
        if (text.strip().codePointCount(0, text.strip().length()) > SocialChat.MAX_CHARS)
            throw new CommandError("Messages are " + SocialChat.MAX_CHARS + " characters at most");
        SocialChat.send(to, t).whenComplete((r, e) -> MinecraftClient.getInstance().execute(() -> {
            if (e != null) CommandManager.print("§c" + SocialClient.message(e));
            else SocialChat.echo(to, name, t);
        }));
    }

    private static String need(String arg, String usage) {
        if (arg == null || arg.isBlank()) throw new CommandError("Usage: " + usage);
        return arg;
    }

    private static UUID known(SocialState s, String name) {
        UUID u = s.findByName(name);
        if (u == null) throw new CommandError("Nobody called " + name + " in your friends, requests or party");
        return u;
    }

    private static UUID friend(SocialState s, String name) {
        UUID u = s.findByName(name);
        if (u == null || !s.isFriend(u)) throw new CommandError(name + " isn't your friend. §d.friend add " + name);
        return u;
    }

    private static UUID member(SocialState s, String name) {
        UUID u = s.findByName(name);
        if (s.party() == null || u == null || !s.party().has(u)) throw new CommandError(name + " isn't in your party");
        return u;
    }

    private static SocialState.Invite invite(SocialState s, String name) {
        if (s.invites().isEmpty()) throw new CommandError("No party invites right now");
        if (name == null) return s.invites().get(0);
        for (var i : s.invites()) if (i.fromName().equalsIgnoreCase(name) || shown(i.from(), i.fromName()).equalsIgnoreCase(name)) return i;
        throw new CommandError("No invite from " + name);
    }

    private static String shown(UUID u, String name) { return MojangNames.shown(u, name); }

    /** Prints the answer in chat ({@code ok} builds the line), or the error in red. */
    private static void say(CompletableFuture<JsonObject> f, java.util.function.Function<JsonObject, String> ok) {
        f.whenComplete((r, e) -> MinecraftClient.getInstance().execute(() ->
                CommandManager.print(e != null ? "§c" + SocialClient.message(e) : "§7" + ok.apply(r))));
    }
}
