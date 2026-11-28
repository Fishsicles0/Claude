package net.evadeclient.installer;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static net.evadeclient.installer.LauncherView.AMBER;
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
import static net.evadeclient.installer.LauncherView.ellipsize;
import static net.evadeclient.installer.LauncherView.ellipsizeEnd;
import static net.evadeclient.installer.LauncherView.mix;

/**
 * The v2.5 launcher tools on top of {@link LauncherView}: import from another launcher (the {@code "import"} sheet),
 * drag-and-drop install, instance templates (in the New instance dialog), the optional extras (the {@code "extras"}
 * sheet and a row on the instance page), the instance's More menu (Start in safe mode, Desktop shortcut), memory
 * advice for PCs whose graphics share the RAM, and the desktop shortcuts' play requests. Click ids: {@code inst-more},
 * {@code inst-extras}, {@code tpl:*}, {@code tl-safe:*}, and {@code ov-x*} / {@code ov-imp*} on the sheets.
 */
final class LauncherTools {
    final LauncherView v;
    // ---- wired by LauncherApp (null in screenshots and tests: nothing runs in the background then)
    Launcher.Settings settings;
    private ExecutorService work;
    private Runnable reload;
    private Consumer<String> quickPlay;
    private final InstallerCore core = new InstallerCore();

    /** This PC's memory advice (null until the graphics were read). */
    MemoryAdvice.Advice advice;
    /** The template picked in the New instance dialog. */
    InstanceTemplates.Template template = InstanceTemplates.BLANK;

    // ---- import sheet
    List<LauncherImport.Found> found;
    boolean scanning, importWorlds = true;
    String importBusy, importTitle;
    double importProgress;
    List<String> importNotes = List.of();

    // ---- drag and drop
    boolean dragging;
    String dropBusy;

    /** The Shader packs chooser (the {@code "shaders"} sheet and the Packs button on the Shaders row). */
    final ShaderPackSheet shaderPacks;

    LauncherTools(LauncherView v) {
        this.v = v;
        this.shaderPacks = new ShaderPackSheet(v, this);
    }

    void attach(Launcher.Settings s, ExecutorService work, Runnable reload, Consumer<String> quickPlay) {
        this.settings = s;
        this.work = work;
        this.reload = reload;
        this.quickPlay = quickPlay;
    }

