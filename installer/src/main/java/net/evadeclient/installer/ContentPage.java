package net.evadeclient.installer;

import javax.swing.JFileChooser;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static net.evadeclient.installer.LauncherView.AMBER;
import static net.evadeclient.installer.LauncherView.BORDER;
import static net.evadeclient.installer.LauncherView.CARD_2;
import static net.evadeclient.installer.LauncherView.DIM;
import static net.evadeclient.installer.LauncherView.FAINT;
import static net.evadeclient.installer.LauncherView.FIELD;
import static net.evadeclient.installer.LauncherView.H;
import static net.evadeclient.installer.LauncherView.HAIRLINE;
import static net.evadeclient.installer.LauncherView.OK;
import static net.evadeclient.installer.LauncherView.RED;
import static net.evadeclient.installer.LauncherView.RED_HI;
import static net.evadeclient.installer.LauncherView.TEXT;
import static net.evadeclient.installer.LauncherView.W;
import static net.evadeclient.installer.LauncherView.center;
import static net.evadeclient.installer.LauncherView.ellipsizeEnd;
import static net.evadeclient.installer.LauncherView.icon;
import static net.evadeclient.installer.LauncherView.mix;

/**
 * The launcher's Mods page: everything an instance holds besides Evade itself, in four tabs.
 * <ul>
 * <li><b>Installed</b>: mods, resource packs and shader packs, with search, sort, a source chip (Evade / Modrinth /
 * Manual), update chips and Update all (hash lookups on Modrinth; Evade-managed jars are never touched), multi-select
 * switch on / off / remove, a "switch off every non-Evade mod" troubleshooting switch with Restore, and a check for
 * missing dependencies, incompatible mods and mods that do the same job.</li>
 * <li><b>Browse Modrinth</b>: mods, resource packs, shaders, data packs (into a chosen world) and modpacks, with
 * sort, categories and a client-side filter.</li>
 * <li><b>Worlds</b>: the instance's saves with backups (rotated), restore, duplicate, rename, delete to the bin,
 * copy seed, and their data packs.</li>
 * <li><b>Modpacks &amp; sharing</b>: import a .mrpack or share code into a new instance; export this one.</li>
 * </ul>
 * Painted with {@link LauncherView}'s own widgets so it looks like the rest of the window; long work runs on its own
 * threads and lands back on the UI thread.
 */
