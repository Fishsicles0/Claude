package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.HudElementModule;
import net.evadeclient.module.Module;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.setting.Setting;
import net.evadeclient.setting.StatusSetting;
import net.evadeclient.setting.StringSetting;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.StringHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntPredicate;

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
    /** Sort the module list A-Z instead of the curated order (kept for the session). */
    private static boolean sortAz;
    private boolean enabledOnly;
    /** The screen to go back to when the menu was opened from another one (the HUD editor's right-click). */
    private Screen returnTo;

    private String search = "";
    private boolean searchFocused;
    private double scroll, scrollTarget;
    private long openedAt;
    private final Map<Object, Float> anim = new HashMap<>();
    /** Springs by key (see {@link #spring}): highlights, scroll and sliders that chase a moving target. */
    private final Map<Object, Motion.Spring> springs = new HashMap<>();
    /** Client Settings' Animations, read once per frame at the top of {@link #render}. */
    private boolean anims = true;
    /** Client Settings' Smooth Font, read once per frame at the top of {@link #render}, so clicks measure text the
     *  way the frame they land on drew it. */
    private boolean smooth = true;
    /** Milliseconds for animations. Tests swap in a fixed-step clock to record smooth clips for the website. */
    public static java.util.function.LongSupplier clock = System::currentTimeMillis;
    private long lastFrame = clock.getAsLong();
    private float dt;

    /** The module (keybind) or KeySetting waiting for a key press. */
    private Object listening;
    /** A key the capture refused (e.g. the Menu Key for a module): the row flashes red with {@link #rejectMsg}. */
    private Object rejectRow;
    private long rejectAt;
    private String rejectMsg = "";
    private NumberSetting dragging;
    private int dragX, dragW;
    /** Where the cursor is along the dragged slider (0..1), unstepped: the thumb follows it exactly while the value
     *  snaps to its step, then glides onto the stepped value on release. */
    private float dragPct;
    /** The text field open on a settings row (a number, a colour or a short text value), or null. */
    private Editor editor;

    /** A modifier Menu Key (e.g. Right Shift) closes the menu when it is released, if nothing else was pressed,
     *  typed, clicked or scrolled meanwhile: Shift + letter types a capital and Shift + wheel steps by 10. */
    private boolean menuKeyDown, menuKeyUsed;

    // scrollbar of the current page (set while drawing it) and its drag state
    private int sbTop, sbViewH, sbContentH;
    private boolean sbDrag;
    private double sbGrab;

    // sidebar scrolling: only used when the nav list can't fit (it always fits at the menu's design size)
    private double navScroll;

    // settings-row hover: the slider under the mouse (wheel and arrow keys), and the row the tooltip is for
    private NumberSetting hoverSlider;
    private Object tipRow;
    private long tipSince;
    private List<TipLine> tip;
    private int tipX, tipY;

    // layout (recomputed each frame)
    private int wx, wy, ww, wh, cx, cy, cw, ch, searchY, headerY;
    private static final int SIDEBAR = 158, ROW_H = 24, SLIDER_W = 120;
    private static final int LIST_H = 50, CARD_H = 58, COMPACT_H = 22, GAP = 8;
    private static final int NAV_TOP = 58, NAV_HEADER_H = 18, NAV_ITEM_H = 27, NAV_GROUP_GAP = 6;
    private static final int NAV_CONTENT_H = navContentHeight();
    /** Hover time before a settings row shows its tooltip. */
    private static final long TIP_DELAY = 400;
    /** How long a pending profile delete waits for its confirming second click. */
    private static final long DELETE_CONFIRM_MS = 3000;

    public EvadeMenu() {
        super(Text.literal("Evade"));
        selected = null; // opening the menu always starts on the list, not wherever it was last
    }

    /** Test hook: jump the menu straight to the Profiles page. */
    public static void openProfilesForTest() { page = Page.PROFILES; selected = null; }

    /** Open the menu, optionally straight onto a module's settings page. */
    public static EvadeMenu open(Module select) {
        return open(select, null);
    }

    /**
     * Open the menu, optionally on a module's settings page, from another screen: Escape (or Back) on that module
     * page and closing the menu return to {@code returnTo} instead of the game. Picking a sidebar page forgets it.
     */
    public static EvadeMenu open(Module select, Screen returnTo) {
        EvadeMenu menu = new EvadeMenu();
        if (select != null) selected = select;
        menu.returnTo = returnTo;
        return menu;
    }

    @Override
    protected void init() {
        openedAt = clock.getAsLong();
        anims = settings().animations.get();
        smooth = settings().smoothFont.get();
    }

    private ClientSettings settings() { return EvadeClient.modules.get(ClientSettings.class); }

    // ------------------------------------------------------------------ rendering

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        settings().applyTheme();
        if (settings().blur.get()) super.renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fillGradient(0, 0, width, height, 0xB0000000, (0xD0000000 | (Theme.lerpColor(0xFF000000, Theme.RED, 0.09f) & 0xFFFFFF)));
    }

    // ------------------------------------------------------------------ fitting the screen
    // The menu is laid out for about 820x506 GUI pixels. With a large GUI scale (e.g. Auto on a laptop with Windows
    // display scaling) the screen can be much smaller, so the menu draws itself at the largest scale that fits:
    // a whole number of screen pixels per menu pixel when possible, so text stays crisp. Mouse input is mapped the same way.
    private static final int DESIGN_W = 820, DESIGN_H = 506;
    /** Menu units to GUI units (1 = no change). */
    private float vs = 1f;
    /** Screen pixels per menu pixel. Picks the smooth font files rasterised for it (see {@link #fontG}); at 1 the
     *  smallest text roles step up a size so they stay readable (see {@link #shown}). */
    private double menuPx = 2;

    private void updateScale() {
        if (client == null) { vs = 1f; return; }
        var win = client.getWindow();
        double gs = Math.max(1, win.getScaleFactor());
        double fit = Math.min(win.getFramebufferWidth() / (double) DESIGN_W, win.getFramebufferHeight() / (double) DESIGN_H);
        double k = fit >= 1 ? Math.min(gs, Math.floor(fit)) : fit; // screen pixels per menu pixel
        vs = (float) (k / gs);
        menuPx = k;
    }

    private Click scaled(Click c) { return vs == 1f ? c : new Click(c.x() / vs, c.y() / vs, c.buttonInfo()); }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        anims = settings().animations.get();
        smooth = settings().smoothFont.get();
        super.render(ctx, mouseX, mouseY, delta);
        updateScale();
        int realW = width, realH = height;
        width = Math.round(realW / vs);
        height = Math.round(realH / vs);
        var mm = ctx.getMatrices();
        mm.pushMatrix();
        mm.scale(vs, vs);
        try {
            renderMenu(ctx, (int) Math.floor(mouseX / vs), (int) Math.floor(mouseY / vs), delta);
        } finally {
            mm.popMatrix();
            width = realW;
            height = realH;
        }
    }

    private void renderMenu(DrawContext ctx, int mouseX, int mouseY, float delta) {
        long now = clock.getAsLong();
        dt = Math.max(0f, Math.min(0.1f, (now - lastFrame) / 1000f));
        lastFrame = now;
        scroll += (scrollTarget - scroll) * (1 - Math.exp(-18 * dt));
        // Per-frame hover state, filled in by whichever page draws below.
        sbContentH = 0;
        hoverSlider = null;
        tip = null;
        if (pendingDelete != null && now - pendingDeleteAt > DELETE_CONFIRM_MS) pendingDelete = null;

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

        float open = anims ? ease(Math.min(1, (clock.getAsLong() - openedAt) / 220f)) : 1;
        var m = ctx.getMatrices();
        m.pushMatrix();
        float s = 0.95f + 0.05f * open;
        m.translate(width / 2f, height / 2f + (1 - open) * 14);
        m.scale(s, s);
        m.translate(-width / 2f, -height / 2f);

        // Window: a soft drop shadow and a faint accent halo behind the body, then the sidebar, then the 1 px ring
        // over both, so it also covers the sidebar's rounded corners.
        windowShadow(ctx, wx, wy + 6, wx + ww, wy + wh + 6, Theme.alpha(0xFF000000, 0.6f));
        windowShadow(ctx, wx, wy, wx + ww, wy + wh, Theme.alpha(Theme.RED, 0.2f));
        Theme.round(ctx, wx, wy, wx + ww, wy + wh, Theme.BG, Theme.R_WIN);
        renderSidebar(ctx, mouseX, mouseY);
        Theme.stroke(ctx, wx, wy, wx + ww, wy + wh, Theme.BORDER, Theme.R_WIN);
        renderClose(ctx, mouseX, mouseY);

        if (selected != null) renderSettingsPage(ctx, mouseX, mouseY, selected);
        else if (page == Page.SETTINGS) renderSettingsPage(ctx, mouseX, mouseY, settings());
        else if (page == Page.PROFILES) renderProfilesPage(ctx, mouseX, mouseY);
        else {
            renderSearch(ctx, mouseX, mouseY);
            renderHeader(ctx, mouseX, mouseY);
            renderGrid(ctx, mouseX, mouseY);
        }
        drawTip(ctx);

        m.popMatrix();
    }

    /**
     * A {@link Theme#SH_WINDOW} shadow (or halo) around the window rect, with the middle that the nine-slice leaves
     * out filled in the same colour. The window body is slightly translucent, so an empty middle would show through
     * it as a faint frame where the edge slices end.
     */
    private static void windowShadow(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
        Theme.Shadow s = Theme.SH_WINDOW;
        Theme.shadow(ctx, s, x1, y1, x2, y2, color);
        int in = s.slice() - s.pad(); // how far the edge slices reach inside the rect (they are opaque there)
        Theme.fill(ctx, x1 + in, y1 + in, x2 - in, y2 - in, color);
    }

    // ---- sidebar ------------------------------------------------------

    private record NavHit(Nav nav, int y) {}

    private static int navContentHeight() {
        int h = 0;
        for (NavGroup g : GROUPS) h += (g.header != null ? NAV_HEADER_H : 0) + g.items.size() * NAV_ITEM_H + NAV_GROUP_GAP;
        return h;
    }

    /** The nav list's visible band: from under the logo to above the account footer. */
    private int navTop() { return wy + NAV_TOP; }
    private int navBottom() { return wy + wh - 34; }
    private int navMaxScroll() { return Math.max(0, NAV_CONTENT_H - (navBottom() - navTop())); }

    /** Lay the grouped nav out and return each item with its y, for render + click to share. */
    private List<NavHit> navLayout() {
        List<NavHit> hits = new ArrayList<>();
        int y = navTop() - (int) Math.round(navScroll);
        for (NavGroup g : GROUPS) {
            if (g.header != null) y += NAV_HEADER_H;
            for (Nav n : g.items) { hits.add(new NavHit(n, y)); y += NAV_ITEM_H; }
            y += NAV_GROUP_GAP;
        }
        return hits;
    }

    private void renderSidebar(DrawContext ctx, int mx, int my) {
        // Rounded on the left, one radius smaller and one pixel in, so it sits inside the window's corner ring
        // instead of poking a square corner through it.
        Theme.round(ctx, wx + 1, wy + 1, wx + SIDEBAR, wy + wh - 1, Theme.SIDEBAR, Theme.R_WIN - 1, Theme.TL | Theme.BL);
        Theme.fill(ctx, wx + SIDEBAR, wy + 1, wx + SIDEBAR + 1, wy + wh - 1, Theme.BORDER);

        net.evadeclient.util.Logo.lockup(ctx, wx + 16, wy + 15, 22);
        caps(ctx, "Client", wx + 44, wy + 35, Theme.TEXT_FAINT, Role.CAPS_SM, 1.5f);
        Theme.fill(ctx, wx + 16, wy + 46, wx + SIDEBAR - 16, wy + 47, Theme.DIVIDER);

        // On a screen too short for the whole list (it fits at the design size) the list clips and scrolls.
        navScroll = Math.max(0, Math.min(navScroll, navMaxScroll()));
        boolean clip = navMaxScroll() > 0;
        if (clip) ctx.enableScissor(wx + 1, navTop(), wx + SIDEBAR, navBottom());
        int y = navTop() - (int) Math.round(navScroll);
        for (NavGroup g : GROUPS) {
            if (g.header != null) {
                caps(ctx, g.header, wx + 18, y + 7, Theme.TEXT_HEADER, Role.CAPS, 2f);
                y += NAV_HEADER_H;
            }
            for (Nav n : g.items) {
                boolean active = !n.hud && n.page == page && selected == null;
                navItem(ctx, n, y, mx, my, active);
                y += NAV_ITEM_H;
            }
            y += NAV_GROUP_GAP;
        }
        if (clip) ctx.disableScissor();

        String user = client != null && client.getSession() != null ? client.getSession().getUsername() : "";
        Theme.fill(ctx, wx + 16, wy + wh - 32, wx + SIDEBAR - 16, wy + wh - 31, Theme.DIVIDER);
        int footW = SIDEBAR - 32;
        text(ctx, ellipsize(Role.LABEL, user.isEmpty() ? "Player" : user, footW), wx + 16, wy + wh - 25, Theme.TEXT_DIM, Role.LABEL);
        text(ctx, ellipsize(Role.SMALL, "Evade v" + EvadeClient.VERSION, footW), wx + 16, wy + wh - 15, Theme.TEXT_FAINT, Role.SMALL);
    }

    private void navItem(DrawContext ctx, Nav n, int y, int mx, int my, boolean active) {
        int x1 = wx + 10, x2 = wx + SIDEBAR - 10, top = y + 1, bot = y + NAV_ITEM_H - 3;
        boolean hover = mx >= x1 && mx < x2 && my >= top && my < bot && my >= navTop() && my < navBottom();
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
        net.evadeclient.util.Icons.draw(ctx, n.icon, ix, iy, ICON, fg);

        int textColor = active ? Theme.RED_BRIGHT : Theme.lerpColor(Theme.TEXT_SOFT, Theme.TEXT, t);
        text(ctx, n.label, ix + ICON + 9, y + (NAV_ITEM_H - 9) / 2, textColor, false);

        // Count pill, right-aligned (category pages only, not Settings): calm grey, in the accent only when active,
        // so the sidebar doesn't read as a column of red badges. On hover it lifts with the row, which is about the
        // pill's own grey there.
        if (n.page != null && n.page.category != null && n.page != Page.SETTINGS) {
            String cnt = String.valueOf(modulesFor(n.page, "").size());
            int tw = width(Role.SMALL, cnt);
            int cw2 = Math.max(13, tw + 8);
            int chipX = x2 - 7 - cw2, chipY = y + (NAV_ITEM_H - 2 - 12) / 2;
            int chip = active ? Theme.alpha(Theme.RED, 0.3f) : Theme.lerpColor(Theme.CONTROL, Theme.CONTROL_HOVER, Math.min(1f, t * 2.5f));
            Theme.pill(ctx, chipX, chipY, chipX + cw2, chipY + 12, chip);
            // The vanilla width counts a trailing pixel column the glyphs don't fill: centre past it. Inter's advances
            // carry their own side bearings, so it centres on the measured width (rounding half up).
            text(ctx, cnt, chipX + (smooth ? (cw2 - tw + 1) / 2 : (cw2 - tw) / 2 + 1), chipY + 3,
                    active ? Theme.RED_BRIGHT : Theme.TEXT_DIM, Role.SMALL);
        }
    }

    private static final int ICON = 12;

    private void renderClose(DrawContext ctx, int mx, int my) {
        int bx = wx + ww - 24, by = wy + 12;
        boolean hc = mx >= bx && mx < bx + 14 && my >= by && my < by + 14;
        Theme.round(ctx, bx, by, bx + 14, by + 14, hc ? Theme.RED : Theme.CONTROL);
        icon(ctx, "close", bx + 2, by + 2, 10, hc ? Theme.ON_ACCENT : Theme.TEXT_SOFT);
    }

    // ---- content header ----------------------------------------------

    /** Right edge of the content row that shares the window's top band with the close button. */
    private int contentRight() { return cx + cw - 22; }

    private int searchW() { return contentRight() - cx; }

    private void renderSearch(DrawContext ctx, int mx, int my) {
        int sx = cx, sw = searchW();
        boolean hs = mx >= sx && mx < sx + sw && my >= searchY && my < searchY + 24;
        focusGlow(ctx, "search", searchFocused, sx, searchY, sx + sw, searchY + 24);
        Theme.roundBordered(ctx, sx, searchY, sx + sw, searchY + 24, Theme.INPUT, searchFocused ? Theme.RED : hs ? Theme.BORDER_HOVER : Theme.BORDER);
        // magnifier
        icon(ctx, "search", sx + 7, searchY + 7, 10, searchFocused ? Theme.RED_BRIGHT : Theme.TEXT_DIM);

        boolean caret = searchFocused && (clock.getAsLong() / 500) % 2 == 0;
        if (search.isEmpty() && !searchFocused) {
            text(ctx, "Search modules", sx + 22, searchY + 8, Theme.TEXT_FAINT, false);
            int hx = sx + 22 + width("Search modules") + 14;
            text(ctx, ellipsize(Role.LABEL, "Ctrl+F  ·  searches every mod and setting", sx + sw - 8 - hx), hx, searchY + 9,
                    Theme.alpha(Theme.TEXT_FAINT, 0.7f), Role.LABEL);
        } else {
            text(ctx, trimStart(search, sw - 30) + (caret ? "_" : ""), sx + 22, searchY + 8, Theme.TEXT, false);
        }
    }

    /** A soft accent glow around a text field that fades in while it has focus (drawn before the field's box). */
    private void focusGlow(DrawContext ctx, String id, boolean focused, int x1, int y1, int x2, int y2) {
        float focus = animate("focus:" + id, focused ? 1 : 0, 16);
        if (focus > 0.01f) Theme.shadow(ctx, Theme.SH_CARD, x1, y1, x2, y2, Theme.alpha(Theme.RED, 0.28f * focus));
    }

    // All / Enabled segmented toggle and the A-Z sort chip to its left, right-aligned in the header.
    private static final int SEG_W = 150, AZ_W = 34;

    private int segX() { return cx + cw - SEG_W; }
    private int azX() { return segX() - 8 - AZ_W; }

    private void renderHeader(DrawContext ctx, int mx, int my) {
        // title + module count; while searching the list covers every category
        String title = search.isBlank() ? page.label : "Search";
        text(ctx, title, cx, headerY, Theme.TEXT, Role.TITLE);
        int tw = width(Role.TITLE, title);
        int total = modulesFor(listPage(), "").size();
        String noun = total == 1 ? " module" : " modules";
        String count = !search.isBlank() || enabledOnly ? visibleModules().size() + " of " + total + noun : total + noun;
        text(ctx, ellipsize(Role.LABEL, count, azX() - 10 - (cx + tw + 10)), cx + tw + 10, headerY + 6, Theme.TEXT_DIM, Role.LABEL);

        int segX = segX(), segY = headerY - 3, half = SEG_W / 2;
        Theme.roundBordered(ctx, segX, segY, segX + SEG_W, segY + 22, Theme.INPUT, Theme.BORDER);
        float sel = animate("seg", enabledOnly ? 1 : 0, 18);
        // The knob glides in device pixels (a whole-pixel step would visibly jump at GUI scale 2 and up).
        int knobX = segX + 2;
        var km = ctx.getMatrices();
        km.pushMatrix();
        km.translate(Theme.snap(ctx, (half - 2) * sel), 0);
        Theme.round(ctx, knobX, segY + 2, knobX + half - 2, segY + 20, Theme.alpha(Theme.RED, 0.9f), Theme.R_XS);
        km.popMatrix();
        drawCentered(ctx, "All", segX, segX + half, segY + 7, Theme.lerpColor(Theme.ON_ACCENT, Theme.TEXT_DIM, sel));
        drawCentered(ctx, "Enabled", segX + half, segX + SEG_W, segY + 7, Theme.lerpColor(Theme.TEXT_DIM, Theme.ON_ACCENT, sel));

        int ax = azX();
        boolean ha = mx >= ax && mx < ax + AZ_W && my >= segY && my < segY + 22;
        Theme.roundBordered(ctx, ax, segY, ax + AZ_W, segY + 22, sortAz ? Theme.tint(0.16f) : ha ? Theme.CONTROL_HOVER : Theme.INPUT,
                sortAz ? Theme.RED : ha ? Theme.BORDER_HOVER : Theme.BORDER);
        drawCentered(ctx, "A-Z", ax, ax + AZ_W, segY + 7, sortAz ? Theme.RED_BRIGHT : ha ? Theme.TEXT : Theme.TEXT_DIM);
        if (ha) setTip(mx, my, List.of(new TipLine(sortAz ? "Sorted A-Z. Click for the usual order" : "Sort A-Z", Theme.TEXT_SOFT)));
    }

    private void drawCentered(DrawContext ctx, String s, int x1, int x2, int y, int color) {
        drawCentered(ctx, s, x1, x2, y, color, Role.BODY);
    }

    /** {@code s} in role {@code r}, centred between {@code x1} and {@code x2}. */
    private void drawCentered(DrawContext ctx, String s, int x1, int x2, int y, int color, Role r) {
        text(ctx, s, x1 + (x2 - x1 - width(r, s)) / 2, y, color, r);
    }

    // ---- profiles page ------------------------------------------------

    private String profileInput = "";
    private boolean profileFocused;
    /** A profile whose delete button was clicked once: a second click within {@link #DELETE_CONFIRM_MS} deletes it. */
    private String pendingDelete;
    private long pendingDeleteAt;
    private static final int PROFILE_H = 44;

    private int createButtonX() { return contentRight() - 96; }
    private int importButtonX() { return createButtonX() - 78; }
    private int profileInputW() { return cw - 22 - 104 - 78; }

    private void renderProfilesPage(DrawContext ctx, int mx, int my) {
        var profiles = EvadeClient.profiles;
        if (profiles == null) return;

        // new-profile input + Import and Create buttons (kept clear of the window's close button)
        int inW = profileInputW();
        boolean hin = mx >= cx && mx < cx + inW && my >= searchY && my < searchY + 24;
        focusGlow(ctx, "profile", profileFocused, cx, searchY, cx + inW, searchY + 24);
        Theme.roundBordered(ctx, cx, searchY, cx + inW, searchY + 24, Theme.INPUT, profileFocused ? Theme.RED : hin ? Theme.BORDER_HOVER : Theme.BORDER);
        boolean caret = profileFocused && (clock.getAsLong() / 500) % 2 == 0;
        if (profileInput.isEmpty() && !profileFocused) {
            text(ctx, "New profile name…", cx + 8, searchY + 8, Theme.TEXT_FAINT, false);
        } else {
            text(ctx, trimStart(profileInput, inW - 22) + (caret ? "_" : ""), cx + 8, searchY + 8, Theme.TEXT, false);
        }
        int bx = createButtonX(), br = contentRight();
        boolean hc = mx >= bx && mx < br && my >= searchY && my < searchY + 24;
        Theme.round(ctx, bx, searchY, br, searchY + 24, hc ? Theme.RED_BRIGHT : Theme.RED);
        drawCentered(ctx, "Create", bx, br, searchY + 8, Theme.ON_ACCENT, Role.STRONG);
        int ix = importButtonX();
        boolean hi = mx >= ix && mx < ix + 70 && my >= searchY && my < searchY + 24;
        Theme.roundBordered(ctx, ix, searchY, ix + 70, searchY + 24, hi ? Theme.CONTROL_HOVER : Theme.CONTROL, hi ? Theme.RED : Theme.BORDER);
        drawCentered(ctx, "Import", ix, ix + 70, searchY + 8, Theme.TEXT);

        // header
        text(ctx, "Profiles", cx, headerY, Theme.TEXT, Role.TITLE);
        List<String> list = profiles.list();
        String active = profiles.active();
        int tw = width(Role.TITLE, "Profiles");
        text(ctx, list.size() + (list.size() == 1 ? " profile" : " profiles"), cx + tw + 10, headerY + 6, Theme.TEXT_DIM, Role.LABEL);

        int contentH = list.size() * (PROFILE_H + GAP);
        scrollTarget = Math.max(0, Math.min(scrollTarget, Math.max(0, contentH - ch)));
        ctx.enableScissor(cx - 2, cy, cx + cw + 2, cy + ch);
        for (int i = 0; i < list.size(); i++) {
            int y = cy + i * (PROFILE_H + GAP) - (int) Math.round(scroll);
            if (y + PROFILE_H < cy || y > cy + ch) continue;
            renderProfileRow(ctx, list.get(i), list.get(i).equals(active), list.size(), y, mx, my);
        }
        ctx.disableScissor();
        drawScrollbar(ctx, cy, ch, contentH);

        text(ctx, ellipsize(Role.SMALL, "Each profile remembers its mods, settings, keybinds and HUD layout. Copy a code to share one; Import adds a copied code.", cw),
                cx, cy + ch + 2, Theme.TEXT_FAINT, Role.SMALL);
    }

    private record ProfileButton(String id, String label, int x1, int x2) {}

    /** Width of the Use button and the ACTIVE chip that takes its place on the active row. */
    private static final int USE_W = 46, DELETE_W = 18;

    /** The buttons on a profile row, right to left: Use (or the ACTIVE chip), delete, copy code, link to this server.
     *  The active row leaves the delete slot empty, so Copy code lines up on every row. */
    private List<ProfileButton> profileButtons(String name, boolean active, int total) {
        List<ProfileButton> out = new ArrayList<>();
        int right = cx + cw - 12;
        if (!active) {
            out.add(new ProfileButton("use", "Use", right - USE_W, right)); right -= USE_W + 6;
            if (total > 1) {
                boolean confirm = name.equals(pendingDelete);
                int dw = confirm ? width(Role.LABEL, "Delete?") + 14 : DELETE_W;
                out.add(new ProfileButton("delete", confirm ? "Delete?" : "×", right - dw, right)); right -= dw + 6;
            }
        } else {
            right -= USE_W + 6 + (total > 1 ? DELETE_W + 6 : 0);
        }
        out.add(new ProfileButton("copy", "Copy code", right - 64, right)); right -= 64 + 6;
        String srv = currentServer();
        if (srv != null) {
            String label = isLinked(name, srv) ? "Linked here" : "Use on this server";
            int w = width(Role.LABEL, label) + 14;
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
        if (active) Theme.round(ctx, cx + 1, y + 6, cx + 3, y + PROFILE_H - 6, Theme.RED, 1);

        List<ProfileButton> buttons = profileButtons(name, active, total);
        int textRight = cx + cw - 12 - (active ? USE_W : 0);
        for (ProfileButton b : buttons) textRight = Math.min(textRight, b.x1());
        int textW = textRight - 10 - (cx + 16);

        List<String> servers = EvadeClient.profiles.serversFor(name);
        if (servers.isEmpty()) {
            text(ctx, trim(name, textW), cx + 16, y + (PROFILE_H - 8) / 2, Theme.TEXT, false);
        } else {
            text(ctx, trim(name, textW), cx + 16, y + 11, Theme.TEXT, false);
            text(ctx, ellipsize(Role.SMALL, "Auto on " + String.join(", ", servers), textW), cx + 16, y + 25, Theme.TEXT_DIM, Role.SMALL);
        }

        int by = y + (PROFILE_H - 18) / 2;
        if (active) {
            // The ACTIVE chip sits in the Use button's slot, at the buttons' height.
            int right = cx + cw - 12, tw = width(Role.CAPS, "ACTIVE");
            Theme.pill(ctx, right - USE_W, by, right, by + 18, Theme.alpha(Theme.RED, 0.3f));
            text(ctx, "ACTIVE", right - USE_W + (USE_W - tw) / 2, by + 6, Theme.RED_BRIGHT, Role.CAPS);
        }
        for (ProfileButton b : buttons) {
            boolean hb = inView && mx >= b.x1() && mx < b.x2() && my >= by && my < by + 18;
            switch (b.id()) {
                case "use" -> {
                    // The row's primary action: a solid accent button (its own border colour, so no ring shows).
                    int bg = hb ? Theme.RED_BRIGHT : Theme.RED;
                    Theme.roundBordered(ctx, b.x1(), by, b.x2(), by + 18, bg, bg);
                    drawCentered(ctx, b.label(), b.x1(), b.x2(), by + 5, Theme.ON_ACCENT, Role.STRONG);
                }
                case "delete" -> {
                    boolean confirm = name.equals(pendingDelete);
                    int bg = confirm ? Theme.alpha(Theme.DANGER, hb ? 0.3f : 0.18f) : hb ? Theme.alpha(Theme.DANGER, 0.15f) : Theme.CONTROL;
                    Theme.roundBordered(ctx, b.x1(), by, b.x2(), by + 18, bg, confirm || hb ? Theme.DANGER : Theme.BORDER);
                    if (confirm) {
                        drawCentered(ctx, b.label(), b.x1(), b.x2(), by + 6, Theme.DANGER, Role.LABEL);
                    } else {
                        text(ctx, "×", b.x1() + 6, by + 5, hb ? Theme.DANGER : Theme.TEXT_DIM, false);
                    }
                    if (hb) setTip(mx, my, List.of(new TipLine(confirm ? "Click again to delete " + name : "Delete " + name, Theme.TEXT_SOFT)));
                }
                default -> {
                    boolean linked = b.id().equals("server") && b.label().startsWith("Linked");
                    int bg = hb ? Theme.CONTROL_HOVER : linked ? Theme.alpha(Theme.RED, 0.22f) : Theme.CONTROL;
                    Theme.roundBordered(ctx, b.x1(), by, b.x2(), by + 18, bg, hb || linked ? Theme.RED : Theme.BORDER);
                    drawCentered(ctx, b.label(), b.x1(), b.x2(), by + 6, linked ? Theme.RED_BRIGHT : hb ? Theme.TEXT : Theme.TEXT_SOFT, Role.LABEL);
                }
            }
        }
    }

    private boolean clickProfiles(double mx, double my) {
        var profiles = EvadeClient.profiles;
        if (profiles == null) return true;
        // Any click other than the confirming one cancels a pending delete.
        String confirming = pendingDelete;
        pendingDelete = null;
        // input box / import / create
        int inW = profileInputW();
        profileFocused = mx >= cx && mx < cx + inW && my >= searchY && my < searchY + 24;
        if (profileFocused) return true;
        if (mx >= importButtonX() && mx < importButtonX() + 70 && my >= searchY && my < searchY + 24) {
            String imported = profiles.importCode(client.keyboard.getClipboard());
            if (imported != null) Notifications.push("Profile imported", imported + " is ready to use", true);
            else Notifications.push("Nothing to import", "Copy an Evade profile code first", false);
            return true;
        }
        if (mx >= createButtonX() && mx < contentRight() && my >= searchY && my < searchY + 24) {
            createProfile();
            return true;
        }
        List<String> list = profiles.list();
        String active = profiles.active();
        for (int i = 0; i < list.size(); i++) {
            int y = cy + i * (PROFILE_H + GAP) - (int) Math.round(scroll);
            if (my < y || my >= y + PROFILE_H || my < cy || my >= cy + ch) continue;
            String name = list.get(i);
            int by = y + (PROFILE_H - 18) / 2;
            if (my < by || my >= by + 18) return true;
            // Lay the buttons out as they were drawn (with the delete still pending), then act.
            pendingDelete = confirming;
            List<ProfileButton> buttons = profileButtons(name, name.equals(active), list.size());
            pendingDelete = null;
            for (ProfileButton b : buttons) {
                if (mx < b.x1() || mx >= b.x2()) continue;
                switch (b.id()) {
                    case "use" -> profiles.switchTo(name);
                    case "delete" -> {
                        if (name.equals(confirming)) {
                            if (profiles.delete(name)) Notifications.push("Profile deleted", name, true);
                        } else {
                            pendingDelete = name;
                            pendingDeleteAt = clock.getAsLong();
                        }
                    }
                    case "copy" -> {
                        String code = profiles.shareCode(name);
                        if (code != null) {
                            client.keyboard.setClipboard(code);
                            Notifications.push("Code copied", "Share it; anyone can paste it into Import", true);
                        }
                    }
                    case "server" -> {
                        boolean on = profiles.toggleServer(name, currentServer());
                        Notifications.push("Profile", on ? name + " will load on this server" : "Unlinked from this server", true);
                    }
                }
                return true;
            }
            return true;
        }
        return true;
    }

    /** Create a profile from the input (or "Profile N" when it is blank) and switch to it. */
    private void createProfile() {
        var profiles = EvadeClient.profiles;
        if (profiles == null) return;
        String name = profileName(profileInput);
        if (profiles.create(name)) {
            profileInput = "";
            profileFocused = false;
            Notifications.push("Profile created", name + " is now active", true);
        } else if (!net.evadeclient.config.ProfileManager.sanitize(profileInput).isEmpty()) {
            Notifications.push("Name taken", "There's already a profile called " + name, false);
        }
    }

    /** Fall back to an auto name ("Profile N") when the input is blank. */
    private String profileName(String typed) {
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
            if (!f.isBlank() && !matches(mod, f)) continue;
            out.add(mod);
        }
        return out;
    }

    /** Search matches a mod's name, description or any of its settings' names and descriptions (all words must match). */
    private static boolean matches(Module mod, String f) {
        StringBuilder hay = new StringBuilder(mod.getName()).append(' ').append(mod.getDescription()).append(' ')
                .append(mod.getCategory().displayName);
        for (Setting<?> st : mod.getSettings()) {
            if (st.isHidden()) continue;
            hay.append(' ').append(st.getName());
            String d = descriptionOf(st);
            if (!d.isEmpty()) hay.append(' ').append(d);
        }
        String h = hay.toString().toLowerCase(Locale.ROOT);
        for (String word : f.trim().split("\\s+")) if (!h.contains(word)) return false;
        return true;
    }

    /** The page whose modules the list shows: a search looks through every category. */
    private Page listPage() { return search.isBlank() ? page : Page.ALL; }

    /** Modules to show, after the search box, the All/Enabled filter and the A-Z sort. */
    private List<Module> visibleModules() {
        List<Module> list = modulesFor(listPage(), search);
        if (enabledOnly) list.removeIf(mod -> !mod.isEnabled());
        if (sortAz) list.sort(Comparator.comparing(Module::getName, String.CASE_INSENSITIVE_ORDER));
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

        // Wider than the rows on both sides (they still end at cx + cw) so a hover glow fades out instead of being
        // cut off; the sidebar edge is 17 px away and the scrollbar is drawn after the scissor.
        ctx.enableScissor(cx - 10, cy, cx + cw + 10, cy + ch);
        // Hover glows first, under every row, so one never paints over the row before it (Compact rows are 4 px
        // apart). They use the hover value the rows animated last frame: a frame late, which can't be seen. Enabled
        // rows get no glow of their own: a list of enabled mods would look like neon.
        for (int i = 0; i < mods.size(); i++) {
            Module mod = mods.get(i);
            int x = cx + (i % cols) * (cardW + rg);
            int y = cy + (i / cols) * (rh + rg) - (int) Math.round(scroll);
            if (y + rh < cy || y > cy + ch) continue;
            float h = anim.getOrDefault("hover:" + mod.getName(), 0f);
            if (h > 0.01f) Theme.shadow(ctx, Theme.SH_CARD, x, y, x + cardW, y + rh, Theme.alpha(Theme.RED, 0.30f * h));
        }
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
            String q = search.trim();
            String msg = !q.isEmpty() ? (enabledOnly ? "No enabled modules match \"" : "No modules match \"") + q + "\""
                    : enabledOnly ? "No enabled mods here" : "Nothing here yet";
            msg = trim(msg, cw - 20);
            text(ctx, msg, cx + (cw - width(msg)) / 2, cy + 30, Theme.TEXT_DIM, false);
        }
        ctx.disableScissor();
        drawScrollbar(ctx, cy, ch, contentH);

        text(ctx, ellipsize(Role.SMALL, "Click: toggle  ·  Right-click / gear: settings & keybind", cw), cx, cy + ch + 3, Theme.TEXT_FAINT, Role.SMALL);
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
        if (on > 0.01f) Theme.round(ctx, x + 1, y + 6, x + 3, y + LIST_H - 6, Theme.alpha(Theme.RED, on), 1);

        int tx = x + 16;
        text(ctx, ellipsize(Role.STRONG, mod.getName(), w - 150), tx, y + 11, Theme.TEXT, Role.STRONG);
        text(ctx, ellipsize(Role.SMALL, mod.getDescription(), w - 150), tx, y + 26, Theme.TEXT_DIM, Role.SMALL);

        int right = x + w - 12;
        // toggle (far right)
        Theme.toggle(ctx, right - 20, y + (LIST_H - 10) / 2, on);
        right -= 34;
        // gear: every module has one, since every module has at least its keybind to set
        if (hasSettingsPage(mod)) {
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
        if (on > 0.01f) Theme.fill(ctx, x + 1, y + 3, x + 2, y + CARD_H - 3, Theme.alpha(Theme.RED, on));

        int catColor = Theme.lerpColor(Theme.TEXT_FAINT, Theme.RED_BRIGHT, on);
        icon(ctx, categoryIcon(mod.getCategory()), x + 7, y + 4, 9, catColor);
        caps(ctx, mod.getCategory().displayName, x + 19, y + 7, catColor, Role.CAPS_SM, 0f);
        if (mod.getKey() != GLFW.GLFW_KEY_UNKNOWN) {
            String k = KeySetting.keyName(mod.getKey());
            int kw = width(Role.TINY, k);
            Theme.pill(ctx, x + w - kw - 12, y + 5, x + w - 5, y + 14, Theme.CONTROL_HOVER);
            text(ctx, k, x + w - kw - 9, y + 7, Theme.TEXT_DIM, Role.TINY);
        }
        text(ctx, ellipsize(Role.STRONG, mod.getName(), w - 14), x + 8, y + 16, Theme.TEXT, Role.STRONG);
        text(ctx, ellipsize(Role.TINY, mod.getDescription(), w - 14), x + 8, y + 29, Theme.TEXT_DIM, Role.TINY);

        Theme.toggle(ctx, x + 8, y + CARD_H - 15, on);
        if (hasSettingsPage(mod)) {
            int gx = x + w - 16, gy = y + CARD_H - 16;
            boolean hg = inView && mx >= gx - 3 && mx < gx + 12 && my >= gy - 2 && my < gy + 12;
            Theme.round(ctx, gx - 3, gy - 2, gx + 12, gy + 12, hg ? Theme.RED : Theme.CONTROL);
            icon(ctx, "settings", gx, gy, 9, hg ? Theme.ON_ACCENT : Theme.TEXT_SOFT);
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
        if (on > 0.01f) Theme.fill(ctx, x + 1, y + 2, x + 2, y + COMPACT_H - 2, Theme.alpha(Theme.RED, on));
        text(ctx, ellipsize(Role.STRONG, mod.getName(), w - 46), x + 8, y + (COMPACT_H - 8) / 2, Theme.TEXT, Role.STRONG);

        Theme.toggle(ctx, x + w - 24, y + (COMPACT_H - 10) / 2, on);
        if (hasSettingsPage(mod)) {
            int gx = x + w - 40, gy = y + (COMPACT_H - 10) / 2;
            boolean hg = inView && mx >= gx - 2 && mx < gx + 11 && my >= gy - 1 && my < gy + 11;
            icon(ctx, "settings", gx, gy, 10, hg ? Theme.RED_BRIGHT : Theme.TEXT_DIM);
        }
    }

    // ---- scrollbar ----------------------------------------------------

    /** Draw the current page's scrollbar in the gutter right of the content (only when it overflows) and remember
     *  its geometry for dragging. */
    private void drawScrollbar(DrawContext ctx, int top, int viewH, int contentH) {
        sbTop = top;
        sbViewH = viewH;
        sbContentH = contentH;
        if (!hasScrollbar()) return;
        int barH = thumbH(), barY = thumbY();
        Theme.fill(ctx, cx + cw + 8, top, cx + cw + 10, top + viewH, Theme.DIVIDER);
        Theme.round(ctx, cx + cw + 8, barY, cx + cw + 10, barY + barH, sbDrag ? Theme.RED_BRIGHT : Theme.RED);
    }

    private boolean hasScrollbar() { return sbViewH > 0 && sbContentH > sbViewH; }
    private int maxScroll() { return Math.max(0, sbContentH - sbViewH); }
    private int thumbH() { return Math.max(24, sbViewH * sbViewH / Math.max(1, sbContentH)); }

    private int thumbY() {
        int max = maxScroll();
        return sbTop + (int) ((sbViewH - thumbH()) * (max == 0 ? 0 : Math.max(0, Math.min(1, scroll / max))));
    }

    /** Whether (mx, my) is in the scrollbar's grab zone (8 px around the 2 px bar). */
    private boolean onScrollbar(double mx, double my) {
        return hasScrollbar() && mx >= cx + cw + 5 && mx < cx + cw + 13 && my >= sbTop && my < sbTop + sbViewH;
    }

    private void dragScrollbar(double my) {
        int range = sbViewH - thumbH();
        double t = range <= 0 ? 0 : (my - sbGrab - sbTop) / range;
        scrollTarget = scroll = Math.max(0, Math.min(1, t)) * maxScroll();
    }

    // ---- settings page ------------------------------------------------

    private List<Object> rowsFor(Module mod) {
        List<Object> rows = new ArrayList<>();
        if (mod.getCategory() != Category.CLIENT) rows.add("keybind");
        for (Setting<?> st : mod.getSettings()) if (!st.isHidden()) rows.add(st);
        return rows;
    }

    /** Whether the module gets a gear: anything the settings page can show, its keybind included. */
    private boolean hasSettingsPage(Module mod) { return !rowsFor(mod).isEmpty(); }

    private int settingsTop() { return cy; }

    /** Right edge of the settings rows' controls, and of the header's master toggle that lines up with them. */
    private int controlRight() { return cx + cw - 10; }

    private void renderSettingsPage(DrawContext ctx, int mx, int my, Module mod) {
        // header: Back + title + description + master toggle
        if (selected != null) {
            boolean hb = mx >= cx && mx < cx + 52 && my >= searchY && my < searchY + 18;
            Theme.roundBordered(ctx, cx, searchY, cx + 52, searchY + 18, hb ? Theme.RED : Theme.CONTROL, hb ? Theme.RED : Theme.BORDER);
            text(ctx, "‹ Back", cx + 8, searchY + 5, hb ? Theme.ON_ACCENT : Theme.TEXT, false);
        }
        // The title stops short of the Reset button, or of the master toggle when there is nothing to reset.
        boolean resettable = hasChangedSettings(mod);
        int titleRight = resettable ? resetX() - 8 : selected != null ? controlRight() - 20 - 8 : cx + cw;
        text(ctx, ellipsize(Role.TITLE, mod.getName(), titleRight - cx), cx, headerY, Theme.TEXT, Role.TITLE);
        text(ctx, ellipsize(Role.LABEL, mod.getDescription(), cw), cx, headerY + 16, Theme.TEXT_DIM, Role.LABEL);
        if (selected != null) Theme.toggle(ctx, controlRight() - 20, headerY, animate("on:" + mod.getName(), mod.isEnabled() ? 1 : 0, 14));
        if (resettable) {
            int rx = resetX(), rw = 52;
            boolean hr = mx >= rx && mx < rx + rw && my >= headerY - 4 && my < headerY + 12;
            Theme.roundBordered(ctx, rx, headerY - 4, rx + rw, headerY + 12, hr ? Theme.CONTROL_HOVER : Theme.CONTROL, hr ? Theme.RED : Theme.BORDER);
            icon(ctx, "reset", rx + 6, headerY - 1, 9, hr ? Theme.RED_BRIGHT : Theme.TEXT_DIM);
            text(ctx, "Reset", rx + 19, headerY, hr ? Theme.TEXT : Theme.TEXT_SOFT, Role.LABEL);
        }

        int top = settingsTop();
        List<Object> rows = rowsFor(mod);
        int contentH = rows.size() * ROW_H;
        int viewH = cy + ch - top;
        scrollTarget = Math.max(0, Math.min(scrollTarget, Math.max(0, contentH - viewH)));
        boolean inList = mx >= cx && mx < cx + cw && my >= top && my < cy + ch;

        Object hovered = null;
        ctx.enableScissor(cx - 2, top, cx + cw + 2, cy + ch);
        int y = top - (int) Math.round(scroll);
        for (Object row : rows) {
            if (y + ROW_H <= top || y >= cy + ch) { y += ROW_H; continue; }
            boolean hover = inList && my >= y && my < y + ROW_H - 2;
            if (hover) hovered = row;
            Theme.round(ctx, cx, y, cx + cw, y + ROW_H - 3, hover ? Theme.ROW_HOVER : Theme.ROW);
            // changed from its default: a small accent dot in the row's left margin
            if (row instanceof Setting<?> st && changed(st)) Theme.disc(ctx, cx + 3, y + (ROW_H - 3) / 2 - 1, 3, Theme.RED);
            String label = row instanceof Setting<?> st ? st.getName() : "Toggle Key";
            text(ctx, label, cx + 10, y + 7, Theme.TEXT, false);
            renderControl(ctx, row, mod, controlRight(), y, mx, my, hover);
            y += ROW_H;
        }
        ctx.disableScissor();
        drawScrollbar(ctx, top, viewH, contentH);

        // Tooltip for the row under the mouse once it has rested there (not while dragging, typing or binding).
        long now = clock.getAsLong();
        if (hovered != tipRow) { tipRow = hovered; tipSince = now; }
        if (hovered != null && now - tipSince >= TIP_DELAY && dragging == null && editor == null && listening == null && !sbDrag) {
            List<TipLine> lines = tipFor(hovered, mod);
            if (!lines.isEmpty()) setTip(mx, my, lines);
        }
    }

    /** Draws the control for a settings row, right-aligned at {@code right}. */
    private void renderControl(DrawContext ctx, Object row, Module mod, int right, int y, int mx, int my, boolean rowHover) {
        int midY = y + (ROW_H - 3) / 2;
        boolean isKeybind = row.equals("keybind");
        if (isKeybind || row instanceof KeySetting) {
            Object self = isKeybind ? mod : row;
            boolean l = listening == self;
            int code = isKeybind ? mod.getKey() : ((KeySetting) row).get();
            boolean rejected = rejectRow == self && clock.getAsLong() - rejectAt < 1400;
            boolean conflict = !l && !conflicts(code, self).isEmpty();
            String text = l ? (acceptsMouse(self) ? "Press a key or mouse button..." : "Press a key...") : KeySetting.keyName(code);
            int w = Math.max(50, width(text) + 14);
            int border = rejected || conflict ? Theme.DANGER : l ? Theme.RED : Theme.BORDER;
            int fill = rejected ? Theme.alpha(Theme.DANGER, 0.16f) : Theme.CONTROL;
            Theme.roundBordered(ctx, right - w, midY - 7, right, midY + 7, fill, border);
            text(ctx, text, right - w + (w - width(text)) / 2, midY - 4, rejected || conflict ? Theme.DANGER : l ? Theme.RED_BRIGHT : Theme.TEXT, false);
            String hint = rejected ? rejectMsg : l ? (isMenuKey(self) ? "Esc cancel · Del reset" : "Esc cancel · Del clear")
                    : conflict && rowHover ? "In use elsewhere" : null;
            if (hint != null) {
                int hw = width(Role.SMALL, hint);
                text(ctx, hint, right - w - 8 - hw, midY - 3, rejected || conflict ? Theme.DANGER : Theme.TEXT_FAINT, Role.SMALL);
            }
        } else if (row instanceof BooleanSetting b) {
            Theme.toggle(ctx, right - 20, midY - 5, animate(b, b.get() ? 1 : 0, 14));
        } else if (row instanceof NumberSetting n) {
            int sx = right - SLIDER_W;
            double range = n.getMax() - n.getMin();
            float pct = range > 0 ? (float) Math.max(0, Math.min(1, (n.get() - n.getMin()) / range)) : 0f;
            boolean drag = dragging == n;
            // Hovering the track or thumb (or just past either end), or dragging, lifts the control: a brighter
            // fill, an accent halo and a slightly larger thumb.
            float g = animate(new AnimKey("sg", n), drag || (rowHover && mx >= sx - 6 && mx <= right + 6) ? 1 : 0, 22);
            // While dragging, the thumb follows the cursor exactly (the value still snaps to its step); otherwise
            // it glides onto the stepped value, so a release, a wheel step or a reset all ease into place.
            float shown;
            if (drag) {
                shown = dragPct;
                springObj(n).set(dragPct);
            } else {
                shown = spring(n, pct, 32f, 0.8f);
            }
            shown = Math.max(0f, Math.min(1f, shown));
            Theme.pill(ctx, sx, midY - 2, right, midY + 2, Theme.TRACK);
            Theme.pill(ctx, sx, midY - 2, sx + Math.max(4, Math.round(SLIDER_W * shown)), midY + 2,
                    Theme.lerpColor(Theme.RED, Theme.RED_BRIGHT, 0.4f * g));
            // Thumb: whole-pixel geometry around kx, moved the remaining fraction in device pixels.
            float fx = sx + SLIDER_W * shown;
            int kx = (int) Math.floor(fx);
            var km = ctx.getMatrices();
            km.pushMatrix();
            km.translate(Theme.snap(ctx, fx - kx), 0);
            if (!Theme.square) {
                Theme.disc(ctx, kx - 7, midY - 7, 14, Theme.alpha(Theme.RED, 0.22f * g));
                Theme.knobShadow(ctx, kx - 4, midY - 3, Theme.alpha(0xFF000000, 0.35f));
            }
            float ks = (8 + 2 * g) / 8f;
            km.pushMatrix();
            km.translate(kx, midY);
            km.scale(ks, ks);
            km.translate(-kx, -midY);
            Theme.disc(ctx, kx - 4, midY - 4, 8, 0xFFFFFFFF);
            km.popMatrix();
            if (drag) Theme.stroke(ctx, kx - 4, midY - 4, kx + 4, midY + 4, Theme.RED, 4);
            km.popMatrix();
            if (editor != null && editor.target == n) {
                int ew = Math.max(48, width(editor.text) + 14);
                editor.render(ctx, sx - 8 - ew, midY - 7, sx - 8, midY + 7);
            } else {
                String v = n.display();
                int vx = sx - 8 - width(v);
                boolean hv = rowHover && mx >= vx - 4 && mx < sx - 4;
                if (hv) Theme.roundBordered(ctx, vx - 4, midY - 7, sx - 4, midY + 7, Theme.CONTROL, Theme.BORDER);
                text(ctx, v, vx, midY - 4, hv ? Theme.TEXT : Theme.TEXT_DIM, false);
            }
            if (rowHover && mx >= sx - 8 - Math.max(48, width(n.display()) + 14) && mx <= right + 6) hoverSlider = n;
        } else if (row instanceof ModeSetting md) {
            String v = md.get();
            int w = Math.max(70, width(v) + 30);
            Theme.roundBordered(ctx, right - w, midY - 7, right, midY + 7, Theme.CONTROL, Theme.BORDER);
            icon(ctx, "chevron_l", right - w + 4, midY - 4, 7, Theme.RED);
            icon(ctx, "chevron_r", right - 11, midY - 4, 7, Theme.RED);
            text(ctx, v, right - w + (w - width(v)) / 2, midY - 4, Theme.TEXT, false);
        } else if (row instanceof StringSetting st) {
            boolean editable = editable(st);
            if (editor != null && editor.target == st) {
                int ew = Math.max(90, width(editor.text) + 16);
                editor.render(ctx, right - ew, midY - 7, right, midY + 7);
                return;
            }
            String v = st.get();
            int w = Math.max(60, width(v) + 16);
            boolean hv = editable && rowHover && mx >= right - w && mx < right;
            Theme.roundBordered(ctx, right - w, midY - 7, right, midY + 7, hv ? Theme.CONTROL_HOVER : Theme.CONTROL, hv ? Theme.BORDER_HOVER : Theme.BORDER);
            text(ctx, v, right - w + (w - width(v)) / 2, midY - 4, Theme.TEXT, false);
            String hint = editable ? "click to edit" : st.hint();
            int hw = width(Role.SMALL, hint);
            text(ctx, hint, right - w - 8 - hw, midY - 3, Theme.TEXT_FAINT, Role.SMALL);
        } else if (row instanceof ColorSetting c) {
            int n = ColorSetting.PALETTE.length, sz = 9, gap = 3;
            int sx = right - n * (sz + gap) + gap;
            boolean inPalette = false;
            for (int i = 0; i < n; i++) {
                int col = ColorSetting.PALETTE[i];
                int x = sx + i * (sz + gap);
                boolean sel = (c.get() | 0xFF000000) == col;
                swatch(ctx, x, midY, sz, col, sel);
                if (sel) inPalette = true;
            }
            if (editor != null && editor.target == c) {
                int ew = Math.max(62, width(editor.text) + 14);
                editor.render(ctx, sx - 8 - ew, midY - 7, sx - 8, midY + 7);
                return;
            }
            // Left of the palette: the current colour, ringed, when it isn't one of the swatches; otherwise a
            // small "#" chip. Either one opens a #RRGGBB field.
            int x = customChipX(sx);
            boolean hc = rowHover && mx >= x - 2 && mx < x + sz + 2;
            if (!inPalette) {
                swatch(ctx, x, midY, sz, c.get() | 0xFF000000, true);
            } else {
                Theme.roundBordered(ctx, x - 2, midY - 6, x + sz + 2, midY + 6, hc ? Theme.CONTROL_HOVER : Theme.CONTROL, hc ? Theme.BORDER_HOVER : Theme.BORDER);
                int hw = width("#");
                text(ctx, "#", x + (sz - hw) / 2 + 1, midY - 4, hc ? Theme.TEXT : Theme.TEXT_DIM, false);
            }
        }
    }

    /** x of the custom-colour chip left of a colour row's palette starting at {@code paletteX}. */
    private static int customChipX(int paletteX) { return paletteX - 3 - 8 - 9; }

    /**
     * One colour swatch, {@code sz} x 8 at x, centred on {@code midY}: a small rounded chip with a faint light
     * hairline, so near-black colours still show their edge on a dark row. The selected one gets a white ring with a
     * 1 px gap, in the same footprint the old white plate had.
     */
    private static void swatch(DrawContext ctx, int x, int midY, int sz, int color, boolean selected) {
        Theme.round(ctx, x, midY - 4, x + sz, midY + 4, color, Theme.R_XS);
        Theme.hairline(ctx, x, midY - 4, x + sz, midY + 4, Theme.alpha(0xFFFFFFFF, 0.12f), 2);
        if (selected) Theme.stroke(ctx, x - 2, midY - 6, x + sz + 2, midY + 6, 0xFFFFFFFF, 4);
    }

    // ------------------------------------------------------------------ tooltips

    private record TipLine(String text, int color) {}

    /** Show {@code lines} as this frame's tooltip next to the mouse. */
    private void setTip(int mx, int my, List<TipLine> lines) {
        tip = lines;
        tipX = mx;
        tipY = my;
    }

    /** What a settings row's tooltip says: its description, any key conflict, and how to use the control. */
    private List<TipLine> tipFor(Object row, Module mod) {
        List<TipLine> out = new ArrayList<>();
        String how = null;
        boolean resettable = false;
        if (row instanceof Setting<?> st) {
            String d = descriptionOf(st);
            if (!d.isBlank()) out.add(new TipLine(d, Theme.TEXT_SOFT));
            resettable = changed(st);
        }
        if (row.equals("keybind") || row instanceof KeySetting) {
            Object self = row.equals("keybind") ? mod : row;
            int code = self instanceof Module m ? m.getKey() : ((KeySetting) self).get();
            List<String> users = conflicts(code, self);
            if (!users.isEmpty()) out.add(new TipLine("Also used by: " + String.join(", ", users), Theme.DANGER));
            how = isMenuKey(self) ? "Click to change · right-click resets to Right Shift"
                    : acceptsMouse(self) ? "Click, then press a key or a middle/side mouse button · right-click clears"
                    : "Click to change · right-click clears";
        } else if (row instanceof NumberSetting) {
            how = "Drag, scroll or use ←/→ (Shift: ×10) · click the value to type it";
        } else if (row instanceof ModeSetting) {
            how = "Click the right half for the next option, the left half for the previous";
        } else if (row instanceof ColorSetting) {
            how = "Pick a swatch, or click # for any #RRGGBB colour";
        } else if (row instanceof StringSetting st && editable(st)) {
            how = "Click to edit · Enter saves, Esc cancels";
        }
        if (resettable) how = how == null ? "Middle-click to reset to default" : how + " · middle-click resets";
        if (how != null) out.add(new TipLine(how, Theme.TEXT_FAINT));
        return out;
    }

    /** Draw this frame's tooltip, if any: wrapped to 200 px, kept inside the window. */
    private void drawTip(DrawContext ctx) {
        if (tip == null || tip.isEmpty()) return;
        Role r = Role.LABEL;
        int maxW = 200, lineH = Math.max(8, Math.round(9 * (smooth ? r.scale : shown(r).scale)) + 2);
        List<TipLine> lines = new ArrayList<>();
        for (TipLine t : tip) for (String part : wrap(r, t.text(), maxW)) lines.add(new TipLine(part, t.color()));
        int w = 0;
        for (TipLine t : lines) w = Math.max(w, width(r, t.text()));
        int bw = w + 12, bh = lines.size() * lineH + 8;
        int x = tipX + 10, y = tipY + 12;
        if (x + bw > wx + ww - 4) x = tipX - 6 - bw;
        if (y + bh > wy + wh - 4) y = tipY - 6 - bh;
        x = Math.max(wx + 4, x);
        y = Math.max(wy + 4, y);
        Theme.roundBordered(ctx, x, y, x + bw, y + bh, Theme.CARD, Theme.BORDER_HOVER);
        int ty = y + 5;
        for (TipLine t : lines) {
            text(ctx, t.text(), x + 6, ty, t.color(), r);
            ty += lineH;
        }
    }

    /** {@code s} broken into lines at most {@code maxW} wide in role {@code r}. */
    private List<String> wrap(Role r, String s, int maxW) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (width(r, next) <= maxW || line.isEmpty()) {
                line.setLength(0);
                line.append(width(r, next) <= maxW ? next : trim(r, word, maxW));
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(width(r, word) <= maxW ? word : trim(r, word, maxW));
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        int realW = width, realH = height;
        width = Math.round(realW / vs); height = Math.round(realH / vs);
        try { return clicked(scaled(click), doubled); } finally { width = realW; height = realH; }
    }

    private boolean clicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        int button = click.button();
        if (menuKeyDown) menuKeyUsed = true;

        // An open text field: a click inside places the caret; anywhere else applies it, then the click goes on.
        if (editor != null) {
            if (editor.contains(mx, my)) { editor.clickAt(mx); return true; }
            commitEditor();
        }

        // Binding a key: middle/side buttons bind (where mouse buttons are allowed), right clears, left cancels.
        if (listening != null) {
            Object target = listening;
            if (button >= GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                if (!acceptsMouse(target)) reject(target, "Keys only here");
                else if (bindKey(target, KeySetting.mouseCode(button))) listening = null;
                return true;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) clearKey(target);
            listening = null;
            return true;
        }

        // close button
        if (mx >= wx + ww - 24 && mx < wx + ww - 10 && my >= wy + 12 && my < wy + 26) { close(); return true; }

        // scrollbar: drag the thumb, or page toward a click on the track
        if (button == 0 && onScrollbar(mx, my)) {
            int ty = thumbY();
            if (my >= ty && my < ty + thumbH()) {
                sbDrag = true;
                sbGrab = my - ty;
            } else {
                scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget + (my < ty ? -sbViewH : sbViewH)));
            }
            return true;
        }

        // sidebar
        if (mx >= wx && mx < wx + SIDEBAR && my >= wy && my < wy + wh) {
            if (my >= navTop() && my < navBottom()) {
                for (NavHit hit : navLayout()) {
                    if (my >= hit.y && my < hit.y + NAV_ITEM_H) {
                        if (hit.nav.hud) {
                            returnTo = null; // the editor comes back to this menu, not to where the menu came from
                            client.setScreen(new HudEditorScreen(this));
                        } else go(hit.nav.page);
                        return true;
                    }
                }
            }
            return true;
        }

        if (page == Page.PROFILES && selected == null) return clickProfiles(mx, my);

        if (selected != null || page == Page.SETTINGS) {
            // back button / master toggle on settings header
            if (selected != null && my >= searchY && my < searchY + 18 && mx >= cx && mx < cx + 52) { leaveModulePage(); return true; }
            if (selected != null && my >= headerY - 4 && my < headerY + 14 && mx >= controlRight() - 22 && mx < controlRight() + 2) {
                selected.toggle();
                return true;
            }
            Module rm = selected != null ? selected : settings();
            if (hasChangedSettings(rm) && my >= headerY - 4 && my < headerY + 12 && mx >= resetX() && mx < resetX() + 52) {
                for (Setting<?> st : rm.getSettings()) st.reset();
                if (rm instanceof HudElementModule hem) resetPosition(hem);
                return true;
            }
            return clickSettings(mx, my, button);
        }

        // search box
        int sw = searchW();
        searchFocused = mx >= cx && mx < cx + sw && my >= searchY && my < searchY + 24;
        if (searchFocused) return true;

        // All / Enabled toggle, and the A-Z sort chip
        int segY = headerY - 3;
        if (my >= segY && my < segY + 22 && mx >= segX() && mx < segX() + SEG_W) {
            enabledOnly = mx >= segX() + SEG_W / 2;
            scrollTarget = 0;
            return true;
        }
        if (my >= segY && my < segY + 22 && mx >= azX() && mx < azX() + AZ_W) {
            sortAz = !sortAz;
            scrollTarget = 0;
            return true;
        }

        // module list
        if (my < cy || my >= cy + ch || mx < cx || mx >= cx + cw) return super.mouseClicked(click, doubled);
        List<Module> mods = visibleModules();
        int cols = columns();
        int rh = rowH(), rg = rowGap();
        int cardW = (cw - rg * (cols - 1)) / cols;
        for (int i = 0; i < mods.size(); i++) {
            int x = cx + (i % cols) * (cardW + rg);
            int y = cy + (i / cols) * (rh + rg) - (int) Math.round(scroll);
            if (mx < x || mx >= x + cardW || my < y || my >= y + rh) continue;
            Module mod = mods.get(i);
            if (hasSettingsPage(mod) && (gearHit(mx, my, x, y, cardW) || button == 1)) {
                openModule(mod);
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
        // Only the visible list: the window padding, the backdrop and rows scrolled out of view never react.
        if (mx < cx || mx >= cx + cw || my < top || my >= cy + ch) return true;
        int y = top - (int) Math.round(scroll);
        int right = controlRight();
        for (Object row : rowsFor(mod)) {
            if (my >= y && my < y + ROW_H - 2) {
                // Middle-click puts one setting back to its default.
                if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                    if (row instanceof Setting<?> st && changed(st)) st.reset();
                    return true;
                }
                if (row.equals("keybind")) {
                    if (button == 1) clearKey(mod); else if (button == 0) listening = mod;
                } else if (row instanceof KeySetting k) {
                    if (button == 1) clearKey(k); else if (button == 0) listening = k;
                } else if (row instanceof StringSetting st) {
                    if (button != 0) return true;
                    if (st.clickAction() != null) st.clickAction().run();
                    else if (editable(st)) openEditor(st, st.get(), stringLimit(st), StringHelper::isValidChar);
                } else if (row instanceof BooleanSetting b) {
                    if (button == 0 || button == 1) b.toggle();
                } else if (row instanceof NumberSetting n) {
                    int sx = right - SLIDER_W;
                    int vx = sx - 8 - width(n.display());
                    if (button != 0) return true;
                    if (mx >= sx - 6 && mx <= right + 6) {
                        // Only the track and knob drag: a click on the label must not jump the value to its minimum.
                        dragging = n;
                        dragX = sx;
                        dragW = SLIDER_W;
                        updateSlider(mx);
                    } else if (mx >= vx - 4 && mx < sx - 4) {
                        openEditor(n, n.display(), 12, cp -> Character.isDigit(cp) || cp == '.' || cp == ',' || cp == '-');
                    }
                } else if (row instanceof ModeSetting md) {
                    int w = Math.max(70, width(md.get()) + 30);
                    // Only the chip cycles: right half forward, left half (or a right-click) back.
                    if ((button == 0 || button == 1) && mx >= right - w && mx <= right) md.cycle(button == 0 && mx >= right - w / 2.0);
                } else if (row instanceof ColorSetting c) {
                    if (button != 0) return true;
                    int n = ColorSetting.PALETTE.length, sz = 9, gap = 3;
                    int sx = right - n * (sz + gap) + gap;
                    int chip = customChipX(sx);
                    if (mx >= chip - 2 && mx < chip + sz + 2) {
                        openEditor(c, String.format("#%06X", c.get() & 0xFFFFFF), 7,
                                cp -> cp == '#' || Character.digit(cp, 16) >= 0);
                        return true;
                    }
                    int i = (int) Math.floor((mx - sx + gap / 2.0) / (sz + gap));
                    if (mx >= sx - 2 && i >= 0 && i < n) c.set(ColorSetting.PALETTE[i]);
                }
                return true;
            }
            y += ROW_H;
        }
        return true;
    }

    private void updateSlider(double mx) {
        double pct = Math.max(0, Math.min(1, (mx - dragX) / dragW));
        dragPct = (float) pct;
        dragging.set(dragging.getMin() + pct * (dragging.getMax() - dragging.getMin()));
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        if (dragging != null) { updateSlider(click.x() / vs); return true; }
        if (sbDrag) { dragScrollbar(click.y() / vs); return true; }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        dragging = null;
        sbDrag = false;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        double mx = mouseX / vs, my = mouseY / vs;
        if (menuKeyDown) menuKeyUsed = true;
        // Shift + wheel arrives as horizontal scrolling on macOS.
        double amount = v != 0 ? v : h;
        if (amount == 0) return true;
        if (mx >= wx && mx < wx + SIDEBAR && my >= wy && my < wy + wh) {
            navScroll = Math.max(0, Math.min(navMaxScroll(), navScroll - amount * 20));
            return true;
        }
        // Over a slider (or its value) the wheel steps the number: one step, ten with Shift.
        if (hoverSlider != null && editor == null && dragging == null) {
            nudge(hoverSlider, amount > 0 ? 1 : -1, client != null && client.isShiftPressed() ? 10 : 1);
            return true;
        }
        scrollTarget = Math.max(0, scrollTarget - v * 32);
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        // An open text field takes every key (Enter applies, Escape cancels).
        if (editor != null) { editor.key(input); return true; }
        if (listening != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) { listening = null; return true; } // cancel: the key stays as it was
            if (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) { clearKey(listening); listening = null; return true; }
            if (bindKey(listening, key)) listening = null;
            return true;
        }
        int menuKey = settings().menuKey.get();
        if (menuKeyDown && key != menuKey) menuKeyUsed = true;
        if (key == GLFW.GLFW_KEY_F && (input.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0) {
            // Ctrl+F (Cmd+F): jump to the search box from anywhere in the menu.
            returnTo = null;
            if (selected != null) back();
            if (page == Page.SETTINGS || page == Page.PROFILES) page = Page.ALL;
            searchFocused = true;
            return true;
        }
        boolean profiles = page == Page.PROFILES && selected == null;
        boolean listPage = selected == null && page != Page.SETTINGS && page != Page.PROFILES;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (profiles && (profileFocused || !profileInput.isEmpty())) { profileInput = ""; profileFocused = false; return true; }
            if (selected != null) { leaveModulePage(); return true; }
            if (!search.isEmpty()) { search = ""; searchFocused = false; scrollTarget = 0; return true; }
            close();
            return true;
        }
        if (input.isPaste() && (profiles || listPage)) {
            String clip = client != null ? clean(client.keyboard.getClipboard()) : "";
            if (profiles) {
                profileFocused = true;
                profileInput = cap(profileInput + clip, 24);
            } else {
                searchFocused = true;
                search = cap(search + clip, 32);
                scrollTarget = 0;
            }
            return true;
        }
        if (profiles) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (!profileInput.isEmpty()) { profileInput = profileInput.substring(0, profileInput.length() - 1); profileFocused = true; }
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                if (profileFocused) createProfile();
                return true;
            }
        }
        if (listPage && key == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
            search = search.substring(0, search.length() - 1);
            searchFocused = true;
            scrollTarget = 0;
            return true;
        }
        if (listPage && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && !search.isBlank()) {
            // Enter toggles the module when the search has narrowed the list down to one.
            List<Module> vis = visibleModules();
            if (vis.size() == 1) vis.get(0).toggle();
            return true;
        }
        if (hoverSlider != null && (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT)) {
            nudge(hoverSlider, key == GLFW.GLFW_KEY_RIGHT ? 1 : -1, input.hasShift() ? 10 : 1);
            return true;
        }
        if (hasScrollbar()) {
            switch (key) {
                case GLFW.GLFW_KEY_PAGE_UP -> { scrollTarget = Math.max(0, scrollTarget - sbViewH); return true; }
                case GLFW.GLFW_KEY_PAGE_DOWN -> { scrollTarget = Math.min(maxScroll(), scrollTarget + sbViewH); return true; }
                case GLFW.GLFW_KEY_HOME -> { scrollTarget = 0; return true; }
                case GLFW.GLFW_KEY_END -> { scrollTarget = maxScroll(); return true; }
                default -> { }
            }
        }
        if (key == menuKey) {
            boolean typing = searchFocused || profileFocused;
            if (isModifier(key)) {
                // Decided on release: a tap closes, Shift + letter (or + wheel, + click) doesn't. Held keys repeat,
                // so only the first press starts the tap.
                if (!menuKeyDown) {
                    menuKeyDown = true;
                    menuKeyUsed = typing;
                }
                return true;
            }
            if (!typing && !profiles) { close(); return true; }
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean keyReleased(KeyInput input) {
        if (menuKeyDown && input.key() == settings().menuKey.get()) {
            menuKeyDown = false;
            if (!menuKeyUsed && editor == null && listening == null) close();
            return true;
        }
        return super.keyReleased(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (menuKeyDown) menuKeyUsed = true;
        if (editor != null) { if (input.isValidChar()) editor.insert(input.asString()); return true; }
        if (listening != null) return true;
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

    private static boolean isModifier(int key) {
        return key >= GLFW.GLFW_KEY_LEFT_SHIFT && key <= GLFW.GLFW_KEY_RIGHT_SUPER;
    }

    /** Clipboard text as one line of valid characters. */
    private static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\r' || cp == '\t') b.append(' ');
            else if (StringHelper.isValidChar(cp)) b.appendCodePoint(cp);
        });
        return b.toString().replaceAll(" {2,}", " ").strip();
    }

    private static String cap(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }

    /** A number without a trailing ".0" (1024, 0.5). */
    private static String plain(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }

    // ---- key binding --------------------------------------------------

    private boolean isMenuKey(Object target) { return target == settings().menuKey; }

    /** Whether a mouse button can be bound here: module toggles and hold keys that allow it (never the Menu Key). */
    private static boolean acceptsMouse(Object target) {
        return target instanceof Module || (target instanceof KeySetting k && k.allowMouse);
    }

    /** Bind {@code code} to a module toggle or KeySetting. Refuses (and flashes the row) what can't work there. */
    private boolean bindKey(Object target, int code) {
        boolean valid = KeySetting.isMouse(code) ? KeySetting.isValidMouse(code) : code != GLFW.GLFW_KEY_UNKNOWN && KeySetting.isValidKey(code);
        if (!valid) { reject(target, "Can't bind that"); return false; }
        if (!isMenuKey(target) && code == settings().menuKey.get()) { reject(target, "That's the Menu Key"); return false; }
        if (target instanceof Module m) {
            m.setKey(code);
        } else if (target instanceof KeySetting k) {
            if (!k.accepts(code)) { reject(target, "Keys only here"); return false; }
            k.set(code);
        }
        rejectRow = null;
        return true;
    }

    /** Unbind (right-click, Delete). The Menu Key can't be unbound: it goes back to its default instead. */
    private void clearKey(Object target) {
        if (target instanceof Module m) m.setKey(GLFW.GLFW_KEY_UNKNOWN);
        else if (target instanceof KeySetting k) {
            if (isMenuKey(k)) k.reset(); else k.set(GLFW.GLFW_KEY_UNKNOWN);
        }
    }

    private void reject(Object target, String why) {
        rejectRow = target;
        rejectAt = clock.getAsLong();
        rejectMsg = why;
    }

    /** Everything else bound to {@code code}, for the red conflict chip and its tooltip. */
    private static List<String> conflicts(int code, Object self) {
        if (code == GLFW.GLFW_KEY_UNKNOWN) return List.of();
        return EvadeClient.modules.usersOf(code, self);
    }

    // ---- inline text fields -------------------------------------------

    private static boolean editable(StringSetting st) { return !(st instanceof StatusSetting) && st.clickAction() == null; }

    /** Longest value a text setting takes from the menu: the Nick Hider name is capped like {@code .nick}. */
    private static int stringLimit(StringSetting st) {
        var nh = EvadeClient.modules.get(net.evadeclient.module.visuals.NickHider.class);
        return nh != null && nh.nick == st ? 16 : 32;
    }

    private void openEditor(Setting<?> target, String initial, int max, IntPredicate allowed) {
        editor = new Editor(target, initial, max, allowed);
    }

    /** Apply the open field (Enter, or a click anywhere else) and save. An unchanged field changes nothing. */
    private void commitEditor() {
        Editor e = editor;
        editor = null;
        if (e == null || e.text.equals(e.initial)) return;
        boolean applied = false;
        switch (e.target) {
            case NumberSetting n -> {
                try {
                    double d = Double.parseDouble(e.text.trim().replace(',', '.'));
                    if (Double.isFinite(d)) {
                        n.set(d);
                        applied = true;
                        if (d < n.getMin() || d > n.getMax())
                            Notifications.push(n.getName(), "Allowed " + plain(n.getMin()) + " to " + plain(n.getMax()) + ": set to " + n.display(), false);
                    }
                } catch (NumberFormatException ignored) { }
            }
            case ColorSetting c -> {
                String hex = e.text.trim().replace("#", "");
                if (hex.length() == 3) hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
                if (hex.length() == 6) {
                    int rgb = Integer.parseInt(hex, 16);
                    int a = c.get() >>> 24 == 0 ? 0xFF000000 : c.get() & 0xFF000000;
                    c.set(a | rgb);
                    applied = true;
                }
            }
            case StringSetting st -> {
                String v = e.text.strip();
                if (!v.isEmpty()) { st.set(cap(v, e.max)); applied = true; }
            }
            default -> { }
        }
        if (applied) EvadeClient.config.save();
        else Notifications.push(e.target.getName(), "Not a valid value: kept " + e.target.display(), false);
    }

    /**
     * A one-line text field drawn in the menu's own font, opened on a settings row to type a number, a colour or a
     * short text value. Starts with everything selected, so typing replaces the value.
     */
    private final class Editor {
        final Setting<?> target;
        final String initial;
        final int max;
        final IntPredicate allowed;
        String text;
        int caret;
        boolean all = true;
        long typedAt = clock.getAsLong();
        int x1, y1, x2, y2;

        Editor(Setting<?> target, String initial, int max, IntPredicate allowed) {
            this.target = target;
            this.initial = initial;
            this.max = max;
            this.allowed = allowed;
            this.text = initial;
            this.caret = initial.length();
        }

        void render(DrawContext ctx, int x1, int y1, int x2, int y2) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            Theme.roundBordered(ctx, x1, y1, x2, y2, Theme.INPUT, Theme.RED);
            int tx = x1 + 6, ty = (y1 + y2) / 2 - 4;
            if (all && !text.isEmpty()) Theme.fill(ctx, tx - 1, ty - 1, tx + width(text) + 1, ty + 9, Theme.alpha(Theme.RED, 0.45f));
            text(ctx, text, tx, ty, Theme.TEXT, false);
            long now = clock.getAsLong();
            if (!all && ((now - typedAt) / 500) % 2 == 0) {
                int cxp = tx + width(text.substring(0, caret));
                Theme.fill(ctx, cxp, ty - 1, cxp + 1, ty + 9, Theme.TEXT);
            }
        }

        boolean contains(double mx, double my) { return mx >= x1 && mx < x2 && my >= y1 && my < y2; }

        /** Put the caret at the gap nearest to {@code mx}. */
        void clickAt(double mx) {
            all = false;
            int tx = x1 + 6, best = 0;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i <= text.length(); i++) {
                double d = Math.abs(tx + width(text.substring(0, i)) - mx);
                if (d < bestD) { bestD = d; best = i; }
            }
            caret = best;
            typedAt = clock.getAsLong();
        }

        void insert(String s) {
            StringBuilder ok = new StringBuilder();
            s.codePoints().filter(allowed).forEach(ok::appendCodePoint);
            if (ok.isEmpty()) return;
            if (all) { text = ""; caret = 0; all = false; }
            String add = cap(ok.toString(), Math.max(0, max - text.length()));
            text = text.substring(0, caret) + add + text.substring(caret);
            caret += add.length();
            typedAt = clock.getAsLong();
        }

        void key(KeyInput in) {
            int k = in.key();
            typedAt = clock.getAsLong();
            if (in.isEscape()) { editor = null; return; }
            if (in.isEnter()) { commitEditor(); return; }
            if (in.isSelectAll()) { all = true; return; }
            if (in.isCopy() || in.isCut()) {
                if (client != null) client.keyboard.setClipboard(text);
                if (in.isCut()) { text = ""; caret = 0; all = false; }
                return;
            }
            if (in.isPaste()) { if (client != null) insert(clean(client.keyboard.getClipboard())); return; }
            switch (k) {
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    if (all) { text = ""; caret = 0; all = false; }
                    else if (caret > 0) {
                        int from = in.hasCtrlOrCmd() ? 0 : caret - 1;
                        text = text.substring(0, from) + text.substring(caret);
                        caret = from;
                    }
                }
                case GLFW.GLFW_KEY_DELETE -> {
                    if (all) { text = ""; caret = 0; all = false; }
                    else if (caret < text.length()) text = text.substring(0, caret) + text.substring(caret + 1);
                }
                case GLFW.GLFW_KEY_LEFT -> { caret = all ? 0 : Math.max(0, caret - 1); all = false; }
                case GLFW.GLFW_KEY_RIGHT -> { caret = all ? text.length() : Math.min(text.length(), caret + 1); all = false; }
                case GLFW.GLFW_KEY_HOME -> { caret = 0; all = false; }
                case GLFW.GLFW_KEY_END -> { caret = text.length(); all = false; }
                default -> { }
            }
        }
    }

    // ---- number steps -------------------------------------------------

    /**
     * Move {@code n} by {@code steps} of its step size in direction {@code dir}. The step is found by asking the
     * setting itself: the smallest offset it doesn't snap back is exactly one step (it rounds to the nearest step).
     */
    private static void nudge(NumberSetting n, int dir, int steps) {
        double range = n.getMax() - n.getMin();
        if (!(range > 0)) return;
        for (int s = 0; s < steps; s++) {
            double v0 = n.get();
            boolean moved = false;
            for (double d = range * 1e-7; d <= range * 2; d *= 2) {
                n.set(v0 + dir * d);
                if (Math.abs(n.get() - v0) > range * 1e-9) { moved = true; break; }
            }
            if (!moved) { n.set(v0); return; }
        }
    }

    // ---- navigation -----------------------------------------------------

    private void openModule(Module mod) {
        commitEditor();
        listening = null;
        selected = mod;
        scroll = scrollTarget = 0;
    }

    private void go(Page p) {
        commitEditor();
        listening = null;
        page = p;
        selected = null;
        returnTo = null;
        search = "";
        searchFocused = false;
        profileInput = "";
        profileFocused = false;
        pendingDelete = null;
        scroll = scrollTarget = 0;
    }

    private void back() {
        commitEditor();
        listening = null;
        selected = null;
        scroll = scrollTarget = 0;
    }

    /** Escape or Back on a module page: back to the screen that opened it (the HUD editor), else to the list. */
    private void leaveModulePage() {
        if (returnTo != null && client != null) {
            commitEditor();
            EvadeClient.config.save();
            Screen to = returnTo;
            returnTo = null;
            client.setScreen(to);
            return;
        }
        back();
    }

    @Override
    public void close() {
        commitEditor();
        EvadeClient.config.save();
        if (returnTo != null && client != null) {
            Screen to = returnTo;
            returnTo = null;
            client.setScreen(to);
            return;
        }
        super.close();
    }

    @Override
    public void removed() {
        commitEditor(); // e.g. the HUD editor opened over a half-typed value
        super.removed();
    }

    @Override
    public boolean shouldPause() { return false; }

    // ------------------------------------------------------------------ helpers

    /** An animation key for one control part of a setting (e.g. a slider's hover glow): unlike a "kind:" + name
     *  string it never collides with a same-named setting of another module. */
    private record AnimKey(String kind, Object target) {}

    private float animate(Object key, float target, float speed) {
        float v = anim.getOrDefault(key, target);
        if (!anims) v = target;
        else v += (target - v) * (1 - (float) Math.exp(-speed * dt));
        anim.put(key, v);
        return v;
    }

    /**
     * The spring for {@code key} stepped one frame toward {@code target} ({@code w} stiffness in rad/s, {@code z}
     * damping ratio; see {@link Motion.Spring}). With Animations off it jumps to the target and returns it.
     */
    private float spring(Object key, float target, float w, float z) {
        Motion.Spring s = springObj(key);
        if (!anims) { s.set(target); return target; }
        return s.step(target, w, z, dt);
    }

    /** The spring for {@code key}, created on first use (it starts on whatever target it is first stepped to). */
    private Motion.Spring springObj(Object key) {
        return springs.computeIfAbsent(key, k -> new Motion.Spring());
    }

    private static float ease(float t) { return 1 - (float) Math.pow(1 - t, 3); }

    // ------------------------------------------------------------------ text: UI roles (see UiFont)
    // Every string the menu draws goes through these, in a UiFont role, and no text is matrix-scaled. Smooth Font on:
    // each role is Inter at its own size, rasterised 1:1 with the screen pixels the menu is drawn at. Off: each role
    // is Minecraft's font at the scale the menu always drew it. A role's font shift puts its baseline where the old
    // scaled line had it, so every (x, y) here is a top-left as before.

    /** Device pixels per menu pixel as the smooth font files to use (1..6): the GUI scale, or less while the menu is
     *  drawn smaller to fit the screen. Its glyphs then map one texel to one screen pixel. */
    private int fontG() {
        return Math.max(UiFont.MIN_G, Math.min(UiFont.MAX_G, (int) Math.round(menuPx)));
    }

    /**
     * The role drawn for {@code r}. At one screen pixel per menu pixel (a small or high-DPI screen) the smallest
     * roles would be too small to read: Inter's 6 px roles step up to their 7 px siblings, and the vanilla font never
     * shrinks below its own size, since a scaled pixel font drops rows there.
     */
    private Role shown(Role r) {
        if (menuPx >= 1.99) return r;
        if (!smooth) return r.scale < 1f ? Role.BODY : r;
        return r == Role.TINY ? Role.SMALL : r == Role.CAPS_SM ? Role.CAPS : r;
    }

    /** {@code s} in role {@code r}'s Inter font for the menu's screen pixels. */
    private Text smoothText(String s, Role r) {
        return Text.literal(s).setStyle(UiFont.style(shown(r), fontG()));
    }

    /** Draw {@code s} in role {@code r} with its top-left at (x, y). */
    private void text(DrawContext ctx, String s, int x, int y, int color, Role r) {
        if (s == null || s.isEmpty()) return;
        if (!smooth) {
            UiFont.draw(ctx, textRenderer, s, x, y, color, shown(r), false);
            return;
        }
        int c = Theme.fa(color);
        if (c >>> 24 != 0) ctx.drawText(textRenderer, smoothText(s, r), x, y, c, false);
    }

    /** Draw {@code s} as body text (nav labels, setting rows, control values). */
    private void text(DrawContext ctx, String s, int x, int y, int color, boolean shadow) {
        if (!shadow) {
            text(ctx, s, x, y, color, Role.BODY);
            return;
        }
        if (s == null || s.isEmpty()) return;
        int c = Theme.fa(color);
        if (c >>> 24 != 0) ctx.drawText(textRenderer, smooth ? smoothText(s, Role.BODY) : Text.literal(s), x, y, c, true);
    }

    /** The width {@code s} takes in role {@code r}, in menu pixels. */
    private int width(Role r, String s) {
        if (s == null || s.isEmpty()) return 0;
        return smooth ? textRenderer.getWidth(smoothText(s, r)) : UiFont.width(textRenderer, s, shown(r), false);
    }

    private int width(String s) { return width(Role.BODY, s); }

    /**
     * Draw {@code s} upper-cased in role {@code r} with {@code tracking} extra pixels between letters: a small-caps
     * header. Smooth: each letter at its true advance plus the tracking, snapped to a screen pixel so none is blurred
     * across two. Otherwise the letters spaced out in the vanilla font, as the menu always spaced its headers. With
     * no tracking it is plain upper-case text.
     */
    private void caps(DrawContext ctx, String s, int x, int y, int color, Role r, float tracking) {
        if (s == null || s.isEmpty()) return;
        String up = s.toUpperCase(Locale.ROOT);
        if (tracking == 0f) {
            text(ctx, up, x, y, color, r);
            return;
        }
        if (!smooth) {
            UiFont.draw(ctx, textRenderer, UiFont.spaced(up), x, y, color, shown(r), false);
            return;
        }
        int c = Theme.fa(color);
        if (c >>> 24 == 0) return;
        Style st = UiFont.style(shown(r), fontG());
        double px = Math.max(0.25, menuPx);
        var m = ctx.getMatrices();
        float pen = 0;
        for (int i = 0; i < up.length(); ) {
            int cp = up.codePointAt(i);
            i += Character.charCount(cp);
            Text ch = Text.literal(Character.toString(cp)).setStyle(st);
            if (!Character.isWhitespace(cp)) {
                m.pushMatrix();
                try {
                    m.translate((float) (Math.round((x + pen) * px) / px), y);
                    ctx.drawText(textRenderer, ch, 0, 0, c, false);
                } finally {
                    m.popMatrix();
                }
            }
            pen += textRenderer.getTextHandler().getWidth(ch) + tracking;
        }
    }

    /** Longest prefix of {@code s} that fits in {@code max} pixels in role {@code r} (never splitting a surrogate pair). */
    private String trim(Role r, String s, int max) {
        if (width(r, s) <= max) return s;
        int lo = 0, hi = s.length();
        while (lo < hi) { int mid = (lo + hi + 1) / 2; if (width(r, s.substring(0, mid)) <= max) lo = mid; else hi = mid - 1; }
        if (lo > 0 && Character.isHighSurrogate(s.charAt(lo - 1))) lo--;
        return s.substring(0, lo);
    }

    /** Longest prefix of {@code s} that fits in {@code max} pixels as body text. */
    private String trim(String s, int max) { return trim(Role.BODY, s, max); }

    /** {@code s} cut to fit {@code max} pixels in role {@code r}, ending in "..." when it had to be cut. */
    private String ellipsize(Role r, String s, int max) {
        if (s == null || width(r, s) <= max) return s;
        int dots = width(r, "...");
        if (max <= dots) return "";
        return trim(r, s, max - dots).stripTrailing() + "...";
    }

    /** Longest suffix of {@code s} that fits in {@code max} pixels: a text field keeps the end you are typing in view. */
    private String trimStart(String s, int max) {
        if (width(s) <= max) return s;
        int lo = 0, hi = s.length();
        while (lo < hi) { int mid = (lo + hi) / 2; if (width(s.substring(mid)) <= max) hi = mid; else lo = mid + 1; }
        return s.substring(lo);
    }

    // ------------------------------------------------------------------ icons

    /** A white line icon from textures/gui/nav, tinted and drawn at {@code size} GUI pixels (see {@link net.evadeclient.util.Icons}). */
    private void icon(DrawContext ctx, String name, int x, int y, int size, int color) {
        net.evadeclient.util.Icons.draw(ctx, name, x, y, size, color);
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

    /** x of the header's Reset button: left of the master toggle on a module page, at the right on Settings. */
    private int resetX() { return cx + cw - (selected != null ? 92 : 52); }

    /** Whether a shown setting differs from its default (hidden settings are remembered state, not changes). */
    private static boolean hasChangedSettings(Module mod) {
        for (Setting<?> st : mod.getSettings()) if (changed(st)) return true;
        return false;
    }

    /** A shown, editable setting that is not at its default value. */
    private static boolean changed(Setting<?> st) {
        return !st.isHidden() && !(st instanceof StatusSetting) && !st.isDefault();
    }

    // ------------------------------------------------------------------ newer APIs, used when present
    // Setting descriptions, server-address normalisation and HUD position reset are added by other parts of the
    // client. Looked up once by name so the menu works (without them) on any build; replace with direct calls once
    // they have landed everywhere.

    private static java.lang.reflect.Method describeMethod, isLinkedMethod, resetPositionMethod;
    private static boolean describeLooked, isLinkedLooked, resetPositionLooked;

    /** A setting's description ({@code Setting.getDescription()}), or "" when it has none. */
    private static String descriptionOf(Setting<?> st) {
        if (!describeLooked) {
            describeLooked = true;
            try { describeMethod = Setting.class.getMethod("getDescription"); } catch (ReflectiveOperationException ignored) { }
        }
        if (describeMethod == null) return "";
        try {
            Object d = describeMethod.invoke(st);
            return d instanceof String s ? s : "";
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }

    /** Whether {@code profile} is linked to {@code address}, with addresses compared the way ProfileManager stores them. */
    private static boolean isLinked(String profile, String address) {
        var profiles = EvadeClient.profiles;
        if (!isLinkedLooked) {
            isLinkedLooked = true;
            try { isLinkedMethod = profiles.getClass().getMethod("isLinked", String.class, String.class); } catch (ReflectiveOperationException ignored) { }
        }
        if (isLinkedMethod != null) {
            try { return Boolean.TRUE.equals(isLinkedMethod.invoke(profiles, profile, address)); } catch (ReflectiveOperationException ignored) { }
        }
        return profiles.serversFor(profile).contains(address.trim().toLowerCase(Locale.ROOT));
    }

    /** Put a HUD element back at its default spot ({@code HudElementModule.resetPosition()}), when available. */
    private static void resetPosition(HudElementModule hem) {
        if (!resetPositionLooked) {
            resetPositionLooked = true;
            try { resetPositionMethod = HudElementModule.class.getMethod("resetPosition"); } catch (ReflectiveOperationException ignored) { }
        }
        if (resetPositionMethod == null) return;
        try { resetPositionMethod.invoke(hem); } catch (ReflectiveOperationException ignored) { }
    }
}
