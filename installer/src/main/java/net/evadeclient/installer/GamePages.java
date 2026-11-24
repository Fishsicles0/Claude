package net.evadeclient.installer;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.ActionEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.evadeclient.installer.LauncherView.AMBER;
import static net.evadeclient.installer.LauncherView.BORDER;
import static net.evadeclient.installer.LauncherView.DIM;
import static net.evadeclient.installer.LauncherView.FAINT;
import static net.evadeclient.installer.LauncherView.FIELD;
import static net.evadeclient.installer.LauncherView.H;
import static net.evadeclient.installer.LauncherView.HAIRLINE;
import static net.evadeclient.installer.LauncherView.OK;
import static net.evadeclient.installer.LauncherView.RAISED;
import static net.evadeclient.installer.LauncherView.RED;
import static net.evadeclient.installer.LauncherView.RED_HI;
import static net.evadeclient.installer.LauncherView.TEXT;
import static net.evadeclient.installer.LauncherView.W;
import static net.evadeclient.installer.LauncherView.mix;

/**
 * The launcher's game-side views, painted in the same hand-drawn style as {@link LauncherView} (whose drawing helpers
 * they use): the Game page (an instance's status and play time, its logs, its advanced settings), the "Playing" and
 * crash states on Home and in the instance list, the Join button on a server card, a confirm dialog, the Undo toast
 * after deleting an instance, and keyboard access to all of it (Tab / Shift+Tab move a visible focus ring, Enter or
 * Space press, and each control has an accessible name). Every id here starts with "gp-" and comes back to
 * {@link #click}.
 */
final class GamePages {
    /** What the controls do ({@link GameController} implements it). */
    interface Actions {
        default void stopGame(Launcher.Instance i) {}
        default void joinServer(Launcher.Server s) {}
        default void continueWorld(Launcher.Instance i) {}
        default void cancelRequest(Launcher.Instance i) {}
        default void fix(Launcher.Instance i, CrashHelper.Diagnosis d, boolean alt) {}
        default void dismissCrash(Launcher.Instance i) {}
        default void openReport(Path p) {}
        default void openLog(Launcher.Instance i, Path file) {}
        default void copyLog() {}
        default void shareLog() {}
        default void extrasChanged(Launcher.Instance i, InstanceExtras x) {}
        default void browseJava(Launcher.Instance i) {}
        default void undoDelete() {}
        default void osNotifyChanged(boolean on) {}
        default void tabShown(String tab) {}
    }

    /** What's known about one instance's game (filled in by the controller). */
    static final class Info {
        GameState.Run run = GameState.Run.NONE;
        GameState.Session session;
        GameState.Playtime playtime = GameState.Playtime.NONE;
        InstanceExtras extras = new InstanceExtras();
        CrashHelper.Diagnosis crash;
        /** A Join / Continue waiting for the game: {type, name, created}. */
        String requestName;
        long requestAt;
        String[] lastWorld;
        List<Path> reports = List.of();

        boolean running() { return run == GameState.Run.RUNNING || run == GameState.Run.NOT_RESPONDING; }
    }

    final LauncherView v;
    Actions actions = new Actions() {};
    final Map<String, Info> info = new HashMap<>();
    /** The Game page's tab: "overview", "logs" or "settings". */
    String tab = "overview";
    boolean osNotify = true;

    // logs
    List<Path> logFiles = List.of();
    Path logFile;
    List<LogTools.Line> logLines = List.of();
    String logError;
    int logFilter;
    boolean follow = true;
    private List<LogTools.Line> filtered = List.of();
    private Object filterKey;
    /** Where the log list was scrolled to last frame (and its length), to notice the player scrolling up. */
    private float lastMax;
    String javaCheck;
    List<String> argsNotes = List.of();

    // dialogs and toasts
    record Confirm(String title, String body, String ok, boolean danger, Runnable run) {}
    Confirm confirm;
    String undoText;
    long undoAt;

    // keyboard
    String focusId;
    private final Map<String, String> names = new LinkedHashMap<>();
    private List<String> order = List.of();

