package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.misc.StreamerMode;
import net.evadeclient.module.visuals.Waypoints;
import net.evadeclient.module.visuals.Waypoints.Point;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont.Role;
import net.evadeclient.util.WaypointIcons;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The Waypoint Manager: this world's points in a searchable list (nearest first, or by name or age), each with its
 * icon, colour, dimension, coordinates and distance. Edit a point's name, colour, icon, beam and how far away it
 * still shows; hide or show it; make it your target; delete it (with Undo); or put its coordinates into the chat box
 * as plain text to send yourself. Nothing here teleports or sends anything.
 */
public final class WaypointManagerScreen extends EvadePanelScreen {
    private static final int ROW_H = 30, EDIT_H = 112;
    private static final String[] SORTS = {"Distance", "Name", "Newest"};
    private static final int[] RANGES = {0, 64, 128, 256, 512, 1000, 2000, 5000, 10000};
    private static final long UNDO_MS = 8000;

    private static int sort;
    /** The point whose editor is open, and its name field while typing. */
    private Point editing;
    private String nameBuffer;
    private boolean nameFocused;
    /** The last delete, for Undo. */
    private Point undoPoint;
    private int undoIndex;
    private long undoAt;
    /** Where the name field was drawn last frame. */
    private int[] nameRect = new int[4];

    public WaypointManagerScreen(Screen parent) {
        super("Waypoints", parent);
    }

    /** Test hook: the open manager's editor, so a test can drive it. */
    public Point editingForTest() { return editing; }

    private static Waypoints wp() { return EvadeClient.modules == null ? null : EvadeClient.modules.get(Waypoints.class); }

    @Override protected int prefWidth() { return 660; }
    @Override protected int prefHeight() { return 420; }
    @Override protected boolean hasSearch() { return true; }

    @Override
    protected String subtitle() {
        Waypoints w = wp();
        if (w == null || client == null || client.world == null) return "Join a world to see its waypoints";
        int n = w.allInWorld().size();
        String where = client.isInSingleplayer() ? "this world" : StreamerMode.hidesServerAddress() ? "this server" : serverName();
        return n + " point" + (n == 1 ? "" : "s") + " in " + where + (w.coordsHidden() ? " · coordinates hidden" : "");
    }

    private String serverName() {
        var e = client.getCurrentServerEntry();
        return e == null ? "this server" : e.name == null || e.name.isBlank() ? e.address : e.name;
    }

    @Override
    protected void renderHeaderRight(DrawContext ctx, int right, int y, int mx, int my) {
        int x = button(ctx, "sort", "Sort: " + SORTS[sort], right - 24, y, mx, my, 0, b -> sort = (sort + (b == 1 ? SORTS.length - 1 : 1)) % SORTS.length);
        button(ctx, "add", "+ Add here", x - 6, y, mx, my, 1, b -> addHere());
    }

    private void addHere() {
        Waypoints w = wp();
        if (w == null || client.player == null) return;
        Point p = w.addAt(w.freeName("Waypoint"), null, client.player.getBlockPos());
        if (p != null) {
            openEditor(p);
            Notifications.push("Waypoint", "Saved " + p.name(), true);
        }
    }

    private List<Point> points() {
        Waypoints w = wp();
        if (w == null) return List.of();
        List<Point> list = new ArrayList<>(w.allInWorld());
        String q = search.strip().toLowerCase(Locale.ROOT);
        if (!q.isEmpty()) list.removeIf(p -> !(p.name().toLowerCase(Locale.ROOT).contains(q)
                || Waypoints.dimensionName(p.dim()).toLowerCase(Locale.ROOT).contains(q)
                || (p.icon() != null && p.icon().toLowerCase(Locale.ROOT).contains(q))));
        switch (sort) {
            case 1 -> list.sort(Comparator.comparing(Point::name, String.CASE_INSENSITIVE_ORDER));
            case 2 -> list.sort(Comparator.comparingLong(Point::created).reversed());
            default -> list.sort(Comparator.comparingDouble(p -> {
                double d = w.distanceTo(p);
                return d < 0 ? Double.MAX_VALUE : d;
            }));
        }
        return list;
    }

