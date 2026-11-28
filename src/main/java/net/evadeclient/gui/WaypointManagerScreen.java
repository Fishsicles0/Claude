package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.misc.StreamerMode;
import net.evadeclient.module.visuals.Waypoints;
import net.evadeclient.module.visuals.Waypoints.Point;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.util.Notifications;
import net.evadeclient.gui.kit.Badge;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.Dropdown;
import net.evadeclient.gui.kit.EmptyState;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.SearchField;
import net.evadeclient.gui.kit.Segmented;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.evadeclient.util.WaypointIcons;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
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
    private static final int ROW_H = 36, EDIT_H = 124;
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

    /** Opens the manager with {@code p}'s editor already open (from the World Map's right-click menu). */
    public static WaypointManagerScreen editing(Screen parent, Point p) {
        WaypointManagerScreen s = new WaypointManagerScreen(parent);
        if (p != null) s.openEditor(p);
        return s;
    }

    /** Test hook: the open manager's editor, so a test can drive it. */
    public Point editingForTest() { return editing; }

    private static Waypoints wp() { return EvadeClient.modules == null ? null : EvadeClient.modules.get(Waypoints.class); }

    @Override protected int prefWidth() { return 700; }
    @Override protected int prefHeight() { return 440; }
    @Override protected boolean hasSearch() { return true; }
    @Override protected String searchHint() { return "Search points, dimensions, icons"; }
    @Override protected String headerIcon() { return "waypoint"; }

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
        boolean can = client != null && client.player != null;
        int w = Button.width(kit, "Add here", Button.Size.SM, "plus", false);
        drawButton("add", "Add here", "plus", right - w, y, w, Button.Size.SM, PRIMARY, can, b -> addHere());
        headerReach(right - w);
    }

    @Override
    protected void renderToolbar(DrawContext ctx, int x, int y, int right, int mx, int my) {
        int dw = 132;
        String lbl = "Sort";
        int lw = width(lbl, Role.LABEL);
        kit.textCentredY(lbl, right - dw - lw - Tokens.S3, y + 12, Tokens.N700, Role.LABEL);
        Dropdown.draw(kit, "sort", right - dw, y, dw, List.of(SORTS), SORTS[sort], true, o -> {
            for (int i = 0; i < SORTS.length; i++) if (SORTS[i].equals(o)) sort = i;
        });
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
            return EmptyState.draw(kit, "empty", cx + cw / 2, top + 30, "waypoint", "No world", "Join a world to manage its waypoints.", null, null) + 50;
        }
        List<Point> list = points();
        if (list.isEmpty()) {
            boolean none = search.isBlank();
            return EmptyState.draw(kit, "empty", cx + cw / 2, top + 30, none ? "waypoint" : "search",
                    none ? "No waypoints yet" : "Nothing matches", none ? "Add one where you stand, or type .wp add <name> in chat." : "Try another name or a dimension.",
                    null, null) + 50;
        }
        boolean hideCoords = w.coordsHidden();
        int y = top, i = 0;
        for (Point p : list) {
            if (inView(y, ROW_H)) {
                float a = enter(i), f = Theme.fade;
                Theme.fade = f * a;
                try { renderRow(ctx, w, p, y + Math.round((1 - a) * 6), mx, my, hideCoords); } finally { Theme.fade = f; }
            }
            i++;
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
        int y2 = y + ROW_H - 3;
        boolean hv = mouseIn(mx, my, cx, y, cx + cw, y2);
        float h = hover("row:" + p.name() + p.dim(), hv);
        rowSurface(cx, y, cx + cw, y2, h, open);
        float mid = (y + y2) / 2f;
        int color = Waypoints.color(p);
        // The point's colour and icon in a tile, lit by its own colour.
        int tx1 = cx + 8, ty1 = Math.round(mid - 12);
        Paint.rect(ctx, tx1, ty1, tx1 + 24, ty1 + 24, Tokens.R_XS, Theme.lerpColor(Tokens.N250, color | 0xFF000000, p.hidden() ? 0.04f : 0.14f));
        Paint.ring(ctx, tx1, ty1, tx1 + 24, ty1 + 24, Tokens.R_XS, Theme.alpha(color | 0xFF000000, p.hidden() ? 0.15f : 0.35f), 1);
        if (WaypointIcons.has(p.icon())) WaypointIcons.draw(ctx, p.icon(), tx1 + 4, ty1 + 4, 2, color);
        else Paint.circle(ctx, tx1 + 12, ty1 + 12, 8, color | 0xFF000000);

        int tx = cx + 42;
        int nameColor = p.hidden() ? Tokens.N600 : Tokens.N950;
        String name = ellipsize(p.name(), Role.STRONG, cw / 2 - 60);
        kit.textCentredY(name, tx, mid - 6, nameColor, Role.STRONG);
        int bx = tx + width(name, Role.STRONG) + Tokens.S3;
        if (p.equals(w.target())) bx += Badge.draw(kit, bx, Math.round(mid - 6 - Badge.H / 2f), "Target", Badge.Tone.ACCENT) + Tokens.S2;
        if (p.hidden()) Badge.draw(kit, bx, Math.round(mid - 6 - Badge.H / 2f), "Hidden", Badge.Tone.NEUTRAL);
        double d = w.distanceTo(p);
        StringBuilder sub = new StringBuilder(Waypoints.dimensionName(p.dim()));
        if (!hideCoords) sub.append("  ·  ").append(p.x()).append(", ").append(p.y()).append(", ").append(p.z());
        if (p.range() > 0) sub.append("  ·  shows within ").append(p.range()).append(" m");
        kit.textCentredY(ellipsize(sub.toString(), Role.SMALL, cw - 42 - 330), tx, mid + 7, Tokens.N700, Role.SMALL);

        // Clicking the row itself opens or closes its editor (the buttons, registered after, are on top).
        contentHit("row:" + key(p), cx, y, cx + cw, y2, b -> { if (p.equals(editing)) closeEditor(); else openEditor(p); });
        int right = cx + cw - 8, by = Math.round(mid - 10);
        right = button("del:" + key(p), "Delete", null, right, by, DANGER, b -> delete(p)) - 2;
        right = button("share:" + key(p), "Share", null, right, by, GHOST, b -> share(p)) - 2;
        right = button("hide:" + key(p), p.hidden() ? "Show" : "Hide", null, right, by, GHOST,
                b -> w.replacePoint(p, p.withHidden(!p.hidden()))) - Tokens.S3;
        right = button("edit:" + key(p), open ? "Done" : "Edit", open ? "check" : "edit", right, by, open ? SELECTED : SECONDARY, b -> {
            if (open) closeEditor(); else openEditor(p);
        }) - Tokens.S5;
        // Distance: the row's number, tabular so the column doesn't jitter.
        String dist = d < 0 ? "other dim." : Math.round(d) + " m";
        int dw = d < 0 ? width(dist, Role.SMALL) : UiFont.tabularWidth(textRenderer, dist, Role.STRONG, smooth);
        if (d < 0) kit.textCentredY(dist, right - dw, mid, Tokens.N600, Role.SMALL);
        else UiFont.drawTabular(ctx, textRenderer, dist, right - dw, Math.round(UiFont.centredY(Role.STRONG, mid, smooth)), Tokens.N900, Role.STRONG, smooth);
    }

    private static String key(Point p) { return p.name() + "@" + p.dim(); }

    private void renderEditor(DrawContext ctx, Waypoints w, Point p, int y, int mx, int my) {
        Paint.surface(ctx, cx + 12, y - 1, cx + cw, y + EDIT_H - 6, Tokens.R_MD, Tokens.SUNKEN.withFill(Tokens.N150), 0, Float.NaN);
        Paint.pill(ctx, cx + 4, y + 6, cx + 6, y + EDIT_H - 13, Theme.alpha(Tokens.A500, 0.5f));
        int lx = cx + 26, vx = cx + 104;
        int row = y + 8;

        // Name
        label("Name", lx, row + 12);
        int fx2 = Math.min(cx + cw - 140, vx + 240);
        if (nameFocused && !kit.isFocused(NAME)) commitName(); // focus moved away (Tab): keep what was typed
        if (!kit.isFocused(NAME)) SearchField.set(kit, NAME, p.name());
        String typed = SearchField.draw(kit, NAME, vx, row, fx2 - vx, "Point name", null, false);
        if (typed.length() > 32) SearchField.set(kit, NAME, typed.substring(0, 32));
        nameFocused = kit.isFocused(NAME);
        if (nameFocused) nameBuffer = SearchField.text(kit, NAME);
        nameRect = new int[]{vx, row, fx2, row + 24};
        boolean isTarget = p.equals(w.target());
        int tw = Button.width(kit, isTarget ? "Clear target" : "Set as target", Button.Size.SM, "waypoint", false);
        drawButton("target:" + key(p), isTarget ? "Clear target" : "Set as target", isTarget ? null : "waypoint", cx + cw - 12 - tw, row + 2, tw,
                Button.Size.SM, isTarget ? SELECTED : SECONDARY, true, b -> w.setTarget(isTarget ? null : p));
        row += 30;

        // Colour: "A" is the automatic colour
        label("Colour", lx, row + 8);
        int sx = vx;
        int[] colors = new int[ColorSetting.PALETTE.length + 1];
        colors[0] = 0;
        System.arraycopy(ColorSetting.PALETTE, 0, colors, 1, ColorSetting.PALETTE.length);
        for (int c : colors) {
            boolean sel = (p.color() & 0xFFFFFF) == (c & 0xFFFFFF) && (p.color() == 0) == (c == 0);
            int shownC = c == 0 ? Waypoints.color(new Point(p.name(), p.dim(), 0, 0, 0)) : c;
            boolean sh = mouseIn(mx, my, sx - 1, row, sx + 15, row + 16);
            if (sel) Paint.ring(ctx, sx - 2.5f, row - 0.5f, sx + 16.5f, row + 16.5f, 4.5f, Tokens.N950, 2);
            else if (sh) Paint.ring(ctx, sx - 2.5f, row - 0.5f, sx + 16.5f, row + 16.5f, 4.5f, Theme.alpha(Tokens.N950, 0.35f), 1);
            Paint.rect(ctx, sx, row + 2, sx + 14, row + 14, Tokens.R_XS, shownC | 0xFF000000);
            if (c == 0) kit.textCentredY("A", sx + 7 - width("A", Role.TINY) / 2, row + 8, 0xCC000000, Role.TINY);
            final int pick = c;
            contentHit("col:" + c, sx - 1, row, sx + 15, row + 16, b -> w.replacePoint(p, p.withColor(pick)));
            sx += 19;
        }
        row += 24;

        // Icon
        label("Icon", lx, row + 9);
        int ix = vx;
        for (String icon : WaypointIcons.NAMES) {
            boolean none = icon.equals("None");
            boolean sel = none ? !WaypointIcons.has(p.icon()) : icon.equals(p.icon());
            boolean ih = mouseIn(mx, my, ix, row - 1, ix + 20, row + 19);
            int fill = sel ? Tokens.ACCENT_SURFACE : ih ? Tokens.N300 : Tokens.N250;
            Paint.rect(ctx, ix, row - 1, ix + 20, row + 19, Tokens.R_XS, fill);
            if (sel) Paint.ring(ctx, ix, row - 1, ix + 20, row + 19, Tokens.R_XS, Theme.alpha(Tokens.A500, 0.7f), 1);
            if (none) kit.textCentredY("–", ix + 10 - width("–", Role.BODY) / 2, row + 9, Tokens.N700, Role.BODY);
            else WaypointIcons.draw(ctx, icon, ix + 1, row + 1, 2, Waypoints.color(p));
            final String pick = none ? null : icon;
            contentHit("icon:" + icon, ix, row - 1, ix + 20, row + 19, b -> w.replacePoint(p, p.withIcon(pick)));
            ix += 23;
        }
        row += 26;

        // Beam and range
        label("Beam", lx, row + 11);
        List<String> beams = List.of("Default", "Always", "Never");
        int sw = Segmented.draw(kit, "beam", vx, row, beams, beams.get(Math.max(0, Math.min(2, p.beam()))), true,
                o -> w.replacePoint(p, p.withBeam(beams.indexOf(o))));
        int rx = vx + sw + 24;
        label("Show within", rx, row + 11);
        rx += width("Show within", Role.LABEL) + 10;
        List<String> ranges = new ArrayList<>();
        for (int r : RANGES) ranges.add(rangeLabel(r));
        Dropdown.draw(kit, "range", rx, row - 1, 120, ranges, rangeLabel(snapRange(p.range())), true, o -> {
            int idx = ranges.indexOf(o);
            if (idx >= 0) w.replacePoint(p, p.withRange(RANGES[idx]));
        });
    }

    private static final String NAME = "wp#name";

    private void label(String s, int x, int centreY) { kit.textCentredY(s, x, centreY, Tokens.N800, Role.LABEL); }

    private static String rangeLabel(int r) { return r == 0 ? "Any distance" : r + " m"; }

    private static int snapRange(int current) {
        int i = 0;
        while (i < RANGES.length - 1 && RANGES[i] < current) i++;
        return RANGES[i];
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
        SearchField.set(kit, NAME, p.name());
    }

    private void closeEditor() {
        commitName();
        editing = null;
        nameFocused = false;
    }

    private void commitName() {
        Waypoints w = wp();
        if (kit.isFocused(NAME)) { nameBuffer = SearchField.text(kit, NAME); kit.focus(null, false); nameFocused = true; }
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

    @Override protected int footerHeight() { return 30; }

    @Override
    protected void renderFooter(DrawContext ctx, int x1, int y, int x2, int mx, int my) {
        Icons.draw(ctx, "info", x1, y + 9, 12, Tokens.N600);
        kit.textCentredY("Click a point to edit it · Share types its coordinates into chat for you to send", x1 + 18, y + 15, Tokens.N700, Role.SMALL);
    }

    @Override
    protected void renderOverlay(DrawContext ctx, int mx, int my) {
        long now = System.currentTimeMillis();
        if (undoPoint != null && now - undoAt < UNDO_MS) {
            // A snackbar over the footer: e4, the message and Undo.
            String msg = "Deleted " + undoPoint.name();
            int bw = width(msg, Role.BODY) + Button.width(kit, "Undo", Button.Size.SM, "reset", false) + 40;
            float in = Motion.decorative() ? Motion.standard(Motion.clamp01((now - undoAt) / (float) Motion.DUR_BASE)) : 1f;
            int x1 = wx + (ww - bw) / 2, y1 = wy + wh - 36 + Math.round((1 - in) * 8);
            float f = Theme.fade;
            Theme.fade = f * Math.min(1f, in * 1.5f);
            try {
                Paint.surface(ctx, x1, y1, x1 + bw, y1 + 28, Tokens.R_MD, Tokens.E4, 0, Float.NaN);
                Icons.draw(ctx, "check", x1 + 10, y1 + 8, 12, Tokens.OK);
                kit.textCentredY(msg, x1 + 28, y1 + 14, Tokens.N950, Role.BODY);
                button("undo", "Undo", "reset", x1 + bw - 4, y1 + 4, SECONDARY, b -> undo());
                // Time left, as a hairline along the bottom.
                float left = 1f - (now - undoAt) / (float) UNDO_MS;
                Paint.rect(ctx, x1 + 8, y1 + 26, x1 + 8 + (bw - 16) * left, y1 + 27, 0.5f, Theme.alpha(Tokens.OK, 0.6f));
            } finally {
                Theme.fade = f;
            }
        } else {
            undoPoint = null;
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
        if (kit.isFocused(NAME)) {
            switch (key) {
                case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> { commitName(); return true; }
                case GLFW.GLFW_KEY_ESCAPE -> {
                    kit.focus(null, false);
                    nameFocused = false;
                    nameBuffer = editing == null ? null : editing.name();
                    if (editing != null) SearchField.set(kit, NAME, editing.name());
                    return true;
                }
                default -> { return super.keyPressed(input); }
            }
        }
        if (key == GLFW.GLFW_KEY_Z && (input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0 && undoPoint != null) { undo(); return true; }
        if (key == GLFW.GLFW_KEY_ESCAPE && !searchFocused() && kit.popup() == null) {
            if (editing != null) { closeEditor(); return true; }
            close();
            return true;
        }
        return super.keyPressed(input);
    }


    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        // A click outside the name field applies what was typed first.
        boolean inName = click.x() >= nameRect[0] && click.x() < nameRect[2] && click.y() >= nameRect[1] && click.y() < nameRect[3];
        if (kit.isFocused(NAME) && !inName) commitName();
        return super.mouseClicked(click, doubled);
    }

    @Override
    public void removed() {
        commitName();
        super.removed();
    }
}
