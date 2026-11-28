package net.evadeclient.gui.menu;

import net.evadeclient.gui.kit.Badge;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.EmptyState;
import net.evadeclient.gui.kit.Kit;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.SearchField;
import net.evadeclient.gui.kit.Tabs;
import net.evadeclient.gui.kit.Toggle;
import net.evadeclient.module.misc.Friends;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.social.MojangNames;
import net.evadeclient.social.SocialChat;
import net.evadeclient.social.SocialClient;
import net.evadeclient.social.SocialJoin;
import net.evadeclient.social.SocialState;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Friends and party: tabs for Friends, Requests, Party and Chat (counts on the tabs) plus a settings gear, a field to
 * add a friend (the message box on Chat), and rows with skin heads and a presence ring. Each row shows its one main
 * verb (Join, Accept, Invite...) as a button; everything else, the destructive actions included, sits in its "more"
 * menu, where Remove, Block, Kick and Leave ask twice. Chat shows bubbles (yours on the right). Offline friends recede.
 */
public final class FriendsPage {
    public static final String ADD = "friendAdd", CHAT = "chatDraft";

    private enum Tab {
        FRIENDS("Friends"), REQUESTS("Requests"), PARTY("Party"), CHAT("Chat"), SETTINGS("Settings");
        final String label;
        Tab(String label) { this.label = label; }
    }

    private static Tab tab = Tab.FRIENDS;

    private static final int K_HEAD = 0, K_PERSON = 1, K_TOGGLE = 2, K_NOTE = 3, K_EMPTY = 4, K_MSG = 5;

    /** A button of a row. {@code style}: 0 plain, 1 the main verb, 2 destructive (asks twice). */
    private record Btn(String id, String label, int style, Runnable action) {}

    private record Row(String key, int kind, String title, String sub, UUID head, int dot, String chip, List<Btn> buttons,
                       BooleanSupplier on, Runnable toggle, boolean enabled, List<String> lines, boolean mine) {
        int h() {
            return switch (kind) {
                case K_HEAD -> 26;
                case K_TOGGLE -> 30;
                case K_NOTE -> 16;
                case K_EMPTY -> 190;
                case K_MSG -> lines.size() * 11 + 16;
                default -> 46;
            };
        }
    }

    private static Row head(String t) { return new Row("h:" + t, K_HEAD, t, null, null, 0, null, List.of(), null, null, true, null, false); }
    private static Row note(String t) { return new Row("n:" + t, K_NOTE, t, null, null, 0, null, List.of(), null, null, true, null, false); }
    private static Row empty(String t, String sub, Btn b) {
        return new Row("e:" + t, K_EMPTY, t, sub, null, 0, null, b == null ? List.of() : List.of(b), null, null, true, null, false);
    }
    private static Row toggle(String key, String t, String sub, BooleanSupplier on, Runnable tg, boolean enabled) {
        return new Row("t:" + key, K_TOGGLE, t, sub, null, 0, null, List.of(), on, tg, enabled, null, false);
    }
    private static Row person(String key, String t, String sub, UUID head, int dot, String chip, List<Btn> b) {
        return new Row("p:" + key, K_PERSON, t, sub, head, dot, chip, b, null, null, true, null, false);
    }

    private final MenuHost host;
    private String confirm;
    private double confirmAt;
    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    public FriendsPage(MenuHost host) { this.host = host; }

    /** Test hook: the tab to open on (0 Friends, 1 Requests, 2 Party, 3 Chat, 4 Settings). */
    public static void setTab(int i) { tab = Tab.values()[Math.max(0, Math.min(Tab.values().length - 1, i))]; }

    public static boolean friendsOn() {
        var f = Friends.get();
        return f != null && f.isEnabled() && f.activated();
    }

    private static boolean chatOpen() { return tab == Tab.CHAT && SocialChat.open() != null; }

    /** The field typing goes to on this page. */
    public static String field() { return chatOpen() ? CHAT : ADD; }

    public void reset() {
        SearchField.set(host.kit(), ADD, "");
        confirm = null;
    }

    private void switchTab(Tab t) {
        if (t == tab) return;
        tab = t;
        host.pageChanged(0);
        host.scroll().reset();
        SocialClient.refresh();
    }

    // ------------------------------------------------------------------ render

