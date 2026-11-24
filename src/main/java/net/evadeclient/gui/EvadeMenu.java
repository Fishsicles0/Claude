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
        PROFILES("Profiles", null), FRIENDS("Friends", null), SETTINGS("Settings", Category.CLIENT), PINNED("Pinned", null);

        final String label;
        final Category category;

        Page(String label, Category category) { this.label = label; this.category = category; }
    }

    /** A sidebar entry: a category page, or the special Edit-HUD action (page == null, hud == true). */
    private record Nav(String label, Page page, String icon, boolean hud, Runnable action) {
        Nav(String label, Page page, String icon, boolean hud) { this(label, page, icon, hud, null); }
    }
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
                    new Nav("Settings", Page.SETTINGS, "settings", false),
                    new Nav("Keybinds", null, "keyboard", false, KeybindsScreen::openFromCurrent),
                    new Nav("World Map", null, "map", false, net.evadeclient.gui.WorldMapScreen::openFromMenu),
                    new Nav("Pinned", Page.PINNED, "star", false),
                    new Nav("Mod Browser", null, "mods", false, net.evadeclient.gui.ModBrowserScreen::open),
                    new Nav("Quick Play", null, "server", false, net.evadeclient.module.misc.QuickPlay::open),
                    new Nav("Screenshots", null, "camera", false, net.evadeclient.gui.ScreenshotGalleryScreen::openFromCurrent),
                    new Nav("Friends", Page.FRIENDS, "friends", false))));

    private static Page page = Page.ALL;
    private static Module selected;
    /** Sort the module list A-Z instead of the curated order (kept for the session). */
    private static boolean sortAz;
    private boolean enabledOnly;
    /** The screen to go back to when the menu was opened from another one (the HUD editor's right-click). */
    private Screen returnTo;

    private String search = "";
    private boolean searchFocused;
    /** The content list's scroll: {@code scroll} is where it is drawn this frame (the "scroll" spring, critically
     *  damped, so wheel notches in a row blend into one glide), {@code scrollTarget} where it is headed. */
    private double scroll, scrollTarget;
    /** How far the current page's content can scroll, written by its clamp each frame ({@link #clampScroll}). */
    private int lastMaxScroll;
    /** When the content was last scrolled by the wheel, keys or the scrollbar ({@link #clock} ms): the scrollbar's
     *  thumb stays fully lit for {@link #SCROLLBAR_LIT_MS} after. */
    private double lastScrollAt = -1e9;
    /** When the menu was last put on screen ({@link #clock} ms): the intro animation runs from here. */
    private double openedAt;
    /** When the close fade started ({@link #clock} ms), or -1 while the menu is open. */
    private double closingAt = -1;
    /** The close fade has played and the real close is queued (or done), so it is only queued once. */
    private boolean finishing;
    private final Map<Object, Float> anim = new HashMap<>();
    /** Springs by key (see {@link #spring}): highlights, scroll and sliders that chase a moving target. */
    private final Map<Object, Motion.Spring> springs = new HashMap<>();
    /** Client Settings' Animations, read once per frame at the top of {@link #render}. */
    private boolean anims = true;
    /** Client Settings' Smooth Font, read once per frame at the top of {@link #render}, so clicks measure text the
     *  way the frame they land on drew it. */
    private boolean smooth = true;
    private static final long CLOCK_ORIGIN = System.nanoTime();
    /** The real animation clock: milliseconds since the client loaded, monotonic (never jumps with the wall clock)
     *  and finer than a millisecond, so frame times stay even at high frame rates. */
    public static final java.util.function.DoubleSupplier SYSTEM_CLOCK = () -> (System.nanoTime() - CLOCK_ORIGIN) / 1e6;
    /** Milliseconds for animations (the menu's, Zoom's). Tests swap in a fixed-step clock to record smooth clips for
     *  the website, and put {@link #SYSTEM_CLOCK} back after. */
    public static java.util.function.DoubleSupplier clock = SYSTEM_CLOCK;
    private double lastFrame = clock.getAsDouble();
    private float dt;

    /** The button or control under a held mouse press (see {@link #button}), or null: its shape darkens and dips while
     *  the button is down. Set at each resolved hit in mouseClicked ({@link #press}), cleared in mouseReleased. */
    private String pressedKey;
    /** The last press and when it began ({@link #clock} ms): a quick tap still shows its dip for
     *  {@link #PRESS_MIN_MS}, even when the release comes before the next frame. */
    private String tapKey;
    private double pressedAt = -1e9;
    /** Key boxes that just took a key (or were cleared), by row: when, for their confirming flash. */
    private final Map<Object, Double> keyFlashAt = new HashMap<>();
    /** A mode picker's last cycle: the value it left, which way it went (+1 forward, -1 back) and when. */
    private record ModeSwap(String prev, int dir, double at) {}
    private final Map<ModeSetting, ModeSwap> modeSwaps = new HashMap<>();

    /** The module (keybind) or KeySetting waiting for a key press. */
    private Object listening;
    /** When {@link #listening} started ({@link #clock} ms): its box's pulse and counting dots start from here. */
    private double listenAt;
    /** A key the capture refused (e.g. the Menu Key for a module): the row flashes red with {@link #rejectMsg}. */
    private Object rejectRow;
    private double rejectAt;
    private String rejectMsg = "";
    private NumberSetting dragging;
    private int dragX, dragW;
    /** Where the cursor is along the dragged slider (0..1), unstepped: the thumb follows it exactly while the value
     *  snaps to its step, then glides onto the stepped value on release. */
    private float dragPct;
    /** The text field open on a settings row (a number, a colour or a short text value), or null. */
    private Editor editor;
    /** The colour picker pop-over (open over a colour row's custom chip); it gets input before everything else. */
    public final ColorPicker picker = new ColorPicker();

    /** A modifier Menu Key (e.g. Right Shift) closes the menu when it is released, if nothing else was pressed,
     *  typed, clicked or scrolled meanwhile: Shift + letter types a capital and Shift + wheel steps by 10. */
    private boolean menuKeyDown, menuKeyUsed;

    // scrollbar of the current page (set while drawing it) and its drag state: sbGrab is where on the thumb it was
    // grabbed, so the thumb stays under the cursor
    private int sbTop, sbViewH, sbContentH;
    private boolean sbDrag;
    private double sbGrab;

    // sidebar scrolling: only used when the nav list can't fit (it always fits at the menu's design size)
    private double navScroll;
    /** Whether the sidebar drew its active-page highlight last frame (false on a mod's settings page). */
    private boolean navPillShown;

    /** When the content last changed page ({@link #clock} ms): it fades through from here (see {@link #PAGE_MS}).
     *  Far in the past until the first change, so a menu opened on the list shows it at rest. */
    private double pageAt = -1e9;
    /** Which way that change went: +1 a mod's settings opened (slides in from the right), -1 Back (from the left),
     *  0 another page (rises into place). */
    private int pageDir;

    // settings-row hover: the slider under the mouse (wheel and arrow keys), and the row the tooltip is for
    private NumberSetting hoverSlider;
    private Object tipRow;
    private double tipSince;
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
    /** The window's intro (it fades in, then finishes growing and rising into place) and its close fade, in ms. */
    private static final double OPEN_FADE_MS = 140, OPEN_MOVE_MS = 200, CLOSE_MS = 120;
    /** A page change: the new content fades in over this (ease out) while it slides {@link #PAGE_SLIDE} px sideways
     *  (a mod's settings, Back) or rises {@link #PAGE_RISE} px (another sidebar page) into place. */
    private static final double PAGE_MS = 180;
    private static final float PAGE_SLIDE = 12f, PAGE_RISE = 6f;
    /** The scroll spring: stiffness (rad/s) and damping ratio. Critically damped: about 95% of the way in 170 ms
     *  and never past the target, so the list never overshoots its ends. */
    private static final float SCROLL_W = 28f, SCROLL_Z = 1f;
    /** How long the scrollbar's thumb stays fully lit after a scroll before it dims, in ms. */
    private static final double SCROLLBAR_LIT_MS = 800;
    /** The content list's soft edges: {@link #EDGE_FADE} px tall, fully in once the list is {@link #EDGE_RAMP} px
     *  from that end. */
    private static final int EDGE_FADE = 12, EDGE_RAMP = 24;
    /** Shortest time a press shows its dip, so a quick tap still reads as one (ms). */
    private static final double PRESS_MIN_MS = 80;
    /** A key box's confirming flash when it takes a key, and a mode picker's value slide on a cycle (ms). */
    private static final double KEY_FLASH_MS = 250, MODE_SWAP_MS = 150;

    public EvadeMenu() {
        super(Text.literal("Evade"));
        selected = null; // opening the menu always starts on the list, not wherever it was last
    }

    /** Test hook: jump the menu straight to the Profiles page. */
    public static void openProfilesForTest() { page = Page.PROFILES; selected = null; }
    /** Test hook: back to the All mods page (what a fresh menu shows). */
    public static void openAllForTest() { page = Page.ALL; selected = null; }

    /** Test hook: jump the menu straight to the Friends page, on one of its tabs (0 Friends, 1 Requests, 2 Party, 3 Chat, 4 Settings). */
    public static void openFriendsForTest(int tab) {
        page = Page.FRIENDS;
        selected = null;
        friendsTab = FTab.values()[Math.max(0, Math.min(FTab.values().length - 1, tab))];
    }

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

    /**
     * Each time the menu is put on screen (MinecraftClient.setScreen calls this once per display, before init): the
     * intro starts here. Not in init, which a window resize runs again, so resizing doesn't replay it; coming back
     * from the HUD editor does.
     */
    @Override
    public void onDisplayed() {
        super.onDisplayed();
        openedAt = clock.getAsDouble();
        lastFrame = openedAt; // springs and fades pick up from here, not from before the menu was away
        closingAt = -1;
        finishing = false;
        pressedKey = tapKey = null; // a press from before the menu was away never shows
        // Shown straight onto a mod's settings (open(select), or back from the HUD editor): the page slides in from
        // the right with the intro, as if it had just been opened from the list.
        if (selected != null) { pageAt = openedAt; pageDir = 1; }
    }

    @Override
    protected void init() {
        anims = settings().animations.get();
        smooth = settings().smoothFont.get();
    }

    private ClientSettings settings() { return EvadeClient.modules.get(ClientSettings.class); }

    // ------------------------------------------------------------------ rendering

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        settings().applyTheme();
        anims = settings().animations.get(); // this runs before render, which reads it again
        if (settings().blur.get()) super.renderBackground(ctx, mouseX, mouseY, delta);
        // The tint fades in and out with the window (the vanilla blur can't fade: it is on or off).
        float a = windowState(clock.getAsDouble()).alpha();
        ctx.fillGradient(0, 0, width, height, Theme.alpha(0xB0000000, a),
                Theme.alpha(0xD0000000 | (Theme.lerpColor(0xFF000000, Theme.RED, 0.09f) & 0xFFFFFF), a));
    }

    /** How the whole window looks this frame: its opacity, its scale about the screen centre, and how far below its
     *  resting place it sits (menu pixels). */
    private record WindowState(float alpha, float scale, float dy) {}

    private static final WindowState AT_REST = new WindowState(1f, 1f, 0f);
    /** Where the window was when the close fade started: at rest, or part way through its intro (a quick open and
     *  close), so the close carries on from there instead of first jumping back to fully shown. */
    private WindowState closeFrom = AT_REST;

    /**
     * The window's open or close animation at {@code now} ({@link #clock} ms). Opening: it fades in over 140 ms while
     * it grows from 97% and rises 10 px into place over 200 ms (both ease out). Closing: it fades out over 120 ms,
     * shrinking 1.5% and sinking 6 px (ease in). With Animations off it is simply there. Both renderBackground and
     * render ask, so the order they run in doesn't matter.
     */
    private WindowState windowState(double now) {
        if (closingAt >= 0) {
            // The clock went back (a test swapped it): fade out from here, unless the fade is already done.
            if (now < closingAt && !finishing) closingAt = now;
            float c = finishing ? 1f : Motion.cubicIn((float) ((now - closingAt) / CLOSE_MS));
            WindowState f = closeFrom;
            return new WindowState(f.alpha() * (1f - c), f.scale() + (0.985f - f.scale()) * c, f.dy() + (6f - f.dy()) * c);
        }
        if (!anims) return AT_REST;
        if (now < openedAt) openedAt = now - OPEN_MOVE_MS; // the clock went back: show the menu, never hide it
        float a = Motion.cubicOut((float) ((now - openedAt) / OPEN_FADE_MS));
        float p = Motion.cubicOut((float) ((now - openedAt) / OPEN_MOVE_MS));
        return p >= 1f && a >= 1f ? AT_REST : new WindowState(a, 0.97f + 0.03f * p, (1f - p) * 10f);
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
            // Under the colour picker nothing reacts to the mouse.
            int hx = picker.isOpen() ? -10000 : mouseX, hy = picker.isOpen() ? -10000 : mouseY;
            renderMenu(ctx, (int) Math.floor(hx / vs), (int) Math.floor(hy / vs), delta);
        } finally {
            mm.popMatrix();
            width = realW;
            height = realH;
        }
        picker.render(ctx, mouseX, mouseY, width, height);
    }

    private void renderMenu(DrawContext ctx, int mouseX, int mouseY, float delta) {
        double now = clock.getAsDouble();
        dt = (float) Math.max(0, Math.min(0.1, (now - lastFrame) / 1000));
        lastFrame = now;
        // The list glides to its target on a critically damped spring (wheel notches in a row blend into one glide,
        // and it never runs past the end); with Animations off it is simply there.
        scroll = spring("scroll", (float) scrollTarget, SCROLL_W, SCROLL_Z);
        // Per-frame hover state, filled in by whichever page draws below.
        sbContentH = 0;
        hoverSlider = null;
        tip = null;
        if (pendingDelete != null && now - pendingDeleteAt > DELETE_CONFIRM_MS) pendingDelete = null;
        if (friendConfirm != null && now - friendConfirmAt > DELETE_CONFIRM_MS) friendConfirm = null;

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

        // The window opens and closes as one piece: scaled about the screen centre, moved down by dy (snapped to a
        // device pixel), and every colour it draws multiplied by its opacity through Theme.fade, which Theme, UiFont,
        // Icons and Logo all honour. The fade is put back in the finally: the HUD and toasts never inherit it.
        WindowState ws = windowState(now);
        var m = ctx.getMatrices();
        float base = Theme.fade;
        m.pushMatrix();
        try {
            m.translate(width / 2f, height / 2f + Theme.snap(ctx, ws.dy()));
            m.scale(ws.scale(), ws.scale());
            m.translate(-width / 2f, -height / 2f);

            // Window: a soft drop shadow and a faint accent halo behind the body, then the sidebar, then the 1 px ring
            // over both, so it also covers the sidebar's rounded corners. While the window fades, the shadow and halo
            // fade with the square of its opacity: the body is slightly see-through, and at the same opacity they
            // would tint the whole window from underneath.
            Theme.fade = base * ws.alpha() * ws.alpha();
            windowShadow(ctx, wx, wy + 6, wx + ww, wy + wh + 6, Theme.alpha(0xFF000000, 0.6f));
            windowShadow(ctx, wx, wy, wx + ww, wy + wh, Theme.alpha(Theme.RED, 0.2f));
            Theme.fade = base * ws.alpha();
            Theme.round(ctx, wx, wy, wx + ww, wy + wh, Theme.BG, Theme.R_WIN);
            renderSidebar(ctx, mouseX, mouseY);
            Theme.stroke(ctx, wx, wy, wx + ww, wy + wh, Theme.BORDER, Theme.R_WIN);
            renderClose(ctx, mouseX, mouseY);

            // The content area fades through on a page change: the new page fades in while it slides in sideways
            // (a mod's settings from the right, Back from the left) or rises into place (a sidebar page). The old
            // page is not drawn again and clicks are not held back. The sidebar and close button stay put. The
            // pages' scissor rects are transformed by the matrix, so their clips slide with them.
            float pt = pageProgress(now);
            float pageBase = Theme.fade;
            boolean moving = pt < 1f;
            if (moving) {
                Theme.fade = pageBase * pt;
                m.pushMatrix();
                m.translate(Theme.snap(ctx, pageDir * (1f - pt) * PAGE_SLIDE),
                        pageDir == 0 ? Theme.snap(ctx, (1f - pt) * PAGE_RISE) : 0f);
            }
            try {
                if (selected != null) renderSettingsPage(ctx, mouseX, mouseY, selected);
                else if (page == Page.SETTINGS) renderSettingsPage(ctx, mouseX, mouseY, settings());
                else if (page == Page.PROFILES) renderProfilesPage(ctx, mouseX, mouseY);
                else if (page == Page.FRIENDS) renderFriendsPage(ctx, mouseX, mouseY);
                else {
                    renderSearch(ctx, mouseX, mouseY);
                    renderHeader(ctx, mouseX, mouseY);
                    renderGrid(ctx, mouseX, mouseY);
                }
            } finally {
                if (moving) {
                    m.popMatrix();
                    Theme.fade = pageBase;
                }
            }
            drawTip(ctx);
        } finally {
            Theme.fade = base;
            m.popMatrix();
        }

        // The close fade has played: close for real, after this frame (setScreen must never run mid-render).
        if (closingAt >= 0 && !finishing && now - closingAt >= CLOSE_MS) queueFinishClose();
    }

    /** How far the current page's fade-through is at {@code now} ({@link #clock} ms): 0 just changed, 1 at rest
     *  (ease out over {@link #PAGE_MS}). Always 1 with Animations off. */
    private float pageProgress(double now) {
        if (!anims) return 1f;
        if (now < pageAt) pageAt = now - PAGE_MS; // the clock went back (a test swapped it): show the page, never hide it
        return Motion.cubicOut((float) ((now - pageAt) / PAGE_MS));
    }

    /** The content just changed page: it fades through from now, sliding in the direction {@code dir} (see
     *  {@link #pageDir}). */
    private void pageChanged(int dir) {
        pageAt = clock.getAsDouble();
        pageDir = dir;
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
        int listTop = navTop() - (int) Math.round(navScroll);
        renderNavHighlight(ctx, listTop);
        int y = listTop;
        for (NavGroup g : GROUPS) {
            if (g.header != null) {
                caps(ctx, g.header, wx + 18, y + 7, Theme.TEXT_HEADER, Role.CAPS, 2f);
                y += NAV_HEADER_H;
            }
            for (Nav n : g.items) {
                navItem(ctx, n, y, mx, my, navActive(n));
                y += NAV_ITEM_H;
            }
            y += NAV_GROUP_GAP;
        }
        if (clip) ctx.disableScissor();

        Theme.fill(ctx, wx + 16, wy + wh - 32, wx + SIDEBAR - 16, wy + wh - 31, Theme.DIVIDER);
        renderAccountFooter(ctx, mx, my);
    }

    /** Whether {@code n} is the page on screen (none is while a mod's settings are open). */
    private boolean navActive(Nav n) {
        return !n.hud && n.page == page && selected == null;
    }

    /**
     * The active page's highlight (accent-tinted fill, thin accent outline), drawn once under the items at
     * {@code listTop} (the nav list's top, scrolled). It glides between items on a spring that is barely underdamped
     * (about 95% of the way in 125 ms, well under 1% overshoot) and, while a mod's settings are open, fades out where
     * it is. Coming back it fades in on its item instead of flying in from the old one. The spring runs in list
     * coordinates, so scrolling the sidebar moves it with the items, not after them.
     */
    private void renderNavHighlight(DrawContext ctx, int listTop) {
        NavHit active = null;
        for (NavHit h : navLayout()) if (navActive(h.nav)) { active = h; break; }
        Motion.Spring pill = springObj("navPill");
        float py;
        if (active != null) {
            float target = active.y - listTop;
            // Back from a mod's settings: start on the item (unless the highlight is still mostly shown, a quick
            // there-and-back, where it glides on from where it is rather than jump).
            if (!navPillShown && anim.getOrDefault("navPillA", 0f) < 0.5f) pill.set(target);
            py = spring("navPill", target, 30f, 0.85f);
        } else {
            py = pill.x;
        }
        navPillShown = active != null;
        float pa = settled(animate("navPillA", active != null ? 1 : 0, 18));
        if (pa <= 0f || !pill.init) return;

        // Whole pixels for the shape, the rest as a device-pixel translation, so it moves smoothly at any GUI scale.
        float fy = listTop + py;
        int iy = (int) Math.floor(fy);
        var m = ctx.getMatrices();
        float base = Theme.fade;
        m.pushMatrix();
        try {
            Theme.fade = base * pa;
            m.translate(0, Theme.snap(ctx, fy - iy));
            Theme.roundBordered(ctx, wx + 10, iy + 1, wx + SIDEBAR - 10, iy + NAV_ITEM_H - 3, Theme.tint(0.11f),
                    Theme.lerpColor(Theme.BORDER, Theme.RED, 0.62f));
        } finally {
            Theme.fade = base;
            m.popMatrix();
        }
    }

    /** A 0..1 fade amount within a fraction of a colour step of either end taken as that end, so a settled sidebar
     *  draws exactly what a still frame of it should (the eased value only approaches its target). */
    private static float settled(float v) {
        return v > 0.998f ? 1f : v < 0.002f ? 0f : v;
    }

    private void navItem(DrawContext ctx, Nav n, int y, int mx, int my, boolean active) {
        int x1 = wx + 10, x2 = wx + SIDEBAR - 10, top = y + 1, bot = y + NAV_ITEM_H - 3;
        boolean hover = mx >= x1 && mx < x2 && my >= top && my < bot && my >= navTop() && my < navBottom();
        float t = animate("nav:" + n.label, hover ? 0.4f : 0, 16);
        // How active the item looks: its icon, label and count turn to the accent as the highlight arrives.
        float act = settled(animate("navA:" + n.label, active ? 1 : 0, 22));

        // The active highlight is drawn by renderNavHighlight; here only the soft hover fill, which gives way to it.
        float hf = t * (1f - act);
        if (hf > 0.01f) Theme.round(ctx, x1, top, x2, bot, Theme.alpha(Theme.CONTROL_HOVER, hf));

        // Line icon, tinted: dim, brightening on hover, turning to the accent as the item becomes active.
        int fg = Theme.lerpColor(Theme.lerpColor(Theme.TEXT_DIM, Theme.TEXT, t), Theme.RED_BRIGHT, act);
        int ix = x1 + 8, iy = y + (NAV_ITEM_H - 2 - ICON) / 2;
        net.evadeclient.util.Icons.draw(ctx, n.icon, ix, iy, ICON, fg);

        int textColor = Theme.lerpColor(Theme.lerpColor(Theme.TEXT_SOFT, Theme.TEXT, t), Theme.RED_BRIGHT, act);
        text(ctx, n.label, ix + ICON + 9, y + (NAV_ITEM_H - 9) / 2, textColor, false);

        // Count pill, right-aligned (category pages only, not Settings): calm grey, in the accent only when active
        // (blending over as the item becomes active), so the sidebar doesn't read as a column of red badges. On hover
        // it lifts with the row, which is about the pill's own grey there.
        if (n.page != null && n.page.category != null && n.page != Page.SETTINGS) {
            String cnt = String.valueOf(modulesFor(n.page, "").size());
            int tw = width(Role.SMALL, cnt);
            int cw2 = Math.max(13, tw + 8);
            int chipX = x2 - 7 - cw2, chipY = y + (NAV_ITEM_H - 2 - 12) / 2;
            int chip = Theme.lerpColor(Theme.lerpColor(Theme.CONTROL, Theme.CONTROL_HOVER, Math.min(1f, t * 2.5f)),
                    Theme.alpha(Theme.RED, 0.3f), act);
            Theme.pill(ctx, chipX, chipY, chipX + cw2, chipY + 12, chip);
            // The vanilla width counts a trailing pixel column the glyphs don't fill: centre past it. Inter's advances
            // carry their own side bearings, so it centres on the measured width (rounding half up).
            text(ctx, cnt, chipX + (smooth ? (cw2 - tw + 1) / 2 : (cw2 - tw) / 2 + 1), chipY + 3,
                    Theme.lerpColor(Theme.TEXT_DIM, Theme.RED_BRIGHT, act), Role.SMALL);
        }
    }

    private static final int ICON = 12;

    private void renderClose(DrawContext ctx, int mx, int my) {
        int bx = wx + ww - 24, by = wy + 12;
        boolean hc = mx >= bx && mx < bx + 14 && my >= by && my < by + 14;
        float h = button(ctx, "close", bx, by, bx + 14, by + 14, hc, Theme.CONTROL, Theme.RED, Theme.CONTROL, Theme.RED);
        icon(ctx, "close", bx + 2, by + 2, 10, Theme.lerpColor(Theme.TEXT_SOFT, Theme.ON_ACCENT, h));
    }

    // ---- content header ----------------------------------------------

    /** Right edge of the content row that shares the window's top band with the close button. */
    private int contentRight() { return cx + cw - 22; }

    private int searchW() { return contentRight() - cx; }

    private void renderSearch(DrawContext ctx, int mx, int my) {
        int sx = cx, sw = searchW();
        boolean hs = mx >= sx && mx < sx + sw && my >= searchY && my < searchY + 24;
        float focus = focusGlow(ctx, "search", searchFocused, sx, searchY, sx + sw, searchY + 24);
        Theme.roundBordered(ctx, sx, searchY, sx + sw, searchY + 24, Theme.INPUT, inputBorder("search", hs, focus));
        // magnifier
        icon(ctx, "search", sx + 7, searchY + 7, 10, Theme.lerpColor(Theme.TEXT_DIM, Theme.RED_BRIGHT, focus));

        boolean caret = searchFocused && blink(0);
        if (search.isEmpty() && !searchFocused) {
            text(ctx, "Search modules", sx + 22, searchY + 8, Theme.TEXT_FAINT, false);
            int hx = sx + 22 + width("Search modules") + 14;
            text(ctx, ellipsize(Role.LABEL, "Ctrl+F  ·  searches every mod and setting", sx + sw - 8 - hx), hx, searchY + 9,
                    Theme.alpha(Theme.TEXT_FAINT, 0.7f), Role.LABEL);
        } else {
            text(ctx, trimStart(search, sw - 30) + (caret ? "_" : ""), sx + 22, searchY + 8, Theme.TEXT, false);
        }
    }

    /** A soft accent glow around a text field that fades in while it has focus (drawn before the field's box).
     *  Returns how focused it looks (0..1), for the field's border and icon to follow. */
    private float focusGlow(DrawContext ctx, String id, boolean focused, int x1, int y1, int x2, int y2) {
        float focus = animate("focus:" + id, focused ? 1 : 0, 16);
        if (focus > 0.01f) Theme.shadow(ctx, Theme.SH_CARD, x1, y1, x2, y2, Theme.alpha(Theme.RED, 0.28f * focus));
        return focus;
    }

    /** A text field's border: it cross-fades to the lighter hover border under the mouse and to the accent with
     *  {@code focus} (from {@link #focusGlow}). The field itself never moves or dips: only its border changes. */
    private int inputBorder(String id, boolean hover, float focus) {
        float h = animate("ih:" + id, hover ? 1 : 0, 22);
        return Theme.lerpColor(Theme.lerpColor(Theme.BORDER, Theme.BORDER_HOVER, h), Theme.RED, focus);
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
        // On (sorted) it is accent-tinted whether hovered or not; turning it on or off cross-fades too.
        float az = animate("azOn", sortAz ? 1 : 0, 18);
        int azFill = Theme.tint(0.16f);
        float h = button(ctx, "az", ax, segY, ax + AZ_W, segY + 22, ha,
                mix(Theme.INPUT, azFill, az), mix(Theme.CONTROL_HOVER, azFill, az),
                mix(Theme.BORDER, Theme.RED, az), mix(Theme.BORDER_HOVER, Theme.RED, az));
        drawCentered(ctx, "A-Z", ax, ax + AZ_W, segY + 7,
                Theme.lerpColor(Theme.lerpColor(Theme.TEXT_DIM, Theme.TEXT, h), Theme.RED_BRIGHT, az));
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
    private double pendingDeleteAt;
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
        float focus = focusGlow(ctx, "profile", profileFocused, cx, searchY, cx + inW, searchY + 24);
        Theme.roundBordered(ctx, cx, searchY, cx + inW, searchY + 24, Theme.INPUT, inputBorder("profile", hin, focus));
        boolean caret = profileFocused && blink(0);
        if (profileInput.isEmpty() && !profileFocused) {
            text(ctx, "New profile name…", cx + 8, searchY + 8, Theme.TEXT_FAINT, false);
        } else {
            text(ctx, trimStart(profileInput, inW - 22) + (caret ? "_" : ""), cx + 8, searchY + 8, Theme.TEXT, false);
        }
        int bx = createButtonX(), br = contentRight();
        boolean hc = mx >= bx && mx < br && my >= searchY && my < searchY + 24;
        button(ctx, "create", bx, searchY, br, searchY + 24, hc, Theme.RED, Theme.RED_BRIGHT, Theme.RED, Theme.RED_BRIGHT);
        drawCentered(ctx, "Create", bx, br, searchY + 8, Theme.ON_ACCENT, Role.STRONG);
        int ix = importButtonX();
        boolean hi = mx >= ix && mx < ix + 70 && my >= searchY && my < searchY + 24;
        button(ctx, "import", ix, searchY, ix + 70, searchY + 24, hi, Theme.CONTROL, Theme.CONTROL_HOVER, Theme.BORDER, Theme.RED);
        drawCentered(ctx, "Import", ix, ix + 70, searchY + 8, Theme.TEXT);

        // header
        text(ctx, "Profiles", cx, headerY, Theme.TEXT, Role.TITLE);
        List<String> list = profiles.list();
        String active = profiles.active();
        int tw = width(Role.TITLE, "Profiles");
        text(ctx, list.size() + (list.size() == 1 ? " profile" : " profiles"), cx + tw + 10, headerY + 6, Theme.TEXT_DIM, Role.LABEL);

        int contentH = list.size() * (PROFILE_H + GAP);
        clampScroll(contentH, ch);
        int si = scrollPx();
        ctx.enableScissor(cx - 2, cy, cx + cw + 2, cy + ch);
        pushScrollFraction(ctx, si);
        try {
            for (int i = 0; i < list.size(); i++) {
                int y = cy + i * (PROFILE_H + GAP) - si;
                if (y + PROFILE_H < cy || y > cy + ch) continue;
                renderProfileRow(ctx, list.get(i), list.get(i).equals(active), list.size(), y, mx, my);
            }
        } finally {
            ctx.getMatrices().popMatrix();
        }
        ctx.disableScissor();
        edgeFades(ctx, cy, cy + ch);
        scrollbar(ctx, mx, my, contentH, cy, ch);

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
        // How active the row looks: switching profiles cross-fades the tint, ring, accent bar and ACTIVE chip from
        // the old row to the new one.
        float pa = settled(animate("pa:" + name, active ? 1 : 0, 16));
        int fill = mix(Theme.lerpColor(Theme.CARD, Theme.CARD_HOVER, h), Theme.tint(0.09f), pa);
        int border = Theme.lerpColor(Theme.lerpColor(Theme.BORDER, Theme.RED, h * 0.6f), Theme.RED, pa);
        Theme.roundBordered(ctx, cx, y, cx + cw, y + PROFILE_H, fill, border);
        if (pa > 0.01f) Theme.round(ctx, cx + 1, y + 6, cx + 3, y + PROFILE_H - 6, Theme.alpha(Theme.RED, pa), 1);

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
        if (active && pa > 0.01f) {
            // The ACTIVE chip sits in the Use button's slot, at the buttons' height, fading in as the row turns active.
            int right = cx + cw - 12, tw = width(Role.CAPS, "ACTIVE");
            Theme.pill(ctx, right - USE_W, by, right, by + 18, Theme.alpha(Theme.RED, 0.3f * pa));
            text(ctx, "ACTIVE", right - USE_W + (USE_W - tw) / 2, by + 6, Theme.alpha(Theme.RED_BRIGHT, pa), Role.CAPS);
        }
        for (ProfileButton b : buttons) {
            boolean hb = inView && mx >= b.x1() && mx < b.x2() && my >= by && my < by + 18;
            String id = "pb:" + name + ":" + b.id();
            switch (b.id()) {
                case "use" -> {
                    // The row's primary action: a solid accent button (no ring). On the row that just stopped being
                    // active it fades in as the ACTIVE chip there fades out.
                    float base = Theme.fade;
                    Theme.fade = base * (1f - pa);
                    try {
                        button(ctx, id, b.x1(), by, b.x2(), by + 18, hb, Theme.RED, Theme.RED_BRIGHT, Theme.RED, Theme.RED_BRIGHT);
                        drawCentered(ctx, b.label(), b.x1(), b.x2(), by + 5, Theme.ON_ACCENT, Role.STRONG);
                    } finally {
                        Theme.fade = base;
                    }
                }
                case "delete" -> {
                    boolean confirm = name.equals(pendingDelete);
                    float bh = button(ctx, id, b.x1(), by, b.x2(), by + 18, hb,
                            confirm ? Theme.alpha(Theme.DANGER, 0.18f) : Theme.CONTROL,
                            Theme.alpha(Theme.DANGER, confirm ? 0.3f : 0.15f),
                            confirm ? Theme.DANGER : Theme.BORDER, Theme.DANGER);
                    if (confirm) {
                        drawCentered(ctx, b.label(), b.x1(), b.x2(), by + 6, Theme.DANGER, Role.LABEL);
                    } else {
                        text(ctx, "×", b.x1() + 6, by + 5, Theme.lerpColor(Theme.TEXT_DIM, Theme.DANGER, bh), false);
                    }
                    if (hb) setTip(mx, my, List.of(new TipLine(confirm ? "Click again to delete " + name : "Delete " + name, Theme.TEXT_SOFT)));
                }
                default -> {
                    boolean linked = b.id().equals("server") && b.label().startsWith("Linked");
                    float bh = button(ctx, id, b.x1(), by, b.x2(), by + 18, hb,
                            linked ? Theme.alpha(Theme.RED, 0.22f) : Theme.CONTROL, Theme.CONTROL_HOVER,
                            linked ? Theme.RED : Theme.BORDER, Theme.RED);
                    drawCentered(ctx, b.label(), b.x1(), b.x2(), by + 6,
                            linked ? Theme.RED_BRIGHT : Theme.lerpColor(Theme.TEXT_SOFT, Theme.TEXT, bh), Role.LABEL);
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
            press("import");
            var r = profiles.importProfile(client.keyboard.getClipboard());
            if (r.ok()) Notifications.push("Profile imported", r.name() + " is ready to use", true);
            else Notifications.push(r.error().title, r.error().detail, false);
            return true;
        }
        if (mx >= createButtonX() && mx < contentRight() && my >= searchY && my < searchY + 24) {
            press("create");
            createProfile();
            return true;
        }
        List<String> list = profiles.list();
        String active = profiles.active();
        int si = scrollPx();
        for (int i = 0; i < list.size(); i++) {
            int y = cy + i * (PROFILE_H + GAP) - si;
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
                press("pb:" + name + ":" + b.id());
                switch (b.id()) {
                    case "use" -> profiles.switchTo(name);
                    case "delete" -> {
                        if (name.equals(confirming)) {
                            if (profiles.delete(name)) Notifications.push("Profile deleted", name, true);
                        } else {
                            pendingDelete = name;
                            pendingDeleteAt = clock.getAsDouble();
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

    // ---- friends page -------------------------------------------------
    // Friends, requests, the party and social settings (net.evadeclient.social). Built from rows that rendering and
    // clicks lay out the same way, like the profiles page. Opened from the account footer at the bottom of the sidebar.

    private enum FTab {
        FRIENDS("Friends"), REQUESTS("Requests"), PARTY("Party"), CHAT("Chat"), SETTINGS("Settings");
        final String label;
        FTab(String label) { this.label = label; }
    }

    private static FTab friendsTab = FTab.FRIENDS;
    private String friendInput = "";
    private boolean friendFocused;
    /** A button that asks twice (remove, block, kick, leave, delete) clicked once: a second click within
     *  {@link #DELETE_CONFIRM_MS} does it. */
    private String friendConfirm;
    private double friendConfirmAt;
    /** Buttons whose request is on its way: they show "..." until the answer comes. */
    private final java.util.Set<String> friendBusy = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final int FRIEND_ROW_H = 40, FRIEND_HEAD_H = 24, FRIEND_NOTE_H = 16, FRIEND_EMPTY_H = 84, FTAB_W = 68;
    private static final int FR_HEAD = 0, FR_ROW = 1, FR_TOGGLE = 2, FR_NOTE = 3, FR_EMPTY = 4, FR_MSG = 5, FRIEND_MSG_H = 12;
    /** The message being typed on the Chat tab (the top field turns into the message box there). */
    private String chatDraft = "";

    /** A button on a friends row. {@code style}: 0 plain, 1 accent, 2 danger (asks twice). Listed right to left. */
    private record FBtn(String id, String label, int style, Runnable action) {}

    /** A row of the friends page: a header, a person (or action) row, a toggle, a note, or a big empty-state card. */
    private record FRow(String key, int kind, String title, String sub, int dot, String chip, List<FBtn> buttons,
                        java.util.function.BooleanSupplier on, Runnable toggle, boolean enabled) {
        int h() {
            return switch (kind) {
                case FR_HEAD -> FRIEND_HEAD_H;
                case FR_TOGGLE -> ROW_H;
                case FR_NOTE -> FRIEND_NOTE_H;
                case FR_EMPTY -> FRIEND_EMPTY_H;
                case FR_MSG -> FRIEND_MSG_H;
                default -> FRIEND_ROW_H + GAP / 2;
            };
        }
    }

    private static FRow fHead(String title) { return new FRow("h:" + title, FR_HEAD, title, null, 0, null, List.of(), null, null, true); }
    private static FRow fNote(String text) { return new FRow("n:" + text, FR_NOTE, text, null, 0, null, List.of(), null, null, true); }
    private static FRow fEmpty(String title, String sub, FBtn button) {
        return new FRow("e:" + title, FR_EMPTY, title, sub, 0, null, button == null ? List.of() : List.of(button), null, null, true);
    }
    private static FRow fToggle(String key, String title, String sub, java.util.function.BooleanSupplier on, Runnable toggle, boolean enabled) {
        return new FRow("t:" + key, FR_TOGGLE, title, sub, 0, null, List.of(), on, toggle, enabled);
    }
    private static FRow fPerson(String key, String title, String sub, int dot, String chip, List<FBtn> buttons) {
        return new FRow("p:" + key, FR_ROW, title, sub, dot, chip, buttons, null, null, true);
    }

    private static boolean friendsOn() {
        var f = net.evadeclient.module.misc.Friends.get();
        return f != null && f.isEnabled() && f.activated();
    }

    private int friendAddX() { return contentRight() - 70; }
    private int friendInputW() { return friendAddX() - 8 - cx; }

    private void renderFriendsPage(DrawContext ctx, int mx, int my) {
        boolean on = friendsOn();
        var s = net.evadeclient.social.SocialClient.state();
        boolean ready = on && net.evadeclient.social.SocialClient.ready() && s.loaded();

        // Add-a-friend field and button (only once Friends is on); on the Chat tab with a conversation open, the message box.
        if (on) {
            boolean chat = chatInput();
            int inW = friendInputW();
            boolean hin = mx >= cx && mx < cx + inW && my >= searchY && my < searchY + 24;
            float focus = focusGlow(ctx, "friend", friendFocused, cx, searchY, cx + inW, searchY + 24);
            Theme.roundBordered(ctx, cx, searchY, cx + inW, searchY + 24, Theme.INPUT, inputBorder("friend", hin, focus));
            icon(ctx, "friends", cx + 7, searchY + 6, 11, Theme.lerpColor(Theme.TEXT_DIM, Theme.RED_BRIGHT, focus));
            String typed = chat ? chatDraft : friendInput;
            if (typed.isEmpty() && !friendFocused) {
                text(ctx, chat ? "Message " + chatTitle(s, net.evadeclient.social.SocialChat.open()) + " (Enter to send)" : "Add a friend by Minecraft name",
                        cx + 24, searchY + 8, Theme.TEXT_FAINT, false);
            } else {
                String shown = typed + (friendFocused && blink(0) ? "_" : "");
                int room = inW - 30;
                // Long messages show their end, where you're typing.
                while (shown.length() > 1 && width(shown) > room) shown = shown.substring(1);
                text(ctx, shown, cx + 24, searchY + 8, Theme.TEXT, false);
            }
            int bx = friendAddX(), br = contentRight();
            boolean hb = mx >= bx && mx < br && my >= searchY && my < searchY + 24;
            button(ctx, "fadd", bx, searchY, br, searchY + 24, hb, Theme.RED, Theme.RED_BRIGHT, Theme.RED, Theme.RED_BRIGHT);
            drawCentered(ctx, friendBusy.contains("fadd") ? "..." : chat ? "Send" : "Add", bx, br, searchY + 8, Theme.ON_ACCENT, Role.STRONG);
        }

        // Title, a status line and the tabs.
        text(ctx, "Friends", cx, headerY, Theme.TEXT, Role.TITLE);
        int tw = width(Role.TITLE, "Friends");
        String status;
        if (!on) status = "Off";
        else if (!ready) status = net.evadeclient.social.SocialClient.status();
        else status = s.onlineFriends() + " of " + s.friends().size() + " online" + (s.inParty() ? "  ·  party of " + s.party().members().size() : "");
        int tabsX = cx + cw - FTab.values().length * FTAB_W;
        text(ctx, ellipsize(Role.LABEL, status, (on ? tabsX - 10 : cx + cw) - (cx + tw + 10)), cx + tw + 10, headerY + 6, Theme.TEXT_DIM, Role.LABEL);
        if (on) renderFriendTabs(ctx, mx, my, tabsX, s);

        List<FRow> rows = friendRows(on, ready, s);
        int contentH = 0;
        for (FRow r : rows) contentH += r.h();
        clampScroll(contentH, ch);
        int si = scrollPx();
        ctx.enableScissor(cx - 2, cy, cx + cw + 2, cy + ch);
        pushScrollFraction(ctx, si);
        try {
            int y = cy - si;
            for (FRow r : rows) {
                if (y + r.h() >= cy && y <= cy + ch) renderFriendRow(ctx, r, y, mx, my);
                y += r.h();
            }
        } finally {
            ctx.getMatrices().popMatrix();
        }
        ctx.disableScissor();
        edgeFades(ctx, cy, cy + ch);
        scrollbar(ctx, mx, my, contentH, cy, ch);
    }

    private void renderFriendTabs(DrawContext ctx, int mx, int my, int x, net.evadeclient.social.SocialState s) {
        FTab[] tabs = FTab.values();
        int segY = headerY - 3, w = tabs.length * FTAB_W;
        Theme.roundBordered(ctx, x, segY, x + w, segY + 22, Theme.INPUT, Theme.BORDER);
        // The knob glides between tabs on a spring, in device pixels.
        float kx = spring("ftab", friendsTab.ordinal() * FTAB_W, 30f, 0.85f);
        var km = ctx.getMatrices();
        km.pushMatrix();
        km.translate(Theme.snap(ctx, kx), 0);
        Theme.round(ctx, x + 2, segY + 2, x + FTAB_W - 2, segY + 20, Theme.alpha(Theme.RED, 0.9f), Theme.R_XS);
        km.popMatrix();
        int waiting = s.incoming().size() + s.invites().size(), unread = net.evadeclient.social.SocialChat.totalUnread();
        for (FTab t : tabs) {
            int tx = x + t.ordinal() * FTAB_W;
            float sel = settled(animate("ftabA:" + t, t == friendsTab ? 1 : 0, 18));
            boolean hover = t != friendsTab && mx >= tx && mx < tx + FTAB_W && my >= segY && my < segY + 22;
            float h = animate("ftabH:" + t, hover ? 1 : 0, 22);
            int color = Theme.lerpColor(Theme.lerpColor(Theme.TEXT_DIM, Theme.TEXT, h), Theme.ON_ACCENT, sel);
            String label = t.label;
            if ((t == FTab.REQUESTS && waiting > 0) || (t == FTab.CHAT && unread > 0)) {
                // A count pill after the label.
                String cnt = String.valueOf(t == FTab.CHAT ? Math.min(99, unread) : waiting);
                int lw = width(label), cwid = Math.max(11, width(Role.SMALL, cnt) + 6), total = lw + 4 + cwid;
                int lx = tx + (FTAB_W - total) / 2;
                text(ctx, label, lx, segY + 7, color, false);
                int px = lx + lw + 4;
                Theme.pill(ctx, px, segY + 6, px + cwid, segY + 16, Theme.lerpColor(Theme.RED, Theme.alpha(0xFFFFFFFF, 0.25f), sel));
                text(ctx, cnt, px + (cwid - width(Role.SMALL, cnt) + 1) / 2, segY + 8, Theme.ON_ACCENT, Role.SMALL);
            } else {
                drawCentered(ctx, label, tx, tx + FTAB_W, segY + 7, color);
            }
        }
    }

    /** The rows of the current tab (or the intro while Friends is off, or the connection state while it connects). */
    private List<FRow> friendRows(boolean on, boolean ready, net.evadeclient.social.SocialState s) {
        List<FRow> rows = new ArrayList<>();
        if (!on) {
            rows.add(fEmpty("Play with your friends", "See who's online and on which server, make a party, and ping spots for your party.",
                    new FBtn("fstart", "Turn on Friends", 1, () -> {
                        var f = net.evadeclient.module.misc.Friends.get();
                        if (f != null) f.activate();
                        friendsTab = FTab.FRIENDS;
                    })));
            rows.add(fNote("Friends uses evadeclient.com and signs in with your Minecraft account (no password). See evadeclient.com/privacy."));
            rows.add(fNote("Nothing is sent until you turn it on. Party pings only reach your party on the same server."));
            return rows;
        }
        if (!ready && friendsTab != FTab.SETTINGS) {
            rows.add(fEmpty(net.evadeclient.social.SocialClient.status(), "Friends connect once Evade has checked your Minecraft account.", null));
            return rows;
        }
        switch (friendsTab) {
            case FRIENDS -> {
                if (s.friends().isEmpty()) {
                    rows.add(fEmpty("No friends yet", "Type a friend's Minecraft name above. They need Evade and Friends turned on.", null));
                    break;
                }
                for (var p : s.friends()) {
                    String name = net.evadeclient.social.MojangNames.shown(p.uuid(), p.name());
                    List<FBtn> b = new ArrayList<>();
                    b.add(new FBtn("frm:" + p.uuid(), "Remove", 2, () -> friendAct("frm:" + p.uuid(),
                            net.evadeclient.module.misc.Friends.withUuid("friend.remove", p.uuid()), "Friends", "Removed " + name)));
                    b.add(new FBtn("fbl:" + p.uuid(), "Block", 2, () -> friendAct("fbl:" + p.uuid(),
                            net.evadeclient.module.misc.Friends.withUuid("block", p.uuid()), "Friends", "Blocked " + name)));
                    boolean inMyParty = s.party() != null && s.party().has(p.uuid());
                    if (!inMyParty && p.online()) b.add(new FBtn("finv:" + p.uuid(), "Invite", 0, () -> friendAct("finv:" + p.uuid(),
                            net.evadeclient.module.misc.Friends.withUuid("party.invite", p.uuid()), "Party", "Invited " + name)));
                    if (p.online()) b.add(new FBtn("fmsg:" + p.uuid(), "Message", 0, () -> openChat(p.uuid())));
                    if (p.online() && p.server() != null && !net.evadeclient.social.SocialJoin.onServer(p.server()))
                        b.add(new FBtn("fjoin:" + p.uuid(), "Join", 1, () -> net.evadeclient.social.SocialJoin.ask(name, p.server())));
                    String sub = p.online() ? (p.server() != null ? "Online  ·  " + net.evadeclient.module.misc.StreamerMode.friendServer(p.server()) : "Online") : "Last seen " + ago(p.lastSeen());
                    rows.add(fPerson("f:" + p.uuid(), name, sub, p.online() ? Theme.OK : Theme.TEXT_FAINT, inMyParty ? "PARTY" : null, b));
                }
            }
            case REQUESTS -> {
                if (s.incoming().isEmpty() && s.outgoing().isEmpty() && s.invites().isEmpty()) {
                    rows.add(fEmpty("No requests", "Friend requests and party invites show up here.", null));
                    break;
                }
                if (!s.invites().isEmpty()) {
                    rows.add(fHead("Party invites"));
                    for (var i : s.invites()) {
                        String from = net.evadeclient.social.MojangNames.shown(i.from(), i.fromName());
                        com.google.gson.JsonObject body = new com.google.gson.JsonObject();
                        body.addProperty("party", i.party());
                        long left = Math.max(0, 10 * 60_000L - (System.currentTimeMillis() - i.at())) / 60_000L;
                        rows.add(fPerson("i:" + i.party(), from + "'s party", i.size() + (i.size() == 1 ? " player" : " players") + "  ·  ends in " + Math.max(1, left) + "m", 0, null, List.of(
                                new FBtn("ijoin:" + i.party(), "Join", 1, () -> friendAct("ijoin:" + i.party(),
                                        net.evadeclient.social.SocialClient.call("party.accept", body), "Party", "You joined " + from + "'s party")),
                                new FBtn("idec:" + i.party(), "Decline", 0, () -> friendAct("idec:" + i.party(),
                                        net.evadeclient.social.SocialClient.call("party.decline", body), "Party", null)))));
                    }
                }
                if (!s.incoming().isEmpty()) {
                    rows.add(fHead("Friend requests"));
                    for (var r : s.incoming()) {
                        String name = net.evadeclient.social.MojangNames.shown(r.uuid(), r.name());
                        boolean checked = net.evadeclient.social.MojangNames.confirmed(r.uuid(), name);
                        rows.add(fPerson("in:" + r.uuid(), name, (checked ? "Name checked with Mojang" : "Checking the name with Mojang...") + "  ·  " + ago(r.at()), 0, null, List.of(
                                new FBtn("racc:" + r.uuid(), "Accept", 1, () -> friendAct("racc:" + r.uuid(),
                                        net.evadeclient.module.misc.Friends.withUuid("friend.accept", r.uuid()), "Friends", "You're now friends with " + name)),
                                new FBtn("rdec:" + r.uuid(), "Decline", 0, () -> friendAct("rdec:" + r.uuid(),
                                        net.evadeclient.module.misc.Friends.withUuid("friend.decline", r.uuid()), "Friends", null)),
                                new FBtn("rbl:" + r.uuid(), "Block", 2, () -> friendAct("rbl:" + r.uuid(),
                                        net.evadeclient.module.misc.Friends.withUuid("block", r.uuid()), "Friends", "Blocked " + name)))));
                    }
                }
                if (!s.outgoing().isEmpty()) {
                    rows.add(fHead("Sent"));
                    for (var r : s.outgoing()) {
                        String name = net.evadeclient.social.MojangNames.shown(r.uuid(), r.name());
                        rows.add(fPerson("out:" + r.uuid(), name, "Waiting for them  ·  sent " + ago(r.at()), 0, null, List.of(
                                new FBtn("rcan:" + r.uuid(), "Cancel", 0, () -> friendAct("rcan:" + r.uuid(),
                                        net.evadeclient.module.misc.Friends.withUuid("friend.cancel", r.uuid()), "Friends", null)))));
                    }
                }
            }
            case PARTY -> {
                var party = s.party();
                if (party == null) {
                    rows.add(fEmpty("You're not in a party", "Invite an online friend from Friends, or start an empty party.",
                            new FBtn("pcreate", "Create party", 1, () -> friendAct("pcreate",
                                    net.evadeclient.social.SocialClient.call("party.create", new com.google.gson.JsonObject()), "Party", "Party created"))));
                } else {
                    boolean lead = s.isLeader();
                    rows.add(fHead("Members  " + party.members().size() + " / 8"));
                    for (var p : party.members()) {
                        boolean me = p.uuid().equals(s.me());
                        String name = me ? "You" : net.evadeclient.social.MojangNames.shown(p.uuid(), p.name());
                        List<FBtn> b = new ArrayList<>();
                        if (lead && !me) {
                            b.add(new FBtn("pkick:" + p.uuid(), "Kick", 2, () -> friendAct("pkick:" + p.uuid(),
                                    net.evadeclient.module.misc.Friends.withUuid("party.kick", p.uuid()), "Party", "Removed " + name)));
                            b.add(new FBtn("plead:" + p.uuid(), "Make leader", 0, () -> friendAct("plead:" + p.uuid(),
                                    net.evadeclient.module.misc.Friends.withUuid("party.transfer", p.uuid()), "Party", name + " leads the party now")));
                        }
                        String sub = p.online() ? (p.server() != null ? "Online  ·  " + net.evadeclient.module.misc.StreamerMode.friendServer(p.server()) : "Online") : "Offline";
                        rows.add(fPerson("m:" + p.uuid(), name, sub, p.online() ? Theme.OK : Theme.TEXT_FAINT, p.leader() ? "LEADER" : null, b));
                    }
                    for (var p : party.invited()) {
                        String name = net.evadeclient.social.MojangNames.shown(p.uuid(), p.name());
                        rows.add(fPerson("pi:" + p.uuid(), name, "Invited  ·  waiting for them", Theme.TEXT_FAINT, null, List.of()));
                    }
                    String here = net.evadeclient.social.SocialClient.currentServer(client);
                    if (lead && party.members().size() > 1) rows.add(fPerson("pbring", "Bring party here", here != null
                            ? "Asks your party to join you on " + here + " (they choose)" : "Join a multiplayer server first", 0, null,
                            here == null ? List.of() : List.of(new FBtn("pbring", "Bring", 1, () -> friendAct("pbring",
                                    net.evadeclient.social.SocialJoin.summonParty(), "Party", "Asked your party to join you")))));
                    var offer = net.evadeclient.social.SocialJoin.pendingSummon();
                    if (offer != null) rows.add(fPerson("psum", offer.who() + " wants the party on " + offer.address(), "You choose: Join asks first", 0, null, List.of(
                            new FBtn("psumd", "Dismiss", 0, net.evadeclient.social.SocialJoin::dismissSummon),
                            new FBtn("psumj", "Join", 1, () -> net.evadeclient.social.SocialJoin.ask(offer.who(), offer.address())))));
                    int pu = net.evadeclient.social.SocialChat.unread(net.evadeclient.social.SocialChat.PARTY);
                    rows.add(fPerson("pchat", "Party chat", pu > 0 ? pu + " new" : "Talk to your party through Evade", 0, pu > 0 ? "NEW" : null,
                            List.of(new FBtn("pchat", "Open", 0, () -> openChat(net.evadeclient.social.SocialChat.PARTY)))));
                    rows.add(fPerson("pleave", "Leave party", lead && party.members().size() > 1 ? "The next member who joined leads after you"
                            : "Your party ends when its last member leaves", 0, null, List.of(
                            new FBtn("pleave", "Leave", 2, () -> friendAct("pleave",
                                    net.evadeclient.social.SocialClient.call("party.leave", new com.google.gson.JsonObject()), "Party", "You left the party")))));
                }
                List<net.evadeclient.social.SocialState.Person> invitable = new ArrayList<>();
                for (var p : s.friends()) if (p.online() && (party == null || (!party.has(p.uuid()) && party.invited().stream().noneMatch(q -> q.uuid().equals(p.uuid()))))) invitable.add(p);
                if (!invitable.isEmpty()) {
                    rows.add(fHead("Invite friends"));
                    for (var p : invitable) {
                        String name = net.evadeclient.social.MojangNames.shown(p.uuid(), p.name());
                        rows.add(fPerson("pinv:" + p.uuid(), name, p.server() != null ? "Online  ·  " + net.evadeclient.module.misc.StreamerMode.friendServer(p.server()) : "Online", Theme.OK, null, List.of(
                                new FBtn("pinvb:" + p.uuid(), "Invite", 1, () -> friendAct("pinvb:" + p.uuid(),
                                        net.evadeclient.module.misc.Friends.withUuid("party.invite", p.uuid()), "Party", "Invited " + name)))));
                    }
                }
                var pp = net.evadeclient.module.misc.PartyPing.get();
                if (pp != null) rows.add(fNote("Ping a spot: " + KeySetting.keyName(pp.pingKey.get()) + " (Party Ping), Sneak for danger, or .ping. Only your party on the same server sees it."));
            }
            case CHAT -> chatRows(rows, s);
            case SETTINGS -> {
                var f = net.evadeclient.module.misc.Friends.get();
                var pp = net.evadeclient.module.misc.PartyPing.get();
                if (f == null) break;
                rows.add(fHead("Privacy"));
                rows.add(fToggle("allow", "Allow friend requests", "Off: nobody can send you new requests",
                        s::allowRequests, () -> friendSetting("allowRequests", s.allowRequests() ? "nobody" : "everyone"), ready));
                rows.add(fToggle("share", "Share my server with friends", "Friends and your party see which server you're on",
                        s::shareServer, () -> friendSetting("shareServer", !s.shareServer()), ready));
                rows.add(fHead("Notifications"));
                rows.add(fToggle("otoast", "Friend online toasts", null, f.onlineToasts::get, () -> f.onlineToasts.set(!f.onlineToasts.get()), true));
                rows.add(fToggle("rtoast", "Request and party toasts", null, f.requestToasts::get, () -> f.requestToasts.set(!f.requestToasts.get()), true));
                rows.add(fToggle("mtoast", "Message toasts", null, f.messageToasts::get, () -> f.messageToasts.set(!f.messageToasts.get()), true));
                rows.add(fToggle("mchat", "Messages in chat", "Marked [Evade]; only you see them", f.messagesInChat::get, () -> f.messagesInChat.set(!f.messagesInChat.get()), true));
                rows.add(fToggle("jprompt", "Join buttons in chat", "When a friend starts playing on a server", f.joinPrompts::get, () -> f.joinPrompts.set(!f.joinPrompts.get()), true));
                rows.add(fHead("Friend colours"));
                rows.add(fToggle("fcol", "Colour friends' names", "In chat and the tab list, on your screen", f.friendColors::get, () -> f.friendColors.set(!f.friendColors.get()), true));
                rows.add(fPerson("fcolp", "Colour and highlighted players", "Pick the colour, or add players who aren't on Evade", 0, null, List.of(
                        new FBtn("fcolp", "Open", 0, () -> { selected = f; pageChanged(1); resetScroll(); }))));
                if (pp != null) {
                    rows.add(fHead("Party Ping"));
                    rows.add(fToggle("pping", "Party Ping", null, pp::isEnabled, pp::toggle, true));
                    rows.add(fToggle("psound", "Ping sound", null, pp.sound::get, () -> pp.sound.set(!pp.sound.get()), true));
                    rows.add(fPerson("ppage", "Ping key, beams and minimap", "Ping Key: " + KeySetting.keyName(pp.pingKey.get()), 0, null, List.of(
                            new FBtn("ppage", "Open", 0, () -> { selected = pp; pageChanged(1); resetScroll(); }))));
                }
                if (!s.blocked().isEmpty()) {
                    rows.add(fHead("Blocked"));
                    for (var p : s.blocked()) {
                        String name = net.evadeclient.social.MojangNames.shown(p.uuid(), p.name());
                        rows.add(fPerson("b:" + p.uuid(), name, "No requests, invites, messages or pings from them", 0, null, List.of(
                                new FBtn("unb:" + p.uuid(), "Unblock", 0, () -> friendAct("unb:" + p.uuid(),
                                        net.evadeclient.module.misc.Friends.withUuid("unblock", p.uuid()), "Friends", "Unblocked " + name)))));
                    }
                }
                rows.add(fHead("Account"));
                rows.add(fToggle("use", "Use Friends", "Off: disconnects from evadeclient.com", f::isEnabled, f::toggle, true));
                rows.add(fPerson("fdel", "Delete my social data", "Friends, requests, blocks and party, from evadeclient.com, now", 0, null, List.of(
                        new FBtn("fdel", "Delete", 2, () -> friendAct("fdel", net.evadeclient.social.SocialClient.call("delete", new com.google.gson.JsonObject()),
                                "Friends", "Your social data was deleted", r -> { f.deactivate(); net.evadeclient.social.SocialClient.stop("Off"); })))));
                rows.add(fNote("Status: " + net.evadeclient.social.SocialClient.status() + ". Unused social data is deleted after 90 days."));
            }
        }
        return rows;
    }

    /** Whether the top field is the message box (Chat tab, a conversation open). */
    private boolean chatInput() {
        return page == Page.FRIENDS && friendsTab == FTab.CHAT && net.evadeclient.social.SocialChat.open() != null;
    }

    /** Opens a conversation on the Chat tab (a friend's UUID, or party chat). */
    private void openChat(java.util.UUID convo) {
        net.evadeclient.social.SocialChat.setOpen(convo);
        friendsTab = FTab.CHAT;
        friendFocused = true;
        pageChanged(0);
        resetScroll();
        scrollTarget = 1e9; // the newest messages, at the bottom (clamped when laid out)
    }

    /** "Party chat", or the friend's name. */
    private static String chatTitle(net.evadeclient.social.SocialState s, java.util.UUID convo) {
        if (convo == null) return "";
        if (net.evadeclient.social.SocialChat.PARTY.equals(convo)) return "your party";
        for (var p : s.friends()) if (p.uuid().equals(convo)) return net.evadeclient.social.MojangNames.shown(p.uuid(), p.name());
        return "them";
    }

    /** The Chat tab: conversations, or one conversation's messages (wrapped to the page, newest at the bottom). */
    private void chatRows(List<FRow> rows, net.evadeclient.social.SocialState s) {
        java.util.UUID cur = net.evadeclient.social.SocialChat.open();
        if (cur != null && net.evadeclient.social.SocialChat.PARTY.equals(cur) && !s.inParty()) cur = null;
        if (cur != null && !net.evadeclient.social.SocialChat.PARTY.equals(cur) && !s.isFriend(cur)) cur = null;
        final java.util.UUID open = cur;
        if (open == null) {
            net.evadeclient.social.SocialChat.setOpen(null);
            if (s.inParty()) {
                int u = net.evadeclient.social.SocialChat.unread(net.evadeclient.social.SocialChat.PARTY);
                rows.add(fPerson("c:party", "Party chat", chatPreview(net.evadeclient.social.SocialChat.PARTY, "Everyone in your party who's online"), 0, u > 0 ? u + " NEW" : null,
                        List.of(new FBtn("copen:party", "Open", u > 0 ? 1 : 0, () -> openChat(net.evadeclient.social.SocialChat.PARTY)))));
            }
            if (s.friends().isEmpty() && !s.inParty()) {
                rows.add(fEmpty("No one to message yet", "Add friends first. Messages go through Evade, not the server you're on.", null));
                return;
            }
            for (var p : s.friends()) {
                String name = net.evadeclient.social.MojangNames.shown(p.uuid(), p.name());
                int u = net.evadeclient.social.SocialChat.unread(p.uuid());
                rows.add(fPerson("c:" + p.uuid(), name, chatPreview(p.uuid(), p.online() ? "Online" : "Offline: can't get messages now"),
                        p.online() ? Theme.OK : Theme.TEXT_FAINT, u > 0 ? u + " NEW" : null,
                        List.of(new FBtn("copen:" + p.uuid(), "Open", u > 0 ? 1 : 0, () -> openChat(p.uuid())))));
            }
            rows.add(fNote("Plain text, 256 characters. Relayed to whoever is online and not kept. Block someone to stop their messages."));
            return;
        }
        net.evadeclient.social.SocialChat.viewing(open);
        boolean party = net.evadeclient.social.SocialChat.PARTY.equals(open);
        String title = party ? "Party chat" : chatTitle(s, open);
        boolean online = party || s.friends().stream().anyMatch(p -> p.uuid().equals(open) && p.online());
        rows.add(fPerson("chead", title, party ? "Your party: " + s.party().members().size() + " players" : online ? "Online" : "Offline: they won't get messages now",
                party ? 0 : online ? Theme.OK : Theme.TEXT_FAINT, null, List.of(new FBtn("cback", "Back", 0, () -> { net.evadeclient.social.SocialChat.setOpen(null); resetScroll(); }))));
        var lines = net.evadeclient.social.SocialChat.lines(open);
        if (lines.isEmpty()) rows.add(fNote(party ? "Say hi to your party: type below, or .pc <message> in chat." : "No messages yet. Type above, or .msg " + title + " <message> in chat."));
        int room = cw - 16;
        for (var l : lines) {
            String who = (l.mine() ? "You" : l.name()) + ": ";
            int color = l.mine() ? Theme.TEXT_DIM : Theme.TEXT;
            int whoW = width(who);
            List<String> wrapped = wrapWords(l.text(), room - whoW, room);
            for (int i = 0; i < wrapped.size(); i++) {
                rows.add(new FRow("cm:" + l.at() + ":" + i + ":" + rows.size(), FR_MSG, wrapped.get(i), null, color, i == 0 ? who : null,
                        List.of(), null, null, l.mine()));
            }
        }
    }

    /** The newest message of a conversation, or {@code fallback}. */
    private static String chatPreview(java.util.UUID convo, String fallback) {
        var lines = net.evadeclient.social.SocialChat.lines(convo);
        if (lines.isEmpty()) return fallback;
        var l = lines.get(lines.size() - 1);
        return (l.mine() ? "You: " : "") + l.text();
    }

    /** {@code text} broken at spaces into lines: the first {@code first} pixels wide, the rest {@code rest}. */
    private List<String> wrapWords(String text, int first, int rest) {
        List<String> out = new ArrayList<>();
        String line = "";
        for (String w : text.split(" ")) {
            String next = line.isEmpty() ? w : line + " " + w;
            int room = out.isEmpty() ? first : rest;
            if (width(next) <= room) { line = next; continue; }
            if (!line.isEmpty()) { out.add(line); line = ""; room = rest; }
            while (width(w) > room && w.length() > 1) { // one long word: cut it
                int n = w.length();
                while (n > 1 && width(w.substring(0, n)) > room) n--;
                out.add(w.substring(0, n));
                w = w.substring(n);
            }
            line = w;
        }
        if (!line.isEmpty() || out.isEmpty()) out.add(line);
        return out;
    }

    /** Sends the message box's text to the open conversation. */
    private void sendChatDraft() {
        java.util.UUID to = net.evadeclient.social.SocialChat.open();
        String text = chatDraft;
        if (to == null || net.evadeclient.social.SocialChat.clean(text).isEmpty()) { friendFocused = true; return; }
        chatDraft = "";
        friendAct("fadd", net.evadeclient.social.SocialChat.send(to, text), "Message", null, r -> scrollTarget = 1e9);
    }

    private void friendSetting(String key, Object value) {
        com.google.gson.JsonObject b = new com.google.gson.JsonObject();
        if (value instanceof Boolean v) b.addProperty(key, v); else b.addProperty(key, String.valueOf(value));
        friendAct("set:" + key, net.evadeclient.social.SocialClient.call("settings", b), "Friends", null);
    }

    private void friendAct(String id, java.util.concurrent.CompletableFuture<com.google.gson.JsonObject> f, String title, String success) {
        friendAct(id, f, title, success, null);
    }

    /** Runs a request for button {@code id} (which shows "..." meanwhile) and toasts the outcome. */
    private void friendAct(String id, java.util.concurrent.CompletableFuture<com.google.gson.JsonObject> f, String title, String success,
                           java.util.function.Consumer<com.google.gson.JsonObject> done) {
        friendBusy.add(id);
        f.whenComplete((r, e) -> friendBusy.remove(id));
        net.evadeclient.module.misc.Friends.run(f, title, success, done);
    }

    /** "just now", "5m ago", "3h ago", "2d ago". */
    private static String ago(long at) {
        if (at <= 0) return "a while ago";
        long min = Math.max(0, System.currentTimeMillis() - at) / 60_000L;
        if (min < 1) return "just now";
        if (min < 60) return min + "m ago";
        long h = min / 60;
        return h < 24 ? h + "h ago" : h / 24 + "d ago";
    }

    /** The buttons of a row laid out right to left from the row's right edge: {x1, x2} per button. */
    private int[][] friendButtonsAt(FRow r) {
        int[][] out = new int[r.buttons().size()][];
        int right = cx + cw - 12;
        for (int i = 0; i < r.buttons().size(); i++) {
            FBtn b = r.buttons().get(i);
            int w = Math.max(44, width(Role.LABEL, friendLabel(b)) + 16);
            out[i] = new int[]{right - w, right};
            right -= w + 6;
        }
        return out;
    }

    private String friendLabel(FBtn b) {
        if (friendBusy.contains(b.id())) return "...";
        return b.style() == 2 && b.id().equals(friendConfirm) ? b.label() + "?" : b.label();
    }

    private void renderFriendRow(DrawContext ctx, FRow r, int y, int mx, int my) {
        boolean inView = my >= cy && my < cy + ch;
        switch (r.kind()) {
            case FR_HEAD -> caps(ctx, r.title(), cx + 2, y + 10, Theme.TEXT_HEADER, Role.CAPS, 1.5f);
            case FR_NOTE -> text(ctx, ellipsize(Role.SMALL, r.title(), cw - 4), cx + 2, y + 4, Theme.TEXT_FAINT, Role.SMALL);
            case FR_MSG -> {
                int tx = cx + 8;
                if (r.chip() != null) {
                    text(ctx, r.chip(), tx, y + 2, r.enabled() ? Theme.TEXT_DIM : Theme.RED_BRIGHT, false);
                    tx += width(r.chip());
                }
                text(ctx, r.title(), tx, y + 2, r.dot(), false);
            }
            case FR_EMPTY -> {
                Theme.roundBordered(ctx, cx, y + 4, cx + cw, y + FRIEND_EMPTY_H - 8, Theme.CARD, Theme.BORDER);
                drawCentered(ctx, ellipsize(Role.STRONG, r.title(), cw - 20), cx, cx + cw, y + 16, Theme.TEXT, Role.STRONG);
                if (r.sub() != null) drawCentered(ctx, ellipsize(Role.LABEL, r.sub(), cw - 20), cx, cx + cw, y + 30, Theme.TEXT_DIM, Role.LABEL);
                if (!r.buttons().isEmpty()) {
                    FBtn b = r.buttons().get(0);
                    int bw = width(Role.STRONG, friendLabel(b)) + 24, bx = cx + (cw - bw) / 2, by = y + 46;
                    boolean hb = inView && mx >= bx && mx < bx + bw && my >= by && my < by + 20;
                    button(ctx, b.id(), bx, by, bx + bw, by + 20, hb, Theme.RED, Theme.RED_BRIGHT, Theme.RED, Theme.RED_BRIGHT);
                    drawCentered(ctx, friendLabel(b), bx, bx + bw, by + 6, Theme.ON_ACCENT, Role.STRONG);
                }
            }
            case FR_TOGGLE -> {
                boolean hover = inView && mx >= cx && mx < cx + cw && my >= y && my < y + ROW_H - 3;
                float rh = animate("frow:" + r.key(), hover && r.enabled() ? 1 : 0, 22);
                float base = Theme.fade;
                if (!r.enabled()) Theme.fade = base * 0.45f;
                try {
                    Theme.round(ctx, cx, y, cx + cw, y + ROW_H - 3, Theme.lerpColor(Theme.ROW, Theme.ROW_HOVER, rh));
                    text(ctx, r.title(), cx + 10, y + 7, Theme.TEXT, false);
                    if (r.sub() != null) {
                        int sx = cx + 10 + width(r.title()) + 10;
                        text(ctx, ellipsize(Role.SMALL, r.sub(), controlRight() - 30 - sx), sx, y + 8, Theme.TEXT_FAINT, Role.SMALL);
                    }
                    Theme.toggle(ctx, controlRight() - 20, y + (ROW_H - 3) / 2 - 5, animate("ftog:" + r.key(), r.on().getAsBoolean() ? 1 : 0, 14));
                } finally {
                    Theme.fade = base;
                }
            }
            default -> {
                int h = FRIEND_ROW_H;
                boolean hover = inView && mx >= cx && mx < cx + cw && my >= y && my < y + h;
                float hv = animate("frow:" + r.key(), hover ? 1 : 0, 14);
                Theme.roundBordered(ctx, cx, y, cx + cw, y + h, Theme.lerpColor(Theme.CARD, Theme.CARD_HOVER, hv),
                        Theme.lerpColor(Theme.BORDER, Theme.RED, hv * 0.6f));
                int[][] at = friendButtonsAt(r);
                int textRight = cx + cw - 12;
                for (int[] b : at) textRight = Math.min(textRight, b[0]);
                int tx = cx + 14;
                if (r.dot() != 0) {
                    Theme.disc(ctx, cx + 12, y + (h - 6) / 2, 6, r.dot());
                    tx = cx + 26;
                }
                int titleW = textRight - 10 - tx;
                String title = ellipsize(Role.BODY, r.title(), titleW - (r.chip() != null ? width(Role.CAPS, r.chip()) + 16 : 0));
                text(ctx, title, tx, y + 9, Theme.TEXT, false);
                if (r.chip() != null) {
                    int px = tx + width(title) + 8, pw = width(Role.CAPS, r.chip()) + 10;
                    Theme.pill(ctx, px, y + 8, px + pw, y + 19, Theme.alpha(Theme.RED, 0.3f));
                    text(ctx, r.chip(), px + 5, y + 11, Theme.RED_BRIGHT, Role.CAPS);
                }
                if (r.sub() != null) text(ctx, ellipsize(Role.SMALL, r.sub(), titleW), tx, y + 24, Theme.TEXT_DIM, Role.SMALL);
                int by = y + (h - 18) / 2;
                for (int i = 0; i < at.length; i++) {
                    FBtn b = r.buttons().get(i);
                    boolean hb = inView && mx >= at[i][0] && mx < at[i][1] && my >= by && my < by + 18;
                    boolean confirm = b.style() == 2 && b.id().equals(friendConfirm);
                    String label = friendLabel(b);
                    switch (b.style()) {
                        case 1 -> {
                            button(ctx, b.id(), at[i][0], by, at[i][1], by + 18, hb, Theme.RED, Theme.RED_BRIGHT, Theme.RED, Theme.RED_BRIGHT);
                            drawCentered(ctx, label, at[i][0], at[i][1], by + 5, Theme.ON_ACCENT, Role.STRONG);
                        }
                        case 2 -> {
                            float bh = button(ctx, b.id(), at[i][0], by, at[i][1], by + 18, hb,
                                    confirm ? Theme.alpha(Theme.DANGER, 0.18f) : Theme.CONTROL,
                                    Theme.alpha(Theme.DANGER, confirm ? 0.3f : 0.15f),
                                    confirm ? Theme.DANGER : Theme.BORDER, Theme.DANGER);
                            drawCentered(ctx, label, at[i][0], at[i][1], by + 6, confirm ? Theme.DANGER
                                    : Theme.lerpColor(Theme.TEXT_SOFT, Theme.DANGER, bh), Role.LABEL);
                            if (hb && confirm) setTip(mx, my, List.of(new TipLine("Click again to confirm", Theme.TEXT_SOFT)));
                        }
                        default -> {
                            float bh = button(ctx, b.id(), at[i][0], by, at[i][1], by + 18, hb, Theme.CONTROL, Theme.CONTROL_HOVER, Theme.BORDER, Theme.RED);
                            drawCentered(ctx, label, at[i][0], at[i][1], by + 6, Theme.lerpColor(Theme.TEXT_SOFT, Theme.TEXT, bh), Role.LABEL);
                        }
                    }
                }
            }
        }
    }

    private boolean clickFriends(double mx, double my) {
        String confirming = friendConfirm;
        friendConfirm = null; // any click but the confirming one cancels a pending confirm
        boolean on = friendsOn();
        var s = net.evadeclient.social.SocialClient.state();
        boolean ready = on && net.evadeclient.social.SocialClient.ready() && s.loaded();
        if (on) {
            friendFocused = mx >= cx && mx < cx + friendInputW() && my >= searchY && my < searchY + 24;
            if (friendFocused) return true;
            if (mx >= friendAddX() && mx < contentRight() && my >= searchY && my < searchY + 24) {
                press("fadd");
                if (chatInput()) sendChatDraft(); else addFriendFromInput();
                return true;
            }
            int tabsX = cx + cw - FTab.values().length * FTAB_W, segY = headerY - 3;
            if (mx >= tabsX && mx < cx + cw && my >= segY && my < segY + 22) {
                FTab t = FTab.values()[Math.min(FTab.values().length - 1, (int) ((mx - tabsX) / FTAB_W))];
                if (t != friendsTab) {
                    friendsTab = t;
                    pageChanged(0);
                    resetScroll();
                    net.evadeclient.social.SocialClient.refresh();
                }
                return true;
            }
        }
        if (my < cy || my >= cy + ch || mx < cx || mx >= cx + cw) return true;
        int y = cy - scrollPx();
        for (FRow r : friendRows(on, ready, s)) {
            int h = r.h();
            if (my >= y && my < y + h) {
                switch (r.kind()) {
                    case FR_TOGGLE -> {
                        if (r.enabled() && my < y + ROW_H - 3) { r.toggle().run(); press("ftog:" + r.key()); }
                    }
                    case FR_EMPTY -> {
                        if (r.buttons().isEmpty()) break;
                        FBtn b = r.buttons().get(0);
                        int bw = width(Role.STRONG, friendLabel(b)) + 24, bx = cx + (cw - bw) / 2, by = y + 46;
                        if (mx >= bx && mx < bx + bw && my >= by && my < by + 20) { press(b.id()); b.action().run(); }
                    }
                    case FR_ROW -> {
                        int by = y + (FRIEND_ROW_H - 18) / 2;
                        if (my < by || my >= by + 18) break;
                        friendConfirm = confirming; // lay out as drawn (a pending confirm is wider)
                        int[][] at = friendButtonsAt(r);
                        friendConfirm = null;
                        for (int i = 0; i < at.length; i++) {
                            if (mx < at[i][0] || mx >= at[i][1]) continue;
                            FBtn b = r.buttons().get(i);
                            if (friendBusy.contains(b.id())) return true;
                            press(b.id());
                            if (b.style() == 2 && !b.id().equals(confirming)) {
                                friendConfirm = b.id();
                                friendConfirmAt = clock.getAsDouble();
                            } else {
                                b.action().run();
                            }
                            return true;
                        }
                    }
                    default -> { }
                }
                return true;
            }
            y += h;
        }
        return true;
    }

    private void addFriendFromInput() {
        String name = friendInput.strip();
        if (name.isEmpty()) { friendFocused = true; return; }
        if (!net.evadeclient.social.SocialClient.ready()) {
            Notifications.push("Friends", "Still connecting: " + net.evadeclient.social.SocialClient.status(), false);
            return;
        }
        friendInput = "";
        friendAct("fadd", net.evadeclient.module.misc.Friends.addFriend(name), "Friends", null, r ->
                Notifications.push("Friends", r.has("accepted") && r.get("accepted").getAsBoolean()
                        ? "You're now friends with " + name : "Friend request sent to " + name, true));
    }

    // ---- account footer (opens Friends) --------------------------------

    private int footerTop() { return wy + wh - 31; }

    /** The sidebar's account footer: your name, and Friends at a glance. Clicking it opens the Friends page. */
    private void renderAccountFooter(DrawContext ctx, int mx, int my) {
        int x1 = wx + 10, x2 = wx + SIDEBAR - 10, top = footerTop() + 2, bot = wy + wh - 4;
        boolean hover = mx >= x1 && mx < x2 && my >= top && my < bot;
        boolean active = page == Page.FRIENDS && selected == null;
        float t = animate("foot", hover ? 0.4f : 0, 16);
        float act = settled(animate("footA", active ? 1 : 0, 22));
        if (act > 0.01f) {
            float base = Theme.fade;
            Theme.fade = base * act;
            try {
                Theme.roundBordered(ctx, x1, top, x2, bot, Theme.tint(0.11f), Theme.lerpColor(Theme.BORDER, Theme.RED, 0.62f));
            } finally {
                Theme.fade = base;
            }
        }
        float hf = t * (1f - act);
        if (hf > 0.01f) Theme.round(ctx, x1, top, x2, bot, Theme.alpha(Theme.CONTROL_HOVER, hf));
        int fg = Theme.lerpColor(Theme.lerpColor(Theme.TEXT_DIM, Theme.TEXT, t), Theme.RED_BRIGHT, act);
        icon(ctx, "friends", x1 + 6, top + (bot - top - ICON) / 2, ICON, fg);

        var s = net.evadeclient.social.SocialClient.state();
        boolean on = friendsOn();
        int waiting = on ? s.incoming().size() + s.invites().size() + net.evadeclient.social.SocialChat.totalUnread() : 0;
        int pillW = 0;
        if (waiting > 0) {
            String cnt = String.valueOf(waiting);
            pillW = Math.max(13, width(Role.SMALL, cnt) + 8);
            int px = x2 - 6 - pillW, py = top + (bot - top - 12) / 2;
            Theme.pill(ctx, px, py, px + pillW, py + 12, Theme.alpha(Theme.RED, 0.85f));
            text(ctx, cnt, px + (smooth ? (pillW - width(Role.SMALL, cnt) + 1) / 2 : (pillW - width(Role.SMALL, cnt)) / 2 + 1), py + 3, Theme.ON_ACCENT, Role.SMALL);
        }
        int tx = x1 + 6 + ICON + 7, tw = x2 - (pillW > 0 ? pillW + 10 : 6) - tx;
        String user = client != null && client.getSession() != null ? client.getSession().getUsername() : "";
        text(ctx, ellipsize(Role.LABEL, user.isEmpty() ? "Player" : user, tw), tx, top + 4,
                Theme.lerpColor(Theme.lerpColor(Theme.TEXT_SOFT, Theme.TEXT, t), Theme.RED_BRIGHT, act), Role.LABEL);
        String line = !on ? "Friends  ·  Evade v" + EvadeClient.VERSION
                : s.loaded() ? s.onlineFriends() + (s.onlineFriends() == 1 ? " friend" : " friends") + " online"
                : "Friends  ·  connecting";
        text(ctx, ellipsize(Role.SMALL, line, tw), tx, top + 15, Theme.TEXT_FAINT, Role.SMALL);
        if (hover) setTip(mx, my, List.of(new TipLine("Friends and party  ·  Evade v" + EvadeClient.VERSION, Theme.TEXT_SOFT)));
    }

    // ---- module list --------------------------------------------------

    private List<Module> modulesFor(Page p, String filter) {
        List<Module> out = new ArrayList<>();
        String f = filter.toLowerCase(Locale.ROOT);
        for (Module mod : EvadeClient.modules.all()) {
            if (mod.getCategory() == Category.CLIENT) continue;
            if (p.category != null && mod.getCategory() != p.category) continue;
            if (p == Page.PINNED && !net.evadeclient.config.MenuState.isPinned(mod)) continue;
            if (!f.isBlank() && !matches(mod, f)) continue;
            out.add(mod);
        }
        return out;
    }

    /** Search matches a mod's name, description or any of its settings' names and descriptions (all words must match). */
    private static boolean matches(Module mod, String f) {
        StringBuilder hay = new StringBuilder(mod.getName()).append(' ').append(mod.getDescription()).append(' ')
                .append(mod.getCategory().displayName);
        String addon = net.evadeclient.addon.EvadeAddons.ownerName(mod);
        if (addon != null) hay.append(" addon ").append(addon);
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
        clampScroll(contentH, ch);
        int si = scrollPx();

        // Wider than the rows on both sides (they still end at cx + cw) so a hover glow fades out instead of being
        // cut off; the sidebar edge is 17 px away and the scrollbar is drawn after the scissor.
        ctx.enableScissor(cx - 10, cy, cx + cw + 10, cy + ch);
        pushScrollFraction(ctx, si);
        try {
            // Hover glows first, under every row, so one never paints over the row before it (Compact rows are 4 px
            // apart). They use the hover value the rows animated last frame: a frame late, which can't be seen.
            // Enabled rows get no glow of their own: a list of enabled mods would look like neon.
            for (int i = 0; i < mods.size(); i++) {
                Module mod = mods.get(i);
                int x = cx + (i % cols) * (cardW + rg);
                int y = cy + (i / cols) * (rh + rg) - si;
                if (y + rh < cy || y > cy + ch) continue;
                float h = anim.getOrDefault("hover:" + mod.getName(), 0f);
                if (h > 0.01f) Theme.shadow(ctx, Theme.SH_CARD, x, y, x + cardW, y + rh, Theme.alpha(Theme.RED, 0.30f * h));
            }
            for (int i = 0; i < mods.size(); i++) {
                Module mod = mods.get(i);
                int x = cx + (i % cols) * (cardW + rg);
                int y = cy + (i / cols) * (rh + rg) - si;
                if (y + rh < cy || y > cy + ch) continue;
                if (cards()) renderCard(ctx, mod, x, y, cardW, mx, my);
                else if (compact()) renderCompact(ctx, mod, x, y, cardW, mx, my);
                else renderListRow(ctx, mod, x, y, cardW, mx, my);
            }
        } finally {
            ctx.getMatrices().popMatrix();
        }
        if (mods.isEmpty()) {
            String q = search.trim();
            String msg = !q.isEmpty() ? (enabledOnly ? "No enabled modules match \"" : "No modules match \"") + q + "\""
                    : enabledOnly ? "No enabled mods here"
                    : listPage() == Page.PINNED ? "Click the star next to a mod's name to pin it here" : "Nothing here yet";
            msg = trim(msg, cw - 20);
            text(ctx, msg, cx + (cw - width(msg)) / 2, cy + 30, Theme.TEXT_DIM, false);
        }
        ctx.disableScissor();
        edgeFades(ctx, cy, cy + ch);
        scrollbar(ctx, mx, my, contentH, cy, ch);

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
        renderStar(ctx, mod, x, y, w, hover, mx, my);
        text(ctx, ellipsize(Role.SMALL, blurb(mod), w - 150), tx, y + 26, blurbColor(mod), Role.SMALL);

        int right = x + w - 12;
        // toggle (far right)
        Theme.toggle(ctx, right - 20, y + (LIST_H - 10) / 2, on);
        right -= 34;
        // gear: every module has one, since every module has at least its keybind to set
        if (hasSettingsPage(mod)) {
            int gy = y + (LIST_H - 16) / 2;
            boolean hg = inView && mx >= right - 16 && mx < right + 2 && my >= gy - 1 && my < gy + 17;
            float gh = button(ctx, "gear:" + mod.getName(), right - 16, gy, right + 2, gy + 16, hg,
                    Theme.CONTROL, Theme.tint(0.12f), Theme.BORDER, Theme.RED);
            icon(ctx, "settings", right - 13, gy + 3, 10, Theme.lerpColor(Theme.TEXT_DIM, Theme.RED_BRIGHT, gh));
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
        renderStar(ctx, mod, x, y, w, hover, mx, my);
        text(ctx, ellipsize(Role.TINY, blurb(mod), w - 14), x + 8, y + 29, blurbColor(mod), Role.TINY);

        Theme.toggle(ctx, x + 8, y + CARD_H - 15, on);
        if (hasSettingsPage(mod)) {
            int gx = x + w - 16, gy = y + CARD_H - 16;
            boolean hg = inView && mx >= gx - 3 && mx < gx + 12 && my >= gy - 2 && my < gy + 12;
            float gh = button(ctx, "gear:" + mod.getName(), gx - 3, gy - 2, gx + 12, gy + 12, hg,
                    Theme.CONTROL, Theme.RED, Theme.CONTROL, Theme.RED);
            icon(ctx, "settings", gx, gy, 9, Theme.lerpColor(Theme.TEXT_SOFT, Theme.ON_ACCENT, gh));
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
        renderStar(ctx, mod, x, y, w, hover, mx, my);

        Theme.toggle(ctx, x + w - 24, y + (COMPACT_H - 10) / 2, on);
        if (hasSettingsPage(mod)) {
            int gx = x + w - 40, gy = y + (COMPACT_H - 10) / 2;
            boolean hg = inView && mx >= gx - 2 && mx < gx + 11 && my >= gy - 1 && my < gy + 11;
            // A bare icon here (no box): only its colour cross-fades.
            float gh = animate("bh:gear:" + mod.getName(), hg ? 1 : 0, 22);
            icon(ctx, "settings", gx, gy, 10, Theme.lerpColor(Theme.TEXT_DIM, Theme.RED_BRIGHT, gh));
        }
    }

    // ---- scrollbar ----------------------------------------------------

    // The list scrolls on a spring in fractional pixels: rows are laid out (and hit-tested) at the whole-pixel scroll
    // scrollPx(), and the rest, under a pixel, is a device-pixel translation while they are drawn, so a glide is
    // smooth at any GUI scale and text stays on the pixel grid. Clicks land within a pixel of what was drawn.

    /** Clamp the scroll target to a page's content ({@code contentH} tall, shown in {@code viewH}) and remember how
     *  far it can scroll ({@link #lastMaxScroll}): the wheel and keys clamp to it at once, so the list never heads past
     *  an end and then springs back. */
    private void clampScroll(int contentH, int viewH) {
        lastMaxScroll = Math.max(0, contentH - viewH);
        scrollTarget = Math.max(0, Math.min(scrollTarget, lastMaxScroll));
    }

    /** {@code target} clamped to the current page's scroll range. */
    private double clampTarget(double target) {
        return Math.max(0, Math.min(lastMaxScroll, target));
    }

    /** The scroll in whole pixels, for layout and hit tests. */
    private int scrollPx() { return (int) Math.floor(scroll); }

    /**
     * Push the rest of the scroll below {@code si} (under a pixel) as an upward translation snapped to a device pixel;
     * the caller pops it. Call after enableScissor: a scissor rect is transformed by the matrix when it is pushed, so
     * pushing first would move the clip with the rows.
     */
    private void pushScrollFraction(DrawContext ctx, int si) {
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(0, -Theme.snap(ctx, (float) (scroll - si)));
    }

    /** Jump the list back to the top at once (a new page, filter or search: the old position means nothing there). */
    private void resetScroll() {
        scroll = scrollTarget = 0;
        springObj("scroll").set(0);
    }

    /**
     * Soft edges on a scrolling list between {@code top} and {@code bottom}, drawn over its rows after the scissor:
     * the window colour fades in over the last {@link #EDGE_FADE} px at an end with more content beyond it, so a row
     * cut by the edge melts into the window instead of stopping in a hard line. Each fade ramps in over the first
     * {@link #EDGE_RAMP} px of scroll, so there is none at the top of the list, or at the bottom of one that fits.
     */
    private void edgeFades(DrawContext ctx, int top, int bottom) {
        float topA = (float) Math.min(1, scroll / EDGE_RAMP);
        float botA = (float) Math.min(1, (lastMaxScroll - scroll) / EDGE_RAMP);
        int solid = Theme.BG | 0xFF000000, clear = Theme.BG & 0xFFFFFF;
        int x1 = cx - 10, x2 = cx + cw + 10;
        if (topA > 0.004f)
            ctx.fillGradient(x1, top, x2, top + EDGE_FADE, Theme.fa(Theme.alpha(solid, topA)), Theme.fa(clear));
        if (botA > 0.004f)
            ctx.fillGradient(x1, bottom - EDGE_FADE, x2, bottom, Theme.fa(clear), Theme.fa(Theme.alpha(solid, botA)));
    }

    /** Right edge of the scrollbar, in the 8 px gutter right of the content; the bar grows leftwards from it. */
    private int barRight() { return cx + cw + 10; }

    /**
     * The current page's scrollbar, in the gutter right of the content, when the content ({@code contentH} tall)
     * overflows its view ({@code viewH} from {@code viewTop}); remembers its geometry for clicks and drags. At rest it
     * is a slim 2 px thumb, dimmed {@link #SCROLLBAR_LIT_MS} after the last scroll. With the mouse near it (or while
     * dragging) it widens to 4 px, lights up and shows its track. The thumb follows the fractional scroll, snapped to
     * a device pixel.
     */
    private void scrollbar(DrawContext ctx, int mx, int my, int contentH, int viewTop, int viewH) {
        sbTop = viewTop;
        sbViewH = viewH;
        sbContentH = contentH;
        if (!hasScrollbar()) return;
        int right = barRight();
        boolean near = mx >= right - 4 - 10 && mx < right + 10 && my >= viewTop && my < viewTop + viewH;
        float sh = animate("sbH", near || sbDrag ? 1 : 0, 22);
        double since = clock.getAsDouble() - lastScrollAt;
        boolean lit = near || sbDrag || (since >= 0 && since < SCROLLBAR_LIT_MS);
        // Lights up quickly, dims slowly.
        float a = animate("sbA", lit ? 1f : 0.45f, lit ? 24 : 7);
        float w = Theme.snap(ctx, 2 + 2 * sh);

        if (sh > 0.01f) barPill(ctx, right, w, viewTop, viewTop + viewH, Theme.alpha(Theme.DIVIDER, sh));
        // Whole pixels for the shape, the rest as a device-pixel translation, like the sidebar highlight.
        float fy = thumbTop();
        int iy = (int) Math.floor(fy);
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            m.translate(0, Theme.snap(ctx, fy - iy));
            barPill(ctx, right, w, iy, iy + thumbH(), Theme.alpha(sbDrag ? Theme.RED_BRIGHT : Theme.RED, a));
        } finally {
            m.popMatrix();
        }
    }

    /**
     * A vertical pill {@code w} px wide (fractional while the bar widens) with its right edge at {@code right}. A whole
     * width is drawn as it is; in between, the next wider pill is squeezed sideways about its right edge, so the
     * width changes smoothly instead of in whole-pixel steps.
     */
    private static void barPill(DrawContext ctx, int right, float w, int y1, int y2, int color) {
        int whole = Math.round(w);
        if (Math.abs(w - whole) < 0.01f) {
            Theme.pill(ctx, right - whole, y1, right, y2, color);
            return;
        }
        int full = (int) Math.ceil(w);
        var m = ctx.getMatrices();
        m.pushMatrix();
        try {
            m.translate(right, 0);
            m.scale(w / full, 1f);
            m.translate(-right, 0);
            Theme.pill(ctx, right - full, y1, right, y2, color);
        } finally {
            m.popMatrix();
        }
    }

    private boolean hasScrollbar() { return sbViewH > 0 && sbContentH > sbViewH; }
    private int maxScroll() { return Math.max(0, sbContentH - sbViewH); }
    private int thumbH() { return Math.min(sbViewH, Math.max(24, sbViewH * sbViewH / Math.max(1, sbContentH))); }

    /** The thumb's top at the current (fractional) scroll. */
    private float thumbTop() {
        int max = maxScroll();
        return sbTop + (float) ((sbViewH - thumbH()) * (max == 0 ? 0 : Math.max(0, Math.min(1, scroll / max))));
    }

    /** Whether (mx, my) is in the scrollbar's gutter (cx + cw + 4 .. + 14, the height of its track). */
    private boolean onScrollbar(double mx, double my) {
        return hasScrollbar() && mx >= cx + cw + 4 && mx < cx + cw + 14 && my >= sbTop && my < sbTop + sbViewH;
    }

    /** Move the list so the grabbed point of the thumb is under {@code my}: 1:1, with no glide. */
    private void dragScrollbar(double my) {
        int range = sbViewH - thumbH();
        double t = range <= 0 ? 0 : (my - sbGrab - sbTop) / range;
        scrollTarget = scroll = Math.max(0, Math.min(1, t)) * maxScroll();
        springObj("scroll").set((float) scrollTarget);
        lastScrollAt = clock.getAsDouble();
    }

    // ---- settings page ------------------------------------------------

    private List<Object> rowsFor(Module mod) {
        List<Object> rows = new ArrayList<>();
        if (mod.getCategory() != Category.CLIENT) { rows.add("keybind"); rows.add("bindmode"); }
        for (Setting<?> st : mod.getSettings()) if (!st.isHidden()) rows.add(st);
        if (mod.getCategory() != Category.CLIENT) rows.add("share");
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
            float bh = button(ctx, "back", cx, searchY, cx + 52, searchY + 18, hb, Theme.CONTROL, Theme.RED, Theme.BORDER, Theme.RED);
            text(ctx, "‹ Back", cx + 8, searchY + 5, Theme.lerpColor(Theme.TEXT, Theme.ON_ACCENT, bh), false);
        }
        // Reset fades in (sliding 4 px in from the right) once a setting differs from its default, and out again
        // once none does. The title stops short of it while it shows, or of the master toggle without it.
        boolean resettable = hasChangedSettings(mod);
        float ra = settled(animate(resetKey(mod), resettable ? 1 : 0, 18));
        boolean resetShown = resettable || ra > 0.01f;
        int titleRight = resetShown ? resetX() - 8 : selected != null ? controlRight() - 20 - 8 : cx + cw;
        text(ctx, ellipsize(Role.TITLE, mod.getName(), titleRight - cx), cx, headerY, Theme.TEXT, Role.TITLE);
        text(ctx, ellipsize(Role.LABEL, blurb(mod), cw), cx, headerY + 16, blurbColor(mod), Role.LABEL);
        if (selected != null) Theme.toggle(ctx, controlRight() - 20, headerY, animate("on:" + mod.getName(), mod.isEnabled() ? 1 : 0, 14));
        if (ra > 0.01f) {
            int rx = resetX(), rw = 52;
            boolean hr = resettable && ra >= 0.5f && mx >= rx && mx < rx + rw && my >= headerY - 4 && my < headerY + 12;
            var m = ctx.getMatrices();
            float base = Theme.fade;
            m.pushMatrix();
            try {
                Theme.fade = base * ra;
                m.translate(Theme.snap(ctx, (1f - ra) * 4f), 0);
                float rh = button(ctx, "reset", rx, headerY - 4, rx + rw, headerY + 12, hr,
                        Theme.CONTROL, Theme.CONTROL_HOVER, Theme.BORDER, Theme.RED);
                icon(ctx, "reset", rx + 6, headerY - 1, 9, Theme.lerpColor(Theme.TEXT_DIM, Theme.RED_BRIGHT, rh));
                text(ctx, "Reset", rx + 19, headerY, Theme.lerpColor(Theme.TEXT_SOFT, Theme.TEXT, rh), Role.LABEL);
            } finally {
                Theme.fade = base;
                m.popMatrix();
            }
        }

        int top = settingsTop();
        List<Object> rows = rowsFor(mod);
        int contentH = rows.size() * ROW_H;
        int viewH = cy + ch - top;
        clampScroll(contentH, viewH);
        boolean inList = mx >= cx && mx < cx + cw && my >= top && my < cy + ch;

        Object hovered = null;
        int si = scrollPx();
        ctx.enableScissor(cx - 2, top, cx + cw + 2, cy + ch);
        pushScrollFraction(ctx, si);
        try {
            int y = top - si;
            for (Object row : rows) {
                // A row starting right at the bottom edge still shows the fraction of a pixel the list is moved up.
                if (y + ROW_H <= top || y > cy + ch) { y += ROW_H; continue; }
                boolean hover = inList && my >= y && my < y + ROW_H - 2;
                if (hover) hovered = row;
                float rh = animate("row:" + rowId(row, mod), hover ? 1 : 0, 22);
                Theme.round(ctx, cx, y, cx + cw, y + ROW_H - 3, Theme.lerpColor(Theme.ROW, Theme.ROW_HOVER, rh));
                // changed from its default: a small accent dot in the row's left margin
                if (row instanceof Setting<?> st && changed(st)) Theme.disc(ctx, cx + 3, y + (ROW_H - 3) / 2 - 1, 3, Theme.RED);
                String label = row instanceof Setting<?> st ? st.getName()
                        : row.equals("bindmode") ? "Key Mode" : row.equals("share") ? "Share Settings" : "Toggle Key";
                text(ctx, label, cx + 10, y + 7, Theme.TEXT, false);
                renderControl(ctx, row, mod, controlRight(), y, mx, my, hover);
                y += ROW_H;
            }
        } finally {
            ctx.getMatrices().popMatrix();
        }
        ctx.disableScissor();
        edgeFades(ctx, top, cy + ch);
        scrollbar(ctx, mx, my, contentH, top, viewH);

        // Tooltip for the row under the mouse once it has rested there (not while dragging, typing or binding).
        double now = clock.getAsDouble();
        if (hovered != tipRow) { tipRow = hovered; tipSince = now; }
        if (hovered != null && now - tipSince >= TIP_DELAY && dragging == null && editor == null && listening == null && !sbDrag) {
            List<TipLine> lines = tipFor(hovered, mod);
            if (!lines.isEmpty()) setTip(mx, my, lines);
        }
    }

    /** Draws the control for a settings row, right-aligned at {@code right}. */
    private void renderControl(DrawContext ctx, Object row, Module mod, int right, int y, int mx, int my, boolean rowHover) {
        int midY = y + (ROW_H - 3) / 2;
        if (row.equals("bindmode") || row.equals("share")) {
            for (Chip c : chips(row, mod, right)) {
                boolean hv = rowHover && mx >= c.x1 && mx < c.x2 && my >= midY - 7 && my < midY + 7;
                int fill = c.active ? Theme.tint(0.22f) : Theme.CONTROL;
                int border = c.active ? Theme.alpha(Theme.RED, 0.8f) : Theme.BORDER;
                button(ctx, "chip:" + rowId(row, mod) + ":" + c.id, c.x1, midY - 7, c.x2, midY + 7, hv,
                        fill, c.active ? fill : Theme.CONTROL_HOVER, border, Theme.RED);
                text(ctx, c.label, c.x1 + (c.x2 - c.x1 - width(Role.SMALL, c.label)) / 2, midY - 3,
                        c.active ? Theme.TEXT : Theme.TEXT_SOFT, Role.SMALL);
            }
            return;
        }
        boolean isKeybind = row.equals("keybind");
        if (isKeybind || row instanceof KeySetting) {
            Object self = isKeybind ? mod : row;
            String rid = rowId(row, mod);
            double now = clock.getAsDouble();
            boolean l = listening == self;
            int code = isKeybind ? mod.getKey() : ((KeySetting) row).get();
            boolean rejected = rejectRow == self && now - rejectAt < 1400;
            boolean conflict = !l && !conflicts(code, self).isEmpty();
            // Listening: the prompt's dots count up every 300 ms. The box is sized, and the text placed, for all three,
            // so neither moves while they count.
            String label = l ? (acceptsMouse(self) ? "Press a key or mouse button" : "Press a key")
                    : isKeybind ? net.evadeclient.util.Keybinds.comboName(code, mod.getBindMods()) + (mod.isHoldBind() && code != GLFW.GLFW_KEY_UNKNOWN ? " (hold)" : "")
                    : KeySetting.keyName(code);
            String sized = l ? label + "..." : label;
            String text = l ? label + ".".repeat(anims ? (int) Math.floorMod((long) Math.floor((now - listenAt) / 300), 4L) : 3) : label;
            int tw = width(sized);
            int w = Math.max(50, tw + 14);
            // The box eases to its new width (key name to prompt and back), growing and shrinking to the left.
            int bw = Math.round(animate("kw:" + rid, w, 22));
            int x1 = right - bw;
            int border, borderHover;
            if (rejected || conflict) {
                border = borderHover = Theme.DANGER;
            } else if (l) {
                // The accent border breathes 1.2 times a second while it waits, starting fully lit.
                float pulse = anims ? 0.55f + 0.45f * (0.5f + 0.5f * (float) Math.cos(2 * Math.PI * 1.2 * (now - listenAt) / 1000)) : 1f;
                border = borderHover = Theme.alpha(Theme.RED, pulse);
            } else {
                border = Theme.BORDER;
                borderHover = Theme.BORDER_HOVER;
            }
            int fill = rejected ? Theme.alpha(Theme.DANGER, 0.16f) : Theme.CONTROL;
            int fillHover = rejected ? fill : Theme.CONTROL_HOVER;
            // Just bound (or cleared): the box flashes accent-tinted and back.
            float flash = keyFlash(self, now);
            if (flash > 0f) {
                fill = mix(fill, Theme.tint(0.25f), flash);
                fillHover = mix(fillHover, Theme.tint(0.25f), flash);
            }
            // The whole row is the control (a click anywhere on it binds), so the box lights up with the row.
            button(ctx, "key:" + rid, x1, midY - 7, right, midY + 7, rowHover, fill, fillHover, border, borderHover);
            // Clipped to the box while it is still narrower than its new text.
            boolean clip = bw < tw + 6;
            if (clip) ctx.enableScissor(x1 + 2, midY - 7, right - 2, midY + 7);
            try {
                text(ctx, text, x1 + (bw - tw) / 2, midY - 4, rejected || conflict ? Theme.DANGER : l ? Theme.RED_BRIGHT : Theme.TEXT, false);
            } finally {
                if (clip) ctx.disableScissor();
            }
            String hint = rejected ? rejectMsg : l ? (isMenuKey(self) ? "Esc cancel · Del reset" : "Esc cancel · Del clear")
                    : conflict && rowHover ? "In use elsewhere" : null;
            if (hint != null) {
                int hw = width(Role.SMALL, hint);
                text(ctx, hint, x1 - 8 - hw, midY - 3, rejected || conflict ? Theme.DANGER : Theme.TEXT_FAINT, Role.SMALL);
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
                // The value's box (click it to type a value) fades in under the mouse.
                float vh = animate("bh:num:" + rowId(row, mod), hv ? 1 : 0, 22);
                if (vh > 0.01f)
                    Theme.roundBordered(ctx, vx - 4, midY - 7, sx - 4, midY + 7, Theme.alpha(Theme.CONTROL, vh), Theme.alpha(Theme.BORDER, vh));
                text(ctx, v, vx, midY - 4, Theme.lerpColor(Theme.TEXT_DIM, Theme.TEXT, vh), false);
            }
            if (rowHover && mx >= sx - 8 - Math.max(48, width(n.display()) + 14) && mx <= right + 6) hoverSlider = n;
        } else if (row instanceof ModeSetting md) {
            String rid = rowId(row, mod);
            String v = md.get();
            // The chip eases to the new value's width, growing and shrinking to the left.
            int bw = Math.round(animate("mw:" + rid, modeChipW(v), 20));
            int x1 = right - bw;
            boolean hv = rowHover && mx >= x1 && mx <= right;
            button(ctx, "mode:" + rid, x1, midY - 7, right, midY + 7, hv, Theme.CONTROL, Theme.CONTROL_HOVER, Theme.BORDER, Theme.BORDER_HOVER);
            // Each chevron brightens while the half that steps its way is under the mouse (right: next, left: previous).
            boolean fwd = mx >= x1 + bw / 2.0;
            float hl = animate("mcl:" + rid, hv && !fwd ? 1 : 0, 22), hr = animate("mcr:" + rid, hv && fwd ? 1 : 0, 22);
            icon(ctx, "chevron_l", x1 + 4, midY - 4, 7, Theme.lerpColor(Theme.RED, Theme.RED_BRIGHT, hl));
            icon(ctx, "chevron_r", right - 11, midY - 4, 7, Theme.lerpColor(Theme.RED, Theme.RED_BRIGHT, hr));
            modeValue(ctx, md, v, x1, right, midY);
        } else if (row instanceof StringSetting st) {
            boolean editable = editable(st);
            // Long values (Hidden Modules, custom texts) stop at half the row, so they never run over the label.
            int maxW = Math.max(90, cw / 2);
            if (editor != null && editor.target == st) {
                int ew = Math.min(maxW, Math.max(90, width(editor.text) + 16));
                editor.render(ctx, right - ew, midY - 7, right, midY + 7);
                return;
            }
            String full = st.get();
            String v = width(full) + 16 > maxW ? ellipsize(Role.BODY, full, maxW - 16) : full;
            int w = Math.min(maxW, Math.max(60, width(v) + 16));
            // A click anywhere on the row edits the value (or runs its action), so the box lights up with the row.
            // A read-only status value never does.
            boolean hv = (editable || st.clickAction() != null) && rowHover;
            button(ctx, "str:" + rowId(row, mod), right - w, midY - 7, right, midY + 7, hv,
                    Theme.CONTROL, Theme.CONTROL_HOVER, Theme.BORDER, Theme.BORDER_HOVER);
            text(ctx, v, right - w + (w - width(v)) / 2, midY - 4, Theme.TEXT, false);
            String hint = editable ? "click to edit" : st.hint();
            int hw = width(Role.SMALL, hint);
            text(ctx, hint, right - w - 8 - hw, midY - 3, Theme.TEXT_FAINT, Role.SMALL);
        } else if (row instanceof ColorSetting c) {
            String rid = rowId(row, mod);
            int n = ColorSetting.PALETTE.length, sz = SWATCH, gap = SWATCH_GAP;
            int sx = right - n * (sz + gap) + gap;
            int sel = -1;
            // A value of 0 means "follow the theme" (Keystrokes' key colours): no swatch is picked then.
            boolean themed = c.get() >>> 24 == 0;
            for (int i = 0; i < n && sel < 0 && !themed; i++) if ((c.get() | 0xFF000000) == ColorSetting.PALETTE[i]) sel = i;
            // The swatch under the mouse (as a click would pick it) grows a pixel on each side.
            int hi = rowHover ? swatchAt(mx, sx) : -1;
            float selGrow = 0f;
            for (int i = 0; i < n; i++) {
                float g = settled(animate("swh:" + rid + ":" + i, i == hi ? 1 : 0, 22));
                if (i == sel) selGrow = g;
                swatch(ctx, sx + i * (sz + gap), midY, sz, ColorSetting.PALETTE[i], g);
            }
            paletteRing(ctx, rid, sx, sel, midY, selGrow);
            if (editor != null && editor.target == c) {
                int ew = Math.max(62, width(editor.text) + 14);
                editor.render(ctx, sx - 8 - ew, midY - 7, sx - 8, midY + 7);
                return;
            }
            // Left of the palette: the current colour, ringed, when it isn't one of the swatches; otherwise a
            // small "#" chip. Either one opens a #RRGGBB field.
            int x = customChipX(sx);
            boolean hc = rowHover && mx >= x - 2 && mx < x + sz + 2;
            if (themed) {
                // "Theme": an empty ringed chip marked A (automatic) instead of a black swatch.
                float h = button(ctx, "hex:" + rid, x - 2, midY - 6, x + sz + 2, midY + 6, hc,
                        Theme.CONTROL, Theme.CONTROL_HOVER, Theme.BORDER, Theme.BORDER_HOVER);
                Theme.stroke(ctx, x - 2, midY - 6, x + sz + 2, midY + 6, Theme.alpha(0xFFFFFFFF, 0.6f + 0.4f * h), 4);
                int aw = width("A");
                text(ctx, "A", x + (sz - aw) / 2 + 1, midY - 4, Theme.lerpColor(Theme.TEXT_DIM, Theme.TEXT, h), false);
            } else if (sel < 0) {
                swatch(ctx, x, midY, sz, c.get() | 0xFF000000, 0f);
                Theme.stroke(ctx, x - 2, midY - 6, x + sz + 2, midY + 6, 0xFFFFFFFF, 4);
            } else {
                float h = button(ctx, "hex:" + rid, x - 2, midY - 6, x + sz + 2, midY + 6, hc,
                        Theme.CONTROL, Theme.CONTROL_HOVER, Theme.BORDER, Theme.BORDER_HOVER);
                int hw = width("#");
                text(ctx, "#", x + (sz - hw) / 2 + 1, midY - 4, Theme.lerpColor(Theme.TEXT_DIM, Theme.TEXT, h), false);
            }
        }
    }

    /** A mode chip's width for {@code value}: the text plus room for both chevrons, 70 at least. */
    private int modeChipW(String value) { return Math.max(70, width(value) + 30); }

    /**
     * A mode chip's value, centred between {@code x1} and {@code x2}. For {@link #MODE_SWAP_MS} after a cycle the old
     * value slides 8 px out and fades while the new one slides in from the side the cycle went (forward: from the
     * right), both clipped to the chip between its chevrons. Snapped to device pixels, so the text stays crisp.
     */
    private void modeValue(DrawContext ctx, ModeSetting md, String v, int x1, int x2, int midY) {
        int bw = x2 - x1;
        ModeSwap sw = modeSwaps.get(md);
        float e = 1f;
        if (sw != null) {
            double since = clock.getAsDouble() - sw.at();
            if (anims && since >= 0 && since < MODE_SWAP_MS) e = Motion.cubicOut((float) (since / MODE_SWAP_MS));
            else modeSwaps.remove(md);
        }
        if (sw == null || e >= 1f) {
            text(ctx, v, x1 + (bw - width(v)) / 2, midY - 4, Theme.TEXT, false);
            return;
        }
        ctx.enableScissor(x1 + 12, midY - 7, x2 - 12, midY + 7);
        try {
            slideText(ctx, sw.prev(), x1 + (bw - width(sw.prev())) / 2, midY - 4, -sw.dir() * 8f * e, 1f - e);
            slideText(ctx, v, x1 + (bw - width(v)) / 2, midY - 4, sw.dir() * 8f * (1f - e), e);
        } finally {
            ctx.disableScissor();
        }
    }

    /** Body text at (x, y) moved {@code dx} (snapped to a device pixel) and drawn at {@code alpha}. */
    private void slideText(DrawContext ctx, String s, int x, int y, float dx, float alpha) {
        if (alpha <= 0.004f) return;
        var m = ctx.getMatrices();
        float base = Theme.fade;
        m.pushMatrix();
        try {
            Theme.fade = base * alpha;
            m.translate(Theme.snap(ctx, dx), 0);
            text(ctx, s, x, y, Theme.TEXT, false);
        } finally {
            Theme.fade = base;
            m.popMatrix();
        }
    }

    /** A colour row's swatch size and the gap between swatches. */
    private static final int SWATCH = 9, SWATCH_GAP = 3;

    /** Which palette swatch an x picks (a click or the hover), for a palette starting at {@code paletteX}: each takes
     *  its own width and half the gap on either side. -1 for none. */
    private static int swatchAt(double mx, int paletteX) {
        if (mx < paletteX - 2) return -1;
        int i = (int) Math.floor((mx - paletteX + SWATCH_GAP / 2.0) / (SWATCH + SWATCH_GAP));
        return i >= 0 && i < ColorSetting.PALETTE.length ? i : -1;
    }

    /**
     * The white ring around a colour row's selected swatch (index {@code sel}, -1 for a colour that isn't in the
     * palette). Picking another swatch slides it there on a spring with a hint of overshoot, drawn at a device-pixel
     * x; it grows by {@code grow} with the swatch under it, keeping its 1 px gap. A colour outside the palette fades
     * it out where it is, and the next pick fades it back in on its swatch instead of flying in.
     */
    private void paletteRing(DrawContext ctx, String rid, int paletteX, int sel, int midY, float grow) {
        float a = settled(animate("swa:" + rid, sel >= 0 ? 1 : 0, 18));
        String key = "sw:" + rid;
        Motion.Spring s = springObj(key);
        if (sel >= 0) {
            float target = sel * (SWATCH + SWATCH_GAP);
            if (a < 0.5f) s.set(target);
            spring(key, target, 30f, 0.75f);
        }
        if (a <= 0f || !s.init) return;
        float fx = paletteX + s.x;
        int ix = (int) Math.floor(fx);
        var m = ctx.getMatrices();
        float base = Theme.fade;
        m.pushMatrix();
        try {
            Theme.fade = base * a;
            m.translate(Theme.snap(ctx, fx - ix), 0);
            if (grow > 0f) {
                float c = ix + SWATCH / 2f;
                m.translate(c, midY);
                m.scale((SWATCH + 4 + 2 * grow) / (SWATCH + 4), (12 + 2 * grow) / 12f);
                m.translate(-c, -midY);
            }
            Theme.stroke(ctx, ix - 2, midY - 6, ix + SWATCH + 2, midY + 6, 0xFFFFFFFF, 4);
        } finally {
            Theme.fade = base;
            m.popMatrix();
        }
    }

    /** x of the custom-colour chip left of a colour row's palette starting at {@code paletteX}. */
    private static int customChipX(int paletteX) { return paletteX - 3 - 8 - 9; }

    /**
     * One colour swatch, {@code sz} x 8 at x, centred on {@code midY}: a small rounded chip with a faint light
     * hairline, so near-black colours still show their edge on a dark row. {@code grow} (0..1, the hover) enlarges it
     * by up to a pixel on each side about its centre. The selected one's white ring (1 px gap, in the footprint the
     * old white plate had) is drawn by {@link #paletteRing}, so it can slide between swatches.
     */
    private static void swatch(DrawContext ctx, int x, int midY, int sz, int color, float grow) {
        var m = ctx.getMatrices();
        boolean grown = grow > 0f;
        if (grown) {
            float c = x + sz / 2f;
            m.pushMatrix();
            m.translate(c, midY);
            m.scale((sz + 2 * grow) / sz, (8 + 2 * grow) / 8f);
            m.translate(-c, -midY);
        }
        try {
            Theme.round(ctx, x, midY - 4, x + sz, midY + 4, color, Theme.R_XS);
            Theme.hairline(ctx, x, midY - 4, x + sz, midY + 4, Theme.alpha(0xFFFFFFFF, 0.12f), 2);
        } finally {
            if (grown) m.popMatrix();
        }
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
            how = "Pick a swatch, or click # (or the custom colour) for the full colour picker";
        } else if (row instanceof StringSetting st && editable(st)) {
            how = "Click to edit · Enter saves, Esc cancels";
        }
        if (row.equals("bindmode")) {
            out.add(new TipLine("Toggle: each press turns the mod on or off. Hold: on only while the key is held.", Theme.TEXT_SOFT));
            how = "Ctrl, Shift and Alt make the bind need that key held too";
        } else if (row.equals("share")) {
            out.add(new TipLine("A code (" + net.evadeclient.config.ModShare.PREFIX + "...) with this mod's settings, to paste into chat or a message.", Theme.TEXT_SOFT));
            how = "Copy puts it on your clipboard · Paste applies a code you copied";
        }
        if (resettable) how = how == null ? "Right-click the name or middle-click to reset to default" : how + " · right-click the name resets";
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

    /** The close fade is playing: input is swallowed (a close is never reversed). */
    private boolean closing() { return closingAt >= 0 || finishing; }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (closing()) return true;
        if (picker.isOpen()) return picker.mouseClicked(click.x(), click.y(), click.button());
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
                else if (bindKey(target, KeySetting.mouseCode(button))) { flashKey(target); listening = null; }
                return true;
            }
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) { clearKey(target); flashKey(target); }
            listening = null;
            return true;
        }

        // close button
        if (mx >= wx + ww - 24 && mx < wx + ww - 10 && my >= wy + 12 && my < wy + 26) { press("close"); close(); return true; }

        // scrollbar: drag the thumb, or page toward a click on the track
        if (button == 0 && onScrollbar(mx, my)) {
            float ty = thumbTop();
            if (my >= ty && my < ty + thumbH()) {
                sbDrag = true;
                sbGrab = my - ty;
                // Stop any glide where it is, so the thumb stays under the cursor until the first drag.
                scrollTarget = scroll;
                springObj("scroll").set((float) scroll);
            } else {
                scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget + (my < ty ? -sbViewH : sbViewH)));
            }
            lastScrollAt = clock.getAsDouble();
            return true;
        }

        // sidebar
        if (mx >= wx && mx < wx + SIDEBAR && my >= wy && my < wy + wh) {
            if (my >= navTop() && my < navBottom()) {
                for (NavHit hit : navLayout()) {
                    if (my >= hit.y && my < hit.y + NAV_ITEM_H) {
                        if (hit.nav.action != null) {
                            hit.nav.action.run();
                        } else if (hit.nav.hud) {
                            returnTo = null; // the editor comes back to this menu, not to where the menu came from
                            client.setScreen(new HudEditorScreen(this));
                        } else go(hit.nav.page);
                        return true;
                    }
                }
            }
            if (my >= footerTop() + 2 && my < wy + wh - 4 && mx >= wx + 10 && mx < wx + SIDEBAR - 10) {
                press("foot");
                go(Page.FRIENDS);
            }
            return true;
        }

        if (page == Page.PROFILES && selected == null) return clickProfiles(mx, my);
        if (page == Page.FRIENDS && selected == null) return clickFriends(mx, my);

        if (selected != null || page == Page.SETTINGS) {
            // back button / master toggle on settings header
            if (selected != null && my >= searchY && my < searchY + 18 && mx >= cx && mx < cx + 52) { press("back"); leaveModulePage(); return true; }
            if (selected != null && my >= headerY - 4 && my < headerY + 14 && mx >= controlRight() - 22 && mx < controlRight() + 2) {
                selected.toggle();
                return true;
            }
            Module rm = selected != null ? selected : settings();
            // Only once Reset has mostly faded in: a click where it is only just appearing (or fading out) does nothing.
            if (hasChangedSettings(rm) && anim.getOrDefault(resetKey(rm), 1f) >= 0.5f
                    && my >= headerY - 4 && my < headerY + 12 && mx >= resetX() && mx < resetX() + 52) {
                press("reset");
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
            resetScroll();
            return true;
        }
        if (my >= segY && my < segY + 22 && mx >= azX() && mx < azX() + AZ_W) {
            press("az");
            sortAz = !sortAz;
            resetScroll();
            return true;
        }

        // module list
        if (my < cy || my >= cy + ch || mx < cx || mx >= cx + cw) return super.mouseClicked(click, doubled);
        List<Module> mods = visibleModules();
        int cols = columns();
        int rh = rowH(), rg = rowGap();
        int cardW = (cw - rg * (cols - 1)) / cols;
        int si = scrollPx();
        for (int i = 0; i < mods.size(); i++) {
            int x = cx + (i % cols) * (cardW + rg);
            int y = cy + (i / cols) * (rh + rg) - si;
            if (mx < x || mx >= x + cardW || my < y || my >= y + rh) continue;
            Module mod = mods.get(i);
            int[] star = starRect(mod, x, y, cardW);
            if (button == 0 && mx >= star[0] && mx < star[2] && my >= star[1] && my < star[3]) {
                press("star:" + mod.getName());
                boolean pinned = net.evadeclient.config.MenuState.togglePin(mod);
                Notifications.push(mod.getName(), pinned ? "Pinned" : "Unpinned", pinned);
                return true;
            }
            boolean gear = hasSettingsPage(mod) && gearHit(mx, my, x, y, cardW);
            if (gear || (hasSettingsPage(mod) && button == 1)) {
                if (gear) press("gear:" + mod.getName());
                openModule(mod);
            } else if (button == 0) {
                mod.toggle();
            }
            return true;
        }
        return true;
    }

    /** The pin star's hit box {x1, y1, x2, y2} on a row/card at (x, y), just after the mod's name. */
    private int[] starRect(Module mod, int x, int y, int w) {
        int nameX, nameY, maxW;
        if (cards()) { nameX = x + 8; nameY = y + 16; maxW = w - 14; }
        else if (compact()) { nameX = x + 8; nameY = y + (COMPACT_H - 8) / 2; maxW = w - 46; }
        else { nameX = x + 16; nameY = y + 11; maxW = w - 150; }
        int sx = nameX + Math.min(maxW, width(Role.STRONG, ellipsize(Role.STRONG, mod.getName(), maxW))) + 5;
        if (cards()) sx = Math.min(sx, x + w - 14);
        return new int[]{sx - 2, nameY - 2, sx + 10, nameY + 10};
    }

    /** The pin star after a mod's name: gold when pinned, a faint outline while the row is hovered. */
    private void renderStar(DrawContext ctx, Module mod, int x, int y, int w, boolean rowHover, int mx, int my) {
        boolean pinned = net.evadeclient.config.MenuState.isPinned(mod);
        float hv = animate("starRow:" + mod.getName(), rowHover ? 1 : 0, 16);
        if (!pinned && hv < 0.01f) return;
        int[] r = starRect(mod, x, y, w);
        boolean over = rowHover && mx >= r[0] && mx < r[2] && my >= r[1] && my < r[3];
        int color = pinned ? Theme.WARN : over ? Theme.TEXT_SOFT : Theme.alpha(Theme.TEXT_FAINT, hv);
        // Pixel font: the smooth font has no star glyph.
        ctx.drawText(textRenderer, Text.literal(pinned ? "★" : "☆"), r[0] + 2, r[1] + 2, Theme.fa(color), false);
        if (over) setTip(mx, my, List.of(new TipLine(pinned ? "Unpin" : "Pin to the Pinned page", Theme.TEXT_SOFT)));
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
        int y = top - scrollPx();
        int right = controlRight();
        for (Object row : rowsFor(mod)) {
            if (my >= y && my < y + ROW_H - 2) {
                if (row.equals("bindmode") || row.equals("share")) {
                    if (button != 0) return true;
                    for (Chip c : chips(row, mod, right)) {
                        if (mx < c.x1 || mx >= c.x2) continue;
                        press("chip:" + rowId(row, mod) + ":" + c.id);
                        chipClicked(mod, c.id);
                        return true;
                    }
                    return true;
                }
                // Right-click on a changed setting's name puts it back to its default (as middle-click does anywhere).
                if (button == 1 && row instanceof Setting<?> rs && changed(rs) && mx < cx + 10 + width(rs.getName()) + 6) {
                    rs.reset();
                    return true;
                }
                // Middle-click puts one setting back to its default.
                if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
                    if (row instanceof Setting<?> st && changed(st)) st.reset();
                    return true;
                }
                String rid = rowId(row, mod);
                if (row.equals("keybind") || row instanceof KeySetting) {
                    Object self = row.equals("keybind") ? mod : row;
                    if (button == 1) {
                        press("key:" + rid);
                        clearKey(self);
                        flashKey(self);
                    } else if (button == 0) {
                        press("key:" + rid);
                        startListening(self);
                    }
                } else if (row instanceof StringSetting st) {
                    if (button != 0) return true;
                    if (st.clickAction() != null || editable(st)) press("str:" + rid);
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
                    // The chip as it was drawn (it eases to a new value's width).
                    int w = Math.round(anim.getOrDefault("mw:" + rid, (float) modeChipW(md.get())));
                    // Only the chip cycles: right half forward, left half (or a right-click) back.
                    if ((button == 0 || button == 1) && mx >= right - w && mx <= right) {
                        boolean forward = button == 0 && mx >= right - w / 2.0;
                        String prev = md.get();
                        press("mode:" + rid);
                        md.cycle(forward);
                        if (!prev.equals(md.get())) modeSwaps.put(md, new ModeSwap(prev, forward ? 1 : -1, clock.getAsDouble()));
                    }
                } else if (row instanceof ColorSetting c) {
                    if (button != 0) return true;
                    int sx = right - ColorSetting.PALETTE.length * (SWATCH + SWATCH_GAP) + SWATCH_GAP;
                    int chip = customChipX(sx);
                    if (mx >= chip - 2 && mx < chip + SWATCH + 2) {
                        picker.open(c, (chip - 2) * vs, my * vs); // the full colour picker (ColorPicker)
                        return true;
                    }
                    int i = swatchAt(mx, sx);
                    if (i >= 0) c.set(ColorSetting.PALETTE[i]);
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
        if (closing()) return true;
        if (picker.isOpen()) return picker.mouseDragged(click.x(), click.y());
        if (dragging != null) { updateSlider(click.x() / vs); return true; }
        if (sbDrag) { dragScrollbar(click.y() / vs); return true; }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(Click click) {
        picker.mouseReleased();
        dragging = null;
        sbDrag = false;
        pressedKey = null; // the pressed button springs back (a quick tap still shows its dip: see pressed())
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        if (closing()) return true;
        if (picker.isOpen()) return true;
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
        // Each notch moves the target; the spring blends notches in a row into one glide. Clamped here, not a frame
        // later, so the list never heads past an end and comes back.
        scrollTarget = clampTarget(scrollTarget - v * 32);
        if (v != 0) lastScrollAt = clock.getAsDouble();
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (closing()) return true;
        if (picker.isOpen()) return picker.keyPressed(input);
        int key = input.key();
        // An open text field takes every key (Enter applies, Escape cancels).
        if (editor != null) { editor.key(input); return true; }
        if (listening != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) { listening = null; return true; } // cancel: the key stays as it was
            // A bound or cleared key flashes its box to confirm it took.
            if (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) {
                clearKey(listening);
                flashKey(listening);
                listening = null;
                return true;
            }
            if (bindKey(listening, key)) { flashKey(listening); listening = null; }
            return true;
        }
        int menuKey = settings().menuKey.get();
        if (menuKeyDown && key != menuKey) menuKeyUsed = true;
        if (key == GLFW.GLFW_KEY_F && (input.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0) {
            // Ctrl+F (Cmd+F): jump to the search box from anywhere in the menu.
            returnTo = null;
            if (selected != null) back(); // slides the list back in from the left
            else if (page == Page.SETTINGS || page == Page.PROFILES || page == Page.FRIENDS) pageChanged(0);
            if (page == Page.SETTINGS || page == Page.PROFILES || page == Page.FRIENDS) {
                page = Page.ALL;
                resetScroll(); // a new page: its list starts at the top
            }
            searchFocused = true;
            return true;
        }
        boolean profiles = page == Page.PROFILES && selected == null;
        boolean friends = page == Page.FRIENDS && selected == null && friendsOn();
        boolean listPage = selected == null && page != Page.SETTINGS && page != Page.PROFILES && page != Page.FRIENDS;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (profiles && (profileFocused || !profileInput.isEmpty())) { profileInput = ""; profileFocused = false; return true; }
            if (friends && chatInput() && (friendFocused || !chatDraft.isEmpty())) { chatDraft = ""; friendFocused = false; return true; }
            if (friends && (friendFocused || !friendInput.isEmpty())) { friendInput = ""; friendFocused = false; return true; }
            if (selected != null) { leaveModulePage(); return true; }
            if (!search.isEmpty()) { search = ""; searchFocused = false; resetScroll(); return true; }
            close();
            return true;
        }
        if (input.isPaste() && (profiles || listPage || friends)) {
            String clip = client != null ? clean(client.keyboard.getClipboard()) : "";
            if (friends && chatInput()) {
                friendFocused = true;
                chatDraft = cap(chatDraft + clip, net.evadeclient.social.SocialChat.MAX_CHARS);
            } else if (friends) {
                friendFocused = true;
                friendInput = cap(friendInput + clip.replaceAll("[^A-Za-z0-9_]", ""), 16);
            } else if (profiles) {
                profileFocused = true;
                profileInput = cap(profileInput + clip, 24);
            } else {
                searchFocused = true;
                search = cap(search + clip, 32);
                resetScroll();
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
        if (friends && chatInput()) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (!chatDraft.isEmpty()) { chatDraft = chatDraft.substring(0, chatDraft.length() - 1); friendFocused = true; }
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                sendChatDraft();
                return true;
            }
        } else if (friends) {
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (!friendInput.isEmpty()) { friendInput = friendInput.substring(0, friendInput.length() - 1); friendFocused = true; }
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                if (friendFocused) addFriendFromInput();
                return true;
            }
        }
        if (listPage && key == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
            search = search.substring(0, search.length() - 1);
            searchFocused = true;
            resetScroll();
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
            double to = switch (key) {
                case GLFW.GLFW_KEY_PAGE_UP -> scrollTarget - sbViewH;
                case GLFW.GLFW_KEY_PAGE_DOWN -> scrollTarget + sbViewH;
                case GLFW.GLFW_KEY_HOME -> 0;
                case GLFW.GLFW_KEY_END -> maxScroll();
                default -> Double.NaN;
            };
            if (!Double.isNaN(to)) {
                scrollTarget = Math.max(0, Math.min(maxScroll(), to));
                lastScrollAt = clock.getAsDouble();
                return true;
            }
        }
        if (key == menuKey) {
            boolean typing = searchFocused || profileFocused || friendFocused;
            if (isModifier(key)) {
                // Decided on release: a tap closes, Shift + letter (or + wheel, + click) doesn't. Held keys repeat,
                // so only the first press starts the tap.
                if (!menuKeyDown) {
                    menuKeyDown = true;
                    menuKeyUsed = typing;
                }
                return true;
            }
            if (!typing && !profiles && !friends) { close(); return true; }
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean keyReleased(KeyInput input) {
        if (closing()) return true;
        if (menuKeyDown && input.key() == settings().menuKey.get()) {
            menuKeyDown = false;
            if (!menuKeyUsed && editor == null && listening == null) close();
            return true;
        }
        return super.keyReleased(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (closing()) return true;
        if (picker.isOpen()) return picker.charTyped(input);
        if (menuKeyDown) menuKeyUsed = true;
        if (editor != null) { if (input.isValidChar()) editor.insert(input.asString()); return true; }
        if (listening != null) return true;
        if (selected != null || page == Page.SETTINGS || !input.isValidChar()) return false;
        if (page == Page.PROFILES) {
            profileFocused = true;
            if (profileInput.length() < 24) profileInput += input.asString();
            return true;
        }
        if (page == Page.FRIENDS && friendsOn() && chatInput()) {
            // A message: any text, 256 characters at most.
            friendFocused = true;
            if (chatDraft.length() < net.evadeclient.social.SocialChat.MAX_CHARS) chatDraft += input.asString();
            return true;
        }
        if (page == Page.FRIENDS) {
            // Minecraft names: letters, digits and underscores, 16 at most.
            String c = input.asString();
            if (friendsOn() && c.matches("[A-Za-z0-9_]+") && friendInput.length() < 16) {
                friendFocused = true;
                friendInput += c;
            }
            return true;
        }
        // Typing anywhere starts a search, like a launcher.
        searchFocused = true;
        if (search.length() < 32) search += input.asString();
        resetScroll();
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
        rejectAt = clock.getAsDouble();
        rejectMsg = why;
    }

    // ---- Key Mode and Share rows ---------------------------------------

    /** A small button on a Key Mode or Share Settings row. */
    private record Chip(String id, String label, int x1, int x2, boolean active) {}

    /** The row's chips, laid out right to left from {@code right}. */
    private List<Chip> chips(Object row, Module mod, int right) {
        List<String[]> spec = new ArrayList<>();
        if (row.equals("bindmode")) {
            int mods = mod.getBindMods();
            spec.add(new String[]{"alt", "Alt", (mods & net.evadeclient.util.Keybinds.ALT) != 0 ? "1" : ""});
            spec.add(new String[]{"shift", "Shift", (mods & net.evadeclient.util.Keybinds.SHIFT) != 0 ? "1" : ""});
            spec.add(new String[]{"ctrl", "Ctrl", (mods & net.evadeclient.util.Keybinds.CTRL) != 0 ? "1" : ""});
            spec.add(null); // gap
            spec.add(new String[]{"hold", "Hold", mod.isHoldBind() ? "1" : ""});
            spec.add(new String[]{"toggle", "Toggle", mod.isHoldBind() ? "" : "1"});
        } else {
            spec.add(new String[]{"paste", "Paste code", ""});
            spec.add(new String[]{"copy", "Copy code", ""});
        }
        List<Chip> out = new ArrayList<>();
        int x2 = right;
        for (String[] c : spec) {
            if (c == null) { x2 -= 8; continue; }
            int w = width(Role.SMALL, c[1]) + 14;
            out.add(new Chip(c[0], c[1], x2 - w, x2, !c[2].isEmpty()));
            x2 -= w + 4;
        }
        return out;
    }

    private void chipClicked(Module mod, String id) {
        switch (id) {
            case "toggle" -> mod.setHoldBind(false);
            case "hold" -> mod.setHoldBind(true);
            case "ctrl" -> mod.setBindMods(mod.getBindMods() ^ net.evadeclient.util.Keybinds.CTRL);
            case "shift" -> mod.setBindMods(mod.getBindMods() ^ net.evadeclient.util.Keybinds.SHIFT);
            case "alt" -> mod.setBindMods(mod.getBindMods() ^ net.evadeclient.util.Keybinds.ALT);
            case "copy" -> {
                String code = net.evadeclient.config.ModShare.code(mod);
                if (code == null) { Notifications.push(mod.getName(), "Couldn't make a code", false); return; }
                client.keyboard.setClipboard(code);
                Notifications.push(mod.getName(), "Settings code copied", true);
                return;
            }
            case "paste" -> {
                var r = net.evadeclient.config.ModShare.apply(client.keyboard.getClipboard(), mod);
                Notifications.push(mod.getName(), r.ok() ? "Settings pasted from the code" : r.error(), r.ok());
                return;
            }
            default -> { return; }
        }
        EvadeClient.config.save();
    }

    /** Everything else bound to {@code code}, for the red conflict chip and its tooltip. */
    private static List<String> conflicts(int code, Object self) {
        if (code == GLFW.GLFW_KEY_UNKNOWN) return List.of();
        if (self instanceof Module m) return net.evadeclient.util.Keybinds.conflictNames(code, m.getBindMods(), m);
        return EvadeClient.modules.usersOf(code, self);
    }

    // ---- inline text fields -------------------------------------------

    private static boolean editable(StringSetting st) { return !(st instanceof StatusSetting) && st.clickAction() == null; }

    /** Longest value a text setting takes from the menu: its own cap when it sets one, else 32 (Nick Hider: 16). */
    private static int stringLimit(StringSetting st) {
        var nh = EvadeClient.modules.get(net.evadeclient.module.visuals.NickHider.class);
        if (nh != null && nh.nick == st) return Math.min(16, st.getMaxLength());
        return st.getMaxLength() < StringSetting.DEFAULT_MAX_LENGTH ? st.getMaxLength() : 32;
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
        double typedAt = clock.getAsDouble();
        int x1, y1, x2, y2;
        /** Pixels the text is scrolled left, so the caret stays in view in a field narrower than its text. */
        int scroll;

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
            int ty = (y1 + y2) / 2 - 4;
            // Keep the caret (or, with everything selected, the start) inside the field.
            int caretPx = all ? 0 : width(text.substring(0, Math.min(caret, text.length())));
            int room = Math.max(1, x2 - x1 - 12);
            scroll = Math.max(0, Math.min(scroll, caretPx));
            scroll = Math.max(scroll, caretPx - room);
            scroll = Math.max(0, Math.min(scroll, Math.max(0, width(text) - room)));
            int tx = x1 + 6 - scroll;
            boolean clip = width(text) > room;
            if (clip) ctx.enableScissor(x1 + 2, y1, x2 - 2, y2);
            try {
                if (all && !text.isEmpty()) Theme.fill(ctx, tx - 1, ty - 1, tx + width(text) + 1, ty + 9, Theme.alpha(Theme.RED, 0.45f));
                text(ctx, text, tx, ty, Theme.TEXT, false);
                if (!all && blink(typedAt)) {
                    int cxp = tx + caretPx;
                    Theme.fill(ctx, cxp, ty - 1, cxp + 1, ty + 9, Theme.TEXT);
                }
            } finally {
                if (clip) ctx.disableScissor();
            }
        }

        boolean contains(double mx, double my) { return mx >= x1 && mx < x2 && my >= y1 && my < y2; }

        /** Put the caret at the gap nearest to {@code mx}. */
        void clickAt(double mx) {
            all = false;
            int tx = x1 + 6 - scroll, best = 0;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i <= text.length(); i++) {
                double d = Math.abs(tx + width(text.substring(0, i)) - mx);
                if (d < bestD) { bestD = d; best = i; }
            }
            caret = best;
            typedAt = clock.getAsDouble();
        }

        void insert(String s) {
            StringBuilder ok = new StringBuilder();
            s.codePoints().filter(allowed).forEach(ok::appendCodePoint);
            if (ok.isEmpty()) return;
            if (all) { text = ""; caret = 0; all = false; }
            String add = cap(ok.toString(), Math.max(0, max - text.length()));
            text = text.substring(0, caret) + add + text.substring(caret);
            caret += add.length();
            typedAt = clock.getAsDouble();
        }

        void key(KeyInput in) {
            int k = in.key();
            typedAt = clock.getAsDouble();
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

    /** Move {@code n} by {@code steps} of its step size (a hundredth of its range when it has none) in direction {@code dir}. */
    private static void nudge(NumberSetting n, int dir, int steps) {
        double step = n.getStep() > 0 ? n.getStep() : (n.getMax() - n.getMin()) / 100;
        if (!(step > 0)) return;
        n.set(n.get() + dir * steps * step);
    }

    // ---- navigation -----------------------------------------------------

    private void openModule(Module mod) {
        commitEditor();
        listening = null;
        if (mod != selected) pageChanged(1);
        selected = mod;
        resetScroll();
    }

    private void go(Page p) {
        commitEditor();
        listening = null;
        // Fade through only when what the content shows changes (not a click on the page already on screen at rest).
        if (p != page || selected != null || !search.isEmpty() || scrollTarget != 0 || scroll >= 0.5) pageChanged(0);
        page = p;
        selected = null;
        returnTo = null;
        search = "";
        searchFocused = false;
        profileInput = "";
        profileFocused = false;
        pendingDelete = null;
        friendInput = "";
        friendFocused = false;
        friendConfirm = null;
        resetScroll();
        if (p == Page.FRIENDS) net.evadeclient.social.SocialClient.refresh();
    }

    private void back() {
        commitEditor();
        listening = null;
        if (selected != null) pageChanged(-1);
        selected = null;
        resetScroll();
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

    /**
     * Escape, the Menu Key or the close button. With Animations on the window fades out first (input is ignored
     * meanwhile) and the frame that finishes the fade closes it; a second close() during the fade closes at once.
     */
    @Override
    public void close() {
        if (anims && closingAt < 0 && !finishing) {
            commitEditor(); // the fade shows the value the field applied
            double now = clock.getAsDouble();
            closeFrom = windowState(now);
            closingAt = now;
            return;
        }
        finishClose();
    }

    /** Queue the real close for after this frame: ThreadExecutor.send always queues, where execute would run it here. */
    private void queueFinishClose() {
        finishing = true;
        double at = closingAt;
        // Skipped if this close was already finished (a second close()) or the menu was shown again before the task
        // ran: either one resets closingAt.
        if (client != null) client.send(() -> { if (closingAt == at) finishClose(); });
        else finishClose();
    }

    /** Close for real: save, then leave for the screen the menu came from, or the game. */
    private void finishClose() {
        finishing = true; // input stays swallowed
        closingAt = -1;   // the fade is over: a close task still queued for it finds nothing to do
        commitEditor();
        EvadeClient.config.save();
        // Something else may have replaced the menu during the fade (a disconnect, a server screen): never close that.
        if (client == null || client.currentScreen != this) return;
        if (returnTo != null) {
            Screen to = returnTo;
            returnTo = null;
            client.setScreen(to);
            return;
        }
        super.close();
    }

    @Override
    public void tick() {
        super.tick();
        // Frames normally finish the close fade; this ends it even if none are drawn (a minimised window) or the
        // queued close never ran, so an invisible menu can never be left on screen swallowing input.
        if (closingAt >= 0 && clock.getAsDouble() - closingAt >= CLOSE_MS + 250) finishClose();
    }

    @Override
    public void removed() {
        commitEditor(); // e.g. the HUD editor opened over a half-typed value
        picker.close();
        // Replaced in the middle of the close fade (a disconnect): still save, as the close would have.
        if (closingAt >= 0 && !finishing) {
            finishing = true;
            EvadeClient.config.save();
        }
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

    // ---- buttons: hover and press feedback ---------------------------

    /**
     * A push button's shape, from x1,y1 to x2,y2. Its fill and border cross-fade to their hover colours (about
     * 100 ms). While it is held ({@link #pressed}) it darkens a little and dips about 3/4 px on each side (snapped to a
     * device pixel) on a quick, slightly bouncy spring, and springs back on release. Only the shape is scaled: the
     * caller draws the label and icon after this returns, so text stays crisp. A button whose border colours equal
     * its fill colours is drawn without a ring. Returns the hover amount (0..1) for the label and icon to blend with.
     */
    private float button(DrawContext ctx, String id, int x1, int y1, int x2, int y2, boolean hover,
                         int fill, int fillHover, int border, int borderHover) {
        float h = animate("bh:" + id, hover ? 1 : 0, 22);
        float p = Math.max(0f, spring("bp:" + id, pressed(id) ? 1 : 0, 40f, 0.7f));
        float in = Theme.snap(ctx, 0.75f * p);
        int f = mix(fill, fillHover, h);
        f = Theme.lerpColor(f, f & 0xFF000000, 0.12f * p); // darker, at the same opacity
        boolean ring = border != fill || borderHover != fillHover;
        int w = x2 - x1, ht = y2 - y1;
        boolean dip = in > 0f && 2 * in < w && 2 * in < ht;
        var m = ctx.getMatrices();
        if (dip) {
            // Scaled about the centre so each edge moves in by exactly `in`: whole device pixels, so no edge blurs.
            float cxm = (x1 + x2) / 2f, cym = (y1 + y2) / 2f;
            m.pushMatrix();
            m.translate(cxm, cym);
            m.scale(1f - 2f * in / w, 1f - 2f * in / ht);
            m.translate(-cxm, -cym);
        }
        try {
            if (ring) Theme.roundBordered(ctx, x1, y1, x2, y2, f, mix(border, borderHover, h));
            else Theme.round(ctx, x1, y1, x2, y2, f);
        } finally {
            if (dip) m.popMatrix();
        }
        return h;
    }

    /** A click landed on the button or control {@code id} (see {@link #button}): it shows pressed until the mouse is
     *  released, and for at least {@link #PRESS_MIN_MS}. */
    private void press(String id) {
        pressedKey = id;
        tapKey = id;
        pressedAt = clock.getAsDouble();
    }

    /** Whether {@code id} looks pressed: held down, or tapped less than {@link #PRESS_MIN_MS} ago. */
    private boolean pressed(String id) {
        if (id.equals(pressedKey)) return true;
        if (!id.equals(tapKey)) return false;
        double since = clock.getAsDouble() - pressedAt;
        return since >= 0 && since < PRESS_MIN_MS;
    }

    /**
     * {@code a} blended toward {@code b} by {@code t}, in premultiplied alpha: a fade between an opaque and a
     * translucent colour (a grey button and a faint red one) never passes through a colour brighter than both ends,
     * as a straight per-channel blend does when it carries the translucent colour's full RGB into a mostly opaque mix.
     * The same as {@link Theme#lerpColor} for two opaque colours.
     */
    private static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        if (t == 0f) return a;
        if (t == 1f) return b;
        float aa = (a >>> 24) / 255f, ba = (b >>> 24) / 255f;
        float oa = aa + (ba - aa) * t;
        if (oa <= 0f) return 0;
        int r = mixChannel(a >> 16 & 255, aa, b >> 16 & 255, ba, t, oa);
        int g = mixChannel(a >> 8 & 255, aa, b >> 8 & 255, ba, t, oa);
        int bl = mixChannel(a & 255, aa, b & 255, ba, t, oa);
        return Math.round(oa * 255f) << 24 | r << 16 | g << 8 | bl;
    }

    private static int mixChannel(int ca, float aa, int cb, float ba, float t, float oa) {
        float v = (ca * aa + (cb * ba - ca * aa) * t) / oa;
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    /** A settings row's id for its control's animations and press state, unique across modules (two modules can
     *  have settings of the same name, and every module has a Toggle Key row). */
    private static String rowId(Object row, Module mod) {
        return mod.getName() + "/" + (row instanceof Setting<?> st ? st.getName() : row instanceof String r ? r : "keybind");
    }

    /** The animation key of a module page's Reset button (see renderSettingsPage): one per module, so a page never
     *  shows another page's Reset fading out. */
    private static String resetKey(Module mod) { return "reset:" + mod.getName(); }

    /** Start waiting for a key for {@code target} (a module's keybind or a KeySetting). */
    private void startListening(Object target) {
        listening = target;
        listenAt = clock.getAsDouble();
    }

    /** {@code target}'s key box just took a key (or was cleared): it flashes to confirm (see {@link #keyFlash}). */
    private void flashKey(Object target) {
        keyFlashAt.put(target, clock.getAsDouble());
    }

    /** How strongly {@code row}'s key box is flashing at {@code now}: up to 1 within the first fifth of
     *  {@link #KEY_FLASH_MS}, then easing back to 0. Never with Animations off. */
    private float keyFlash(Object row, double now) {
        Double at = keyFlashAt.get(row);
        if (at == null) return 0f;
        double t = (now - at) / KEY_FLASH_MS;
        if (!anims || t < 0 || t >= 1) {
            keyFlashAt.remove(row);
            return 0f;
        }
        if (t < 0.2) return Motion.cubicOut((float) (t / 0.2));
        float u = (float) (1 - (t - 0.2) / 0.8);
        return u * u;
    }

    /** Whether a caret blinking every 500 ms since {@code since} ({@link #clock} ms) is in its shown half. */
    private static boolean blink(double since) {
        return Math.floorMod((long) Math.floor((clock.getAsDouble() - since) / 500), 2L) == 0;
    }

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

    // ------------------------------------------------------------------ small API bridges

    /** A setting's description, or "" when it has none. */
    private static String descriptionOf(Setting<?> st) {
        String d = st.getDescription();
        return d == null ? "" : d;
    }

    /** Whether {@code profile} is linked to {@code address}, with addresses compared the way ProfileManager stores them. */
    private static boolean isLinked(String profile, String address) {
        return EvadeClient.profiles.isLinked(profile, address);
    }

    /** Put a HUD element back at its default spot. */
    private static void resetPosition(HudElementModule hem) {
        hem.resetPosition();
    }

    /** A module's one-line description, or why it can't be used here. */
    private static String blurb(net.evadeclient.module.Module mod) {
        if (net.evadeclient.util.ServerRules.isDisabled(mod)) return "Disabled by this server";
        String addon = net.evadeclient.addon.EvadeAddons.ownerName(mod);
        return addon == null ? mod.getDescription() : "Addon: " + addon + "  ·  " + mod.getDescription();
    }

    private static int blurbColor(net.evadeclient.module.Module mod) {
        return net.evadeclient.util.ServerRules.isDisabled(mod) ? Theme.WARN : Theme.TEXT_DIM;
    }
}
