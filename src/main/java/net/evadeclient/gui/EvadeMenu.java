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
    private record Nav(String label, Page page, String badge, boolean hud) {}
    private record NavGroup(String header, List<Nav> items) {}

    private static final List<NavGroup> GROUPS = List.of(
            new NavGroup(null, List.of(new Nav("All Mods", Page.ALL, "A", false))),
            new NavGroup("GAMEPLAY", List.of(
                    new Nav("Controls", Page.MODS, "C", false),
                    new Nav("Server-facing", Page.SERVER, "S", false),
                    new Nav("Misc", Page.MISC, "~", false))),
            new NavGroup("INTERFACE", List.of(
                    new Nav("HUD", Page.HUD, "H", false),
                    new Nav("Visuals", Page.VISUALS, "V", false))),
            new NavGroup("GENERAL", List.of(
                    new Nav("Edit HUD Layout", null, "E", true),
                    new Nav("Profiles", Page.PROFILES, "P", false),
                    new Nav("Settings", Page.SETTINGS, "⚙", false))));

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
    private static final int NAV_TOP = 58, NAV_HEADER_H = 18, NAV_ITEM_H = 27, NAV_GROUP_GAP = 6, BADGE = 20;

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
        dt = Math.min(0.1f, (now - lastFrame) / 1000f);
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

        // selection pill / hover highlight
        if (active) {
            Theme.round(ctx, x1, top, x2, bot, Theme.tint(0.14f));
            ctx.fill(x1, top + 3, x1 + 2, bot - 3, Theme.RED);
        } else if (t > 0.01f) {
            Theme.round(ctx, x1, top, x2, bot, Theme.alpha(Theme.CONTROL_HOVER, t));
        }

        // rounded letter badge
        int bx = wx + 16, by = y + (NAV_ITEM_H - BADGE) / 2;
        int badgeFill = active ? Theme.RED : Theme.lerpColor(Theme.CONTROL, Theme.CONTROL_HOVER, t);
        Theme.round(ctx, bx, by, bx + BADGE, by + BADGE, badgeFill);
        if (active) Theme.round(ctx, bx, by, bx + BADGE, by + BADGE, badgeFill); // crisp on red
        int letterColor = active ? 0xFFFFFFFF : Theme.lerpColor(Theme.TEXT_SOFT, Theme.TEXT, t);
        int lw = Math.round(textRenderer.getWidth(n.badge) * 0.95f);
        small(ctx, n.badge, bx + (BADGE - lw) / 2, by + 6, letterColor, 0.95f);

        int textColor = active ? Theme.TEXT : Theme.lerpColor(Theme.TEXT_SOFT, Theme.TEXT, t);
        ctx.drawText(textRenderer, n.label, bx + BADGE + 9, y + (NAV_ITEM_H - 8) / 2, textColor, false);

        // count chip, right-aligned (category pages only, not Settings)
        if (n.page != null && n.page.category != null && n.page != Page.SETTINGS) {
            String cnt = String.valueOf(modulesFor(n.page, "").size());
            int cw2 = Math.round(textRenderer.getWidth(cnt) * 0.8f) + 8;
            int chipX = x2 - 6 - cw2, chipY = y + (NAV_ITEM_H - 13) / 2;
            Theme.round(ctx, chipX, chipY, chipX + cw2, chipY + 13, active ? Theme.alpha(Theme.RED, 0.28f) : Theme.DIVIDER);
            small(ctx, cnt, chipX + 4, chipY + 3, active ? Theme.RED_BRIGHT : Theme.TEXT_DIM, 0.8f);
        }
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
        ctx.drawText(textRenderer, "x", bx + 4, by + 3, Theme.TEXT, false);
    }

    // ---- content header ----------------------------------------------

    private int searchW() { return cw - 22; }

    private void renderSearch(DrawContext ctx, int mx, int my) {
        int sx = cx, sw = searchW();
        boolean hs = mx >= sx && mx < sx + sw && my >= searchY && my < searchY + 24;
        Theme.roundBordered(ctx, sx, searchY, sx + sw, searchY + 24, Theme.INPUT, searchFocused ? Theme.RED : hs ? Theme.BORDER_HOVER : Theme.BORDER);
        // magnifier
        int gx = sx + 10, gy = searchY + 12;
        ctx.fill(gx - 3, gy - 3, gx + 2, gy - 2, Theme.TEXT_DIM);
        ctx.fill(gx - 3, gy + 2, gx + 2, gy + 3, Theme.TEXT_DIM);
        ctx.fill(gx - 4, gy - 2, gx - 3, gy + 2, Theme.TEXT_DIM);
        ctx.fill(gx + 2, gy - 2, gx + 3, gy + 2, Theme.TEXT_DIM);
        ctx.fill(gx + 3, gy + 3, gx + 6, gy + 6, Theme.TEXT_DIM);

        boolean caret = searchFocused && (clock.getAsLong() / 500) % 2 == 0;
        if (search.isEmpty() && !searchFocused) {
            ctx.drawText(textRenderer, "Search modules", sx + 22, searchY + 8, Theme.TEXT_FAINT, false);
            small(ctx, "fav:   recent:", sx + 22 + textRenderer.getWidth("Search modules") + 14, searchY + 9, Theme.alpha(Theme.TEXT_FAINT, 0.7f), 0.85f);
        } else {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(search, sw - 30) + (caret ? "_" : ""), sx + 22, searchY + 8, Theme.TEXT, false);
        }
    }

    private void renderHeader(DrawContext ctx, int mx, int my) {
        // title + module count
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(cx, headerY);
        m.scale(1.45f, 1.45f);
        ctx.drawText(textRenderer, page.label, 0, 0, Theme.TEXT, false);
        m.popMatrix();
        int tw = Math.round(textRenderer.getWidth(page.label) * 1.45f);
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
        ctx.drawText(textRenderer, s, x1 + (x2 - x1 - textRenderer.getWidth(s)) / 2, y, color, false);
    }

    // ---- profiles page ------------------------------------------------

    private String profileInput = "";
    private boolean profileFocused;
    private static final int PROFILE_H = 44;

    private int createButtonX() { return cx + cw - 96; }

    private void renderProfilesPage(DrawContext ctx, int mx, int my) {
        var profiles = EvadeClient.profiles;
        if (profiles == null) return;

        // new-profile input + Create button
        int inW = cw - 104;
        boolean hin = mx >= cx && mx < cx + inW && my >= searchY && my < searchY + 24;
        Theme.roundBordered(ctx, cx, searchY, cx + inW, searchY + 24, Theme.INPUT, profileFocused ? Theme.RED : hin ? Theme.BORDER_HOVER : Theme.BORDER);
        boolean caret = profileFocused && (clock.getAsLong() / 500) % 2 == 0;
        if (profileInput.isEmpty() && !profileFocused) {
            ctx.drawText(textRenderer, "New profile name…", cx + 8, searchY + 8, Theme.TEXT_FAINT, false);
        } else {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(profileInput, inW - 14) + (caret ? "_" : ""), cx + 8, searchY + 8, Theme.TEXT, false);
        }
        int bx = createButtonX();
        boolean hc = mx >= bx && mx < cx + cw && my >= searchY && my < searchY + 24;
        Theme.round(ctx, bx, searchY, cx + cw, searchY + 24, hc ? Theme.RED : Theme.RED_DARK);
        drawCentered(ctx, "Create", bx, cx + cw, searchY + 8, Theme.TEXT);

        // header
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(cx, headerY);
        m.scale(1.45f, 1.45f);
        ctx.drawText(textRenderer, "Profiles", 0, 0, Theme.TEXT, false);
        m.popMatrix();
        List<String> list = profiles.list();
        String active = profiles.active();
        int tw = Math.round(textRenderer.getWidth("Profiles") * 1.45f);
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

        small(ctx, "Each profile remembers its own mods, settings, keybinds and HUD layout.", cx, cy + ch + 2, Theme.TEXT_FAINT, 0.8f);
    }

    private void renderProfileRow(DrawContext ctx, String name, boolean active, int total, int y, int mx, int my) {
        boolean inView = my >= cy && my < cy + ch;
        boolean hover = inView && mx >= cx && mx < cx + cw && my >= y && my < y + PROFILE_H;
        float h = animate("ph:" + name, hover ? 1 : 0, 14);
        int fill = Theme.lerpColor(Theme.CARD, Theme.CARD_HOVER, h);
        int border = active ? Theme.RED : Theme.lerpColor(Theme.BORDER, Theme.RED, h * 0.6f);
        Theme.roundBordered(ctx, cx, y, cx + cw, y + PROFILE_H, active ? Theme.tint(0.09f) : fill, border);
        if (active) Theme.round(ctx, cx + 1, y + 6, cx + 3, y + PROFILE_H - 6, Theme.RED);

        ctx.drawText(textRenderer, name, cx + 16, y + (PROFILE_H - 8) / 2, Theme.TEXT, false);

        int right = cx + cw - 12;
        if (active) {
            int w = Math.round(textRenderer.getWidth("ACTIVE") * 0.8f) + 12, chipY = y + (PROFILE_H - 15) / 2;
            Theme.round(ctx, right - w, chipY, right, chipY + 15, Theme.alpha(Theme.RED, 0.3f));
            small(ctx, "ACTIVE", right - w + 6, chipY + 4, Theme.RED_BRIGHT, 0.8f);
        } else {
            // Use button
            int uw = 46, uy = y + (PROFILE_H - 18) / 2;
            boolean hu = inView && mx >= right - uw && mx < right && my >= uy && my < uy + 18;
            Theme.roundBordered(ctx, right - uw, uy, right, uy + 18, hu ? Theme.RED : Theme.CONTROL, hu ? Theme.RED : Theme.BORDER);
            drawCentered(ctx, "Use", right - uw, right, uy + 5, Theme.TEXT);
            right -= uw + 8;
            // delete button (not when it's the last profile)
            if (total > 1) {
                boolean hd = inView && mx >= right - 18 && mx < right && my >= uy && my < uy + 18;
                Theme.roundBordered(ctx, right - 18, uy, right, uy + 18, hd ? Theme.tint(0.17f) : Theme.CONTROL, hd ? Theme.RED : Theme.BORDER);
                ctx.drawText(textRenderer, "×", right - 12, uy + 5, hd ? Theme.RED_BRIGHT : Theme.TEXT_DIM, false);
            }
        }
    }

    private boolean clickProfiles(double mx, double my) {
        var profiles = EvadeClient.profiles;
        if (profiles == null) return true;
        // input box / create button
        int inW = cw - 104;
        profileFocused = mx >= cx && mx < cx + inW && my >= searchY && my < searchY + 24;
        if (profileFocused) return true;
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
            if (name.equals(active)) return true;
            int right = cx + cw - 12, uw = 46;
            if (mx >= right - uw && mx < right) { profiles.switchTo(name); return true; }
            right -= uw + 8;
            if (list.size() > 1 && mx >= right - 18 && mx < right) { profiles.delete(name); return true; }
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
            if (!f.isEmpty() && !mod.getName().toLowerCase(Locale.ROOT).contains(f)
                    && !mod.getDescription().toLowerCase(Locale.ROOT).contains(f)) continue;
            out.add(mod);
        }
        return out;
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
    private int columns() { return cards() ? (cw >= 430 ? 3 : 2) : 1; }
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
            ctx.drawText(textRenderer, msg, cx + (cw - textRenderer.getWidth(msg)) / 2, cy + 30, Theme.TEXT_DIM, false);
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
        ctx.drawText(textRenderer, textRenderer.trimToWidth(mod.getName(), w - 150), tx, y + 11, Theme.TEXT, false);
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
            small(ctx, "⚙", right - 12, gy + 4, hg ? Theme.RED_BRIGHT : Theme.TEXT_DIM, 0.95f);
            right -= 24;
        }
        // keybind chip
        if (mod.getKey() != GLFW.GLFW_KEY_UNKNOWN) {
            String k = KeySetting.keyName(mod.getKey());
            int kw = Math.max(34, textRenderer.getWidth(k) + 12);
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

        small(ctx, mod.getCategory().displayName.toUpperCase(Locale.ROOT), x + 8, y + 7, Theme.alpha(Theme.RED, 0.55f + 0.45f * on), 0.65f);
        if (mod.getKey() != GLFW.GLFW_KEY_UNKNOWN) {
            String k = KeySetting.keyName(mod.getKey());
            int kw = Math.round(textRenderer.getWidth(k) * 0.65f);
            Theme.round(ctx, x + w - kw - 12, y + 5, x + w - 5, y + 14, Theme.CONTROL_HOVER);
            small(ctx, k, x + w - kw - 9, y + 7, Theme.TEXT_DIM, 0.65f);
        }
        ctx.drawText(textRenderer, textRenderer.trimToWidth(mod.getName(), w - 14), x + 8, y + 16, Theme.TEXT, false);
        small(ctx, trimScaled(mod.getDescription(), w - 14, 0.7f), x + 8, y + 29, Theme.TEXT_DIM, 0.7f);

        Theme.toggle(ctx, x + 8, y + CARD_H - 15, on);
        if (!mod.getSettings().isEmpty()) {
            int gx = x + w - 16, gy = y + CARD_H - 16;
            boolean hg = inView && mx >= gx - 3 && mx < gx + 12 && my >= gy - 2 && my < gy + 12;
            Theme.round(ctx, gx - 3, gy - 2, gx + 12, gy + 12, hg ? Theme.RED : Theme.CONTROL);
            small(ctx, "⚙", gx, gy + 1, Theme.TEXT, 0.95f);
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
        ctx.drawText(textRenderer, textRenderer.trimToWidth(mod.getName(), w - 46), x + 8, y + (COMPACT_H - 8) / 2, Theme.TEXT, false);

        Theme.toggle(ctx, x + w - 24, y + (COMPACT_H - 10) / 2, on);
        if (!mod.getSettings().isEmpty()) {
            int gx = x + w - 40, gy = y + (COMPACT_H - 10) / 2;
            boolean hg = inView && mx >= gx - 2 && mx < gx + 11 && my >= gy - 1 && my < gy + 11;
            small(ctx, "⚙", gx, gy + 1, hg ? Theme.RED_BRIGHT : Theme.TEXT_DIM, 0.9f);
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
            ctx.drawText(tr, "‹ Back", cx + 8, searchY + 5, Theme.TEXT, false);
        }
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(cx, headerY);
        m.scale(1.45f, 1.45f);
        ctx.drawText(tr, mod.getName(), 0, 0, Theme.TEXT, false);
        m.popMatrix();
        small(ctx, trimScaled(mod.getDescription(), (int) (cw / 0.85f), 0.85f), cx, headerY + 16, Theme.TEXT_DIM, 0.85f);
        if (selected != null) Theme.toggle(ctx, cx + cw - 22, headerY, animate("on:" + mod.getName(), mod.isEnabled() ? 1 : 0, 14));

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
            String label = row instanceof Setting<?> st ? st.getName() : "Keybind";
            ctx.drawText(tr, label, cx + 10, y + 7, Theme.TEXT, false);
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
            int w = Math.max(50, tr.getWidth(text) + 14);
            Theme.roundBordered(ctx, right - w, midY - 7, right, midY + 7, Theme.CONTROL, l ? Theme.RED : Theme.BORDER);
            ctx.drawText(tr, text, right - w + (w - tr.getWidth(text)) / 2, midY - 4, l ? Theme.RED_BRIGHT : Theme.TEXT, false);
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
            ctx.drawText(tr, v, sx - 8 - tr.getWidth(v), midY - 4, Theme.TEXT_DIM, false);
        } else if (row instanceof ModeSetting md) {
            String v = md.get();
            int w = Math.max(70, tr.getWidth(v) + 30);
            Theme.roundBordered(ctx, right - w, midY - 7, right, midY + 7, Theme.CONTROL, Theme.BORDER);
            ctx.drawText(tr, "‹", right - w + 5, midY - 4, Theme.RED, false);
            ctx.drawText(tr, "›", right - 9, midY - 4, Theme.RED, false);
            ctx.drawText(tr, v, right - w + (w - tr.getWidth(v)) / 2, midY - 4, Theme.TEXT, false);
        } else if (row instanceof net.evadeclient.setting.StringSetting st) {
            String v = st.get();
            int w = Math.max(60, tr.getWidth(v) + 16);
            Theme.roundBordered(ctx, right - w, midY - 7, right, midY + 7, Theme.CONTROL, Theme.BORDER);
            ctx.drawText(tr, v, right - w + (w - tr.getWidth(v)) / 2, midY - 4, Theme.TEXT, false);
            int hw = Math.round(tr.getWidth(st.hint()) * 0.75f);
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
                    int w = Math.max(70, textRenderer.getWidth(md.get()) + 30);
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
        ctx.drawText(textRenderer, s, 0, 0, color, false);
        m.popMatrix();
    }

    private String trimScaled(String s, int maxW, float scale) {
        int limit = Math.round(maxW / scale);
        if (textRenderer.getWidth(s) <= limit) return s;
        return textRenderer.trimToWidth(s, limit - textRenderer.getWidth("...")) + "...";
    }

    private static float ease(float t) { return 1 - (float) Math.pow(1 - t, 3); }
}