    @Override
    protected int renderContent(DrawContext ctx, int top, int mx, int my) {
        Waypoints w = wp();
        if (w == null || client.world == null) {
            String msg = "Join a world to manage its waypoints";
            text(ctx, msg, cx + (cw - width(msg, Role.BODY)) / 2, top + 30, Theme.TEXT_DIM, Role.BODY);
            return 60;
        }
        List<Point> list = points();
        if (list.isEmpty()) {
            String msg = search.isBlank() ? "No waypoints yet. Add one here, or type .wp add <name>" : "No waypoints match \"" + search.strip() + "\"";
            text(ctx, msg, cx + (cw - width(msg, Role.BODY)) / 2, top + 30, Theme.TEXT_DIM, Role.BODY);
            return 60;
        }
        boolean hideCoords = w.coordsHidden();
        int y = top;
        for (Point p : list) {
            if (inView(y, ROW_H)) renderRow(ctx, w, p, y, mx, my, hideCoords);
            y += ROW_H;
            if (p.equals(editing)) {
                if (inView(y, EDIT_H)) renderEditor(ctx, w, p, y, mx, my);
                y += EDIT_H;
            }
        }
        return y - top + 6;
    }

    private void renderRow(DrawContext ctx, Waypoints w, Point p, int y, int mx, int my, boolean hideCoords) {
        boolean open = p.equals(editing);
        boolean hv = mouseIn(mx, my, cx, y, cx + cw, y + ROW_H - 3);
        float h = hover("row:" + p.name() + p.dim(), hv);
        int fill = open ? Theme.tint(0.08f) : Theme.lerpColor(Theme.ROW, Theme.ROW_HOVER, h);
        Theme.round(ctx, cx, y, cx + cw, y + ROW_H - 3, fill);
        int color = Waypoints.color(p);
        Theme.round(ctx, cx, y + 5, cx + 2, y + ROW_H - 8, color, 1);
        if (WaypointIcons.has(p.icon())) WaypointIcons.draw(ctx, p.icon(), cx + 10, y + 5, 2, color);
        else Theme.disc(ctx, cx + 15, y + 9, 9, color);

        int tx = cx + 34;
        int nameColor = p.hidden() ? Theme.TEXT_FAINT : Theme.TEXT;
        text(ctx, ellipsize(p.name(), Role.STRONG, cw / 2 - 40), tx, y + 5, nameColor, Role.STRONG);
        double d = w.distanceTo(p);
        StringBuilder sub = new StringBuilder(Waypoints.dimensionName(p.dim()));
        if (!hideCoords) sub.append("  ·  ").append(p.x()).append(", ").append(p.y()).append(", ").append(p.z());
        sub.append("  ·  ").append(d < 0 ? "other dimension" : Math.round(d) + "m");
        if (p.hidden()) sub.append("  ·  hidden");
        if (p.equals(w.target())) sub.append("  ·  target");
        if (p.range() > 0) sub.append("  ·  shows within ").append(p.range()).append("m");
        text(ctx, ellipsize(sub.toString(), Role.SMALL, cw - 34 - 300), tx, y + 17, Theme.TEXT_DIM, Role.SMALL);

        // Clicking the row itself opens or closes its editor (the buttons, registered after, are checked first).
        contentHit(cx, y, cx + cw, y + ROW_H - 3, b -> { if (p.equals(editing)) closeEditor(); else openEditor(p); });
        int right = cx + cw - 6, by = y + 5;
        right = button(ctx, "del:" + key(p), "Delete", right, by, mx, my, 2, b -> delete(p)) - 4;
        right = button(ctx, "share:" + key(p), "Share", right, by, mx, my, 0, b -> share(p)) - 4;
        right = button(ctx, "hide:" + key(p), p.hidden() ? "Show" : "Hide", right, by, mx, my, 0,
                b -> w.replacePoint(p, p.withHidden(!p.hidden()))) - 4;
        button(ctx, "edit:" + key(p), open ? "Done" : "Edit", right, by, mx, my, open ? 1 : 0, b -> {
            if (open) closeEditor(); else openEditor(p);
        });
    }

