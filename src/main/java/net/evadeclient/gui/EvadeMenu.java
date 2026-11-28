package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.Kit;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.SearchField;
import net.evadeclient.gui.menu.FriendsPage;
import net.evadeclient.gui.menu.MenuHost;
import net.evadeclient.gui.menu.MenuPage;
import net.evadeclient.gui.menu.MenuScroll;
import net.evadeclient.gui.menu.MenuSidebar;
import net.evadeclient.gui.menu.ModsPage;
import net.evadeclient.gui.menu.ProfilesPage;
import net.evadeclient.gui.menu.SettingsPage;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.render.ui.UiShape;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.Setting;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.StringHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Evade hub ("Obsidian", GUI 3.0): one glass window with a sidebar (pages, a tools grid and your account chip)
 * and a content area showing a module list (List, Cards or Compact), a module's settings, Profiles or Friends. Built
 * on the GUI kit ({@code gui/kit}): every control has hover, press and keyboard focus (Tab / Shift+Tab, Enter,
 * arrows), and every animation follows Client Settings › Animations (Full, Reduced, Off).
 *
 * <p>The window is laid out in design px (about 900 x 532) and drawn at the largest whole number of device px per
 * design px that fits, so it looks the same at GUI 2 and 3 and stays crisp at 4K. This class owns the window, its
 * open/close and page transitions, input routing and the state shared by the pages ({@code gui/menu/*}).
 */
public class EvadeMenu extends Screen implements MenuHost {
    private static MenuPage page = MenuPage.ALL;
    private static Module selected;
    /** Sort the module list A-Z instead of the curated order (kept for the session). */
    private static boolean sortAz;
    private boolean enabledOnly;
    /** The screen to go back to when the menu was opened from another one (the HUD editor's right-click). */
    private Screen returnTo;

    private final Kit kit = new Kit();
    private final MenuScroll scroll = new MenuScroll();
    /** The colour picker pop-over (opened from a colour row); it gets input before everything else. */
    public final ColorPicker picker = new ColorPicker();
    private final MenuSidebar sidebar = new MenuSidebar(this);
    private final ModsPage modsPage = new ModsPage(this);
    private final SettingsPage settingsPage = new SettingsPage(this);
    private final ProfilesPage profilesPage = new ProfilesPage(this);
    private final FriendsPage friendsPage = new FriendsPage(this);

    /** When the menu was last put on screen ({@link #clock} ms): the intro animation runs from here. */
    private double openedAt;
    /** When the close fade started ({@link #clock} ms), or -1 while the menu is open. */
    private double closingAt = -1;
    /** The close fade has played and the real close is queued (or done), so it is only queued once. */
    private boolean finishing;
    private static final long CLOCK_ORIGIN = System.nanoTime();
    /** The real animation clock: milliseconds since the client loaded, monotonic. */
    public static final java.util.function.DoubleSupplier SYSTEM_CLOCK = () -> (System.nanoTime() - CLOCK_ORIGIN) / 1e6;
    /** Milliseconds for animations (the menu's, Zoom's). Tests swap in a fixed-step clock and put
     *  {@link #SYSTEM_CLOCK} back after. */
    public static java.util.function.DoubleSupplier clock = SYSTEM_CLOCK;
    private double lastFrame = clock.getAsDouble();

    /** A modifier Menu Key (e.g. Right Shift) closes the menu when it is released, if nothing else was pressed,
     *  typed, clicked or scrolled meanwhile: Shift + letter types a capital and Shift + wheel steps by 10. */
    private boolean menuKeyDown, menuKeyUsed;
    /** The last input was a key (not the mouse): focus moves show their ring. */
    private boolean keyboard;

    /** When the content last changed page ({@link #clock} ms) and which way: +1 deeper, -1 back, 0 another page. */
    private double pageAt = -1e9;
    private int pageDir;

    /** Text fields of the pages (kit ids). */
    public static final String SEARCH = "search";

    // layout (design px, recomputed each frame)
    private int wx, wy, ww, wh;
    public static final int SIDEBAR_W = 184;
    private static final int DESIGN_W = 900, DESIGN_H = 532;
    /** Window open: fade (dur.quick) while it grows from 97% and rises 10 px (dur.slow); close: 120 ms. */
    private static final double OPEN_FADE_MS = Motion.DUR_QUICK, OPEN_MOVE_MS = Motion.DUR_SLOW, CLOSE_MS = 120;

    public EvadeMenu() {
        super(Text.literal("Evade"));
        selected = null; // opening the menu always starts on the list, not wherever it was last
    }

    /** Test hook: jump the menu straight to the Profiles page. */
    public static void openProfilesForTest() { page = MenuPage.PROFILES; selected = null; }

    /** Test hook: back to the All mods page (what a fresh menu shows). */
    public static void openAllForTest() { page = MenuPage.ALL; selected = null; }

    /** Test hook: jump to the Friends page, on a tab (0 Friends, 1 Requests, 2 Party, 3 Chat, 4 Settings). */
    public static void openFriendsForTest(int tab) {
        page = MenuPage.FRIENDS;
        selected = null;
        FriendsPage.setTab(tab);
    }

    /** Open the menu, optionally straight onto a module's settings page. */
    public static EvadeMenu open(Module select) { return open(select, null); }

    /** Open the menu on a module page from another screen: Escape (or Back) there returns to {@code returnTo}. */
    public static EvadeMenu open(Module select, Screen returnTo) {
        EvadeMenu menu = new EvadeMenu();
        if (select != null) selected = select;
        menu.returnTo = returnTo;
        return menu;
    }

    /** Each time the menu is put on screen: the intro starts here (a resize runs init again, not this). */
    @Override
    public void onDisplayed() {
        super.onDisplayed();
        openedAt = clock.getAsDouble();
        lastFrame = openedAt;
        closingAt = -1;
        finishing = false;
        if (selected != null) { pageAt = openedAt; pageDir = 1; }
    }

    private ClientSettings settings() { return EvadeClient.modules.get(ClientSettings.class); }

    private static Motion.Level level() { return Motion.level(); }

    // ------------------------------------------------------------------ MenuHost

    @Override public Kit kit() { return kit; }
    @Override public MenuScroll scroll() { return scroll; }
    @Override public double now() { return clock.getAsDouble(); }
    @Override public int cx() { return wx + SIDEBAR_W + 20; }
    @Override public int cw() { return ww - SIDEBAR_W - 40; }
    @Override public int top() { return wy + 14; }
    @Override public int cy() { return wy + 92; }
    @Override public int ch() { return wy + wh - 10 - cy(); }
    @Override public int headerRight() { return closeX() - 8; }
    @Override public MenuPage page() { return page; }
    @Override public Module selected() { return selected; }
    @Override public String search() { return SearchField.text(kit, SEARCH); }
    @Override public boolean enabledOnly() { return enabledOnly; }
    @Override public void enabledOnly(boolean on) { if (on != enabledOnly) { enabledOnly = on; scroll.reset(); } }
    @Override public boolean sortAz() { return sortAz; }
    @Override public void sortAz(boolean on) { if (on != sortAz) { sortAz = on; scroll.reset(); } }
    @Override public ColorPicker picker() { return picker; }

    @Override
    public void openPicker(ColorSetting c, int x, int y) {
        picker.open(c, x * vs, y * vs);
    }

    private int closeX() { return wx + ww - 16 - 20; }

    // ------------------------------------------------------------------ text cache

    private final Map<String, String> fitCache = new HashMap<>();
    private boolean fitSmooth;

    @Override
    public String fit(String s, Role r, int max) {
        if (s == null) return "";
        if (fitSmooth != kit.smooth || fitCache.size() > 4000) { fitCache.clear(); fitSmooth = kit.smooth; }
        String key = r.ordinal() + ":" + max + ":" + s;
        String v = fitCache.get(key);
        if (v == null) {
            v = kit.ellipsize(s, r, max);
            fitCache.put(key, v);
        }
        return v;
    }

    // ------------------------------------------------------------------ modules

    @Override
    public List<Module> modulesFor(MenuPage p, String filter) {
        List<Module> out = new ArrayList<>();
        String f = filter.toLowerCase(Locale.ROOT);
        for (Module mod : EvadeClient.modules.all()) {
            if (mod.getCategory() == Category.CLIENT) continue;
            if (p.category != null && mod.getCategory() != p.category) continue;
            if (p == MenuPage.PINNED && !net.evadeclient.config.MenuState.isPinned(mod)) continue;
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
            String d = st.getDescription();
            if (d != null && !d.isEmpty()) hay.append(' ').append(d);
        }
        String h = hay.toString().toLowerCase(Locale.ROOT);
        for (String word : f.trim().split("\\s+")) if (!h.contains(word)) return false;
        return true;
    }

    /** The page whose modules the list shows: a search looks through every category. */
    private MenuPage listPage() { return search().isBlank() ? page : MenuPage.ALL; }

    /** Modules to show, after the search box, the All/Enabled filter and the A-Z sort. */
    @Override
    public List<Module> visibleModules() {
        List<Module> list = modulesFor(listPage(), search());
        if (enabledOnly) list.removeIf(mod -> !mod.isEnabled());
        if (sortAz) list.sort(Comparator.comparing(Module::getName, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        settings().applyTheme();
        Tokens.refresh();
        if (settings().blur.get()) super.renderBackground(ctx, mouseX, mouseY, delta);
        // The scrim fades with the window (the vanilla blur can't fade: it is on or off).
        float a = windowState(clock.getAsDouble()).alpha();
        Paint.scrim(ctx, width, height, a);
    }

    /** How the whole window looks this frame: opacity, scale about the screen centre, and offset below its place. */
    private record WindowState(float alpha, float scale, float dy) {}

    private static final WindowState AT_REST = new WindowState(1f, 1f, 0f);
    private WindowState closeFrom = AT_REST;

    /**
     * The window's open or close animation at {@code now}. Full: fades in over 150 ms while it grows from 97% and
     * rises 10 px over 320 ms (ease.standard); closes in 120 ms, shrinking 1.5% and sinking 6 px (ease.exit). Reduced:
     * the same fades with no movement. Off: simply there.
     */
    private WindowState windowState(double now) {
        boolean full = level() == Motion.Level.FULL;
        if (closingAt >= 0) {
            if (now < closingAt && !finishing) closingAt = now;
            float c = finishing ? 1f : Motion.exit(Motion.clamp01((float) ((now - closingAt) / CLOSE_MS)));
            WindowState f = closeFrom;
            return full ? new WindowState(f.alpha() * (1f - c), f.scale() + (0.985f - f.scale()) * c, f.dy() + (6f - f.dy()) * c)
                    : new WindowState(f.alpha() * (1f - c), 1f, 0f);
        }
        if (level() == Motion.Level.OFF) return AT_REST;
        if (now < openedAt) openedAt = now - OPEN_MOVE_MS;
        float a = Motion.standard(Motion.clamp01((float) ((now - openedAt) / Math.min(OPEN_FADE_MS, Motion.duration((int) OPEN_FADE_MS)))));
        if (!full) return a >= 1f ? AT_REST : new WindowState(a, 1f, 0f);
        float p = Motion.standard(Motion.clamp01((float) ((now - openedAt) / OPEN_MOVE_MS)));
        return p >= 1f && a >= 1f ? AT_REST : new WindowState(a, 0.97f + 0.03f * p, (1f - p) * 10f);
    }

    // ---- fitting the screen: menu units to GUI units, and screen pixels per menu pixel
    private float vs = 1f;
    private double menuPx = 2;

    private void updateScale() {
        if (client == null) { vs = 1f; return; }
        var win = client.getWindow();
        double gs = Math.max(1, win.getScaleFactor());
        double fit = Math.min(win.getFramebufferWidth() / (double) DESIGN_W, win.getFramebufferHeight() / (double) DESIGN_H);
        double k = fit >= 1 ? Math.min(gs, Math.floor(fit)) : fit;
        vs = (float) (k / gs);
        menuPx = k;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        updateScale();
        int realW = width, realH = height;
        width = Math.round(realW / vs);
        height = Math.round(realH / vs);
        var mm = ctx.getMatrices();
        mm.pushMatrix();
        mm.scale(vs, vs);
        UiFont.designG = Math.max(UiFont.MIN_G, Math.min(UiFont.MAX_G, (int) Math.ceil(menuPx - 0.01)));
        try {
            int hx = picker.isOpen() ? -10000 : (int) Math.floor(mouseX / vs), hy = picker.isOpen() ? -10000 : (int) Math.floor(mouseY / vs);
            renderMenu(ctx, hx, hy);
        } finally {
            UiFont.designG = 0;
            mm.popMatrix();
            width = realW;
            height = realH;
        }
        picker.render(ctx, mouseX, mouseY, width, height);
    }

    private void renderMenu(DrawContext ctx, int mouseX, int mouseY) {
        double now = clock.getAsDouble();
        float dt = (float) Math.max(0, Math.min(0.1, (now - lastFrame) / 1000));
        lastFrame = now;
        scroll.frame(dt);

        ww = Math.min(width - 20, 880);
        wh = Math.min(height - 12, 520);
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;
        // Toasts sit below the window's close button while the menu is open.
        Notifications.screenTopInset = Math.round((wy + 50) * vs);

        WindowState ws = windowState(now);
        var m = ctx.getMatrices();
        float base = Theme.fade;
        kit.begin(ctx, mouseX, mouseY);
        m.pushMatrix();
        try {
            m.translate(width / 2f, height / 2f + Theme.snap(ctx, ws.dy()));
            m.scale(ws.scale(), ws.scale());
            m.translate(-width / 2f, -height / 2f);
            Theme.fade = base * ws.alpha();
            drawWindow(ctx, mouseX);
            sidebar.render(kit, wx, wy, SIDEBAR_W, wh);

            float pt = pageProgress(now);
            float pageBase = Theme.fade;
            boolean moving = pt < 1f;
            if (moving) {
                Theme.fade = pageBase * pt;
                m.pushMatrix();
                if (level() == Motion.Level.FULL) {
                    float from = pageDir > 0 ? 24f : pageDir < 0 ? -16f : 0f;
                    m.translate(Theme.snap(ctx, from * (1f - pt)), pageDir == 0 ? Theme.snap(ctx, (1f - pt) * 6f) : 0f);
                }
            }
            try {
                if (selected != null) settingsPage.render(kit, selected, true);
                else if (page == MenuPage.SETTINGS) settingsPage.render(kit, settings(), false);
                else if (page == MenuPage.PROFILES) profilesPage.render(kit);
                else if (page == MenuPage.FRIENDS) friendsPage.render(kit);
                else modsPage.render(kit);
            } finally {
                if (moving) {
                    m.popMatrix();
                    Theme.fade = pageBase;
                }
            }
            Button.icon(kit, "close", closeX(), top() + 4, 24, "close", "Close", true, this::close);
            kit.end();
        } finally {
            Theme.fade = base;
            m.popMatrix();
        }
        if (closingAt >= 0 && !finishing && now - closingAt >= CLOSE_MS) queueFinishClose();
    }

    /**
     * The window: e1 glass with its ambient and contact shadows, a specular rim that follows the cursor, a faint
     * accent light leaking from below, the darker sidebar plate and the hairline between them.
     */
    private void drawWindow(DrawContext ctx, int mouseX) {
        int x2 = wx + ww, y2 = wy + wh;
        // Light leaking from under the window (additive, kept low): the only red in the frame.
        if (Paint.gpu() && Tokens.glows() && level() != Motion.Level.OFF)
            UiShape.glow(ctx, wx + ww * 0.18f, y2 - 30, x2 - ww * 0.18f, y2 - 4, 14, 26, Theme.alpha(Tokens.A600, 0.20f));
        Paint.surface(ctx, wx, wy, x2, y2, Tokens.R_LG, Tokens.E1, 0, Math.max(0, Math.min(ww, mouseX - wx)));
        int sx2 = wx + SIDEBAR_W;
        int plate = Theme.alpha(Tokens.N0, Tokens.reduceTransparency ? 0.45f : 0.32f);
        if (Paint.gpu()) {
            UiShape.shape(wx + 0.5f, wy + 0.5f, sx2, y2 - 0.5f).radii(Tokens.R_LG - 0.5f, 0, 0, Tokens.R_LG - 0.5f)
                    .vertical(plate, Theme.alpha(Tokens.N0, Tokens.reduceTransparency ? 0.5f : 0.40f)).draw(ctx);
            float px = 1f / Theme.deviceScale(ctx);
            UiShape.shape(sx2, wy + 10, sx2 + px, y2 - 10).mirror(Theme.alpha(0xFFFFFFFF, 0.07f), 0x00FFFFFF, false, 0.35f).draw(ctx);
        } else {
            Theme.round(ctx, wx + 1, wy + 1, sx2, y2 - 1, plate, Math.round(Tokens.R_LG - 1), Theme.TL | Theme.BL);
            Theme.fill(ctx, sx2, wy + 1, sx2 + 1, y2 - 1, Theme.alpha(0xFFFFFFFF, 0.06f));
        }
    }

    /** How far the page's fade-through is: 0 just changed, 1 at rest (dur.base, ease.standard; Off: 1). */
    private float pageProgress(double now) {
        int d = Motion.duration(Motion.DUR_BASE);
        if (d <= 0) return 1f;
        if (now < pageAt) pageAt = now - d;
        return Motion.standard(Motion.clamp01((float) ((now - pageAt) / d)));
    }

    @Override
    public void pageChanged(int dir) {
        pageAt = clock.getAsDouble();
        pageDir = dir;
    }

    // ------------------------------------------------------------------ input

    /** The close fade is playing: input is swallowed (a close is never reversed). */
    private boolean closing() { return closingAt >= 0 || finishing; }

    private double dx(double x) { return x / vs; }
    private double dy(double y) { return y / vs; }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (closing()) return true;
        if (picker.isOpen()) return picker.mouseClicked(click.x(), click.y(), click.button());
        if (menuKeyDown) menuKeyUsed = true;
        keyboard = false;
        double mx = dx(click.x()), my = dy(click.y());
        int button = click.button();
        if (settingsPage.preClick(mx, my, button)) return true;
        kit.mouseClicked(mx, my, button);
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        picker.mouseReleased();
        kit.mouseReleased(dx(click.x()), dy(click.y()), click.button());
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double ddx, double ddy) {
        if (closing()) return true;
        if (picker.isOpen()) return picker.mouseDragged(click.x(), click.y());
        kit.mouseDragged(dx(click.x()), dy(click.y()), click.button());
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        if (closing() || picker.isOpen()) return true;
        if (menuKeyDown) menuKeyUsed = true;
        double mx = dx(mouseX), my = dy(mouseY);
        double amount = v != 0 ? v : h; // Shift + wheel arrives as horizontal scrolling on macOS
        if (amount == 0) return true;
        if (mx >= wx && mx < wx + SIDEBAR_W) { sidebar.wheel(amount); return true; }
        if (selected != null || page == MenuPage.SETTINGS) {
            if (settingsPage.wheel(amount, client != null && client.isShiftPressed())) return true;
        }
        if (kit.mouseScrolled(mx, my, amount)) return true;
        scroll.wheel(v);
        return true;
    }

    /** The id of the focused text field, or null. */
    private String typingIn() {
        for (String id : new String[]{SEARCH, ProfilesPage.FIELD, FriendsPage.ADD, FriendsPage.CHAT})
            if (kit.isFocused(id)) return id;
        return null;
    }

    private boolean onListPage() { return selected == null && page.isList(); }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (closing()) return true;
        if (picker.isOpen()) return picker.keyPressed(input);
        keyboard = true;
        int key = input.key();
        if (settingsPage.keyPressed(input)) return true;
        int menuKey = settings().menuKey.get();
        if (menuKeyDown && key != menuKey) menuKeyUsed = true;
        if (key == GLFW.GLFW_KEY_F && (input.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0) {
            // Ctrl+F (Cmd+F): jump to the search box from anywhere in the menu.
            returnTo = null;
            if (selected != null) back();
            if (!page.isList()) { pageChanged(0); page = MenuPage.ALL; scroll.reset(); }
            kit.focus(SEARCH, false);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (kit.popup() != null) return kit.keyPressed(key, input.modifiers());
            String t = typingIn();
            if (t != null) {
                if (!SearchField.text(kit, t).isEmpty()) {
                    SearchField.set(kit, t, "");
                    if (t.equals(SEARCH)) scroll.reset();
                } else {
                    kit.focus(null, false);
                }
                return true;
            }
            if (selected != null) { leaveModulePage(); return true; }
            if (onListPage() && !search().isEmpty()) { SearchField.set(kit, SEARCH, ""); scroll.reset(); return true; }
            close();
            return true;
        }
        boolean enter = key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER;
        String typing = typingIn();
        if (enter && typing != null) {
            switch (typing) {
                case SEARCH -> {
                    // Enter toggles the module when the search has narrowed the list down to one.
                    List<Module> vis = visibleModules();
                    if (vis.size() == 1) vis.get(0).toggle();
                }
                case ProfilesPage.FIELD -> profilesPage.create();
                default -> friendsPage.submit();
            }
            return true;
        }
        if (input.isPaste() && typing == null && !(selected != null || page == MenuPage.SETTINGS)) {
            String clip = client != null ? clean(client.keyboard.getClipboard()) : "";
            String target = fieldFor();
            if (target != null) {
                kit.focus(target, false);
                SearchField.set(kit, target, SearchField.text(kit, target) + clip);
                sanitize();
                if (target.equals(SEARCH)) scroll.reset();
            }
            return true;
        }
        String before = typing == null ? null : SearchField.text(kit, typing);
        if (kit.keyPressed(key, input.modifiers())) {
            if (typing != null && !before.equals(SearchField.text(kit, typing))) { sanitize(); if (typing.equals(SEARCH)) scroll.reset(); }
            return true;
        }
        if (onListPage() && modsPage.keyNav(key)) return true;
        double to = switch (key) {
            case GLFW.GLFW_KEY_PAGE_UP -> scroll.target - ch();
            case GLFW.GLFW_KEY_PAGE_DOWN -> scroll.target + ch();
            case GLFW.GLFW_KEY_HOME -> 0;
            case GLFW.GLFW_KEY_END -> scroll.max();
            default -> Double.NaN;
        };
        if (!Double.isNaN(to)) { scroll.to(to); return true; }
        if (key == menuKey) {
            boolean typingNow = typing != null;
            if (isModifier(key)) {
                if (!menuKeyDown) {
                    menuKeyDown = true;
                    menuKeyUsed = typingNow;
                }
                return true;
            }
            if (!typingNow && page != MenuPage.PROFILES && page != MenuPage.FRIENDS) { close(); return true; }
        }
        return super.keyPressed(input);
    }

    /** Where unfocused typing goes on this page (the search on lists, the name field on Profiles, ...). */
    private String fieldFor() {
        if (selected != null || page == MenuPage.SETTINGS) return null;
        if (page == MenuPage.PROFILES) return ProfilesPage.FIELD;
        if (page == MenuPage.FRIENDS) return FriendsPage.field();
        return SEARCH;
    }

    /** Keep the fields within their limits (names: letters, digits, underscores; lengths). */
    private void sanitize() {
        cap(SEARCH, 32, null);
        cap(ProfilesPage.FIELD, 24, null);
        cap(FriendsPage.ADD, 16, "[^A-Za-z0-9_]");
        cap(FriendsPage.CHAT, net.evadeclient.social.SocialChat.MAX_CHARS, null);
    }

    private void cap(String id, int max, String strip) {
        String s = SearchField.text(kit, id), t = strip == null ? s : s.replaceAll(strip, "");
        if (t.length() > max) t = t.substring(0, max);
        if (!t.equals(s)) SearchField.set(kit, id, t);
    }

    @Override
    public boolean keyReleased(KeyInput input) {
        if (closing()) return true;
        if (menuKeyDown && input.key() == settings().menuKey.get()) {
            menuKeyDown = false;
            if (!menuKeyUsed && !settingsPage.busy()) close();
            return true;
        }
        return super.keyReleased(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (closing()) return true;
        if (picker.isOpen()) return picker.charTyped(input);
        if (menuKeyDown) menuKeyUsed = true;
        if (settingsPage.charTyped(input)) return true;
        if (!input.isValidChar()) return false;
        String typing = typingIn();
        if (typing == null) {
            typing = fieldFor();
            if (typing == null) return false;
            if (typing.equals(FriendsPage.ADD) && !FriendsPage.friendsOn()) return true;
            kit.focus(typing, false);
        }
        kit.charTyped(input.codepoint());
        sanitize();
        if (typing.equals(SEARCH)) scroll.reset();
        return true;
    }

    private static boolean isModifier(int key) {
        return key >= GLFW.GLFW_KEY_LEFT_SHIFT && key <= GLFW.GLFW_KEY_RIGHT_SUPER;
    }

    /** Clipboard text as one line of valid characters. */
    public static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\r' || cp == '\t') b.append(' ');
            else if (StringHelper.isValidChar(cp)) b.appendCodePoint(cp);
        });
        return b.toString().replaceAll(" {2,}", " ").strip();
    }

    // ------------------------------------------------------------------ navigation

    @Override
    public void openModule(Module mod) {
        settingsPage.leave();
        if (mod != selected) pageChanged(1);
        selected = mod;
        scroll.reset();
    }

    @Override
    public void go(MenuPage p) {
        settingsPage.leave();
        if (p != page || selected != null || !search().isEmpty() || scroll.target != 0 || scroll.pos >= 0.5) pageChanged(0);
        if (p == MenuPage.FRIENDS && p != page) net.evadeclient.social.SocialClient.refresh();
        page = p;
        selected = null;
        returnTo = null;
        SearchField.set(kit, SEARCH, "");
        if (kit.focused() != null && kit.focused().equals(SEARCH)) kit.focus(null, false);
        profilesPage.reset();
        friendsPage.reset();
        scroll.reset();
    }

    private void back() {
        settingsPage.leave();
        Module was = selected;
        if (selected != null) pageChanged(-1);
        selected = null;
        scroll.reset();
        // Keyboard users land back on the gear they came in from.
        if (was != null) modsPage.returnFocus(was, keyboard);
    }

    @Override
    public void leaveModulePage() {
        if (returnTo != null && client != null) {
            settingsPage.leave();
            EvadeClient.config.save();
            Screen to = returnTo;
            returnTo = null;
            client.setScreen(to);
            return;
        }
        back();
    }

    /** Open the HUD editor over the menu (it comes back here). */
    public void editHud() {
        returnTo = null;
        if (client != null) client.setScreen(new HudEditorScreen(this));
    }

    /** Escape, the Menu Key or the close button: the window fades out first (unless Animations is Off). */
    @Override
    public void close() {
        if (level() != Motion.Level.OFF && closingAt < 0 && !finishing) {
            settingsPage.leave();
            double now = clock.getAsDouble();
            closeFrom = windowState(now);
            closingAt = now;
            return;
        }
        finishClose();
    }

    private void queueFinishClose() {
        finishing = true;
        double at = closingAt;
        if (client != null) client.send(() -> { if (closingAt == at) finishClose(); });
        else finishClose();
    }

    /** Close for real: save, then leave for the screen the menu came from, or the game. */
    private void finishClose() {
        finishing = true;
        closingAt = -1;
        settingsPage.leave();
        EvadeClient.config.save();
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
        if (closingAt >= 0 && clock.getAsDouble() - closingAt >= CLOSE_MS + 250) finishClose();
    }

    @Override
    public void removed() {
        settingsPage.leave();
        picker.close();
        Notifications.screenTopInset = -1;
        if (closingAt >= 0 && !finishing) {
            finishing = true;
            EvadeClient.config.save();
        }
        super.removed();
    }

    @Override
    public boolean shouldPause() { return false; }

    /** Test hook: the design-px centre of a kit control drawn last frame (e.g. "nav:HUD"), in window (GUI) px, or null. */
    public double[] controlCentreForTest(String id) {
        int[] r = sidebar.rectForTest(id);
        if (r == null) r = modsPage.rectForTest(id);
        if (r == null) return null;
        return new double[]{(r[0] + r[2]) / 2.0 * vs, (r[1] + r[3]) / 2.0 * vs};
    }
}
