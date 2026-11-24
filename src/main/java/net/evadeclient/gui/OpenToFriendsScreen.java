package net.evadeclient.gui;

import net.evadeclient.module.misc.Friends;
import net.evadeclient.social.MojangNames;
import net.evadeclient.social.OpenToFriends;
import net.evadeclient.social.SocialClient;
import net.evadeclient.social.SocialPresence;
import net.evadeclient.social.SocialState;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Open to Friends (pause menu, singleplayer). Without e4mc it explains how to turn it on in the launcher; with it, it
 * opens the world through e4mc and lets you pick which online friends and party members get an invite. The address
 * is never shown here and never copied anywhere: only the people you pick receive it, through evadeclient.com.
 */
public final class OpenToFriendsScreen extends EvadeDialog {
    private static final int ROWS = 6;
    private final Set<UUID> picked = new LinkedHashSet<>();
    private final List<CheckboxWidget> boxes = new ArrayList<>();
    private List<SocialState.Person> people = List.of();
    private String error;
    private boolean sending, asked;
    private int page;
    private ButtonWidget send;

    public OpenToFriendsScreen(Screen parent) {
        super("Open to Friends", parent);
    }

    @Override protected int prefWidth() { return 360; }

    @Override protected int prefHeight() { return OpenToFriends.open() ? 92 + ROWS * 22 + 30 : 170; }

    private enum Mode { NO_E4MC, NO_FRIENDS, START, WAITING, OPEN }

    private Mode mode() {
        if (!OpenToFriends.e4mcInstalled()) return Mode.NO_E4MC;
        Friends f = Friends.get();
        if (f == null || !f.isEnabled() || !f.activated() || !SocialClient.ready()) return Mode.NO_FRIENDS;
        if (OpenToFriends.open()) return Mode.OPEN;
        return asked && OpenToFriends.starting() ? Mode.WAITING : Mode.START;
    }

    private Mode built;

    @Override
    protected void init() {
        frame();
        boxes.clear();
        Mode m = built = mode();
        int by = py + ph - 30, bw = (pw - 28 - 4) / 2;
        switch (m) {
            case NO_E4MC, NO_FRIENDS -> {
                if (m == Mode.NO_FRIENDS) button("Open Friends", px + 14, by, bw, b -> {
                    Friends f = Friends.get();
                    if (f != null) f.activate();
                    EvadeMenu.openFriendsForTest(0); // sets the page the menu opens on
                    client.setScreen(EvadeMenu.open(null, this));
                });
                else button("OK", px + 14, by, bw, b -> close());
                button("Back", px + 14 + bw + 4, by, bw, b -> close());
            }
            case START, WAITING -> {
                ButtonWidget open = button(m == Mode.WAITING ? "Opening..." : "Open my world", px + 14, by, bw, b -> {
                    error = OpenToFriends.start();
                    asked = error == null;
                    clearAndInit();
                });
                open.active = m == Mode.START;
                button("Back", px + 14 + bw + 4, by, bw, b -> close());
            }
            case OPEN -> {
                people = OpenToFriends.candidates();
                if (picked.isEmpty()) {
                    SocialState s = SocialClient.state();
                    for (SocialState.Person p : people) if (s.party() != null && s.party().has(p.uuid())) picked.add(p.uuid());
                }
                int pages = Math.max(1, (people.size() + ROWS - 1) / ROWS);
                page = Math.min(page, pages - 1);
                int y = py + 70;
                for (int i = page * ROWS; i < Math.min(people.size(), (page + 1) * ROWS); i++) {
                    SocialState.Person p = people.get(i);
                    String name = MojangNames.shown(p.uuid(), p.name());
                    String sub = SocialPresence.describe(p);
                    CheckboxWidget box = CheckboxWidget.builder(Text.literal(name + (sub == null ? "" : "  §8" + sub)), textRenderer)
                            .pos(px + 14, y).checked(picked.contains(p.uuid()))
                            .callback((c, on) -> { if (on) picked.add(p.uuid()); else picked.remove(p.uuid()); updateSend(); }).build();
                    boxes.add(addDrawableChild(box));
                    y += 22;
                }
                if (pages > 1) {
                    button("<", px + pw - 14 - 44, py + 46, 20, b -> { page = Math.max(0, page - 1); clearAndInit(); });
                    button(">", px + pw - 14 - 20, py + 46, 20, b -> { page = Math.min(pages - 1, page + 1); clearAndInit(); });
                }
                int w3 = (pw - 28 - 8) / 3;
                send = button("Send invites", px + 14, by, w3, b -> sendInvites());
                button("Stop sharing", px + 14 + w3 + 4, by, w3, b -> {
                    OpenToFriends.stop();
                    Notifications.push("Open to Friends", "Your world is no longer shared", false);
                    close();
                });
                button("Back", px + 14 + (w3 + 4) * 2, by, w3, b -> close());
                updateSend();
            }
        }
    }

