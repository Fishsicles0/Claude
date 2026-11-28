package net.evadeclient.gui;

import net.evadeclient.api.ApiInteractions;
import net.evadeclient.module.misc.Friends;
import net.evadeclient.module.misc.VoiceChat;
import net.evadeclient.social.PlayerCards;
import net.evadeclient.social.SocialClient;
import net.evadeclient.social.SocialPresence;
import net.evadeclient.social.SocialState;
import net.evadeclient.util.Notifications;
import net.evadeclient.gui.kit.Badge;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.EmptyState;
import net.evadeclient.gui.kit.Kit;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.SearchField;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.Screen;

import java.util.List;
import java.util.Locale;

/**
 * Player cards. The list shows the players in the tab list (names the game already shows); clicking one opens their
 * card: Add friend, Invite to party, Copy name, voice chat volume, Hide this player (your screen only, until you close
 * the game) and any entries the server added through the Server API. Works only from names you can already see:
 * there's no way to pick a player in the world.
 */
public final class PlayerCardScreen extends EvadeDialog {
    private static final int COLS = 3, ROWS = 6;
    private PlayerCards.Player player;
    private int page;

    private PlayerCardScreen(Screen parent, PlayerCards.Player player) {
        super(player == null ? "Players" : player.name(), parent);
        this.player = player;
    }

    /** Opens the list of tab-list players. Render thread. */
    public static void openList(Screen parent) {
        MinecraftClient.getInstance().setScreen(new PlayerCardScreen(parent, null));
    }

    /** Opens {@code name}'s card if they're in the tab list. Render thread. */
    public static boolean openFor(String name, Screen parent) {
        PlayerCards.Player p = PlayerCards.find(name);
        if (p == null) return false;
        MinecraftClient.getInstance().setScreen(new PlayerCardScreen(parent, p));
        return true;
    }

    @Override protected int prefWidth() { return player == null ? 440 : 380; }

    @Override protected int prefHeight() { return player == null ? 150 + ROWS * 30 + 30 : 150 + rows() * 30 + 30; }

    @Override protected String titleIcon() { return player == null ? "friends" : null; }

    @Override protected String heading() { return player == null ? "Players" : ""; }

    private int rows() {
        if (player == null) return 0;
        return (actions() + 1) / 2;
    }

    private int actions() {
        int n = 2; // copy, hide
        SocialState s = SocialClient.state();
        if (socialOn() && !s.isFriend(player.uuid())) n++;
        if (socialOn() && s.isFriend(player.uuid())) n++;
        if (VoiceChat.installed()) n++;
        n += ApiInteractions.entries().size();
        return n;
    }

    private static boolean socialOn() {
        Friends f = Friends.get();
        return f != null && f.isEnabled() && f.activated() && SocialClient.ready();
    }

    /** The card's actions: label, icon, what it does. */
    private record CardAction(String label, String icon, Runnable run) {}

    private final List<CardAction> cardActions = new java.util.ArrayList<>();

    @Override
    protected void init() {
        frame();
        cardActions.clear();
        if (player == null) {
            action("Done", Button.Variant.PRIMARY, this::close);
            kit.focus(SEARCH, false);
        } else initCard();
    }

    private static final String SEARCH = "search";

    private List<PlayerCards.Player> filtered() {
        List<PlayerCards.Player> all = PlayerCards.tabList();
        String needle = SearchField.text(kit, SEARCH).strip().toLowerCase(Locale.ROOT);
        if (!needle.isEmpty()) all = all.stream().filter(p -> p.name().toLowerCase(Locale.ROOT).contains(needle)).toList();
        return all;
    }

