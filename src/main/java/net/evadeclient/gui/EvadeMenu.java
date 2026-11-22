package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.Setting;
import net.evadeclient.util.Theme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Evade menu: a centred window with a grouped sidebar, a search bar, an All/Enabled filter and a
 * list of modules (List, Cards or Compact layout), plus a per-module settings page. Black and red throughout.
 */
public class EvadeMenu extends Screen {
    private enum Page {
        ALL("All Mods", null), MODS("Controls", Category.MODS), SERVER("Server-facing", Category.SERVER),
        MISC("Misc", Category.MISC), HUD("HUD", Category.HUD), VISUALS("Visuals", Category.VISUALS),
        PROFILES("Profiles", null), SETTINGS("Settings", Category.CLIENT);

        final String label;
        final Category category;

        Page(String label, Category category) { this.label = label; this.category = category; }
    }

    /** A sidebar entry: a category page, or the special Edit-HUD action (page == null, hud == true). */
    private record Nav(String label, Page page, String icon, boolean hud) {}
    private record NavGroup(String header, List<Nav> items) {}

    private static final List<NavGroup> GROUPS = List.of(
            new NavGroup(null, List.of(new Nav("All Mods", Page.ALL, "all", false))),
            new NavGroup("GAMEPLAY", List.of(
                    new Nav("Controls", Page.MODS, "controls", false),
                    new Nav("Server-facing", Page.SERVER, "server", false),
                    new Nav("Misc", Page.MISC, "misc", false))),
            new NavGroup("INTERFACE", List.of(
                    new Nav("HUD", Page.HUD, "hud", false),
                    new Nav("Visuals", Page.VISUALS, "visuals", false))),
            new NavGroup("GENERAL", List.of(
                    new Nav("Edit HUD Layout", null, "edit", true),
                    new Nav("Profiles", Page.PROFILES, "profiles", false),
                    new Nav("Settings", Page.SETTINGS, "settings", false))));

    private static Page page = Page.ALL;
    private static Module selected;
    private boolean enabledOnly;

    private String search = "";
    private boolean searchFocused;
    private double scroll, scrollTarget;
    private long openedAt;
    private final Map<Object, Float> anim = new HashMap<>();
    /** Milliseconds for animations. Tests swap in a fixed-step clock to record smooth clips for the website. */
    public static java.util.function.LongSupplier clock = System::currentTimeMillis;
    private long lastFrame = clock.getAsLong();
    private float dt;

    /** The module (keybind) or KeySetting waiting for a key press. */
    private Object listening;
    private NumberSetting dragging;
    private int dragX, dragW;

    // layout (recomputed each frame)
    private int wx, wy, ww, wh, cx, cy, cw, ch, searchY, headerY;
    private static final int SIDEBAR = 158, ROW_H = 24, SLIDER_W = 120;
    private static final int LIST_H = 50, CARD_H = 58, COMPACT_H = 22, GAP = 8;
    private static final int NAV_TOP = 58, NAV_HEADER_H = 18, NAV_ITEM_H = 27, NAV_GROUP_GAP = 6;

    public EvadeMenu() {
        super(Text.literal("Evade"));
        selected = null; // opening the menu always starts on the list, not wherever it was last
    }

    /** Test hook: jump the menu straight to the Profiles page. */
    public static void openProfilesForTest() { page = Page.PROFILES; selected = null; }

    /** Open the menu, optionally straight onto a module's settings page. */
    public static EvadeMenu open(Module select) {
        EvadeMenu menu = new EvadeMenu();
        if (select != null) selected = select;
        return menu;
    }

    @Override
    protected void init() {
        openedAt = clock.getAsLong();
    }

    private ClientSettings settings() { return EvadeClient.modules.get(ClientSettings.class); }

    // ------------------------------------------------------------------ rendering

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        settings().applyTheme();
        if (settings().blur.get()) super.renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fillGradient(0, 0, width, height, 0xB0000000, (0xD0000000 | (Theme.lerpColor(0xFF000000, Theme.RED, 0.09f) & 0xFFFFFF)));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        long now = clock.getAsLong();
        dt = Math.max(0f, Math.min(0.1f, (now - lastFrame) / 1000f));
        lastFrame = now;
        scroll += (scrollTarget - scroll) * (1 - Math.exp(-18 * dt));

        ww = Math.min(width - 40, 780);
        wh = Math.min(height - 36, 470);
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;
        cx = wx + SIDEBAR + 18;
        cw = ww - SIDEBAR - 36;
        searchY = wy + 16;
        headerY = searchY + 26 + 12;
        cy = headerY + 30;
        ch = wy + wh - 16 - cy;

        float open = settings().animations.get() ? ease(Math.min(1, (clock.getAsLong() - openedAt) / 220f)) : 1;
        var m = ctx.getMatrices();
        m.pushMatrix();
        float s = 0.95f + 0.05f * open;
        m.translate(width / 2f, height / 2f + (1 - open) * 14);
        m.scale(s, s);
        m.translate(-width / 2f, -height / 2f);

        // window with a soft red glow
        Theme.round(ctx, wx - 3, wy - 3, wx + ww + 3, wy + wh + 3, Theme.alpha(Theme.RED, 0.19f));
        Theme.roundBordered(ctx, wx, wy, wx + ww, wy + wh, Theme.BG, Theme.BORDER);
        renderSidebar(ctx, mouseX, mouseY);
        renderClose(ctx, mouseX, mouseY);

        if (selected != null) renderSettingsPage(ctx, mouseX, mouseY, selected);
        else if (page == Page.SETTINGS) renderSettingsPage(ctx, mouseX, mouseY, settings());
        else if (page == Page.PROFILES) renderProfilesPage(ctx, mouseX, mouseY);
        else {
            renderSearch(ctx, mouseX, mouseY);
            renderHeader(ctx, mouseX, mouseY);
            renderGrid(ctx, mouseX, mouseY);
        }

