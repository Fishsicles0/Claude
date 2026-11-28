package net.evadeclient.gui;

import net.evadeclient.module.misc.Friends;
import net.evadeclient.social.MojangNames;
import net.evadeclient.social.OpenToFriends;
import net.evadeclient.social.SocialClient;
import net.evadeclient.social.SocialPresence;
import net.evadeclient.social.SocialState;
import net.evadeclient.util.Notifications;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.Kit;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.Progress;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;

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
    private List<SocialState.Person> people = List.of();
    private String error;
    private boolean sending, asked;
    private int page;
    private Act send;

    public OpenToFriendsScreen(Screen parent) {
        super("Open to Friends", parent);
    }

    @Override protected int prefWidth() { return 420; }

    @Override protected int prefHeight() { return OpenToFriends.open() ? 120 + ROWS * 30 + 54 : 236; }

    @Override protected String titleIcon() { return "friends"; }

    @Override protected String heading() { return OpenToFriends.open() ? OpenToFriends.buttonLabel() : "Open to Friends"; }

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
        Mode m = built = mode();
        send = null;
        switch (m) {
            case NO_E4MC, NO_FRIENDS -> {
                action("Back", Button.Variant.GHOST, this::close);
                if (m == Mode.NO_FRIENDS) action("Open Friends", Button.Variant.PRIMARY, () -> {
                    Friends f = Friends.get();
                    if (f != null) f.activate();
                    EvadeMenu.openFriendsForTest(0); // sets the page the menu opens on
                    client.setScreen(EvadeMenu.open(null, this));
                });
                else action("OK", Button.Variant.PRIMARY, this::close);
            }
            case START, WAITING -> {
                action("Back", Button.Variant.GHOST, this::close);
                Act open = action(m == Mode.WAITING ? "Opening…" : "Open my world", Button.Variant.PRIMARY, () -> {
                    error = OpenToFriends.start();
                    asked = error == null;
                    clearAndInit();
                });
                open.active = m == Mode.START;
            }
            case OPEN -> {
                people = OpenToFriends.candidates();
                if (picked.isEmpty()) {
                    SocialState s = SocialClient.state();
                    for (SocialState.Person p : people) if (s.party() != null && s.party().has(p.uuid())) picked.add(p.uuid());
                }
                int pages = Math.max(1, (people.size() + ROWS - 1) / ROWS);
                page = Math.min(page, pages - 1);
                action("Stop sharing", Button.Variant.DANGER, () -> {
                    OpenToFriends.stop();
                    Notifications.push("Open to Friends", "Your world is no longer shared", false);
                    close();
                }).left();
                action("Back", Button.Variant.GHOST, this::close);
                send = action("Send invites", Button.Variant.PRIMARY, this::sendInvites);
                updateSend();
            }
        }
    }

    private void updateSend() {
        if (send == null) return;
        picked.retainAll(people.stream().map(SocialState.Person::uuid).toList());
        send.active = !sending && !picked.isEmpty();
        send.setLabel(picked.isEmpty() ? "Send invites" : "Invite " + picked.size());
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
    protected void renderBody(DrawContext ctx, int mx, int my) {
        int y = by;
        switch (built) {
            case NO_E4MC -> {
                y = para("Open to Friends lets Evade friends and your party join this world without a server. It needs the free e4mc mod, which the Evade launcher can add for you.", bx, y, bw, Tokens.N800, Role.BODY);
                y += 8;
                // The steps, numbered.
                String[] steps = {"In the Evade launcher, open this instance's settings, then Extras.", "Turn on \"Open to Friends\".", "Restart the game, then press this button again."};
                for (int i = 0; i < steps.length; i++) {
                    Paint.circle(ctx, bx + 7, y + 5, 14, Tokens.N250);
                    String n = String.valueOf(i + 1);
                    kit.textCentredY(n, bx + 7 - kit.width(n, Role.TINY) / 2, y + 5, Tokens.N900, Role.TINY);
                    y = para(steps[i], bx + 22, y, bw - 22, Tokens.N950, Role.BODY) + 4;
                }
                note("info", Tokens.INFO, "e4mc is MIT licensed and downloaded from Modrinth by the launcher. It's off unless you turn it on.", y + 4);
            }
            case NO_FRIENDS -> {
                y = para("Invites go to your Evade friends and party, so Friends needs to be on and connected.", bx, y, bw, Tokens.N800, Role.BODY);
                note("info", Tokens.INFO, "Friends: " + SocialClient.status(), y + 8);
            }
            case START, WAITING -> {
                y = para("Opens this world to LAN and e4mc gives it a private address. Then you pick which friends and party members get an invite; only they get the address, and they join by clicking Join.", bx, y, bw, Tokens.N800, Role.BODY);
                y += 10;
                if (built == Mode.WAITING) {
                    Progress.circle(kit, bx + 6, y + 5, 6, Float.NaN);
                    kit.text("Waiting for e4mc…", bx + 18, y, Tokens.N950, Role.BODY);
                } else if (error != null) note("warn", Tokens.WARN, error, y);
                else if (OpenToFriends.timedOut()) note("warn", Tokens.WARN, "e4mc didn't answer. Check chat for its message, or try again.", y);
            }
            case OPEN -> {
                kit.text(people.isEmpty() ? "None of your friends or party are online right now." : "Pick who gets an invite (party members are picked).", bx, y, Tokens.N800, Role.BODY);
                int pages = Math.max(1, (people.size() + ROWS - 1) / ROWS);
                if (pages > 1) {
                    int rx = bx + bw;
                    String pg = (page + 1) + " / " + pages;
                    Button.icon(kit, "next", rx - 22, y - 5, 22, "chevron_r", "Next page", page < pages - 1, () -> { page++; });
                    kit.textCentredY(pg, rx - 26 - kit.width(pg, Role.SMALL), y + 6, Tokens.N700, Role.SMALL);
                    Button.icon(kit, "prev", rx - 52 - kit.width(pg, Role.SMALL), y - 5, 22, "chevron_l", "Previous page", page > 0, () -> { page--; });
                }
                y += 20;
                for (int i = page * ROWS; i < Math.min(people.size(), (page + 1) * ROWS); i++) {
                    SocialState.Person p = people.get(i);
                    boolean on = picked.contains(p.uuid());
                    String id = "p:" + p.uuid();
                    int st = kit.state(id, bx, y, bx + bw, y + 28, true);
                    float h = kit.anim(id + "#h", (st & Kit.HOVER) != 0 ? 1f : 0f);
                    Paint.surface(ctx, bx, y, bx + bw, y + 28, Tokens.R_SM, Tokens.E2.noAmbient().withFill(Theme.lerpColor(Tokens.N200, Tokens.N250, h)), h, Float.NaN);
                    // The checkbox: a sunken square, accent with a check when picked.
                    float cyy = y + 14;
                    float on01 = kit.anim(id + "#on", on ? 1f : 0f);
                    if (on01 > 0.5f) Paint.rect(ctx, bx + 8, cyy - 7, bx + 22, cyy + 7, Tokens.R_XS, Tokens.A600);
                    else Paint.sunken(ctx, bx + 8, cyy - 7, bx + 22, cyy + 7, Tokens.R_XS);
                    if (on01 > 0.5f) Icons.draw(ctx, "check", bx + 10, Math.round(cyy - 5), 10, Tokens.ON_ACCENT);
                    if ((st & Kit.FOCUSED) != 0) Paint.focusRing(kit, bx, y, bx + bw, y + 28, Tokens.R_SM, Tokens.FOCUS_RING);
                    String name = MojangNames.shown(p.uuid(), p.name());
                    kit.textCentredY(name, bx + 32, cyy, Tokens.N950, Role.STRONG);
                    String sub = SocialPresence.describe(p);
                    if (sub != null) kit.textCentredY(kit.ellipsize(sub, Role.SMALL, bw - 50 - kit.width(name, Role.STRONG)), bx + 40 + kit.width(name, Role.STRONG), cyy, Tokens.N700, Role.SMALL);
                    kit.hit(id, bx, y, bx + bw, y + 28, () -> { if (!picked.remove(p.uuid())) picked.add(p.uuid()); updateSend(); });
                    y += 30;
                }
                note("info", Tokens.N600, "Only the people you pick get the address.", py + ph - PAD - Button.Size.MD.h - 22);
            }
        }
    }
}