    public void render(Kit k) {
        if (confirm != null && host.now() - confirmAt > 3000) confirm = null;
        boolean on = friendsOn();
        SocialState s = SocialClient.state();
        boolean ready = on && SocialClient.ready() && s.loaded();
        int cx = host.cx(), cw = host.cw(), top = host.top(), cy = host.cy(), ch = host.ch();
        List<Row> rows = rows(k, on, ready, s);
        int contentH = 0;
        for (Row r : rows) contentH += r.h();
        MenuScroll scroll = host.scroll();
        scroll.clamp(contentH, ch);
        int si = scroll.px();
        k.ctx.enableScissor(cx - 12, cy, cx + cw + 12, cy + ch);
        scroll.push(k.ctx);
        try {
            int y = cy - si;
            for (Row r : rows) {
                if (y + r.h() >= cy && y <= cy + ch) row(k, r, cx, y, cw);
                y += r.h();
            }
        } finally {
            scroll.pop(k.ctx);
            k.ctx.disableScissor();
        }
        scroll.chrome(k, "friendbar", cx - 12, cy, cx + cw, cy + ch);
        k.barrier("fr#top", cx - 12, top, cx + cw + 20, cy, null);
        k.barrier("fr#bottom", cx - 12, cy + ch, cx + cw + 20, cy + ch + 40, null);

        // Header: title and status, then the tabs and the settings gear.
        k.textCentredY("Friends", cx, top + 16, Tokens.N950, Role.HEADLINE);
        int tx = cx + k.width("Friends", Role.HEADLINE) + 10;
        String status = !on ? "Off" : !ready ? SocialClient.status()
                : s.onlineFriends() + " of " + s.friends().size() + " online" + (s.inParty() ? "  ·  party of " + s.party().members().size() : "");
        int right = host.headerRight();
        if (on) {
            Button.icon(k, "ftab:settings", right - 24, top + 4, 24, "settings", "Friends settings", true, () -> switchTab(Tab.SETTINGS));
            if (tab == Tab.SETTINGS) Paint.slant(k.ctx, right - 20, top + 28, right - 4, top + 30, Tokens.A500);
            int waiting = s.incoming().size() + s.invites().size() + net.evadeclient.social.OpenToFriends.invites().size();
            int unread = SocialChat.totalUnread();
            List<String> labels = List.of("Friends", waiting > 0 ? "Requests " + waiting : "Requests", "Party", unread > 0 ? "Chat " + Math.min(99, unread) : "Chat");
            int tw = 0;
            for (String l : labels) tw += k.width(l, Role.STRONG) + Tabs.GAP;
            tw -= Tabs.GAP;
            int tabsX = right - 34 - tw;
            String sel = tab == Tab.SETTINGS ? "" : labels.get(tab.ordinal());
            Tabs.draw(k, "ftabs", tabsX, top + 5, tw + 2, labels, sel, l -> switchTab(Tab.values()[labels.indexOf(l)]));
            right = tabsX - 14;
        }
        k.textCentredY(host.fit(status, Role.SMALL, right - tx), tx, top + 17, Tokens.N700, Role.SMALL);

        if (on) {
            boolean chat = chatOpen();
            String fid = chat ? CHAT : ADD;
            int fw = Math.min(420, cw - 90);
            String ph = chat ? "Message " + chatTitle(s, SocialChat.open()) + "  (Enter sends)" : "Add a friend by Minecraft name";
            String typed = SearchField.draw(k, fid, cx, top + 40, fw, ph, null, false);
            String label = busy.contains("fadd") ? "…" : chat ? "Send" : "Add";
            Button.draw(k, "fadd", cx + fw + 8, top + 40, 0, Button.Size.MD, typed.isBlank() ? Button.Variant.SECONDARY : Button.Variant.PRIMARY,
                    label, chat ? null : "plus", true, this::submit);
        }
        Paint.fadeLine(k.ctx, cx - 8, cy - 7, cx + cw + 8, Theme.alpha(0xFFFFFFFF, 0.06f));
    }

    private void row(Kit k, Row r, int x, int y, int w) {
        switch (r.kind) {
            case K_HEAD -> UiFont.caps(k.ctx, k.tr, r.title.toUpperCase(java.util.Locale.ROOT), x + 2, y + 12, Tokens.N700, Role.CAPS, 0.9f, k.smooth);
            case K_NOTE -> k.text(host.fit(r.title, Role.SMALL, w - 4), x + 2, y + 4, Tokens.N700, Role.SMALL);
            case K_EMPTY -> {
                Btn b = r.buttons.isEmpty() ? null : r.buttons.get(0);
                EmptyState.draw(k, r.key, x + w / 2, y + 8, r.key.contains("Play with") ? "friends" : "friends", host.fit(r.title, Role.TITLE, w - 40), r.sub,
                        b == null ? null : label(b), b == null ? null : b.action);
            }
            case K_MSG -> bubble(k, r, x, y, w);
            case K_TOGGLE -> {
                String id = r.key;
                boolean hover = k.mx >= x && k.mx < x + w && k.my >= y && k.my < y + r.h() - 2;
                float hov = k.anim(id + "#h", hover && r.enabled ? 1f : 0f);
                if (hov > 0.01f) Paint.rect(k.ctx, x - 4, y, x + w + 4, y + r.h() - 2, Tokens.R_SM, Theme.alpha(Tokens.N250, 0.5f * hov));
                float base = Theme.fade;
                if (!r.enabled) Theme.fade = base * 0.4f;
                try {
                    float cy = y + (r.h() - 2) / 2f;
                    k.textCentredY(r.title, x + 8, cy, Tokens.N950, Role.BODY);
                    if (r.sub != null) {
                        int sx = x + 8 + k.width(r.title, Role.BODY) + 12;
                        k.textCentredY(host.fit(r.sub, Role.SMALL, x + w - 60 - sx), sx, cy, Tokens.N700, Role.SMALL);
                    }
                    k.hit(id + "#row", x - 4, y, x + w + 4, y + r.h() - 2, (b, mx, my) -> { if (b == 0 && r.enabled) r.toggle.run(); }, false);
                    Toggle.draw(k, id, x + w - 10 - Toggle.MD_W, Math.round(cy - Toggle.MD_H / 2f), false, r.on.getAsBoolean(), r.enabled, r.toggle);
                } finally {
                    Theme.fade = base;
                }
            }
            default -> person(k, r, x, y, w);
        }
    }