    private void initCard() {
        SocialState s = SocialClient.state();
        java.util.UUID u = player.uuid();
        String name = player.name();
        if (socialOn() && !s.isFriend(u)) cardActions.add(new CardAction("Add friend", "plus", () -> Friends.run(Friends.addFriend(name), "Friends", null,
                r -> Notifications.push("Friends", SocialState.bool(r, "accepted") ? "You're now friends with " + name : "Friend request sent to " + name, true))));
        if (socialOn() && s.isFriend(u)) {
            boolean online = s.friends().stream().anyMatch(p -> p.uuid().equals(u) && p.online());
            boolean inParty = s.party() != null && s.party().has(u);
            cardActions.add(new CardAction(inParty ? "In your party" : "Invite to party", "friends", () -> {
                if (inParty) return;
                if (online) Friends.run(Friends.withUuid("party.invite", u), "Party", "Invited " + name, null);
                else Notifications.push("Party", name + " isn't online on Evade right now", false);
            }));
        }
        cardActions.add(new CardAction("Copy name", "copy", () -> {
            if (client != null) client.keyboard.setClipboard(name);
            Notifications.push("Player card", "Copied " + name, true);
        }));
        boolean hidden = PlayerCards.isHidden(u);
        cardActions.add(new CardAction(hidden ? "Show this player" : "Hide this player", hidden ? "visuals" : "eye_off", () -> {
            if (hidden) PlayerCards.show(u); else PlayerCards.hide(u);
            Notifications.push("Player card", hidden ? name + " is shown again" : name + " is hidden on your screen until you close the game", true);
            clearAndInit();
        }));
        if (VoiceChat.installed()) cardActions.add(new CardAction("Voice volume…", "settings", () -> {
            close();
            Notifications.push("Voice chat", "Adjust volumes: set " + name + " to 0 to mute them", false);
            VoiceChat.openMenu();
        }));
        for (ApiInteractions.Entry e : ApiInteractions.entries()) cardActions.add(new CardAction(e.label(), "server", () -> {
            if (ApiInteractions.run(e, name)) close();
        }));
        action("All players", Button.Variant.GHOST, () -> { player = null; clearAndInit(); }).icon("chevron_l");
        action("Done", Button.Variant.PRIMARY, this::close);
    }

    /** The player's face (from the tab list's skin), 8x8 plus the hat layer, in a rounded tile. */
    private void head(DrawContext ctx, java.util.UUID u, int x, int y, int size) {
        Paint.rect(ctx, x - 1, y - 1, x + size + 1, y + size + 1, Tokens.R_XS + 1, Tokens.N250);
        var nh = client == null ? null : client.getNetworkHandler();
        var e = nh == null ? null : nh.getPlayerListEntry(u);
        if (e != null) PlayerSkinDrawer.draw(ctx, e.getSkinTextures(), x, y, size, Theme.fa(0xFFFFFFFF));
        else Icons.draw(ctx, "profiles", x + size / 6, y + size / 6, size * 2 / 3, Tokens.N700);
    }

    @Override
    protected void renderBody(DrawContext ctx, int mx, int my) {
        if (player == null) { renderList(ctx, mx, my); return; }
        SocialState s = SocialClient.state();
        java.util.UUID u = player.uuid();
        // Identity: a big head, the name and how you know them.
        int hy = py + PAD - 4;
        head(ctx, u, bx, hy, 40);
        kit.text(player.name(), bx + 52, hy + 2, Tokens.N950, Role.HEADLINE);
        boolean friend = s.isFriend(u), party = s.party() != null && s.party().has(u);
        int bxx = bx + 52;
        if (friend) bxx += Badge.draw(kit, bxx, hy + 26, "Evade friend", Badge.Tone.OK) + Tokens.S2;
        if (party) bxx += Badge.draw(kit, bxx, hy + 26, "Party", Badge.Tone.NEUTRAL) + Tokens.S2;
        if (!friend && !party) bxx += Badge.draw(kit, bxx, hy + 26, "On this server", Badge.Tone.NEUTRAL) + Tokens.S2;
        if (PlayerCards.isHidden(u)) Badge.draw(kit, bxx, hy + 26, "Hidden", Badge.Tone.WARN);
        var fr = s.friends().stream().filter(p -> p.uuid().equals(u)).findFirst().orElse(null);
        String sub = fr == null ? null : SocialPresence.describe(fr);
        int y = hy + 52;
        if (sub != null) { kit.text(kit.ellipsize(sub, Role.SMALL, bw), bx, y, Tokens.N700, Role.SMALL); y += 14; }
        y += 4;
        int colW = (bw - Tokens.S3) / 2;
        for (int i = 0; i < cardActions.size(); i++) {
            CardAction a = cardActions.get(i);
            int x = bx + (i % 2) * (colW + Tokens.S3), yy = y + (i / 2) * 30;
            Button.draw(kit, "ca" + i, x, yy, colW, Button.Size.MD, Button.Variant.SECONDARY, a.label(),
                    PanelGlyphs.has(a.icon()) ? null : a.icon(), true, a.run());
        }
        y += ((cardActions.size() + 1) / 2) * 30 + 4;
        if (!socialOn()) note("info", Tokens.N600, "Turn on Friends to add friends and invite to your party.", y);
        else if (!ApiInteractions.entries().isEmpty()) note("server", Tokens.N600, "Server entries run the server's own command.", y);
    }