    private static String key(Point p) { return p.name() + "@" + p.dim(); }

    private void renderEditor(DrawContext ctx, Waypoints w, Point p, int y, int mx, int my) {
        Theme.roundBordered(ctx, cx + 8, y - 2, cx + cw, y + EDIT_H - 6, Theme.CARD, Theme.BORDER);
        int lx = cx + 20, vx = cx + 96;
        int row = y + 6;

        // Name
        text(ctx, "Name", lx, row + 4, Theme.TEXT_SOFT, Role.LABEL);
        int fx2 = Math.min(cx + cw - 12, vx + 220);
        boolean fh = mouseIn(mx, my, vx, row, fx2, row + 16);
        Theme.roundBordered(ctx, vx, row, fx2, row + 16, Theme.INPUT, nameFocused ? Theme.RED : fh ? Theme.BORDER_HOVER : Theme.BORDER);
        String shown = nameFocused ? nameBuffer : p.name();
        text(ctx, ellipsize(shown, Role.BODY, fx2 - vx - 12), vx + 6, row + 4, Theme.TEXT, Role.BODY);
        if (nameFocused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int cxp = vx + 6 + Math.min(width(shown, Role.BODY), fx2 - vx - 12);
            Theme.fill(ctx, cxp + 1, row + 3, cxp + 2, row + 13, Theme.TEXT);
        }
        nameRect = new int[]{vx, row, fx2, row + 16};
        contentHit(vx, row, fx2, row + 16, b -> { nameFocused = true; if (nameBuffer == null) nameBuffer = p.name(); });
        boolean isTarget = p.equals(w.target());
        button(ctx, "target:" + key(p), isTarget ? "Clear target" : "Set as target", cx + cw - 12, row, mx, my, isTarget ? 1 : 0,
                b -> { w.setTarget(isTarget ? null : p); });
        row += 22;

        // Colour
        text(ctx, "Colour", lx, row + 4, Theme.TEXT_SOFT, Role.LABEL);
        int sx = vx;
        int[] colors = new int[ColorSetting.PALETTE.length + 1];
        colors[0] = 0;
        System.arraycopy(ColorSetting.PALETTE, 0, colors, 1, ColorSetting.PALETTE.length);
        for (int c : colors) {
            boolean sel = (p.color() & 0xFFFFFF) == (c & 0xFFFFFF) && (p.color() == 0) == (c == 0);
            int shownC = c == 0 ? Waypoints.color(new Point(p.name(), p.dim(), 0, 0, 0)) : c;
            if (sel) Theme.round(ctx, sx - 2, row - 1, sx + 15, row + 16, Theme.RED, 2);
            Theme.round(ctx, sx, row + 1, sx + 13, row + 14, shownC | 0xFF000000, 2);
            if (c == 0) text(ctx, "A", sx + 4, row + 4, 0xFF000000, Role.SMALL);
            final int pick = c;
            contentHit(sx - 1, row, sx + 14, row + 15, b -> w.replacePoint(p, p.withColor(pick)));
            sx += 17;
        }
        row += 22;

        // Icon
        text(ctx, "Icon", lx, row + 4, Theme.TEXT_SOFT, Role.LABEL);
        int ix = vx;
        for (String icon : WaypointIcons.NAMES) {
            boolean none = icon.equals("None");
            boolean sel = none ? !WaypointIcons.has(p.icon()) : icon.equals(p.icon());
            boolean ih = mouseIn(mx, my, ix, row - 1, ix + 20, row + 17);
            Theme.roundBordered(ctx, ix, row - 1, ix + 20, row + 17, sel ? Theme.tint(0.22f) : ih ? Theme.CONTROL_HOVER : Theme.CONTROL,
                    sel ? Theme.RED : Theme.BORDER);
            if (none) text(ctx, "–", ix + 7, row + 4, Theme.TEXT_DIM, Role.BODY);
            else WaypointIcons.draw(ctx, icon, ix + 1, row, 2, Waypoints.color(p));
            final String pick = none ? null : icon;
            contentHit(ix, row - 1, ix + 20, row + 17, b -> w.replacePoint(p, p.withIcon(pick)));
            ix += 23;
        }
        row += 24;

        // Beam and range
        text(ctx, "Beam", lx, row + 4, Theme.TEXT_SOFT, Role.LABEL);
        String[] beams = {"Default", "Always", "Never"};
        int bx = vx;
        for (int i = 0; i < beams.length; i++) {
            final int mode = i;
            boolean sel = p.beam() == i;
            int bw = width(beams[i], Role.LABEL) + 14;
            boolean bh = mouseIn(mx, my, bx, row, bx + bw, row + 16);
            Theme.roundBordered(ctx, bx, row, bx + bw, row + 16, sel ? Theme.tint(0.22f) : bh ? Theme.CONTROL_HOVER : Theme.CONTROL,
                    sel ? Theme.RED : Theme.BORDER);
            text(ctx, beams[i], bx + 7, row + 4, sel ? Theme.TEXT : Theme.TEXT_SOFT, Role.LABEL);
            contentHit(bx, row, bx + bw, row + 16, b -> w.replacePoint(p, p.withBeam(mode)));
            bx += bw + 4;
        }
        int rx = bx + 24;
        text(ctx, "Show within", rx, row + 4, Theme.TEXT_SOFT, Role.LABEL);
        rx += width("Show within", Role.LABEL) + 8;
        String rv = p.range() == 0 ? "Any distance" : p.range() + " m";
        int rw = Math.max(90, width(rv, Role.LABEL) + 30);
        boolean rh = mouseIn(mx, my, rx, row, rx + rw, row + 16);
        Theme.roundBordered(ctx, rx, row, rx + rw, row + 16, rh ? Theme.CONTROL_HOVER : Theme.CONTROL, Theme.BORDER);
        text(ctx, "‹", rx + 5, row + 4, Theme.TEXT_DIM, Role.LABEL);
        text(ctx, "›", rx + rw - 9, row + 4, Theme.TEXT_DIM, Role.LABEL);
        text(ctx, rv, rx + (rw - width(rv, Role.LABEL)) / 2, row + 4, Theme.TEXT, Role.LABEL);
        final int rxf = rx, rwf = rw;
        contentHit(rx, row, rx + rw, row + 16, b -> {
            double mxNow = client.mouse.getScaledX(client.getWindow());
            boolean forward = b == 0 && mxNow >= rxf + rwf / 2.0;
            w.replacePoint(p, p.withRange(stepRange(p.range(), forward)));
        });
    }