    private void updateSend() {
        if (send == null) return;
        picked.retainAll(people.stream().map(SocialState.Person::uuid).toList());
        send.active = !sending && !picked.isEmpty();
        send.setMessage(Text.literal(picked.isEmpty() ? "Send invites" : "Invite " + picked.size()));
    }

    private void sendInvites() {
        sending = true;
        updateSend();
        Friends.run(OpenToFriends.invite(picked), "Open to Friends", null, r -> {
            int n = SocialState.num(r, "delivered");
            Notifications.push("Open to Friends", n == 0 ? "Nobody got the invite (they may have gone offline)"
                    : "Invite sent to " + n + (n == 1 ? " player" : " players") + ". They join with the Join button", n > 0);
        });
        sending = false;
        close();
    }

    @Override
    public void tick() {
        if (built != mode()) clearAndInit(); // the address arrived, Friends connected, ...
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        title(ctx, OpenToFriends.open() ? OpenToFriends.buttonLabel() : "Open to Friends");
        int y = py + 32;
        switch (built) {
            case NO_E4MC -> {
                y = para(ctx, "Open to Friends lets Evade friends and your party join this world without a server. It needs the free e4mc mod, which the Evade launcher can add for you.", y, Theme.TEXT_DIM);
                y += 6;
                y = para(ctx, "In the Evade launcher, open this instance's settings, then Extras, and turn on \"Open to Friends\". Restart the game, then press this button again.", y, Theme.TEXT);
                para(ctx, "e4mc is MIT licensed and downloaded from Modrinth by the launcher. It's off unless you turn it on.", y + 6, Theme.TEXT_FAINT);
            }
            case NO_FRIENDS -> {
                y = para(ctx, "Invites go to your Evade friends and party, so Friends needs to be on and connected.", y, Theme.TEXT_DIM);
                para(ctx, "Friends: " + SocialClient.status(), y + 6, Theme.TEXT_FAINT);
            }
            case START, WAITING -> {
                y = para(ctx, "Opens this world to LAN and e4mc gives it a private address. Then you pick which friends and party members get an invite; only they get the address, and they join by clicking Join.", y, Theme.TEXT_DIM);
                if (built == Mode.WAITING) para(ctx, "Waiting for e4mc...", y + 6, Theme.TEXT);
                if (error != null) para(ctx, error, y + 6, Theme.WARN);
                else if (OpenToFriends.timedOut()) para(ctx, "e4mc didn't answer. Check chat for its message, or try again.", y + 6, Theme.WARN);
            }
            case OPEN -> {
                para(ctx, people.isEmpty() ? "None of your friends or party are online right now." : "Pick who gets an invite (party members are picked):", y, Theme.TEXT_DIM);
                line(ctx, "Only the people you pick get the address.", px + 14, py + 50, Theme.TEXT_FAINT);
            }
        }
    }
}