    private String label(Btn b) {
        if (busy.contains(b.id)) return "…";
        return b.style == 2 && b.id.equals(confirm) ? b.label + "?" : b.label;
    }

    private void press(Btn b) {
        if (busy.contains(b.id)) return;
        if (b.style == 2 && !b.id.equals(confirm)) {
            confirm = b.id;
            confirmAt = host.now();
            return;
        }
        confirm = null;
        b.action.run();
    }

    private void person(Kit k, Row r, int x, int y, int w) {
        int h = 40;
        String id = r.key;
        int st = k.state(id, x, y, x + w, y + h, true);
        float hov = k.anim(id + "#h", (st & Kit.HOVER) != 0 ? 1f : 0f);
        boolean offline = r.head != null && r.dot == Tokens.N600;
        float base = Theme.fade;
        Paint.surface(k.ctx, x, y, x + w, y + h, Tokens.R_MD, Tokens.E2.noAmbient().withFill(Theme.lerpColor(Tokens.N200, Tokens.N250, hov)), hov, k.mx - x);
        k.hit(id, x, y, x + w, y + h, (b, mx, my) -> { }, false);
        if (offline) Theme.fade = base * 0.7f;
        try {
            float cy = y + h / 2f;
            int tx = x + 12;
            if (r.head != null) {
                Heads.draw(k.ctx, r.head, r.title, tx, Math.round(cy - 12), 24, r.dot);
                tx += 36;
            } else if (r.dot != 0) {
                Paint.circle(k.ctx, tx + 4, cy, 7, r.dot);
                tx += 16;
            }
            // Buttons: the main verb inline, the rest in the "more" menu.
            List<Btn> inline = new ArrayList<>(), more = new ArrayList<>();
            List<Btn> ordered = new ArrayList<>();
            for (Btn b : r.buttons) if (b.style == 1) ordered.add(b);
            for (Btn b : r.buttons) if (b.style == 0) ordered.add(b);
            for (Btn b : r.buttons) if (b.style == 2) ordered.add(b);
            int max = r.buttons.size() <= 2 && r.buttons.stream().noneMatch(b -> b.style == 2) ? 2 : 1;
            for (Btn b : ordered) {
                if (inline.size() < max && b.style != 2) inline.add(b); else more.add(b);
            }
            if (r.buttons.size() == 1 && more.size() == 1) { inline.add(more.remove(0)); }
            int right = x + w - 10;
            if (!more.isEmpty()) {
                right -= 24;
                moreMenu(k, id + "#more", right, Math.round(cy - 12), more);
                right -= 6;
            }
            for (Btn b : inline) {
                String l = label(b);
                Button.Variant v = b.style == 2 ? Button.Variant.DANGER : Button.Variant.SECONDARY;
                int bw = Button.width(k, l, Button.Size.SM, null, false);
                right -= bw;
                Button.draw(k, b.id, right, Math.round(cy - 10), bw, Button.Size.SM, v, l, null, !busy.contains(b.id), () -> press(b));
                right -= 6;
            }
            int titleMax = right - tx - 10 - (r.chip != null ? Badge.width(k, r.chip) + 8 : 0);
            String title = host.fit(r.title, Role.STRONG, titleMax);
            int ty = r.sub == null ? Math.round(cy) : Math.round(cy - 6);
            k.textCentredY(title, tx, ty, Tokens.N950, Role.STRONG);
            if (r.chip != null) {
                Badge.Tone tone = r.chip.contains("NEW") ? Badge.Tone.ACCENT : Badge.Tone.NEUTRAL;
                Badge.draw(k, tx + k.width(title, Role.STRONG) + 8, ty - Badge.H / 2, r.chip, tone);
            }
            if (r.sub != null) k.textCentredY(host.fit(r.sub, Role.SMALL, right - tx - 10), tx, Math.round(cy + 7), Tokens.N700, Role.SMALL);
        } finally {
            Theme.fade = base;
        }
    }

