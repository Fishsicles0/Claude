package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.mods.ContentInstaller;
import net.evadeclient.mods.IconCache;
import net.evadeclient.mods.InstalledMod;
import net.evadeclient.mods.ModBrowser;
import net.evadeclient.mods.ModManager;
import net.evadeclient.mods.ModrinthApi;
import net.evadeclient.mods.ModrinthApi.ContentType;
import net.evadeclient.mods.ModrinthApi.Hit;
import net.evadeclient.mods.ModrinthApi.Project;
import net.evadeclient.mods.ModrinthApi.Sort;
import net.evadeclient.mods.ModrinthApi.Version;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Mod Browser: find Fabric mods for this game version on Modrinth, read about one, and install it (with the mods
 * it needs) into Evade's own mods folder; or look through the mods already there, update and remove them. Nothing is
 * ever installed or changed without a click, every file comes from Modrinth, and changes load on the next start.
 *
 * <p>Drawn like the Evade menu: a centred window laid out for 820x506 GUI pixels and drawn at the largest scale that
 * fits, rounded cards, the accent colour, the UI font roles and spring scrolling. All network and disk work runs on
 * {@link ModBrowser#EXECUTOR}; results come back on the client thread.
 */
public class ModBrowserScreen extends Screen {
    private enum Tab { BROWSE, INSTALLED }

    /** Category chips: label and Modrinth category slug (null = all). */
    private static final String[][] CATEGORIES = {
            {"All", null}, {"Optimization", "optimization"}, {"Utility", "utility"}, {"Decoration", "decoration"},
            {"Equipment", "equipment"}, {"Game Mechanics", "game-mechanics"}, {"Social", "social"}, {"Storage", "storage"},
            {"Library", "library"}, {"Adventure", "adventure"}, {"Magic", "magic"}, {"Technology", "technology"},
            {"Food", "food"}, {"Mobs", "mobs"}, {"Transportation", "transportation"}, {"Worldgen", "worldgen"},
            {"Management", "management"}, {"Economy", "economy"}, {"Minigame", "minigame"}};
    /** Resource pack chips: style, resolution and what the pack changes. */
    private static final String[][] PACK_CATEGORIES = {
            {"All", null}, {"Combat", "combat"}, {"Vanilla-like", "vanilla-like"}, {"Simplistic", "simplistic"},
            {"Realistic", "realistic"}, {"Tweaks", "tweaks"}, {"Utility", "utility"}, {"Themed", "themed"},
            {"16x", "16x"}, {"32x", "32x"}, {"64x", "64x"}, {"Blocks", "blocks"}, {"Items", "items"},
            {"Entities", "entities"}, {"GUI", "gui"}, {"Fonts", "fonts"}, {"Audio", "audio"}, {"Models", "models"}};
    /** Shader pack chips: look, features and how heavy they are. */
    private static final String[][] SHADER_CATEGORIES = {
            {"All", null}, {"Potato", "potato"}, {"Low", "low"}, {"Medium", "medium"}, {"High", "high"},
            {"Vanilla-like", "vanilla-like"}, {"Semi-realistic", "semi-realistic"}, {"Realistic", "realistic"},
            {"Fantasy", "fantasy"}, {"Cartoon", "cartoon"}, {"Shadows", "shadows"}, {"Reflections", "reflections"},
            {"Colored Lighting", "colored-lighting"}, {"Path Tracing", "path-tracing"}};
    /** Data pack chips. */
    private static final String[][] DATAPACK_CATEGORIES = {
            {"All", null}, {"Utility", "utility"}, {"Game Mechanics", "game-mechanics"}, {"Worldgen", "worldgen"},
            {"Adventure", "adventure"}, {"Equipment", "equipment"}, {"Decoration", "decoration"}, {"Mobs", "mobs"},
            {"Magic", "magic"}, {"Technology", "technology"}, {"Food", "food"}, {"Storage", "storage"}};
    /** The type tabs: label and content type. */
    private static final ContentType[] TYPES = {ContentType.MOD, ContentType.RESOURCE_PACK, ContentType.SHADER, ContentType.DATA_PACK};

    private static final String WARNING = "Mods come from Modrinth and aren't checked by Evade. Some servers don't allow certain mods: check their rules first.";
    private static final int PAGE = 20;

    // Kept for the session, so the browser reopens where it was left.
    private static Tab tab = Tab.BROWSE;
    private static String query = "";
    private static Sort sort = Sort.RELEVANCE;
    private static String category;
    /** Which kind of content Browse shows: mods, resource packs, shader packs or data packs. */
    private static ContentType type = ContentType.MOD;
    /** Resource packs, shaders and data packs installed this session ("type:projectId"). */
    private static final Set<String> contentInstalledNow = new HashSet<>();
    /** The world a data pack goes into (folder name), picked in the detail panel. */
    private static String datapackWorld;
    /** Singleplayer worlds for data packs, re-read every couple of seconds (not every frame). */
    private List<ContentInstaller.World> worldsCache;
    private double worldsAt;
    /** Install progress by Modrinth project id ("Installing 40%"), while an install runs. */
    private static final Map<String, String> installing = new HashMap<>();
    /** Projects installed this session (shown as Installed straight away). */
    private static final Set<String> installedNow = new HashSet<>();

    private final Screen returnTo;
    private final ModBrowser browser = ModBrowser.get();

    // ---- browse
    private final List<Hit> hits = new ArrayList<>();
    private int total;
    private boolean loading, loadingMore;
    private String error;
    /** Bumped by each new search, so a slow older reply is dropped. */
    private int searchGen;
    /** When the typed query should be searched ({@link #now} ms), or -1. */
    private double searchDue = -1;
    private boolean searchFocused;
    private double chipScroll;

    // ---- detail
    private Hit selected;
    private Project project;
    private List<Version> versions;
    private boolean detailLoading;
    private String detailError;
    private ModManager.Plan plan;
    private boolean planning;
    private String planError;
    private int detailGen;

    // ---- installed
    private List<InstalledMod> installed = List.of();
    private Set<String> installedProjects = Set.of();
    private boolean scanning;
    private Map<String, Version> updates = Map.of();
    private boolean checking;
    private String updateNote;
    /** What is running on a file in the Installed tab ("Updating", "Removing"), by file name. */
    private final Map<String, String> busy = new HashMap<>();
    /** The Remove button clicked once (file name) and when: a second click within 3 s removes. */
    private String confirmRemove;
    private double confirmAt;

    // ---- motion and layout
    private final Map<Object, Float> anim = new HashMap<>();
    private final Scroll listScroll = new Scroll(), detailScroll = new Scroll(), installedScroll = new Scroll();
    private boolean anims = true, smooth = true;
    private double lastFrame, openedAt;
    private float dt;
    private float vs = 1f;
    private double menuPx = 2;
    private int wx, wy, ww, wh, cx, cy, cw, ch;
    private int mouseX, mouseY;
    /** Clickable areas drawn this frame, last drawn on top. */
    private final List<Hot> hots = new ArrayList<>();
    private final ArrayList<int[]> clips = new ArrayList<>();
    private Scroll hoverScroll;
    private boolean overChips;
    private List<String> tip;
    private int tipX, tipY;

    private record Hot(int x1, int y1, int x2, int y2, Runnable action) {}

    private static final int DESIGN_W = 820, DESIGN_H = 506;
    private static final int DETAIL_W = 296, ROW = 48, IROW = 46, GAP = 6;

    public ModBrowserScreen(Screen returnTo) {
        super(Text.literal("Mod Browser"));
        this.returnTo = returnTo;
    }

    /** Open the Mod Browser over whatever is on screen; Back and Escape return there. */
    public static void open() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.setScreen(new ModBrowserScreen(mc.currentScreen));
    }

    /** Open on the Installed tab (or Browse) over the current screen. */
    public static void open(boolean installedTab) {
        tab = installedTab ? Tab.INSTALLED : Tab.BROWSE;
        open();
    }

    /** Test hook: browse another kind of content (mods, resource packs, shaders, data packs). */
    public static void setContentTypeForTest(ContentType t) {
        type = t;
        category = null;
        tab = Tab.BROWSE;
    }

    /** The kind of content Browse shows. */
    public static ContentType contentType() { return type; }

    /** Test hook: run a search right away with these settings. */
    public static void setSearchForTest(String q, String sortIndex, String cat) {
        query = q == null ? "" : q;
        for (Sort s : Sort.values()) if (s.index.equals(sortIndex)) sort = s;
        category = cat;
        tab = Tab.BROWSE;
    }

    /** Test hook: how many results are loaded, or -1 while the first page loads. */
    public int resultCountForTest() { return loading ? -1 : hits.size(); }

    private ClientSettings settings() { return EvadeClient.modules.get(ClientSettings.class); }

    private static double now() { return EvadeMenu.clock.getAsDouble(); }

    @Override
    public void onDisplayed() {
        super.onDisplayed();
        openedAt = lastFrame = now();
    }

    @Override
    protected void init() {
        anims = settings().animations.get();
        smooth = settings().smoothFont.get();
        if (hits.isEmpty() && !loading && error == null) search(false);
        rescan(false);
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void close() {
        if (client != null) client.setScreen(returnTo);
    }

    // ------------------------------------------------------------------ data

    private void search(boolean more) {
        if (more) {
            if (loading || loadingMore || hits.size() >= total) return;
            loadingMore = true;
        } else {
            searchGen++;
            loading = true;
            error = null;
            hits.clear();
            total = 0;
            listScroll.reset();
        }
        int gen = searchGen, offset = more ? hits.size() : 0;
        String q = query;
        Sort s = sort;
        String c = category;
        ContentType t = type;
        ModBrowser.async(() -> browser.manager.api().search(t, q, s, c, offset, PAGE), page -> {
            if (gen != searchGen) return;
            loading = loadingMore = false;
            if (page.offset() != hits.size()) return;
            hits.addAll(page.hits());
            total = page.total();
        }, msg -> {
            if (gen != searchGen) return;
            loading = loadingMore = false;
            if (!more) error = msg;
        });
    }

    private void select(Hit h) {
        if (selected != null && selected.projectId().equals(h.projectId())) return;
        selected = h;
        project = null;
        versions = null;
        detailError = null;
        plan = null;
        planError = null;
        planning = false;
        detailLoading = true;
        detailScroll.reset();
        int gen = ++detailGen;
        ContentType t = type;
        ModBrowser.async(() -> {
            ModrinthApi api = browser.manager.api();
            return new Object[]{api.project(h.projectId()), api.versions(h.projectId(), t)};
        }, r -> {
            if (gen != detailGen) return;
            detailLoading = false;
            project = (Project) r[0];
            @SuppressWarnings("unchecked") List<Version> v = (List<Version>) r[1];
            versions = v;
        }, msg -> {
            if (gen != detailGen) return;
            detailLoading = false;
            detailError = msg;
        });
    }

    /** Rescan the mods folder (and ask Modrinth which projects the jars are, for the Installed marks). */
    private void rescan(boolean thenCheckUpdates) {
        if (scanning) return;
        scanning = true;
        ModManager mm = browser.manager;
        ModBrowser.async(() -> {
            List<InstalledMod> list = mm.scan();
            mm.identify(list);
            return list;
        }, list -> {
            scanning = false;
            installed = list;
            Set<String> ids = new HashSet<>();
            for (InstalledMod m : list) {
                if (mm.removalPending(m)) continue;
                String p = mm.projectIdOf(m);
                if (p != null) ids.add(p);
            }
            installedProjects = ids;
            if (thenCheckUpdates) checkUpdates();
        }, msg -> scanning = false);
    }

    private void checkUpdates() {
        if (checking) return;
        checking = true;
        updateNote = null;
        List<InstalledMod> list = installed;
        ModBrowser.async(() -> browser.manager.checkUpdates(list), u -> {
            checking = false;
            updates = u;
            updateNote = u.isEmpty() ? "Everything is up to date" : u.size() + (u.size() == 1 ? " update available" : " updates available");
        }, msg -> {
            checking = false;
            updateNote = msg;
        });
    }

    private boolean isInstalled(String projectId) {
        if (type != ContentType.MOD) return contentInstalledNow.contains(type + ":" + projectId);
        return installedNow.contains(projectId) || installedProjects.contains(projectId);
    }

    /** Switch Browse to another kind of content: a fresh search with that kind's categories. */
    private void switchType(ContentType t) {
        if (t == type) return;
        type = t;
        category = null;
        chipScroll = 0;
        selected = null;
        detailGen++;
        plan = null;
        search(false);
    }

    private static String[][] categories() {
        return switch (type) {
            case RESOURCE_PACK -> PACK_CATEGORIES;
            case SHADER -> SHADER_CATEGORIES;
            case DATA_PACK -> DATAPACK_CATEGORIES;
            default -> CATEGORIES;
        };
    }

    private static String typeLabel(ContentType t) {
        return switch (t) {
            case RESOURCE_PACK -> "Resource Packs";
            case SHADER -> "Shaders";
            case DATA_PACK -> "Data Packs";
            default -> "Mods";
        };
    }

    /** "mods", "resource packs", ... for sentences. */
    private static String noun(ContentType t) {
        return typeLabel(t).toLowerCase(Locale.ROOT);
    }

    /** The project's page on modrinth.com. */
    private static String pageUrl(ContentType t, String slug) {
        String kind = switch (t) {
            case RESOURCE_PACK -> "resourcepack";
            case SHADER -> "shader";
            case DATA_PACK -> "datapack";
            default -> "mod";
        };
        return "https://modrinth.com/" + kind + "/" + slug;
    }

    /** Install a resource pack, shader pack or data pack (one zip, no dependencies). */
    private void runContentInstall(Hit hit, Version v, String world) {
        String pid = hit.projectId();
        ContentType t = type;
        installing.put(pid, "Starting");
        ModBrowser.async(() -> ContentInstaller.install(browser.manager.api(), t, world, v, frac -> {
            String s = (int) Math.round(frac * 100) + "%";
            MinecraftClient.getInstance().execute(() -> installing.put(pid, s));
        }), file -> {
            installing.remove(pid);
            contentInstalledNow.add(t + ":" + pid);
            Notifications.push("Installed " + hit.title(), switch (t) {
                case RESOURCE_PACK -> "Turn it on in Options > Resource Packs";
                case SHADER -> net.evadeclient.util.ShaderSwitch.irisLoaded() ? "Pick it in Performance > Shader Pack" : "Shaders need Iris: run the Evade installer with Shaders on";
                default -> "In " + world + ". It loads the next time you open the world (or after /reload)";
            }, true);
        }, msg -> {
            installing.remove(pid);
            if (msg.equals("Already installed")) contentInstalledNow.add(t + ":" + pid);
            Notifications.push("Couldn't install " + hit.title(), msg, false);
        });
    }

    private void planInstall(String projectId) {
        planning = true;
        planError = null;
        plan = null;
        int gen = detailGen;
        ModBrowser.async(() -> browser.manager.plan(projectId), p -> {
            if (gen != detailGen) return;
            planning = false;
            plan = p;
        }, msg -> {
            if (gen != detailGen) return;
            planning = false;
            planError = msg;
        });
    }

    private void runInstall(ModManager.Plan p) {
        String pid = p.projectId();
        String title = p.root() != null ? p.root().title() : "Mod";
        plan = null;
        installing.put(pid, "Starting");
        ModBrowser.async(() -> browser.manager.install(p, (name, frac) -> {
            String s = name + "  " + (int) Math.round(frac * 100) + "%";
            MinecraftClient.getInstance().execute(() -> installing.put(pid, s));
        }), r -> {
            installing.remove(pid);
            if (!r.installed().isEmpty()) {
                browser.markRestartNeeded();
                installedNow.add(pid);
                int deps = r.installed().size() - 1;
                Notifications.push("Installed " + title, deps > 0 ? "With " + deps + " required mod" + (deps == 1 ? "" : "s") + ". Restart to load it"
                        : "Restart Minecraft to load it", true);
            } else {
                installedNow.add(pid);
                Notifications.push(title, "Already installed", true);
            }
            rescan(false);
        }, msg -> {
            installing.remove(pid);
            Notifications.push("Couldn't install " + title, msg, false);
        });
    }

    private void runUpdate(InstalledMod m, Version v) {
        busy.put(m.fileName(), "Updating");
        ModBrowser.async(() -> browser.manager.update(m, v, null), out -> {
            busy.remove(m.fileName());
            browser.markRestartNeeded();
            Map<String, Version> u = new HashMap<>(updates);
            u.remove(m.fileName());
            updates = u;
            Notifications.push("Updated " + m.displayName(), "To " + v.versionNumber() + ". Restart to load it", true);
            rescan(false);
        }, msg -> {
            busy.remove(m.fileName());
            Notifications.push("Couldn't update " + m.displayName(), msg, false);
        });
    }

    private void runRemove(InstalledMod m) {
        busy.put(m.fileName(), "Removing");
        ModBrowser.async(() -> browser.manager.remove(m), out -> {
            busy.remove(m.fileName());
            browser.markRestartNeeded();
            String pid = browser.manager.projectIdOf(m);
            if (pid != null) installedNow.remove(pid);
            Notifications.push("Removed " + m.displayName(), out == ModManager.Outcome.DONE ? "Restart Minecraft to unload it"
                    : "It's in use: it will be deleted when Minecraft closes", true);
            rescan(false);
        }, msg -> {
            busy.remove(m.fileName());
            Notifications.push("Couldn't remove " + m.displayName(), msg, false);
        });
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
        settings().applyTheme();
        if (settings().blur.get()) super.renderBackground(ctx, mx, my, delta);
        float a = intro();
        ctx.fillGradient(0, 0, width, height, Theme.alpha(0xB0000000, a),
                Theme.alpha(0xD0000000 | (Theme.lerpColor(0xFF000000, Theme.RED, 0.09f) & 0xFFFFFF), a));
    }

    private float intro() {
        if (!anims) return 1f;
        double t = now() - openedAt;
        if (t < 0) openedAt = now() - 200;
        return Motion.cubicOut((float) (t / 140));
    }

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
    public void render(DrawContext ctx, int mx, int my, float delta) {
        anims = settings().animations.get();
        smooth = settings().smoothFont.get();
        super.render(ctx, mx, my, delta);
        updateScale();
        int realW = width, realH = height;
        width = Math.round(realW / vs);
        height = Math.round(realH / vs);
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.scale(vs, vs);
        try {
            mouseX = (int) Math.floor(mx / vs);
            mouseY = (int) Math.floor(my / vs);
            renderWindow(ctx);
        } finally {
            m.popMatrix();
            width = realW;
            height = realH;
        }
    }

    private void renderWindow(DrawContext ctx) {
        double t = now();
        dt = (float) Math.max(0, Math.min(0.1, (t - lastFrame) / 1000));
        lastFrame = t;
        hots.clear();
        clips.clear();
        tip = null;
        hoverScroll = null;
        overChips = false;
        if (searchDue >= 0 && t >= searchDue) { searchDue = -1; search(false); }
        if (confirmRemove != null && t - confirmAt > 3000) confirmRemove = null;

        ww = Math.min(width - 40, 780);
        wh = Math.min(height - 36, 470);
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;

        float a = intro();
        float p = anims ? Motion.cubicOut((float) ((t - openedAt) / 200)) : 1f;
        var m = ctx.getMatrices();
        float base = Theme.fade;
        m.pushMatrix();
        try {
            float sc = 0.97f + 0.03f * p;
            m.translate(width / 2f, height / 2f + Theme.snap(ctx, (1f - p) * 10f));
            m.scale(sc, sc);
            m.translate(-width / 2f, -height / 2f);
            Theme.fade = base * a * a;
            windowShadow(ctx, wx, wy + 6, wx + ww, wy + wh + 6, Theme.alpha(0xFF000000, 0.6f));
            windowShadow(ctx, wx, wy, wx + ww, wy + wh, Theme.alpha(Theme.RED, 0.2f));
            Theme.fade = base * a;
            Theme.round(ctx, wx, wy, wx + ww, wy + wh, Theme.BG, Theme.R_WIN);
            Theme.stroke(ctx, wx, wy, wx + ww, wy + wh, Theme.BORDER, Theme.R_WIN);

            renderTop(ctx);
            int top = wy + 50;
            if (browser.restartNeeded()) {
                renderRestartBanner(ctx, wx + 16, top, wx + ww - 16);
                top += 28;
            }
            cx = wx + 16;
            cw = ww - 32;
            cy = top;
            ch = wy + wh - 26 - cy;
            if (tab == Tab.BROWSE) renderBrowse(ctx);
            else renderInstalled(ctx);

            // footer: the safety note
            Theme.fill(ctx, wx + 16, wy + wh - 22, wx + ww - 16, wy + wh - 21, Theme.DIVIDER);
            text(ctx, ellipsize(Role.SMALL, WARNING, ww - 32), wx + 16, wy + wh - 15, Theme.alpha(Theme.WARN, 0.85f), Role.SMALL);
            drawTip(ctx);
        } finally {
            Theme.fade = base;
            m.popMatrix();
        }
    }

    /** A window shadow (or halo) with its middle filled too, as the menu draws it (the window body is translucent). */
    private static void windowShadow(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
        Theme.Shadow s = Theme.SH_WINDOW;
        Theme.shadow(ctx, s, x1, y1, x2, y2, color);
        int in = s.slice() - s.pad();
        Theme.fill(ctx, x1 + in, y1 + in, x2 - in, y2 - in, color);
    }

    private void renderTop(DrawContext ctx) {
        int y = wy + 14;
        // back
        int bx = wx + 14;
        boolean hb = hover(bx, y, bx + 22, y + 22);
        float h = button(ctx, "back", bx, y, bx + 22, y + 22, hb, Theme.CONTROL, Theme.CONTROL_HOVER, Theme.BORDER, Theme.RED);
        Icons.draw(ctx, "chevron_l", bx + 5, y + 5, 12, Theme.lerpColor(Theme.TEXT_SOFT, Theme.TEXT, h));
        hot(bx, y, bx + 22, y + 22, this::close);
        if (hb) setTip(returnTo != null ? "Back" : "Close");

        text(ctx, "Mod Browser", bx + 32, y + 3, Theme.TEXT, Role.TITLE);
        int tw = width(Role.TITLE, "Mod Browser");
        String sub = "Fabric " + ModrinthApi.GAME_VERSION + " mods from Modrinth";

        // close
        int cxb = wx + ww - 24, cyb = wy + 12;
        boolean hc = hover(cxb, cyb, cxb + 14, cyb + 14);
        float hcv = button(ctx, "close", cxb, cyb, cxb + 14, cyb + 14, hc, Theme.CONTROL, Theme.RED, Theme.CONTROL, Theme.RED);
        Icons.draw(ctx, "close", cxb + 2, cyb + 2, 10, Theme.lerpColor(Theme.TEXT_SOFT, Theme.ON_ACCENT, hcv));
        hot(cxb, cyb, cxb + 14, cyb + 14, () -> { if (client != null) client.setScreen(null); });

        // tabs: Browse | Installed (n)
        int segW = 190, half = segW / 2, sx = cxb - 12 - segW, sy = y;
        String inst = "Installed" + (installed.isEmpty() ? "" : " (" + installed.size() + ")");
        Theme.roundBordered(ctx, sx, sy, sx + segW, sy + 22, Theme.INPUT, Theme.BORDER);
        float sel = animate("tab", tab == Tab.INSTALLED ? 1 : 0, 18);
        var km = ctx.getMatrices();
        km.pushMatrix();
        km.translate(Theme.snap(ctx, (half - 2) * sel), 0);
        Theme.round(ctx, sx + 2, sy + 2, sx + half, sy + 20, Theme.alpha(Theme.RED, 0.9f), Theme.R_XS);
        km.popMatrix();
        centered(ctx, "Browse", sx, sx + half, sy + 7, Theme.lerpColor(Theme.ON_ACCENT, Theme.TEXT_DIM, sel), Role.BODY);
        centered(ctx, inst, sx + half, sx + segW, sy + 7, Theme.lerpColor(Theme.TEXT_DIM, Theme.ON_ACCENT, sel), Role.BODY);
        hot(sx, sy, sx + half, sy + 22, () -> tab = Tab.BROWSE);
        hot(sx + half, sy, sx + segW, sy + 22, () -> { tab = Tab.INSTALLED; rescan(false); });

        int subX = bx + 32 + tw + 10;
        if (tab == Tab.BROWSE) {
            // what to browse: Mods | Resource Packs | Shaders | Data Packs
            int tx = sx - 10;
            for (int i = TYPES.length - 1; i >= 0; i--) tx -= width(Role.LABEL, typeLabel(TYPES[i])) + 16;
            if (tx >= subX) {
                Theme.roundBordered(ctx, tx - 2, sy, sx - 8, sy + 22, Theme.INPUT, Theme.BORDER);
                int x = tx;
                for (ContentType t : TYPES) {
                    String label = typeLabel(t);
                    int w = width(Role.LABEL, label) + 16;
                    boolean on = t == type, hv = hover(x, sy, x + w, sy + 22);
                    float onA = animate("type:" + t, on ? 1 : 0, 18), hvA = animate("typeH:" + t, hv ? 1 : 0, 22);
                    if (onA > 0.01f) Theme.round(ctx, x, sy + 2, x + w - 2, sy + 20, Theme.alpha(Theme.RED, 0.9f * onA), Theme.R_XS);
                    centered(ctx, label, x, x + w - 2, sy + 8, Theme.lerpColor(Theme.lerpColor(Theme.TEXT_DIM, Theme.TEXT, hvA), Theme.ON_ACCENT, onA), Role.LABEL);
                    hot(x, sy, x + w, sy + 22, () -> switchType(t));
                    x += w;
                }
                sub = null;
            }
        }
        if (sub != null) text(ctx, ellipsize(Role.LABEL, sub, sx - 10 - subX), subX, y + 8, Theme.TEXT_DIM, Role.LABEL);
        Theme.fill(ctx, wx + 16, wy + 44, wx + ww - 16, wy + 45, Theme.DIVIDER);
    }

    private void renderRestartBanner(DrawContext ctx, int x1, int y, int x2) {
        Theme.roundBordered(ctx, x1, y, x2, y + 22, Theme.tint(0.16f), Theme.RED);
        Icons.draw(ctx, "reset", x1 + 8, y + 5, 12, Theme.RED_BRIGHT);
        text(ctx, "Restart Minecraft to load new mods", x1 + 26, y + 7, Theme.TEXT, Role.STRONG);
        String more = "Changes in the mods folder only take effect on the next start";
        int lx = x1 + 26 + width(Role.STRONG, "Restart Minecraft to load new mods") + 12;
        text(ctx, ellipsize(Role.LABEL, more, x2 - 10 - lx), lx, y + 8, Theme.TEXT_SOFT, Role.LABEL);
    }

    // ---- browse tab -----------------------------------------------------

    private void renderBrowse(DrawContext ctx) {
        // search + sort
        int third = 76, sortW = third * 3 + 2, sx = cx + cw - sortW;
        int inW = sx - 10 - cx;
        boolean hs = hover(cx, cy, cx + inW, cy + 24);
        float focus = animate("focus", searchFocused ? 1 : 0, 16);
        if (focus > 0.01f) Theme.shadow(ctx, Theme.SH_CARD, cx, cy, cx + inW, cy + 24, Theme.alpha(Theme.RED, 0.28f * focus));
        float ih = animate("ih", hs ? 1 : 0, 22);
        Theme.roundBordered(ctx, cx, cy, cx + inW, cy + 24, Theme.INPUT,
                Theme.lerpColor(Theme.lerpColor(Theme.BORDER, Theme.BORDER_HOVER, ih), Theme.RED, focus));
        Icons.draw(ctx, "search", cx + 7, cy + 7, 10, Theme.lerpColor(Theme.TEXT_DIM, Theme.RED_BRIGHT, focus));
        boolean caret = searchFocused && Math.floorMod((long) (now() / 500), 2L) == 0;
        if (query.isEmpty() && !searchFocused) text(ctx, "Search Modrinth " + noun(type), cx + 22, cy + 8, Theme.TEXT_FAINT, Role.BODY);
        else text(ctx, trimStart(query, inW - 34) + (caret ? "_" : ""), cx + 22, cy + 8, Theme.TEXT, Role.BODY);
        hot(cx, cy, cx + inW, cy + 24, () -> searchFocused = true);

        Theme.roundBordered(ctx, sx, cy, sx + sortW, cy + 24, Theme.INPUT, Theme.BORDER);
        float sp = animate("sort", sort.ordinal(), 18);
        var km = ctx.getMatrices();
        km.pushMatrix();
        km.translate(Theme.snap(ctx, third * sp), 0);
        Theme.round(ctx, sx + 2, cy + 2, sx + third, cy + 22, Theme.alpha(Theme.RED, 0.9f), Theme.R_XS);
        km.popMatrix();
        for (Sort s : Sort.values()) {
            int x1 = sx + 2 + third * s.ordinal(), x2 = x1 + third - 2;
            float on = Math.max(0f, 1f - Math.abs(sp - s.ordinal()));
            centered(ctx, s.label, x1, x2, cy + 8, Theme.lerpColor(Theme.TEXT_DIM, Theme.ON_ACCENT, on), Role.LABEL);
            hot(x1, cy, x2, cy + 24, () -> { if (sort != s) { sort = s; search(false); } });
        }

        // category chips (wheel scrolls them sideways)
        int chipY = cy + 32, chipH = 18;
        int chipsW = 0;
        String[][] cats = categories();
        for (String[] c : cats) chipsW += width(Role.LABEL, c[0]) + 18 + 6;
        int maxChip = Math.max(0, chipsW - 6 - cw);
        chipScroll = Math.max(0, Math.min(maxChip, chipScroll));
        overChips = hover(cx, chipY, cx + cw, chipY + chipH);
        pushClip(ctx, cx, chipY - 2, cx + cw, chipY + chipH + 2);
        int x = cx - (int) Math.round(chipScroll);
        for (String[] c : cats) {
            int w = width(Role.LABEL, c[0]) + 18;
            boolean on = c[1] == null ? category == null : c[1].equals(category);
            boolean hv = hover(x, chipY, x + w, chipY + chipH);
            float hvA = animate("chip:" + c[0], hv ? 1 : 0, 22);
            float onA = animate("chipOn:" + c[0], on ? 1 : 0, 18);
            int fill = mix(Theme.lerpColor(Theme.CONTROL, Theme.CONTROL_HOVER, hvA), Theme.tint(0.2f), onA);
            int border = Theme.lerpColor(Theme.lerpColor(Theme.BORDER, Theme.BORDER_HOVER, hvA), Theme.RED, onA);
            if (Theme.square) Theme.roundBordered(ctx, x, chipY, x + w, chipY + chipH, fill, border);
            else {
                Theme.pill(ctx, x, chipY, x + w, chipY + chipH, border);
                Theme.pill(ctx, x + 1, chipY + 1, x + w - 1, chipY + chipH - 1, fill);
            }
            text(ctx, c[0], x + 9, chipY + 5, Theme.lerpColor(Theme.lerpColor(Theme.TEXT_SOFT, Theme.TEXT, hvA), Theme.RED_BRIGHT, onA), Role.LABEL);
            String slug = c[1];
            hot(x, chipY, x + w, chipY + chipH, () -> {
                if (slug == null ? category != null : !slug.equals(category)) { category = slug; search(false); }
            });
            x += w + 6;
        }
        popClip(ctx);
        if (maxChip > 0) {
            // soft edge where more chips continue
            int solid = Theme.BG | 0xFF000000, clear = Theme.BG & 0xFFFFFF;
            if (chipScroll < maxChip) fadeH(ctx, cx + cw - 20, chipY - 1, cx + cw, chipY + chipH + 1, clear, solid);
            if (chipScroll > 0) fadeH(ctx, cx, chipY - 1, cx + 20, chipY + chipH + 1, solid, clear);
        }

        // results and detail
        int listTop = chipY + chipH + 10, listH = cy + ch - listTop;
        boolean narrow = cw < 600;
        if (selected != null && narrow) {
            renderDetail(ctx, cx, listTop, cx + cw, listTop + listH);
            return;
        }
        int listW = selected != null ? cw - DETAIL_W - 10 : cw;
        renderResults(ctx, cx, listTop, listW, listH);
        if (selected != null) renderDetail(ctx, cx + listW + 10, listTop, cx + cw, listTop + listH);
    }

    private void renderResults(DrawContext ctx, int x, int y, int w, int h) {
        int contentH = hits.size() * (ROW + GAP) + (loadingMore ? 24 : 0);
        Scroll s = listScroll;
        s.clamp(contentH, h);
        s.step();
        if (hover(x, y, x + w + 12, y + h)) hoverScroll = s;
        int si = s.px();
        pushClip(ctx, x - 6, y, x + w + 6, y + h);
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(0, -Theme.snap(ctx, (float) (s.pos - si)));
        try {
            for (int i = 0; i < hits.size(); i++) {
                int ry = y + i * (ROW + GAP) - si;
                if (ry + ROW < y || ry > y + h) continue;
                renderHit(ctx, hits.get(i), x, ry, w);
            }
            if (loadingMore) centered(ctx, "Loading more...", x, x + w, y + hits.size() * (ROW + GAP) - si + 6, Theme.TEXT_DIM, Role.LABEL);
        } finally {
            m.popMatrix();
        }
        popClip(ctx);
        edgeFades(ctx, s, x - 6, x + w + 6, y, y + h);
        scrollbar(ctx, s, x + w + 6, y, h, contentH);

        if (loading) centered(ctx, "Searching Modrinth...", x, x + w, y + 30, Theme.TEXT_DIM, Role.BODY);
        else if (error != null) {
            centered(ctx, ellipsize(Role.BODY, error, w - 20), x, x + w, y + 30, Theme.DANGER, Role.BODY);
            int bw = 70, bx = x + (w - bw) / 2, by = y + 48;
            boolean hb = hover(bx, by, bx + bw, by + 20);
            button(ctx, "retry", bx, by, bx + bw, by + 20, hb, Theme.CONTROL, Theme.CONTROL_HOVER, Theme.BORDER, Theme.RED);
            centered(ctx, "Try again", bx, bx + bw, by + 6, Theme.TEXT, Role.LABEL);
            hot(bx, by, bx + bw, by + 20, () -> search(false));
        } else if (hits.isEmpty()) {
            centered(ctx, query.isBlank() ? "Nothing here" : "No " + noun(type) + " match \"" + ellipsize(Role.BODY, query, 200) + "\"", x, x + w, y + 30, Theme.TEXT_DIM, Role.BODY);
        }
        // more pages as the list nears its end
        if (!loading && !loadingMore && error == null && hits.size() < total && s.target >= s.max - 160) search(true);
    }

    private void renderHit(DrawContext ctx, Hit hit, int x, int y, int w) {
        boolean sel = selected != null && selected.projectId().equals(hit.projectId());
        boolean hv = hover(x, y, x + w, y + ROW);
        float h = animate("hit:" + hit.projectId(), hv ? 1 : 0, 14);
        float on = animate("hitSel:" + hit.projectId(), sel ? 1 : 0, 16);
        if (h > 0.01f) Theme.shadow(ctx, Theme.SH_CARD, x, y, x + w, y + ROW, Theme.alpha(Theme.RED, 0.22f * h));
        int fill = mix(Theme.lerpColor(Theme.CARD, Theme.CARD_HOVER, h), Theme.tint(0.09f), on);
        int border = Theme.lerpColor(Theme.lerpColor(Theme.BORDER, Theme.RED, h * 0.6f), Theme.RED, on);
        Theme.roundBordered(ctx, x, y, x + w, y + ROW, fill, border);
        if (on > 0.01f) Theme.round(ctx, x + 1, y + 7, x + 3, y + ROW - 7, Theme.alpha(Theme.RED, on), 1);

        drawIcon(ctx, "hit:" + hit.projectId(), hit.iconUrl(), hit.projectId(), null, hit.title(), x + 9, y + 8, 32);
        int tx = x + 50, right = x + w - 10;
        // right column: downloads, and Installed / Installing
        String dl = compact(hit.downloads()) + " downloads";
        int dlw = width(Role.SMALL, dl);
        text(ctx, dl, right - dlw, y + 11, Theme.TEXT_DIM, Role.SMALL);
        String status = installing.containsKey(hit.projectId()) ? "Installing" : isInstalled(hit.projectId()) ? "Installed" : null;
        int rightCol = dlw;
        if (status != null) {
            int sw = width(Role.CAPS, status.toUpperCase(Locale.ROOT)) + 10;
            int sy = y + 26;
            Theme.pill(ctx, right - sw, sy, right, sy + 12, Theme.alpha(status.equals("Installed") ? Theme.OK : Theme.RED, 0.22f));
            text(ctx, status.toUpperCase(Locale.ROOT), right - sw + 5, sy + 3, status.equals("Installed") ? Theme.OK : Theme.RED_BRIGHT, Role.CAPS);
            rightCol = Math.max(rightCol, sw);
        }
        int textW = right - rightCol - 10 - tx;
        String title = ellipsize(Role.STRONG, hit.title(), textW);
        text(ctx, title, tx, y + 9, Theme.TEXT, Role.STRONG);
        int ax = tx + width(Role.STRONG, title) + 6;
        if (ax < tx + textW - 20) text(ctx, ellipsize(Role.LABEL, "by " + hit.author(), tx + textW - ax), ax, y + 10, Theme.TEXT_DIM, Role.LABEL);
        text(ctx, ellipsize(Role.SMALL, hit.description(), textW), tx, y + 27, Theme.TEXT_DIM, Role.SMALL);
        hot(x, y, x + w, y + ROW, () -> select(hit));
    }

    // ---- detail panel ---------------------------------------------------

    private void renderDetail(DrawContext ctx, int x1, int y1, int x2, int y2) {
        Hit hit = selected;
        Theme.roundBordered(ctx, x1, y1, x2, y2, Theme.CARD, Theme.BORDER);
        int pad = 12, w = x2 - x1 - pad * 2;

        Scroll s = detailScroll;
        s.step();
        if (hover(x1, y1, x2, y2)) hoverScroll = s;
        int si = s.px();
        pushClip(ctx, x1 + 1, y1 + 1, x2 - 1, y2 - 1);
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(0, -Theme.snap(ctx, (float) (s.pos - si)));
        int y = y1 + pad - si;
        int x = x1 + pad;
        try {
            drawIcon(ctx, "hit:" + hit.projectId(), hit.iconUrl(), hit.projectId(), null, hit.title(), x, y, 40);
            int tx = x + 50, tw = w - 50 - 20;
            text(ctx, ellipsize(Role.STRONG, hit.title(), tw), tx, y + 4, Theme.TEXT, Role.STRONG);
            text(ctx, ellipsize(Role.LABEL, "by " + hit.author(), tw), tx, y + 17, Theme.TEXT_DIM, Role.LABEL);
            String meta = compact(hit.downloads()) + " downloads" + (hit.dateModified() != null ? "  ·  updated " + ago(hit.dateModified()) : "");
            text(ctx, ellipsize(Role.SMALL, meta, tw), tx, y + 29, Theme.TEXT_FAINT, Role.SMALL);
            y += 48;

            y = renderInstallBox(ctx, hit, x, y, w);
            y += 10;

            if (detailLoading) {
                text(ctx, "Loading details...", x, y, Theme.TEXT_DIM, Role.LABEL);
                y += 14;
            } else if (detailError != null) {
                for (String line : wrap(Role.LABEL, detailError, w)) { text(ctx, line, x, y, Theme.DANGER, Role.LABEL); y += 11; }
            } else if (project != null) {
                y = section(ctx, "About", x, y, w);
                String sum = summary(project);
                int lines = 0;
                for (String para : sum.split("\n")) {
                    for (String line : wrap(Role.LABEL, para, w)) {
                        if (lines++ >= 18) break;
                        text(ctx, line, x, y, Theme.TEXT_SOFT, Role.LABEL);
                        y += 11;
                    }
                    y += 4;
                    if (lines >= 18) break;
                }
                String sides = "Client: " + side(project.clientSide()) + "  ·  Server: " + side(project.serverSide());
                text(ctx, ellipsize(Role.SMALL, sides, w), x, y, Theme.TEXT_DIM, Role.SMALL);
                y += 12;
                // Modrinth page (opens through Minecraft's link confirmation)
                String link = "View on Modrinth";
                int lw = width(Role.LABEL, link) + 16;
                boolean hl = hover(x, y, x + lw, y + 18);
                button(ctx, "link", x, y, x + lw, y + 18, hl, Theme.CONTROL, Theme.CONTROL_HOVER, Theme.BORDER, Theme.RED);
                centered(ctx, link, x, x + lw, y + 5, Theme.TEXT_SOFT, Role.LABEL);
                String slug = project.slug() != null ? project.slug() : project.id();
                ContentType pt = type;
                hot(x, y, x + lw, y + 18, () -> ConfirmLinkScreen.open(this, pageUrl(pt, slug)));
                y += 26;

                y = section(ctx, (type == ContentType.MOD ? "Versions for Fabric " : "Versions for ") + ModrinthApi.GAME_VERSION, x, y, w);
                if (versions == null || versions.isEmpty()) {
                    text(ctx, "None yet", x, y, Theme.TEXT_DIM, Role.LABEL);
                    y += 12;
                } else {
                    int shown = 0;
                    for (Version v : versions) {
                        if (shown++ >= 8) break;
                        Theme.roundBordered(ctx, x, y, x + w, y + 20, Theme.ROW, Theme.DIVIDER);
                        String type = v.type().isEmpty() ? "" : v.type().toUpperCase(Locale.ROOT);
                        int typeW = type.isEmpty() ? 0 : width(Role.CAPS, type) + 8;
                        String when = v.published() != null ? ago(v.published()) : "";
                        int whenW = width(Role.SMALL, when);
                        text(ctx, when, x + w - 6 - whenW, y + 7, Theme.TEXT_FAINT, Role.SMALL);
                        if (typeW > 0) {
                            int tcol = type.equals("RELEASE") ? Theme.OK : Theme.WARN;
                            int px = x + w - 6 - whenW - 6 - typeW;
                            Theme.pill(ctx, px, y + 4, px + typeW, y + 16, Theme.alpha(tcol, 0.18f));
                            text(ctx, type, px + 4, y + 7, tcol, Role.CAPS);
                        }
                        text(ctx, ellipsize(Role.LABEL, v.versionNumber(), w - 20 - whenW - typeW), x + 6, y + 6, Theme.TEXT, Role.LABEL);
                        y += 23;
                    }
                    if (versions.size() > 8) {
                        text(ctx, "and " + (versions.size() - 8) + " older", x, y + 1, Theme.TEXT_FAINT, Role.SMALL);
                        y += 12;
                    }
                }
            }
            y += pad;
        } finally {
            m.popMatrix();
        }
        popClip(ctx);
        int contentH = y + si - y1;
        s.clamp(contentH, y2 - y1);
        edgeFades(ctx, s, x1 + 1, x2 - 1, y1 + 1, y2 - 1);
        scrollbar(ctx, s, x2 - 3, y1 + 4, y2 - y1 - 8, contentH);

        // the panel's close button, over its content
        int bx = x2 - 22, by = y1 + 8;
        boolean hb = hover(bx, by, bx + 14, by + 14);
        float hv = button(ctx, "detailClose", bx, by, bx + 14, by + 14, hb, Theme.CONTROL, Theme.CONTROL_HOVER, Theme.BORDER, Theme.RED);
        Icons.draw(ctx, "close", bx + 2, by + 2, 10, Theme.lerpColor(Theme.TEXT_DIM, Theme.TEXT, hv));
        hot(bx, by, bx + 14, by + 14, () -> { selected = null; detailGen++; plan = null; });
    }

    /** The Install button, or the plan to confirm, or progress. Returns the y below it. */
    private int renderInstallBox(DrawContext ctx, Hit hit, int x, int y, int w) {
        String pid = hit.projectId();
        String prog = installing.get(pid);
        if (prog != null) {
            Theme.roundBordered(ctx, x, y, x + w, y + 24, Theme.tint(0.12f), Theme.RED);
            centered(ctx, "Installing...  " + prog, x, x + w, y + 8, Theme.TEXT, Role.STRONG);
            return y + 24;
        }
        if (isInstalled(pid) && plan == null) {
            Theme.roundBordered(ctx, x, y, x + w, y + 24, Theme.alpha(Theme.OK, 0.12f), Theme.alpha(Theme.OK, 0.6f));
            centered(ctx, "Installed", x, x + w, y + 8, Theme.OK, Role.STRONG);
            return y + 24;
        }
        if (type != ContentType.MOD) return renderContentInstall(ctx, hit, x, y, w);
        if (plan != null && plan.projectId().equals(pid)) return renderPlan(ctx, x, y, w);
        boolean hv = hover(x, y, x + w, y + 24) && !planning;
        button(ctx, "install", x, y, x + w, y + 24, hv, Theme.RED, Theme.RED_BRIGHT, Theme.RED, Theme.RED_BRIGHT);
        centered(ctx, planning ? "Checking what it needs..." : "Install", x, x + w, y + 8, Theme.ON_ACCENT, Role.STRONG);
        if (!planning) hot(x, y, x + w, y + 24, () -> planInstall(pid));
        y += 24;
        if (planError != null) {
            y += 4;
            for (String line : wrap(Role.LABEL, planError, w)) { text(ctx, line, x, y, Theme.DANGER, Role.LABEL); y += 11; }
        }
        return y;
    }

    /**
     * The Install box for a resource pack, shader pack or data pack: installs the newest version straight into its
     * folder. Data packs first pick one of your singleplayer worlds.
     */
    private int renderContentInstall(DrawContext ctx, Hit hit, int x, int y, int w) {
        if (detailLoading) {
            Theme.roundBordered(ctx, x, y, x + w, y + 24, Theme.CONTROL, Theme.BORDER);
            centered(ctx, "Loading...", x, x + w, y + 8, Theme.TEXT_DIM, Role.STRONG);
            return y + 24;
        }
        Version v = versions == null ? null : ContentInstaller.newest(versions);
        if (v == null) {
            for (String line : wrap(Role.LABEL, "No version for Minecraft " + ModrinthApi.GAME_VERSION + " yet", w)) { text(ctx, line, x, y, Theme.TEXT_DIM, Role.LABEL); y += 11; }
            return y;
        }
        List<ContentInstaller.World> worlds = List.of();
        if (type == ContentType.DATA_PACK) {
            if (worldsCache == null || now() - worldsAt > 2000) { worldsCache = ContentInstaller.worlds(6); worldsAt = now(); }
            worlds = worldsCache;
            if (worlds.isEmpty()) {
                for (String line : wrap(Role.LABEL, "Data packs go into a singleplayer world. Create a world first, then come back.", w)) {
                    text(ctx, line, x, y, Theme.TEXT_DIM, Role.LABEL);
                    y += 11;
                }
                return y;
            }
            boolean known = false;
            for (ContentInstaller.World wd : worlds) if (wd.folder().equals(datapackWorld)) known = true;
            if (!known) datapackWorld = worlds.get(0).folder();
            text(ctx, "ADD TO WORLD", x, y, Theme.TEXT_HEADER, Role.CAPS);
            y += 12;
            for (ContentInstaller.World wd : worlds) {
                boolean on = wd.folder().equals(datapackWorld), hv = hover(x, y, x + w, y + 18);
                Theme.roundBordered(ctx, x, y, x + w, y + 18, on ? Theme.tint(0.16f) : hv ? Theme.CONTROL_HOVER : Theme.CONTROL, on ? Theme.RED : Theme.BORDER);
                text(ctx, ellipsize(Role.LABEL, wd.folder(), w - 16), x + 8, y + 5, on ? Theme.TEXT : Theme.TEXT_SOFT, Role.LABEL);
                String folder = wd.folder();
                hot(x, y, x + w, y + 18, () -> datapackWorld = folder);
                y += 21;
            }
            y += 3;
        }
        String world = type == ContentType.DATA_PACK ? datapackWorld : null;
        String pid = hit.projectId();
        if (isInstalled(pid) || ContentInstaller.present(type, world, v)) {
            Theme.roundBordered(ctx, x, y, x + w, y + 24, Theme.alpha(Theme.OK, 0.12f), Theme.alpha(Theme.OK, 0.6f));
            centered(ctx, "Installed", x, x + w, y + 8, Theme.OK, Role.STRONG);
            y += 24;
        } else {
            boolean hv = hover(x, y, x + w, y + 24);
            button(ctx, "install", x, y, x + w, y + 24, hv, Theme.RED, Theme.RED_BRIGHT, Theme.RED, Theme.RED_BRIGHT);
            centered(ctx, "Install " + v.versionNumber(), x, x + w, y + 8, Theme.ON_ACCENT, Role.STRONG);
            hot(x, y, x + w, y + 24, () -> runContentInstall(hit, v, world));
            y += 24;
        }
        String where = "Into " + ContentInstaller.describe(type, world);
        if (type == ContentType.SHADER && !net.evadeclient.util.ShaderSwitch.irisLoaded()) where = "Shader packs need Iris: run the Evade installer with Shaders on";
        y += 4;
        for (String line : wrap(Role.SMALL, where, w)) { text(ctx, line, x, y, type == ContentType.SHADER && !net.evadeclient.util.ShaderSwitch.irisLoaded() ? Theme.WARN : Theme.TEXT_FAINT, Role.SMALL); y += 10; }
        return y;
    }

    private int renderPlan(DrawContext ctx, int x, int y, int w) {
        List<ModManager.Step> steps = plan.steps();
        List<ModManager.Step> todo = plan.toInstall();
        int rows = steps.size();
        int boxH = 22 + rows * 14 + 32;
        Theme.roundBordered(ctx, x, y, x + w, y + boxH, Theme.INPUT, Theme.BORDER_HOVER);
        int iy = y + 8;
        String head = todo.isEmpty() ? "Already installed" : "Install " + todo.size() + (todo.size() == 1 ? " mod" : " mods") + " into Evade's mods folder";
        text(ctx, ellipsize(Role.LABEL, head, w - 16), x + 8, iy, Theme.TEXT, Role.LABEL);
        iy += 14;
        for (ModManager.Step s : steps) {
            String left = (s.dependency() ? "+ " : "") + s.title();
            String right = s.skip() != null ? s.skip() : s.version().versionNumber();
            int rw = width(Role.SMALL, right);
            text(ctx, ellipsize(Role.SMALL, left, w - 24 - rw), x + 8, iy, s.skip() != null ? Theme.TEXT_FAINT : Theme.TEXT_SOFT, Role.SMALL);
            text(ctx, right, x + w - 8 - rw, iy, s.skip() != null ? Theme.OK : Theme.TEXT_DIM, Role.SMALL);
            iy += 14;
        }
        int by = y + boxH - 26, half = (w - 24) / 2;
        int b1 = x + 8, b2 = x + 8 + half + 8;
        if (!todo.isEmpty()) {
            boolean h1 = hover(b1, by, b1 + half, by + 20);
            button(ctx, "confirm", b1, by, b1 + half, by + 20, h1, Theme.RED, Theme.RED_BRIGHT, Theme.RED, Theme.RED_BRIGHT);
            centered(ctx, "Install", b1, b1 + half, by + 6, Theme.ON_ACCENT, Role.STRONG);
            ModManager.Plan p = plan;
            hot(b1, by, b1 + half, by + 20, () -> runInstall(p));
        }
        boolean h2 = hover(b2, by, b2 + half, by + 20);
        button(ctx, "cancel", b2, by, b2 + half, by + 20, h2, Theme.CONTROL, Theme.CONTROL_HOVER, Theme.BORDER, Theme.RED);
        centered(ctx, todo.isEmpty() ? "OK" : "Cancel", b2, b2 + half, by + 6, Theme.TEXT, Role.LABEL);
        hot(b2, by, b2 + half, by + 20, () -> {
            if (plan != null && plan.toInstall().isEmpty()) installedNow.add(plan.projectId());
            plan = null;
        });
        return y + boxH;
    }

    private int section(DrawContext ctx, String title, int x, int y, int w) {
        text(ctx, title.toUpperCase(Locale.ROOT), x, y, Theme.TEXT_HEADER, Role.CAPS);
        Theme.fill(ctx, x, y + 11, x + w, y + 12, Theme.DIVIDER);
        return y + 17;
    }

    private static String side(String s) {
        if (s == null) return "unknown";
        return switch (s) {
            case "required" -> "required";
            case "optional" -> "optional";
            case "unsupported" -> "not needed";
            default -> s;
        };
    }

    // ---- installed tab --------------------------------------------------

    private void renderInstalled(DrawContext ctx) {
        // actions
        int y = cy;
        String chk = checking ? "Checking..." : "Check for updates";
        int cw1 = width(Role.LABEL, chk) + 20;
        boolean h1 = hover(cx, y, cx + cw1, y + 22) && !checking;
        button(ctx, "check", cx, y, cx + cw1, y + 22, h1, Theme.RED, Theme.RED_BRIGHT, Theme.RED, Theme.RED_BRIGHT);
        centered(ctx, chk, cx, cx + cw1, y + 7, Theme.ON_ACCENT, Role.LABEL);
        if (!checking) hot(cx, y, cx + cw1, y + 22, () -> rescan(true));
        String open = "Open mods folder";
        int ow = width(Role.LABEL, open) + 20, ox = cx + cw1 + 8;
        boolean h2 = hover(ox, y, ox + ow, y + 22);
        button(ctx, "openFolder", ox, y, ox + ow, y + 22, h2, Theme.CONTROL, Theme.CONTROL_HOVER, Theme.BORDER, Theme.RED);
        centered(ctx, open, ox, ox + ow, y + 7, Theme.TEXT, Role.LABEL);
        hot(ox, y, ox + ow, y + 22, () -> {
            try {
                java.nio.file.Files.createDirectories(browser.manager.modsDir());
                Util.getOperatingSystem().open(browser.manager.modsDir());
            } catch (Exception e) {
                Notifications.push("Couldn't open the folder", String.valueOf(e.getMessage()), false);
            }
        });
        String note = scanning && installed.isEmpty() ? "Reading the mods folder..." : updateNote != null ? updateNote
                : installed.size() + (installed.size() == 1 ? " mod" : " mods") + " in " + shortPath(browser.manager.modsDir());
        int nx = ox + ow + 12;
        text(ctx, ellipsize(Role.LABEL, note, cx + cw - nx), nx, y + 8, updateNote != null && !updates.isEmpty() ? Theme.RED_BRIGHT : Theme.TEXT_DIM, Role.LABEL);

        int listTop = y + 32, listH = cy + ch - listTop;
        int contentH = installed.size() * (IROW + GAP);
        Scroll s = installedScroll;
        s.clamp(contentH, listH);
        s.step();
        if (hover(cx, listTop, cx + cw + 12, listTop + listH)) hoverScroll = s;
        int si = s.px();
        pushClip(ctx, cx - 6, listTop, cx + cw + 6, listTop + listH);
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(0, -Theme.snap(ctx, (float) (s.pos - si)));
        try {
            for (int i = 0; i < installed.size(); i++) {
                int ry = listTop + i * (IROW + GAP) - si;
                if (ry + IROW < listTop || ry > listTop + listH) continue;
                renderInstalledRow(ctx, installed.get(i), cx, ry, cw);
            }
        } finally {
            m.popMatrix();
        }
        popClip(ctx);
        edgeFades(ctx, s, cx - 6, cx + cw + 6, listTop, listTop + listH);
        scrollbar(ctx, s, cx + cw + 6, listTop, listH, contentH);
        if (installed.isEmpty() && !scanning) centered(ctx, "No mods in the mods folder yet", cx, cx + cw, listTop + 30, Theme.TEXT_DIM, Role.BODY);
    }

    private void renderInstalledRow(DrawContext ctx, InstalledMod mod, int x, int y, int w) {
        ModManager mm = browser.manager;
        boolean pending = mm.removalPending(mod);
        boolean hv = hover(x, y, x + w, y + IROW);
        float h = animate("inst:" + mod.fileName(), hv ? 1 : 0, 14);
        int fill = Theme.lerpColor(Theme.CARD, Theme.CARD_HOVER, h);
        Theme.roundBordered(ctx, x, y, x + w, y + IROW, fill, Theme.lerpColor(Theme.BORDER, Theme.BORDER_HOVER, h));

        String pid = mm.projectIdOf(mod);
        drawIcon(ctx, "jar:" + mod.fileName() + ":" + mod.sha1(), null, pid, mod.icon(), mod.displayName(), x + 9, y + 7, 32);

        // buttons, right to left
        int right = x + w - 10, by = y + (IROW - 20) / 2;
        String reason = ModManager.protectedReason(mod);
        String state = busy.get(mod.fileName());
        if (state != null) {
            right = chip(ctx, state + "...", right, by + 4, Theme.TEXT_SOFT, Theme.CONTROL);
        } else if (pending) {
            right = chip(ctx, "Removed on restart", right, by + 4, Theme.WARN, Theme.alpha(Theme.WARN, 0.16f));
        } else {
            if (reason != null) {
                right = chip(ctx, "Required", right, by + 4, Theme.TEXT_DIM, Theme.CONTROL);
                if (hover(right, by, x + w - 10, by + 20)) setTip(reason);
            } else {
                boolean confirm = mod.fileName().equals(confirmRemove);
                List<InstalledMod> needs = mm.requiredBy(mod, installed);
                String label = confirm ? (needs.isEmpty() ? "Remove?" : "Remove anyway?") : "Remove";
                int bw = width(Role.LABEL, label) + 18, bx = right - bw;
                boolean hb = hover(bx, by, right, by + 20);
                float bh = button(ctx, "rm:" + mod.fileName(), bx, by, right, by + 20, hb,
                        confirm ? Theme.alpha(Theme.DANGER, 0.18f) : Theme.CONTROL, Theme.alpha(Theme.DANGER, confirm ? 0.3f : 0.15f),
                        confirm ? Theme.DANGER : Theme.BORDER, Theme.DANGER);
                centered(ctx, label, bx, right, by + 6, confirm ? Theme.DANGER : Theme.lerpColor(Theme.TEXT_SOFT, Theme.DANGER, bh), Role.LABEL);
                if (hb) {
                    if (!needs.isEmpty()) setTip("Needed by " + names(needs) + ". They may not start without it");
                    else setTip(confirm ? "Click again to remove " + mod.displayName() : "Remove " + mod.displayName());
                }
                hot(bx, by, right, by + 20, () -> {
                    if (mod.fileName().equals(confirmRemove)) {
                        confirmRemove = null;
                        runRemove(mod);
                    } else {
                        confirmRemove = mod.fileName();
                        confirmAt = now();
                    }
                });
                right = bx - 6;
            }
            Version up = updates.get(mod.fileName());
            if (up != null) {
                String label = "Update";
                int bw = width(Role.LABEL, label) + 18, bx = right - bw;
                boolean hb = hover(bx, by, right, by + 20);
                button(ctx, "up:" + mod.fileName(), bx, by, right, by + 20, hb, Theme.RED, Theme.RED_BRIGHT, Theme.RED, Theme.RED_BRIGHT);
                centered(ctx, label, bx, right, by + 6, Theme.ON_ACCENT, Role.STRONG);
                if (hb) setTip("Update to " + up.versionNumber());
                hot(bx, by, right, by + 20, () -> runUpdate(mod, up));
                right = bx - 6;
            }
        }

        int tx = x + 50, textW = right - 10 - tx;
        String name = ellipsize(Role.STRONG, mod.displayName(), textW);
        text(ctx, name, tx, y + 10, pending ? Theme.TEXT_DIM : Theme.TEXT, Role.STRONG);
        int vx = tx + width(Role.STRONG, name) + 6;
        if (mod.version() != null && vx < tx + textW - 16)
            text(ctx, ellipsize(Role.LABEL, mod.version(), tx + textW - vx), vx, y + 11, Theme.TEXT_DIM, Role.LABEL);
        String sub = mod.fileName() + (mod.id() != null ? "  ·  " + mod.id() : "");
        Version up = updates.get(mod.fileName());
        if (up != null && state == null && !pending) sub = "Update available: " + up.versionNumber() + "  ·  " + sub;
        text(ctx, ellipsize(Role.SMALL, sub, textW), tx, y + 27, up != null && !pending ? Theme.RED_BRIGHT : Theme.TEXT_FAINT, Role.SMALL);
    }

    /** A small status chip right-aligned at {@code right}; returns its left edge minus the gap. */
    private int chip(DrawContext ctx, String label, int right, int y, int color, int fill) {
        int w = width(Role.LABEL, label) + 14;
        Theme.pill(ctx, right - w, y, right, y + 12, fill);
        text(ctx, label, right - w + 7, y + 2, color, Role.LABEL);
        return right - w - 6;
    }

    private static String names(List<InstalledMod> mods) {
        List<String> n = new ArrayList<>();
        for (InstalledMod m : mods) { if (n.size() == 3) { n.add("more"); break; } n.add(m.displayName()); }
        return String.join(", ", n);
    }

    private static String shortPath(java.nio.file.Path p) {
        java.nio.file.Path parent = p.getParent();
        return (parent != null && parent.getFileName() != null ? parent.getFileName() + "/" : "") + p.getFileName();
    }

    // ---- icons ------------------------------------------------------------

    /**
     * A mod icon, or a lettered placeholder while it loads (or when there is none). Web icons come from Modrinth's CDN
     * only; when one can't be decoded (lossy WebP) the project's legacy icon.png is tried.
     */
    private void drawIcon(DrawContext ctx, String key, String url, String projectId, byte[] jarIcon, String title, int x, int y, int size) {
        Identifier id = null;
        if (url != null && ModrinthApi.isModrinthCdn(url)) {
            ModrinthApi api = browser.manager.api();
            id = browser.icons.get(key, () -> {
                try {
                    byte[] b = api.icon(url);
                    if (!net.evadeclient.mods.WebpLossless.isWebp(b) || decodable(b)) return b;
                } catch (Exception ignored) {
                    // fall through
                }
                return projectId != null && projectId.matches("[A-Za-z0-9]{1,16}") ? api.icon("https://cdn.modrinth.com/data/" + projectId + "/icon.png") : null;
            });
        } else if (jarIcon != null) {
            id = browser.icons.get(key, () -> jarIcon);
        }
        if (id != null) {
            Theme.round(ctx, x, y, x + size, y + size, Theme.CONTROL, Theme.R);
            IconCache.draw(ctx, id, x, y, size);
            return;
        }
        Theme.round(ctx, x, y, x + size, y + size, Theme.lerpColor(Theme.CONTROL, Theme.RED, 0.18f), Theme.R);
        String letter = title == null || title.isBlank() ? "?" : title.substring(0, 1).toUpperCase(Locale.ROOT);
        Role r = size >= 36 ? Role.TITLE : Role.STRONG;
        centered(ctx, letter, x, x + size, y + (size - (r == Role.TITLE ? 13 : 9)) / 2, Theme.RED_BRIGHT, r);
    }

    private static boolean decodable(byte[] webp) {
        try {
            net.evadeclient.mods.WebpLossless.decode(webp);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ scrolling

    private final class Scroll {
        double pos, target;
        int max;
        final Motion.Spring spring = new Motion.Spring();

        void clamp(int contentH, int viewH) {
            max = Math.max(0, contentH - viewH);
            target = Math.max(0, Math.min(max, target));
        }

        void step() {
            if (!anims) { spring.set((float) target); pos = target; return; }
            pos = spring.step((float) target, 28f, 1f, dt);
        }

        void by(double d) { target = Math.max(0, Math.min(max, target + d)); }

        void reset() { pos = target = 0; spring.set(0); }

        int px() { return (int) Math.floor(pos); }
    }

    private void edgeFades(DrawContext ctx, Scroll s, int x1, int x2, int top, int bottom) {
        float topA = (float) Math.min(1, s.pos / 24), botA = (float) Math.min(1, (s.max - s.pos) / 24);
        int solid = Theme.BG | 0xFF000000, clear = Theme.BG & 0xFFFFFF;
        if (topA > 0.004f) ctx.fillGradient(x1, top, x2, top + 12, Theme.fa(Theme.alpha(solid, topA)), Theme.fa(clear));
        if (botA > 0.004f) ctx.fillGradient(x1, bottom - 12, x2, bottom, Theme.fa(clear), Theme.fa(Theme.alpha(solid, botA)));
    }

    private void scrollbar(DrawContext ctx, Scroll s, int right, int top, int viewH, int contentH) {
        if (contentH <= viewH || viewH <= 0) return;
        int th = Math.min(viewH, Math.max(24, viewH * viewH / Math.max(1, contentH)));
        float frac = s.max == 0 ? 0 : (float) Math.max(0, Math.min(1, s.pos / s.max));
        float fy = top + (viewH - th) * frac;
        int iy = (int) Math.floor(fy);
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(0, Theme.snap(ctx, fy - iy));
        Theme.pill(ctx, right - 2, iy, right, iy + th, Theme.alpha(Theme.RED, 0.6f));
        m.popMatrix();
    }

    /** A horizontal gradient from {@code from} (left) to {@code to} (right). */
    private static void fadeH(DrawContext ctx, int x1, int y1, int x2, int y2, int from, int to) {
        int w = x2 - x1;
        for (int i = 0; i < w; i++) {
            float t = (i + 0.5f) / w;
            Theme.fill(ctx, x1 + i, y1, x1 + i + 1, y2, Theme.lerpColor(from, to, t));
        }
    }

    // ------------------------------------------------------------------ hit areas and clips

    private void pushClip(DrawContext ctx, int x1, int y1, int x2, int y2) {
        if (!clips.isEmpty()) {
            int[] c = clips.get(clips.size() - 1);
            x1 = Math.max(x1, c[0]); y1 = Math.max(y1, c[1]); x2 = Math.min(x2, c[2]); y2 = Math.min(y2, c[3]);
        }
        clips.add(new int[]{x1, y1, x2, y2});
        ctx.enableScissor(x1, y1, Math.max(x1, x2), Math.max(y1, y2));
    }

    private void popClip(DrawContext ctx) {
        clips.remove(clips.size() - 1);
        ctx.disableScissor();
    }

    /** Whether the mouse is over the rect (and inside the current clip). */
    private boolean hover(int x1, int y1, int x2, int y2) {
        if (!clips.isEmpty()) {
            int[] c = clips.get(clips.size() - 1);
            x1 = Math.max(x1, c[0]); y1 = Math.max(y1, c[1]); x2 = Math.min(x2, c[2]); y2 = Math.min(y2, c[3]);
        }
        return mouseX >= x1 && mouseX < x2 && mouseY >= y1 && mouseY < y2;
    }

    /** Remember a clickable rect for this frame (clipped to the current scissor). */
    private void hot(int x1, int y1, int x2, int y2, Runnable action) {
        if (!clips.isEmpty()) {
            int[] c = clips.get(clips.size() - 1);
            x1 = Math.max(x1, c[0]); y1 = Math.max(y1, c[1]); x2 = Math.min(x2, c[2]); y2 = Math.min(y2, c[3]);
        }
        if (x2 > x1 && y2 > y1) hots.add(new Hot(x1, y1, x2, y2, action));
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x() / vs, my = click.y() / vs;
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        boolean wasFocused = searchFocused;
        searchFocused = false;
        for (int i = hots.size() - 1; i >= 0; i--) {
            Hot h = hots.get(i);
            if (mx >= h.x1 && mx < h.x2 && my >= h.y1 && my < h.y2) {
                pressX = mx;
                pressY = my;
                pressedAt = now();
                h.action.run();
                return true;
            }
        }
        if (wasFocused && searchDue >= 0) { searchDue = -1; search(false); }
        return true;
    }

    /** The last click, for the clicked button's brief press shade. */
    private double pressX = -1, pressY = -1, pressedAt = -1e9;

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        double amount = v != 0 ? v : h;
        if (amount == 0) return true;
        if (overChips && tab == Tab.BROWSE) {
            chipScroll -= amount * 30;
            return true;
        }
        if (hoverScroll != null) hoverScroll.by(-v * 32);
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (input.isEscape()) {
            if (searchFocused) { searchFocused = false; return true; }
            if (selected != null && tab == Tab.BROWSE) { selected = null; detailGen++; plan = null; return true; }
            close();
            return true;
        }
        if (tab == Tab.BROWSE) {
            if (key == GLFW.GLFW_KEY_F && input.hasCtrlOrCmd()) { searchFocused = true; return true; }
            if (searchFocused || !query.isEmpty()) {
                if (input.isPaste() && client != null) {
                    String clip = client.keyboard.getClipboard().replaceAll("[\\p{Cntrl}]", " ").trim();
                    query = cap(query + clip);
                    searchFocused = true;
                    searchDue = now() + 350;
                    return true;
                }
                if (key == GLFW.GLFW_KEY_BACKSPACE && searchFocused && !query.isEmpty()) {
                    query = input.hasCtrlOrCmd() ? "" : query.substring(0, query.offsetByCodePoints(query.length(), -1));
                    searchDue = now() + 350;
                    return true;
                }
                if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && searchFocused) {
                    searchDue = -1;
                    search(false);
                    return true;
                }
            }
        }
        Scroll s = tab == Tab.BROWSE ? listScroll : installedScroll;
        if (key == GLFW.GLFW_KEY_PAGE_DOWN) { s.by(200); return true; }
        if (key == GLFW.GLFW_KEY_PAGE_UP) { s.by(-200); return true; }
        if (key == GLFW.GLFW_KEY_HOME && !searchFocused) { s.target = 0; return true; }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (tab != Tab.BROWSE || !input.isValidChar()) return false;
        // typing anywhere searches, like the menu
        searchFocused = true;
        query = cap(query + input.asString());
        searchDue = now() + 350;
        return true;
    }

    private static String cap(String s) { return s.length() > 64 ? s.substring(0, 64) : s; }

    // ------------------------------------------------------------------ drawing helpers (as in EvadeMenu)

    private float animate(Object key, float target, float speed) {
        float v = anim.getOrDefault(key, target);
        if (!anims) v = target;
        else v += (target - v) * (1 - (float) Math.exp(-speed * dt));
        anim.put(key, v);
        return v;
    }

    /** A push button's shape with hover cross-fade and a brief darken when clicked. Returns the hover amount. */
    private float button(DrawContext ctx, String id, int x1, int y1, int x2, int y2, boolean hover,
                         int fill, int fillHover, int border, int borderHover) {
        float h = animate("bh:" + id, hover ? 1 : 0, 22);
        boolean down = now() - pressedAt < 120 && pressX >= x1 && pressX < x2 && pressY >= y1 && pressY < y2;
        float p = animate("bp:" + id, down ? 1 : 0, 40);
        int f = mix(fill, fillHover, h);
        f = Theme.lerpColor(f, f & 0xFF000000, 0.12f * p);
        boolean ring = border != fill || borderHover != fillHover;
        if (ring) Theme.roundBordered(ctx, x1, y1, x2, y2, f, mix(border, borderHover, h));
        else Theme.round(ctx, x1, y1, x2, y2, f);
        return h;
    }

    /** {@code a} blended toward {@code b} in premultiplied alpha (see EvadeMenu.mix). */
    private static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        if (t == 0f) return a;
        if (t == 1f) return b;
        float aa = (a >>> 24) / 255f, ba = (b >>> 24) / 255f;
        float oa = aa + (ba - aa) * t;
        if (oa <= 0f) return 0;
        int r = mixCh(a >> 16 & 255, aa, b >> 16 & 255, ba, t, oa);
        int g = mixCh(a >> 8 & 255, aa, b >> 8 & 255, ba, t, oa);
        int bl = mixCh(a & 255, aa, b & 255, ba, t, oa);
        return Math.round(oa * 255f) << 24 | r << 16 | g << 8 | bl;
    }

    private static int mixCh(int ca, float aa, int cb, float ba, float t, float oa) {
        float v = (ca * aa + (cb * ba - ca * aa) * t) / oa;
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    private void setTip(String s) {
        tip = List.of(s);
        tipX = mouseX;
        tipY = mouseY;
    }

    private void drawTip(DrawContext ctx) {
        if (tip == null) return;
        Role r = Role.LABEL;
        List<String> lines = new ArrayList<>();
        for (String t : tip) lines.addAll(wrap(r, t, 200));
        int w = 0;
        for (String l : lines) w = Math.max(w, width(r, l));
        int lh = 11, bw = w + 12, bh = lines.size() * lh + 8;
        int x = tipX + 10, y = tipY + 12;
        if (x + bw > wx + ww - 4) x = tipX - 6 - bw;
        if (y + bh > wy + wh - 4) y = tipY - 6 - bh;
        x = Math.max(wx + 4, x);
        y = Math.max(wy + 4, y);
        Theme.roundBordered(ctx, x, y, x + bw, y + bh, Theme.CARD, Theme.BORDER_HOVER);
        int ty = y + 5;
        for (String l : lines) { text(ctx, l, x + 6, ty, Theme.TEXT_SOFT, r); ty += lh; }
    }

    // ---- text (the menu's UiFont roles) ------------------------------------

    private int fontG() { return Math.max(UiFont.MIN_G, Math.min(UiFont.MAX_G, (int) Math.round(menuPx))); }

    private Role shown(Role r) {
        if (menuPx >= 1.99) return r;
        if (!smooth) return r.scale < 1f ? Role.BODY : r;
        return r == Role.TINY ? Role.SMALL : r == Role.CAPS_SM ? Role.CAPS : r;
    }

    private Text smoothText(String s, Role r) {
        return Text.literal(s).setStyle(UiFont.style(shown(r), fontG()));
    }

    private void text(DrawContext ctx, String s, int x, int y, int color, Role r) {
        if (s == null || s.isEmpty()) return;
        if (!smooth) {
            UiFont.draw(ctx, textRenderer, s, x, y, color, shown(r), false);
            return;
        }
        int c = Theme.fa(color);
        if (c >>> 24 != 0) ctx.drawText(textRenderer, smoothText(s, r), x, y, c, false);
    }

    private int width(Role r, String s) {
        if (s == null || s.isEmpty()) return 0;
        return smooth ? textRenderer.getWidth(smoothText(s, r)) : UiFont.width(textRenderer, s, shown(r), false);
    }

    private void centered(DrawContext ctx, String s, int x1, int x2, int y, int color, Role r) {
        text(ctx, s, x1 + (x2 - x1 - width(r, s)) / 2, y, color, r);
    }

    private String trim(Role r, String s, int max) {
        if (width(r, s) <= max) return s;
        int lo = 0, hi = s.length();
        while (lo < hi) { int mid = (lo + hi + 1) / 2; if (width(r, s.substring(0, mid)) <= max) lo = mid; else hi = mid - 1; }
        if (lo > 0 && Character.isHighSurrogate(s.charAt(lo - 1))) lo--;
        return s.substring(0, lo);
    }

    private String ellipsize(Role r, String s, int max) {
        if (s == null || width(r, s) <= max) return s;
        int dots = width(r, "...");
        if (max <= dots) return "";
        return trim(r, s, max - dots).stripTrailing() + "...";
    }

    private String trimStart(String s, int max) {
        if (width(Role.BODY, s) <= max) return s;
        int lo = 0, hi = s.length();
        while (lo < hi) { int mid = (lo + hi) / 2; if (width(Role.BODY, s.substring(mid)) <= max) hi = mid; else lo = mid + 1; }
        return s.substring(lo);
    }

    private List<String> wrap(Role r, String s, int maxW) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            if (word.isEmpty()) continue;
            String next = line.isEmpty() ? word : line + " " + word;
            if (width(r, next) <= maxW) {
                line.setLength(0);
                line.append(next);
            } else {
                if (!line.isEmpty()) out.add(line.toString());
                line.setLength(0);
                line.append(width(r, word) <= maxW ? word : trim(r, word, maxW));
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    // ------------------------------------------------------------------ formatting

    /** 1234 -> 1.2K, 3_400_000 -> 3.4M. */
    static String compact(long n) {
        if (n < 1000) return Long.toString(n);
        if (n < 1_000_000) return trimZero(n / 1000.0) + "K";
        if (n < 1_000_000_000) return trimZero(n / 1_000_000.0) + "M";
        return trimZero(n / 1_000_000_000.0) + "B";
    }

    private static String trimZero(double v) {
        String s = v >= 100 ? String.format(Locale.ROOT, "%.0f", v) : String.format(Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** "3 days ago" from an ISO-8601 timestamp. */
    static String ago(String iso) {
        try {
            long s = Duration.between(Instant.parse(iso), Instant.now()).getSeconds();
            if (s < 3600) return Math.max(1, s / 60) + " min ago";
            if (s < 86400) return s / 3600 + (s / 3600 == 1 ? " hour ago" : " hours ago");
            long d = s / 86400;
            if (d < 31) return d + (d == 1 ? " day ago" : " days ago");
            if (d < 365) return d / 30 + (d / 30 == 1 ? " month ago" : " months ago");
            return d / 365 + (d / 365 == 1 ? " year ago" : " years ago");
        } catch (Exception e) {
            return "";
        }
    }

    /** The project's short description, then the start of its page (Markdown and HTML stripped), about 900 chars. */
    static String summary(Project p) {
        StringBuilder out = new StringBuilder(p.description().trim());
        String body = p.body()
                .replaceAll("(?s)```.*?```", " ")
                .replaceAll("(?s)<!--.*?-->", " ")
                .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ")
                .replaceAll("\\[!\\[[^\\]]*\\]\\([^)]*\\)\\]\\([^)]*\\)", " ")
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")
                .replaceAll("(?m)^\\s*[-*+]\\s+", "• ")
                .replaceAll("(?m)^\\s*>\\s?", "")
                .replaceAll("(?m)^\\s*\\|?\\s*:?-{3,}.*$", "")
                .replace("|", " ")
                .replaceAll("[*_`~]{1,3}", "")
                .replaceAll("&nbsp;", " ").replaceAll("&amp;", "&").replaceAll("&lt;", "<").replaceAll("&gt;", ">");
        for (String para : body.split("\\n\\s*\\n")) {
            String t = para.replaceAll("\\s+", " ").trim();
            if (t.isEmpty() || t.equalsIgnoreCase(p.description().trim()) || t.length() < 3) continue;
            if (out.length() > 900) break;
            if (out.length() + t.length() > 900) t = t.substring(0, Math.max(0, 900 - out.length())).trim() + "...";
            out.append('\n').append(t);
        }
        return out.toString();
    }
}