final class ContentPage {
    private final LauncherView v;
    private final InstallerCore core = new InstallerCore();
    private final ExecutorService work = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "content-work");
        t.setDaemon(true);
        return t;
    });
    /** Screenshots: sample data only, nothing loads in the background. */
    boolean offline;

    enum Tab {
        INSTALLED("Installed"), ADDONS("Add-ons"), BROWSE("Browse Modrinth"), WORLDS("Worlds"), PACKS("Modpacks & sharing");
        final String label;
        Tab(String label) { this.label = label; }
    }

    Tab tab = Tab.INSTALLED;
    final JTextField filterField, browseField;
    private Rectangle filterRect, browseRect;

    // ---- installed
    Content.Type installedType = Content.Type.MOD;
    String sort = "name";
    final Set<Path> selected = new LinkedHashSet<>();
    private Path dir;
    private List<Mods.Entry> analysedMods;
    /** Files Modrinth knows (mods, resource packs, shader packs), with their newer versions. */
    Map<Path, Updates.Known> known = new HashMap<>();
    Map<String, ContentApi.Project> projects = new HashMap<>();
    boolean checking;
    String checkError;
    private boolean checkedOnce;
    List<ModChecks.Problem> problems = List.of();
    Map<Path, ModChecks.Meta> metas = new HashMap<>();
    Set<String> recorded = new HashSet<>();
    Map<Path, Long> modified = new HashMap<>();
    List<Content.Pack> packs;
    private Content.Type packsFor;
    /** Files being updated -> progress. */
    final Map<Path, Double> updating = new HashMap<>();
    String bulk;
    double bulkProgress;
    /** Files the troubleshooting switch turned off (restored by Restore). */
    List<String> troubleshoot = List.of();

    // ---- browse
    Content.Type browseType = Content.Type.MOD;
    Content.Sort browseSort = Content.Sort.RELEVANCE;
    String category;
    boolean clientOnly = true;
    List<Mods.Hit> hits = List.of();
    int hitsTotal;
    boolean searching;
    String searchError;
    private String lastQuery = "";
    private boolean searched;
    private final AtomicInteger seq = new AtomicInteger();
    /** Project id -> "installing", "installed" or an error. */
    final Map<String, String> hitState = new HashMap<>();
    double hitProgress;
    String datapackWorld;

    // ---- worlds
    List<Worlds.World> worlds;
    final Map<String, List<Worlds.Backup>> backups = new HashMap<>();
    final Map<String, Double> worldBusy = new HashMap<>();
    private boolean worldsLoading;

    // ---- modpacks & sharing
    boolean exConfig = true, exOptions = true, exPacks = true, exServers = false;
    String packBusy;
    double packProgress;
    String resultTitle;
    List<String> resultNotes = List.of();
    boolean resultError;

    // ---- the sheet over the page: "mod:<n>", "pack:<n>", "hit:<n>", "problems", "datapacks:<folder>"
    String sheet;
    final Map<String, String> bodies = new HashMap<>();
    private final Set<String> bodyLoading = new HashSet<>();
    /** What the open confirm dialog is about. */
    private Object pending;
    /** Version list, pins and Undo last update. */
    final ContentVersions versions;
    /** The Add-ons tab: tested mods, one click each. */
    final ContentAddons addons;

    ContentPage(LauncherView v) {
        this.v = v;
        versions = new ContentVersions(this, v);
        addons = new ContentAddons(this, v);
        filterField = field("Search installed");
        browseField = field("Search Modrinth");
        v.add(filterField);
        v.add(browseField);
        filterField.getDocument().addDocumentListener(onChange(v::repaint));
        Timer debounce = new Timer(450, e -> { if (!browseField.getText().strip().equals(lastQuery)) search(false); });
        debounce.setRepeats(false);
        browseField.getDocument().addDocumentListener(onChange(debounce::restart));
        browseField.addActionListener(e -> search(false));
        new Timer(40, e -> { if (busy() && v.isShowing()) v.repaint(); }).start();
    }

    private JTextField field(String placeholder) {
        JTextField f = new JTextField();
        f.setOpaque(false);
        f.setBorder(new EmptyBorder(0, 0, 0, 0));
        f.setForeground(TEXT);
        f.setCaretColor(RED_HI);
        f.setSelectionColor(new Color(225, 29, 46, 110));
        f.setSelectedTextColor(Color.WHITE);
        f.setFont(v.inter.deriveFont(13f));
        f.putClientProperty("placeholder", placeholder);
        f.setVisible(false);
        return f;
    }

    private static DocumentListener onChange(Runnable r) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }

    private boolean busy() {
        return checking || searching || bulk != null || packBusy != null || !updating.isEmpty() || !worldBusy.isEmpty() || hitState.containsValue("installing");
    }

    private void ui(Runnable r) {
        SwingUtilities.invokeLater(() -> { r.run(); v.repaint(); });
    }

    private void fail(String what, Exception e) {
        String msg = EvadeInstaller.friendly(e);
        ui(() -> v.toast(what + ": " + msg));
    }

    // ------------------------------------------------------------------ text fields

    /** Shows the page's text fields where they were painted this frame (hidden under dialogs, menus and sheets). */
    void placeFields(boolean blocked, Rectangle covered) {
        float size = 13f * v.uiScale;
        for (JTextField f : new JTextField[]{filterField, browseField}) if (f.getFont().getSize2D() != size) f.setFont(v.inter.deriveFont(size));
        boolean show = !blocked && sheet == null && v.page == LauncherView.Page.MODS;
        v.place(filterField, show ? filterRect : null, covered);
        v.place(browseField, show ? browseRect : null, covered);
        filterRect = null;
        browseRect = null;
    }

    // ------------------------------------------------------------------ data

    /** Keeps the per-instance data in step with the selected instance and its mod list. */
    private void ensure(Launcher.Instance sel) {
        if (sel == null) return;
        if (!sel.dir.equals(dir)) {
            dir = sel.dir;
            analysedMods = null;
            known = new HashMap<>();
            problems = List.of();
            metas = new HashMap<>();
            selected.clear();
            packs = null;
            worlds = null;
            backups.clear();
            checkedOnce = false;
            checkError = null;
            datapackWorld = null;
            troubleshoot = readTroubleshoot(dir);
            versions.ensure(dir);
            hitState.clear();
            sheet = null;
        }
        if (offline) return;
        if (v.mods != analysedMods) {
            analysedMods = v.mods;
            analyse(sel, !checkedOnce);
            checkedOnce = true;
        }
        if ((installedType != Content.Type.MOD && (packs == null || packsFor != installedType))) loadPacks(sel);
        if (tab == Tab.WORLDS && worlds == null && !worldsLoading) loadWorlds(sel);
        if (tab == Tab.BROWSE && !searched) { searched = true; search(false); }
    }

    /** Reads every jar's metadata for the checks; with {@code network}, looks files up on Modrinth for sources and updates. */
    private void analyse(Launcher.Instance sel, boolean network) {
        List<Mods.Entry> list = v.mods;
        Path d = sel.dir;
        work.submit(() -> {
            Map<Path, ModChecks.Meta> m = new LinkedHashMap<>();
            Map<Path, Long> mod = new HashMap<>();
            Set<Path> on = new HashSet<>();
            for (Mods.Entry e : list) {
                ModChecks.Meta meta = ModChecks.read(e.file());
                if (meta != null) m.put(e.file(), meta);
                if (e.enabled()) on.add(e.file());
                try { mod.put(e.file(), Files.getLastModifiedTime(e.file()).toMillis()); } catch (IOException ignored) { }
            }
            List<ModChecks.Problem> probs = ModChecks.check(m, on, Map.of(), d);
            Set<String> rec = new HashSet<>();
            var r = InstallerCore.readJsonObject(d.resolve(Mods.RECORD));
            for (String k : r.keySet()) if (r.get(k).isJsonObject() && InstallerCore.str(r.getAsJsonObject(k), "file") != null)
                rec.add(InstallerCore.str(r.getAsJsonObject(k), "file").toLowerCase(Locale.ROOT));
            ui(() -> {
                if (!d.equals(dir)) return;
                metas = m;
                modified = mod;
                problems = probs;
                recorded = rec;
            });
            if (network) checkUpdates(sel, false);
        });
    }

    /** Looks the instance's own mods and packs up on Modrinth (sources, update chips). */
    void checkUpdates(Launcher.Instance sel, boolean tell) {
        if (sel == null) return;
        Path d = sel.dir;
        List<Mods.Entry> list = v.mods;
        ui(() -> { checking = true; checkError = null; });
        work.submit(() -> {
            try {
                Map<Path, Content.Type> files = new LinkedHashMap<>();
                for (Path f : Updates.playerMods(list)) files.put(f, Content.Type.MOD);
                for (Content.Type t : new Content.Type[]{Content.Type.RESOURCEPACK, Content.Type.SHADER})
                    for (Content.Pack p : Content.packs(d, t, null)) if (!p.folder()) files.put(p.file(), t);
                Map<Path, Updates.Known> k = Updates.check(files);
                Set<String> ids = new LinkedHashSet<>();
                for (Updates.Known x : k.values()) if (x.current() != null && x.current().projectId() != null) ids.add(x.current().projectId());
                Map<String, ContentApi.Project> pr = new HashMap<>();
                try { pr.putAll(ContentApi.projects(ids)); } catch (IOException ignored) { }
                long n = k.values().stream().filter(Updates.Known::hasUpdate).count();
                ui(() -> {
                    if (!d.equals(dir)) return;
                    known = new HashMap<>(k);
                    projects.putAll(pr);
                    checking = false;
                    if (tell) v.toast(n == 0 ? "Everything is up to date" : n + (n == 1 ? " update" : " updates") + " ready");
                    else if (n > 0) v.notice(n + (n == 1 ? " update" : " updates") + " for " + sel.name, "Mods or packs you added have newer versions. Open Mods to update them.", false);
                });
            } catch (Exception e) {
                String msg = EvadeInstaller.friendly(e);
                ui(() -> { checking = false; checkError = msg; if (tell) v.toast("Couldn't check for updates: " + msg); });
            }
        });
    }

    private void loadPacks(Launcher.Instance sel) {
        Content.Type t = installedType;
        packsFor = t;
        packs = List.of();
        Path d = sel.dir;
        work.submit(() -> {
            List<Content.Pack> l = Content.packs(d, t, null);
            ui(() -> { if (d.equals(dir) && installedType == t) packs = l; });
        });
    }

    private void loadWorlds(Launcher.Instance sel) {
        worldsLoading = true;
        Path d = sel.dir;
        work.submit(() -> {
            List<Worlds.World> l = Worlds.list(d);
            Map<String, List<Worlds.Backup>> b = new HashMap<>();
            for (Worlds.World w : l) b.put(w.folder(), Worlds.backups(d, w.folder()));
            ui(() -> {
                worldsLoading = false;
                if (!d.equals(dir)) return;
                worlds = l;
                backups.clear();
                backups.putAll(b);
                if (datapackWorld == null && !l.isEmpty()) datapackWorld = l.get(0).folder();
            });
        });
    }

    private void refreshMods(Launcher.Instance sel) {
        if (sel != null) v.actions.showMods(sel);
    }

    // ------------------------------------------------------------------ painting

    Rectangle paint(Graphics2D g) {
        Launcher.Instance sel = v.selected();
        ensure(sel);
        int CX = v.CX, CW = v.CW;
        v.pageTitle(g, "Mods", "Mods, packs and worlds for one instance. A mod switched off stays, ready to turn back on.");
        instancePicker(g, sel, CX, CW);
        int ty = 100, x = CX;
        long ups = versions.unpinnedCount(known.values());
        for (Tab t : Tab.values()) {
            int count = t == Tab.INSTALLED ? v.mods.size() : -1;
            v.tab(g, "c-tab:" + t.name(), x, ty, t.label, count, tab == t);
            g.setFont((tab == t ? v.interBold : v.inter).deriveFont(13.5f));
            int w = g.getFontMetrics().stringWidth(t.label) + (count >= 0 ? g.getFontMetrics().stringWidth(String.valueOf(count)) + 28 : 0);
            if (t == Tab.INSTALLED && ups > 0) {
                chip(g, ups + (ups == 1 ? " update" : " updates"), x + w + 8, ty + 9, AMBER, new Color(251, 191, 36, 34));
                w += 80;
            }
            x += w + 44;
        }
        g.setColor(HAIRLINE);
        g.fillRect(CX, ty + 38, CW, 1);
        int y = ty + 52, h = H - y - 24;
        Rectangle field = null;
        switch (tab) {
            case INSTALLED -> paintInstalled(g, sel, CX, y, CW, h);
            case ADDONS -> addons.paint(g, sel, CX, y, CW, h);
            case BROWSE -> paintBrowse(g, sel, CX, y, CW, h);
            case WORLDS -> paintWorlds(g, sel, CX, y, CW, h);
            case PACKS -> paintPacks(g, sel, CX, y, CW, h);
        }
        if (sheet != null) paintSheet(g, sel);
        return field;
    }

    private void instancePicker(Graphics2D g, Launcher.Instance sel, int CX, int CW) {
        g.setFont(v.interBold.deriveFont(12.5f));
        String nm = sel != null ? sel.name : "—";
        int pw = Math.min(220, g.getFontMetrics().stringWidth(nm) + 74);
        int px = CX + CW - pw;
        float t = v.anim("mods-instance", "mods-instance".equals(v.hovered));
        g.setColor(mix(CARD_2, new Color(0x1E2129), t));
        g.fill(new RoundRectangle2D.Double(px, 96, pw, 40, 24, 24));
        icon(g, "layers", px + 22, 116, 15, DIM);
        g.setColor(TEXT);
        g.drawString(ellipsizeEnd(g, nm, pw - 70), px + 40, 121);
        icon(g, "chevron-down", px + pw - 20, 116, 9, DIM);
        v.hit("mods-instance", new Rectangle(px, 96, pw, 40));
    }

    // ------------------------------------------------------------------ widgets

    /** A small rounded label; returns its width. */
    private int chip(Graphics2D g, String s, int x, int y, Color fg, Color bg) {
        g.setFont(v.interBold.deriveFont(10f));
        int w = g.getFontMetrics().stringWidth(s) + 16;
        g.setColor(bg);
        g.fill(new RoundRectangle2D.Double(x, y, w, 18, 18, 18));
        g.setColor(fg);
        center(g, s, x, y, w, 18);
        return w;
    }

    /** A chip that can be clicked (filters, sort, the update chip). */
    private int chipButton(Graphics2D g, String id, String s, int x, int y, boolean on) {
        float t = v.anim(id, id.equals(v.hovered));
        g.setFont(v.interBold.deriveFont(11.5f));
        int w = g.getFontMetrics().stringWidth(s) + 24, h = 28;
        g.setColor(on ? new Color(225, 29, 46, 46) : mix(new Color(0x1A1D23), new Color(0x23272F), t));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, h, h));
        if (on) {
            g.setColor(new Color(225, 29, 46, 120));
            g.draw(new RoundRectangle2D.Double(x + .5, y + .5, w - 1, h - 1, h, h));
        }
        g.setColor(on ? RED_HI : mix(DIM, TEXT, t));
        center(g, s, x, y, w, h);
        v.hit(id, new Rectangle(x, y, w, h));
        return w;
    }

    private void checkbox(Graphics2D g, String id, int x, int y, boolean on) {
        float t = v.anim(id, id.equals(v.hovered));
        g.setColor(on ? RED : mix(new Color(0x1A1D23), new Color(0x272B34), t));
        g.fill(new RoundRectangle2D.Double(x, y, 18, 18, 7, 7));
        if (!on) {
            g.setColor(mix(BORDER, new Color(0x464B57), t));
            g.draw(new RoundRectangle2D.Double(x + .5, y + .5, 17, 17, 7, 7));
        } else {
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D c = new Path2D.Double();
            c.moveTo(x + 4.5, y + 9.5);
            c.lineTo(x + 7.8, y + 12.6);
            c.lineTo(x + 13.5, y + 6);
            g.draw(c);
            g.setStroke(new BasicStroke(1f));
        }
        v.hit(id, new Rectangle(x - 4, y - 4, 26, 26));
    }

    /** A pill row of choices: {id, label}; the chosen one filled red. */
    private int segmented(Graphics2D g, int x, int y, String[][] items, String on) {
        g.setFont(v.interBold.deriveFont(12f));
        int[] ws = new int[items.length];
        int total = 8;
        for (int i = 0; i < items.length; i++) { ws[i] = g.getFontMetrics().stringWidth(items[i][1]) + 28; total += ws[i]; }
        g.setColor(FIELD);
        g.fill(new RoundRectangle2D.Double(x, y, total, 40, 24, 24));
        g.setColor(HAIRLINE);
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, total - 1, 39, 24, 24));
        int sx = x + 4;
        for (int i = 0; i < items.length; i++) {
            String id = items[i][0];
            boolean sel = id.equals(on);
            float t = v.anim(id, id.equals(v.hovered));
            if (sel) {
                g.setColor(RED);
                g.fill(new RoundRectangle2D.Double(sx, y + 4, ws[i], 32, 18, 18));
            } else if (t > 0.01f) {
                g.setColor(new Color(255, 255, 255, Math.round(12 * t)));
                g.fill(new RoundRectangle2D.Double(sx, y + 4, ws[i], 32, 18, 18));
            }
            g.setFont(v.interBold.deriveFont(12f));
            g.setColor(sel ? Color.WHITE : mix(DIM, TEXT, t));
            center(g, items[i][1], sx, y + 4, ws[i], 32);
            v.hit(id, new Rectangle(sx, y + 4, ws[i], 32));
            sx += ws[i];
        }
        return total;
    }

    /** A painted search box; the real text field goes in the returned rectangle. */
    private Rectangle searchBox(Graphics2D g, JTextField f, int x, int y, int w, String hint) {
        v.box(g, x, y, w, 40);
        icon(g, "search", x + 20, y + 20, 13, DIM);
        Rectangle r = new Rectangle(x + 38, y + 6, w - 50, 28);
        if (f.getText().isEmpty()) {
            g.setFont(v.inter.deriveFont(13f));
            g.setColor(FAINT);
            g.drawString(ellipsizeEnd(g, hint, w - 60), r.x + 1, y + 25);
        }
        return r;
    }

    /** A dropdown-looking button. */
    private void dropdown(Graphics2D g, String id, int x, int y, int w, String text) {
        float t = v.anim(id, id.equals(v.hovered));
        g.setColor(mix(CARD_2, new Color(0x1E2129), t));
        g.fill(new RoundRectangle2D.Double(x, y, w, 40, 24, 24));
        g.setFont(v.interBold.deriveFont(12f));
        g.setColor(TEXT);
        g.drawString(ellipsizeEnd(g, text, w - 44), x + 16, y + 25);
        icon(g, "chevron-down", x + w - 20, y + 20, 9, DIM);
        v.hit(id, new Rectangle(x, y, w, 40));
    }

    private void spinner(Graphics2D g, int cx, int cy, int r) {
        g.setColor(BORDER);
        g.setStroke(new BasicStroke(2f));
        g.draw(new java.awt.geom.Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        double a = (System.currentTimeMillis() % 900) / 900.0 * 360;
        g.setColor(RED_HI);
        g.draw(new Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, -a, 100, Arc2D.OPEN));
        g.setStroke(new BasicStroke(1f));
    }

    private void progressPill(Graphics2D g, int x, int y, int w, int h, double f, String label) {
        g.setColor(new Color(0x2A1418));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 10, 10));
        g.setColor(new Color(225, 29, 46, 140));
        g.fill(new RoundRectangle2D.Double(x, y, Math.max(8, w * Math.min(1, Math.max(0.06, f))), h, 10, 10));
        g.setFont(v.interBold.deriveFont(12f));
        g.setColor(TEXT);
        center(g, label, x, y, w, h);
    }

    private void empty(Graphics2D g, int x, int y, int w, int h, String title, String sub) {
        g.setFont(v.interBold.deriveFont(15f));
        g.setColor(TEXT);
        center(g, title, x, y + h / 2 - 40, w, 20);
        g.setFont(v.inter.deriveFont(12.5f));
        g.setColor(DIM);
        center(g, sub, x, y + h / 2 - 12, w, 18);
    }

    /** A thin banner (warnings, troubleshooting): amber or neutral, text on the left, buttons placed by the caller. */
    private void banner(Graphics2D g, int x, int y, int w, int h, boolean warn, String title, String sub) {
        g.setColor(warn ? new Color(251, 191, 36, 20) : new Color(255, 255, 255, 8));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 16, 16));
        g.setColor(warn ? new Color(251, 191, 36, 70) : HAIRLINE);
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, w - 1, h - 1, 16, 16));
        icon(g, "alert", x + 24, y + h / 2, 15, warn ? AMBER : DIM);
        g.setFont(v.interBold.deriveFont(12.5f));
        g.setColor(TEXT);
        g.drawString(ellipsizeEnd(g, title, w - 380), x + 44, y + h / 2 - 3);
        g.setFont(v.inter.deriveFont(11.5f));
        g.setColor(DIM);
        g.drawString(ellipsizeEnd(g, sub, w - 380), x + 44, y + h / 2 + 14);
    }

    // ------------------------------------------------------------------ installed

    private String sourceOf(Mods.Entry e) {
        if (e.managed()) return "Evade";
        String plain = e.fileName().replaceAll("(?i)\\.disabled$", "").toLowerCase(Locale.ROOT);
        if (recorded.contains(plain) || known.containsKey(e.file())) return "Modrinth";
        return "Manual";
    }

    /** The installed mods after the search box and sort order. */
    List<Mods.Entry> shownMods() {
        String q = filterField.getText().strip().toLowerCase(Locale.ROOT);
        List<Mods.Entry> out = new ArrayList<>();
        for (Mods.Entry e : v.mods) {
            if (!q.isEmpty() && !(e.name().toLowerCase(Locale.ROOT).contains(q) || e.fileName().toLowerCase(Locale.ROOT).contains(q)
                    || (e.id() != null && e.id().contains(q)) || e.description().toLowerCase(Locale.ROOT).contains(q))) continue;
            out.add(e);
        }
        switch (sort) {
            case "date" -> out.sort(Comparator.comparingLong((Mods.Entry e) -> modified.getOrDefault(e.file(), 0L)).reversed());
            case "size" -> out.sort(Comparator.comparingLong(Mods.Entry::size).reversed());
            case "updates" -> out.sort(Comparator.comparing((Mods.Entry e) -> !hasUpdate(e.file())));
            default -> { }
        }
        return out;
    }

    private List<Content.Pack> shownPacks() {
        String q = filterField.getText().strip().toLowerCase(Locale.ROOT);
        List<Content.Pack> out = new ArrayList<>();
        if (packs != null) for (Content.Pack p : packs) {
            if (!q.isEmpty() && !(p.name().toLowerCase(Locale.ROOT).contains(q) || (p.description() != null && p.description().toLowerCase(Locale.ROOT).contains(q)))) continue;
            out.add(p);
        }
        switch (sort) {
            case "date" -> out.sort(Comparator.comparingLong((Content.Pack p) -> mtime(p.file())).reversed());
            case "size" -> out.sort(Comparator.comparingLong(Content.Pack::size).reversed());
            case "updates" -> out.sort(Comparator.comparing((Content.Pack p) -> !hasUpdate(p.file())));
            default -> { }
        }
        return out;
    }

    private static long mtime(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0; }
    }

    private boolean hasUpdate(Path f) {
        Updates.Known k = known.get(f);
        return k != null && k.hasUpdate();
    }

    private void paintInstalled(Graphics2D g, Launcher.Instance sel, int x, int y, int w, int h) {
        // toolbar: type, search, sort, updates
        int sw = segmented(g, x, y, new String[][]{{"c-itype:MOD", "Mods"}, {"c-itype:RESOURCEPACK", "Resource packs"}, {"c-itype:SHADER", "Shader packs"}},
                "c-itype:" + installedType.name());
        long ups = known.values().stream().filter(k -> k.hasUpdate() && (installedType == Content.Type.MOD ? k.type() == Content.Type.MOD : true)).count();
        long allUps = versions.unpinnedCount(known.values());
        int bw = 170;
        int bx = x + w - bw;
        int undoW = versions.rollback.isEmpty() || bulk != null ? 0 : 150;
        if (bulk != null) progressPill(g, bx, y, bw, 40, bulkProgress, bulk);
        else if (checking) {
            g.setColor(new Color(0x1A1D23));
            g.fill(new RoundRectangle2D.Double(bx, y, bw, 40, 24, 24));
            spinner(g, bx + 24, y + 20, 7);
            g.setFont(v.interBold.deriveFont(12.5f));
            g.setColor(DIM);
            g.drawString("Checking…", bx + 42, y + 25);
        } else if (allUps > 0) v.smallPrimary(g, "c-updall", bx, y, bw, 40, "Update all (" + allUps + ")", "download");
        else v.ghostButton(g, "c-check", bx, y, bw, 40, "Check for updates", false, "download");
        String sortLabel = switch (sort) { case "date" -> "Newest"; case "size" -> "Largest"; case "updates" -> "Updates first"; default -> "Name"; };
        dropdown(g, "c-sort", bx - 12 - 150, y, 150, "Sort: " + sortLabel);
        if (undoW > 0) {
            v.ghostButton(g, "c-undo", bx - 12 - 150 - 12 - undoW, y, undoW, 40, "Undo update", false, "arrow-right");
            v.game.name("c-undo", "Undo the last update: puts the previous versions back");
        }
        filterRect = searchBox(g, filterField, x + sw + 12, y, bx - 12 - 150 - 12 - (undoW > 0 ? undoW + 12 : 0) - (x + sw + 12), "Search " + (installedType == Content.Type.MOD ? "mods" : installedType.label.toLowerCase(Locale.ROOT)));
        y += 52;
        h -= 52;

        // banners
        if (installedType == Content.Type.MOD) {
            List<ModChecks.Problem> errs = problems.stream().filter(p -> !p.warning()).toList();
            List<ModChecks.Problem> warns = problems.stream().filter(ModChecks.Problem::warning).toList();
            if (!troubleshoot.isEmpty()) {
                banner(g, x, y, w, 52, false, "Troubleshooting: " + troubleshoot.size() + " of your mods are switched off",
                        "Play to see if the problem is gone, then turn them back on one at a time, or all at once.");
                v.smallPrimary(g, "c-restore", x + w - 16 - 150, y + 10, 150, 32, "Restore mods", null);
                y += 62;
                h -= 62;
            }
            if (!problems.isEmpty()) {
                ModChecks.Problem first = errs.isEmpty() ? warns.get(0) : errs.get(0);
                String title = errs.isEmpty() ? (warns.size() == 1 ? first.title() : warns.size() + " things to look at")
                        : errs.size() == 1 ? first.title() : errs.size() + " problems will stop the game from starting";
                banner(g, x, y, w, 52, !errs.isEmpty(), title, errs.size() == 1 || errs.isEmpty() && warns.size() == 1 ? first.detail() : first.title() + (problems.size() > 1 ? " · and " + (problems.size() - 1) + " more" : ""));
                boolean fixable = problems.stream().anyMatch(p -> p.kind() == ModChecks.Kind.MISSING && p.option() == null || p.kind() == ModChecks.Kind.SWITCHED_OFF);
                int bxx = x + w - 16 - 110;
                v.ghostButton(g, "c-problems", bxx, y + 10, 110, 32, "Details", false);
                if (fixable) v.smallPrimary(g, "c-fixall", bxx - 10 - 150, y + 10, 150, 32, "Install missing", "download");
                y += 62;
                h -= 62;
            }
        }

        v.card(g, x, y, w, h, false);
        if (installedType == Content.Type.MOD) paintModRows(g, sel, x, y, w, h, ups);
        else paintPackRows(g, sel, x, y, w, h);
    }

    private void paintModRows(Graphics2D g, Launcher.Instance sel, int x, int y, int w, int h, long ups) {
        List<Mods.Entry> list = shownMods();
        if (v.mods.isEmpty()) {
            empty(g, x, y, w, h, sel != null && sel.installed == null ? "Nothing installed yet" : "No mods here",
                    sel != null && sel.installed == null ? "Press Play once to install Evade Client in this instance." : "Find mods under Browse Modrinth.");
            v.ghostButton(g, "c-tab:BROWSE", x + w / 2 - 90, y + h / 2 + 16, 180, 36, "Browse Modrinth", false);
            return;
        }
        if (list.isEmpty()) {
            empty(g, x, y, w, h - 50, "No mods match “" + filterField.getText().strip() + "”", "Try another name.");
        }
        Rectangle area = new Rectangle(x + 1, y + 8, w - 2, h - 58);
        float off = v.scrollOf("c-mods");
        Shape was = g.getClip();
        g.clip(area);
        v.clip = area;
        int ry = y + 10 - Math.round(off);
        Set<String> problemFiles = new HashSet<>();
        for (ModChecks.Problem p : problems) if (!p.warning()) for (Path f : p.mods()) problemFiles.add(f.toString());
        for (int n = 0; n < list.size(); n++) {
            Mods.Entry e = list.get(n);
            int idx = v.mods.indexOf(e);
            if (ry + 58 >= area.y && ry <= area.y + area.height) {
                String rowId = "c-row:" + idx;
                float t = v.anim(rowId, rowId.equals(v.hovered));
                boolean sel1 = selected.contains(e.file());
                g.setColor(sel1 ? new Color(225, 29, 46, 22) : new Color(255, 255, 255, (n % 2 == 1 ? 5 : 0) + Math.round(8 * t)));
                g.fill(new RoundRectangle2D.Double(x + 10, ry, w - 20, 56, 10, 10));
                v.hit(rowId, new Rectangle(x + 10, ry, w - 20, 56));
                int ix = x + 22;
                if (!e.managed()) checkbox(g, "c-sel:" + idx, ix, ry + 19, sel1);
                ix += 30;
                Color fade = e.enabled() ? TEXT : FAINT;
                v.modIcon(g, ix, ry + 9, 38, e.name(), "Evade".equals(e.option()) && e.name().startsWith("Evade") ? v.mark : ModIcons.get(e.file(), v::repaint), e.enabled());
                int tx = ix + 50;
                g.setFont(v.interBold.deriveFont(13f));
                g.setColor(fade);
                String name = ellipsizeEnd(g, e.name(), 260);
                g.drawString(name, tx, ry + 25);
                int cx = tx + g.getFontMetrics().stringWidth(name) + 8;
                g.setFont(v.inter.deriveFont(11.5f));
                g.setColor(FAINT);
                if (!e.version().isEmpty()) {
                    String ver = ellipsizeEnd(g, e.version(), 130);
                    g.drawString(ver, cx, ry + 25);
                    cx += g.getFontMetrics().stringWidth(ver) + 10;
                }
                String src = sourceOf(e);
                cx += chip(g, src, cx, ry + 12, src.equals("Evade") ? RED_HI : src.equals("Modrinth") ? new Color(0x5BE49B) : DIM,
                        src.equals("Evade") ? new Color(225, 29, 46, 30) : src.equals("Modrinth") ? new Color(52, 211, 153, 22) : new Color(255, 255, 255, 12)) + 6;
                if (problemFiles.contains(e.file().toString())) cx += chip(g, "Problem", cx, ry + 12, AMBER, new Color(251, 191, 36, 30)) + 6;
                Updates.Known k = known.get(e.file());
                Double up = updating.get(e.file());
                if (up != null) progressPill(g, cx, ry + 10, 110, 22, up, "Updating…");
                else if (k != null && k.hasUpdate()) {
                    String lab = "Update · " + ellipsizeEnd(g, k.latest().number(), 90);
                    chipButtonSmall(g, "c-upd:" + idx, lab, cx, ry + 11);
                }
                g.setFont(v.inter.deriveFont(12f));
                g.setColor(e.enabled() ? DIM : FAINT);
                String desc = !e.description().isEmpty() ? e.description() : e.fileName();
                g.drawString(ellipsizeEnd(g, desc, w - 360), tx, ry + 44);

                int rx = x + w - 30;
                g.setFont(v.inter.deriveFont(10.5f));
                g.setColor(FAINT);
                String size = sizeText(e.size());
                if (e.managed()) {
                    String tag = e.option().equals("Evade") ? "Required" : e.option();
                    boolean ev = e.option().equals("Evade");
                    g.setFont(v.interBold.deriveFont(10f));
                    int tw = g.getFontMetrics().stringWidth(tag) + 18;
                    g.setColor(ev ? new Color(225, 29, 46, 36) : new Color(255, 255, 255, 12));
                    g.fill(new RoundRectangle2D.Double(rx - tw, ry + 17, tw, 22, 22, 22));
                    g.setColor(ev ? RED_HI : DIM);
                    center(g, tag, rx - tw, ry + 17, tw, 22);
                    g.setFont(v.inter.deriveFont(10.5f));
                    g.setColor(FAINT);
                    String m = ev ? "kept up to date by Play" : "set in Instances";
                    g.drawString(m, rx - tw - 10 - g.getFontMetrics().stringWidth(m), ry + 32);
                } else {
                    v.iconButton(g, "c-rm:" + idx, rx - 32, ry + 12, 32, "trash", true);
                    v.toggle(g, "mod-toggle:" + idx, rx - 32 - 56, ry + 18, e.enabled(), true);
                    g.setFont(v.inter.deriveFont(10.5f));
                    g.setColor(FAINT);
                    g.drawString(size, rx - 32 - 56 - 12 - g.getFontMetrics().stringWidth(size), ry + 32);
                }
            }
            ry += 58;
        }
        int content = ry + Math.round(off) - (y + 10);
        g.setClip(was);
        v.clip = null;
        v.scrollArea(g, "c-mods", area, content);
        g.setColor(BORDER);
        g.fillRect(x + 1, y + h - 50, w - 2, 1);
        int fy = y + h - 41;
        if (!selected.isEmpty()) {
            checkbox(g, "c-selall", x + 22, fy + 7, true);
            g.setFont(v.interBold.deriveFont(12.5f));
            g.setColor(TEXT);
            g.drawString(selected.size() + " selected", x + 52, fy + 21);
            int bx2 = x + w - 24;
            v.ghostButton(g, "c-clear", bx2 -= 90, fy, 90, 32, "Clear", false);
            v.ghostButton(g, "c-del", bx2 -= 100, fy, 90, 32, "Remove", true);
            v.ghostButton(g, "c-off", bx2 -= 120, fy, 110, 32, "Switch off", false);
            v.ghostButton(g, "c-on", bx2 -= 120, fy, 110, 32, "Switch on", false);
            return;
        }
        boolean any = v.mods.stream().anyMatch(m -> !m.managed());
        if (any) checkbox(g, "c-selall", x + 22, fy + 7, false);
        g.setFont(v.inter.deriveFont(11.5f));
        g.setColor(FAINT);
        long offN = v.mods.stream().filter(m -> !m.enabled()).count();
        String line = v.mods.size() + " mods" + (offN > 0 ? "  ·  " + offN + " switched off" : "")
                + (ups > 0 ? "  ·  " + ups + (ups == 1 ? " update" : " updates") : checkError != null ? "  ·  update check failed" : "")
                + "  ·  Evade and its extras are kept up to date by Play";
        g.drawString(ellipsizeEnd(g, line, w - 470), x + (any ? 52 : 24), fy + 21);
        v.ghostButton(g, "mods-folder", x + w - 24 - 150, fy, 150, 32, "Open mods folder", false);
        if (troubleshoot.isEmpty() && any) v.ghostButton(g, "c-trouble", x + w - 24 - 150 - 10 - 220, fy, 220, 32, "Switch off non-Evade mods", false);
    }

    private void chipButtonSmall(Graphics2D g, String id, String s, int x, int y) {
        float t = v.anim(id, id.equals(v.hovered));
        g.setFont(v.interBold.deriveFont(10.5f));
        int w = g.getFontMetrics().stringWidth(s) + 30;
        g.setColor(mix(new Color(251, 191, 36, 36), new Color(251, 191, 36, 80), t));
        g.fill(new RoundRectangle2D.Double(x, y, w, 20, 20, 20));
        icon(g, "download", x + 12, y + 10, 9, AMBER);
        g.setColor(mix(AMBER, Color.WHITE, t));
        g.drawString(s, x + 21, y + 14);
        v.hit(id, new Rectangle(x, y, w, 20));
    }

    private void paintPackRows(Graphics2D g, Launcher.Instance sel, int x, int y, int w, int h) {
        List<Content.Pack> list = shownPacks();
        String folder = installedType.folder;
        if (packs == null || packs.isEmpty()) {
            empty(g, x, y, w, h, packs == null ? "Loading…" : "No " + installedType.label.toLowerCase(Locale.ROOT) + " yet",
                    installedType == Content.Type.SHADER ? "Find shader packs under Browse Modrinth. They need Shaders (Iris) switched on in Instances."
                            : "Find resource packs under Browse Modrinth, or drop zips in the " + folder + " folder.");
            v.ghostButton(g, "c-browse-type", x + w / 2 - 90, y + h / 2 + 16, 180, 36, "Browse Modrinth", false);
            return;
        }
        String active = installedType == Content.Type.SHADER ? activeShader(sel) : null;
        Set<String> enabledRp = installedType == Content.Type.RESOURCEPACK ? enabledResourcePacks(sel) : Set.of();
        Rectangle area = new Rectangle(x + 1, y + 8, w - 2, h - 58);
        float off = v.scrollOf("c-packs");
        Shape was = g.getClip();
        g.clip(area);
        v.clip = area;
        int ry = y + 10 - Math.round(off);
        for (int n = 0; n < list.size(); n++) {
            Content.Pack p = list.get(n);
            int idx = packs.indexOf(p);
            if (ry + 58 >= area.y && ry <= area.y + area.height) {
                String rowId = "c-prow:" + idx;
                float t = v.anim(rowId, rowId.equals(v.hovered));
                g.setColor(new Color(255, 255, 255, (n % 2 == 1 ? 5 : 0) + Math.round(8 * t)));
                g.fill(new RoundRectangle2D.Double(x + 10, ry, w - 20, 56, 10, 10));
                v.hit(rowId, new Rectangle(x + 10, ry, w - 20, 56));
                Updates.Known k = known.get(p.file());
                ContentApi.Project pr = k != null && k.current() != null ? projects.get(k.current().projectId()) : null;
                Image img = ModIcons.local(p.file(), "pack.png", mtime(p.file()), v::repaint);
                if (img == null && pr != null) img = ModIcons.url(pr.iconUrl(), v::repaint);
                v.modIcon(g, x + 22, ry + 9, 38, p.name(), img, true);
                int tx = x + 72;
                g.setFont(v.interBold.deriveFont(13f));
                g.setColor(TEXT);
                String name = ellipsizeEnd(g, pr != null ? pr.title() : p.name(), 320);
                g.drawString(name, tx, ry + 25);
                int cx = tx + g.getFontMetrics().stringWidth(name) + 10;
                String src = k != null || p.projectId() != null ? "Modrinth" : "Manual";
                cx += chip(g, src, cx, ry + 12, src.equals("Modrinth") ? new Color(0x5BE49B) : DIM, src.equals("Modrinth") ? new Color(52, 211, 153, 22) : new Color(255, 255, 255, 12)) + 6;
                boolean on = installedType == Content.Type.SHADER ? p.fileName().equals(active) : enabledRp.contains("file/" + p.fileName());
                if (on) cx += chip(g, installedType == Content.Type.SHADER ? "In use" : "On", cx, ry + 12, OK, new Color(52, 211, 153, 22)) + 6;
                Double up = updating.get(p.file());
                if (up != null) progressPill(g, cx, ry + 10, 110, 22, up, "Updating…");
                else if (k != null && k.hasUpdate()) chipButtonSmall(g, "c-pupd:" + idx, "Update · " + k.latest().number(), cx, ry + 11);
                g.setFont(v.inter.deriveFont(12f));
                g.setColor(DIM);
                String desc = p.description() != null && !p.description().isBlank() ? p.description() : p.fileName();
                g.drawString(ellipsizeEnd(g, desc, w - 360), tx, ry + 44);
                int rx = x + w - 30;
                v.iconButton(g, "c-prm:" + idx, rx - 32, ry + 12, 32, "trash", true);
                g.setFont(v.inter.deriveFont(10.5f));
                g.setColor(FAINT);
                String size = (p.folder() ? "folder · " : "") + sizeText(p.size());
                g.drawString(size, rx - 44 - g.getFontMetrics().stringWidth(size), ry + 32);
            }
            ry += 58;
        }
        int content = ry + Math.round(off) - (y + 10);
        g.setClip(was);
        v.clip = null;
        v.scrollArea(g, "c-packs", area, content);
        g.setColor(BORDER);
        g.fillRect(x + 1, y + h - 50, w - 2, 1);
        g.setFont(v.inter.deriveFont(11.5f));
        g.setColor(FAINT);
        String note = installedType == Content.Type.SHADER
                ? (sel != null && !sel.shaders ? "Shader packs need Iris: switch on Shaders for this instance in Instances" : "Pick the pack in game: Options › Video Settings › Shader Packs")
                : "New packs are switched on for you; reorder them in game under Options › Resource Packs";
        g.drawString(ellipsizeEnd(g, packs.size() + " packs  ·  " + note, w - 230), x + 24, y + h - 20);
        v.ghostButton(g, "c-pfolder", x + w - 24 - 170, y + h - 41, 170, 32, "Open " + folder + " folder", false);
    }

    private static String activeShader(Launcher.Instance sel) {
        if (sel == null) return null;
        try {
            java.util.Properties p = new java.util.Properties();
            try (var in = Files.newInputStream(sel.dir.resolve("config").resolve("iris.properties"))) { p.load(in); }
            return p.getProperty("shaderPack");
        } catch (Exception e) {
            return null;
        }
    }

    private static Set<String> enabledResourcePacks(Launcher.Instance sel) {
        Set<String> out = new HashSet<>();
        if (sel == null) return out;
        try {
            for (String l : Files.readAllLines(sel.dir.resolve("options.txt"))) {
                if (!l.startsWith("resourcePacks:")) continue;
                for (var e : com.google.gson.JsonParser.parseString(l.substring(14)).getAsJsonArray()) out.add(e.getAsString());
            }
        } catch (Exception ignored) { }
        return out;
    }

    static String sizeText(long b) {
        if (b <= 0) return "";
        if (b < 1024 * 1024) return Math.max(1, b / 1024) + " KB";
        if (b < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0).replace(".0 MB", " MB");
        return String.format(Locale.ROOT, "%.1f GB", b / 1073741824.0);
    }

    // ------------------------------------------------------------------ browse

    private void paintBrowse(Graphics2D g, Launcher.Instance sel, int x, int y, int w, int h) {
        String[][] types = new String[Content.Type.values().length][];
        for (int i = 0; i < types.length; i++) types[i] = new String[]{"c-btype:" + Content.Type.values()[i].name(), Content.Type.values()[i].label};
        int sw = segmented(g, x, y, types, "c-btype:" + browseType.name());
        String sortLabel = browseSort.label;
        dropdown(g, "c-bsort", x + w - 190, y, 190, "Sort: " + sortLabel);
        browseRect = searchBox(g, browseField, x + sw + 12, y, w - sw - 12 - 190 - 12,
                "Search " + browseType.label.toLowerCase(Locale.ROOT) + " for " + InstallerCore.MC_VERSION);
        y += 50;
        h -= 50;
        // filters
        int fx = x;
        fx += chipButton(g, "c-cat:", "All", fx, y, category == null) + 8;
        for (String[] c : Content.categories(browseType)) fx += chipButton(g, "c-cat:" + c[0], c[1], fx, y, c[0].equals(category)) + 8;
        if (browseType == Content.Type.MOD) {
            fx += 8;
            fx += chipButton(g, "c-client", (clientOnly ? "✓ " : "") + "Works on any server", fx, y, clientOnly) + 8;
        }
        // where it goes / what it needs
        int rx = x + w;
        if (browseType == Content.Type.DATAPACK) {
            String wn = "Pick a world";
            if (worlds == null && !worldsLoading && sel != null && !offline) loadWorlds(sel);
            if (worlds != null) for (Worlds.World wo : worlds) if (wo.folder().equals(datapackWorld)) wn = wo.name();
            if (worlds != null && worlds.isEmpty()) wn = "No worlds yet";
            int dw = 230;
            dropdown(g, "c-dpworld", rx - dw, y - 6, dw, "Into: " + wn);
        } else if (browseType == Content.Type.SHADER && sel != null && !sel.shaders) {
            g.setFont(v.inter.deriveFont(11.5f));
            g.setColor(AMBER);
            String s = "Shader packs need Iris";
            int bw2 = 150;
            v.ghostButton(g, "c-iris", rx - bw2, y - 2, bw2, 32, "Switch on Shaders", false);
            g.drawString(s, rx - bw2 - 12 - g.getFontMetrics().stringWidth(s), y + 18);
        } else if (browseType == Content.Type.MODPACK) {
            g.setFont(v.inter.deriveFont(11.5f));
            g.setColor(FAINT);
            String s = "Each modpack becomes a new instance with Evade on top";
            g.drawString(s, rx - g.getFontMetrics().stringWidth(s), y + 18);
        }
        y += 40;
        h -= 40;

        v.card(g, x, y, w, h, false);
        if (hits.isEmpty()) {
            g.setFont(v.inter.deriveFont(12.5f));
            g.setColor(searchError != null ? LauncherTheme.DANGER : DIM);
            String msg = searching ? "Searching Modrinth…" : searchError != null ? EvadeInstaller.sentence(searchError)
                    : "No " + browseType.label.toLowerCase(Locale.ROOT) + " found" + (lastQuery.isEmpty() ? "" : " for “" + lastQuery + "”") + ".";
            center(g, msg, x, y + h / 2 - 20, w, 18);
            if (searchError != null && !searching) v.ghostButton(g, "c-retry", x + w / 2 - 70, y + h / 2 + 6, 140, 34, "Try again", false);
            return;
        }
        Set<String> installed = installedFor(sel);
        Rectangle area = new Rectangle(x + 1, y + 8, w - 2, h - 16);
        float off = v.scrollOf("c-browse");
        Shape was = g.getClip();
        g.clip(area);
        v.clip = area;
        int ry = y + 10 - Math.round(off);
        for (int n = 0; n < hits.size(); n++) {
            Mods.Hit hh = hits.get(n);
            if (ry + 70 >= area.y && ry <= area.y + area.height) {
                String rowId = "c-hitd:" + n;
                float t = v.anim(rowId, rowId.equals(v.hovered));
                g.setColor(new Color(255, 255, 255, (n % 2 == 1 ? 5 : 0) + Math.round(8 * t)));
                g.fill(new RoundRectangle2D.Double(x + 10, ry, w - 20, 68, 10, 10));
                v.hit(rowId, new Rectangle(x + 10, ry, w - 20, 68));
                v.modIcon(g, x + 22, ry + 13, 42, hh.title(), ModIcons.url(hh.iconUrl(), v::repaint), true);
                g.setFont(v.interBold.deriveFont(13.5f));
                g.setColor(TEXT);
                String title = ellipsizeEnd(g, hh.title(), 280);
                g.drawString(title, x + 78, ry + 26);
                int titleW = g.getFontMetrics().stringWidth(title);
                g.setFont(v.inter.deriveFont(11.5f));
                g.setColor(FAINT);
                g.drawString(ellipsizeEnd(g, "by " + hh.author() + "  ·  " + Mods.count(hh.downloads()) + " downloads", w - 300 - titleW), x + 78 + titleW + 10, ry + 26);
                g.setColor(DIM);
                g.setFont(v.inter.deriveFont(12f));
                g.drawString(ellipsizeEnd(g, hh.description(), w - 260), x + 78, ry + 47);

                String st = hitState.get(hh.projectId());
                String option = browseType == Content.Type.MOD ? InstallerCore.optionForSlug(hh.slug()) : null;
                int bx = x + w - 30 - 130;
                if (option != null) {
                    g.setFont(v.inter.deriveFont(11f));
                    g.setColor(FAINT);
                    center(g, "Part of " + option, bx, ry + 18, 130, 32);
                } else if ("installing".equals(st)) {
                    progressPill(g, bx, ry + 18, 130, 32, hitProgress, browseType == Content.Type.MODPACK ? "Importing…" : "Installing…");
                } else if ("installed".equals(st) || installed.contains(hh.projectId())) {
                    g.setColor(new Color(52, 211, 153, 22));
                    g.fill(new RoundRectangle2D.Double(bx, ry + 18, 130, 32, 10, 10));
                    icon(g, "check", bx + 30, ry + 34, 10, OK);
                    g.setFont(v.interBold.deriveFont(12f));
                    g.setColor(OK);
                    g.drawString(browseType == Content.Type.MODPACK ? "Imported" : "Installed", bx + 42, ry + 38);
                } else {
                    boolean noWorld = browseType == Content.Type.DATAPACK && datapackWorld == null;
                    if (noWorld) {
                        g.setFont(v.inter.deriveFont(11f));
                        g.setColor(FAINT);
                        center(g, "Pick a world first", bx, ry + 18, 130, 32);
                    } else v.smallPrimary(g, "c-inst:" + n, bx, ry + 18, 130, 32, browseType == Content.Type.MODPACK ? "Import" : "Install", "download");
                    if (st != null) {
                        g.setFont(v.inter.deriveFont(10.5f));
                        g.setColor(RED_HI);
                        g.drawString(ellipsizeEnd(g, st, w - 260), x + 78, ry + 64);
                    }
                }
            }
            ry += 74;
        }
        if (hits.size() < hitsTotal) {
            v.ghostButton(g, "c-more", x + w / 2 - 80, ry + 6, 160, 34, searching ? "Loading…" : "Load more", false);
            ry += 50;
        }
        int content = ry + Math.round(off) - (y + 10);
        g.setClip(was);
        v.clip = null;
        v.scrollArea(g, "c-browse", area, content);
    }

    /** What of the browse type is already in the instance (Modrinth project ids). */
    private Set<String> installedFor(Launcher.Instance sel) {
        Set<String> out = new HashSet<>();
        if (sel == null) return out;
        if (browseType == Content.Type.MOD) out.addAll(v.installedProjects);
        for (Updates.Known k : known.values()) if (k.current() != null && k.current().projectId() != null && k.type() == browseType) out.add(k.current().projectId());
        if (browseType == Content.Type.RESOURCEPACK || browseType == Content.Type.SHADER) out.addAll(installedPackIds.getOrDefault(browseType, Set.of()));
        if (browseType == Content.Type.DATAPACK && datapackWorld != null) out.addAll(installedPackIds.getOrDefault(Content.Type.DATAPACK, Set.of()));
        return out;
    }

    private final Map<Content.Type, Set<String>> installedPackIds = new HashMap<>();

    /** Runs a Modrinth search with the browse tab's type, sort and filters. */
    void search(boolean more) {
        if (offline) return;
        int s = seq.incrementAndGet();
        String q = browseField.getText().strip();
        int offset = more ? hits.size() : 0;
        Content.Type t = browseType;
        Content.Sort so = browseSort;
        String cat = category;
        boolean client = clientOnly;
        searching = true;
        searchError = null;
        searched = true;
        if (!more) {
            lastQuery = q;
            v.resetScroll("c-browse");
        }
        Launcher.Instance sel = v.selected();
        String world = datapackWorld;
        v.repaint();
        work.submit(() -> {
            try {
                Mods.Page page = Content.search(core, t, q, so, cat, client, offset);
                Set<String> have = new HashSet<>();
                if (sel != null && (t == Content.Type.RESOURCEPACK || t == Content.Type.SHADER || t == Content.Type.DATAPACK))
                    have = Content.installedProjects(sel.dir, t, t == Content.Type.DATAPACK ? world : null);
                Set<String> h2 = have;
                ui(() -> {
                    if (s != seq.get()) return;
                    searching = false;
                    List<Mods.Hit> l = new ArrayList<>(more ? hits : List.of());
                    l.addAll(page.hits());
                    hits = l;
                    hitsTotal = page.total();
                    installedPackIds.put(t, h2);
                });
            } catch (Exception e) {
                ui(() -> {
                    if (s != seq.get()) return;
                    searching = false;
                    if (!more) hits = List.of();
                    searchError = e.getMessage() != null ? e.getMessage() : "Couldn't reach Modrinth";
                });
            }
        });
    }

    private void install(Mods.Hit h) {
        Launcher.Instance sel = v.selected();
        if (sel == null) return;
        Content.Type t = browseType;
        String world = datapackWorld;
        hitState.put(h.projectId(), "installing");
        hitProgress = 0;
        InstallerCore.Progress p = (f, m) -> SwingUtilities.invokeLater(() -> hitProgress = f);
        work.submit(() -> {
            try {
                String done;
                if (t == Content.Type.MOD) {
                    List<String> names = Mods.install(core, sel.dir, h, p);
                    done = "Installed " + h.title() + (names.size() > 1 ? " and " + (names.size() - 1) + " librar" + (names.size() == 2 ? "y" : "ies") : "") + " in " + sel.name;
                } else if (t == Content.Type.MODPACK) {
                    Launcher.Instance made = importFromModrinth(h, p);
                    done = "Made " + made.name + ". Press Play to install Evade in it.";
                } else {
                    Content.installPack(sel.dir, t, h, world, p);
                    done = "Installed " + h.title() + (t == Content.Type.SHADER && !sel.shaders ? ". Switch on Shaders in Instances to use it." : t == Content.Type.DATAPACK ? " in that world" : "");
                }
                String msg = done;
                ui(() -> {
                    hitState.put(h.projectId(), "installed");
                    v.toast(msg);
                    packs = null;
                    if (t == Content.Type.RESOURCEPACK || t == Content.Type.SHADER) installedPackIds.computeIfAbsent(t, k -> new HashSet<>()).add(h.projectId());
                    if (t == Content.Type.DATAPACK) installedPackIds.computeIfAbsent(t, k -> new HashSet<>()).add(h.projectId());
                });
                if (t == Content.Type.MOD) refreshMods(sel);
            } catch (Exception e) {
                String msg = EvadeInstaller.friendly(e);
                ui(() -> hitState.put(h.projectId(), msg));
            }
        });
    }

    /** Downloads a modpack's newest Fabric 1.21.11 .mrpack from Modrinth and imports it into a new instance. */
    private Launcher.Instance importFromModrinth(Mods.Hit h, InstallerCore.Progress p) throws Exception {
        ContentApi.Version ver = ContentApi.newest(h.projectId(), List.of("fabric"));
        if (ver == null) throw new IOException(h.title() + " has no version for Fabric " + InstallerCore.MC_VERSION);
        Path tmp = Launcher.dataDir().resolve("downloads").resolve("pack-" + Long.toHexString(System.nanoTime()) + ".mrpack");
        try {
            p.update(0.02, "Downloading " + h.title());
            ContentApi.download(ver.url(), tmp, ver.sha512(), java.util.Set.of("cdn.modrinth.com"), f -> p.update(0.02 + 0.08 * f, null));
            Mrpack.Info info = Mrpack.inspect(tmp);
            if (!info.ok()) throw new IOException(info.problem());
            Mrpack.Result r = Mrpack.importPack(v.settings, List.copyOf(v.instances), tmp, v.settings.defaultMemoryGb, (f, m) -> p.update(0.1 + 0.9 * f, m));
            afterImport(r, h.title());
            return r.instance();
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }

    /** A new instance from a pack or code: list it, select it, show what was left out. */
    private void afterImport(Mrpack.Result r, String what) {
        ui(() -> {
            List<Launcher.Instance> list = Launcher.listInstances(v.settings);
            for (Launcher.Instance i : list) Launcher.check(i, v.release);
            v.instances = list;
            for (Launcher.Instance i : list) if (i.id.equals(r.instance().id)) { v.actions.select(i); v.focusedId = i.id; }
            resultTitle = "Made “" + r.instance().name + "” from " + what + ": " + r.count() + " files";
            resultNotes = r.notes();
            resultError = false;
            v.notice("New instance: " + r.instance().name, "Made from " + what + ". Press Play to install Evade in it.", false);
        });
    }

    // ------------------------------------------------------------------ worlds

    private void paintWorlds(Graphics2D g, Launcher.Instance sel, int x, int y, int w, int h) {
        long total = 0;
        boolean anyLocked = false;
        if (worlds != null) for (Worlds.World wo : worlds) { total += wo.size(); anyLocked |= wo.locked(); }
        g.setFont(v.interBold.deriveFont(14f));
        g.setColor(TEXT);
        String head = worlds == null ? "Worlds" : worlds.size() + (worlds.size() == 1 ? " world" : " worlds");
        g.drawString(head, x + 4, y + 25);
        int hw = g.getFontMetrics().stringWidth(head);
        g.setFont(v.inter.deriveFont(12f));
        g.setColor(FAINT);
        if (worlds != null && !worlds.isEmpty()) g.drawString(sizeText(total) + "  ·  backups keep the newest " + Worlds.KEEP + " per world", x + 4 + hw + 12, y + 25);
        v.ghostButton(g, "c-wbackups", x + w - 170, y, 170, 40, "Backups folder", false, "folder");
        v.ghostButton(g, "c-wsaves", x + w - 170 - 10 - 170, y, 170, 40, "Saves folder", false, "folder");
        v.ghostButton(g, "c-wreload", x + w - 340 - 10 - 44, y, 44, 40, "↻", false);
        y += 52;
        h -= 52;
        if (anyLocked) {
            banner(g, x, y, w, 52, true, "Minecraft has a world open", "Leave the world (or close the game) before backing it up, restoring, renaming or deleting it.");
            y += 62;
            h -= 62;
        }
        v.card(g, x, y, w, h, false);
        if (worlds == null) {
            spinner(g, x + w / 2, y + h / 2 - 10, 10);
            return;
        }
        if (worlds.isEmpty()) {
            empty(g, x, y, w, h, "No worlds yet", "Singleplayer worlds you create in " + (sel != null ? sel.name : "this instance") + " show up here.");
            return;
        }
        Rectangle area = new Rectangle(x + 1, y + 8, w - 2, h - 16);
        float off = v.scrollOf("c-worlds");
        Shape was = g.getClip();
        g.clip(area);
        v.clip = area;
        int ry = y + 10 - Math.round(off);
        for (int n = 0; n < worlds.size(); n++) {
            Worlds.World wo = worlds.get(n);
            if (ry + 72 >= area.y && ry <= area.y + area.height) {
                if (n % 2 == 1) {
                    g.setColor(new Color(255, 255, 255, 5));
                    g.fill(new RoundRectangle2D.Double(x + 10, ry, w - 20, 68, 10, 10));
                }
                Image img = wo.icon() != null ? ModIcons.local(wo.icon(), null, mtime(wo.icon()), v::repaint) : null;
                v.modIcon(g, x + 22, ry + 12, 44, wo.name(), img, true);
                int tx = x + 80;
                g.setFont(v.interBold.deriveFont(13.5f));
                g.setColor(TEXT);
                String name = ellipsizeEnd(g, wo.name(), 360);
                g.drawString(name, tx, ry + 28);
                int cx = tx + g.getFontMetrics().stringWidth(name) + 10;
                cx += chip(g, wo.mode(), cx, ry + 15, wo.hardcore() ? RED_HI : DIM, wo.hardcore() ? new Color(225, 29, 46, 30) : new Color(255, 255, 255, 12)) + 6;
                if (wo.cheats()) cx += chip(g, "Cheats", cx, ry + 15, DIM, new Color(255, 255, 255, 12)) + 6;
                if (wo.locked()) chip(g, "Open in game", cx, ry + 15, AMBER, new Color(251, 191, 36, 30));
                g.setFont(v.inter.deriveFont(12f));
                g.setColor(DIM);
                List<Worlds.Backup> bs = backups.getOrDefault(wo.folder(), List.of());
                String line = "Played " + ago(wo.lastPlayed()) + "  ·  " + sizeText(wo.size()) + "  ·  "
                        + (bs.isEmpty() ? "no backups" : bs.size() + (bs.size() == 1 ? " backup" : " backups") + ", last " + ago(bs.get(0).time()))
                        + (wo.folder().equals(wo.name()) ? "" : "  ·  " + wo.folder());
                g.drawString(ellipsizeEnd(g, line, w - 420), tx, ry + 48);
                int rx = x + w - 30;
                v.iconButton(g, "c-wmenu:" + n, rx - 36, ry + 16, 36, "list", false);
                Double busy = worldBusy.get(wo.folder());
                if (busy != null) progressPill(g, rx - 36 - 10 - 130, ry + 18, 130, 32, busy, "Working…");
                else {
                    v.ghostButton(g, "c-wbackup:" + n, rx - 36 - 10 - 130, ry + 18, 130, 32, "Back up", false, "download");
                    if (!bs.isEmpty()) v.ghostButton(g, "c-wrestore:" + n, rx - 36 - 20 - 260, ry + 18, 120, 32, "Restore…", false);
                }
            }
            ry += 72;
        }
        int content = ry + Math.round(off) - (y + 10);
        g.setClip(was);
        v.clip = null;
        v.scrollArea(g, "c-worlds", area, content);
    }

    /** "3 min ago", "yesterday", "12 Mar" (worlds and backups). */
    static String ago(long ms) {
        if (ms <= 0) return "never";
        long d = System.currentTimeMillis() - ms;
        if (d < 60_000) return "just now";
        if (d < 3_600_000) return d / 60_000 + " min ago";
        if (d < 86_400_000) return d / 3_600_000 + " h ago";
        if (d < 2 * 86_400_000) return "yesterday";
        if (d < 14L * 86_400_000) return d / 86_400_000 + " days ago";
        return DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()));
    }

    // ------------------------------------------------------------------ modpacks & sharing

    private void paintPacks(Graphics2D g, Launcher.Instance sel, int x, int y, int w, int h) {
        int gap = 20, cw = (w - gap) / 2;
        int ch = Math.min(h, 360);
        // import
        v.card(g, x, y, cw, ch, false);
        int ix = x + 26, iy = y + 40;
        g.setFont(v.interBold.deriveFont(17f));
        g.setColor(TEXT);
        g.drawString("Add a modpack", ix, iy);
        g.setFont(v.inter.deriveFont(12.5f));
        g.setColor(DIM);
        int ly = iy + 26;
        for (String l : LauncherView.wrapLines(g, "Import a Modrinth modpack (.mrpack) or a friend's share code. Each one becomes a new instance with Evade added on top. "
                + "Only modpacks for Minecraft " + InstallerCore.MC_VERSION + " with Fabric work.", cw - 52, 4)) { g.drawString(l, ix, ly); ly += 19; }
        int by = ly + 14;
        v.smallPrimary(g, "c-impfile", ix, by, cw - 52, 42, "Import a .mrpack file…", "download");
        v.ghostButton(g, "c-impbrowse", ix, by + 54, (cw - 52 - 12) / 2, 40, "Find modpacks", false, "search");
        v.ghostButton(g, "c-impcode", ix + (cw - 52 - 12) / 2 + 12, by + 54, (cw - 52 - 12) / 2, 40, "Paste a share code", false, "copy");
        g.setFont(v.inter.deriveFont(11.5f));
        g.setColor(FAINT);
        int ny = by + 124;
        for (String l : LauncherView.wrapLines(g, "Files are only downloaded from Modrinth and GitHub, and each one is checked against the pack's SHA-512. "
                + "Fabric API, FPS mods, Iris and Voice chat come from Evade's own options instead of the pack.", cw - 52, 4)) { g.drawString(l, ix, ny); ny += 17; }

        // export / share
        int ex = x + cw + gap;
        v.card(g, ex, y, cw, ch, false);
        ix = ex + 26;
        g.setFont(v.interBold.deriveFont(17f));
        g.setColor(TEXT);
        g.drawString(ellipsizeEnd(g, "Share " + (sel != null ? sel.name : "this instance"), cw - 52), ix, iy);
        g.setFont(v.inter.deriveFont(12.5f));
        g.setColor(DIM);
        ly = iy + 26;
        for (String l : LauncherView.wrapLines(g, "Export a .mrpack anyone can import, or copy a short share code with this instance's Modrinth mods, packs and options.", cw - 52, 3)) {
            g.drawString(l, ix, ly);
            ly += 19;
        }
        int ry = ly + 8;
        String[][] opts = {{"c-exo:config", "Mod settings (config folder)"}, {"c-exo:options", "Game options and keybinds"},
                {"c-exo:packs", "Resource and shader packs"}, {"c-exo:servers", "Server list"}};
        boolean[] vals = {exConfig, exOptions, exPacks, exServers};
        for (int i = 0; i < opts.length; i++) {
            checkbox(g, opts[i][0], ix, ry, vals[i]);
            g.setFont(v.inter.deriveFont(12.5f));
            g.setColor(TEXT);
            g.drawString(opts[i][1], ix + 28, ry + 14);
            v.hit(opts[i][0], new Rectangle(ix - 4, ry - 4, cw - 52, 26));
            ry += 28;
        }
        int bw = (cw - 52 - 12) / 2;
        v.smallPrimary(g, "c-export", ix, ry + 8, bw, 40, "Export .mrpack…", "download");
        v.ghostButton(g, "c-sharecode", ix + bw + 12, ry + 8, bw, 40, "Copy share code", false, "copy");
        g.setFont(v.inter.deriveFont(11.5f));
        g.setColor(FAINT);
        ny = ry + 68;
        for (String l : LauncherView.wrapLines(g, "Mods on Modrinth are linked, not copied. Evade itself, switched-off mods, and mods whose licence doesn't allow sharing copies are left out.",
                cw - 52, 3)) { g.drawString(l, ix, ny); ny += 17; }

        // status / result
        int sy = y + ch + 16;
        int sh = H - 24 - sy;
        if (sh < 60) return;
        if (packBusy != null) {
            v.card(g, x, sy, w, Math.min(sh, 70), false);
            spinner(g, x + 30, sy + 35, 9);
            g.setFont(v.interBold.deriveFont(13f));
            g.setColor(TEXT);
            g.drawString(ellipsizeEnd(g, packBusy, w - 300), x + 52, sy + 40);
            progressPill(g, x + w - 26 - 220, sy + 21, 220, 28, packProgress, Math.round(packProgress * 100) + "%");
        } else if (resultTitle != null) {
            v.card(g, x, sy, w, sh, false);
            icon(g, resultError ? "alert" : "check", x + 30, sy + 30, 14, resultError ? LauncherTheme.DANGER : OK);
            g.setFont(v.interBold.deriveFont(13f));
            g.setColor(TEXT);
            g.drawString(ellipsizeEnd(g, resultTitle, w - 120), x + 52, sy + 35);
            v.iconButton(g, "c-resx", x + w - 44, sy + 14, 32, "close-x", false);
            g.setFont(v.inter.deriveFont(12f));
            g.setColor(DIM);
            int ty2 = sy + 60;
            int maxLines = Math.max(0, (sh - 70) / 18);
            List<String> notes = resultNotes;
            for (int i = 0; i < notes.size() && i < maxLines; i++) {
                String s = i == maxLines - 1 && notes.size() > maxLines ? "…and " + (notes.size() - i) + " more" : "•  " + notes.get(i);
                g.drawString(ellipsizeEnd(g, s, w - 80), x + 52, ty2);
                ty2 += 18;
            }
            if (notes.isEmpty()) g.drawString("Nothing was left out.", x + 52, ty2);
        }
    }

    // ------------------------------------------------------------------ sheet (details over the page)

    private void paintSheet(Graphics2D g, Launcher.Instance sel) {
        // Everything under the sheet is out of reach while it's open.
        v.hitMap.clear();
        v.hitMap.put("c-dismiss", new Rectangle(0, 0, W, H));
        g.setColor(new Color(4, 5, 7, 150));
        g.fill(new RoundRectangle2D.Double(0, 0, W, H, 22, 22));
        int w = 620, h = 520, x = (W - w) / 2, y = (H - h) / 2;
        v.raised(g, x, y, w, h);
        v.iconButton(g, "c-sheetx", x + w - 48, y + 16, 32, "close-x", false);
        int ix = x + 28, iw = w - 56;
        if (sheet.equals("problems")) { paintProblems(g, x, y, w, h, ix, iw); return; }
        if (sheet.equals("versions")) { versions.paint(g, x, y, w, h, ix, iw); return; }
        if (sheet.startsWith("addon:")) { addons.paintSheet(g, sel, sheet.substring(6), x, y, w, h, ix, iw); return; }
        if (sheet.startsWith("datapacks:")) { paintDatapacks(g, sel, x, y, w, h, ix, iw, sheet.substring(10)); return; }
        String title, sub, body = null, projectId = null, url = null;
        Image img = null;
        Path file = null;
        Updates.Known k = null;
        if (sheet.startsWith("mod:")) {
            int n = Integer.parseInt(sheet.substring(4));
            if (n >= v.mods.size()) { sheet = null; return; }
            Mods.Entry e = v.mods.get(n);
            file = e.file();
            k = known.get(file);
            title = e.name();
            sub = (e.version().isEmpty() ? "" : e.version() + "  ·  ") + e.fileName() + "  ·  " + sizeText(e.size()) + "  ·  " + sourceOf(e)
                    + (e.authors().isEmpty() ? "" : "  ·  by " + e.authors());
            img = "Evade".equals(e.option()) && e.name().startsWith("Evade") ? v.mark : ModIcons.get(e.file(), v::repaint);
            body = e.description();
        } else if (sheet.startsWith("pack:")) {
            int n = Integer.parseInt(sheet.substring(5));
            if (packs == null || n >= packs.size()) { sheet = null; return; }
            Content.Pack p = packs.get(n);
            file = p.file();
            k = known.get(file);
            title = p.name();
            sub = p.fileName() + "  ·  " + sizeText(p.size()) + (p.packFormat() > 0 ? "  ·  pack format " + p.packFormat() : "");
            img = ModIcons.local(p.file(), "pack.png", mtime(p.file()), v::repaint);
            body = p.description();
        } else {
            int n = Integer.parseInt(sheet.substring(4));
            if (n >= hits.size()) { sheet = null; return; }
            Mods.Hit hh = hits.get(n);
            title = hh.title();
            sub = "by " + hh.author() + "  ·  " + Mods.count(hh.downloads()) + " downloads  ·  " + browseType.one();
            img = ModIcons.url(hh.iconUrl(), v::repaint);
            projectId = hh.projectId();
            body = hh.description();
            url = "https://modrinth.com/" + (browseType == Content.Type.DATAPACK ? "datapack" : browseType.api) + "/" + hh.slug();
        }
        if (k != null && k.current() != null) {
            projectId = k.current().projectId();
            ContentApi.Project pr = projects.get(projectId);
            if (pr != null) url = "https://modrinth.com/" + pr.type() + "/" + pr.slug();
        }
        v.modIcon(g, ix, y + 26, 52, title, img, true);
        g.setFont(v.interBold.deriveFont(19f));
        g.setColor(TEXT);
        g.drawString(ellipsizeEnd(g, title, iw - 120), ix + 68, y + 50);
        g.setFont(v.inter.deriveFont(11.5f));
        g.setColor(FAINT);
        g.drawString(ellipsizeEnd(g, sub, iw - 90), ix + 68, y + 70);
        int ty = y + 104;
        if (k != null && k.hasUpdate()) {
            g.setColor(new Color(251, 191, 36, 18));
            g.fill(new RoundRectangle2D.Double(ix, ty, iw, 44, 14, 14));
            icon(g, "download", ix + 20, ty + 22, 12, AMBER);
            g.setFont(v.interBold.deriveFont(12.5f));
            g.setColor(TEXT);
            g.drawString(ellipsizeEnd(g, "Update: " + (k.current() != null ? k.current().number() : "?") + " → " + k.latest().number(), iw - 200), ix + 38, ty + 27);
            v.smallPrimary(g, "c-sheetupd", ix + iw - 120 - 8, ty + 7, 120, 30, "Update", null);
            ty += 58;
        }
        // Text: the update's changelog first, then the project's description (fetched once).
        String text = body == null ? "" : body;
        if (projectId != null) {
            String b = bodies.get(projectId);
            if (b != null) text = b;
            else if (!bodyLoading.contains(projectId) && !offline) loadBody(projectId);
        }
        if (k != null && k.hasUpdate() && k.latest().changelog() != null && !k.latest().changelog().isBlank())
            text = "What's new in " + k.latest().number() + ":\n" + plain(k.latest().changelog()) + "\n\n" + text;
        g.setFont(v.inter.deriveFont(12.5f));
        g.setColor(DIM);
        int bottom = y + h - 84;
        for (String para : text.split("\n")) {
            if (ty + 18 > bottom) break;
            if (para.isBlank()) { ty += 8; continue; }
            boolean head = para.startsWith("What's new");
            g.setFont((head ? v.interBold : v.inter).deriveFont(12.5f));
            g.setColor(head ? TEXT : DIM);
            for (String l : LauncherView.wrapLines(g, para, iw, Math.max(1, (bottom - ty) / 18))) { g.drawString(l, ix, ty + 14); ty += 18; }
        }
        if (projectId != null && bodies.get(projectId) == null && bodyLoading.contains(projectId)) {
            spinner(g, ix + 8, ty + 18, 6);
        }
        int by = y + h - 64;
        int bx = ix + iw;
        if (url != null) { v.ghostButton(g, "c-openurl", bx - 170, by, 170, 40, "Open on Modrinth", false, "external"); bx -= 180; }
        if (file != null) v.ghostButton(g, "c-showfile", bx - 150, by, 150, 40, "Show file", false, "folder");
        if (file != null && k != null) versions.paintSheetButtons(g, k, ix, by);
        else if (sheet.startsWith("hit:")) {
            int n = Integer.parseInt(sheet.substring(4));
            Mods.Hit hh = hits.get(n);
            if (!installedFor(sel).contains(hh.projectId()) && !"installed".equals(hitState.get(hh.projectId())) && !"installing".equals(hitState.get(hh.projectId()))
                    && (browseType != Content.Type.MOD || InstallerCore.optionForSlug(hh.slug()) == null))
                v.smallPrimary(g, "c-inst:" + n, bx - 150, by, 150, 40, browseType == Content.Type.MODPACK ? "Import" : "Install", "download");
        }
        sheetUrl = url;
        sheetFile = file;
        sheetTitle = title;
    }

    private String sheetUrl, sheetTitle;
    private Path sheetFile;

    /** The file and name on the open mod or pack sheet (for its version list). */
    Path sheetFileShown() { return sheetFile; }

    String sheetTitleShown() { return sheetTitle; }

    /** After switching a version or undoing an update: the lists reload. */
    void versionsChanged(Launcher.Instance sel, Content.Type type) {
        packs = null;
        if (type == null || type == Content.Type.MOD) refreshMods(sel);
    }

    private void loadBody(String projectId) {
        bodyLoading.add(projectId);
        work.submit(() -> {
            String text;
            try {
                ContentApi.Project p = ContentApi.project(projectId);
                text = p == null ? "" : plain(p.body() != null && !p.body().isBlank() ? p.body() : p.description());
                if (p != null && p.license() != null) text += "\n\nLicence: " + p.license();
            } catch (IOException e) {
                text = "Couldn't load the description: " + EvadeInstaller.friendly(e);
            }
            String t = text;
            ui(() -> bodies.put(projectId, t));
        });
    }

    /** Markdown and HTML as plain readable text (pictures and markup dropped, links kept as their text). */
    static String plain(String md) {
        if (md == null) return "";
        String s = md.replace("\r", "");
        s = s.replaceAll("(?s)<!--.*?-->", "");
        s = s.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "");
        s = s.replaceAll("\\[!\\[[^\\]]*\\]\\([^)]*\\)\\]\\([^)]*\\)", "");
        s = s.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("<[^>]+>", "");
        s = s.replaceAll("(?m)^#{1,6}\\s*", "");
        s = s.replaceAll("(?m)^\\s*[-*+]\\s+", "• ");
        s = s.replaceAll("(?m)^\\s*>\\s?", "");
        s = s.replaceAll("\\*\\*|__|`", "");
        s = s.replaceAll("(?m)^[-=_*]{3,}\\s*$", "");
        s = s.replaceAll("(?m)^\\s*\\|.*$", ""); // tables don't read well as plain text
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&nbsp;", " ");
        s = s.replaceAll("\n{3,}", "\n\n");
        return s.strip();
    }

    private void paintProblems(Graphics2D g, int x, int y, int w, int h, int ix, int iw) {
        g.setFont(v.interBold.deriveFont(19f));
        g.setColor(TEXT);
        g.drawString("Mod check", ix, y + 50);
        g.setFont(v.inter.deriveFont(12f));
        g.setColor(DIM);
        g.drawString(problems.isEmpty() ? "Nothing to fix." : "Red ones stop the game from starting. Amber ones are worth a look.", ix, y + 72);
        int ty = y + 96;
        Shape was = g.getClip();
        Rectangle area = new Rectangle(x + 8, ty, w - 16, h - 96 - 76);
        g.clip(area);
        v.clip = area;
        float off = v.scrollOf("c-probs");
        int ry = ty - Math.round(off);
        for (int i = 0; i < problems.size(); i++) {
            ModChecks.Problem p = problems.get(i);
            g.setColor(new Color(255, 255, 255, 6));
            g.fill(new RoundRectangle2D.Double(ix, ry, iw, 66, 12, 12));
            icon(g, "alert", ix + 20, ry + 22, 13, p.warning() ? AMBER : RED_HI);
            g.setFont(v.interBold.deriveFont(12.5f));
            g.setColor(TEXT);
            g.drawString(ellipsizeEnd(g, p.title(), iw - 200), ix + 38, ry + 26);
            g.setFont(v.inter.deriveFont(11.5f));
            g.setColor(DIM);
            List<String> ls = LauncherView.wrapLines(g, p.detail(), iw - 200, 2);
            for (int j = 0; j < ls.size(); j++) g.drawString(ls.get(j), ix + 38, ry + 44 + j * 15);
            String act = switch (p.kind()) {
                case MISSING -> p.option() == null ? "Install" : null;
                case SWITCHED_OFF -> "Switch on";
                case BREAKS, DUPLICATE -> "Switch one off";
                case OVERLAP -> p.evadeModule() != null ? "Turn Evade's off" : p.mods().size() > 1 ? "Switch one off" : null;
            };
            if (act != null) v.ghostButton(g, "c-fix:" + i, ix + iw - 140 - 12, ry + 17, 140, 32, act, false);
            ry += 74;
        }
        g.setClip(was);
        v.clip = null;
        v.scrollArea(g, "c-probs", area, problems.size() * 74);
        v.ghostButton(g, "c-recheck", ix + iw - 150, y + h - 64, 150, 40, "Check again", false);
    }

    private void paintDatapacks(Graphics2D g, Launcher.Instance sel, int x, int y, int w, int h, int ix, int iw, String folder) {
        Worlds.World wo = null;
        if (worlds != null) for (Worlds.World x2 : worlds) if (x2.folder().equals(folder)) wo = x2;
        if (wo == null || sel == null) { sheet = null; return; }
        g.setFont(v.interBold.deriveFont(19f));
        g.setColor(TEXT);
        g.drawString(ellipsizeEnd(g, "Data packs in " + wo.name(), iw - 60), ix, y + 50);
        g.setFont(v.inter.deriveFont(12f));
        g.setColor(DIM);
        g.drawString("They load the next time the world opens (or after /reload).", ix, y + 72);
        List<Content.Pack> dps = dpCache.computeIfAbsent(folder, f -> Worlds.datapacks(sel.dir, worldOf(f)));
        int ry = y + 96;
        if (dps.isEmpty()) {
            g.setColor(FAINT);
            g.drawString("No data packs yet.", ix, ry + 20);
        }
        for (int i = 0; i < dps.size() && ry < y + h - 120; i++) {
            Content.Pack p = dps.get(i);
            g.setColor(new Color(255, 255, 255, 6));
            g.fill(new RoundRectangle2D.Double(ix, ry, iw, 48, 12, 12));
            v.modIcon(g, ix + 10, ry + 8, 32, p.name(), ModIcons.local(p.file(), "pack.png", mtime(p.file()), v::repaint), true);
            g.setFont(v.interBold.deriveFont(12.5f));
            g.setColor(TEXT);
            g.drawString(ellipsizeEnd(g, p.name(), iw - 140), ix + 54, ry + 22);
            g.setFont(v.inter.deriveFont(11f));
            g.setColor(FAINT);
            g.drawString(ellipsizeEnd(g, p.description() != null ? p.description() : p.fileName(), iw - 140), ix + 54, ry + 38);
            v.iconButton(g, "c-dprm:" + i, ix + iw - 44, ry + 8, 32, "trash", true);
            ry += 54;
        }
        v.smallPrimary(g, "c-dpadd", ix + iw - 200, y + h - 64, 200, 40, "Find data packs", "search");
        v.ghostButton(g, "c-dpfolder", ix + iw - 200 - 12 - 170, y + h - 64, 170, 40, "Open folder", false, "folder");
    }

    private final Map<String, List<Content.Pack>> dpCache = new HashMap<>();

    private Worlds.World worldOf(String folder) {
        if (worlds != null) for (Worlds.World w : worlds) if (w.folder().equals(folder)) return w;
        return null;
    }

    // ------------------------------------------------------------------ clicks

    /** Handles a click on this page's controls; false when it isn't one of them. */
    boolean click(String id) {
        if (v.page != LauncherView.Page.MODS) return false;
        Launcher.Instance sel = v.selected();
        if (id.startsWith("pop:c:")) {
            v.popup = null;
            popupClick(id.substring(6), sel);
            return true;
        }
        if (id.equals("c-dismiss") || id.equals("c-sheetx")) { sheet = null; return true; }
        if (id.startsWith("mod-toggle:") || id.equals("tab-installed") || id.equals("tab-browse")) {
            if (id.equals("tab-browse")) { tab = Tab.BROWSE; return true; }
            if (id.equals("tab-installed")) { tab = Tab.INSTALLED; return true; }
            return false; // the view toggles the mod (same index into v.mods)
        }
        if (!id.startsWith("c-")) return false;
        String arg = id.indexOf(':') > 0 ? id.substring(id.indexOf(':') + 1) : "";
        String cmd = id.indexOf(':') > 0 ? id.substring(0, id.indexOf(':')) : id;
        if (versions.click(cmd, arg, sel)) return true;
        if (addons.click(cmd, arg, sel)) return true;
        switch (cmd) {
            case "c-tab" -> {
                tab = Tab.valueOf(arg);
                if (tab == Tab.INSTALLED) refreshMods(sel);
                if (tab == Tab.WORLDS && sel != null && !offline) loadWorlds(sel);
            }
            case "c-itype" -> { installedType = Content.Type.valueOf(arg); packs = null; filterField.setText(""); }
            case "c-browse-type" -> { tab = Tab.BROWSE; setBrowseType(installedType); }
            case "c-sort" -> popupAt(id, new String[][]{{"pop:c:sort:name", "Name", "name"}, {"pop:c:sort:date", "Newest first", "date"},
                    {"pop:c:sort:size", "Largest first", "size"}, {"pop:c:sort:updates", "Updates first", "updates"}}, sort, 200);
            case "c-check" -> checkUpdates(sel, true);
            case "c-updall" -> updateAll(sel);
            case "c-upd" -> { int n = Integer.parseInt(arg); if (n < v.mods.size()) update(sel, v.mods.get(n).file()); }
            case "c-pupd" -> { int n = Integer.parseInt(arg); if (packs != null && n < packs.size()) update(sel, packs.get(n).file()); }
            case "c-row" -> sheet = "mod:" + arg;
            case "c-prow" -> sheet = "pack:" + arg;
            case "c-sel" -> {
                int n = Integer.parseInt(arg);
                if (n < v.mods.size()) { Path f = v.mods.get(n).file(); if (!selected.remove(f)) selected.add(f); }
            }
            case "c-selall" -> {
                if (!selected.isEmpty()) selected.clear();
                else for (Mods.Entry e : shownMods()) if (!e.managed()) selected.add(e.file());
            }
            case "c-clear" -> selected.clear();
            case "c-on", "c-off" -> setEnabled(sel, selectedEntries(), cmd.equals("c-on"), null);
            case "c-del" -> {
                List<Mods.Entry> l = selectedEntries();
                if (l.isEmpty()) break;
                pending = l;
                v.openModal(new LauncherView.Modal("c:del-mods", "Remove " + l.size() + (l.size() == 1 ? " mod?" : " mods?"),
                        "They go to the " + (InstallerCore.WINDOWS ? "Recycle Bin" : "Trash") + ", so you can get them back from there.", "Remove", null, null), null);
            }
            case "c-rm" -> {
                int n = Integer.parseInt(arg);
                if (n >= v.mods.size()) break;
                pending = List.of(v.mods.get(n));
                Mods.Entry e = v.mods.get(n);
                v.openModal(new LauncherView.Modal("c:del-mods", "Remove " + e.name() + "?", e.fileName() + " goes to the "
                        + (InstallerCore.WINDOWS ? "Recycle Bin" : "Trash") + ".", "Remove", null, null), null);
            }
            case "c-prm" -> {
                int n = Integer.parseInt(arg);
                if (packs == null || n >= packs.size()) break;
                Content.Pack p = packs.get(n);
                pending = p;
                v.openModal(new LauncherView.Modal("c:del-pack", "Remove " + p.name() + "?", p.fileName() + " goes to the "
                        + (InstallerCore.WINDOWS ? "Recycle Bin" : "Trash") + ".", "Remove", null, null), null);
            }
            case "c-pfolder" -> { if (sel != null) v.actions.openFolder(sel.dir.resolve(installedType.folder)); }
            case "c-trouble" -> {
                List<Mods.Entry> l = new ArrayList<>();
                for (Mods.Entry e : v.mods) if (!e.managed() && e.enabled()) l.add(e);
                if (l.isEmpty()) { v.toast("All your own mods are already switched off"); break; }
                pending = l;
                v.openModal(new LauncherView.Modal("c:trouble", "Switch off " + l.size() + " of your mods?",
                        "Only Evade and its extras stay on. If the game then works, one of your mods was the problem. Restore turns them all back on.",
                        "Switch off", null, null), null);
            }
            case "c-restore" -> restoreTroubleshoot(sel);
            case "c-problems" -> sheet = "problems";
            case "c-fixall" -> fixAll(sel);
            case "c-fix" -> { int n = Integer.parseInt(arg); if (n < problems.size()) fix(sel, problems.get(n)); }
            case "c-recheck" -> { analysedMods = null; refreshMods(sel); }
            case "c-openurl" -> { if (sheetUrl != null) v.actions.openUrl(sheetUrl); }
            case "c-showfile" -> { if (sheetFile != null) v.actions.openFolder(sheetFile.getParent()); }
            case "c-sheetupd" -> { if (sheetFile != null) { update(sel, sheetFile); sheet = null; } }
            // browse
            case "c-btype" -> setBrowseType(Content.Type.valueOf(arg));
            case "c-bsort" -> {
                List<String[]> items = new ArrayList<>();
                for (Content.Sort s : Content.Sort.values()) items.add(new String[]{"pop:c:bsort:" + s.name(), s.label, s.name()});
                popupAt(id, items.toArray(new String[0][]), browseSort.name(), 220);
            }
            case "c-cat" -> { category = arg.isEmpty() ? null : arg; search(false); }
            case "c-client" -> { clientOnly = !clientOnly; search(false); }
            case "c-more" -> { if (!searching) search(true); }
            case "c-retry" -> search(false);
            case "c-hitd" -> sheet = "hit:" + arg;
            case "c-inst" -> {
                int n = Integer.parseInt(arg);
                if (n >= hits.size()) break;
                Mods.Hit h = hits.get(n);
                String st = hitState.get(h.projectId());
                if ("installing".equals(st) || "installed".equals(st)) break;
                if (browseType == Content.Type.DATAPACK && datapackWorld == null) { v.toast("Pick a world for the data pack first"); break; }
                sheet = null;
                install(h);
            }
            case "c-dpworld" -> {
                List<String[]> items = new ArrayList<>();
                if (worlds != null) for (Worlds.World w : worlds) items.add(new String[]{"pop:c:dpworld:" + w.folder(), w.name(), w.folder()});
                if (items.isEmpty()) { v.toast("Make a world in game first: data packs go into a world"); break; }
                popupAt(id, items.toArray(new String[0][]), datapackWorld, 260);
            }
            case "c-iris" -> {
                if (sel != null) { sel.shaders = true; sel.dirty = true; v.actions.instanceChanged(sel); v.toast("Shaders are on: Play installs Iris"); }
            }
            // worlds
            case "c-wsaves" -> { if (sel != null) v.actions.openFolder(Worlds.savesDir(sel.dir)); }
            case "c-wbackups" -> { if (sel != null) v.actions.openFolder(Worlds.backupsDir(sel.dir)); }
            case "c-wreload" -> { if (sel != null) { worlds = null; dpCache.clear(); loadWorlds(sel); } }
            case "c-wbackup" -> { Worlds.World w = worldAt(arg); if (w != null) backup(sel, w); }
            case "c-wrestore" -> {
                Worlds.World w = worldAt(arg);
                if (w == null) break;
                List<String[]> items = new ArrayList<>();
                List<Worlds.Backup> bs = backups.getOrDefault(w.folder(), List.of());
                DateTimeFormatter f = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);
                for (int i = 0; i < bs.size(); i++) items.add(new String[]{"pop:c:restore:" + arg + ":" + i,
                        f.format(Instant.ofEpochMilli(bs.get(i).time()).atZone(ZoneId.systemDefault())) + "  ·  " + sizeText(bs.get(i).size()), ""});
                popupAt(id, items.toArray(new String[0][]), null, 300);
            }
            case "c-wmenu" -> {
                Worlds.World w = worldAt(arg);
                if (w == null) break;
                popupAt(id, new String[][]{{"pop:c:w:dp:" + arg, "Data packs…", ""}, {"pop:c:w:dup:" + arg, "Duplicate", ""},
                        {"pop:c:w:ren:" + arg, "Rename…", ""}, {"pop:c:w:seed:" + arg, w.seed() != null ? "Copy seed" : "Copy seed (not readable)", ""},
                        {"pop:c:w:open:" + arg, "Open folder", ""}, {"pop:c:w:del:" + arg, "Delete…", ""}}, null, 220);
            }
            case "c-dprm" -> {
                String folder = sheet != null && sheet.startsWith("datapacks:") ? sheet.substring(10) : null;
                List<Content.Pack> dps = folder == null ? null : dpCache.get(folder);
                int n = Integer.parseInt(arg);
                if (dps == null || n >= dps.size() || sel == null) break;
                Content.Pack p = dps.get(n);
                work.submit(() -> {
                    try {
                        Content.removePack(sel.dir, p);
                        ui(() -> { dpCache.remove(folder); v.toast("Removed " + p.name()); });
                    } catch (Exception e) { fail("Couldn't remove " + p.name(), e); }
                });
            }
            case "c-dpadd" -> {
                String folder = sheet != null && sheet.startsWith("datapacks:") ? sheet.substring(10) : null;
                sheet = null;
                datapackWorld = folder;
                tab = Tab.BROWSE;
                setBrowseType(Content.Type.DATAPACK);
            }
            case "c-dpfolder" -> {
                String folder = sheet != null && sheet.startsWith("datapacks:") ? sheet.substring(10) : null;
                if (sel != null && folder != null) v.actions.openFolder(Worlds.savesDir(sel.dir).resolve(folder).resolve("datapacks"));
            }
            // modpacks & sharing
            case "c-impfile" -> importFile();
            case "c-impbrowse" -> { tab = Tab.BROWSE; setBrowseType(Content.Type.MODPACK); }
            case "c-impcode" -> {
                v.openModal(new LauncherView.Modal("c:text-code", "Paste a share code", "A share code makes a new instance with the same Modrinth mods, packs and options. They start with "
                        + Mrpack.CODE_PREFIX + ".", "Make instance", null, null), "");
            }
            case "c-exo" -> {
                switch (arg) {
                    case "config" -> exConfig = !exConfig;
                    case "options" -> exOptions = !exOptions;
                    case "packs" -> exPacks = !exPacks;
                    case "servers" -> exServers = !exServers;
                    default -> { }
                }
            }
            case "c-export" -> exportFile(sel);
            case "c-sharecode" -> shareCode(sel);
            case "c-resx" -> { resultTitle = null; resultNotes = List.of(); }
            default -> { return false; }
        }
        return true;
    }

    private void popupAt(String id, String[][] items, String current, int width) {
        Rectangle r = v.hitMap.get(id);
        if (r == null) return;
        List<String[]> l = new ArrayList<>();
        for (String[] it : items) l.add(new String[]{it[0], it[1], current != null && current.equals(it[2]) ? "✓" : ""});
        int ph = l.size() * 38 + 12;
        int px = Math.min(W - 28 - width, r.x);
        int py = r.y + r.height + 6;
        if (py + ph > H - 12) py = Math.max(12, r.y - 6 - ph);
        v.popup = new LauncherView.Popup(id, px, py, width, l);
    }

    private void popupClick(String what, Launcher.Instance sel) {
        String[] p = what.split(":", 3);
        switch (p[0]) {
            case "sort" -> sort = p[1];
            case "bsort" -> { browseSort = Content.Sort.valueOf(p[1]); search(false); }
            case "dpworld" -> { datapackWorld = what.substring(8); search(false); }
            case "restore" -> {
                String[] all = what.substring(8).split(":");
                Worlds.World w = worldAt(all[0]);
                int i = Integer.parseInt(all[1]);
                List<Worlds.Backup> bs = w == null ? List.of() : backups.getOrDefault(w.folder(), List.of());
                if (w == null || i >= bs.size()) return;
                pending = bs.get(i);
                v.openModal(new LauncherView.Modal("c:restore", "Restore " + w.name() + "?", "The world goes back to that backup. "
                        + "Its current state is backed up first, so you can undo this.", "Restore", null, null), null);
            }
            case "w" -> {
                String[] q = what.substring(2).split(":");
                Worlds.World w = worldAt(q[1]);
                if (w == null || sel == null) return;
                switch (q[0]) {
                    case "dp" -> sheet = "datapacks:" + w.folder();
                    case "dup" -> worldTask(sel, w, "Copying " + w.name(), () -> { Worlds.World c = Worlds.duplicate(sel.dir, w); return "Made " + c.name(); });
                    case "ren" -> { pending = w; v.openModal(new LauncherView.Modal("c:text-world", "Rename world", "The name shown in the game's world list.", "Rename", null, null), w.name()); }
                    case "seed" -> { if (w.seed() != null) { v.actions.copyText(w.seed()); } else v.toast("This world's seed couldn't be read"); }
                    case "open" -> v.actions.openFolder(w.dir());
                    case "del" -> {
                        pending = w;
                        v.openModal(new LauncherView.Modal("c:del-world", "Delete " + w.name() + "?", "The world (" + sizeText(w.size()) + ") goes to the "
                                + (InstallerCore.WINDOWS ? "Recycle Bin" : "Trash") + ". Its backups stay in the backups folder.", "Delete", null, null), null);
                    }
                    default -> { }
                }
            }
            default -> { }
        }
    }

    private Worlds.World worldAt(String arg) {
        try {
            int n = Integer.parseInt(arg);
            return worlds != null && n < worlds.size() ? worlds.get(n) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void setBrowseType(Content.Type t) {
        if (t == browseType && searched) return;
        browseType = t;
        category = null;
        hits = List.of();
        hitsTotal = 0;
        hitState.clear();
        if (t == Content.Type.DATAPACK && worlds == null && v.selected() != null && !offline) loadWorlds(v.selected());
        search(false);
    }

    // ------------------------------------------------------------------ dialogs (LauncherView's confirm dialog)

    /** The label over the text box of this page's text dialogs. */
    String fieldLabel(LauncherView.Modal m) { return m.kind().equals("c:text-code") ? "SHARE CODE" : "NAME"; }

    String fieldHint(LauncherView.Modal m) { return m.kind().equals("c:text-code") ? Mrpack.CODE_PREFIX + "…" : "World name"; }

    /** The player confirmed one of this page's dialogs. */
    @SuppressWarnings("unchecked")
    void confirm(LauncherView.Modal m, String text) {
        Launcher.Instance sel = v.selected();
        Object what = pending;
        pending = null;
        if (sel == null) return;
        switch (m.kind()) {
            case "c:del-mods" -> removeMods(sel, (List<Mods.Entry>) what);
            case "c:del-pack" -> {
                Content.Pack p = (Content.Pack) what;
                work.submit(() -> {
                    try {
                        Content.removePack(sel.dir, p);
                        ui(() -> { packs = null; v.toast("Removed " + p.name()); });
                    } catch (Exception e) { fail("Couldn't remove " + p.name(), e); }
                });
            }
            case "c:trouble" -> setEnabled(sel, (List<Mods.Entry>) what, false, "troubleshoot");
            case "c:restore" -> {
                Worlds.Backup b = (Worlds.Backup) what;
                Worlds.World w = worldOf(b.world());
                worldTask(sel, w, "Restoring", () -> { Worlds.restore(sel.dir, b, null); return "Restored the backup"; });
            }
            case "c:del-world" -> {
                Worlds.World w = (Worlds.World) what;
                worldTask(sel, w, "Deleting", () -> { Worlds.delete(sel.dir, w); return "Deleted " + w.name(); });
            }
            case "c:text-world" -> {
                Worlds.World w = (Worlds.World) what;
                if (text.isBlank()) return;
                worldTask(sel, w, "Renaming", () -> { Worlds.rename(w, text); return "Renamed to " + text.strip(); });
            }
            case "c:text-code" -> fromCode(text);
            default -> { }
        }
    }

    // ------------------------------------------------------------------ actions

    private List<Mods.Entry> selectedEntries() {
        List<Mods.Entry> out = new ArrayList<>();
        for (Mods.Entry e : v.mods) if (selected.contains(e.file()) && !e.managed()) out.add(e);
        return out;
    }

    /** Switches mods on or off; {@code troubleshoot}: remember them so Restore can bring them back. */
    private void setEnabled(Launcher.Instance sel, List<Mods.Entry> list, boolean on, String troubleshootTag) {
        if (sel == null || list.isEmpty()) return;
        selected.clear();
        work.submit(() -> {
            List<String> changed = new ArrayList<>();
            int failed = 0;
            for (Mods.Entry e : list) {
                if (e.managed() || e.enabled() == on) continue;
                try {
                    Mods.Entry n = Mods.setEnabled(e, on);
                    changed.add(n.fileName());
                } catch (IOException ex) {
                    failed++;
                }
            }
            if (troubleshootTag != null) {
                List<String> names = new ArrayList<>(troubleshoot);
                names.addAll(changed);
                writeTroubleshoot(sel.dir, names);
                ui(() -> troubleshoot = names);
            }
            int f = failed, c = changed.size();
            ui(() -> v.toast(c + (c == 1 ? " mod" : " mods") + " switched " + (on ? "on" : "off") + (f > 0 ? " (" + f + " couldn't be: is the game running?)" : "")));
            refreshMods(sel);
        });
    }

    private void restoreTroubleshoot(Launcher.Instance sel) {
        if (sel == null) return;
        List<String> names = troubleshoot;
        work.submit(() -> {
            int n = 0;
            for (String f : names) {
                Path p = sel.dir.resolve("mods").resolve(f);
                if (!InstallerCore.plainName(f) || !Files.isRegularFile(p) || !f.toLowerCase(Locale.ROOT).endsWith(".disabled")) continue;
                try {
                    Mods.setEnabled(Mods.read(p, false, null), true);
                    n++;
                } catch (IOException ignored) { }
            }
            writeTroubleshoot(sel.dir, List.of());
            int c = n;
            ui(() -> { troubleshoot = List.of(); v.toast("Turned " + c + (c == 1 ? " mod" : " mods") + " back on"); });
            refreshMods(sel);
        });
    }

    private static Path troubleshootFile(Path dir) { return dir.resolve(".evade").resolve("troubleshoot.json"); }

    private static List<String> readTroubleshoot(Path dir) {
        List<String> out = new ArrayList<>();
        var o = InstallerCore.readJsonObject(troubleshootFile(dir));
        if (o.has("off") && o.get("off").isJsonArray()) for (var e : o.getAsJsonArray("off")) if (e.isJsonPrimitive()) out.add(e.getAsString());
        return out;
    }

    private static void writeTroubleshoot(Path dir, List<String> names) {
        try {
            if (names.isEmpty()) { Files.deleteIfExists(troubleshootFile(dir)); return; }
            Files.createDirectories(troubleshootFile(dir).getParent());
            var o = new com.google.gson.JsonObject();
            o.add("off", ContentApi.array(names));
            InstallerCore.writeAtomic(troubleshootFile(dir), InstallerCore.GSON.toJson(o));
        } catch (IOException ignored) { }
    }

    private void removeMods(Launcher.Instance sel, List<Mods.Entry> list) {
        selected.clear();
        work.submit(() -> {
            int n = 0;
            for (Mods.Entry e : list) {
                if (e.managed()) continue;
                try {
                    // To the bin (the record forgets it either way).
                    if (Worlds.trash(e.file(), sel.dir)) { Files.deleteIfExists(e.file()); Mods.remove(sel.dir, e); n++; }
                } catch (IOException ignored) { }
            }
            int c = n;
            ui(() -> v.toast("Removed " + c + (c == 1 ? " mod" : " mods")));
            refreshMods(sel);
        });
    }

    private void update(Launcher.Instance sel, Path file) {
        Updates.Known k = known.get(file);
        if (sel == null || k == null || !k.hasUpdate() || updating.containsKey(file)) return;
        updating.put(file, 0.0);
        work.submit(() -> {
            try {
                Path np = Updates.apply(sel.dir, k, (f, m) -> SwingUtilities.invokeLater(() -> { if (updating.containsKey(file)) updating.put(file, f); }),
                        ContentPins.begin(sel.dir));
                ui(() -> {
                    versions.reload();
                    updating.remove(file);
                    known.remove(file);
                    known.put(np, new Updates.Known(np, k.type(), k.latest().sha1(), k.latest(), null));
                    packs = null;
                    v.toast("Updated " + np.getFileName());
                });
                if (k.type() == Content.Type.MOD) refreshMods(sel);
            } catch (Exception e) {
                ui(() -> updating.remove(file));
                fail("Couldn't update " + file.getFileName(), e);
            }
        });
    }

    private void updateAll(Launcher.Instance sel) {
        if (sel == null || bulk != null) return;
        // Pinned projects stay as they are; the files replaced are kept for "Undo update".
        List<Updates.Known> todo = ContentPins.unpinnedUpdates(sel.dir, known.values());
        if (todo.isEmpty()) return;
        bulk = "Updating 0 of " + todo.size();
        bulkProgress = 0;
        work.submit(() -> {
            int done = 0, failed = 0;
            Map<Path, Updates.Known> now = new HashMap<>();
            ContentPins.Batch batch;
            try {
                batch = ContentPins.begin(sel.dir);
            } catch (IOException e) {
                batch = null;
            }
            for (Updates.Known k : todo) {
                int i = done + failed;
                ui(() -> { bulk = "Updating " + (i + 1) + " of " + todo.size(); });
                try {
                    Path np = Updates.apply(sel.dir, k, (f, m) -> SwingUtilities.invokeLater(() -> bulkProgress = (i + f) / todo.size()), batch);
                    now.put(k.file(), new Updates.Known(np, k.type(), k.latest().sha1(), k.latest(), null));
                    done++;
                } catch (Exception e) {
                    failed++;
                }
            }
            int d = done, f = failed;
            ui(() -> {
                bulk = null;
                versions.reload();
                for (var e : now.entrySet()) { known.remove(e.getKey()); known.put(e.getValue().file(), e.getValue()); }
                packs = null;
                v.toast("Updated " + d + (d == 1 ? " file" : " files") + (f > 0 ? ", " + f + " couldn't be updated" : ""));
                v.notice("Updated " + d + " in " + sel.name, f > 0 ? f + " couldn't be updated: is the game running?" : "Mods and packs you added are up to date.", f > 0);
            });
            refreshMods(sel);
        });
    }

    private void fixAll(Launcher.Instance sel) {
        for (ModChecks.Problem p : List.copyOf(problems)) {
            if (p.kind() == ModChecks.Kind.SWITCHED_OFF || p.kind() == ModChecks.Kind.MISSING && p.option() == null) fix(sel, p);
        }
    }

    private void fix(Launcher.Instance sel, ModChecks.Problem p) {
        if (sel == null) return;
        switch (p.kind()) {
            case SWITCHED_OFF -> {
                List<Mods.Entry> l = new ArrayList<>();
                for (Mods.Entry e : v.mods) if (p.mods().contains(e.file())) l.add(e);
                setEnabled(sel, l, true, null);
            }
            case MISSING -> {
                if (p.option() != null) { v.toast("Switch on " + p.option() + " for " + sel.name + " in Instances"); return; }
                v.toast("Finding " + ModChecks.pretty(p.needId()) + " on Modrinth…");
                work.submit(() -> {
                    try {
                        ContentApi.Project pr = p.projectId() != null ? ContentApi.project(p.projectId()) : ModChecks.findProject(p.needId());
                        if (pr == null) {
                            ui(() -> v.toast(ModChecks.pretty(p.needId()) + " isn't on Modrinth for Fabric " + InstallerCore.MC_VERSION + ": get it where the mod that needs it says"));
                            return;
                        }
                        List<String> names = Mods.install(core, sel.dir, new Mods.Hit(pr.id(), pr.slug(), pr.title(), "", "", 0, 0), (f, m) -> { });
                        ui(() -> v.toast("Installed " + String.join(", ", names)));
                    } catch (Exception e) {
                        fail("Couldn't install " + ModChecks.pretty(p.needId()), e);
                    }
                    refreshMods(sel);
                });
            }
            case OVERLAP -> {
                if (p.evadeModule() == null) { if (p.mods().size() > 1) switchOneOff(sel, p); return; }
                try {
                    Addons.requestOff(sel.dir, p.evadeModule());
                    v.toast("Evade's " + p.evadeModule() + " goes off the next time " + sel.name + " starts (switch it back on in the Evade menu)");
                } catch (IOException e) {
                    fail("Couldn't switch Evade's " + p.evadeModule() + " off", e);
                }
            }
            default -> switchOneOff(sel, p);
        }
        sheet = null;
    }

    /** Switches the player's own mod(s) of a problem off, keeping one: never an Evade-managed jar. */
    private void switchOneOff(Launcher.Instance sel, ModChecks.Problem p) {
        List<Mods.Entry> l = new ArrayList<>();
        for (Mods.Entry e : v.mods) if (p.mods().contains(e.file()) && !e.managed()) l.add(e);
        if (l.size() == p.mods().size() && !l.isEmpty()) l.remove(0); // all are the player's: keep the first
        if (l.isEmpty()) { v.toast("Those are Evade's own mods: switch the other one off"); return; }
        setEnabled(sel, l, false, null);
        sheet = null;
    }

    /** Runs a world action with a progress chip on its row, then reloads the list. */
    private void worldTask(Launcher.Instance sel, Worlds.World w, String label, java.util.concurrent.Callable<String> task) {
        String key = w != null ? w.folder() : "?";
        worldBusy.put(key, 0.3);
        work.submit(() -> {
            try {
                String msg = task.call();
                ui(() -> v.toast(msg));
            } catch (Exception e) {
                fail(label + " failed", e);
            }
            ui(() -> { worldBusy.remove(key); worlds = null; dpCache.clear(); });
            loadWorlds(sel);
        });
    }

    private void backup(Launcher.Instance sel, Worlds.World w) {
        if (sel == null || worldBusy.containsKey(w.folder())) return;
        worldBusy.put(w.folder(), 0.0);
        work.submit(() -> {
            try {
                if (Worlds.locked(w.dir())) throw new IOException(w.name() + " is open in the game. Leave the world first.");
                Worlds.Backup b = Worlds.backup(sel.dir, w, f -> SwingUtilities.invokeLater(() -> worldBusy.put(w.folder(), f)));
                ui(() -> v.toast("Backed up " + w.name() + " (" + sizeText(b.size()) + ")"));
            } catch (Exception e) {
                fail("Couldn't back up " + w.name(), e);
            }
            List<Worlds.Backup> bs = Worlds.backups(sel.dir, w.folder());
            ui(() -> { worldBusy.remove(w.folder()); backups.put(w.folder(), bs); });
        });
    }

    private java.awt.Window window() { return SwingUtilities.getWindowAncestor(v); }

    private void importFile() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Choose a modpack (.mrpack)");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Modrinth modpacks (*.mrpack)", "mrpack", "zip"));
        if (fc.showOpenDialog(window()) != JFileChooser.APPROVE_OPTION) return;
        importPackFile(fc.getSelectedFile().toPath());
    }

    /** Imports a .mrpack into a new instance, with progress on the Modpacks tab (also for a file dropped on the window). */
    void importPackFile(Path f) {
        tab = Tab.PACKS;
        runPackTask("Importing " + f.getFileName(), () -> {
            Mrpack.Info info = Mrpack.inspect(f);
            if (!info.ok()) throw new IOException(info.problem());
            Mrpack.Result r = Mrpack.importPack(v.settings, List.copyOf(v.instances), f, v.settings.defaultMemoryGb, this::packProgress);
            afterImport(r, info.name());
        });
    }

    private void fromCode(String text) {
        Mrpack.Share share;
        try {
            share = Mrpack.readCode(text);
        } catch (IOException e) {
            v.toast(e.getMessage());
            return;
        }
        runPackTask("Making “" + share.name() + "” from a share code", () -> {
            Mrpack.Result r = Mrpack.fromCode(core, v.settings, List.copyOf(v.instances), share, v.settings.defaultMemoryGb, this::packProgress);
            afterImport(r, "a share code");
        });
    }

    private void exportFile(Launcher.Instance sel) {
        if (sel == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Export " + sel.name + " as a modpack");
        fc.setSelectedFile(new java.io.File(sel.name.replaceAll("[\\\\/:*?\"<>|]", "_") + ".mrpack"));
        if (fc.showSaveDialog(window()) != JFileChooser.APPROVE_OPTION) return;
        Path out = fc.getSelectedFile().toPath();
        if (!out.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mrpack")) out = out.resolveSibling(out.getFileName() + ".mrpack");
        Path target = out;
        Mrpack.ExportOptions o = new Mrpack.ExportOptions(exConfig, exOptions, exPacks, exPacks, exServers);
        runPackTask("Exporting " + sel.name, () -> {
            Mrpack.Result r = Mrpack.export(sel, target, o, this::packProgress);
            ui(() -> {
                resultTitle = "Exported " + target.getFileName() + " (" + r.count() + " files)";
                resultNotes = r.notes();
                resultError = false;
            });
        });
    }

    private void shareCode(Launcher.Instance sel) {
        if (sel == null) return;
        if (checking) { v.toast("Wait a moment: still looking your mods up on Modrinth"); return; }
        String code = Mrpack.shareCode(sel, known);
        v.actions.copyText(code);
        long n = known.values().stream().filter(k -> k.current() != null).count();
        resultTitle = "Share code copied (" + n + " Modrinth projects)";
        resultNotes = new ArrayList<>(List.of(code, "Your friend pastes it under Mods › Modpacks & sharing › Paste a share code."));
        long manual = v.mods.stream().filter(e -> !e.managed() && !known.containsKey(e.file())).count();
        if (manual > 0) resultNotes.add(manual + (manual == 1 ? " mod isn't" : " mods aren't") + " on Modrinth, so " + (manual == 1 ? "it's" : "they're") + " not in the code");
        resultError = false;
    }

    private void packProgress(double f, String m) {
        SwingUtilities.invokeLater(() -> {
            packProgress = f;
            if (m != null && packBusy != null) packBusy = m;
        });
    }

    private void runPackTask(String label, ThrowingRunnable r) {
        if (packBusy != null) { v.toast("Wait until the current modpack job finishes"); return; }
        packBusy = label;
        packProgress = 0;
        resultTitle = null;
        tab = Tab.PACKS;
        work.submit(() -> {
            try {
                r.run();
            } catch (Exception e) {
                String msg = EvadeInstaller.friendly(e);
                ui(() -> { resultTitle = msg; resultNotes = List.of(); resultError = true; });
            }
            ui(() -> packBusy = null);
        });
    }

    interface ThrowingRunnable { void run() throws Exception; }

    /** Every instance's screenshots/ folder, for the Clips library's Screenshots view (newest instance list each time). */
    static List<net.evadeclient.clips.ui.ShotsGrid.Source> screenshotSources(LauncherView v) {
        List<net.evadeclient.clips.ui.ShotsGrid.Source> out = new ArrayList<>();
        List<Launcher.Instance> list = v.instances;
        for (Launcher.Instance i : list) out.add(new net.evadeclient.clips.ui.ShotsGrid.Source(i.name, i.dir.resolve("screenshots")));
        return out;
    }
}