    /** The "more" button (three dots) and its menu: a popover of actions, destructive ones in the danger colour. */
    private void moreMenu(Kit k, String id, int x, int y, List<Btn> items) {
        boolean open = id.equals(k.popup());
        int st = k.state(id, x, y, x + 24, y + 24, true);
        float hov = k.anim(id + "#h", (st & Kit.HOVER) != 0 || open ? 1f : 0f);
        if (hov > 0.01f) Paint.rect(k.ctx, x, y, x + 24, y + 24, Tokens.R_SM, Theme.alpha(Tokens.N250, 0.7f * hov));
        int dot = Theme.lerpColor(Tokens.N700, Tokens.N950, hov);
        for (int i = -1; i <= 1; i++) Paint.circle(k.ctx, x + 12 + i * 5, y + 12, 3, dot);
        if ((st & Kit.FOCUSED) != 0) Paint.focusRing(k, x, y, x + 24, y + 24, Tokens.R_SM, Tokens.FOCUS_RING);
        k.hit(id, x, y, x + 24, y + 24, () -> { k.popup(open ? null : id); k.remember(id + "#at", k.now); });
        k.tooltip(id, "More", null, x, y, x + 24, y + 24);
        if (!open) return;
        k.overlay(() -> {
            int w = 120;
            for (Btn b : items) w = Math.max(w, k.width(label(b), Role.BODY) + 28);
            int h = items.size() * 22 + 8, mx = x + 24 - w, my = y + 28;
            long at = k.mem(id + "#at", 0L);
            float a = net.evadeclient.gui.Motion.standard(net.evadeclient.gui.Motion.clamp01((k.now - at) / (float) Math.max(1, net.evadeclient.gui.Motion.duration(net.evadeclient.gui.Motion.DUR_QUICK))));
            if (net.evadeclient.gui.Motion.level() == net.evadeclient.gui.Motion.Level.OFF) a = 1f;
            float base = Theme.fade;
            Theme.fade = base * a;
            try {
                k.barrier(id + "#menu", mx, my, mx + w, my + h, null);
                Paint.surface(k.ctx, mx, my, mx + w, my + h, Tokens.R_MD, Tokens.E4, 0, Float.NaN);
                for (int i = 0; i < items.size(); i++) {
                    Btn b = items.get(i);
                    int oy = my + 4 + i * 22;
                    String oid = id + "#" + i;
                    if (k.hovered(oid)) Paint.rect(k.ctx, mx + 4, oy, mx + w - 4, oy + 22, Tokens.R_XS + 1, b.style == 2 ? Theme.alpha(Tokens.DANGER, 0.14f) : Tokens.N350);
                    k.textCentredY(label(b), mx + 12, oy + 11, b.style == 2 ? Tokens.DANGER : Tokens.N900, Role.BODY);
                    k.hit(oid, mx + 4, oy, mx + w - 4, oy + 22, () -> {
                        press(b);
                        if (b.style != 2 || !b.id.equals(confirm)) k.popup(null);
                    });
                }
            } finally {
                Theme.fade = base;
            }
        });
    }

    private void bubble(Kit k, Row r, int x, int y, int w) {
        int lw = 0;
        for (String l : r.lines) lw = Math.max(lw, k.width(l, Role.BODY));
        int bw = lw + 20, bh = r.lines.size() * 11 + 8;
        int bx = r.mine ? x + w - 8 - bw : x + 8;
        if (!r.mine) k.text(r.chip, bx + 2, y, Tokens.N700, Role.SMALL);
        int by = y + (r.mine ? 4 : 10);
        if (r.mine) Paint.surface(k.ctx, bx, by, bx + bw, by + bh, Tokens.R_MD, Tokens.E2.noAmbient().withFill(Tokens.ACCENT_SURFACE), 0, Float.NaN);
        else Paint.surface(k.ctx, bx, by, bx + bw, by + bh, Tokens.R_MD, Tokens.E3.noAmbient(), 0, Float.NaN);
        int ty = by + 5;
        for (String l : r.lines) { k.text(l, bx + 10, ty, Tokens.N950, Role.BODY); ty += 11; }
    }

    // ------------------------------------------------------------------ rows

