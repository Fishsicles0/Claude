package net.evadeclient.gui.menu;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.kit.Badge;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.Kit;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.SearchField;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;

import java.util.List;

/**
 * Profiles: each one remembers its mods, settings, keybinds and HUD layout. A name field with Import and Create
 * (the page's one primary action) in the header, then a card per profile: the active one carries an accent edge and
 * an ACTIVE badge; the others get Use. Copy code shares a profile, the server button links it to the server you are
 * on (it loads there by itself), and delete asks twice.
 */
public final class ProfilesPage {
    public static final String FIELD = "profileName";
    private static final int ROW_H = 48, GAP = 6;

    private final MenuHost host;
    private String pendingDelete;
    private double pendingAt;

    public ProfilesPage(MenuHost host) { this.host = host; }

    public void reset() {
        SearchField.set(host.kit(), FIELD, "");
        pendingDelete = null;
    }

    public void render(Kit k) {
        var profiles = EvadeClient.profiles;
        if (profiles == null) return;
        if (pendingDelete != null && host.now() - pendingAt > 3000) pendingDelete = null;
        int cx = host.cx(), cw = host.cw(), top = host.top(), cy = host.cy() - 30, ch = host.ch() + 30 - 18;
        List<String> list = profiles.list();
        String active = profiles.active();
        MenuScroll scroll = host.scroll();
        scroll.clamp(list.size() * (ROW_H + GAP), ch);
        int si = scroll.px();
        k.ctx.enableScissor(cx - 12, cy, cx + cw + 12, cy + ch);
        scroll.push(k.ctx);
        try {
            for (int i = 0; i < list.size(); i++) {
                int y = cy + i * (ROW_H + GAP) - si;
                if (y + ROW_H < cy || y > cy + ch) continue;
                row(k, list.get(i), list.get(i).equals(active), list.size(), cx, y, cw);
            }
        } finally {
            scroll.pop(k.ctx);
            k.ctx.disableScissor();
        }
        scroll.chrome(k, "profbar", cx - 12, cy, cx + cw, cy + ch);
        k.barrier("prof#top", cx - 12, top, cx + cw + 20, cy, null);
        k.text(host.fit("Each profile remembers its mods, settings, keybinds and HUD layout. Copy a code to share one; Import adds a copied code.",
                Role.SMALL, cw), cx, cy + ch + 6, Tokens.N700, Role.SMALL);

        // Header: title and count, then the name field, Import and Create.
        k.textCentredY("Profiles", cx, top + 16, Tokens.N950, Role.HEADLINE);
        Badge.draw(k, cx + k.width("Profiles", Role.HEADLINE) + 10, top + 10, String.valueOf(list.size()), Badge.Tone.NEUTRAL);
        int right = host.headerRight();
        int cwid = Button.width(k, "Create", Button.Size.MD, "plus", false);
        Button.draw(k, "create", right - cwid, top + 4, cwid, Button.Size.MD, Button.Variant.PRIMARY, "Create", "plus", true, this::create);
        int iw = Button.width(k, "Import", Button.Size.MD, null, false);
        Button.draw(k, "import", right - cwid - 6 - iw, top + 4, iw, Button.Size.MD, Button.Variant.SECONDARY, "Import", null, true, () -> {
            var r = profiles.importProfile(MinecraftClient.getInstance().keyboard.getClipboard());
            if (r.ok()) Notifications.push("Profile imported", r.name() + " is ready to use", true);
            else Notifications.push(r.error().title, r.error().detail, false);
        });
        k.tooltip("import", "Import a profile code", "Copy a code someone shared, then click Import", right - cwid - 6 - iw, top + 4, right - cwid - 6, top + 28);
        int fw = 220;
        SearchField.draw(k, FIELD, right - cwid - iw - 12 - fw, top + 4, fw, "New profile name…", null, false);
        Paint.fadeLine(k.ctx, cx - 8, cy - 7, cx + cw + 8, Theme.alpha(0xFFFFFFFF, 0.06f));
    }

    /** Create a profile from the field (or "Profile N" when it is blank) and switch to it. */
    public void create() {
        var profiles = EvadeClient.profiles;
        if (profiles == null) return;
        String typed = SearchField.text(host.kit(), FIELD);
        String clean = net.evadeclient.config.ProfileManager.sanitize(typed);
        String name = clean;
        if (name.isEmpty()) {
            int n = profiles.list().size() + 1;
            while (profiles.exists("Profile " + n)) n++;
            name = "Profile " + n;
        }
        if (profiles.create(name)) {
            SearchField.set(host.kit(), FIELD, "");
            host.kit().focus(null, false);
            Notifications.push("Profile created", name + " is now active", true);
        } else if (!clean.isEmpty()) {
            Notifications.push("Name taken", "There's already a profile called " + name, false);
        }
    }