    private void renderList(DrawContext ctx, int mx, int my) {
        int y = by;
        String before = SearchField.text(kit, SEARCH);
        SearchField.draw(kit, SEARCH, bx, y, bw, "Search players", null, true);
        if (!before.equals(SearchField.text(kit, SEARCH))) page = 0;
        y += SearchField.H + Tokens.S4;
        List<PlayerCards.Player> all = filtered();
        int per = COLS * ROWS, pages = Math.max(1, (all.size() + per - 1) / per);
        page = Math.max(0, Math.min(page, pages - 1));
        if (all.isEmpty()) {
            boolean none = PlayerCards.tabList().isEmpty();
            EmptyState.draw(kit, "empty", bx + bw / 2, y + 6, none ? "friends" : "search", none ? "Nobody else here" : "Nothing matches",
                    none ? "Players in the tab list show up here." : "Try another name.", null, null);
        }
        int cwid = (bw - (COLS - 1) * Tokens.S2) / COLS;
        for (int i = page * per; i < Math.min(all.size(), (page + 1) * per); i++) {
            int k = i - page * per;
            PlayerCards.Player p = all.get(i);
            int x = bx + (k % COLS) * (cwid + Tokens.S2), yy = y + (k / COLS) * 30;
            String id = "pl:" + p.uuid();
            int st = kit.state(id, x, yy, x + cwid, yy + 26, true);
            float h = kit.anim(id + "#h", (st & Kit.HOVER) != 0 ? 1f : 0f);
            Paint.surface(ctx, x, yy, x + cwid, yy + 26, Tokens.R_SM, Tokens.E2.noAmbient().withFill(Theme.lerpColor(Tokens.N200, Tokens.N250, h)), h, mx - x);
            boolean hidden = PlayerCards.isHidden(p.uuid()), friend = SocialClient.state().isFriend(p.uuid());
            float f0 = Theme.fade;
            if (hidden) Theme.fade = f0 * 0.5f;
            head(ctx, p.uuid(), x + 5, yy + 5, 16);
            Theme.fade = f0;
            kit.textCentredY(kit.ellipsize(p.name(), Role.STRONG, cwid - 40), x + 28, yy + 13, hidden ? Tokens.N600 : Tokens.N950, Role.STRONG);
            if (friend) Paint.circle(ctx, x + cwid - 10, yy + 13, 6, Tokens.OK);
            if ((st & Kit.FOCUSED) != 0) Paint.focusRing(kit, x, yy, x + cwid, yy + 26, Tokens.R_SM, Tokens.FOCUS_RING);
            kit.hit(id, x, yy, x + cwid, yy + 26, () -> { player = p; clearAndInit(); });
            if (friend || hidden) kit.tooltip(id, friend ? "Evade friend" : "Hidden on your screen", null, x, yy, x + cwid, yy + 26);
        }
        // Footer line: the count and the pager.
        int fy = py + ph - PAD - Button.Size.MD.h;
        int n = PlayerCards.tabList().size();
        String hid = PlayerCards.hiddenCount() > 0 ? "  ·  " + PlayerCards.hiddenCount() + " hidden" : "";
        String count = n == 0 ? "Nobody else is in the tab list" : n + (n == 1 ? " player" : " players") + " in the tab list" + hid;
        int px0 = bx;
        if (pages > 1) {
            Button.icon(kit, "prev", px0, fy, 24, "chevron_l", "Previous page", page > 0, () -> page--);
            String pg = (page + 1) + " / " + pages;
            kit.textCentredY(pg, px0 + 28, fy + 12, Tokens.N800, Role.SMALL);
            int pw2 = kit.width(pg, Role.SMALL);
            Button.icon(kit, "next", px0 + 32 + pw2, fy, 24, "chevron_r", "Next page", page < pages - 1, () -> page++);
            px0 += 32 + pw2 + 24 + Tokens.S4;
        }
        kit.textCentredY(kit.ellipsize(count, Role.SMALL, bx + bw - 80 - px0), px0, fy + 12, Tokens.N700, Role.SMALL);
    }
}
