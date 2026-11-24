package net.evadeclient.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * What evadeclient.com last told us about our friends, requests, blocks and party: an immutable snapshot, replaced as
 * a whole ({@link SocialClient#state()}), so the menu and HUD can read it from any thread without locking.
 */
public record SocialState(boolean loaded, UUID me, String myName, boolean allowRequests, boolean shareServer,
                          List<Person> friends, List<Request> incoming, List<Request> outgoing, List<Person> blocked,
                          Party party, List<Invite> invites) {

    /** A friend, party member or blocked player. {@code server} is null unless they share it and are online. */
    public record Person(UUID uuid, String name, boolean online, String server, long lastSeen, boolean leader) {
        public Person withStatus(String newName, boolean on, String srv) {
            return new Person(uuid, newName == null || newName.isEmpty() ? name : newName, on, on ? srv : null,
                    on ? lastSeen : System.currentTimeMillis(), leader);
        }
    }

    /** A friend request, incoming or outgoing. */
    public record Request(UUID uuid, String name, long at) {}

    /** The party you're in. */
    public record Party(String id, UUID leader, List<Person> members, List<Person> invited) {
        public boolean has(UUID u) { return members.stream().anyMatch(p -> p.uuid().equals(u)); }
    }

    /** An invite to someone's party (lapses after 10 minutes). */
    public record Invite(String party, UUID from, String fromName, int size, long at) {}

    public static final SocialState EMPTY = new SocialState(false, null, "", true, true, List.of(), List.of(), List.of(),
            List.of(), null, List.of());

    public boolean isFriend(UUID u) { return u != null && friends.stream().anyMatch(p -> p.uuid().equals(u)); }

    public boolean inParty() { return party != null; }

    public boolean isLeader() { return party != null && me != null && me.equals(party.leader()); }

    public int onlineFriends() { return (int) friends.stream().filter(Person::online).count(); }

    /** A friend (or party member, or request) by name, ignoring case, or null. */
    public UUID findByName(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.ROOT);
        for (Person p : friends) if (p.name().toLowerCase(Locale.ROOT).equals(n)) return p.uuid();
        if (party != null) for (Person p : party.members()) if (p.name().toLowerCase(Locale.ROOT).equals(n)) return p.uuid();
        for (Request r : incoming) if (r.name().toLowerCase(Locale.ROOT).equals(n)) return r.uuid();
        for (Request r : outgoing) if (r.name().toLowerCase(Locale.ROOT).equals(n)) return r.uuid();
        for (Person p : blocked) if (p.name().toLowerCase(Locale.ROOT).equals(n)) return p.uuid();
        return null;
    }

    /** This state with one friend's online state and server changed (a friend.status event), friends re-sorted. */
    public SocialState withFriendStatus(UUID u, String name, boolean online, String server) {
        List<Person> f = new ArrayList<>(friends.size());
        boolean changed = false;
        for (Person p : friends) {
            if (p.uuid().equals(u)) { f.add(p.withStatus(name, online, server)); changed = true; }
            else f.add(p);
        }
        Party pt = party;
        if (pt != null && pt.has(u)) {
            List<Person> m = new ArrayList<>();
            for (Person p : pt.members()) m.add(p.uuid().equals(u) ? p.withStatus(name, online, server) : p);
            pt = new Party(pt.id(), pt.leader(), List.copyOf(m), pt.invited());
            changed = true;
        }
        if (!changed) return this;
        f.sort(ORDER);
        return new SocialState(loaded, me, myName, allowRequests, shareServer, List.copyOf(f), incoming, outgoing, blocked, pt, invites);
    }

    /** Online first, then by name. */
    public static final Comparator<Person> ORDER = Comparator.comparing((Person p) -> !p.online())
            .thenComparing(p -> p.name().toLowerCase(Locale.ROOT));

    // ------------------------------------------------------------------ JSON (the server's "state" object)

    public static SocialState parse(JsonObject o) {
        JsonObject me = obj(o, "me");
        List<Person> friends = new ArrayList<>();
        for (JsonElement e : arr(o, "friends")) { Person p = person(e); if (p != null) friends.add(p); }
        friends.sort(ORDER);
        List<Person> blocked = new ArrayList<>();
        for (JsonElement e : arr(o, "blocked")) { Person p = person(e); if (p != null) blocked.add(p); }
        Party party = null;
        if (o.has("party") && o.get("party").isJsonObject()) {
            JsonObject p = o.getAsJsonObject("party");
            List<Person> members = new ArrayList<>(), invited = new ArrayList<>();
            for (JsonElement e : arr(p, "members")) { Person m = person(e); if (m != null) members.add(m); }
            for (JsonElement e : arr(p, "invited")) { Person m = person(e); if (m != null) invited.add(m); }
            party = new Party(str(p, "id"), uuid(str(p, "leader")), List.copyOf(members), List.copyOf(invited));
        }
        List<Invite> invites = new ArrayList<>();
        for (JsonElement e : arr(o, "invites")) {
            if (!e.isJsonObject()) continue;
            JsonObject i = e.getAsJsonObject();
            JsonObject from = obj(i, "from");
            UUID fu = uuid(str(from, "uuid"));
            if (fu != null) invites.add(new Invite(str(i, "party"), fu, str(from, "name"), num(i, "size"), lng(i, "at")));
        }
        return new SocialState(true, uuid(str(me, "uuid")), str(me, "name"), !"nobody".equals(str(me, "allowRequests")),
                !me.has("shareServer") || me.get("shareServer").getAsBoolean(), List.copyOf(friends),
                requests(arr(o, "incoming")), requests(arr(o, "outgoing")), List.copyOf(blocked), party, List.copyOf(invites));
    }

    private static List<Request> requests(JsonArray a) {
        List<Request> out = new ArrayList<>();
        for (JsonElement e : a) {
            if (!e.isJsonObject()) continue;
            JsonObject r = e.getAsJsonObject();
            UUID u = uuid(str(r, "uuid"));
            if (u != null) out.add(new Request(u, str(r, "name"), lng(r, "at")));
        }
        return List.copyOf(out);
    }

    static Person person(JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        UUID u = uuid(str(o, "uuid"));
        if (u == null) return null;
        String server = o.has("server") && o.get("server").isJsonPrimitive() ? o.get("server").getAsString() : null;
        SocialPresence.read(u, o);
        return new Person(u, str(o, "name"), bool(o, "online"), server, lng(o, "lastSeen"), bool(o, "leader"));
    }

    /** A UUID from the server's 32-hex form (or the dashed form), or null. */
    public static UUID uuid(String s) {
        if (s == null) return null;
        String h = s.replace("-", "");
        if (h.length() != 32) return null;
        try {
            return new UUID(Long.parseUnsignedLong(h.substring(0, 16), 16), Long.parseUnsignedLong(h.substring(16), 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The server's form of a UUID: 32 lower-case hex digits. */
    public static String hex(UUID u) { return u.toString().replace("-", ""); }

    public static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : new JsonObject();
    }

    public static JsonArray arr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    public static String str(JsonObject o, String k) {
        try { return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : ""; } catch (RuntimeException e) { return ""; }
    }

    public static boolean bool(JsonObject o, String k) {
        try { return o != null && o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsBoolean(); } catch (RuntimeException e) { return false; }
    }

    public static long lng(JsonObject o, String k) {
        try { return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : 0; } catch (RuntimeException e) { return 0; }
    }

    public static int num(JsonObject o, String k) { return (int) lng(o, k); }
}
