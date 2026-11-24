package net.evadeclient.installer;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static net.evadeclient.installer.LauncherView.AMBER;
import static net.evadeclient.installer.LauncherView.DIM;
import static net.evadeclient.installer.LauncherView.FAINT;
import static net.evadeclient.installer.LauncherView.H;
import static net.evadeclient.installer.LauncherView.HAIRLINE;
import static net.evadeclient.installer.LauncherView.OK;
import static net.evadeclient.installer.LauncherView.TEXT;
import static net.evadeclient.installer.LauncherView.W;
import static net.evadeclient.installer.LauncherView.ellipsizeEnd;
import static net.evadeclient.installer.LauncherView.mix;

/**
 * The Mods page's version tools, beside {@link ContentPage}: the version list of a mod or pack (every build for this
 * Minecraft version, newest first, with its changelog and a button to switch to it), pinning (Update all skips a pinned
 * project), and "Undo last update". Click ids start with {@code c-ver}, {@code c-pin} and {@code c-undo}; the version
 * list is the sheet {@code "versions"}.
 */
final class ContentVersions {
    private final ContentPage page;
    private final LauncherView v;
    private final ExecutorService work = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "content-versions");
        t.setDaemon(true);
        return t;
    });

    /** The file the version list is for, its project, and the list (null while loading). */
    Path file;
    String projectId, title, loadError;
    List<ContentApi.Version> list;
    /** A version being switched to (its id), and how far. */
    String switching;
    double switchProgress;
    /** Pins of the shown instance, and what Undo would put back (cached; re-read after changes). */
    Set<String> pins = Set.of();
    List<ContentPins.Item> rollback = List.of();
    private Path cachedFor;
    private final Map<String, List<ContentApi.Version>> cache = new HashMap<>();

    ContentVersions(ContentPage page, LauncherView v) {
        this.page = page;
        this.v = v;
    }

    /** Keeps pins and the undo record in step with the instance shown. */
    void ensure(Path dir) {
        if (dir == null || dir.equals(cachedFor)) return;
        cachedFor = dir;
        reload();
    }

    void reload() {
        if (cachedFor == null) return;
        pins = ContentPins.pins(cachedFor);
        rollback = ContentPins.last(cachedFor);
    }

    boolean pinned(Updates.Known k) {
        return k != null && k.current() != null && k.current().projectId() != null && pins.contains(k.current().projectId());
    }

    /** Updates Update all would apply (pinned projects left out). */
    long unpinnedCount(java.util.Collection<Updates.Known> known) {
        return known.stream().filter(k -> k.hasUpdate() && !pinned(k)).count();
    }

    // ------------------------------------------------------------------ the item sheet's extra buttons

    /** Pin and "Other versions" on a mod's or pack's sheet, from {@code x} rightwards. */
    void paintSheetButtons(Graphics2D g, Updates.Known k, int x, int y) {
        if (k == null || k.current() == null || k.current().projectId() == null) return;
        boolean on = pinned(k);
        v.ghostButton(g, "c-versions", x, y, 150, 40, "Other versions", false, "list");
        v.ghostButton(g, "c-pin", x + 160, y, 110, 40, on ? "Unpin" : "Pin", false, null, on);
        v.game.name("c-pin", on ? "Unpin: Update all updates it again" : "Pin: Update all leaves it as it is");
        if (on) {
            g.setFont(v.inter.deriveFont(11.5f));
            g.setColor(AMBER);
            g.drawString("Pinned: Update all leaves it alone", x, y - 10);
        }
    }

    // ------------------------------------------------------------------ the version list

    void open(Path f, Updates.Known k, String name) {
        file = f;
        projectId = k.current().projectId();
        title = name;
        loadError = null;
        list = cache.get(projectId);
        page.sheet = "versions";
        if (list == null && !page.offline) {
            String pid = projectId;
            List<String> loaders = k.type().loaders;
            work.submit(() -> {
                try {
                    List<ContentApi.Version> l = ContentApi.versions(pid, loaders);
                    ui(() -> { cache.put(pid, l); if (pid.equals(projectId)) list = l; });
                } catch (IOException e) {
                    ui(() -> loadError = EvadeInstaller.friendly(e));
                }
            });
        }
    }

    void paint(Graphics2D g, int x, int y, int w, int h, int ix, int iw) {
        Updates.Known k = page.known.get(file);
        g.setFont(v.interBold.deriveFont(19f));
        g.setColor(TEXT);
        g.drawString(ellipsizeEnd(g, "Versions of " + (title == null ? "this" : title), iw - 60), ix, y + 48);
        g.setFont(v.inter.deriveFont(12f));
        g.setColor(FAINT);
        String cur = k != null && k.current() != null ? k.current().number() : null;
        g.drawString(ellipsizeEnd(g, "For Minecraft " + InstallerCore.MC_VERSION + (cur != null ? "  ·  installed: " + cur : "")
                + (pinned(k) ? "  ·  pinned" : ""), iw - 60), ix, y + 70);
        Rectangle area = new Rectangle(x + 12, y + 90, w - 24, h - 90 - 76);
        if (list == null) {
            g.setFont(v.inter.deriveFont(12.5f));
            g.setColor(loadError != null ? LauncherTheme.DANGER : DIM);
            g.drawString(loadError != null ? "Couldn't load the versions: " + loadError : "Loading the versions from Modrinth…", ix, area.y + 30);
            if (loadError == null) v.animating = true;
        } else {
            float off = v.scrollOf("c-verlist");
            Shape was = g.getClip();
            g.clip(area);
            v.clip = area;
            int ry = area.y - Math.round(off), rh = 66;
            for (int n = 0; n < list.size(); n++) {
                ContentApi.Version ver = list.get(n);
                boolean installed = k != null && k.current() != null && ver.id().equals(k.current().id());
                if (ry + rh > area.y - rh && ry < area.y + area.height + rh) paintRow(g, ver, n, installed, ix - 8, ry, iw + 16, rh);
                ry += rh;
            }
            g.setClip(was);
            v.clip = null;
            v.scrollArea(g, "c-verlist", area, list.size() * rh);
            if (list.isEmpty()) {
                g.setFont(v.inter.deriveFont(12.5f));
                g.setColor(DIM);
                g.drawString("No versions for " + InstallerCore.MC_VERSION + " on Modrinth.", ix, area.y + 30);
            }
        }
        int by = y + h - 60;
        v.ghostButton(g, "c-verback", ix, by, 110, 40, "Back", false);
        if (k != null && k.current() != null) {
            boolean on = pinned(k);
            v.ghostButton(g, "c-pin", ix + iw - 120, by, 120, 40, on ? "Unpin" : "Pin", false, null, on);
        }
        g.setFont(v.inter.deriveFont(11.5f));
        g.setColor(FAINT);
        g.drawString(ellipsizeEnd(g, "Picking an older version pins it, so Update all leaves it alone.", iw - 260), ix + 124, by + 25);
    }

    private void paintRow(Graphics2D g, ContentApi.Version ver, int n, boolean installed, int x, int y, int w, int h) {
        String id = "c-ver:" + n;
        float t = v.anim(id, id.equals(v.hovered));
        if (installed || t > 0.01f) {
            g.setColor(installed ? new Color(61, 220, 132, 16) : new Color(255, 255, 255, Math.round(8 * t)));
            g.fill(new RoundRectangle2D.Double(x, y + 3, w, h - 6, 18, 18));
        }
        g.setFont(v.interBold.deriveFont(13.5f));
        g.setColor(TEXT);
        String num = ellipsizeEnd(g, ver.number() == null ? "?" : ver.number(), w - 300);
        g.drawString(num, x + 16, y + 27);
        int bx = x + 16 + g.getFontMetrics().stringWidth(num) + 10;
        String type = ver.type() == null ? "release" : ver.type();
        Color tc = type.equals("release") ? OK : AMBER;
        g.setFont(v.interBold.deriveFont(9.5f));
        int tw = g.getFontMetrics().stringWidth(type.toUpperCase(java.util.Locale.ROOT)) + 14;
        g.setColor(new Color(tc.getRed(), tc.getGreen(), tc.getBlue(), 36));
        g.fill(new RoundRectangle2D.Double(bx, y + 15, tw, 16, 16, 16));
        g.setColor(tc);
        LauncherView.center(g, type.toUpperCase(java.util.Locale.ROOT), bx, y + 15, tw, 16);
        g.setFont(v.inter.deriveFont(11.5f));
        g.setColor(FAINT);
        String date = ver.published() != null && ver.published().length() >= 10 ? ver.published().substring(0, 10) : "";
        g.drawString(date, bx + tw + 10, y + 27);
        String log = ver.changelog() == null ? "" : ContentPage.plain(ver.changelog()).replace('\n', ' ').replaceAll("\\s{2,}", " ").strip();
        g.setColor(DIM);
        g.setFont(v.inter.deriveFont(12f));
        g.drawString(ellipsizeEnd(g, log.isEmpty() ? "No changelog" : log, w - 180), x + 16, y + 48);
        if (installed) {
            g.setFont(v.interBold.deriveFont(12f));
            g.setColor(OK);
            String s = "Installed";
            g.drawString(s, x + w - 16 - g.getFontMetrics().stringWidth(s), y + 38);
        } else if (switching != null && switching.equals(ver.id())) {
            g.setColor(HAIRLINE);
            g.fill(new RoundRectangle2D.Double(x + w - 136, y + 30, 120, 4, 4, 4));
            g.setColor(LauncherView.RED);
            g.fill(new RoundRectangle2D.Double(x + w - 136, y + 30, 120 * Math.max(0.05, switchProgress), 4, 4, 4));
            v.animating = true;
        } else if (switching == null) {
            v.smallButton(g, id, x + w - 16 - 110, y + 17, 110, 32, "Use this");
        }
    }

    // ------------------------------------------------------------------ clicks

    /** This class's clicks; false for anything else. */
    boolean click(String cmd, String arg, Launcher.Instance sel) {
        switch (cmd) {
            case "c-versions" -> {
                Updates.Known k = page.known.get(page.sheetFileShown());
                if (k != null && k.current() != null) open(page.sheetFileShown(), k, page.sheetTitleShown());
            }
            case "c-verback" -> page.sheet = null;
            case "c-pin" -> {
                Updates.Known k = page.known.get(page.sheet != null && page.sheet.equals("versions") ? file : page.sheetFileShown());
                if (sel == null || k == null || k.current() == null) return true;
                String pid = k.current().projectId();
                try {
                    boolean on = ContentPins.setPinned(sel.dir, pid, !pins.contains(pid));
                    reload();
                    v.toast(on ? "Pinned: Update all leaves it as it is" : "Unpinned: Update all updates it again");
                } catch (IOException e) {
                    v.toast("Couldn't save the pin: " + EvadeInstaller.friendly(e));
                }
            }
            case "c-ver" -> {
                int n = Integer.parseInt(arg);
                if (sel == null || list == null || n >= list.size() || switching != null) return true;
                use(sel, list.get(n), n > 0);
            }
            case "c-undo" -> {
                if (sel == null || rollback.isEmpty()) return true;
                v.game.ask("Undo the last update?", "Puts back " + describe(rollback) + ". The newer files are removed.", "Undo", false, () -> undo(sel));
            }
            default -> { return false; }
        }
        return true;
    }

    private String describe(List<ContentPins.Item> items) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < items.size() && i < 4; i++) {
            ContentPins.Item it = items.get(i);
            if (i > 0) b.append(", ");
            String name = it.projectId() != null && page.projects.get(it.projectId()) != null ? page.projects.get(it.projectId()).title() : it.title();
            b.append(name).append(it.oldVersion() != null ? " " + it.oldVersion() : "");
        }
        if (items.size() > 4) b.append(" and ").append(items.size() - 4).append(" more");
        return b.toString();
    }

    /** Switches the file to {@code ver} (kept for Undo); an older version pins the project. */
    private void use(Launcher.Instance sel, ContentApi.Version ver, boolean older) {
        Updates.Known k = page.known.get(file);
        if (k == null) return;
        Updates.Known target = new Updates.Known(k.file(), k.type(), k.sha1(), k.current(), ver);
        if (!target.hasUpdate()) { v.toast("That version is already installed"); return; }
        switching = ver.id();
        switchProgress = 0;
        work.submit(() -> {
            try {
                ContentPins.Batch b = ContentPins.begin(sel.dir);
                Path np = Updates.apply(sel.dir, target, (f, m) -> SwingUtilities.invokeLater(() -> switchProgress = f), b);
                if (older) ContentPins.setPinned(sel.dir, ver.projectId() != null ? ver.projectId() : projectId, true);
                ui(() -> {
                    switching = null;
                    page.known.remove(k.file());
                    page.known.put(np, new Updates.Known(np, k.type(), ver.sha1(), ver, null));
                    file = np;
                    reload();
                    page.versionsChanged(sel, k.type());
                    v.toast("Now on " + ver.number() + (older ? ", pinned" : "") + ". Undo last update puts the old one back.");
                });
            } catch (Exception e) {
                ui(() -> { switching = null; v.toast("Couldn't switch: " + EvadeInstaller.friendly(e)); });
            }
        });
    }

    private void undo(Launcher.Instance sel) {
        work.submit(() -> {
            try {
                int n = ContentPins.undo(sel.dir);
                ui(() -> {
                    reload();
                    page.versionsChanged(sel, null);
                    v.toast(n == 0 ? "Nothing to undo" : "Put back " + n + (n == 1 ? " file" : " files") + " from before the update");
                });
            } catch (IOException e) {
                ui(() -> v.toast("Couldn't undo: " + EvadeInstaller.friendly(e) + ". Is the game running?"));
            }
        });
    }

    private void ui(Runnable r) { SwingUtilities.invokeLater(() -> { r.run(); v.repaint(); }); }
}