    private List<Row> rows(Kit k, boolean on, boolean ready, SocialState s) {
        List<Row> rows = new ArrayList<>();
        if (!on) {
            rows.add(empty("Play with your friends", "See who's online and on which server, make a party, and ping spots for your party.",
                    new Btn("fstart", "Turn on Friends", 1, () -> {
                        var f = Friends.get();
                        if (f != null) f.activate();
                        tab = Tab.FRIENDS;
                    })));
            rows.add(note("Friends uses evadeclient.com and signs in with your Minecraft account (no password). See evadeclient.com/privacy."));
            rows.add(note("Nothing is sent until you turn it on. Party pings only reach your party on the same server."));
            return rows;
        }
        if (!ready && tab != Tab.SETTINGS) {
            rows.add(empty(SocialClient.status(), "Friends connect once Evade has checked your Minecraft account.", null));
            return rows;
        }
        int online = Tokens.OK, away = Tokens.N600;
        switch (tab) {
            case FRIENDS -> {
                if (s.friends().isEmpty()) {
                    rows.add(empty("No friends yet", "Type a friend's Minecraft name above. They need Evade and Friends turned on.", null));
                    break;
                }
                List<Row> on2 = new ArrayList<>(), off = new ArrayList<>();
                for (var p : s.friends()) {
                    String name = MojangNames.shown(p.uuid(), p.name());
                    List<Btn> b = new ArrayList<>();
                    b.add(new Btn("frm:" + p.uuid(), "Remove", 2, () -> act("frm:" + p.uuid(), Friends.withUuid("friend.remove", p.uuid()), "Friends", "Removed " + name)));
                    b.add(new Btn("fbl:" + p.uuid(), "Block", 2, () -> act("fbl:" + p.uuid(), Friends.withUuid("block", p.uuid()), "Friends", "Blocked " + name)));
                    boolean inMyParty = s.party() != null && s.party().has(p.uuid());
                    if (!inMyParty && p.online()) b.add(new Btn("finv:" + p.uuid(), "Invite", 0, () -> act("finv:" + p.uuid(),
                            Friends.withUuid("party.invite", p.uuid()), "Party", "Invited " + name)));
                    if (p.online()) b.add(new Btn("fmsg:" + p.uuid(), "Message", 0, () -> openChat(p.uuid())));
                    if (p.online() && p.server() != null && !SocialJoin.onServer(p.server()))
                        b.add(new Btn("fjoin:" + p.uuid(), "Join", 1, () -> SocialJoin.ask(name, p.server())));
                    String sub = p.online() ? net.evadeclient.social.SocialPresence.describe(p) : "Last seen " + ago(p.lastSeen());
                    Row r = person("f:" + p.uuid(), name, sub, p.uuid(), p.online() ? online : away, inMyParty ? "PARTY" : null, b);
                    (p.online() ? on2 : off).add(r);
                }
                if (!on2.isEmpty()) { rows.add(head("Online — " + on2.size())); rows.addAll(on2); }
                if (!off.isEmpty()) { rows.add(head("Offline — " + off.size())); rows.addAll(off); }
            }
            case REQUESTS -> {
                var worlds = net.evadeclient.social.OpenToFriends.invites();
                if (s.incoming().isEmpty() && s.outgoing().isEmpty() && s.invites().isEmpty() && worlds.isEmpty()) {
                    rows.add(empty("No requests", "Friend requests and party invites show up here.", null));
                    break;
                }
                if (!worlds.isEmpty()) {
                    rows.add(head("World invites"));
                    for (var w : worlds) rows.add(person("w:" + w.at(), w.fromName() + "'s world", w.world().isBlank() ? "Open to Friends" : w.world(), null, 0, null, List.of(
                            new Btn("wjoin:" + w.at(), "Join", 1, () -> net.evadeclient.social.OpenToFriends.join(w)),
                            new Btn("wdis:" + w.at(), "Dismiss", 0, () -> net.evadeclient.social.OpenToFriends.dismiss(w)))));
                }
                if (!s.invites().isEmpty()) {
                    rows.add(head("Party invites"));
                    for (var i : s.invites()) {
                        String from = MojangNames.shown(i.from(), i.fromName());
                        com.google.gson.JsonObject body = new com.google.gson.JsonObject();
                        body.addProperty("party", i.party());
                        long left = Math.max(0, 10 * 60_000L - (System.currentTimeMillis() - i.at())) / 60_000L;
                        rows.add(person("i:" + i.party(), from + "'s party", i.size() + (i.size() == 1 ? " player" : " players") + "  ·  ends in " + Math.max(1, left) + "m",
                                i.from(), 0, null, List.of(
                                new Btn("ijoin:" + i.party(), "Join", 1, () -> act("ijoin:" + i.party(), SocialClient.call("party.accept", body), "Party", "You joined " + from + "'s party")),
                                new Btn("idec:" + i.party(), "Decline", 0, () -> act("idec:" + i.party(), SocialClient.call("party.decline", body), "Party", null)))));
                    }
                }
                if (!s.incoming().isEmpty()) {
                    rows.add(head("Friend requests"));
                    for (var r : s.incoming()) {
                        String name = MojangNames.shown(r.uuid(), r.name());
                        boolean checked = MojangNames.confirmed(r.uuid(), name);
                        rows.add(person("in:" + r.uuid(), name, (checked ? "Name checked with Mojang" : "Checking the name with Mojang…") + "  ·  " + ago(r.at()), r.uuid(), 0, null, List.of(
                                new Btn("racc:" + r.uuid(), "Accept", 1, () -> act("racc:" + r.uuid(), Friends.withUuid("friend.accept", r.uuid()), "Friends", "You're now friends with " + name)),
                                new Btn("rdec:" + r.uuid(), "Decline", 0, () -> act("rdec:" + r.uuid(), Friends.withUuid("friend.decline", r.uuid()), "Friends", null)),
                                new Btn("rbl:" + r.uuid(), "Block", 2, () -> act("rbl:" + r.uuid(), Friends.withUuid("block", r.uuid()), "Friends", "Blocked " + name)))));
                    }
                }
                if (!s.outgoing().isEmpty()) {
                    rows.add(head("Sent"));
                    for (var r : s.outgoing()) {
                        String name = MojangNames.shown(r.uuid(), r.name());
                        rows.add(person("out:" + r.uuid(), name, "Waiting for them  ·  sent " + ago(r.at()), r.uuid(), 0, null, List.of(
                                new Btn("rcan:" + r.uuid(), "Cancel", 0, () -> act("rcan:" + r.uuid(), Friends.withUuid("friend.cancel", r.uuid()), "Friends", null)))));
                    }
                }
            }
            case PARTY -> party(rows, s, online, away);
            case CHAT -> chatRows(k, rows, s, online, away);
            case SETTINGS -> settingsRows(rows, s, ready);
        }
        return rows;
    }