    private ExecutorService work() {
        if (work == null) work = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "launcher-tools"); t.setDaemon(true); return t; });
        return work;
    }

    /** Background start: memory advice, desktop shortcuts' play requests, drag and drop. Call once on the UI thread. */
    void start() {
        work().submit(() -> {
            MemoryAdvice.Advice a = MemoryAdvice.forPc(MemoryAdvice.detect());
            ui(() -> advice = a);
        });
        javax.swing.Timer poll = new javax.swing.Timer(1500, e -> {
            if (v.play == LauncherView.Play.UPDATING || v.play == LauncherView.Play.LAUNCHING) return;
            String id = InstanceShortcut.take();
            if (id != null && quickPlay != null) quickPlay.accept(id);
        });
        poll.setInitialDelay(300);
        poll.start();
        try {
            new DropTarget(v, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
                @Override public void dragEnter(DropTargetDragEvent e) {
                    if (!e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) { e.rejectDrag(); return; }
                    e.acceptDrag(DnDConstants.ACTION_COPY);
                    dragging = true;
                    v.repaint();
                }
                @Override public void dragExit(DropTargetEvent e) { dragging = false; v.repaint(); }
                @Override public void drop(DropTargetDropEvent e) {
                    dragging = false;
                    try {
                        e.acceptDrop(DnDConstants.ACTION_COPY);
                        @SuppressWarnings("unchecked")
                        List<File> files = (List<File>) e.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                        List<Path> paths = new ArrayList<>();
                        for (File f : files) paths.add(f.toPath());
                        e.dropComplete(true);
                        dropped(paths);
                    } catch (Exception ex) {
                        e.dropComplete(false);
                        v.toast("Couldn't read what was dropped");
                    }
                    v.repaint();
                }
            }, true);
        } catch (RuntimeException ignored) { /* headless */ }
    }

    // ------------------------------------------------------------------ instance page

    /** The sub-line under an instance's Memory row: advice when it helps, else the usual hint. Amber when too much. */
    String memoryLine(int gb, String usual) {
        if (advice == null) return usual;
        if (advice.overHalf(gb) || advice.shared()) return advice.line(gb);
        return usual;
    }

    boolean memoryWarn(int gb) { return advice != null && advice.overHalf(gb); }

    /** The "Optional extras" row: what's on, and Choose. */
    void paintExtrasRow(Graphics2D g, Launcher.Instance i, int ix, int ry, int oh, int iw) {
        String on = OptionalMods.names(i.optional);
        v.rowLabel(g, ix, ry, oh, "Optional extras", on.equals("none") ? "Faster start-up, more FPS, Open to Friends: all off" : on, true, iw, iw - 140);
        v.ghostButton(g, "inst-extras", ix + iw - 110, ry + oh / 2 - 17, 110, 34, "Choose", false);
        v.game.name("inst-extras", "Optional extras: " + on);
    }

    /** The instance's More menu: Start in safe mode, Desktop shortcut, Optional extras. */
    private void moreMenu(String id) {
        Launcher.Instance foc = v.focused();
        if (foc == null) return;
        List<String[]> items = new ArrayList<>();
        items.add(new String[]{"pop:tl:safe", "Start in safe mode (Evade's modules off)", ""});
        items.add(new String[]{"pop:tl:shortcut", "Make a desktop shortcut", ""});
        items.add(new String[]{"pop:tl:extras", "Optional extras…", ""});
        Rectangle r = v.hitMap.get(id);
        if (r == null) return;
        int pw = 300, ph = items.size() * 38 + 12;
        int px = Math.min(W - 28 - pw, r.x), py = r.y - 6 - ph;
        if (py < 12) py = r.y + r.height + 6;
        v.popup = new LauncherView.Popup(id, px, py, pw, items);
    }

    // ------------------------------------------------------------------ safe mode and shortcuts

    /** Writes the safe-start request, then plays the instance as usual. */
    void safeStart(Launcher.Instance i) {
        if (i == null) return;
        if (v.game.running(i)) { v.toast(i.name + " is running. Close the game first."); return; }
        try {
            SafeStart.request(i.dir);
        } catch (java.io.IOException e) {
            v.toast("Couldn't ask for safe mode: " + EvadeInstaller.friendly(e));
            return;
        }
        v.actions.select(i);
        v.showPage(LauncherView.Page.HOME);
        v.notice("Safe mode for " + i.name, "The next start has every Evade module off. Turn them back on one at a time in game.", false);
        v.actions.play();
    }

    void shortcut(Launcher.Instance i) {
        if (i == null) return;
        Path self = SelfUpdate.selfPath();
        work().submit(() -> {
            try {
                Path f = InstanceShortcut.create(i, self);
                ui(() -> {
                    v.toast("Shortcut made: " + f.getFileName());
                    v.notice("Desktop shortcut for " + i.name, "Double-click it to update " + i.name + " and open it in the Minecraft Launcher.", false);
                });
            } catch (Exception e) {
                ui(() -> v.toast("Couldn't make the shortcut: " + EvadeInstaller.friendly(e)));
            }
        });
    }

    // ------------------------------------------------------------------ templates (New instance dialog)

    /** The template chips and the picked one's line. Returns the height used. */
    int paintTemplates(Graphics2D g, int x, int y, int w) {
        v.label(g, "START FROM", x, y + 6);
        InstanceTemplates.Template[] all = InstanceTemplates.ALL;
        int gap = 8, cw = (w - gap * (all.length - 1)) / all.length, ch = 58, cy = y + 16;
        for (int k = 0; k < all.length; k++) {
            InstanceTemplates.Template t = all[k];
            String id = "tpl:" + t.key();
            boolean on = t == template;
            float a = v.anim(id, id.equals(v.hovered));
            int cx = x + k * (cw + gap);
            g.setColor(on ? new Color(225, 29, 46, 40) : mix(FIELD, new Color(0x181B21), a));
            g.fill(new RoundRectangle2D.Double(cx, cy, cw, ch, 14, 14));
            g.setColor(on ? RED : LauncherView.BORDER);
            g.draw(new RoundRectangle2D.Double(cx + .5, cy + .5, cw - 1, ch - 1, 14, 14));
            LauncherView.icon(g, t.icon(), cx + cw / 2, cy + 20, 15, on ? RED_HI : mix(DIM, TEXT, a));
            g.setFont(v.interBold.deriveFont(10.5f));
            g.setColor(on ? TEXT : DIM);
            String n = t.name().replace(" (lean)", "").replace(" + shaders", "").replace("Low-end laptop", "Laptop");
            center(g, ellipsizeEnd(g, n, cw - 8), cx, cy + 34, cw, 18);
            v.hit(id, new Rectangle(cx, cy, cw, ch));
            v.game.name(id, "Template " + t.name() + (on ? ", selected" : "") + ". " + t.sub());
        }
        g.setFont(v.inter.deriveFont(11.5f));
        g.setColor(FAINT);
        String sub = template.sub() + (template.shelf().isEmpty() ? "" : ". Adds " + String.join(", ", shelfNames(template)) + " from Modrinth");
        int ly = cy + ch + 18;
        for (String l : LauncherView.wrapLines(g, sub, w, 2)) { g.drawString(l, x, ly); ly += 15; }
        return ly - y;
    }

    private static List<String> shelfNames(InstanceTemplates.Template t) {
        List<String> out = new ArrayList<>();
        for (String s : t.shelf()) out.add(Character.toUpperCase(s.charAt(0)) + s.substring(1));
        return out;
    }

    /** New instance with a template other than Blank (true: handled here, false: the usual create). */
    boolean createInstance(String name, String copyFrom) {
        InstanceTemplates.Template t = template;
        template = InstanceTemplates.BLANK;
        if (t == InstanceTemplates.BLANK || settings == null) return false;
        List<Launcher.Instance> all = List.copyOf(v.instances);
        v.toast("Making " + name + " (" + t.name() + ")…");
        work().submit(() -> {
            List<String> notes = new ArrayList<>();
            try {
                Launcher.Instance i = InstanceTemplates.create(core, settings, all, name, copyFrom, t, notes, null);
                ui(() -> {
                    if (reload != null) reload.run();
                    v.focusedId = i.id;
                    v.toast(i.name + " is ready (" + t.name() + "). Press Play to install Evade in it.");
                    if (!notes.isEmpty()) v.notice(i.name + ": not everything was added", String.join("; ", notes), true);
                });
            } catch (Exception e) {
                ui(() -> v.toast("Couldn't create the instance: " + EvadeInstaller.friendly(e)));
            }
        });
        return true;
    }

    // ------------------------------------------------------------------ sheets

    boolean paintOverlay(Graphics2D g, String overlay) {
        switch (overlay) {
            case "extras" -> paintExtras(g);
            case "import" -> paintImport(g);
            case ShaderPackSheet.OVERLAY -> shaderPacks.paint(g);
            default -> { return false; }
        }
        return true;
    }

    private void veil(Graphics2D g) {
        g.setColor(new Color(4, 5, 7, 150));
        if (v.roundCorners) g.fill(new RoundRectangle2D.Double(0, 0, W, H, 22, 22));
        else g.fillRect(0, 0, W, H);
    }

    private void paintExtras(Graphics2D g) {
        Launcher.Instance i = v.focused();
        if (i == null) { v.overlay = null; return; }
        veil(g);
        int w = 600, rh = 78, h = 132 + OptionalMods.GROUPS.length * rh + 80, x = (W - w) / 2, y = Math.max(16, (H - h) / 2), ix = x + 32, iw = w - 64;
        v.raised(g, x, y, w, h);
        v.hitMap.put("ov-panel", new Rectangle(x, y, w, h));
        g.setFont(v.interBold.deriveFont(21f));
        g.setColor(TEXT);
        g.drawString(ellipsizeEnd(g, "Optional extras for " + i.name, iw), ix, y + 48);
        g.setFont(v.inter.deriveFont(12.5f));
        g.setColor(DIM);
        int ly = y + 72;
        for (String l : LauncherView.wrapLines(g, "All off unless you switch them on. Each downloads from Modrinth when you press Play (Evade never hosts them) and goes away again when you switch it off.", iw, 2)) {
            g.drawString(l, ix, ly);
            ly += 17;
        }
        int ry = y + 118;
        for (int k = 0; k < OptionalMods.GROUPS.length; k++) {
            OptionalMods.Group gr = OptionalMods.GROUPS[k];
            boolean on = i.optional.contains(gr.key());
            boolean avail = v.release == null || v.release.optional().containsKey(gr.key());
            String id = "ov-x:" + gr.key();
            g.setFont(v.interBold.deriveFont(14f));
            g.setColor(avail ? TEXT : FAINT);
            g.drawString(gr.label(), ix, ry + 24);
            int tx = ix + g.getFontMetrics().stringWidth(gr.label()) + 10;
            if (gr.experimental()) {
                g.setFont(v.interBold.deriveFont(9.5f));
                int bw = g.getFontMetrics().stringWidth("EXPERIMENTAL") + 14;
                g.setColor(new Color(251, 191, 36, 36));
                g.fill(new RoundRectangle2D.Double(tx, ry + 12, bw, 16, 16, 16));
                g.setColor(AMBER);
                center(g, "EXPERIMENTAL", tx, ry + 12, bw, 16);
            }
            g.setFont(v.inter.deriveFont(12f));
            g.setColor(DIM);
            g.drawString(ellipsizeEnd(g, avail ? gr.sub() : "Not available for " + InstallerCore.MC_VERSION + " on Modrinth right now", iw - 80), ix, ry + 44);
            g.setFont(v.inter.deriveFont(11f));
            g.setColor(FAINT);
            String credit = gr.credit() + "  ·  ";
            g.drawString(credit, ix, ry + 62);
            v.textLink(g, "ov-xlink:" + gr.key(), "Modrinth", ix + g.getFontMetrics().stringWidth(credit), ry + 62, 0);
            v.toggle(g, id + "-sw", ix + iw - 44, ry + 22, on, avail);
            if (avail) v.hit(id, new Rectangle(ix + iw - 60, ry + 10, 60, 40));
            v.game.name(id, gr.label() + ", " + (on ? "on" : "off") + ". " + gr.sub());
            if (k < OptionalMods.GROUPS.length - 1) {
                g.setColor(HAIRLINE);
                g.fillRect(ix, ry + rh - 4, iw, 1);
            }
            ry += rh;
        }
        g.setFont(v.inter.deriveFont(12f));
        g.setColor(i.dirty ? AMBER : FAINT);
        g.drawString(i.dirty ? "Applied the next time you press Play with " + i.name + "." : "Changes apply the next time you press Play.", ix, y + h - 34);
        v.smallPrimary(g, "ov-x-done", x + w - 32 - 120, y + h - 60, 120, 40, "Done", null);
        if (v.game.focusId == null || !v.hitMap.containsKey(v.game.focusId)) v.game.focusId = "ov-x-done";
    }

    private void paintImport(Graphics2D g) {
        veil(g);
        int w = 680, h = 520, x = (W - w) / 2, y = (H - h) / 2, ix = x + 32, iw = w - 64;
        v.raised(g, x, y, w, h);
        v.hitMap.put("ov-panel", new Rectangle(x, y, w, h));
        g.setFont(v.interBold.deriveFont(21f));
        g.setColor(TEXT);
        g.drawString("Import from another launcher", ix, y + 48);
        g.setFont(v.inter.deriveFont(12.5f));
        g.setColor(DIM);
        String sub = scanning || found == null ? "Looking in the usual launcher folders…"
                : found.isEmpty() ? "No Minecraft 1.21 instances in the usual places. Choose an instance's folder instead."
                : "Found " + found.size() + (found.size() == 1 ? " instance" : " instances") + " from another launcher. Nothing there is changed.";
        g.drawString(ellipsizeEnd(g, sub, iw), ix, y + 72);
        g.setFont(v.inter.deriveFont(11.5f));
        g.setColor(FAINT);
        g.drawString(ellipsizeEnd(g, "Settings, keybinds, servers and packs come over; mods are fetched again from Modrinth for " + InstallerCore.MC_VERSION + ".", iw), ix, y + 92);
        Rectangle area = new Rectangle(x + 14, y + 108, w - 28, h - 108 - 88);
        if (importBusy != null || importTitle != null) {
            paintImportResult(g, ix, area.y + 10, iw);
        } else if (found != null && !found.isEmpty()) {
            float off = v.scrollOf("ov-implist");
            Shape was = g.getClip();
            g.clip(area);
            v.clip = area;
            int ry = area.y + 4 - Math.round(off), rh = 70;
            for (int n = 0; n < found.size(); n++) {
                LauncherImport.Found f = found.get(n);
                if (ry + rh >= area.y && ry <= area.y + area.height) {
                    g.setColor(new Color(255, 255, 255, 6));
                    g.fill(new RoundRectangle2D.Double(ix - 10, ry, iw + 20, rh - 6, 20, 20));
                    v.tile(g, ix, ry + 13, 38, f.name());
                    g.setFont(v.interBold.deriveFont(14f));
                    g.setColor(TEXT);
                    g.drawString(ellipsizeEnd(g, f.name(), iw - 220), ix + 52, ry + 25);
                    g.setFont(v.inter.deriveFont(11.5f));
                    g.setColor(DIM);
                    g.drawString(ellipsizeEnd(g, f.summary(), iw - 220), ix + 52, ry + 42);
                    g.setColor(FAINT);
                    g.drawString(ellipsize(g, f.gameDir().toString(), iw - 220), ix + 52, ry + 57);
                    v.smallPrimary(g, "ov-imp:" + n, ix + iw - 110, ry + 15, 110, 34, "Import", "download");
                    v.game.name("ov-imp:" + n, "Import " + f.name() + ", " + f.summary());
                }
                ry += rh;
            }
            g.setClip(was);
            v.clip = null;
            v.scrollArea(g, "ov-implist", area, found.size() * rh + 8);
        } else if (scanning || found == null) {
            v.animating = true;
        }
        int fy = y + h - 64;
        g.setColor(HAIRLINE);
        g.fillRect(x + 1, fy - 16, w - 2, 1);
        v.toggle(g, "ov-imp-worlds-sw", ix, fy + 9, importWorlds, importBusy == null);
        g.setFont(v.inter.deriveFont(12.5f));
        g.setColor(TEXT);
        g.drawString("Include worlds", ix + 52, fy + 25);
        if (importBusy == null) v.hit("ov-imp-worlds", new Rectangle(ix - 4, fy + 4, 170, 32));
        v.game.name("ov-imp-worlds", "Include worlds, " + (importWorlds ? "on" : "off"));
        if (importBusy == null) v.ghostButton(g, "ov-imp-folder", ix + iw - 120 - 12 - 170, fy, 170, 40, "Choose a folder…", false, "folder");
        v.smallPrimary(g, "ov-imp-close", ix + iw - 120, fy, 120, 40, importTitle != null ? "Done" : "Close", null);
    }

    private void paintImportResult(Graphics2D g, int ix, int y, int iw) {
        g.setFont(v.interBold.deriveFont(15f));
        g.setColor(TEXT);
        g.drawString(ellipsizeEnd(g, importBusy != null ? importBusy : importTitle, iw), ix, y + 22);
        if (importBusy != null) {
            v.progressBar(g, ix, y + 40, iw, 4, Math.max(0.03, Math.min(1, importProgress)));
            v.animating = true;
            return;
        }
        int ly = y + 50;
        g.setFont(v.inter.deriveFont(12.5f));
        for (String n : importNotes) {
            g.setColor(DIM);
            for (String l : LauncherView.wrapLines(g, "• " + n, iw, 3)) {
                if (ly > y + 270) return;
                g.drawString(l, ix, ly);
                ly += 18;
            }
            ly += 4;
        }
        if (importNotes.isEmpty()) {
            g.setColor(OK);
            g.drawString("Everything came over. Press Play to install Evade in it.", ix, ly);
        }
    }

    /** Opens the import sheet and looks for instances (off the UI thread). */
    void openImport() {
        v.closeOverlays();
        v.overlay = "import";
        importTitle = null;
        importNotes = List.of();
        if (importBusy == null) scan();
    }

    private void scan() {
        scanning = true;
        found = null;
        work().submit(() -> {
            List<LauncherImport.Found> l = LauncherImport.detect(LauncherImport.roots());
            ui(() -> { found = l; scanning = false; });
        });
    }

    private void startImport(LauncherImport.Found f) {
        if (importBusy != null || settings == null) return;
        importBusy = "Importing " + f.name();
        importProgress = 0;
        boolean worlds = importWorlds;
        List<Launcher.Instance> all = List.copyOf(v.instances);
        work().submit(() -> {
            try {
                LauncherImport.Result r = LauncherImport.importInstance(core, settings, all, f, worlds,
                        (p, m) -> SwingUtilities.invokeLater(() -> { importProgress = p; if (m != null) importBusy = m; v.repaint(); }));
                ui(() -> {
                    importBusy = null;
                    importTitle = "New instance: " + r.instance().name + " (" + r.mods() + (r.mods() == 1 ? " mod" : " mods") + " from Modrinth)";
                    importNotes = r.notes();
                    if (reload != null) reload.run();
                    v.focusedId = r.instance().id;
                    v.notice("Imported " + r.instance().name, "From another launcher: settings, keybinds, servers" + (worlds ? ", worlds" : "") + " and packs. Press Play to install Evade in it.", false);
                });
            } catch (Exception e) {
                ui(() -> { importBusy = null; importTitle = "Couldn't import " + f.name(); importNotes = List.of(EvadeInstaller.friendly(e)); });
            }
        });
    }

    private void chooseFolder() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Choose an instance folder (the one with options.txt, mods and saves)");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(SwingUtilities.getWindowAncestor(v)) != JFileChooser.APPROVE_OPTION) return;
        Path dir = fc.getSelectedFile().toPath();
        LauncherImport.Found f = LauncherImport.read(dir);
        if (f == null) {
            v.toast("That folder isn't a Minecraft 1.21 instance (no options.txt, mods or saves for 1.21)");
            return;
        }
        startImport(f);
    }

    // ------------------------------------------------------------------ drag and drop

    /** While a file is dragged over the window: a veil saying where it'll go. */
    void paintDrag(Graphics2D g) {
        if (!dragging && dropBusy == null) return;
        g.setColor(new Color(4, 5, 7, 170));
        if (v.roundCorners) g.fill(new RoundRectangle2D.Double(0, 0, W, H, 22, 22));
        else g.fillRect(0, 0, W, H);
        int w = 520, h = 190, x = (W - w) / 2, y = (H - h) / 2;
        g.setColor(new Color(225, 29, 46, 26));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 28, 28));
        g.setColor(RED);
        g.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND, 10f, new float[]{9f, 7f}, 0f));
        g.draw(new RoundRectangle2D.Double(x + 1, y + 1, w - 2, h - 2, 28, 28));
        g.setStroke(new java.awt.BasicStroke(1f));
        LauncherView.icon(g, "download", W / 2, y + 52, 26, RED_HI);
        Launcher.Instance sel = v.selected();
        g.setFont(v.interBold.deriveFont(18f));
        g.setColor(TEXT);
        center(g, dropBusy != null ? dropBusy : "Drop to add to " + (sel != null ? sel.name : "this instance"), x, y + 84, w, 28);
        g.setFont(v.inter.deriveFont(12.5f));
        g.setColor(DIM);
        center(g, "Fabric mods (.jar), resource and shader packs (.zip), modpacks (.mrpack)", x, y + 116, w, 20);
        center(g, "Each is checked for Minecraft " + InstallerCore.MC_VERSION + " first", x, y + 136, w, 20);
        if (dropBusy != null) v.animating = true;
    }

    /** Files dropped on the window: each is checked and goes where it belongs (packs and instances make new ones). */
    void dropped(List<Path> files) {
        Launcher.Instance sel = v.selected();
        if (sel == null || files.isEmpty()) return;
        List<DropInstall.Verdict> verdicts = new ArrayList<>();
        for (Path f : files) verdicts.add(DropInstall.classify(f));
        // New instances: a modpack goes through the Mods page's import, an instance zip through the zip import.
        for (int k = 0; k < files.size(); k++) {
            DropInstall.Verdict vd = verdicts.get(k);
            if (vd.kind() == DropInstall.Kind.MRPACK) { v.showPage(LauncherView.Page.MODS); v.content.importPackFile(files.get(k)); }
        }
        List<Path> zips = new ArrayList<>();
        for (int k = 0; k < files.size(); k++) if (verdicts.get(k).kind() == DropInstall.Kind.INSTANCE_ZIP) zips.add(files.get(k));
        List<String> refused = new ArrayList<>();
        for (DropInstall.Verdict vd : verdicts) if (vd.kind() == DropInstall.Kind.REJECT) refused.add(vd.name() + ": " + vd.reason());
        List<Integer> todo = new ArrayList<>();
        for (int k = 0; k < files.size(); k++) {
            DropInstall.Kind kind = verdicts.get(k).kind();
            if (kind == DropInstall.Kind.MOD || kind == DropInstall.Kind.RESOURCEPACK || kind == DropInstall.Kind.SHADER) todo.add(k);
        }
        if (!refused.isEmpty() && todo.isEmpty() && zips.isEmpty() && verdicts.stream().noneMatch(x -> x.kind() == DropInstall.Kind.MRPACK)) {
            v.notice(refused.size() == 1 ? "Couldn't add " + verdicts.get(0).name() : "Couldn't add " + refused.size() + " files", String.join("; ", refused), true);
            v.toast(refused.get(0));
            return;
        }
        if (todo.isEmpty() && zips.isEmpty()) return;
        dropBusy = "Adding to " + sel.name + "…";
        work().submit(() -> {
            List<DropInstall.Verdict> done = new ArrayList<>();
            List<String> failed = new ArrayList<>(refused);
            for (int k : todo) {
                try {
                    DropInstall.install(sel.dir, files.get(k), verdicts.get(k));
                    done.add(verdicts.get(k));
                } catch (Exception e) {
                    failed.add(verdicts.get(k).name() + ": " + EvadeInstaller.friendly(e));
                }
            }
            List<String> made = new ArrayList<>();
            for (Path z : zips) {
                try {
                    InstanceZip.Result r = InstanceZip.importZip(settings != null ? settings : v.settings, List.copyOf(v.instances), z, null);
                    made.add(r.instance().name);
                } catch (Exception e) {
                    failed.add(z.getFileName() + ": " + EvadeInstaller.friendly(e));
                }
            }
            ui(() -> {
                dropBusy = null;
                if (!done.isEmpty()) {
                    v.toast("Added " + DropInstall.summary(done) + " to " + sel.name);
                    v.actions.showMods(sel);
                }
                if (!made.isEmpty()) {
                    if (reload != null) reload.run();
                    v.notice("New instance: " + String.join(", ", made), "Imported from a dropped zip. Press Play to install Evade in it.", false);
                }
                if (!failed.isEmpty()) v.notice(failed.size() == 1 ? "One file wasn't added" : failed.size() + " files weren't added", String.join("; ", failed), true);
                Launcher.refresh(sel);
            });
        });
    }

    // ------------------------------------------------------------------ clicks

    /** This class's controls; false for anything else. */
    boolean click(String id) {
        if (shaderPacks.click(id)) return true;
        Launcher.Instance foc = v.focused();
        switch (id) {
            case "inst-more" -> moreMenu(id);
            case "inst-extras" -> { v.closeOverlays(); v.overlay = "extras"; v.game.focusId = null; }
            case "ov-x-done" -> { v.overlay = null; v.game.focusId = null; }
            case "ov-imp-worlds" -> importWorlds = !importWorlds;
            case "ov-imp-folder" -> chooseFolder();
            case "ov-imp-close" -> { if (importBusy == null) { v.overlay = null; importTitle = null; } }
            default -> {
                if (id.startsWith("ov-x:")) {
                    if (foc == null) return true;
                    String key = id.substring(5);
                    if (!foc.optional.remove(key)) foc.optional.add(key);
                    foc.optional = OptionalMods.clean(foc.optional);
                    foc.dirty = true;
                    v.actions.instanceChanged(foc);
                } else if (id.startsWith("ov-xlink:")) {
                    OptionalMods.Group gr = OptionalMods.byKey(id.substring(9));
                    if (gr != null) v.actions.openUrl("https://modrinth.com/mod/" + gr.mods()[0][0]);
                } else if (id.startsWith("ov-imp:")) {
                    int n = Integer.parseInt(id.substring(7));
                    if (found != null && n < found.size()) startImport(found.get(n));
                } else if (id.startsWith("tpl:")) {
                    InstanceTemplates.Template t = InstanceTemplates.byKey(id.substring(4));
                    if (t != null) template = t;
                } else if (id.startsWith("tl-safe:")) {
                    for (Launcher.Instance i : v.instances) if (i.id.equals(id.substring(8))) safeStart(i);
                } else return false;
            }
        }
        return true;
    }

    /** Popup items of this class ("tl:*"); false for anything else. */
    boolean popup(String what) {
        Launcher.Instance foc = v.focused();
        switch (what) {
            case "tl:safe" -> safeStart(foc);
            case "tl:shortcut" -> shortcut(foc);
            case "tl:extras" -> { v.overlay = "extras"; v.game.focusId = null; }
            case "tl:import" -> openImport();
            default -> { return false; }
        }
        return true;
    }

    /** Words for a screen reader. */
    String labelFor(String id) {
        if (id == null) return null;
        if (id.equals("inst-more")) return "More: safe mode, desktop shortcut, optional extras";
        if (id.equals("inst-extras")) return "Choose optional extras";
        String sp = shaderPacks.labelFor(id);
        if (sp != null) return sp;
        return null;
    }

    private void ui(Runnable r) { SwingUtilities.invokeLater(() -> { r.run(); v.repaint(); }); }
}
