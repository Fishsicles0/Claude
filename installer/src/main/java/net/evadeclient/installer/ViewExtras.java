package net.evadeclient.installer;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static net.evadeclient.installer.LauncherView.*;

/**
 * Launcher-wide extras on top of {@link LauncherView}: the Appearance settings (theme, accent, text size, reduced
 * motion), keyboard shortcuts and names for screen readers, the Instances list with groups, pins and sorting, export
 * and import of an instance as a .zip, and the "What's new" and "Keyboard shortcuts" sheets. Click ids: {@code ap-*}
 * (appearance), {@code inst-sort}, {@code inst-import}, {@code inst-pin}, {@code inst-group}, {@code inst-export},
 * {@code ig:<group>} (fold a group) and {@code ov-wn-*} / {@code ov-keys-*} on the sheets.
 */
final class ViewExtras {
    final LauncherView v;
    /** The look and list preferences (defaults until {@link LauncherApp} loads the saved ones). */
    UiPrefs prefs = new UiPrefs();
    /** Saving is on in the real launcher (off for screenshots and tests). */
    boolean persist;
    private final ExecutorService work = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "launcher-instances");
        t.setDaemon(true);
        return t;
    });
    /** Screenshots: draw the focus ring without the window having the keyboard. */
    boolean alwaysShowFocus;
    /** Export / import running: what, and how far. */
    String busy;
    double busyProgress;

    ViewExtras(LauncherView v) { this.v = v; }

    void setPrefs(UiPrefs p) {
        prefs = p;
        LauncherTheme.apply(p);
        v.themeChanged();
    }

    private void changed() {
        LauncherTheme.apply(prefs);
        v.themeChanged();
        if (persist) prefs.save();
        v.repaint();
    }

    // ------------------------------------------------------------------ keyboard

    /** Window-wide shortcuts (they work from text fields too). */
    void installKeys() {
        int mod = net.evadeclient.clips.core.Ffmpeg.MAC ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
        Page[] pages = {Page.HOME, Page.INSTANCES, Page.MODS, Page.CLIPS, Page.GAME, Page.SETTINGS};
        for (int k = 0; k < pages.length; k++) {
            Page p = pages[k];
            key(KeyStroke.getKeyStroke(KeyEvent.VK_1 + k, mod), "page-" + k, () -> go(p));
        }
        key(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, mod), "page-settings", () -> go(Page.SETTINGS));
        String[] clips = {"library", "record", "editor", "settings"};
        for (int k = 0; k < clips.length; k++) {
            String id = clips[k];
            key(KeyStroke.getKeyStroke(KeyEvent.VK_1 + k, InputEvent.ALT_DOWN_MASK), "clips-" + id, () -> {
                if (id.equals("editor") && !(v.clipsHost instanceof net.evadeclient.clips.ui.ClipsPages cp && cp.editor.clip() != null)) return;
                go(Page.CLIPS);
                v.actions.clipsPage(id);
            });
        }
        key(KeyStroke.getKeyStroke(KeyEvent.VK_F, mod), "find", this::find);
        key(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "refresh", () -> v.actions.refresh(v.page));
        key(KeyStroke.getKeyStroke(KeyEvent.VK_P, mod), "play-key", () -> {
            if (v.modal != null || v.firstRun()) return;
            if (v.page != Page.HOME) go(Page.HOME);
            v.click("play");
        });
        key(KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), "keys", () -> { v.closeOverlays(); v.overlay = "keys"; v.repaint(); });
        // scrolling the list under the keyboard focus (or the page)
        bindFocused("PAGE_DOWN", "pgdn", () -> scroll(6));
        bindFocused("PAGE_UP", "pgup", () -> scroll(-6));
        bindFocused("DOWN", "down", () -> scroll(1));
        bindFocused("UP", "up", () -> scroll(-1));
    }

    private void key(KeyStroke ks, String name, Runnable r) {
        v.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, name);
        v.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        });
    }

    private void bindFocused(String ks, String name, Runnable r) {
        v.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(ks), name);
        v.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        });
    }

    private void go(Page p) {
        if (v.modal != null) return;
        v.showPage(p);
        v.getAccessibleContext().setAccessibleName(pageName(p) + " page");
    }

    static String pageName(Page p) {
        return switch (p) {
            case HOME -> "Home";
            case INSTANCES -> "Instances";
            case MODS -> "Mods";
            case CLIPS -> "Clips";
            case GAME -> "Game";
            case SETTINGS -> "Settings";
        };
    }

    /** Ctrl+F: the search box of the Mods page (its Browse box on Browse) or of the game log. */
    private void find() {
        if (v.modal != null) return;
        if (v.page == Page.GAME && "logs".equals(v.game.tab)) { SwingUtilities.invokeLater(v.game.search::requestFocusInWindow); return; }
        if (v.page != Page.MODS) go(Page.MODS);
        v.content.sheet = null;
        boolean browse = v.content.tab == ContentPage.Tab.BROWSE;
        if (v.content.tab != ContentPage.Tab.INSTALLED && !browse) v.content.tab = ContentPage.Tab.INSTALLED;
        v.repaint();
        javax.swing.JTextField f = browse ? v.content.browseField : v.content.filterField;
        // the field is placed by the next paint
        SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> { f.requestFocusInWindow(); f.selectAll(); }));
    }

    private void scroll(int steps) {
        Rectangle r = v.game.focusId != null ? v.hitMap.get(v.game.focusId) : null;
        java.awt.Point at = r != null ? new java.awt.Point(r.x + r.width / 2, r.y + r.height / 2) : new java.awt.Point(v.CX + v.CW / 2, H / 2);
        v.wheel(at, steps);
    }

    /** The controls Tab moves through, in the order they're painted (only what's clickable right now). */
    List<String> focusOrder() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Rectangle> e : v.hitMap.entrySet()) {
            String id = e.getKey();
            Rectangle r = e.getValue();
            if (id.equals("dismiss") || id.equals("c-dismiss") || id.startsWith("x-") || id.endsWith("-sw")) continue;
            if (r.width >= W - 10 && r.height >= H - 10) continue;
            out.add(id);
        }
        return out;
    }

    /** What a screen reader says for a control without a name of its own. */
    String labelFor(String id) {
        if (id == null) return "";
        switch (id) {
            case "nav-home": return "Home page";
            case "nav-instances": return "Instances page";
            case "nav-mods": return "Mods page";
            case "nav-clips": return "Clips page";
            case "nav-game": return "Game page";
            case "nav-settings": return "Settings page";
            case "close": return "Close the launcher";
            case "min": return "Minimise";
            case "bell": return "Notifications" + (v.unread > 0 ? ", " + v.unread + " unread" : "");
            case "status-pill": return "Clips status. Opens Clips";
            case "play": return "Play " + (v.selected() != null ? v.selected().name : "");
            case "slide-prev": return "Previous slide";
            case "slide-next": return "Next slide";
            case "side-instance": case "home-switch": case "mods-instance": return "Choose the instance";
            case "inst-sort": return "Sort instances, now " + sortName();
            case "inst-import": return "Import an instance: from another launcher or a zip";
            case "inst-pin": return v.focused() != null && prefs.pinned.contains(v.focused().id) ? "Unpin this instance" : "Pin this instance to the top";
            case "inst-group": return "Move this instance to a group";
            case "inst-export": return "Export this instance as a zip";
            case "ap-dark": return "Dark theme" + (prefs.light ? "" : ", selected");
            case "ap-light": return "Light theme" + (prefs.light ? ", selected" : "");
            case "ap-motion": return "Reduce motion, " + (prefs.reduceMotion ? "on" : "off");
            case "ap-keys": return "Show keyboard shortcuts";
            default: break;
        }
        if (id.startsWith("inst:")) {
            for (Launcher.Instance i : v.instances) if (i.id.equals(id.substring(5))) return i.name + ", instance" + (i.id.equals(v.settings.selected) ? ", plays next" : "");
        }
        if (id.startsWith("ig:")) return "Group " + id.substring(3) + ", " + (prefs.collapsed.contains(id.substring(3)) ? "folded. Press Enter to open" : "open. Press Enter to fold");
        if (id.startsWith("ap-accent:")) {
            int k = Integer.parseInt(id.substring(10));
            return LauncherTheme.ACCENT_NAMES[k] + " accent" + (prefs.accent == k ? ", selected" : "");
        }
        if (id.startsWith("ap-text:")) return "Text size " + id.substring(8) + " percent" + (id.endsWith(":" + prefs.textSize) ? ", selected" : "");
        if (id.startsWith("mem:")) return id.substring(4) + " GB memory";
        if (id.startsWith("pop:") && v.popup != null) for (String[] it : v.popup.items()) if (it[0].equals(id)) return it[1] + (it[2].isEmpty() ? "" : ", selected");
        if (id.startsWith("news:")) {
            int n = Integer.parseInt(id.substring(5));
            if (n < v.news.size()) return "News: " + v.news.get(n).title();
        }
        if (id.startsWith("server:")) {
            int n = Integer.parseInt(id.substring(7));
            if (n < v.settings.servers.size()) return "Server " + v.settings.servers.get(n).name();
        }
        // a readable form of the id: "c-wbackup" -> "wbackup", "inst-dup" -> "Dup"
        String s = id.replaceFirst("^(c|gp|ov|inst|set|s|nav|link|open|clips)-", "").replace('-', ' ').replace(':', ' ');
        return s.isEmpty() ? id : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ appearance (Settings)

    /** The height the Appearance card needs (compact: one line per row). */
    static int appearanceHeight(boolean compact) { return compact ? 132 : 200; }

    void paintAppearance(Graphics2D g, int x, int y, int w, int h) {
        boolean compact = h < appearanceHeight(false);
        v.card(g, x, y, w, h, false);
        int ix = x + 28, iw = w - 56;
        g.setFont(v.interBold.deriveFont(17f));
        g.setColor(TEXT);
        g.drawString("Appearance", ix, y + (compact ? 34 : 40));
        v.textLink(g, "ap-keys", "Keyboard shortcuts (F1)", ix + iw, y + (compact ? 34 : 40), 1);
        int colW = (iw - 32) / 2, c2 = ix + colW + 32;
        int ry = y + (compact ? 48 : 60), rh = compact ? 40 : 64;
        // row 1: theme, accent
        control(g, "Theme", ix, ry, colW, compact, (cx, cy, cw) ->
                segmented(g, new String[]{"ap-dark", "ap-light"}, new String[]{"Dark", "Light"}, prefs.light ? 1 : 0, cx, cy, cw, 32));
        control(g, "Accent", c2, ry, colW, compact, (cx, cy, cw) -> swatches(g, cx, cy, cw));
        ry += rh;
        control(g, "Text size", ix, ry, colW, compact, (cx, cy, cw) ->
                segmented(g, new String[]{"ap-text:100", "ap-text:115", "ap-text:130"}, new String[]{"100%", "115%", "130%"},
                        prefs.textSize == 130 ? 2 : prefs.textSize == 115 ? 1 : 0, cx, cy, cw, 32));
        control(g, "Reduce motion", c2, ry, colW, compact, (cx, cy, cw) -> {
            g.setFont(v.inter.deriveFont(12f));
            g.setColor(FAINT);
            if (!compact) g.drawString(LauncherView.ellipsizeEnd(g, "No slides or animations", cw - 56), cx, cy + 21);
            v.toggle(g, "ap-motion-sw", cx + cw - 40, cy + 6, prefs.reduceMotion, true);
            v.hit("ap-motion", new Rectangle(cx - 4, cy, cw + 8, 32));
        });
    }

    private interface Control { void paint(int x, int y, int w); }

    private void control(Graphics2D g, String label, int x, int y, int w, boolean compact, Control c) {
        if (compact) {
            g.setFont(v.interBold.deriveFont(13f));
            g.setColor(TEXT);
            g.drawString(label, x, y + 21);
            int lw = Math.min(110, g.getFontMetrics().stringWidth(label) + 14);
            c.paint(x + lw, y, w - lw);
        } else {
            v.label(g, label.toUpperCase(Locale.ROOT), x, y + 6);
            c.paint(x, y + 16, w);
        }
    }

    /** A segmented control: a dark track with the chosen part filled with the accent. */
    void segmented(Graphics2D g, String[] ids, String[] labels, int on, int x, int y, int w, int h) {
        Obsidian.segTrack(g, x, y, w, h);
        int sw = (w - 8) / ids.length;
        if (on >= 0 && on < ids.length) Obsidian.segThumb(g, x + 4 + v.spring("seg@" + ids[0], on, 20f) * sw, y + 4, sw, h - 8);
        for (int i = 0; i < ids.length; i++) {
            int sx = x + 4 + i * sw;
            float t = v.anim(ids[i], ids[i].equals(v.hovered));
            if (i != on && t > 0.01f) {
                g.setColor(new Color(255, 255, 255, Math.round(10 * t)));
                g.fill(Obsidian.rr(sx, y + 4, sw, h - 8, (h - 8) / 2.0));
            }
            g.setFont(v.interBold.deriveFont(12.5f));
            g.setColor(i == on ? TEXT : mix(FAINT, TEXT, t));
            center(g, labels[i], sx, y + 4, sw, h - 8);
            v.hit(ids[i], new Rectangle(sx, y + 4, sw, h - 8));
        }
    }

    /** The accent swatches, in their real colours (whatever the current accent). */
    private void swatches(Graphics2D g, int x, int y, int w) {
        int n = LauncherTheme.ACCENTS.length, s = 22, gap = Math.max(6, Math.min(14, (w - n * s) / Math.max(1, n - 1)));
        for (int k = 0; k < n; k++) {
            String id = "ap-accent:" + k;
            int sx = x + k * (s + gap), sy = y + 5;
            float t = v.anim(id, id.equals(v.hovered));
            int was = ThemedGraphics.raw(g, true);
            Color c = new Color(LauncherTheme.ACCENTS[k]);
            if (LauncherTheme.light()) c = new Color(LauncherTheme.mapArgbLightOnly(LauncherTheme.ACCENTS[k]));
            g.setColor(c);
            g.fill(new Ellipse2D.Double(sx - t, sy - t, s + 2 * t, s + 2 * t));
            ThemedGraphics.mode(g, was);
            if (prefs.accent == k) {
                g.setColor(TEXT);
                g.setStroke(new BasicStroke(2f));
                g.draw(new Ellipse2D.Double(sx - 4, sy - 4, s + 8, s + 8));
                g.setStroke(new BasicStroke(1f));
            }
            v.hit(id, new Rectangle(sx - 4, sy - 4, s + 8, s + 8));
        }
    }

    // ------------------------------------------------------------------ instances list

    String sortName() {
        for (int i = 0; i < UiPrefs.SORTS.length; i++) if (UiPrefs.SORTS[i].equals(prefs.sort)) return UiPrefs.SORT_NAMES[i];
        return UiPrefs.SORT_NAMES[0];
    }

    /** The list card's contents: sort and import on top, then the grouped, sorted list. */
    void paintInstanceList(Graphics2D g, int lx, int ly, int lw, int lh) {
        // toolbar
        int tw = lw - 24 - 36 - 8;
        float st = v.anim("inst-sort", "inst-sort".equals(v.hovered));
        g.setColor(mix(CARD_2, new Color(0x1E2129), st));
        g.fill(new RoundRectangle2D.Double(lx + 12, ly + 12, tw, 36, 22, 22));
        LauncherView.icon(g, "list", lx + 32, ly + 30, 14, DIM);
        g.setFont(v.inter.deriveFont(12.5f));
        g.setColor(DIM);
        g.drawString("Sort:", lx + 48, ly + 35);
        int sx = lx + 48 + g.getFontMetrics().stringWidth("Sort: ");
        g.setFont(v.interBold.deriveFont(12.5f));
        g.setColor(TEXT);
        g.drawString(sortName(), sx, ly + 35);
        LauncherView.icon(g, "chevron-down", lx + 12 + tw - 18, ly + 30, 9, DIM);
        v.hit("inst-sort", new Rectangle(lx + 12, ly + 12, tw, 36));
        iconTile(g, "inst-import", lx + lw - 12 - 36, ly + 12, 36, "download", busy != null && busy.startsWith("Import"));

        Rectangle area = new Rectangle(lx + 1, ly + 56, lw - 2, lh - 56 - 76);
        float off = v.scrollOf("instances");
        Shape was = g.getClip();
        g.clip(area);
        v.clip = area;
        int y = area.y + 4 - Math.round(off);
        Launcher.Instance foc = v.focused();
        for (InstanceOrganizer.Row row : rows()) {
            if (row.isHeader()) {
                String id = "ig:" + row.header();
                float t = v.anim(id, id.equals(v.hovered));
                if (t > 0.01f) {
                    g.setColor(new Color(255, 255, 255, Math.round(8 * t)));
                    g.fill(new RoundRectangle2D.Double(lx + 12, y, lw - 24, 30, 16, 16));
                }
                Graphics2D q = (Graphics2D) g.create();
                q.translate(lx + 30, y + 15);
                if (row.collapsed()) q.rotate(-Math.PI / 2);
                LauncherView.icon(q, "chevron-down", 0, 0, 9, mix(FAINT, TEXT, t));
                q.dispose();
                if (row.header().equals(InstanceOrganizer.PINNED)) star(g, lx + 48, y + 15, 10, AMBER, true);
                int hx = lx + (row.header().equals(InstanceOrganizer.PINNED) ? 60 : 44);
                g.setFont(v.interBold.deriveFont(10.5f));
                g.setColor(mix(DIM, TEXT, t));
                String hs = LauncherView.ellipsizeEnd(g, row.header().toUpperCase(Locale.ROOT), lw - 140);
                v.spaced(g, hs, hx, y + 19, 1.2f);
                g.setFont(v.inter.deriveFont(11f));
                g.setColor(FAINT);
                String cnt = String.valueOf(row.count());
                g.drawString(cnt, lx + lw - 28 - g.getFontMetrics().stringWidth(cnt), y + 19);
                v.hit(id, new Rectangle(lx + 12, y, lw - 24, 30));
                y += 34;
                continue;
            }
            Launcher.Instance i = row.instance();
            String id = "inst:" + i.id;
            boolean on = i == foc;
            float t = v.anim(id, id.equals(v.hovered));
            float sel = v.anim(id + "-sel", on);
            if (t > 0.01f && sel < 0.99f) {
                g.setColor(Obsidian.alpha(Obsidian.N250, .8f * t * (1 - sel)));
                g.fill(Obsidian.rr(lx + 12, y, lw - 24, 64, Obsidian.R_SM + 2));
            }
            Obsidian.selected(g, lx + 12, y, lw - 24, 64, Obsidian.R_SM + 2, sel);
            v.instanceTile(g, lx + 28, y + 12, 40, i);
            g.setFont(v.interBold.deriveFont(14f));
            g.setColor(TEXT);
            boolean playing = i.id.equals(v.settings.selected), pinned = prefs.pinned.contains(i.id);
            String nm = LauncherView.ellipsizeEnd(g, i.name, lw - 140 - (playing ? 64 : 0) - (pinned ? 18 : 0));
            g.drawString(nm, lx + 80, y + 29);
            int px = lx + 80 + g.getFontMetrics().stringWidth(nm) + 8;
            if (pinned) { star(g, px + 6, y + 24, 11, AMBER, true); px += 18; }
            if (playing) {
                g.setFont(v.interBold.deriveFont(9f));
                int pw = g.getFontMetrics().stringWidth("ACTIVE") + 14;
                g.setColor(new Color(225, 29, 46, 44));
                g.fill(new RoundRectangle2D.Double(px, y + 17, pw, 16, 16, 16));
                g.setColor(RED_HI);
                center(g, "ACTIVE", px, y + 17, pw, 16);
            }
            g.setFont(v.inter.deriveFont(12f));
            g.setColor(DIM);
            String sub = (i.installed != null ? "v" + i.installed.evadeVersion() : "Not installed yet") + "  ·  " + i.modCount + " mods  ·  " + i.memoryGb + " GB" + v.game.playtimeShort(i);
            g.drawString(LauncherView.ellipsizeEnd(g, sub, lw - 130), lx + 80, y + 48);
            if (!v.game.rowPlaying(g, lx + lw - 40, y + 35, i)) v.statusDot(g, lx + lw - 40, y + 35, i);
            v.hit(id, new Rectangle(lx + 12, y, lw - 24, 64));
            y += 70;
        }
        int content = y + Math.round(off) - (area.y + 4);
        g.setClip(was);
        v.clip = null;
        v.scrollArea(g, "instances", area, content);
        if (busy != null) {
            int by = ly + lh - 76 + 4;
            g.setFont(v.inter.deriveFont(11.5f));
            g.setColor(DIM);
            g.drawString(LauncherView.ellipsizeEnd(g, busy, lw - 40), lx + 20, by + 2);
            v.progressBar(g, lx + 20, by + 8, lw - 40, 4, Math.max(0.03, Math.min(1, busyProgress)));
            v.animating = true;
        }
    }

    List<InstanceOrganizer.Row> rows() {
        return InstanceOrganizer.rows(v.instances, prefs, i -> v.game.info(i).playtime.lastPlayed(), i -> v.game.info(i).playtime.totalMs());
    }

    /** Pin and group buttons in the instance's header, ending at {@code right}. */
    void paintInstanceTools(Graphics2D g, Launcher.Instance i, int right, int y) {
        boolean pinned = prefs.pinned.contains(i.id);
        iconTile(g, "inst-group", right - 40, y, 40, "folder", prefs.groups.containsKey(i.id));
        float t = v.anim("inst-pin", "inst-pin".equals(v.hovered));
        g.setColor(mix(new Color(0x1A1D23), new Color(0x23272F), t));
        g.fill(new RoundRectangle2D.Double(right - 88, y, 40, 40, 24, 24));
        star(g, right - 68, y + 20, 15, pinned ? AMBER : mix(DIM, TEXT, t), pinned);
        v.hit("inst-pin", new Rectangle(right - 88, y, 40, 40));
        String grp = prefs.groups.get(i.id);
        if (grp != null) {
            g.setFont(v.inter.deriveFont(11.5f));
            g.setColor(FAINT);
            String s = LauncherView.ellipsizeEnd(g, "in " + grp, 160);
            g.drawString(s, right - 88 - 10 - g.getFontMetrics().stringWidth(s), y + 25);
        }
    }

    private void iconTile(Graphics2D g, String id, int x, int y, int s, String icon, boolean on) {
        float t = v.anim(id, id.equals(v.hovered));
        g.setColor(on ? new Color(225, 29, 46, 44) : mix(new Color(0x1A1D23), new Color(0x23272F), t));
        g.fill(new RoundRectangle2D.Double(x, y, s, s, 24, 24));
        LauncherView.icon(g, icon, x + s / 2, y + s / 2, 15, on ? RED_HI : mix(DIM, TEXT, t));
        v.hit(id, new Rectangle(x, y, s, s));
    }

    /** A five-point star (pins). */
    static void star(Graphics2D g, double cx, double cy, double s, Color c, boolean filled) {
        Path2D p = new Path2D.Double();
        double ro = s / 2, ri = ro * 0.45;
        for (int k = 0; k < 10; k++) {
            double r = k % 2 == 0 ? ro : ri, a = -Math.PI / 2 + k * Math.PI / 5;
            double px = cx + r * Math.cos(a), py = cy + r * Math.sin(a) + s * 0.05;
            if (k == 0) p.moveTo(px, py); else p.lineTo(px, py);
        }
        p.closePath();
        g.setColor(c);
        if (filled) g.fill(p);
        else {
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(p);
            g.setStroke(new BasicStroke(1f));
        }
    }

    // ------------------------------------------------------------------ sheets

    void paintWhatsNew(Graphics2D g) {
        veil(g);
        int w = 560, x = (W - w) / 2, ix = x + 32, iw = w - 64;
        g.setFont(v.inter.deriveFont(12.5f));
        List<List<String>> bodies = new ArrayList<>();
        int h = 84 + 76;
        for (String[] it : StartupChecks.WHATS_NEW) {
            List<String> l = LauncherView.wrapLines(g, it[1], iw - 30, 3);
            bodies.add(l);
            h += 24 + l.size() * 17 + 10;
        }
        int y = Math.max(20, (H - h) / 2);
        v.raised(g, x, y, w, h);
        v.label(g, "EVADE LAUNCHER " + v.version, ix, y + 34);
        g.setFont(v.interBold.deriveFont(21f));
        g.setColor(TEXT);
        g.drawString("What's new", ix, y + 64);
        int ly = y + 92;
        for (int k = 0; k < StartupChecks.WHATS_NEW.length; k++) {
            g.setColor(RED);
            g.fill(new Ellipse2D.Double(ix + 2, ly + 6, 8, 8));
            g.setFont(v.interBold.deriveFont(13.5f));
            g.setColor(TEXT);
            g.drawString(StartupChecks.WHATS_NEW[k][0], ix + 22, ly + 15);
            ly += 24;
            g.setFont(v.inter.deriveFont(12.5f));
            g.setColor(DIM);
            for (String l : bodies.get(k)) { g.drawString(l, ix + 22, ly + 12); ly += 17; }
            ly += 10;
        }
        v.textLink(g, "ov-keys-open", "Keyboard shortcuts", ix, y + h - 34, 0);
        v.smallPrimary(g, "ov-wn-ok", x + w - 32 - 120, y + h - 60, 120, 40, "Got it", null);
        if (v.game.focusId == null || !v.hitMap.containsKey(v.game.focusId)) v.game.focusId = "ov-wn-ok";
    }

    static final String[][] SHORTCUTS = {
            {"Ctrl+1 … 6", "Home, Instances, Mods, Clips, Game, Settings"},
            {"Ctrl+P", "Play the selected instance"},
            {"Ctrl+F", "Search mods (or the game log)"},
            {"F5", "Refresh this page"},
            {"Tab / Shift+Tab", "Move between controls"},
            {"Enter / Space", "Press the focused control"},
            {"Page Up / Down", "Scroll the list"},
            {"Escape", "Close a menu, dialog or sheet"},
            {"Alt+1 … 4", "Clips: Library, Record, Editor, Settings"},
            {"F1", "This list"}};

    void paintKeys(Graphics2D g) {
        veil(g);
        int w = 520, rh = 34, h = 96 + SHORTCUTS.length * rh + 70, x = (W - w) / 2, y = Math.max(20, (H - h) / 2), ix = x + 32, iw = w - 64;
        v.raised(g, x, y, w, h);
        g.setFont(v.interBold.deriveFont(21f));
        g.setColor(TEXT);
        g.drawString("Keyboard shortcuts", ix, y + 50);
        g.setFont(v.inter.deriveFont(12.5f));
        g.setColor(DIM);
        g.drawString(net.evadeclient.clips.core.Ffmpeg.MAC ? "On a Mac, use Cmd for Ctrl." : "Shortcuts work from anywhere in the launcher.", ix, y + 74);
        int ry = y + 96;
        for (String[] s : SHORTCUTS) {
            g.setFont(v.interBold.deriveFont(12f));
            int kw = g.getFontMetrics().stringWidth(s[0]) + 18;
            g.setColor(new Color(0x21252D));
            g.fill(new RoundRectangle2D.Double(ix, ry + 4, kw, 24, 10, 10));
            g.setColor(TEXT);
            center(g, s[0], ix, ry + 4, kw, 24);
            g.setFont(v.inter.deriveFont(13f));
            g.setColor(DIM);
            g.drawString(LauncherView.ellipsizeEnd(g, s[1], iw - 150), ix + 150, ry + 21);
            ry += rh;
        }
        v.smallPrimary(g, "ov-keys-close", x + w - 32 - 120, y + h - 60, 120, 40, "Close", null);
        if (v.game.focusId == null || !v.hitMap.containsKey(v.game.focusId)) v.game.focusId = "ov-keys-close";
    }

    private void veil(Graphics2D g) {
        g.setColor(new Color(4, 5, 7, 150));
        if (v.roundCorners) g.fill(new RoundRectangle2D.Double(0, 0, W, H, 22, 22));
        else g.fillRect(0, 0, W, H);
    }

    /** Opens "What's new" (and remembers it was seen). */
    void showWhatsNew() {
        v.closeOverlays();
        v.overlay = "whatsnew";
        prefs.seenVersion = v.version;
        if (persist) prefs.save();
        v.repaint();
    }

    // ------------------------------------------------------------------ clicks

    /** Handles this class's controls; false for anything else. */
    boolean click(String id) {
        Launcher.Instance foc = v.focused();
        switch (id) {
            case "ap-dark" -> { prefs.light = false; changed(); }
            case "ap-light" -> { prefs.light = true; changed(); }
            case "ap-motion" -> { prefs.reduceMotion = !prefs.reduceMotion; changed(); }
            case "ap-keys", "ov-keys-open" -> { v.overlay = "keys"; v.game.focusId = null; }
            case "ov-wn-ok", "ov-keys-close" -> { v.overlay = null; v.game.focusId = null; }
            case "inst-sort" -> {
                List<String[]> items = new ArrayList<>();
                for (int k = 0; k < UiPrefs.SORTS.length; k++)
                    items.add(new String[]{"pop:isort:" + UiPrefs.SORTS[k], UiPrefs.SORT_NAMES[k], UiPrefs.SORTS[k].equals(prefs.sort) ? "✓" : ""});
                popupBelow(id, items, 240);
            }
            case "inst-import" -> popupBelow(id, List.of(new String[]{"pop:tl:import", "From another launcher…", ""},
                    new String[]{"pop:imp-zip", "An Evade instance (.zip)…", ""}), 260);
            case "inst-pin" -> {
                if (foc == null) return true;
                if (!prefs.pinned.remove(foc.id)) prefs.pinned.add(foc.id);
                changed();
            }
            case "inst-group" -> {
                if (foc == null) return true;
                String cur = prefs.groups.get(foc.id);
                List<String[]> items = new ArrayList<>();
                for (String gname : InstanceOrganizer.groupNames(prefs)) items.add(new String[]{"pop:igrp:" + gname, gname, gname.equals(cur) ? "✓" : ""});
                items.add(new String[]{"pop:igrp-new", "New group…", ""});
                if (cur != null) items.add(new String[]{"pop:igrp-none", "Take out of “" + cur + "”", ""});
                popupBelow(id, items, 260);
            }
            case "inst-export" -> {
                if (foc == null) return true;
                popupBelow(id, List.of(new String[]{"pop:iexp:worlds", "Export with worlds", ""},
                        new String[]{"pop:iexp:noworlds", "Export without worlds (smaller)", ""}), 260);
            }
            default -> {
                if (id.startsWith("ap-accent:")) { prefs.accent = Integer.parseInt(id.substring(10)); changed(); }
                else if (id.startsWith("ap-text:")) { prefs.textSize = Integer.parseInt(id.substring(8)); changed(); }
                else if (id.startsWith("ig:")) {
                    String gname = id.substring(3);
                    if (!prefs.collapsed.remove(gname)) prefs.collapsed.add(gname);
                    changed();
                } else return false;
            }
        }
        return true;
    }

    private void popupBelow(String id, List<String[]> items, int w) {
        Rectangle r = v.hitMap.get(id);
        if (r == null) return;
        int pw = Math.max(w, r.width), ph = items.size() * 38 + 12;
        int px = Math.min(W - 28 - pw, r.x), py = r.y + r.height + 6;
        if (py + ph > H - 12) py = r.y - 6 - ph;
        v.popup = new LauncherView.Popup(id, px, Math.max(12, py), pw, items);
    }

    /** A popup item of this class ("isort:", "igrp:", "iexp:"); false for anything else. */
    boolean popup(String what) {
        Launcher.Instance foc = v.focused();
        if (what.startsWith("isort:")) { prefs.sort = what.substring(6); changed(); return true; }
        if (what.equals("imp-zip")) { importZip(); return true; }
        if (what.equals("igrp-new")) {
            if (foc != null) v.openModal(new LauncherView.Modal("group", "New group", "Groups keep the Instances list tidy; fold one away with its arrow.", "Create", foc, null), "");
            return true;
        }
        if (what.equals("igrp-none")) { if (foc != null) { prefs.groups.remove(foc.id); changed(); } return true; }
        if (what.startsWith("igrp:")) { if (foc != null) setGroup(foc, what.substring(5)); return true; }
        if (what.startsWith("iexp:")) { if (foc != null) exportZip(foc, what.equals("iexp:worlds")); return true; }
        return false;
    }

    void setGroup(Launcher.Instance i, String name) {
        String g = UiPrefs.cleanGroup(name);
        if (g == null) return;
        if (g.equalsIgnoreCase(InstanceOrganizer.PINNED) || g.equalsIgnoreCase("Other")) g = g + " group";
        for (String have : InstanceOrganizer.groupNames(prefs)) if (have.equalsIgnoreCase(g)) g = have;
        prefs.groups.put(i.id, g);
        prefs.collapsed.remove(g);
        changed();
    }

    /** After the instance list was reloaded: forget pins and groups of instances that are gone. */
    void instancesReloaded() {
        if (!v.instances.isEmpty() && InstanceOrganizer.prune(prefs, v.instances) && persist) prefs.save();
    }

    // ------------------------------------------------------------------ export / import

    private void exportZip(Launcher.Instance i, boolean worlds) {
        if (busy != null) { v.toast("Wait for the " + busy.toLowerCase(Locale.ROOT) + " to finish"); return; }
        if (v.game.running(i)) { v.toast(i.name + " is running. Close the game first."); return; }
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Export " + i.name);
        fc.setSelectedFile(new java.io.File(System.getProperty("user.home"), InstanceZip.fileName(i)));
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Evade instance (*.zip)", "zip"));
        if (fc.showSaveDialog(SwingUtilities.getWindowAncestor(v)) != JFileChooser.APPROVE_OPTION) return;
        Path out = fc.getSelectedFile().toPath();
        if (!out.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) out = out.resolveSibling(out.getFileName() + ".zip");
        Path dest = out;
        busy = "Exporting " + i.name;
        busyProgress = 0;
        v.repaint();
        work.submit(() -> {
            try {
                int n = InstanceZip.export(i, dest, worlds, (f, m) -> SwingUtilities.invokeLater(() -> busyProgress = f));
                ui(() -> {
                    v.toast("Exported " + i.name + ": " + n + " files");
                    v.notice("Exported " + i.name, dest + (worlds ? " (with worlds)" : " (without worlds)") + ". Evade's own files reinstall when it's imported.", false);
                });
            } catch (Exception e) {
                ui(() -> v.toast("Couldn't export: " + EvadeInstaller.friendly(e)));
            } finally {
                ui(() -> busy = null);
            }
        });
    }

    private void importZip() {
        if (busy != null) { v.toast("Wait for the " + busy.toLowerCase(Locale.ROOT) + " to finish"); return; }
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Import an Evade instance (.zip)");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Evade instance (*.zip)", "zip"));
        if (fc.showOpenDialog(SwingUtilities.getWindowAncestor(v)) != JFileChooser.APPROVE_OPTION) return;
        Path f = fc.getSelectedFile().toPath();
        busy = "Importing " + f.getFileName();
        busyProgress = 0;
        v.repaint();
        List<Launcher.Instance> all = List.copyOf(v.instances);
        work.submit(() -> {
            try {
                InstanceZip.Result r = InstanceZip.importZip(v.settings, all, f, (p, m) -> SwingUtilities.invokeLater(() -> busyProgress = p));
                ui(() -> {
                    v.actions.instancesChanged();
                    v.focusedId = r.instance().id;
                    v.toast("Imported " + r.instance().name);
                    v.notice("New instance: " + r.instance().name, "Imported from " + f.getFileName() + ". Press Play to install Evade in it.", false);
                });
            } catch (Exception e) {
                ui(() -> v.notice("Couldn't import " + f.getFileName(), EvadeInstaller.friendly(e), true));
            } finally {
                ui(() -> busy = null);
            }
        });
    }

    private void ui(Runnable r) { SwingUtilities.invokeLater(() -> { r.run(); v.repaint(); }); }
}