    private static int stepRange(int current, boolean up) {
        int i = 0;
        while (i < RANGES.length - 1 && RANGES[i] < current) i++;
        if (RANGES[i] != current) return RANGES[i]; // an odd value snaps to the nearest step
        int n = RANGES.length;
        return RANGES[((up ? i + 1 : i - 1) % n + n) % n];
    }

    /** Re-find the point being edited after a change (points are values: a change makes a new one). */
    @Override
    public void tick() {
        Waypoints w = wp();
        if (editing == null || w == null) return;
        if (w.allInWorld().contains(editing)) return;
        // Same name and dimension: the edited copy.
        for (Point p : w.allInWorld()) {
            if (p.name().equals(editing.name()) && p.dim().equals(editing.dim()) && p.x() == editing.x() && p.z() == editing.z()) {
                editing = p;
                return;
            }
        }
        for (Point p : w.allInWorld()) {
            if (p.created() == editing.created() && p.created() != 0 && p.dim().equals(editing.dim())) { editing = p; return; }
        }
        closeEditor();
    }

    private void openEditor(Point p) {
        commitName();
        editing = p;
        nameBuffer = p.name();
        nameFocused = false;
    }

    private void closeEditor() {
        commitName();
        editing = null;
        nameFocused = false;
    }

    private void commitName() {
        Waypoints w = wp();
        if (!nameFocused || editing == null || w == null || nameBuffer == null) { nameFocused = false; return; }
        nameFocused = false;
        String n = nameBuffer.strip();
        if (n.isEmpty() || n.equals(editing.name())) return;
        for (Point q : w.allInWorld()) {
            if (q != editing && q.dim().equals(editing.dim()) && q.name().equalsIgnoreCase(n) && !q.equals(editing)) {
                Notifications.push("Waypoints", "There's already a " + q.name() + " there", false);
                return;
            }
        }
        if (Waypoints.isDeath(new Point(n, editing.dim(), 0, 0, 0))) {
            Notifications.push("Waypoints", "Names like that are kept for death points", false);
            return;
        }
        Point renamed = editing.withName(n.length() > 32 ? n.substring(0, 32).strip() : n);
        if (w.replacePoint(editing, renamed)) editing = renamed;
    }

