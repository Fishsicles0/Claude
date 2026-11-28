package net.evadeclient.gui.menu;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.kit.Kit;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.Sidebar;
import net.evadeclient.social.SocialChat;
import net.evadeclient.social.SocialClient;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The menu's sidebar: the Evade lockup, the page list in three groups (with neutral counts and the gliding accent
 * highlight), a TOOLS grid of icon tiles for the screens the menu opens (HUD editor, keybinds, map, ...), and your
 * account chip at the bottom, which opens Friends and carries a small accent dot when something waits there.
 */
public final class MenuSidebar {
    private record Nav(String label, MenuPage page, String icon) {}
    private record Group(String header, List<Nav> items) {}
    private record Tool(String id, String label, String icon, Runnable action) {}

    private static final List<Group> GROUPS = List.of(
            new Group(null, List.of(new Nav("All mods", MenuPage.ALL, "all"))),
            new Group("Gameplay", List.of(new Nav("Controls", MenuPage.MODS, "controls"),
                    new Nav("Server-facing", MenuPage.SERVER, "server"), new Nav("Misc", MenuPage.MISC, "misc"))),
            new Group("Interface", List.of(new Nav("HUD", MenuPage.HUD, "hud"), new Nav("Visuals", MenuPage.VISUALS, "visuals"))),
            new Group("General", List.of(new Nav("Profiles", MenuPage.PROFILES, "profiles"), new Nav("Settings", MenuPage.SETTINGS, "settings"))));

    private final MenuHost host;
    private final List<Tool> tools;
    private final Map<String, int[]> rects = new HashMap<>();
    private int[] counts;

    static final int TOP = 58, HEADER = 20, GROUP_GAP = 6, TILE_H = 30;

    public MenuSidebar(MenuHost host) {
        this.host = host;
        this.tools = List.of(
                new Tool("edit", "Edit HUD layout", "edit", () -> ((net.evadeclient.gui.EvadeMenu) host).editHud()),
                new Tool("keys", "Keybinds", "keyboard", net.evadeclient.gui.KeybindsScreen::openFromCurrent),
                new Tool("map", "World map", "map", net.evadeclient.gui.WorldMapScreen::openFromMenu),
                new Tool("schem", "Schematics", "schematic", net.evadeclient.gui.SchematicBrowserScreen::openFromMenu),
                new Tool("browse", "Mod browser", "mods", net.evadeclient.gui.ModBrowserScreen::open),
                new Tool("play", "Quick play", "server", net.evadeclient.module.misc.QuickPlay::open),
                new Tool("shots", "Screenshots", "camera", net.evadeclient.gui.ScreenshotGalleryScreen::openFromCurrent),
                new Tool("wardrobe", "Wardrobe", "wardrobe", net.evadeclient.cosmetics.WardrobeScreen::openFromCurrent));
    }

    /** Category counts (modules don't change while the game runs). */
    private String count(MenuPage p) {
        if (p.category == null || p == MenuPage.SETTINGS) return null;
        if (counts == null) {
            counts = new int[MenuPage.values().length];
            for (MenuPage q : MenuPage.values()) if (q.category != null) counts[q.ordinal()] = host.modulesFor(q, "").size();
        }
        return String.valueOf(counts[p.ordinal()]);
    }

    public void wheel(double amount) { /* the sidebar fits at the design size */ }

    public void render(Kit k, int wx, int wy, int sw, int wh) {
        rects.clear();
        int x = wx + 12, w = sw - 24;
        net.evadeclient.util.Logo.lockup(k.ctx, wx + 20, wy + 18, 22);
        UiFont.caps(k.ctx, k.tr, "Client", wx + 48, wy + 38, Tokens.N600, Role.CAPS_SM, 1.6f, k.smooth);

        MenuPage page = host.page();
        boolean any = false;
        Sidebar.highlight(k, "nav", x, w);
        int y = wy + TOP;
        for (Group g : GROUPS) {
            if (g.header != null) {
                y += GROUP_GAP;
                Sidebar.header(k, g.header.toUpperCase(java.util.Locale.ROOT), x + 10, y + 7);
                y += HEADER;
            }
            for (Nav n : g.items) {
                boolean sel = host.selected() == null && (n.page == page || (n.page == MenuPage.ALL && page == MenuPage.PINNED));
                any |= sel;
                String id = "nav:" + n.label;
                Sidebar.item(k, "nav", id, x, y, w, n.icon, n.label, count(n.page), sel, false, () -> host.go(n.page));
                rects.put(id, new int[]{x, y, x + w, y + Sidebar.ITEM_H});
                y += Sidebar.ITEM_H;
            }
        }
        if (!any) k.remember("nav#selY", null);

        // Tools: the screens the menu opens, as a grid of icon tiles with their names in tooltips.
        y += GROUP_GAP;
        Sidebar.header(k, "TOOLS", x + 10, y + 7);
        y += HEADER;
        int cols = 4, gap = 4, tw = (w - gap * (cols - 1)) / cols;
        for (int i = 0; i < tools.size(); i++) {
            Tool t = tools.get(i);
            int tx = x + (i % cols) * (tw + gap), ty = y + (i / cols) * (TILE_H + gap);
            tile(k, "tool:" + t.id, tx, ty, tw, t.icon, t.label, t.action);
        }

        footer(k, wx + 10, wy + wh - 48, sw - 20, page == MenuPage.FRIENDS && host.selected() == null);
    }