    final JTextField search, java, args, notes;
    private boolean syncing;
    private final Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    GamePages(LauncherView v) {
        this.v = v;
        search = field("Search this log");
        java = field("Java for this instance");
        args = field("Extra Java arguments");
        notes = field("Notes");
        search.getDocument().addDocumentListener(onChange(v::repaint));
        java.getDocument().addDocumentListener(onChange(() -> edit(x -> x.javaDir = java.getText().strip())));
        args.getDocument().addDocumentListener(onChange(() -> edit(x -> x.javaArgs = args.getText().strip())));
        notes.getDocument().addDocumentListener(onChange(() -> edit(x -> x.notes = notes.getText().strip())));
        keyboard();
        v.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) { keyNav = false; }
        });
    }

    private JTextField field(String name) {
        JTextField f = new JTextField();
        f.setOpaque(false);
        f.setBorder(new EmptyBorder(0, 0, 0, 0));
        f.setForeground(TEXT);
        f.setCaretColor(RED_HI);
        f.setSelectionColor(new Color(225, 29, 46, 110));
        f.setSelectedTextColor(Color.WHITE);
        f.setFont(v.inter.deriveFont(13f));
        f.setVisible(false);
        f.getAccessibleContext().setAccessibleName(name);
        f.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ESCAPE"), "gp-escape");
        f.getActionMap().put("gp-escape", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { v.requestFocusInWindow(); }
        });
        v.add(f);
        return f;
    }

    private static DocumentListener onChange(Runnable r) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }

    private void edit(java.util.function.Consumer<InstanceExtras> f) {
        if (syncing) return;
        Launcher.Instance i = v.focused();
        if (i == null) return;
        InstanceExtras x = info(i).extras;
        f.accept(x);
        List<String> n = new ArrayList<>();
        InstanceExtras.cleanArgs(x.javaArgs, n);
        argsNotes = n;
        actions.extrasChanged(i, x);
        v.repaint();
    }

    /** Puts the focused instance's settings in the text fields (on switching instance or opening the tab). */
    void syncFields(Launcher.Instance i) {
        if (i == null) return;
        InstanceExtras x = info(i).extras;
        syncing = true;
        if (!java.getText().equals(x.javaDir)) java.setText(x.javaDir);
        if (!args.getText().equals(x.javaArgs)) args.setText(x.javaArgs);
        if (!notes.getText().equals(x.notes)) notes.setText(x.notes);
        syncing = false;
        List<String> n = new ArrayList<>();
        InstanceExtras.cleanArgs(x.javaArgs, n);
        argsNotes = n;
    }

    Info info(Launcher.Instance i) {
        return i == null ? new Info() : info.computeIfAbsent(i.id, k -> new Info());
    }

    boolean running(Launcher.Instance i) { return i != null && info(i).running(); }

    // ------------------------------------------------------------------ keyboard and names

    private void keyboard() {
        v.setFocusTraversalKeysEnabled(false);
        bind("TAB", "gp-next", () -> moveFocus(1));
        bind("shift TAB", "gp-prev", () -> moveFocus(-1));
        bind("ENTER", "gp-press", this::press);
        bind("SPACE", "gp-press2", this::press);
        Action esc = v.getActionMap().get("escape");
        bind("ESCAPE", "escape", () -> {
            if (confirm != null) { confirm = null; v.repaint(); }
            else if (esc != null) esc.actionPerformed(null);
        });
    }

    private void bind(String key, String name, Runnable r) {
        v.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name);
        v.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { r.run(); }
        });
    }

    /** Names the control {@code id} for screen readers and makes it reachable with Tab. */
    void name(String id, String label) { names.put(id, label); }

    private void moveFocus(int d) {
        List<String> ids = new ArrayList<>();
        for (String id : order) if (v.hitMap.containsKey(id)) ids.add(id);
        if (ids.isEmpty()) { if (d > 0) v.transferFocus(); else v.transferFocusBackward(); return; }
        int at = ids.indexOf(focusId);
        int n = at < 0 ? (d > 0 ? 0 : ids.size() - 1) : Math.floorMod(at + d, ids.size());
        focusId = ids.get(n);
        keyNav = true;
        String label = names.getOrDefault(focusId, focusId);
        v.getAccessibleContext().setAccessibleName(label);
        v.getAccessibleContext().setAccessibleDescription(label + ". Press Enter to activate.");
        v.repaint();
    }

    private void press() {
        if (focusId == null || !v.hitMap.containsKey(focusId)) return;
        v.click(focusId);
    }

    /** Called at the start of every frame: the names gathered last frame become the Tab order. */
    void frameStart() {
        order = new ArrayList<>(names.keySet());
        names.clear();
    }

    // ------------------------------------------------------------------ the Game page

    /** Paints the Game page. Returns nothing: the text fields are placed here too. */
    void paintPage(Graphics2D g) {
        Launcher.Instance i = v.focused();
        v.pageTitle(g, "Game", "Status, logs, crash help and advanced settings for one instance.");
        // instance picker (the same menu as on the Mods page)
        g.setFont(v.interBold.deriveFont(12.5f));
        String nm = i != null ? i.name : "—";
        int pw = Math.min(220, g.getFontMetrics().stringWidth(nm) + 74), px = v.CX + v.CW - pw;
        float t = v.anim("mods-instance", "mods-instance".equals(v.hovered));
        g.setColor(mix(LauncherView.CARD_2, new Color(0x202028), t));
        g.fill(new RoundRectangle2D.Double(px, 96, pw, 40, 24, 24));
        LauncherView.icon(g, "layers", px + 22, 116, 15, DIM);
        g.setColor(TEXT);
        g.drawString(LauncherView.ellipsizeEnd(g, nm, pw - 70), px + 40, 121);
        LauncherView.icon(g, "chevron-down", px + pw - 20, 116, 9, DIM);
        v.hit("mods-instance", new Rectangle(px, 96, pw, 40));
        name("mods-instance", "Instance: " + nm + ". Choose another");

        int ty = 100;
        String[][] tabs = {{"overview", "Overview"}, {"logs", "Logs"}, {"settings", "Settings"}};
        int tx = v.CX;
        for (String[] tb : tabs) {
            String id = "gp-tab:" + tb[0];
            v.tab(g, id, tx, ty, tb[1], -1, tab.equals(tb[0]));
            name(id, tb[1] + " tab" + (tab.equals(tb[0]) ? ", selected" : ""));
            g.setFont(v.interBold.deriveFont(13.5f));
            tx += g.getFontMetrics().stringWidth(tb[1]) + 40;
        }
        g.setColor(HAIRLINE);
        g.fillRect(v.CX, ty + 38, v.CW, 1);
        int y = ty + 52, h = H - y - 24;
        Rectangle searchBox = null, javaBox = null, argsBox = null, notesBox = null;
        if (i == null) {
            v.card(g, v.CX, y, v.CW, h, false);
        } else switch (tab) {
            case "logs" -> searchBox = paintLogs(g, i, v.CX, y, v.CW, h);
            case "settings" -> {
                Rectangle[] r = paintSettings(g, i, v.CX, y, v.CW, h);
                javaBox = r[0];
                argsBox = r[1];
                notesBox = r[2];
            }
            default -> paintOverview(g, i, v.CX, y, v.CW, h);
        }
        place(search, searchBox);
        place(java, javaBox);
        place(args, argsBox);
        place(notes, notesBox);
    }

    /** Hides this page's text fields (another page is showing). */
    void hideFields() {
        place(search, null);
        place(java, null);
        place(args, null);
        place(notes, null);
    }

    private void place(JTextField f, Rectangle r) {
        boolean show = r != null && confirm == null && v.modal == null;
        if (show) {
            float k = v.uiScale;
            Rectangle s = new Rectangle(Math.round(r.x * k), Math.round(r.y * k), Math.round(r.width * k), Math.round(r.height * k));
            if (!s.equals(f.getBounds())) f.setBounds(s);
            Font want = v.inter.deriveFont(13f * k);
            if (!want.equals(f.getFont())) f.setFont(want);
        }
        if (f.isVisible() != show) f.setVisible(show);
    }

    // ---- overview

    private void paintOverview(Graphics2D g, Launcher.Instance i, int x, int y, int w, int h) {
        Info in = info(i);
        int lw = (w - 24) * 55 / 100, rw = w - 24 - lw;
        v.card(g, x, y, lw, h, false);
        int ix = x + 28, iw = lw - 56;
        // status
        String state;
        Color sc;
        String line2;
        long now = System.currentTimeMillis();
        GameState.Session s = in.session;
        switch (in.run) {
            case RUNNING -> {
                state = "Playing";
                sc = OK;
                line2 = where(s) + "  ·  for " + GameState.duration(now - s.started());
            }
            case NOT_RESPONDING -> {
                state = "Not responding";
                sc = AMBER;
                line2 = "No sign of life from the game for " + GameState.duration(now - s.beat()) + ". Stop it if it's stuck.";
            }
            default -> {
                state = "Not running";
                sc = FAINT;
                line2 = s == null ? "Play time and status show here once you've played this instance with Evade."
                        : "Last session " + (in.run == GameState.Run.ENDED_BADLY ? "ended unexpectedly" : "ended normally") + ", "
                        + GameState.when(s.ended() > 0 ? s.ended() : s.beat(), now) + ".";
            }
        }
        g.setFont(v.interBold.deriveFont(10f));
        g.setColor(FAINT);
        v.spaced(g, "STATUS", ix, y + 38, 1.4f);
        g.setColor(new Color(sc.getRed(), sc.getGreen(), sc.getBlue(), 40));
        g.fill(new Ellipse2D.Double(ix - 2, y + 56, 20, 20));
        float pulse = in.run == GameState.Run.RUNNING ? (float) (0.65 + 0.35 * Math.sin(now / 320.0)) : 1f;
        if (in.run == GameState.Run.RUNNING) v.animating = true;
        g.setColor(new Color(sc.getRed(), sc.getGreen(), sc.getBlue(), Math.round(255 * pulse)));
        g.fill(new Ellipse2D.Double(ix + 3, y + 61, 10, 10));
        g.setFont(v.interBold.deriveFont(26f));
        g.setColor(TEXT);
        g.drawString(state, ix + 28, y + 76);
        g.setFont(v.inter.deriveFont(13f));
        g.setColor(DIM);
        int ly = y + 104;
        for (String l : LauncherView.wrapLines(g, line2, iw, 2)) { g.drawString(l, ix, ly); ly += 19; }
        if (s != null && in.running()) {
            g.setFont(v.inter.deriveFont(11.5f));
            g.setColor(FAINT);
            g.drawString("Evade " + (s.version() == null ? "" : s.version()) + "  ·  process " + s.pid(), ix, ly + 2);
        }
        // buttons
        int by = y + 160;
        if (in.running()) {
            ghost(g, "gp-stop", ix, by, 150, 40, "Stop game", true, "close-x", "Stop the game (asks first)");
        } else {
            boolean world = in.lastWorld != null && i.installed != null;
            if (world) primary(g, "gp-continue", ix, by, 220, 40, "Continue " + in.lastWorld[1], "play", "Continue last world " + in.lastWorld[1]);
            else {
                g.setFont(v.inter.deriveFont(12f));
                g.setColor(FAINT);
                g.drawString(i.installed == null ? "Press Play once to install Evade in this instance." : "No singleplayer worlds yet.", ix, by + 25);
            }
        }
        ghost(g, "gp-folder", ix + iw - 130, by, 130, 40, "Open folder", false, "folder", "Open the instance folder");
        if (in.requestName != null) {
            int ry = by + 54;
            g.setColor(new Color(225, 29, 46, 26));
            g.fill(new RoundRectangle2D.Double(ix, ry, iw, 44, 22, 22));
            long left = Math.max(0, GameState.REQUEST_TTL_MS - (now - in.requestAt)) / 1000;
            g.setFont(v.inter.deriveFont(12.5f));
            g.setColor(TEXT);
            g.drawString(LauncherView.ellipsizeEnd(g, in.requestName + ": waiting for the game's title screen (" + left / 60 + ":" + String.format("%02d", left % 60) + ")", iw - 110), ix + 16, ry + 27);
            v.textLink(g, "gp-req-cancel", "Cancel", ix + iw - 16, ry + 27, 1);
            name("gp-req-cancel", "Cancel the waiting request");
            v.animating = true;
        }
        // play time
        int sy = y + h - 132;
        g.setColor(HAIRLINE);
        g.fillRect(ix, sy - 18, iw, 1);
        g.setFont(v.interBold.deriveFont(10f));
        g.setColor(FAINT);
        v.spaced(g, "PLAY TIME", ix, sy + 4, 1.4f);
        GameState.Playtime p = in.playtime;
        long total = p.totalMs();
        String[][] stats = {{p.totalMs() > 0 ? GameState.duration(total) : "—", "Total"},
                {GameState.when(p.lastPlayed(), now), "Last played"}, {String.valueOf(p.sessions()), "Sessions"}};
        int cw = (iw - 24) / 3;
        for (int k = 0; k < 3; k++) {
            int cx = ix + k * (cw + 12);
            g.setColor(new Color(255, 255, 255, 6));
            g.fill(new RoundRectangle2D.Double(cx, sy + 16, cw, 72, 20, 20));
            g.setFont(v.interBold.deriveFont(19f));
            g.setColor(TEXT);
            g.drawString(LauncherView.ellipsizeEnd(g, stats[k][0], cw - 28), cx + 16, sy + 50);
            g.setFont(v.inter.deriveFont(12f));
            g.setColor(FAINT);
            g.drawString(stats[k][1], cx + 16, sy + 72);
        }

        // right: crashes
        int rx = x + lw + 24;
        if (in.crash != null) {
            paintCrash(g, i, in.crash, rx, y, rw, h, false);
            return;
        }
        v.card(g, rx, y, rw, h, false);
        int jx = rx + 24, jw = rw - 48;
        g.setFont(v.interBold.deriveFont(17f));
        g.setColor(TEXT);
        g.drawString("Crashes", jx, y + 40);
        g.setFont(v.inter.deriveFont(12.5f));
        g.setColor(DIM);
        int cy = y + 66;
        for (String l : LauncherView.wrapLines(g, "When a session ends badly, the likely cause shows here in plain words, with a fix you can apply in one click.", jw, 3)) {
            g.drawString(l, jx, cy);
            cy += 18;
        }
        cy += 14;
        g.setFont(v.interBold.deriveFont(10f));
        g.setColor(FAINT);
        v.spaced(g, "RECENT REPORTS", jx, cy, 1.4f);
        cy += 12;
        if (in.reports.isEmpty()) {
            g.setFont(v.inter.deriveFont(12.5f));
            g.setColor(FAINT);
            g.drawString("None. Good.", jx, cy + 22);
        }
        for (int k = 0; k < Math.min(4, in.reports.size()); k++) {
            Path r = in.reports.get(k);
            String id = "gp-report:" + k;
            float ht = v.anim(id, id.equals(v.hovered));
            g.setColor(new Color(255, 255, 255, Math.round(6 + 10 * ht)));
            g.fill(new RoundRectangle2D.Double(jx, cy + 4, jw, 40, 18, 18));
            LauncherView.icon(g, "alert", jx + 20, cy + 24, 13, AMBER);
            g.setFont(v.inter.deriveFont(12.5f));
            g.setColor(TEXT);
            g.drawString(LauncherView.ellipsizeEnd(g, r.getFileName().toString(), jw - 60), jx + 38, cy + 29);
            v.hit(id, new Rectangle(jx, cy + 4, jw, 40));
            name(id, "Open crash report " + r.getFileName());
            cy += 46;
        }
        int ny = y + h - 24 - 58;
        int nh = v.toggleRow(g, "gp-osnotify", jx, ny, jw, "Desktop notifications", "Crashes and updates, while the launcher runs", osNotify, true);
        name("gp-osnotify", "Desktop notifications, " + (osNotify ? "on" : "off"));
    }

    private static String where(GameState.Session s) {
        if (s == null) return "";
        if ("multiplayer".equals(s.state())) return "On " + (s.serverName() != null && !s.serverName().isBlank() ? s.serverName() : s.server());
        if ("singleplayer".equals(s.state())) return "In " + (s.world() != null ? s.world() : "a world");
        return "In the menus";
    }

    // ---- crash card (Game page and Home)

    /** The crash card: what happened, the likely cause, and the fixes. {@code compact}: Home's shorter version. */
    void paintCrash(Graphics2D g, Launcher.Instance i, CrashHelper.Diagnosis d, int x, int y, int w, int h, boolean compact) {
        v.card(g, x, y, w, h, false);
        g.setColor(new Color(225, 29, 46, 16));
        g.fill(new RoundRectangle2D.Double(x, y, w, h, 32, 32));
        g.setColor(new Color(225, 29, 46, 60));
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, w - 1, h - 1, 32, 32));
        int ix = x + 24, iw = w - 48;
        g.setColor(new Color(225, 29, 46, 40));
        g.fill(new RoundRectangle2D.Double(ix, y + 20, 40, 40, 20, 20));
        LauncherView.icon(g, "alert", ix + 20, y + 40, 18, RED_HI);
        g.setFont(v.interBold.deriveFont(10f));
        g.setColor(RED_HI);
        v.spaced(g, ("YOUR LAST SESSION " + (i != null ? "OF " + i.name.toUpperCase() + " " : "") + "CRASHED"), ix + 54, y + 34, 1.2f);
        g.setFont(v.interBold.deriveFont(compact ? 16f : 18f));
        g.setColor(TEXT);
        g.drawString(LauncherView.ellipsizeEnd(g, d.title(), iw - 54 - (compact ? 40 : 0)), ix + 54, y + 56);
        v.iconButton(g, "gp-crash-x", x + w - 24 - 28, y + 20, 28, "close-x", false);
        name("gp-crash-x", "Dismiss the crash card");
        g.setFont(v.inter.deriveFont(12.5f));
        g.setColor(DIM);
        int ly = y + 86;
        int maxLines = compact ? 2 : 5;
        for (String l : LauncherView.wrapLines(g, d.cause(), compact ? iw - 330 : iw, maxLines)) { g.drawString(l, ix, ly); ly += 18; }
        if (!compact && d.detail() != null) {
            ly += 6;
            g.setColor(FIELD);
            g.fill(new RoundRectangle2D.Double(ix, ly, iw, 44, 14, 14));
            g.setFont(mono.deriveFont(11.5f));
            g.setColor(FAINT);
            g.drawString(LauncherView.ellipsizeEnd(g, LogTools.mask(d.detail(), v.playerName), iw - 24), ix + 12, ly + 27);
        }
        // fixes
        String[] fix = fixLabels(d);
        int bh = 40;
        if (compact) {
            int bw = 150, bx = x + w - 24 - bw, by = y + h - 24 - bh;
            ghost(g, "gp-crash-log", bx, by, bw, bh, "Open report", false, "list", "Open the crash report");
            if (fix[0] != null) primary(g, "gp-fix", bx - 12 - 200, by, 200, bh, fix[0], null, fix[0]);
            if (fix[1] != null) ghost(g, "gp-fix-alt", bx - 12 - 200 - 12 - 160, by, 160, bh, fix[1], false, null, fix[1]);
            return;
        }
        int by = y + h - 24 - bh;
        ghost(g, "gp-crash-log", ix, by, iw, bh, "Open the report", false, "list", "Open the crash report");
        by -= bh + 10;
        if (fix[1] != null) { ghost(g, "gp-fix-alt", ix, by, iw, bh, fix[1], false, null, fix[1]); by -= bh + 10; }
        if (fix[0] != null) primary(g, "gp-fix", ix, by, iw, bh, fix[0], null, fix[0]);
    }

    /** The main fix and a second one, as button labels (null: none). */
    static String[] fixLabels(CrashHelper.Diagnosis d) {
        String mod = d.mod() == null ? "the mod" : d.mod();
        return switch (d.fix()) {
            case DISABLE_MOD -> new String[]{d.jar() != null ? "Switch it off and play" : null, "Share log for help"};
            case RAISE_MEMORY -> new String[]{"Give it more memory and play", "Share log for help"};
            case LOWER_MEMORY -> new String[]{"Use less memory and play", "Share log for help"};
            case DRIVER -> new String[]{"Get the latest graphics driver", "Turn shaders off"};
            case CLEAR_JAVA -> new String[]{"Use the Minecraft Launcher's Java", null};
            case FIND_MOD -> new String[]{"Find " + mod + " on Modrinth", d.jar() != null ? "Switch the mod off" : null};
            case OPEN_MODS -> new String[]{"Open the mods folder", null};
            default -> new String[]{null, "Share log for help"};
        };
    }

    // ---- logs

    private Rectangle paintLogs(Graphics2D g, Launcher.Instance i, int x, int y, int w, int h) {
        // toolbar: search, filter, file, actions
        int sw = 300;
        v.box(g, x, y, sw, 40);
        LauncherView.icon(g, "search", x + 20, y + 20, 13, DIM);
        Rectangle field = new Rectangle(x + 38, y + 6, sw - 50, 28);
        if (search.getText().isEmpty()) {
            g.setFont(v.inter.deriveFont(13f));
            g.setColor(FAINT);
            g.drawString("Search this log", x + 39, y + 25);
        }
        String[] filters = {"All", "Warnings", "Errors"};
        int fx = x + sw + 12, fw = 252;
        g.setColor(FIELD);
        g.fill(new RoundRectangle2D.Double(fx, y + 2, fw, 36, 22, 22));
        g.setColor(HAIRLINE);
        g.draw(new RoundRectangle2D.Double(fx + .5, y + 2.5, fw - 1, 35, 22, 22));
        int segW = (fw - 8) / 3;
        for (int k = 0; k < 3; k++) {
            String id = "gp-filter:" + k;
            boolean on = logFilter == k;
            float t = v.anim(id, id.equals(v.hovered));
            int sx = fx + 4 + k * segW;
            if (on) { g.setColor(RED); g.fill(new RoundRectangle2D.Double(sx, y + 6, segW, 28, 16, 16)); }
            else if (t > 0.01f) { g.setColor(new Color(255, 255, 255, Math.round(12 * t))); g.fill(new RoundRectangle2D.Double(sx, y + 6, segW, 28, 16, 16)); }
            g.setFont(v.interBold.deriveFont(12.5f));
            g.setColor(on ? Color.WHITE : mix(DIM, TEXT, t));
            LauncherView.center(g, filters[k], sx, y + 6, segW, 28);
            v.hit(id, new Rectangle(sx, y + 6, segW, 28));
            name(id, "Show " + filters[k].toLowerCase() + (on ? ", selected" : ""));
        }
        int bx = x + w;
        bx -= 120;
        primary(g, "gp-share", bx, y, 120, 40, "Share log", "copy", "Share log: copies it with private details hidden");
        bx -= 8 + 40;
        iconTile(g, "gp-log-folder", bx, y, "folder", "Open the logs folder");
        bx -= 8 + 40;
        iconTile(g, "gp-copy", bx, y, "copy", "Copy the log as shown");
        // file picker
        String fname = logFile == null ? "No log yet" : logFile.getFileName().toString();
        int pw = Math.max(120, bx - 8 - (fx + fw + 12));
        int px = fx + fw + 12;
        float t = v.anim("gp-file", "gp-file".equals(v.hovered));
        g.setColor(mix(LauncherView.CARD_2, new Color(0x202028), t));
        g.fill(new RoundRectangle2D.Double(px, y, pw, 40, 24, 24));
        LauncherView.icon(g, "list", px + 20, y + 20, 13, DIM);
        g.setFont(v.interBold.deriveFont(12.5f));
        g.setColor(TEXT);
        g.drawString(LauncherView.ellipsizeEnd(g, fname, pw - 66), px + 36, y + 25);
        LauncherView.icon(g, "chevron-down", px + pw - 18, y + 20, 9, DIM);
        v.hit("gp-file", new Rectangle(px, y, pw, 40));
        name("gp-file", "Log file: " + fname + ". Next file");

        // the log
        int ly = y + 52, lh = h - 52;
        v.card(g, x, ly, w, lh, false);
        Object key = List.of(System.identityHashCode(logLines), logLines.size(), logFilter, search.getText());
        if (!key.equals(filterKey)) {
            filtered = LogTools.filter(logLines, logFilter, search.getText());
            filterKey = key;
        }
        if (filtered.isEmpty()) {
            g.setFont(v.inter.deriveFont(13f));
            g.setColor(logError != null ? RED_HI : DIM);
            String msg = logError != null ? logError : logLines.isEmpty() ? (logFile == null ? "This instance has no logs yet. They appear once the game has run." : "Loading…")
                    : "No lines match.";
            LauncherView.center(g, msg, x, ly + lh / 2 - 20, w, 20);
            return field;
        }
        int rowH = 18, top = ly + 10, foot = 34;
        Rectangle area = new Rectangle(x + 1, top, w - 2, lh - 20 - foot);
        int content = filtered.size() * rowH + 8;
        float max = Math.max(0, content - area.height);
        float[] sc = v.scroll.computeIfAbsent("gp-log", k -> new float[2]);
        if (follow && sc[0] < max) { sc[0] = max; sc[1] = max; }
        else if (sc[0] < lastMax - 2 && sc[0] < max - 2) follow = false;
        if (sc[0] >= max - 2) follow = true;
        lastMax = max;
        float off = v.scrollOf("gp-log");
        Shape was = g.getClip();
        g.clip(area);
        int first = Math.max(0, (int) (off / rowH) - 1), last = Math.min(filtered.size(), first + area.height / rowH + 3);
        g.setFont(mono);
        String player = v.playerName;
        for (int n = first; n < last; n++) {
            LogTools.Line l = filtered.get(n);
            int ry = top + 4 + n * rowH - Math.round(off);
            if (l.level() == LogTools.Level.ERROR) { g.setColor(new Color(225, 29, 46, 22)); g.fillRect(x + 10, ry, w - 30, rowH); }
            else if (l.level() == LogTools.Level.WARN) { g.setColor(new Color(245, 165, 36, 14)); g.fillRect(x + 10, ry, w - 30, rowH); }
            g.setColor(l.level() == LogTools.Level.ERROR ? new Color(0xFF7A86) : l.level() == LogTools.Level.WARN ? AMBER : new Color(0xC6C6CE));
            g.drawString(LauncherView.ellipsizeEnd(g, LogTools.mask(l.text().replace('\t', ' '), player), w - 50), x + 18, ry + 13);
        }
        g.setClip(was);
        v.scrollArea(g, "gp-log", area, content);
        g.setColor(BORDER);
        g.fillRect(x + 1, ly + lh - foot - 6, w - 2, 1);
        g.setFont(v.inter.deriveFont(11.5f));
        g.setColor(FAINT);
        boolean live = logFile != null && logFile.getFileName().toString().equals("latest.log") && running(i);
        String s = filtered.size() + (filtered.size() == logLines.size() ? " lines" : " of " + logLines.size() + " lines")
                + (live ? "  ·  live" : "") + "  ·  Sign-in tokens, IP addresses and your user and player names are hidden";
        g.drawString(LauncherView.ellipsizeEnd(g, s, w - 200), x + 20, ly + lh - 16);
        if (!follow) {
            v.textLink(g, "gp-follow", "Jump to the end", x + w - 20, ly + lh - 16, 1);
            name("gp-follow", "Jump to the end of the log");
        }
        if (live) v.animating = true;
        return field;
    }

    // ---- settings

    private Rectangle[] paintSettings(Graphics2D g, Launcher.Instance i, int x, int y, int w, int h) {
        InstanceExtras xt = info(i).extras;
        v.card(g, x, y, w, h, false);
        int ix = x + 28, iw = w - 56, ctl = 420, cx = ix + iw - ctl;
        int ry = y + 12, rh = 64;
        // icon
        v.rowLabel(g, ix, ry, 76, "Icon", "Shown here and in the Minecraft Launcher", false, iw - ctl - 20);
        int gx = cx, gy = ry + 12;
        for (int k = -1; k < InstanceExtras.GLYPHS.length; k++) {
            String id = "gp-glyph:" + k;
            boolean on = xt.glyph == k;
            float t = v.anim(id, id.equals(v.hovered));
            int bx = gx + (k + 1) * 46;
            if (k < 0) v.tile(g, bx, gy, 36, i.name);
            else InstanceExtras.paintIcon(g, k, xt.color, bx, gy, 36);
            if (on || t > 0.01f) {
                g.setColor(on ? RED_HI : new Color(255, 255, 255, Math.round(80 * t)));
                g.setStroke(new BasicStroke(2f));
                g.draw(new RoundRectangle2D.Double(bx - 3, gy - 3, 42, 42, 26, 26));
                g.setStroke(new BasicStroke(1f));
            }
            v.hit(id, new Rectangle(bx - 3, gy - 3, 42, 42));
            name(id, (k < 0 ? "Letter tile" : InstanceExtras.GLYPHS[k] + " icon") + (on ? ", selected" : ""));
        }
        for (int k = 0; k < InstanceExtras.COLORS.length; k++) {
            String id = "gp-color:" + k;
            boolean on = xt.color == k;
            int dx = gx + 46 + k * 22, dy = gy + 44;
            g.setColor(new Color(InstanceExtras.COLORS[k]));
            g.fill(new Ellipse2D.Double(dx, dy, 14, 14));
            if (on) {
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(2f));
                g.draw(new Ellipse2D.Double(dx - 3, dy - 3, 20, 20));
                g.setStroke(new BasicStroke(1f));
            }
            v.hit(id, new Rectangle(dx - 4, dy - 4, 22, 22));
            name(id, "Icon colour " + (k + 1) + (on ? ", selected" : ""));
        }
        ry += 76 + 8;
        v.rowLabel(g, ix, ry, rh, "Memory", "How much Minecraft may use", false, iw);
        v.memoryChips(g, cx, ry + 14, ctl, i.memoryGb);
        for (int gb : InstallerCore.memoryOptions()) name("mem:" + gb, gb + " gigabytes of memory" + (gb == i.memoryGb ? ", selected" : ""));
        ry += rh;
        // window
        v.rowLabel(g, ix, ry, rh, "Game window", "Its size when the game opens", false, iw);
        int sel = 0;
        for (int k = 0; k < InstanceExtras.SIZES.length; k++) if (InstanceExtras.SIZES[k][0] == xt.width && InstanceExtras.SIZES[k][1] == xt.height) sel = k;
        g.setColor(FIELD);
        g.fill(new RoundRectangle2D.Double(cx, ry + 14, ctl, 36, 22, 22));
        g.setColor(HAIRLINE);
        g.draw(new RoundRectangle2D.Double(cx + .5, ry + 14.5, ctl - 1, 35, 22, 22));
        int segW = (ctl - 8) / InstanceExtras.SIZES.length;
        for (int k = 0; k < InstanceExtras.SIZES.length; k++) {
            String id = "gp-size:" + k;
            boolean on = sel == k;
            float t = v.anim(id, id.equals(v.hovered));
            int sx = cx + 4 + k * segW;
            if (on) { g.setColor(RED); g.fill(new RoundRectangle2D.Double(sx, ry + 18, segW, 28, 16, 16)); }
            else if (t > 0.01f) { g.setColor(new Color(255, 255, 255, Math.round(12 * t))); g.fill(new RoundRectangle2D.Double(sx, ry + 18, segW, 28, 16, 16)); }
            String lbl = k == 0 ? "Default" : InstanceExtras.SIZES[k][0] + "×" + InstanceExtras.SIZES[k][1];
            g.setFont(v.interBold.deriveFont(12.5f));
            g.setColor(on ? Color.WHITE : mix(DIM, TEXT, t));
            LauncherView.center(g, lbl, sx, ry + 18, segW, 28);
            v.hit(id, new Rectangle(sx, ry + 18, segW, 28));
            name(id, "Window " + lbl + (on ? ", selected" : ""));
        }
        ry += rh;
        // java
        v.rowLabel(g, ix, ry, rh, "Java", javaCheck != null ? javaCheck : "Optional: this instance only, over Settings", false, iw - ctl - 20);
        int fw = ctl - 88;
        v.box(g, cx, ry + 14, fw, 36);
        Rectangle jf = new Rectangle(cx + 12, ry + 18, fw - 44, 28);
        if (java.getText().isEmpty()) {
            g.setFont(v.inter.deriveFont(12.5f));
            g.setColor(FAINT);
            g.drawString("Same as Settings", cx + 13, ry + 37);
        } else {
            float tc = v.anim("gp-java-clear", "gp-java-clear".equals(v.hovered));
            LauncherView.icon(g, "close-x", cx + fw - 18, ry + 32, 10, mix(FAINT, TEXT, tc));
            v.hit("gp-java-clear", new Rectangle(cx + fw - 32, ry + 18, 28, 28));
            name("gp-java-clear", "Clear this instance's Java");
        }
        ghost(g, "gp-java-browse", cx + fw + 8, ry + 14, 80, 36, "Browse", false, null, "Choose a Java program");
        ry += rh;
        // args
        v.rowLabel(g, ix, ry, rh, "Java arguments", argsNotes.isEmpty() ? "Added after Evade's own (memory stays with the chips)" : "", false, iw - ctl - 20);
        if (!argsNotes.isEmpty()) {
            g.setFont(v.inter.deriveFont(12f));
            g.setColor(AMBER);
            g.drawString(LauncherView.ellipsizeEnd(g, argsNotes.get(0), iw - ctl - 20), ix, ry + rh / 2 + 16);
        }
        v.box(g, cx, ry + 14, ctl, 36);
        Rectangle af = new Rectangle(cx + 12, ry + 18, ctl - 24, 28);
        if (args.getText().isEmpty()) {
            g.setFont(v.inter.deriveFont(12.5f));
            g.setColor(FAINT);
            g.drawString("-XX:+AlwaysPreTouch …", cx + 13, ry + 37);
        }
        ry += rh;
        // notes
        v.rowLabel(g, ix, ry, rh, "Notes", "Just for you", true, iw - ctl - 20);
        v.box(g, cx, ry + 14, ctl, 36);
        Rectangle nf = new Rectangle(cx + 12, ry + 18, ctl - 24, 28);
        if (notes.getText().isEmpty()) {
            g.setFont(v.inter.deriveFont(12.5f));
            g.setColor(FAINT);
            g.drawString("What this instance is for…", cx + 13, ry + 37);
        }
        g.setFont(v.inter.deriveFont(12f));
        g.setColor(FAINT);
        String foot = running(i) ? "The game is running: changes reach it the next time it starts." : "Saved as you go. Written to the Minecraft Launcher profile now and on every Play.";
        g.drawString(LauncherView.ellipsizeEnd(g, foot, iw), ix, y + h - 24);
        return new Rectangle[]{jf, af, nf};
    }

    // ------------------------------------------------------------------ Home, instance list, server card

    /** The hero's status line while the instance is playing, or null. {status, sub}. */
    String[] heroStatus(Launcher.Instance i) {
        if (i == null) return null;
        Info in = info(i);
        if (in.run == GameState.Run.RUNNING)
            return new String[]{"Playing now  ·  " + where(in.session) + "  ·  " + GameState.duration(System.currentTimeMillis() - in.session.started()), "RUNNING"};
        if (in.run == GameState.Run.NOT_RESPONDING) return new String[]{"The game isn't responding", "RUNNING"};
        return null;
    }

    /** Beside Play on Home: Stop game while it runs, else Continue (the last world). */
    void heroButtons(Graphics2D g, Launcher.Instance i, int x, int y) {
        if (i == null) return;
        Info in = info(i);
        if (in.running()) glassButton(g, "gp-stop", x, y, 132, "STOP", "the game", "Stop the game (asks first)");
        else if (in.lastWorld != null && i.installed != null) glassButton(g, "gp-continue", x, y, 176, "CONTINUE", in.lastWorld[1], "Continue last world " + in.lastWorld[1]);
    }

    private void glassButton(Graphics2D g, String id, int x, int y, int w, String top, String bottom, String label) {
        float t = v.anim(id, id.equals(v.hovered));
        g.setColor(mix(new Color(255, 255, 255, 18), new Color(255, 255, 255, 34), t));
        g.fill(new RoundRectangle2D.Double(x, y, w, 64, 28, 28));
        g.setColor(new Color(255, 255, 255, 26));
        g.draw(new RoundRectangle2D.Double(x + .5, y + .5, w - 1, 63, 28, 28));
        g.setFont(v.interBold.deriveFont(13f));
        g.setColor(mix(new Color(0xD6D6DC), Color.WHITE, t));
        v.spaced(g, top, x + 20, y + 29, 0.8f);
        g.setFont(v.inter.deriveFont(11.5f));
        g.setColor(new Color(255, 255, 255, 150));
        g.drawString(LauncherView.ellipsizeEnd(g, bottom, w - 40), x + 20, y + 47);
        v.hit(id, new Rectangle(x, y, w, 64));
        name(id, label);
    }

    /** Home's bottom row while a crash is waiting: the crash card instead of the news. True when drawn. */
    boolean paintHomeCrash(Graphics2D g, int x, int y, int w, int h) {
        for (Launcher.Instance i : v.instances) {
            CrashHelper.Diagnosis d = info(i).crash;
            if (d != null) {
                crashInstance = i;
                paintCrash(g, i, d, x, y, w, h, true);
                return true;
            }
        }
        return false;
    }

    /** The instance whose crash card is showing (its buttons act on it). */
    Launcher.Instance crashInstance;

    /** In the instance list: a "Playing" pill instead of the status dot. True when drawn. */
    boolean rowPlaying(Graphics2D g, int x, int y, Launcher.Instance i) {
        Info in = info(i);
        if (!in.running()) return false;
        g.setFont(v.interBold.deriveFont(9f));
        String s = in.run == GameState.Run.RUNNING ? "PLAYING" : "STUCK";
        int pw = g.getFontMetrics().stringWidth(s) + 14;
        Color c = in.run == GameState.Run.RUNNING ? OK : AMBER;
        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 40));
        // on the name's line, right-aligned (the line under it holds the details)
        g.fill(new RoundRectangle2D.Double(x + 12 - pw, y - 24, pw, 16, 16, 16));
        g.setColor(c);
        LauncherView.center(g, s, x + 12 - pw, y - 24, pw, 16);
        return true;
    }

    /** "  ·  41 h played" for the instance list, or "". */
    String playtimeShort(Launcher.Instance i) {
        long t = info(i).playtime.totalMs();
        if (t < 60_000) return "";
        long h = t / 3_600_000;
        return "  ·  " + (h > 0 ? h + "h" : t / 60_000 + "m");
    }

    /** An instance's own icon, when one was picked. True when drawn. */
    boolean paintIcon(Graphics2D g, int x, int y, int s, Launcher.Instance i) {
        InstanceExtras x2 = info(i).extras;
        if (x2.glyph < 0) return false;
        InstanceExtras.paintIcon(g, x2.glyph, x2.color, x, y, s);
        return true;
    }

    private int joinIndex = -1;

    /** The Join button on a server's card. */
    void serverJoin(Graphics2D g, int x, int y, int w, int h, int index) {
        joinIndex = index;
        primary(g, "gp-join", x, y, w, h, "Join", "play", "Join this server");
    }

    // ------------------------------------------------------------------ overlays

    /** Dialog, Undo toast and the focus ring: painted last, over the page. */
    void paintOverlay(Graphics2D g) {
        if (confirm != null) {
            Rectangle close = v.hitMap.get("close"), min = v.hitMap.get("min");
            v.hitMap.clear();
            if (close != null) v.hitMap.put("close", close);
            if (min != null) v.hitMap.put("min", min);
            g.setColor(new Color(4, 4, 6, 170));
            if (v.roundCorners) g.fill(new RoundRectangle2D.Double(0, 0, W, H, 22, 22));
            else g.fillRect(0, 0, W, H);
            int w = 440, h = 210, x = (W - w) / 2, y = (H - h) / 2;
            v.card(g, x, y, w, h, true);
            int ix = x + 28, iw = w - 56;
            g.setFont(v.interBold.deriveFont(19f));
            g.setColor(TEXT);
            g.drawString(LauncherView.ellipsizeEnd(g, confirm.title(), iw), ix, y + 46);
            g.setFont(v.inter.deriveFont(12.5f));
            g.setColor(DIM);
            int ly = y + 72;
            for (String l : LauncherView.wrapLines(g, confirm.body(), iw, 4)) { g.drawString(l, ix, ly); ly += 18; }
            int bw = 130;
            ghost(g, "gp-cancel", x + w - 28 - 2 * bw - 10, y + h - 64, bw, 40, "Cancel", false, null, "Cancel");
            if (confirm.danger()) ghost(g, "gp-ok", x + w - 28 - bw, y + h - 64, bw, 40, confirm.ok(), true, null, confirm.ok());
            else primary(g, "gp-ok", x + w - 28 - bw, y + h - 64, bw, 40, confirm.ok(), null, confirm.ok());
            if (focusId == null || !v.hitMap.containsKey(focusId)) focusId = "gp-cancel";
        }
        if (undoText != null) {
            long age = System.currentTimeMillis() - undoAt;
            if (age > InstanceTrash.UNDO_MS) undoText = null;
            else {
                v.animating = true;
                g.setFont(v.interBold.deriveFont(12.5f));
                int tw = g.getFontMetrics().stringWidth(undoText) + 48 + 70;
                int x = v.CX + (v.CW - tw) / 2, y = H - 70;
                g.setColor(RAISED);
                g.fill(new RoundRectangle2D.Double(x, y, tw, 44, 44, 44));
                g.setColor(new Color(255, 255, 255, 24));
                g.draw(new RoundRectangle2D.Double(x + .5, y + .5, tw - 1, 43, 43, 43));
                g.setColor(TEXT);
                g.drawString(undoText, x + 20, y + 27);
                float t = v.anim("gp-undo", "gp-undo".equals(v.hovered) || "gp-undo".equals(focusId));
                int ux = x + tw - 74;
                g.setColor(mix(new Color(225, 29, 46, 44), new Color(225, 29, 46, 90), t));
                g.fill(new RoundRectangle2D.Double(ux, y + 6, 66, 32, 32, 32));
                g.setColor(mix(new Color(0xFF6B78), Color.WHITE, t));
                LauncherView.center(g, "Undo", ux, y + 6, 66, 32);
                double f = 1 - age / (double) InstanceTrash.UNDO_MS;
                g.setColor(new Color(225, 29, 46, 160));
                g.fill(new RoundRectangle2D.Double(x + 22, y + 39, (tw - 44) * f, 2, 2, 2));
                v.hitMap.put("gp-undo", new Rectangle(ux, y + 6, 66, 32));
                name("gp-undo", "Undo delete");
            }
        }
        // focus ring (keyboard only)
        if (keyNav && focusId != null && v.isFocusOwner()) {
            Rectangle r = v.hitMap.get(focusId);
            if (r != null) {
                g.setColor(new Color(255, 65, 83, 220));
                g.setStroke(new BasicStroke(2f));
                g.draw(new RoundRectangle2D.Double(r.x - 3, r.y - 3, r.width + 6, r.height + 6, 18, 18));
                g.setStroke(new BasicStroke(1f));
            }
        }
    }

    void ask(String title, String body, String ok, boolean danger, Runnable run) {
        confirm = new Confirm(title, body, ok, danger, run);
        focusId = "gp-cancel";
        v.repaint();
    }

    void undo(String text) {
        undoText = text;
        undoAt = System.currentTimeMillis();
        v.repaint();
    }

    // ------------------------------------------------------------------ clicks

    /** A click (or Enter) on one of this class's controls. */
    void click(String id) {
        Launcher.Instance foc = v.focused(), sel = v.selected();
        Launcher.Instance pageInst = v.page == LauncherView.Page.GAME ? foc : sel;
        if (!id.startsWith("gp-tab:") && !id.equals("gp-cancel") && !id.equals("gp-ok")) focusFromMouse(id);
        switch (id) {
            case "gp-cancel" -> confirm = null;
            case "gp-ok" -> { Confirm c = confirm; confirm = null; if (c != null) c.run().run(); }
            case "gp-stop" -> {
                Launcher.Instance i = pageInst;
                if (i == null) break;
                ask("Stop " + i.name + "?", "The game closes right away, like closing it from Task Manager. Anything not saved in the last "
                        + "few seconds is lost: singleplayer worlds save every few minutes.", "Stop game", true, () -> actions.stopGame(i));
            }
            case "gp-continue" -> { if (pageInst != null) actions.continueWorld(pageInst); }
            case "gp-req-cancel" -> { if (pageInst != null) actions.cancelRequest(pageInst); }
            case "gp-folder" -> { if (foc != null) v.actions.openFolder(foc.dir); }
            case "gp-log-folder" -> { if (foc != null) v.actions.openFolder(foc.dir.resolve("logs")); }
            case "gp-copy" -> actions.copyLog();
            case "gp-share" -> actions.shareLog();
            case "gp-follow" -> follow = true;
            case "gp-file" -> {
                if (logFiles.isEmpty() || foc == null) break;
                int at = logFiles.indexOf(logFile);
                actions.openLog(foc, logFiles.get(Math.floorMod(at + 1, logFiles.size())));
            }
            case "gp-crash-x" -> {
                Launcher.Instance i = v.page == LauncherView.Page.GAME ? foc : crashInstance;
                if (i != null) actions.dismissCrash(i);
            }
            case "gp-crash-log" -> {
                Launcher.Instance i = v.page == LauncherView.Page.GAME ? foc : crashInstance;
                CrashHelper.Diagnosis d = i == null ? null : info(i).crash;
                if (d != null) actions.openReport(d.report());
            }
            case "gp-fix", "gp-fix-alt" -> {
                Launcher.Instance i = v.page == LauncherView.Page.GAME ? foc : crashInstance;
                CrashHelper.Diagnosis d = i == null ? null : info(i).crash;
                if (d != null) actions.fix(i, d, id.equals("gp-fix-alt"));
            }
            case "gp-osnotify" -> { osNotify = !osNotify; actions.osNotifyChanged(osNotify); }
            case "gp-java-browse" -> { if (foc != null) actions.browseJava(foc); }
            case "gp-java-clear" -> java.setText("");
            case "gp-undo" -> { undoText = null; actions.undoDelete(); }
            case "gp-join" -> {
                if (joinIndex >= 0 && joinIndex < v.settings.servers.size()) actions.joinServer(v.settings.servers.get(joinIndex));
                v.overlay = null;
            }
            default -> {
                if (id.startsWith("gp-tab:")) {
                    tab = id.substring(7);
                    if (tab.equals("settings")) syncFields(foc);
                    actions.tabShown(tab);
                } else if (id.startsWith("gp-filter:")) {
                    logFilter = Integer.parseInt(id.substring(10));
                } else if (id.startsWith("gp-report:")) {
                    Info in = info(foc);
                    int n = Integer.parseInt(id.substring(10));
                    if (n < in.reports.size()) actions.openReport(in.reports.get(n));
                } else if (id.startsWith("gp-glyph:")) {
                    int k = Integer.parseInt(id.substring(9));
                    edit(x -> x.glyph = k);
                } else if (id.startsWith("gp-color:")) {
                    int k = Integer.parseInt(id.substring(9));
                    edit(x -> { x.color = k; if (x.glyph < 0) x.glyph = 0; });
                } else if (id.startsWith("gp-size:")) {
                    int k = Integer.parseInt(id.substring(8));
                    edit(x -> { x.width = InstanceExtras.SIZES[k][0]; x.height = InstanceExtras.SIZES[k][1]; });
                }
            }
        }
        v.repaint();
    }

    /** The focus ring shows only after Tab was used, until the mouse is pressed again. */
    private boolean keyNav;

    private void focusFromMouse(String id) {
        if (!keyNav) focusId = id;
    }

    // ------------------------------------------------------------------ small widgets

    private void primary(Graphics2D g, String id, int x, int y, int w, int h, String label, String icon, String name) {
        v.smallPrimary(g, id, x, y, w, h, label, icon);
        name(id, name);
    }

    private void ghost(Graphics2D g, String id, int x, int y, int w, int h, String label, boolean danger, String icon, String name) {
        v.ghostButton(g, id, x, y, w, h, label, danger, icon, false);
        name(id, name);
    }

    private void iconTile(Graphics2D g, String id, int x, int y, String icon, String name) {
        float t = v.anim(id, id.equals(v.hovered));
        g.setColor(mix(new Color(0x1C1C22), new Color(0x26262D), t));
        g.fill(new RoundRectangle2D.Double(x, y, 40, 40, 24, 24));
        LauncherView.icon(g, icon, x + 20, y + 20, 15, mix(DIM, TEXT, t));
        v.hit(id, new Rectangle(x, y, 40, 40));
        name(id, name);
    }
}