    private void delete(Point p) {
        Waypoints w = wp();
        if (w == null) return;
        if (p.equals(editing)) { editing = null; nameFocused = false; }
        int i = w.deletePoint(p);
        if (i < 0) return;
        undoPoint = p;
        undoIndex = i;
        undoAt = System.currentTimeMillis();
    }

    /** Typed into the chat box for you to send (or not), as plain text. Never while coordinates are hidden. */
    private void share(Point p) {
        Waypoints w = wp();
        if (w == null) return;
        if (w.coordsHidden()) {
            Notifications.push("Waypoints", StreamerMode.hidesCoordinates() ? "Streamer Mode keeps coordinates out of chat"
                    : "This server hides coordinates", false);
            return;
        }
        MinecraftClient.getInstance().setScreen(new ChatScreen(Waypoints.shareText(p), false));
    }

    @Override
    protected void renderOverlay(DrawContext ctx, int mx, int my) {
        long now = System.currentTimeMillis();
        if (undoPoint != null && now - undoAt < UNDO_MS) {
            String msg = "Deleted " + undoPoint.name();
            int bw = width(msg, Role.LABEL) + width("Undo", Role.LABEL) + 44;
            int x1 = wx + (ww - bw) / 2, y1 = wy + wh - 30;
            Theme.shadow(ctx, Theme.SH_CARD, x1, y1, x1 + bw, y1 + 22, 0x90000000);
            Theme.roundBordered(ctx, x1, y1, x1 + bw, y1 + 22, Theme.CARD, Theme.BORDER);
            text(ctx, msg, x1 + 10, y1 + 7, Theme.TEXT, Role.LABEL);
            button(ctx, "undo", "Undo", x1 + bw - 6, y1 + 3, mx, my, 1, b -> undo());
        } else {
            undoPoint = null;
            text(ctx, "Click a point to edit it · Share types its coordinates into chat for you to send", cx, wy + wh - 10, Theme.TEXT_FAINT, Role.SMALL);
        }
    }

    private void undo() {
        Waypoints w = wp();
        if (w == null || undoPoint == null) return;
        w.restorePoint(undoPoint, undoIndex);
        Notifications.push("Waypoints", "Restored " + undoPoint.name(), true);
        undoPoint = null;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int key = input.key();
        if (nameFocused) {
            switch (key) {
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> commitName();
                case GLFW.GLFW_KEY_ESCAPE -> { nameFocused = false; nameBuffer = editing == null ? null : editing.name(); }
                case GLFW.GLFW_KEY_BACKSPACE -> { if (nameBuffer != null && !nameBuffer.isEmpty()) nameBuffer = nameBuffer.substring(0, nameBuffer.length() - 1); }
                default -> {}
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_Z && (input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0 && undoPoint != null) { undo(); return true; }
        if (key == GLFW.GLFW_KEY_ESCAPE && !searchFocused) {
            if (editing != null) { closeEditor(); return true; }
            close();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (nameFocused) {
            if (input.isValidChar() && nameBuffer != null && nameBuffer.length() < 32) nameBuffer += input.asString();
            return true;
        }
        return super.charTyped(input);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        // A click outside the name field applies what was typed first.
        boolean inName = click.x() >= nameRect[0] && click.x() < nameRect[2] && click.y() >= nameRect[1] && click.y() < nameRect[3];
        if (nameFocused && !inName) commitName();
        return super.mouseClicked(click, doubled);
    }

    @Override
    public void removed() {
        commitName();
        super.removed();
    }
}