    private void party(List<Row> rows, SocialState s, int online, int away) {
        var party = s.party();
        if (party == null) {
            rows.add(empty("You're not in a party", "Invite an online friend from Friends, or start an empty party.",
                    new Btn("pcreate", "Create party", 1, () -> act("pcreate", SocialClient.call("party.create", new com.google.gson.JsonObject()), "Party", "Party created"))));
        } else {
            boolean lead = s.isLeader();
            rows.add(head("Members  " + party.members().size() + " / 8"));
            for (var p : party.members()) {
                boolean me = p.uuid().equals(s.me());
                String name = me ? "You" : MojangNames.shown(p.uuid(), p.name());
                List<Btn> b = new ArrayList<>();
                if (lead && !me) {
                    b.add(new Btn("pkick:" + p.uuid(), "Kick", 2, () -> act("pkick:" + p.uuid(), Friends.withUuid("party.kick", p.uuid()), "Party", "Removed " + name)));
                    b.add(new Btn("plead:" + p.uuid(), "Make leader", 0, () -> act("plead:" + p.uuid(), Friends.withUuid("party.transfer", p.uuid()), "Party", name + " leads the party now")));
                }
                String sub = p.online() ? (p.server() != null ? "Online  ·  " + net.evadeclient.module.misc.StreamerMode.friendServer(p.server()) : "Online") : "Offline";
                rows.add(person("m:" + p.uuid(), name, sub, p.uuid(), p.online() ? online : away, p.leader() ? "LEADER" : null, b));
            }
            for (var p : party.invited())
                rows.add(person("pi:" + p.uuid(), MojangNames.shown(p.uuid(), p.name()), "Invited  ·  waiting for them", p.uuid(), away, null, List.of()));
            String here = SocialClient.currentServer(MinecraftClient.getInstance());
            if (lead && party.members().size() > 1) rows.add(person("pbring", "Bring party here", here != null
                    ? "Asks your party to join you on " + here + " (they choose)" : "Join a multiplayer server first", null, 0, null,
                    here == null ? List.of() : List.of(new Btn("pbring", "Bring", 1, () -> act("pbring", SocialJoin.summonParty(), "Party", "Asked your party to join you")))));
            var offer = SocialJoin.pendingSummon();
            if (offer != null) rows.add(person("psum", offer.who() + " wants the party on " + offer.address(), "You choose: Join asks first", null, 0, null, List.of(
                    new Btn("psumd", "Dismiss", 0, SocialJoin::dismissSummon),
                    new Btn("psumj", "Join", 1, () -> SocialJoin.ask(offer.who(), offer.address())))));
            int pu = SocialChat.unread(SocialChat.PARTY);
            rows.add(person("pchat", "Party chat", pu > 0 ? pu + " new" : "Talk to your party through Evade", null, 0, pu > 0 ? "NEW" : null,
                    List.of(new Btn("pchat", "Open", 0, () -> openChat(SocialChat.PARTY)))));
            rows.add(person("pleave", "Leave party", lead && party.members().size() > 1 ? "The next member who joined leads after you"
                    : "Your party ends when its last member leaves", null, 0, null, List.of(
                    new Btn("pleave", "Leave", 2, () -> act("pleave", SocialClient.call("party.leave", new com.google.gson.JsonObject()), "Party", "You left the party")))));
        }
        List<SocialState.Person> invitable = new ArrayList<>();
        for (var p : s.friends())
            if (p.online() && (party == null || (!party.has(p.uuid()) && party.invited().stream().noneMatch(q -> q.uuid().equals(p.uuid()))))) invitable.add(p);
        if (!invitable.isEmpty()) {
            rows.add(head("Invite friends"));
            for (var p : invitable) {
                String name = MojangNames.shown(p.uuid(), p.name());
                rows.add(person("pinv:" + p.uuid(), name, p.server() != null ? "Online  ·  " + net.evadeclient.module.misc.StreamerMode.friendServer(p.server()) : "Online",
                        p.uuid(), online, null, List.of(new Btn("pinvb:" + p.uuid(), "Invite", 1, () -> act("pinvb:" + p.uuid(),
                                Friends.withUuid("party.invite", p.uuid()), "Party", "Invited " + name)))));
            }
        }
        var pp = net.evadeclient.module.misc.PartyPing.get();
        if (pp != null) rows.add(note("Ping a spot: " + KeySetting.keyName(pp.pingKey.get()) + " (Party Ping), Sneak for danger, or .ping. Only your party on the same server sees it."));
    }