    private void tile(Kit k, String id, int x, int y, int w, String icon, String label, Runnable action) {
        int st = k.state(id, x, y, x + w, y + TILE_H, true);
        float hov = k.anim(id + "#h", (st & Kit.HOVER) != 0 ? 1f : 0f);
        float press = (st & Kit.PRESSED) != 0 ? 0.5f / Theme.deviceScale(k.ctx) : 0f;
        Paint.surface(k.ctx, x, y + press, x + w, y + TILE_H + press, Tokens.R_SM,
                Tokens.E2.noAmbient().withFill(Theme.lerpColor(Theme.alpha(Tokens.N200, 0.55f), Tokens.N250, hov)), hov, k.mx - x);
        Icons.draw(k.ctx, icon, x + (w - 16) / 2, Math.round(y + press + (TILE_H - 16) / 2f), 16, Theme.lerpColor(Tokens.N700, Tokens.N950, hov));
        if ((st & Kit.FOCUSED) != 0) Paint.focusRing(k, x, y, x + w, y + TILE_H, Tokens.R_SM, Tokens.FOCUS_RING);
        k.hit(id, x, y, x + w, y + TILE_H, action);
        k.tooltip(id, label, null, x, y, x + w, y + TILE_H);
        rects.put(id, new int[]{x, y, x + w, y + TILE_H});
    }

    /** Your account chip: head, name and how many friends are online; opens Friends. */
    private void footer(Kit k, int x, int y, int w, boolean active) {
        Paint.fadeLine(k.ctx, x + 4, y - 7, x + w - 4, Theme.alpha(0xFFFFFFFF, 0.06f));
        String id = "nav:Account";
        int h = 38;
        int st = k.state(id, x, y, x + w, y + h, true);
        float hov = k.anim(id + "#h", (st & Kit.HOVER) != 0 ? 1f : 0f);
        if (active) {
            Paint.hgradient(k.ctx, x, y, x + w, y + h, Tokens.R_SM, Theme.lerpColor(Tokens.N200, Tokens.A900, 0.45f), Theme.alpha(Tokens.N200, 0f));
            Paint.slant(k.ctx, x + 3, y + 9, x + 5, y + h - 9, Tokens.A500);
        } else if (hov > 0.01f) {
            Paint.rect(k.ctx, x, y, x + w, y + h, Tokens.R_SM, Theme.alpha(Tokens.N250, 0.7f * hov));
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        var session = mc.getSession();
        String user = session == null || session.getUsername() == null || session.getUsername().isEmpty() ? "Player" : session.getUsername();
        java.util.UUID uuid = session == null ? null : session.getUuidOrNull();
        boolean on = FriendsPage.friendsOn();
        var s = SocialClient.state();
        int waiting = on ? s.incoming().size() + s.invites().size() + SocialChat.totalUnread() : 0;
        Heads.draw(k.ctx, uuid, user, x + 10, y + 9, 20, on && s.loaded() ? Tokens.OK : 0);
        int tx = x + 40, tw = w - 40 - (waiting > 0 ? 18 : 8);
        k.text(host.fit(user, Role.STRONG, tw), tx, y + 9, active ? Tokens.N950 : Theme.lerpColor(Tokens.N900, Tokens.N950, hov), Role.STRONG);
        String line = !on ? "Friends  ·  Evade " + EvadeClient.VERSION
                : s.loaded() ? s.onlineFriends() + (s.onlineFriends() == 1 ? " friend" : " friends") + " online" : "Connecting…";
        k.text(host.fit(line, Role.SMALL, tw), tx, y + 22, Tokens.N700, Role.SMALL);
        if (waiting > 0) Paint.circle(k.ctx, x + w - 12, y + h / 2f, 6, Tokens.A500);
        if ((st & Kit.FOCUSED) != 0) Paint.focusRing(k, x, y, x + w, y + h, Tokens.R_SM, Tokens.FOCUS_RING);
        k.hit(id, x, y, x + w, y + h, () -> host.go(MenuPage.FRIENDS));
        k.tooltip(id, waiting > 0 ? waiting + (waiting == 1 ? " thing waits" : " things wait") + " in Friends" : "Friends and party",
                "Evade " + EvadeClient.VERSION, x, y, x + w, y + h);
        rects.put(id, new int[]{x, y, x + w, y + h});
    }

    /** Test hook: where a sidebar control was drawn last frame (design px), or null. */
    public int[] rectForTest(String id) { return rects.get(id); }
}
