package net.evadeclient.installer;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static net.evadeclient.installer.LauncherView.AMBER;
import static net.evadeclient.installer.LauncherView.DIM;
import static net.evadeclient.installer.LauncherView.FAINT;
import static net.evadeclient.installer.LauncherView.OK;
import static net.evadeclient.installer.LauncherView.TEXT;
import static net.evadeclient.installer.LauncherView.center;
import static net.evadeclient.installer.LauncherView.ellipsizeEnd;
import static net.evadeclient.installer.LauncherView.icon;

/**
 * The Mods page's Add-ons tab, beside {@link ContentPage}: the {@link Addons} tested next to Evade, each with what it
 * does, its licence and a Modrinth link, whether a Fabric 1.21.11 build exists (and fits this PC and instance), and
 * one-click install. Click ids start with {@code c-addon}; an add-on's details are the sheet {@code "addon:<key>"}.
 */
final class ContentAddons {
    private final ContentPage page;
    private final LauncherView v;
    private final InstallerCore core = new InstallerCore();
    private final ExecutorService work = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "content-addons");
        t.setDaemon(true);
        return t;
    });

    /** Modrinth's answer per add-on for the instance shown (missing while checking). */
    final Map<String, Addons.Status> status = new HashMap<>();
    /** Add-on key -> "installing", "installed" or an error. */
    final Map<String, String> state = new HashMap<>();
    double progress;
    private Path checkedFor;

    ContentAddons(ContentPage page, LauncherView v) {
        this.page = page;
        this.v = v;
    }

    private void ui(Runnable r) {
        SwingUtilities.invokeLater(() -> { r.run(); v.repaint(); });
    }

    /** Checks Modrinth for the instance shown (once per instance, again after "Check again"). */
    void ensure(Launcher.Instance sel) {
        if (sel == null || page.offline || sel.dir.equals(checkedFor)) return;
        checkedFor = sel.dir;
        status.clear();
        state.clear();
        Path dir = sel.dir;
        boolean perf = sel.perf;
        work.submit(() -> {
            List<String> gpus = MemoryAdvice.detect();
            for (Addons.Addon a : Addons.ALL) {
                Addons.Status s = Addons.check(core, a, dir, perf, gpus);
                ui(() -> { if (dir.equals(checkedFor)) status.put(a.key(), s); });
            }
        });
    }

    // ------------------------------------------------------------------ painting

    void paint(Graphics2D g, Launcher.Instance sel, int x, int y, int w, int h) {
        ensure(sel);
        g.setFont(v.inter.deriveFont(12.5f));
        g.setColor(DIM);
        g.drawString(ellipsizeEnd(g, "Popular mods tested next to Evade. Each comes from Modrinth when you install it, with the libraries it needs.", w - 170), x + 4, y + 18);
        v.ghostButton(g, "c-addon-recheck", x + w - 150, y - 4, 150, 36, "Check again", false);
        y += 40;
        h -= 40;
        v.card(g, x, y, w, h, false);
        Rectangle area = new Rectangle(x + 1, y + 8, w - 2, h - 16);
        float off = v.scrollOf("c-addons");
        Shape was = g.getClip();
        g.clip(area);
        v.clip = area;
        int ry = y + 10 - Math.round(off);
        for (int n = 0; n < Addons.ALL.length; n++) {
            Addons.Addon a = Addons.ALL[n];
            if (ry + 86 >= area.y && ry <= area.y + area.height) paintRow(g, sel, a, n, x, ry, w);
            ry += 92;
        }
        // asked for, not offered
        g.setFont(v.interBold.deriveFont(12f));
        g.setColor(DIM);
        g.drawString("Not in this list", x + 24, ry + 18);
        ry += 26;
        g.setFont(v.inter.deriveFont(11.5f));
        for (String[] s : Addons.NOT_OFFERED) {
            g.setColor(TEXT);
            g.drawString(s[0], x + 24, ry + 14);
            int nw = g.getFontMetrics().stringWidth(s[0]);
            g.setColor(FAINT);
            g.drawString(ellipsizeEnd(g, s[2], w - nw - 64), x + 24 + nw + 10, ry + 14);
            ry += 22;
        }
        ry += 12;
        int content = ry + Math.round(off) - (y + 10);
        g.setClip(was);
        v.clip = null;
        v.scrollArea(g, "c-addons", area, content);
    }

    private void paintRow(Graphics2D g, Launcher.Instance sel, Addons.Addon a, int n, int x, int ry, int w) {
        String rowId = "c-addon:" + a.key();
        float t = v.anim(rowId, rowId.equals(v.hovered));
        g.setColor(new Color(255, 255, 255, (n % 2 == 1 ? 5 : 0) + Math.round(8 * t)));
        g.fill(new RoundRectangle2D.Double(x + 10, ry, w - 20, 86, 10, 10));
        v.hit(rowId, new Rectangle(x + 10, ry, w - 20, 86));
        v.modIcon(g, x + 22, ry + 20, 42, a.name(), null, true);
        g.setFont(v.interBold.deriveFont(13.5f));
        g.setColor(TEXT);
        g.drawString(a.name(), x + 78, ry + 24);
        int tx = x + 78 + g.getFontMetrics().stringWidth(a.name()) + 10;
        g.setFont(v.inter.deriveFont(11f));
        g.setColor(FAINT);
        g.drawString(a.category() + "  ·  " + a.licence() + "  ·  modrinth.com/mod/" + a.slug(), tx, ry + 24);
        g.setFont(v.inter.deriveFont(12f));
        g.setColor(DIM);
        g.drawString(ellipsizeEnd(g, a.what(), w - 270), x + 78, ry + 45);

        Addons.Status s = status.get(a.key());
        String st = state.get(a.key());
        boolean installed = "installed".equals(st) || sel != null && v.installedProjects.contains(a.projectId());
        g.setFont(v.inter.deriveFont(11.5f));
        String line;
        Color c;
        if (st != null && !"installing".equals(st) && !"installed".equals(st)) { line = st; c = LauncherTheme.DANGER; }
        else if (s == null) { line = page.offline ? "Fabric " + InstallerCore.MC_VERSION + " build on Modrinth" : "Checking Modrinth…"; c = FAINT; }
        else { line = s.line(); c = s.installable() ? OK : AMBER; }
        g.setColor(c);
        g.drawString(ellipsizeEnd(g, line, w - 270), x + 78, ry + 66);

        int bx = x + w - 30 - 130;
        if ("installing".equals(st)) {
            g.setColor(new Color(225, 29, 46, 140));
            g.fill(new RoundRectangle2D.Double(bx, ry + 27, Math.max(8, 130 * Math.min(1, Math.max(0.06, progress))), 32, 10, 10));
            g.setFont(v.interBold.deriveFont(12f));
            g.setColor(TEXT);
            center(g, "Installing…", bx, ry + 27, 130, 32);
        } else if (installed) {
            g.setColor(new Color(61, 220, 132, 22));
            g.fill(new RoundRectangle2D.Double(bx, ry + 27, 130, 32, 10, 10));
            icon(g, "check", bx + 30, ry + 43, 10, OK);
            g.setFont(v.interBold.deriveFont(12f));
            g.setColor(OK);
            g.drawString("Installed", bx + 42, ry + 47);
        } else if (s != null && s.installable()) {
            v.smallPrimary(g, "c-addon-inst:" + a.key(), bx, ry + 27, 130, 32, "Install", "download");
        } else {
            g.setFont(v.inter.deriveFont(11f));
            g.setColor(FAINT);
            center(g, s == null ? "" : "Can't install here", bx, ry + 27, 130, 32);
        }
    }

    /** The details sheet: what was tested, the licence and the Modrinth credit. */
    void paintSheet(Graphics2D g, Launcher.Instance sel, String key, int x, int y, int w, int h, int ix, int iw) {
        Addons.Addon a = Addons.byKey(key);
        if (a == null) { page.sheet = null; return; }
        g.setFont(v.interBold.deriveFont(19f));
        g.setColor(TEXT);
        g.drawString(a.name(), ix, y + 48);
        g.setFont(v.inter.deriveFont(12f));
        g.setColor(DIM);
        List<String> what = LauncherView.wrapLines(g, a.what(), iw - 40, 2);
        for (int j = 0; j < what.size(); j++) g.drawString(what.get(j), ix, y + 72 + j * 16);
        int ty = y + 72 + what.size() * 16 + 16;
        g.setFont(v.inter.deriveFont(11.5f));
        g.setColor(FAINT);
        g.drawString("By its authors on Modrinth: " + a.url(), ix, ty);
        ty += 18;
        g.drawString("Licence: " + a.licence() + (a.closed() ? ". Downloaded from Modrinth when you install it; Evade never hosts it." : ". Downloaded from Modrinth when you install it."), ix, ty);
        ty += 30;
        g.setFont(v.interBold.deriveFont(12.5f));
        g.setColor(TEXT);
        g.drawString("Tested with Evade", ix, ty);
        ty += 10;
        g.setFont(v.inter.deriveFont(12f));
        for (String line : a.tested()) {
            List<String> ls = LauncherView.wrapLines(g, line, iw - 24, 3);
            icon(g, "check", ix + 6, ty + 14, 9, OK);
            g.setColor(DIM);
            for (int j = 0; j < ls.size(); j++) g.drawString(ls.get(j), ix + 20, ty + 18 + j * 16);
            ty += 12 + ls.size() * 16;
        }
        Addons.Status s = status.get(a.key());
        if (s != null) {
            g.setFont(v.inter.deriveFont(12f));
            g.setColor(s.installable() ? OK : AMBER);
            List<String> ls = LauncherView.wrapLines(g, s.line(), iw, 2);
            for (int j = 0; j < ls.size(); j++) g.drawString(ls.get(j), ix, y + h - 92 + j * 16);
        }
        int by = y + h - 60;
        v.ghostButton(g, "c-addon-url:" + a.key(), ix, by, 150, 40, "Modrinth page", false, "external");
        boolean installed = "installed".equals(state.get(a.key())) || sel != null && v.installedProjects.contains(a.projectId());
        if (!installed && s != null && s.installable() && !"installing".equals(state.get(a.key())))
            v.smallPrimary(g, "c-addon-inst:" + a.key(), ix + iw - 140, by, 140, 40, "Install", "download");
    }

    // ------------------------------------------------------------------ clicks

    boolean click(String cmd, String arg, Launcher.Instance sel) {
        switch (cmd) {
            case "c-addon" -> page.sheet = "addon:" + arg;
            case "c-addon-recheck" -> { checkedFor = null; ensure(sel); }
            case "c-addon-url" -> { Addons.Addon a = Addons.byKey(arg); if (a != null) v.actions.openUrl(a.url()); }
            case "c-addon-inst" -> { Addons.Addon a = Addons.byKey(arg); if (a != null) install(sel, a); }
            default -> { return false; }
        }
        return true;
    }

    private void install(Launcher.Instance sel, Addons.Addon a) {
        if (sel == null || "installing".equals(state.get(a.key()))) return;
        Addons.Status s = status.get(a.key());
        if (s == null || !s.installable()) return;
        state.put(a.key(), "installing");
        progress = 0;
        page.sheet = null;
        work.submit(() -> {
            try {
                List<String> names = Addons.install(core, sel.dir, a, (f, m) -> SwingUtilities.invokeLater(() -> progress = f));
                String msg = "Installed " + a.name() + (names.size() > 1 ? " and " + (names.size() - 1) + " librar" + (names.size() == 2 ? "y" : "ies") : "") + " in " + sel.name;
                ui(() -> { state.put(a.key(), "installed"); v.toast(msg); });
                v.actions.showMods(sel);
            } catch (Exception e) {
                String msg = EvadeInstaller.friendly(e);
                ui(() -> state.put(a.key(), msg));
            }
        });
    }
}
