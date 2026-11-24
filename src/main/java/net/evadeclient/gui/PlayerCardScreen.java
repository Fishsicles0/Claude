package net.evadeclient.gui;

import net.evadeclient.api.ApiInteractions;
import net.evadeclient.module.misc.Friends;
import net.evadeclient.module.misc.VoiceChat;
import net.evadeclient.social.PlayerCards;
import net.evadeclient.social.SocialClient;
import net.evadeclient.social.SocialPresence;
import net.evadeclient.social.SocialState;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;

/**
 * Player cards. The list shows the players in the tab list (names the game already shows); clicking one opens their
 * card: Add friend, Invite to party, Copy name, voice chat volume, Hide this player (your screen only, until you close
 * the game) and any entries the server added through the Server API. Works only from names you can already see:
 * there's no way to pick a player in the world.
 */
public final class PlayerCardScreen extends EvadeDialog {
    private static final int COLS = 3, ROWS = 7;
    private PlayerCards.Player player;
    private TextFieldWidget search;
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

    @Override protected int prefWidth() { return player == null ? 380 : 300; }

    @Override protected int prefHeight() { return player == null ? 76 + ROWS * 22 + 30 : 96 + rows() * 22 + 30; }

    private int rows() {
        if (player == null) return 0;
        return (actions() + 1) / 2;
    }

    private int actions() {
        int n = 3; // copy, hide, back is in the footer
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

    @Override
    protected void init() {
        frame();
        if (player == null) initList(); else initCard();
    }

    private void initList() {
        String q = search == null ? "" : search.getText();
        search = new TextFieldWidget(textRenderer, px + 14, py + 32, pw - 28, 18, Text.literal("Search"));
        search.setText(q);
        search.setPlaceholder(Text.literal("Search players").formatted(net.minecraft.util.Formatting.DARK_GRAY));
        search.setChangedListener(s -> { page = 0; clearAndInit(); search.setFocused(true); setFocused(search); });
        addDrawableChild(search);
        List<PlayerCards.Player> all = PlayerCards.tabList();
        String needle = q.strip().toLowerCase(Locale.ROOT);
        if (!needle.isEmpty()) all = all.stream().filter(p -> p.name().toLowerCase(Locale.ROOT).contains(needle)).toList();
        int per = COLS * ROWS, pages = Math.max(1, (all.size() + per - 1) / per);
        page = Math.min(page, pages - 1);
        int cw = (pw - 28 - (COLS - 1) * 4) / COLS;
        for (int i = page * per; i < Math.min(all.size(), (page + 1) * per); i++) {
            int k = i - page * per;
            PlayerCards.Player p = all.get(i);
            String label = (PlayerCards.isHidden(p.uuid()) ? "§8" : SocialClient.state().isFriend(p.uuid()) ? "§a" : "") + p.name();
            button(label, px + 14 + (k % COLS) * (cw + 4), py + 56 + (k / COLS) * 22, cw, b -> {
                player = p;
                clearAndInit();
            });
        }
        int by = py + ph - 30, bw = (pw - 28 - 8) / 3;
        ButtonWidget prev = button("< Page", px + 14, by, bw, b -> { page--; clearAndInit(); });
        prev.active = page > 0;
        ButtonWidget next = button("Page >", px + 14 + bw + 4, by, bw, b -> { page++; clearAndInit(); });
        next.active = page < pages - 1;
        button("Done", px + 14 + (bw + 4) * 2, by, bw, b -> close());
    }

    private void initCard() {
        SocialState s = SocialClient.state();
        java.util.UUID u = player.uuid();
        String name = player.name();
        int bw = (pw - 28 - 4) / 2;
        int[] slot = {0};
        java.util.function.BiConsumer<String, ButtonWidget.PressAction> add = (label, action) -> {
            int i = slot[0]++;
            button(label, px + 14 + (i % 2) * (bw + 4), py + 90 + (i / 2) * 22, bw, action);
        };
        if (socialOn() && !s.isFriend(u)) add.accept("Add friend", b -> Friends.run(Friends.addFriend(name), "Friends", null,
                r -> Notifications.push("Friends", SocialState.bool(r, "accepted") ? "You're now friends with " + name : "Friend request sent to " + name, true)));
        if (socialOn() && s.isFriend(u)) {
            boolean online = s.friends().stream().anyMatch(p -> p.uuid().equals(u) && p.online());
            boolean inParty = s.party() != null && s.party().has(u);
            add.accept(inParty ? "In your party" : "Invite to party", b -> {
                if (inParty) return;
                if (online) Friends.run(Friends.withUuid("party.invite", u), "Party", "Invited " + name, null);
                else Notifications.push("Party", name + " isn't online on Evade right now", false);
            });
        }
        add.accept("Copy name", b -> {
            if (client != null) client.keyboard.setClipboard(name);
            Notifications.push("Player card", "Copied " + name, true);
        });
        boolean hidden = PlayerCards.isHidden(u);
        add.accept(hidden ? "Show this player" : "Hide this player", b -> {
            if (hidden) PlayerCards.show(u); else PlayerCards.hide(u);
            Notifications.push("Player card", hidden ? name + " is shown again" : name + " is hidden on your screen until you close the game", true);
            clearAndInit();
        });
        if (VoiceChat.installed()) add.accept("Voice volume...", b -> {
            close();
            Notifications.push("Voice chat", "Adjust volumes: set " + name + " to 0 to mute them", false);
            VoiceChat.openMenu();
        });
        for (ApiInteractions.Entry e : ApiInteractions.entries()) add.accept(e.label(), b -> {
            if (ApiInteractions.run(e, name)) close();
        });
        int by = py + ph - 30;
        button("All players", px + 14, by, bw, b -> { player = null; clearAndInit(); });
        button("Done", px + 14 + bw + 4, by, bw, b -> close());
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        if (player == null) {
            title(ctx, "Players");
            int n = PlayerCards.tabList().size();
            String hid = PlayerCards.hiddenCount() > 0 ? "  ·  " + PlayerCards.hiddenCount() + " hidden" : "";
            line(ctx, n == 0 ? "Nobody else is in the tab list." : n + (n == 1 ? " player" : " players") + " in the tab list" + hid,
                    px + 14, py + ph - 42, Theme.TEXT_FAINT);
            return;
        }
        title(ctx, player.name());
        SocialState s = SocialClient.state();
        String rel = s.isFriend(player.uuid()) ? "Evade friend" : s.party() != null && s.party().has(player.uuid()) ? "In your party" : "Player on this server";
        line(ctx, rel, px + 14, py + 32, s.isFriend(player.uuid()) ? Theme.OK : Theme.TEXT_DIM);
        var friend = s.friends().stream().filter(p -> p.uuid().equals(player.uuid())).findFirst().orElse(null);
        String sub = friend == null ? null : SocialPresence.describe(friend);
        if (sub != null) line(ctx, sub, px + 14, py + 45, Theme.TEXT_DIM);
        if (PlayerCards.isHidden(player.uuid())) line(ctx, "Hidden on your screen (this session)", px + 14, py + 58, Theme.WARN);
        if (!socialOn()) line(ctx, "Turn on Friends to add friends and invite to your party.", px + 14, py + 72, Theme.TEXT_FAINT);
        else if (!ApiInteractions.entries().isEmpty()) line(ctx, "Server entries run the server's own command.", px + 14, py + 72, Theme.TEXT_FAINT);
    }
}