    private void settingsRows(List<Row> rows, SocialState s, boolean ready) {
        var f = Friends.get();
        var pp = net.evadeclient.module.misc.PartyPing.get();
        if (f == null) return;
        rows.add(head("Privacy"));
        rows.add(toggle("allow", "Allow friend requests", "Off: nobody can send you new requests", s::allowRequests,
                () -> setting("allowRequests", s.allowRequests() ? "nobody" : "everyone"), ready));
        rows.add(toggle("share", "Share my server with friends", "Friends and your party see which server you're on", s::shareServer,
                () -> setting("shareServer", !s.shareServer()), ready));
        rows.add(toggle("shareact", "Share what I'm doing", "In menus, singleplayer, on a server or AFK, and for how long",
                f.shareActivity::get, () -> f.shareActivity.set(!f.shareActivity.get()), true));
        rows.add(head("Notifications"));
        rows.add(toggle("otoast", "Friend online toasts", null, f.onlineToasts::get, () -> f.onlineToasts.set(!f.onlineToasts.get()), true));
        rows.add(toggle("rtoast", "Request and party toasts", null, f.requestToasts::get, () -> f.requestToasts.set(!f.requestToasts.get()), true));
        rows.add(toggle("mtoast", "Message toasts", null, f.messageToasts::get, () -> f.messageToasts.set(!f.messageToasts.get()), true));
        rows.add(toggle("mchat", "Messages in chat", "Marked [Evade]; only you see them", f.messagesInChat::get, () -> f.messagesInChat.set(!f.messagesInChat.get()), true));
        rows.add(toggle("jprompt", "Join buttons in chat", "When a friend starts playing on a server", f.joinPrompts::get, () -> f.joinPrompts.set(!f.joinPrompts.get()), true));
        rows.add(head("Friend colours"));
        rows.add(toggle("fcol", "Colour friends' names", "In chat and the tab list, on your screen", f.friendColors::get, () -> f.friendColors.set(!f.friendColors.get()), true));
        rows.add(person("fcolp", "Colour and highlighted players", "Pick the colour, or add players who aren't on Evade", null, 0, null,
                List.of(new Btn("fcolp", "Open", 0, () -> host.openModule(f)))));
        if (pp != null) {
            rows.add(head("Party Ping"));
            rows.add(toggle("pping", "Party Ping", null, pp::isEnabled, pp::toggle, true));
            rows.add(toggle("psound", "Ping sound", null, pp.sound::get, () -> pp.sound.set(!pp.sound.get()), true));
            rows.add(person("ppage", "Ping key, beams and minimap", "Ping Key: " + KeySetting.keyName(pp.pingKey.get()), null, 0, null,
                    List.of(new Btn("ppage", "Open", 0, () -> host.openModule(pp)))));
        }
        if (!s.blocked().isEmpty()) {
            rows.add(head("Blocked"));
            for (var p : s.blocked()) {
                String name = MojangNames.shown(p.uuid(), p.name());
                rows.add(person("b:" + p.uuid(), name, "No requests, invites, messages or pings from them", p.uuid(), 0, null, List.of(
                        new Btn("unb:" + p.uuid(), "Unblock", 0, () -> act("unb:" + p.uuid(), Friends.withUuid("unblock", p.uuid()), "Friends", "Unblocked " + name)))));
            }
        }
        rows.add(head("Account"));
        rows.add(toggle("use", "Use Friends", "Off: disconnects from evadeclient.com", f::isEnabled, f::toggle, true));
        rows.add(person("fdel", "Delete my social data", "Friends, requests, blocks and party, from evadeclient.com, now", null, 0, null, List.of(
                new Btn("fdel", "Delete", 2, () -> act("fdel", SocialClient.call("delete", new com.google.gson.JsonObject()),
                        "Friends", "Your social data was deleted", r -> { f.deactivate(); SocialClient.stop("Off"); })))));
        rows.add(note("Status: " + SocialClient.status() + ". Unused social data is deleted after 90 days."));
    }

    // ------------------------------------------------------------------ chat

    private void openChat(UUID convo) {
        SocialChat.setOpen(convo);
        tab = Tab.CHAT;
        host.kit().focus(CHAT, false);
        host.pageChanged(0);
        host.scroll().reset();
        host.scroll().target = 1e9; // the newest messages, at the bottom (clamped when laid out)
    }

    private static String chatTitle(SocialState s, UUID convo) {
        if (convo == null) return "";
        if (SocialChat.PARTY.equals(convo)) return "your party";
        for (var p : s.friends()) if (p.uuid().equals(convo)) return MojangNames.shown(p.uuid(), p.name());
        return "them";
    }