    private static String currentServer() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.isInSingleplayer() || mc.getCurrentServerEntry() == null) return null;
        return mc.getCurrentServerEntry().address;
    }

    private void row(Kit k, String name, boolean active, int total, int x, int y, int w) {
        var profiles = EvadeClient.profiles;
        String id = "prof:" + name;
        int st = k.state(id, x, y, x + w, y + ROW_H, true);
        float hov = k.anim(id + "#h", (st & Kit.HOVER) != 0 ? 1f : 0f);
        float act = k.anim(id + "#a", active ? 1f : 0f);
        Paint.surface(k.ctx, x, y, x + w, y + ROW_H, Tokens.R_MD, Tokens.E2.noAmbient().withFill(Theme.lerpColor(Tokens.N200, Tokens.N250, hov)), hov, k.mx - x);
        if (act > 0.01f) {
            Paint.hgradient(k.ctx, x, y, x + w, y + ROW_H, Tokens.R_MD, Theme.alpha(Tokens.A900, 0.45f * act), Theme.alpha(Tokens.A900, 0f));
            Paint.slant(k.ctx, x + 5, y + 12, x + 7, y + ROW_H - 12, Theme.alpha(Tokens.A500, act));
        }
        k.hit(id, x, y, x + w, y + ROW_H, (b, mx, my) -> { }, false);
        float cy = y + ROW_H / 2f;
        Paint.rect(k.ctx, x + 12, cy - 13, x + 38, cy + 13, Tokens.R_XS + 1, Tokens.N250);
        Icons.draw(k.ctx, "profiles", x + 17, Math.round(cy - 8), 16, active ? Tokens.A400 : Tokens.N800);
        int right = x + w - 12;
        // Right to left: Use (or the ACTIVE badge), delete, copy code, the server link.
        if (active) {
            int bw = Badge.width(k, "ACTIVE");
            Badge.draw(k, right - bw, Math.round(cy - Badge.H / 2f), "ACTIVE", Badge.Tone.ACCENT);
            right -= bw + 8;
        } else {
            int uw = Button.draw(k, id + "#use", right - 52, Math.round(cy - 12), 52, Button.Size.MD, Button.Variant.SECONDARY, "Use", null, true,
                    () -> profiles.switchTo(name));
            right -= uw + 6;
        }
        if (!active && total > 1) {
            boolean confirm = name.equals(pendingDelete);
            if (confirm) {
                int dw = Button.width(k, "Delete?", Button.Size.MD, null, false);
                Button.draw(k, id + "#del", right - dw, Math.round(cy - 12), dw, Button.Size.MD, Button.Variant.DANGER, "Delete?", null, true, () -> {
                    if (profiles.delete(name)) Notifications.push("Profile deleted", name, true);
                    pendingDelete = null;
                });
                k.tooltip(id + "#del", "Click again to delete " + name, null, right - dw, Math.round(cy - 12), right, Math.round(cy + 12));
                right -= dw + 6;
            } else {
                Button.icon(k, id + "#del", right - 24, Math.round(cy - 12), 24, "close", "Delete " + name, true, () -> {
                    pendingDelete = name;
                    pendingAt = host.now();
                });
                right -= 30;
            }
        }
        int cpw = Button.width(k, "Copy code", Button.Size.MD, null, false);
        Button.draw(k, id + "#copy", right - cpw, Math.round(cy - 12), cpw, Button.Size.MD, Button.Variant.GHOST, "Copy code", null, true, () -> {
            String code = profiles.shareCode(name);
            if (code != null) {
                MinecraftClient.getInstance().keyboard.setClipboard(code);
                Notifications.push("Code copied", "Share it; anyone can paste it into Import", true);
            }
        });
        right -= cpw + 6;
        String srv = currentServer();
        if (srv != null) {
            boolean linked = profiles.isLinked(name, srv);
            String label = linked ? "Linked here" : "Use on this server";
            int lw = Button.width(k, label, Button.Size.MD, linked ? "check" : "server", false);
            Button.draw(k, id + "#srv", right - lw, Math.round(cy - 12), lw, Button.Size.MD, Button.Variant.GHOST, label, linked ? "check" : "server", true, () -> {
                boolean on = profiles.toggleServer(name, srv);
                Notifications.push("Profile", on ? name + " will load on this server" : "Unlinked from this server", true);
            });
            right -= lw + 6;
        }
        int tx = x + 50, tw = right - tx - 8;
        List<String> servers = profiles.serversFor(name);
        if (servers.isEmpty()) {
            k.textCentredY(host.fit(name, Role.STRONG, tw), tx, cy, Tokens.N950, Role.STRONG);
        } else {
            k.textCentredY(host.fit(name, Role.STRONG, tw), tx, cy - 6, Tokens.N950, Role.STRONG);
            k.textCentredY(host.fit("Loads on " + String.join(", ", servers), Role.SMALL, tw), tx, cy + 8, Tokens.N700, Role.SMALL);
        }
    }
}