        m.popMatrix();
    }

    // ---- sidebar ------------------------------------------------------

    private record NavHit(Nav nav, int y) {}

    /** Lay the grouped nav out and return each item with its y, for render + click to share. */
    private List<NavHit> navLayout() {
        List<NavHit> hits = new ArrayList<>();
        int y = wy + NAV_TOP;
        for (NavGroup g : GROUPS) {
            if (g.header != null) y += NAV_HEADER_H;
            for (Nav n : g.items) { hits.add(new NavHit(n, y)); y += NAV_ITEM_H; }
            y += NAV_GROUP_GAP;
        }
        return hits;
    }

    private void renderSidebar(DrawContext ctx, int mx, int my) {
        ctx.fill(wx + 1, wy + 1, wx + SIDEBAR, wy + wh - 1, Theme.SIDEBAR);
        ctx.fill(wx + SIDEBAR, wy + 1, wx + SIDEBAR + 1, wy + wh - 1, Theme.BORDER);

        net.evadeclient.util.Logo.lockup(ctx, wx + 16, wy + 15, 22);
        small(ctx, "C L I E N T", wx + 44, wy + 35, Theme.TEXT_FAINT, 0.65f);
        ctx.fill(wx + 16, wy + 46, wx + SIDEBAR - 16, wy + 47, Theme.DIVIDER);

        int y = wy + NAV_TOP;
        for (NavGroup g : GROUPS) {
            if (g.header != null) {
                small(ctx, spaced(g.header), wx + 18, y + 7, Theme.TEXT_HEADER, 0.78f);
                y += NAV_HEADER_H;
            }
            for (Nav n : g.items) {
                boolean active = !n.hud && n.page == page && selected == null;
                navItem(ctx, n, y, mx, my, active);
                y += NAV_ITEM_H;
            }
            y += NAV_GROUP_GAP;
        }

        String user = client != null && client.getSession() != null ? client.getSession().getUsername() : "";
        ctx.fill(wx + 16, wy + wh - 32, wx + SIDEBAR - 16, wy + wh - 31, Theme.DIVIDER);
        small(ctx, user.isEmpty() ? "Player" : user, wx + 16, wy + wh - 25, Theme.TEXT_DIM, 0.85f);
        small(ctx, "Evade v" + EvadeClient.VERSION, wx + 16, wy + wh - 15, Theme.TEXT_FAINT, 0.78f);
    }

    private void navItem(DrawContext ctx, Nav n, int y, int mx, int my, boolean active) {
        int x1 = wx + 10, x2 = wx + SIDEBAR - 10, top = y + 1, bot = y + NAV_ITEM_H - 3;
        boolean hover = mx >= x1 && mx < x2 && my >= top && my < bot;
        float t = animate("nav:" + n.label, active ? 1 : hover ? 0.4f : 0, 16);

        // Active: accent-tinted fill with a thin accent outline. Hover: a soft highlight.
        if (active) {
            Theme.roundBordered(ctx, x1, top, x2, bot, Theme.tint(0.11f), Theme.lerpColor(Theme.BORDER, Theme.RED, 0.62f));
        } else if (t > 0.01f) {
            Theme.round(ctx, x1, top, x2, bot, Theme.alpha(Theme.CONTROL_HOVER, t));
        }

        // Line icon, tinted: accent when active, dim otherwise, brightening on hover.
        int fg = active ? Theme.RED_BRIGHT : Theme.lerpColor(Theme.TEXT_DIM, Theme.TEXT, t);
        int ix = x1 + 8, iy = y + (NAV_ITEM_H - 2 - ICON) / 2;
        ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, navIcon(n.icon), ix, iy, 0, 0, ICON, ICON, 24, 24, 24, 24, fg);

        int textColor = active ? Theme.RED_BRIGHT : Theme.lerpColor(Theme.TEXT_SOFT, Theme.TEXT, t);
        text(ctx, n.label, ix + ICON + 9, y + (NAV_ITEM_H - 9) / 2, textColor, false);

        // Count pill, right-aligned (category pages only, not Settings), tinted in the accent colour.
        if (n.page != null && n.page.category != null && n.page != Page.SETTINGS) {
            String cnt = String.valueOf(modulesFor(n.page, "").size());
            int cw2 = Math.max(13, Math.round(width(cnt) * 0.8f) + 8);
            int chipX = x2 - 7 - cw2, chipY = y + (NAV_ITEM_H - 2 - 12) / 2;
            Theme.round(ctx, chipX, chipY, chipX + cw2, chipY + 12, Theme.alpha(Theme.RED, active ? 0.3f : 0.14f));
            int tw = Math.round(width(cnt) * 0.8f);
            small(ctx, cnt, chipX + (cw2 - tw) / 2 + 1, chipY + 3,
                    active ? Theme.RED_BRIGHT : Theme.lerpColor(Theme.TEXT_DIM, Theme.RED_BRIGHT, 0.45f), 0.8f);
        }
    }

    private static final int ICON = 12;
    private static final java.util.Map<String, net.minecraft.util.Identifier> NAV_ICONS = new java.util.HashMap<>();

    private static net.minecraft.util.Identifier navIcon(String name) {
        return NAV_ICONS.computeIfAbsent(name, k -> net.minecraft.util.Identifier.of("evade", "textures/gui/nav/" + k + ".png"));
    }

    /** Spread a header's letters so it reads as a small-caps label. */
    private static String spaced(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) { if (i > 0) b.append(' '); b.append(s.charAt(i)); }
        return b.toString();
    }

    private void renderClose(DrawContext ctx, int mx, int my) {
        int bx = wx + ww - 24, by = wy + 12;
        boolean hc = mx >= bx && mx < bx + 14 && my >= by && my < by + 14;
        Theme.round(ctx, bx, by, bx + 14, by + 14, hc ? Theme.RED : Theme.CONTROL);
        icon(ctx, "close", bx + 3, by + 3, 8, hc ? 0xFFFFFFFF : Theme.TEXT_SOFT);
    }

    // ---- content header ----------------------------------------------

    private int searchW() { return cw - 22; }

    private void renderSearch(DrawContext ctx, int mx, int my) {
        int sx = cx, sw = searchW();
        boolean hs = mx >= sx && mx < sx + sw && my >= searchY && my < searchY + 24;
        Theme.roundBordered(ctx, sx, searchY, sx + sw, searchY + 24, Theme.INPUT, searchFocused ? Theme.RED : hs ? Theme.BORDER_HOVER : Theme.BORDER);
        // magnifier
        icon(ctx, "search", sx + 7, searchY + 7, 10, searchFocused ? Theme.RED_BRIGHT : Theme.TEXT_DIM);

        boolean caret = searchFocused && (clock.getAsLong() / 500) % 2 == 0;
        if (search.isEmpty() && !searchFocused) {
            text(ctx, "Search modules", sx + 22, searchY + 8, Theme.TEXT_FAINT, false);
            small(ctx, "Ctrl+F  ·  searches settings too", sx + 22 + width("Search modules") + 14, searchY + 9, Theme.alpha(Theme.TEXT_FAINT, 0.7f), 0.85f);
        } else {
            text(ctx, trim(search, sw - 30) + (caret ? "_" : ""), sx + 22, searchY + 8, Theme.TEXT, false);
        }
    }

    private void renderHeader(DrawContext ctx, int mx, int my) {
        // title + module count
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(cx, headerY);
        m.scale(1.45f, 1.45f);
        text(ctx, page.label, 0, 0, Theme.TEXT, false);
        m.popMatrix();
        int tw = Math.round(width(page.label) * 1.45f);
        int count = modulesFor(page, "").size();
        small(ctx, count + (count == 1 ? " module" : " modules"), cx + tw + 10, headerY + 6, Theme.TEXT_DIM, 0.9f);

        // All / Enabled segmented toggle, right-aligned
        int segW = 150, segX = cx + cw - segW, segY = headerY - 3, half = segW / 2;
        Theme.roundBordered(ctx, segX, segY, segX + segW, segY + 22, Theme.INPUT, Theme.BORDER);
        float sel = animate("seg", enabledOnly ? 1 : 0, 18);
        int knobX = segX + 2 + Math.round((half - 2) * sel);
        Theme.round(ctx, knobX, segY + 2, knobX + half - 2, segY + 20, Theme.alpha(Theme.RED, 0.9f));
        drawCentered(ctx, "All", segX, segX + half, segY + 7, enabledOnly ? Theme.TEXT_DIM : Theme.TEXT);
        drawCentered(ctx, "Enabled", segX + half, segX + segW, segY + 7, enabledOnly ? Theme.TEXT : Theme.TEXT_DIM);
    }

    private void drawCentered(DrawContext ctx, String s, int x1, int x2, int y, int color) {
        text(ctx, s, x1 + (x2 - x1 - width(s)) / 2, y, color, false);
    }

    // ---- profiles page ------------------------------------------------

    private String profileInput = "";
    private boolean profileFocused;
    private static final int PROFILE_H = 44;

    private int createButtonX() { return cx + cw - 96; }
    private int importButtonX() { return createButtonX() - 78; }

    private void renderProfilesPage(DrawContext ctx, int mx, int my) {
        var profiles = EvadeClient.profiles;
        if (profiles == null) return;

        // new-profile input + Create button
        int inW = cw - 104 - 78;
        boolean hin = mx >= cx && mx < cx + inW && my >= searchY && my < searchY + 24;
        Theme.roundBordered(ctx, cx, searchY, cx + inW, searchY + 24, Theme.INPUT, profileFocused ? Theme.RED : hin ? Theme.BORDER_HOVER : Theme.BORDER);
        boolean caret = profileFocused && (clock.getAsLong() / 500) % 2 == 0;
        if (profileInput.isEmpty() && !profileFocused) {
            text(ctx, "New profile name…", cx + 8, searchY + 8, Theme.TEXT_FAINT, false);
        } else {
            text(ctx, trim(profileInput, inW - 14) + (caret ? "_" : ""), cx + 8, searchY + 8, Theme.TEXT, false);
        }
        int bx = createButtonX();
        boolean hc = mx >= bx && mx < cx + cw && my >= searchY && my < searchY + 24;
        Theme.round(ctx, bx, searchY, cx + cw, searchY + 24, hc ? Theme.RED : Theme.RED_DARK);
        drawCentered(ctx, "Create", bx, cx + cw, searchY + 8, Theme.TEXT);
        int ix = importButtonX();
        boolean hi = mx >= ix && mx < ix + 70 && my >= searchY && my < searchY + 24;
        Theme.roundBordered(ctx, ix, searchY, ix + 70, searchY + 24, hi ? Theme.CONTROL_HOVER : Theme.CONTROL, hi ? Theme.RED : Theme.BORDER);
        drawCentered(ctx, "Import", ix, ix + 70, searchY + 8, Theme.TEXT);

        // header
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(cx, headerY);
        m.scale(1.45f, 1.45f);
        text(ctx, "Profiles", 0, 0, Theme.TEXT, false);
        m.popMatrix();
        List<String> list = profiles.list();
        String active = profiles.active();
        int tw = Math.round(width("Profiles") * 1.45f);
        small(ctx, list.size() + (list.size() == 1 ? " profile" : " profiles"), cx + tw + 10, headerY + 6, Theme.TEXT_DIM, 0.9f);

        int contentH = list.size() * (PROFILE_H + GAP);
        scrollTarget = Math.max(0, Math.min(scrollTarget, Math.max(0, contentH - ch)));
        ctx.enableScissor(cx - 2, cy, cx + cw + 2, cy + ch);
        for (int i = 0; i < list.size(); i++) {
            int y = cy + i * (PROFILE_H + GAP) - (int) Math.round(scroll);
            if (y + PROFILE_H < cy || y > cy + ch) continue;
            renderProfileRow(ctx, list.get(i), list.get(i).equals(active), list.size(), y, mx, my);
        }
        ctx.disableScissor();

        small(ctx, "Each profile remembers its mods, settings, keybinds and HUD layout. Copy a code to share one; Import adds a copied code.",
                cx, cy + ch + 2, Theme.TEXT_FAINT, 0.8f);
    }

    private record ProfileButton(String id, String label, int x1, int x2) {}

    /** The buttons on a profile row, right to left: Use (or the ACTIVE chip), delete, copy code, link to this server. */
    private List<ProfileButton> profileButtons(String name, boolean active, int total) {
        List<ProfileButton> out = new ArrayList<>();
        int right = cx + cw - 12;
        if (!active) {
            out.add(new ProfileButton("use", "Use", right - 46, right)); right -= 46 + 6;
            if (total > 1) { out.add(new ProfileButton("delete", "×", right - 18, right)); right -= 18 + 6; }
        } else {
            right -= Math.round(width("ACTIVE") * 0.8f) + 12 + 6;
        }
        out.add(new ProfileButton("copy", "Copy code", right - 64, right)); right -= 64 + 6;
        String srv = currentServer();
        if (srv != null) {
            boolean linked = EvadeClient.profiles.serversFor(name).contains(srv.toLowerCase(Locale.ROOT));
            String label = linked ? "Linked here" : "Use on this server";
            int w = width(label) * 85 / 100 + 14;
            out.add(new ProfileButton("server", label, right - w, right));
        }
        return out;
    }

    private String currentServer() {
        if (client == null || client.isInSingleplayer() || client.getCurrentServerEntry() == null) return null;
        return client.getCurrentServerEntry().address;
    }

    private void renderProfileRow(DrawContext ctx, String name, boolean active, int total, int y, int mx, int my) {
        boolean inView = my >= cy && my < cy + ch;
        boolean hover = inView && mx >= cx && mx < cx + cw && my >= y && my < y + PROFILE_H;
        float h = animate("ph:" + name, hover ? 1 : 0, 14);
        int fill = Theme.lerpColor(Theme.CARD, Theme.CARD_HOVER, h);
        int border = active ? Theme.RED : Theme.lerpColor(Theme.BORDER, Theme.RED, h * 0.6f);
        Theme.roundBordered(ctx, cx, y, cx + cw, y + PROFILE_H, active ? Theme.tint(0.09f) : fill, border);
        if (active) Theme.round(ctx, cx + 1, y + 6, cx + 3, y + PROFILE_H - 6, Theme.RED);

        List<String> servers = EvadeClient.profiles.serversFor(name);
        if (servers.isEmpty()) {
            text(ctx, name, cx + 16, y + (PROFILE_H - 8) / 2, Theme.TEXT, false);
        } else {
            text(ctx, name, cx + 16, y + 11, Theme.TEXT, false);
            small(ctx, "Auto on " + String.join(", ", servers), cx + 16, y + 25, Theme.TEXT_DIM, 0.8f);
        }

        int by = y + (PROFILE_H - 18) / 2;
        if (active) {
            int right = cx + cw - 12, w = Math.round(width("ACTIVE") * 0.8f) + 12, chipY = y + (PROFILE_H - 15) / 2;
            Theme.round(ctx, right - w, chipY, right, chipY + 15, Theme.alpha(Theme.RED, 0.3f));
            small(ctx, "ACTIVE", right - w + 6, chipY + 4, Theme.RED_BRIGHT, 0.8f);
        }
        for (ProfileButton b : profileButtons(name, active, total)) {
            boolean hb = inView && mx >= b.x1() && mx < b.x2() && my >= by && my < by + 18;
            boolean primary = b.id().equals("use");
            boolean linked = b.id().equals("server") && b.label().startsWith("Linked");
            int bg = hb ? (b.id().equals("delete") ? Theme.tint(0.17f) : primary ? Theme.RED : Theme.CONTROL_HOVER)
                    : linked ? Theme.alpha(Theme.RED, 0.22f) : Theme.CONTROL;
            Theme.roundBordered(ctx, b.x1(), by, b.x2(), by + 18, bg, hb || linked ? Theme.RED : Theme.BORDER);
            if (b.id().equals("use")) drawCentered(ctx, b.label(), b.x1(), b.x2(), by + 5, Theme.TEXT);
            else if (b.id().equals("delete")) text(ctx, "×", b.x1() + 6, by + 5, hb ? Theme.RED_BRIGHT : Theme.TEXT_DIM, false);
            else {
                int tw = Math.round(width(b.label()) * 0.85f);
                small(ctx, b.label(), b.x1() + (b.x2() - b.x1() - tw) / 2, by + 6, linked ? Theme.RED_BRIGHT : hb ? Theme.TEXT : Theme.TEXT_SOFT, 0.85f);
            }
        }
    }

    private boolean clickProfiles(double mx, double my) {
        var profiles = EvadeClient.profiles;
        if (profiles == null) return true;
        // input box / create button
        int inW = cw - 104 - 78;
        profileFocused = mx >= cx && mx < cx + inW && my >= searchY && my < searchY + 24;
        if (profileFocused) return true;
        if (mx >= importButtonX() && mx < importButtonX() + 70 && my >= searchY && my < searchY + 24) {
            String imported = profiles.importCode(client.keyboard.getClipboard());
            if (imported != null) net.evadeclient.util.Notifications.push("Profile imported", imported + " is ready to use", true);
            else net.evadeclient.util.Notifications.push("Nothing to import", "Copy an Evade profile code first", false);
            return true;
        }
        if (mx >= createButtonX() && mx < cx + cw && my >= searchY && my < searchY + 24) {
            String name = ProfileManagerName(profileInput);
            if (profiles.create(name)) { profileInput = ""; profileFocused = false; }
            return true;
        }
        List<String> list = profiles.list();
        String active = profiles.active();
        for (int i = 0; i < list.size(); i++) {
            int y = cy + i * (PROFILE_H + GAP) - (int) Math.round(scroll);
            if (my < y || my >= y + PROFILE_H || my < cy || my > cy + ch) continue;
            String name = list.get(i);
            int by = y + (PROFILE_H - 18) / 2;
            if (my < by || my >= by + 18) return true;
            for (ProfileButton b : profileButtons(name, name.equals(active), list.size())) {
                if (mx < b.x1() || mx >= b.x2()) continue;
                switch (b.id()) {
                    case "use" -> profiles.switchTo(name);
                    case "delete" -> profiles.delete(name);
                    case "copy" -> {
                        String code = profiles.shareCode(name);
                        if (code != null) {
                            client.keyboard.setClipboard(code);
                            net.evadeclient.util.Notifications.push("Code copied", "Share it; anyone can paste it into Import", true);
                        }
                    }
                    case "server" -> {
                        boolean on = profiles.toggleServer(name, currentServer());
                        net.evadeclient.util.Notifications.push("Profile", on ? name + " will load on this server" : "Unlinked from this server", true);
                    }
                }
                return true;
            }
            return true;
        }
        return true;
    }

    /** Fall back to an auto name ("Profile N") when the input is blank. */
    private String ProfileManagerName(String typed) {
        String clean = net.evadeclient.config.ProfileManager.sanitize(typed);
        if (!clean.isEmpty()) return clean;
        var profiles = EvadeClient.profiles;
        int n = profiles.list().size() + 1;
        while (profiles.exists("Profile " + n)) n++;
        return "Profile " + n;
    }

    // ---- module list --------------------------------------------------

    private List<Module> modulesFor(Page p, String filter) {
        List<Module> out = new ArrayList<>();
        String f = filter.toLowerCase(Locale.ROOT);
        for (Module mod : EvadeClient.modules.all()) {
            if (mod.getCategory() == Category.CLIENT) continue;
            if (p.category != null && mod.getCategory() != p.category) continue;
            if (!f.isEmpty() && !matches(mod, f)) continue;
            out.add(mod);
        }
        return out;
    }

    /** Search matches a mod's name, description or any of its settings' names (all words must match). */
    private static boolean matches(Module mod, String f) {
        StringBuilder hay = new StringBuilder(mod.getName()).append(' ').append(mod.getDescription()).append(' ')
                .append(mod.getCategory().displayName);
        for (Setting<?> st : mod.getSettings()) hay.append(' ').append(st.getName());
        String h = hay.toString().toLowerCase(Locale.ROOT);
        for (String word : f.trim().split("\\s+")) if (!h.contains(word)) return false;
        return true;
    }

    /** Modules to show, after the search box and the All/Enabled filter. */
    private List<Module> visibleModules() {
        List<Module> list = modulesFor(page, search);
        if (enabledOnly) list.removeIf(mod -> !mod.isEnabled());
        return list;
    }

    private String style() { return settings().guiStyle.get(); }
    private boolean cards() { return style().equals("Cards"); }
    private boolean compact() { return style().equals("Compact"); }
    private int columns() { return cards() ? (cw >= 430 ? 3 : 2) : compact() ? (cw >= 380 ? 2 : 1) : 1; }
    private int rowH() { return cards() ? CARD_H : compact() ? COMPACT_H : LIST_H; }
    private int rowGap() { return compact() ? 4 : GAP; }

    private void renderGrid(DrawContext ctx, int mx, int my) {
        List<Module> mods = visibleModules();
        int cols = columns();
        int rh = rowH(), rg = rowGap();
        int cardW = (cw - rg * (cols - 1)) / cols;
        int rows = (mods.size() + cols - 1) / cols;
        int contentH = rows * (rh + rg);
        scrollTarget = Math.max(0, Math.min(scrollTarget, Math.max(0, contentH - ch)));

        ctx.enableScissor(cx - 2, cy, cx + cw + 2, cy + ch);
        for (int i = 0; i < mods.size(); i++) {
            Module mod = mods.get(i);
            int x = cx + (i % cols) * (cardW + rg);
            int y = cy + (i / cols) * (rh + rg) - (int) Math.round(scroll);
            if (y + rh < cy || y > cy + ch) continue;
            if (cards()) renderCard(ctx, mod, x, y, cardW, mx, my);
            else if (compact()) renderCompact(ctx, mod, x, y, cardW, mx, my);
            else renderListRow(ctx, mod, x, y, cardW, mx, my);
        }
        if (mods.isEmpty()) {
            String msg = search.isEmpty() ? "Nothing here yet" : "No modules match \"" + search + "\"";
            text(ctx, msg, cx + (cw - width(msg)) / 2, cy + 30, Theme.TEXT_DIM, false);
        }
        ctx.disableScissor();

        if (contentH > ch) {
            int barH = Math.max(24, ch * ch / contentH);
            int barY = cy + (int) ((ch - barH) * (scroll / (contentH - ch)));
            ctx.fill(cx + cw + 8, cy, cx + cw + 10, cy + ch, Theme.DIVIDER);
            Theme.round(ctx, cx + cw + 8, barY, cx + cw + 10, barY + barH, Theme.RED);
        }
    }

    /** The premium default: a full-width row with name, description, keybind chip, gear and toggle. */
    private void renderListRow(DrawContext ctx, Module mod, int x, int y, int w, int mx, int my) {
        boolean inView = my >= cy && my < cy + ch;
        boolean hover = inView && mx >= x && mx < x + w && my >= y && my < y + LIST_H;
        float h = animate("hover:" + mod.getName(), hover ? 1 : 0, 14);
        float on = animate("on:" + mod.getName(), mod.isEnabled() ? 1 : 0, 14);

        int fill = Theme.lerpColor(Theme.lerpColor(Theme.CARD, Theme.CARD_HOVER, h), Theme.tint(0.09f), on * 0.55f);
        int border = Theme.lerpColor(Theme.lerpColor(Theme.BORDER, Theme.tint(0.25f), on), Theme.RED, h * 0.7f);
        Theme.roundBordered(ctx, x, y, x + w, y + LIST_H, fill, border);
        if (on > 0.01f) Theme.round(ctx, x + 1, y + 6, x + 3, y + LIST_H - 6, Theme.alpha(Theme.RED, on));

        int tx = x + 16;
        text(ctx, trim(mod.getName(), w - 150), tx, y + 11, Theme.TEXT, false);
        small(ctx, trimScaled(mod.getDescription(), (int) ((w - 150) / 0.78f), 0.78f), tx, y + 26, Theme.TEXT_DIM, 0.78f);

        int right = x + w - 12;
        // toggle (far right)
        Theme.toggle(ctx, right - 20, y + (LIST_H - 10) / 2, on);
        right -= 34;
        // gear
        if (!mod.getSettings().isEmpty()) {
            int gy = y + (LIST_H - 16) / 2;
            boolean hg = inView && mx >= right - 16 && mx < right + 2 && my >= gy - 1 && my < gy + 17;
            Theme.roundBordered(ctx, right - 16, gy, right + 2, gy + 16, hg ? Theme.tint(0.12f) : Theme.CONTROL, hg ? Theme.RED : Theme.BORDER);
            icon(ctx, "settings", right - 13, gy + 3, 10, hg ? Theme.RED_BRIGHT : Theme.TEXT_DIM);
            right -= 24;
        }
        // keybind chip
        if (mod.getKey() != GLFW.GLFW_KEY_UNKNOWN) {
            String k = KeySetting.keyName(mod.getKey());
            int kw = Math.max(34, width(k) + 12);
            int ky = y + (LIST_H - 16) / 2;
            Theme.roundBordered(ctx, right - kw, ky, right, ky + 16, Theme.CONTROL, Theme.BORDER);
            drawCentered(ctx, k, right - kw, right, ky + 4, Theme.TEXT_DIM);
        }
    }

    private void renderCard(DrawContext ctx, Module mod, int x, int y, int w, int mx, int my) {
        boolean inView = my >= cy && my < cy + ch;
        boolean hover = inView && mx >= x && mx < x + w && my >= y && my < y + CARD_H;
        float h = animate("hover:" + mod.getName(), hover ? 1 : 0, 14);
        float on = animate("on:" + mod.getName(), mod.isEnabled() ? 1 : 0, 14);

        int border = Theme.lerpColor(Theme.lerpColor(Theme.BORDER, Theme.tint(0.32f), on), Theme.RED, h * 0.8f);
        Theme.roundBordered(ctx, x, y, x + w, y + CARD_H, Theme.lerpColor(Theme.CARD, Theme.CARD_HOVER, h), border);
        if (on > 0.01f) ctx.fill(x + 1, y + 3, x + 2, y + CARD_H - 3, Theme.alpha(Theme.RED, on));

        int catColor = Theme.lerpColor(Theme.TEXT_FAINT, Theme.RED_BRIGHT, on);
        icon(ctx, categoryIcon(mod.getCategory()), x + 8, y + 5, 7, catColor);
        small(ctx, mod.getCategory().displayName.toUpperCase(Locale.ROOT), x + 18, y + 7, catColor, 0.62f);
        if (mod.getKey() != GLFW.GLFW_KEY_UNKNOWN) {
            String k = KeySetting.keyName(mod.getKey());
            int kw = Math.round(width(k) * 0.65f);
            Theme.round(ctx, x + w - kw - 12, y + 5, x + w - 5, y + 14, Theme.CONTROL_HOVER);
            small(ctx, k, x + w - kw - 9, y + 7, Theme.TEXT_DIM, 0.65f);
        }
        text(ctx, trim(mod.getName(), w - 14), x + 8, y + 16, Theme.TEXT, false);
        small(ctx, trimScaled(mod.getDescription(), w - 14, 0.7f), x + 8, y + 29, Theme.TEXT_DIM, 0.7f);

        Theme.toggle(ctx, x + 8, y + CARD_H - 15, on);
        if (!mod.getSettings().isEmpty()) {
            int gx = x + w - 16, gy = y + CARD_H - 16;
            boolean hg = inView && mx >= gx - 3 && mx < gx + 12 && my >= gy - 2 && my < gy + 12;
            Theme.round(ctx, gx - 3, gy - 2, gx + 12, gy + 12, hg ? Theme.RED : Theme.CONTROL);
            icon(ctx, "settings", gx, gy, 9, hg ? 0xFFFFFFFF : Theme.TEXT_SOFT);
        }
    }

    private void renderCompact(DrawContext ctx, Module mod, int x, int y, int w, int mx, int my) {
        boolean inView = my >= cy && my < cy + ch;
        boolean hover = inView && mx >= x && mx < x + w && my >= y && my < y + COMPACT_H;
        float hv = animate("hover:" + mod.getName(), hover ? 1 : 0, 14);
        float on = animate("on:" + mod.getName(), mod.isEnabled() ? 1 : 0, 14);

        int fill = Theme.lerpColor(Theme.lerpColor(Theme.CARD, Theme.CARD_HOVER, hv), Theme.tint(0.1f), on * 0.7f);
        int border = Theme.lerpColor(Theme.BORDER, Theme.RED, hv * 0.7f);
        Theme.roundBordered(ctx, x, y, x + w, y + COMPACT_H, fill, border);
        if (on > 0.01f) ctx.fill(x + 1, y + 2, x + 2, y + COMPACT_H - 2, Theme.alpha(Theme.RED, on));
        text(ctx, trim(mod.getName(), w - 46), x + 8, y + (COMPACT_H - 8) / 2, Theme.TEXT, false);

        Theme.toggle(ctx, x + w - 24, y + (COMPACT_H - 10) / 2, on);
        if (!mod.getSettings().isEmpty()) {
            int gx = x + w - 40, gy = y + (COMPACT_H - 10) / 2;
            boolean hg = inView && mx >= gx - 2 && mx < gx + 11 && my >= gy - 1 && my < gy + 11;
            icon(ctx, "settings", gx, gy, 10, hg ? Theme.RED_BRIGHT : Theme.TEXT_DIM);
        }
    }

    // ---- settings page ------------------------------------------------

    private List<Object> rowsFor(Module mod) {
        List<Object> rows = new ArrayList<>();
        if (mod.getCategory() != Category.CLIENT) rows.add("keybind");
        rows.addAll(mod.getSettings());
        return rows;
    }

    private int settingsTop() { return cy; }

    private void renderSettingsPage(DrawContext ctx, int mx, int my, Module mod) {
        TextRenderer tr = textRenderer;
        // header: Back + title + description + master toggle
        if (selected != null) {
            boolean hb = mx >= cx && mx < cx + 52 && my >= searchY && my < searchY + 18;
            Theme.roundBordered(ctx, cx, searchY, cx + 52, searchY + 18, hb ? Theme.RED : Theme.CONTROL, hb ? Theme.RED : Theme.BORDER);
            text(ctx, "‹ Back", cx + 8, searchY + 5, Theme.TEXT, false);
        }
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(cx, headerY);
        m.scale(1.45f, 1.45f);
        text(ctx, mod.getName(), 0, 0, Theme.TEXT, false);
        m.popMatrix();
        small(ctx, trimScaled(mod.getDescription(), (int) (cw / 0.85f), 0.85f), cx, headerY + 16, Theme.TEXT_DIM, 0.85f);
        if (selected != null) Theme.toggle(ctx, cx + cw - 22, headerY, animate("on:" + mod.getName(), mod.isEnabled() ? 1 : 0, 14));
        if (hasChangedSettings(mod)) {
            int rx = resetX(), rw = 52;
            boolean hr = mx >= rx && mx < rx + rw && my >= headerY - 4 && my < headerY + 12;
            Theme.roundBordered(ctx, rx, headerY - 4, rx + rw, headerY + 12, hr ? Theme.CONTROL_HOVER : Theme.CONTROL, hr ? Theme.RED : Theme.BORDER);
            icon(ctx, "reset", rx + 6, headerY - 1, 9, hr ? Theme.RED_BRIGHT : Theme.TEXT_DIM);
            small(ctx, "Reset", rx + 19, headerY, hr ? Theme.TEXT : Theme.TEXT_SOFT, 0.85f);
        }

        int top = settingsTop();
        List<Object> rows = rowsFor(mod);
        int contentH = rows.size() * ROW_H;
        int viewH = cy + ch - top;
        scrollTarget = Math.max(0, Math.min(scrollTarget, Math.max(0, contentH - viewH)));

        ctx.enableScissor(cx - 2, top, cx + cw + 2, cy + ch);
        int y = top - (int) Math.round(scroll);
        for (Object row : rows) {
            boolean hover = mx >= cx && mx < cx + cw && my >= y && my < y + ROW_H - 2 && my >= top;
            Theme.round(ctx, cx, y, cx + cw, y + ROW_H - 3, hover ? Theme.ROW_HOVER : Theme.ROW);
            String label = row instanceof Setting<?> st ? st.getName() : "Toggle Key";
            text(ctx, label, cx + 10, y + 7, Theme.TEXT, false);
            renderControl(ctx, row, mod, cx + cw - 10, y);
            y += ROW_H;
        }
        ctx.disableScissor();
    }

    /** Draws the control for a settings row, right-aligned at {@code right}. */
    private void renderControl(DrawContext ctx, Object row, Module mod, int right, int y) {
        TextRenderer tr = textRenderer;
        int midY = y + (ROW_H - 3) / 2;
        boolean isKeybind = row.equals("keybind");
        if (isKeybind || row instanceof KeySetting) {
            boolean l = isKeybind ? listening == mod : listening == row;
            String text = l ? "Press a key..." : isKeybind ? KeySetting.keyName(mod.getKey()) : ((KeySetting) row).display();
            int w = Math.max(50, width(text) + 14);
            Theme.roundBordered(ctx, right - w, midY - 7, right, midY + 7, Theme.CONTROL, l ? Theme.RED : Theme.BORDER);
            text(ctx, text, right - w + (w - width(text)) / 2, midY - 4, l ? Theme.RED_BRIGHT : Theme.TEXT, false);
        } else if (row instanceof BooleanSetting b) {
            Theme.toggle(ctx, right - 20, midY - 5, animate(b, b.get() ? 1 : 0, 14));
        } else if (row instanceof NumberSetting n) {
            int sx = right - SLIDER_W;
            float pct = (float) ((n.get() - n.getMin()) / (n.getMax() - n.getMin()));
            float shown = animate(n, pct, 20);
            Theme.round(ctx, sx, midY - 2, sx + SLIDER_W, midY + 2, Theme.TRACK);
            Theme.round(ctx, sx, midY - 2, sx + Math.max(4, Math.round(SLIDER_W * shown)), midY + 2, Theme.RED);
            int kx = sx + Math.round(SLIDER_W * shown);
            Theme.round(ctx, kx - 4, midY - 4, kx + 4, midY + 4, 0xFFFFFFFF);
            String v = n.display();
            text(ctx, v, sx - 8 - width(v), midY - 4, Theme.TEXT_DIM, false);
        } else if (row instanceof ModeSetting md) {
            String v = md.get();
            int w = Math.max(70, width(v) + 30);
            Theme.roundBordered(ctx, right - w, midY - 7, right, midY + 7, Theme.CONTROL, Theme.BORDER);
            text(ctx, "‹", right - w + 5, midY - 4, Theme.RED, false);
            text(ctx, "›", right - 9, midY - 4, Theme.RED, false);
            text(ctx, v, right - w + (w - width(v)) / 2, midY - 4, Theme.TEXT, false);
        } else if (row instanceof net.evadeclient.setting.StringSetting st) {
            String v = st.get();
            int w = Math.max(60, width(v) + 16);
            Theme.roundBordered(ctx, right - w, midY - 7, right, midY + 7, Theme.CONTROL, Theme.BORDER);
            text(ctx, v, right - w + (w - width(v)) / 2, midY - 4, Theme.TEXT, false);
            int hw = Math.round(width(st.hint()) * 0.75f);
            small(ctx, st.hint(), right - w - 8 - hw, midY - 3, Theme.TEXT_FAINT, 0.75f);
        } else if (row instanceof ColorSetting c) {
            int n = ColorSetting.PALETTE.length, sz = 9, gap = 3;
            int sx = right - n * (sz + gap) + gap;
            for (int i = 0; i < n; i++) {
                int col = ColorSetting.PALETTE[i];
                int x = sx + i * (sz + gap);
                if ((c.get() | 0xFF000000) == col) Theme.round(ctx, x - 2, midY - 6, x + sz + 2, midY + 6, 0xFFFFFFFF);
                ctx.fill(x, midY - 4, x + sz, midY + 4, col);
            }
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        int button = click.button();
        if (listening != null) { listening = null; return true; }

        // close button
        if (mx >= wx + ww - 24 && mx < wx + ww - 10 && my >= wy + 12 && my < wy + 26) { close(); return true; }

        // sidebar
        if (mx >= wx && mx < wx + SIDEBAR && my >= wy && my < wy + wh) {
            for (NavHit hit : navLayout()) {
                if (my >= hit.y && my < hit.y + NAV_ITEM_H) {
                    if (hit.nav.hud) client.setScreen(new HudEditorScreen(this));
                    else go(hit.nav.page);
                    return true;
                }
            }
            return true;
        }

        if (page == Page.PROFILES && selected == null) return clickProfiles(mx, my);

        if (selected != null || page == Page.SETTINGS) {
            // back button / master toggle on settings header
            if (selected != null && my >= searchY && my < searchY + 18 && mx >= cx && mx < cx + 52) { back(); return true; }
            if (selected != null && my >= headerY - 4 && my < headerY + 14 && mx >= cx + cw - 24) { selected.toggle(); return true; }
            Module rm = selected != null ? selected : settings();
            if (hasChangedSettings(rm) && my >= headerY - 4 && my < headerY + 12 && mx >= resetX() && mx < resetX() + 52) {
                for (Setting<?> st : rm.getSettings()) st.reset();
                return true;
            }
            return clickSettings(mx, my, button);
        }

        // search box
        int sw = searchW();
        searchFocused = mx >= cx && mx < cx + sw && my >= searchY && my < searchY + 24;
        if (searchFocused) return true;

        // All / Enabled toggle
        int segW = 150, segX = cx + cw - segW, segY = headerY - 3;
        if (my >= segY && my < segY + 22 && mx >= segX && mx < segX + segW) {
            enabledOnly = mx >= segX + segW / 2;
            scrollTarget = 0;
            return true;
        }

        // module list
        if (my < cy || my > cy + ch || mx < cx || mx > cx + cw) return super.mouseClicked(click, doubled);
        List<Module> mods = visibleModules();
        int cols = columns();
        int rh = rowH(), rg = rowGap();
        int cardW = (cw - rg * (cols - 1)) / cols;
        for (int i = 0; i < mods.size(); i++) {
            int x = cx + (i % cols) * (cardW + rg);
            int y = cy + (i / cols) * (rh + rg) - (int) Math.round(scroll);
            if (mx < x || mx >= x + cardW || my < y || my >= y + rh) continue;
            Module mod = mods.get(i);
            if ((!mod.getSettings().isEmpty() && gearHit(mx, my, x, y, cardW)) || button == 1) {
                selected = mod;
                scroll = scrollTarget = 0;
            } else if (button == 0) {
                mod.toggle();
            }
            return true;
        }
        return true;
    }

    /** Whether the click landed on a card/row's gear button. */
    private boolean gearHit(double mx, double my, int x, int y, int w) {
        if (cards()) return mx >= x + w - 19 && my >= y + CARD_H - 18;
        if (compact()) return mx >= x + w - 42 && mx < x + w - 26;
        // list style: gear sits just left of the toggle (drawn at x+w-62 .. x+w-44)
        return mx >= x + w - 62 && mx < x + w - 44;
    }

    private boolean clickSettings(double mx, double my, int button) {
        Module mod = selected != null ? selected : settings();
        int top = settingsTop();
        if (my < top) return true;
        int y = top - (int) Math.round(scroll);
        int right = cx + cw - 10;
        for (Object row : rowsFor(mod)) {
            if (my >= y && my < y + ROW_H - 2) {
                if (row.equals("keybind")) {
                    if (button == 1) mod.setKey(GLFW.GLFW_KEY_UNKNOWN); else listening = mod;
                } else if (row instanceof KeySetting k) {
                    if (button == 1) k.set(GLFW.GLFW_KEY_UNKNOWN); else listening = k;
                } else if (row instanceof net.evadeclient.setting.StringSetting st && st.clickAction() != null) {
                    st.clickAction().run();
                } else if (row instanceof BooleanSetting b) {
                    b.toggle();
                } else if (row instanceof NumberSetting n) {
                    dragging = n;
                    dragX = right - SLIDER_W;
                    dragW = SLIDER_W;
                    updateSlider(mx);
                } else if (row instanceof ModeSetting md) {
                    int w = Math.max(70, width(md.get()) + 30);
                    md.cycle(button != 1 && mx >= right - w / 2.0);
                } else if (row instanceof ColorSetting c) {
                    int n = ColorSetting.PALETTE.length, sz = 9, gap = 3;
                    int sx = right - n * (sz + gap) + gap;
                    int i = (int) Math.floor((mx - sx) / (sz + gap));
                    if (i >= 0 && i < n) c.set(ColorSetting.PALETTE[i]);
                }
                return true;
            }
            y += ROW_H;
        }
        return true;
    }

    private void updateSlider(double mx) {
        double pct = Math.max(0, Math.min(1, (mx - dragX) / dragW));
        dragging.set(dragging.getMin() + pct * (dragging.getMax() - dragging.getMin()));
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (dragging != null) { updateSlider(click.x()); return true; }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        dragging = null;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scrollTarget = Math.max(0, scrollTarget - v * 32);
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (listening != null) {
            boolean clear = key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE;
            int value = clear ? GLFW.GLFW_KEY_UNKNOWN : key;
            if (listening instanceof Module m) m.setKey(value);
            else if (listening instanceof KeySetting k) k.set(value);
            listening = null;
            return true;
        }
        if (key == GLFW.GLFW_KEY_F && (input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0) {
            // Ctrl+F: jump to the search box from anywhere in the menu.
            if (selected != null) back();
            if (page == Page.SETTINGS || page == Page.PROFILES) page = Page.ALL;
            searchFocused = true;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (selected != null) { back(); return true; }
            if (!search.isEmpty()) { search = ""; searchFocused = false; return true; }
            close();
            return true;
        }
        if (page == Page.PROFILES && selected == null) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (!profileInput.isEmpty()) profileInput = profileInput.substring(0, profileInput.length() - 1);
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                if (EvadeClient.profiles != null && EvadeClient.profiles.create(ProfileManagerName(profileInput))) profileInput = "";
                return true;
            }
        }
        if (searchFocused && key == GLFW.GLFW_KEY_BACKSPACE) {
            if (!search.isEmpty()) search = search.substring(0, search.length() - 1);
            scrollTarget = 0;
            return true;
        }
        if (!searchFocused && key == settings().menuKey.get()) { close(); return true; }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (selected != null || page == Page.SETTINGS || !input.isValidChar()) return false;
        if (page == Page.PROFILES) {
            profileFocused = true;
            if (profileInput.length() < 24) profileInput += input.asString();
            return true;
        }
        // Typing anywhere starts a search, like a launcher.
        searchFocused = true;
        if (search.length() < 32) search += input.asString();
        scrollTarget = 0;
        return true;
    }

    private void go(Page p) {
        page = p;
        selected = null;
        search = "";
        searchFocused = false;
        profileInput = "";
        profileFocused = false;
        scroll = scrollTarget = 0;
    }

    private void back() {
        selected = null;
        scroll = scrollTarget = 0;
    }

    @Override
    public void close() {
        EvadeClient.config.save();
        super.close();
    }

    @Override
    public boolean shouldPause() { return false; }

    // ------------------------------------------------------------------ helpers

    private float animate(Object key, float target, float speed) {
        float v = anim.getOrDefault(key, target);
        if (!settings().animations.get()) v = target;
        else v += (target - v) * (1 - (float) Math.exp(-speed * dt));
        anim.put(key, v);
        return v;
    }

    private void small(DrawContext ctx, String s, int x, int y, int color, float scale) {
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(x, y);
        m.scale(scale, scale);
        text(ctx, s, 0, 0, color, false);
        m.popMatrix();
    }

    private String trimScaled(String s, int maxW, float scale) {
        int limit = Math.round(maxW / scale);
        if (width(s) <= limit) return s;
        return trim(s, limit - width("...")) + "...";
    }

    private static float ease(float t) { return 1 - (float) Math.pow(1 - t, 3); }

    // ------------------------------------------------------------------ text: Evade's smooth UI font (Inter)

    private static final net.minecraft.text.Style UI_FONT = net.minecraft.text.Style.EMPTY
            .withFont(new net.minecraft.text.StyleSpriteSource.Font(net.minecraft.util.Identifier.of("evade", "ui")));

    /** The string in the menu's font: Inter when Smooth Font is on, otherwise Minecraft's own. */
    private net.minecraft.text.Text ui(String s) {
        var t = net.minecraft.text.Text.literal(s);
        return settings().smoothFont.get() ? t.setStyle(UI_FONT) : t;
    }

    private void text(DrawContext ctx, String s, int x, int y, int color, boolean shadow) {
        ctx.drawText(textRenderer, ui(s), x, y, color, shadow);
    }

    private int width(String s) { return textRenderer.getWidth(ui(s)); }

    /** Longest prefix of {@code s} that fits in {@code max} pixels. */
    private String trim(String s, int max) {
        if (width(s) <= max) return s;
        int lo = 0, hi = s.length();
        while (lo < hi) { int mid = (lo + hi + 1) / 2; if (width(s.substring(0, mid)) <= max) lo = mid; else hi = mid - 1; }
        return s.substring(0, lo);
    }

    // ------------------------------------------------------------------ icons

    /** A white line icon from textures/gui/nav, tinted and drawn at {@code size} GUI pixels. */
    private void icon(DrawContext ctx, String name, int x, int y, int size, int color) {
        ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, navIcon(name), x, y, 0, 0, size, size, 24, 24, 24, 24, color);
    }

    private static String categoryIcon(Category c) {
        return switch (c) {
            case MODS -> "controls";
            case SERVER -> "server";
            case HUD -> "hud";
            case VISUALS -> "visuals";
            case CLIENT -> "settings";
            default -> "misc";
        };
    }

    private int resetX() { return cx + cw - (selected != null ? 84 : 52); }

    private static boolean hasChangedSettings(Module mod) {
        for (Setting<?> st : mod.getSettings()) if (!(st instanceof net.evadeclient.setting.StatusSetting) && !st.isDefault()) return true;
        return false;
    }
}