    private void chatRows(Kit k, List<Row> rows, SocialState s, int online, int away) {
        UUID cur = SocialChat.open();
        if (cur != null && SocialChat.PARTY.equals(cur) && !s.inParty()) cur = null;
        if (cur != null && !SocialChat.PARTY.equals(cur) && !s.isFriend(cur)) cur = null;
        final UUID open = cur;
        if (open == null) {
            SocialChat.setOpen(null);
            if (s.inParty()) {
                int u = SocialChat.unread(SocialChat.PARTY);
                rows.add(person("c:party", "Party chat", preview(SocialChat.PARTY, "Everyone in your party who's online"), null, 0, u > 0 ? u + " NEW" : null,
                        List.of(new Btn("copen:party", "Open", u > 0 ? 1 : 0, () -> openChat(SocialChat.PARTY)))));
            }
            if (s.friends().isEmpty() && !s.inParty()) {
                rows.add(empty("No one to message yet", "Add friends first. Messages go through Evade, not the server you're on.", null));
                return;
            }
            for (var p : s.friends()) {
                String name = MojangNames.shown(p.uuid(), p.name());
                int u = SocialChat.unread(p.uuid());
                rows.add(person("c:" + p.uuid(), name, preview(p.uuid(), p.online() ? "Online" : "Offline: can't get messages now"),
                        p.uuid(), p.online() ? online : away, u > 0 ? u + " NEW" : null,
                        List.of(new Btn("copen:" + p.uuid(), "Open", u > 0 ? 1 : 0, () -> openChat(p.uuid())))));
            }
            rows.add(note("Plain text, 256 characters. Relayed to whoever is online and not kept. Block someone to stop their messages."));
            return;
        }
        SocialChat.viewing(open);
        boolean party = SocialChat.PARTY.equals(open);
        String title = party ? "Party chat" : chatTitle(s, open);
        boolean on = party || s.friends().stream().anyMatch(p -> p.uuid().equals(open) && p.online());
        rows.add(person("chead", title, party ? "Your party: " + s.party().members().size() + " players" : on ? "Online" : "Offline: they won't get messages now",
                party ? null : open, party ? 0 : on ? online : away, null,
                List.of(new Btn("cback", "Back", 0, () -> { SocialChat.setOpen(null); host.scroll().reset(); }))));
        var lines = SocialChat.lines(open);
        if (lines.isEmpty()) rows.add(note(party ? "Say hi to your party: type above, or .pc <message> in chat." : "No messages yet. Type above, or .msg " + title + " <message> in chat."));
        int room = Math.min(host.cw() * 2 / 3, 420);
        for (var l : lines) {
            List<String> wrapped = k.wrap(l.text(), Role.BODY, room, 12);
            rows.add(new Row("cm:" + l.at() + ":" + rows.size(), K_MSG, l.text(), null, null, 0, l.mine() ? "You" : l.name(),
                    List.of(), null, null, true, wrapped, l.mine()));
        }
    }

    private static String preview(UUID convo, String fallback) {
        var lines = SocialChat.lines(convo);
        if (lines.isEmpty()) return fallback;
        var l = lines.get(lines.size() - 1);
        return (l.mine() ? "You: " : "") + l.text();
    }

    // ------------------------------------------------------------------ actions

    /** Enter in the field or the Add / Send button. */
    public void submit() {
        Kit k = host.kit();
        if (chatOpen()) {
            UUID to = SocialChat.open();
            String text = SearchField.text(k, CHAT);
            if (to == null || SocialChat.clean(text).isEmpty()) { k.focus(CHAT, false); return; }
            SearchField.set(k, CHAT, "");
            act("fadd", SocialChat.send(to, text), "Message", null, r -> host.scroll().target = 1e9);
            return;
        }
        String name = SearchField.text(k, ADD).strip();
        if (name.isEmpty()) { k.focus(ADD, false); return; }
        if (!SocialClient.ready()) {
            Notifications.push("Friends", "Still connecting: " + SocialClient.status(), false);
            return;
        }
        SearchField.set(k, ADD, "");
        act("fadd", Friends.addFriend(name), "Friends", null, r ->
                Notifications.push("Friends", r.has("accepted") && r.get("accepted").getAsBoolean()
                        ? "You're now friends with " + name : "Friend request sent to " + name, true));
    }

    private void setting(String key, Object value) {
        com.google.gson.JsonObject b = new com.google.gson.JsonObject();
        if (value instanceof Boolean v) b.addProperty(key, v); else b.addProperty(key, String.valueOf(value));
        act("set:" + key, SocialClient.call("settings", b), "Friends", null);
    }

    private void act(String id, CompletableFuture<com.google.gson.JsonObject> f, String title, String success) {
        act(id, f, title, success, null);
    }

    private void act(String id, CompletableFuture<com.google.gson.JsonObject> f, String title, String success, Consumer<com.google.gson.JsonObject> done) {
        busy.add(id);
        f.whenComplete((r, e) -> busy.remove(id));
        Friends.run(f, title, success, done);
    }

    private static String ago(long at) {
        if (at <= 0) return "a while ago";
        long min = Math.max(0, System.currentTimeMillis() - at) / 60_000L;
        if (min < 1) return "just now";
        if (min < 60) return min + "m ago";
        long h = min / 60;
        return h < 24 ? h + "h ago" : h / 24 + "d ago";
    }
}
