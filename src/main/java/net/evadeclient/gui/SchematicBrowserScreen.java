package net.evadeclient.gui;

import net.evadeclient.module.misc.Schematics;
import net.evadeclient.schematic.Placement;
import net.evadeclient.schematic.SchematicIO;
import net.evadeclient.util.Notifications;
import net.evadeclient.gui.kit.Badge;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.Dropdown;
import net.evadeclient.gui.kit.EmptyState;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.Progress;
import net.evadeclient.gui.kit.Toggle;
import net.evadeclient.schematic.Materials;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Schematics: the files in the schematics folder (newest first, searchable) with a Load button each, and a strip
 * for the loaded one: place it at your feet, turn, mirror, layers, the verifier and the material list. Only reads
 * files and changes what Evade draws.
 */
public final class SchematicBrowserScreen extends EvadePanelScreen {
    private static final int ROW_H = 34, CARD_H = 92;
    private List<String> files = List.of();
    private long listedAt;

    public SchematicBrowserScreen(Screen parent) {
        super("Schematics", parent);
    }

    /** Opens the browser over {@code parent} (null: over the game). */
    public static void open(Screen parent) {
        MinecraftClient.getInstance().setScreen(new SchematicBrowserScreen(parent));
    }

    /** The menu's sidebar entry. */
    public static void openFromMenu() { open(MinecraftClient.getInstance().currentScreen); }

    private static Schematics mod() { return Schematics.get(); }

    @Override protected int prefWidth() { return 680; }
    @Override protected int prefHeight() { return 440; }
    @Override protected boolean hasSearch() { return true; }
    @Override protected String searchHint() { return "Search schematics"; }
    @Override protected String headerIcon() { return "schematic"; }

    @Override
    protected String subtitle() {
        Schematics m = mod();
        if (m != null && m.blockedByServer()) return "This server has switched Schematics off";
        return files.size() + " file" + (files.size() == 1 ? "" : "s") + " · .litematic, .schem, .nbt";
    }

    @Override
    protected void renderHeaderRight(DrawContext ctx, int right, int y, int mx, int my) {
        int x = button("folder", "Open folder", "folder", right, y, GHOST, b -> Schematics.openFolder()) - Tokens.S1;
        button("refresh", "Refresh", "reset", x, y, GHOST, b -> listedAt = 0);
    }

    private List<String> shown() {
        if (System.currentTimeMillis() - listedAt > 2000) {
            files = Schematics.files();
            listedAt = System.currentTimeMillis();
        }
        String q = search.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return files;
        List<String> out = new ArrayList<>();
        for (String f : files) if (f.toLowerCase(Locale.ROOT).contains(q)) out.add(f);
        return out;
    }

    @Override
    protected int renderContent(DrawContext ctx, int top, int mx, int my) {
        Schematics m = mod();
        int y = top;
        boolean loaded = m != null && m.placement() != null;
        if (loaded) {
            if (inView(y, 20)) section("LOADED", null, cx, y - 2);
            y += 20;
            if (inView(y, CARD_H)) renderLoaded(ctx, m, y, mx, my);
            y += CARD_H + Tokens.S6;
        }
        List<String> list = shown();
        if (inView(y, 20)) section("FILES", String.valueOf(list.size()), cx, y - 2);
        y += 20;
        if (list.isEmpty()) {
            boolean none = search.isBlank();
            int h = EmptyState.draw(kit, "empty", cx + cw / 2, y + 10, none ? "schematic" : "search",
                    none ? "No schematics yet" : "Nothing matches",
                    none ? "Put .litematic, .schem or .nbt files in the schematics folder." : "Try part of the file name.",
                    none ? "Open folder" : null, Schematics::openFolder);
            return y - top + h + 30;
        }
        int i = 0;
        for (String f : list) {
            if (inView(y, ROW_H)) {
                float a = enter(i), fd = Theme.fade;
                Theme.fade = fd * a;
                try { renderRow(ctx, m, f, y + Math.round((1 - a) * 6), mx, my, !loaded); } finally { Theme.fade = fd; }
            }
            i++;
            y += ROW_H;
        }
        return y - top + 6;
    }

    /** The loaded schematic: an e3 card with the accent edge, its facts and three grouped tool clusters. */
    private void renderLoaded(DrawContext ctx, Schematics m, int y, int mx, int my) {
        Placement p = m.placement();
        int x1 = cx, x2 = cx + cw, y2 = y + CARD_H;
        Paint.surface(ctx, x1, y, x2, y2, Tokens.R_MD, Tokens.E3, 0, kit.mx - x1);
        Paint.hgradient(ctx, x1, y, x1 + cw * 0.45f, y2, Tokens.R_MD, Theme.alpha(Tokens.A900, 0.42f), Theme.alpha(Tokens.A900, 0f));
        Paint.slant(ctx, x1 + 1, y + 8, x1 + 3, y2 - 8, Tokens.A500);
        var o = p.origin();
        // Line 1: icon tile, name, facts, Unload.
        int tx = x1 + 14;
        float l1 = y + 20;
        Paint.rect(ctx, tx, l1 - 12, tx + 24, l1 + 12, Tokens.R_XS, Theme.alpha(Tokens.A900, 0.7f));
        Icons.draw(ctx, "schematic", tx + 5, Math.round(l1 - 7), 14, Tokens.A400);
        tx += 24 + Tokens.S4;
        String name = ellipsize(p.schematic.name, Role.STRONG, cw / 3);
        kit.textCentredY(name, tx, l1 - 6, Tokens.N950, Role.STRONG);
        String facts = p.schematic.format + "  ·  " + p.sizeX() + " × " + p.sizeY() + " × " + p.sizeZ() + "  ·  at " + o.getX() + ", " + o.getY() + ", " + o.getZ()
                + "  ·  " + p.rotation() * 90 + "°" + (p.mirror() != Placement.MIRROR_NONE ? "  ·  mirrored " + Placement.mirrorName(p.mirror()) : "");
        kit.textCentredY(ellipsize(facts, Role.SMALL, cw - 150), tx, l1 + 7, Tokens.N700, Role.SMALL);
        button("unload", "Unload", "close", x2 - 10, Math.round(l1 - 10), DANGER, b -> m.unload());

        // Line 2: PLACE · ROTATE · MIRROR | VIEW (layers, verifier) | Materials and the build's progress.
        int ly = y + 44, by = ly + 14;
        Paint.fadeLine(ctx, x1 + 12, ly - 4, x2 - 12, Theme.alpha(0xFFFFFFFF, 0.05f));
        int gx = x1 + 14;
        gx = cluster("PLACE", gx, ly);
        int pw = Button.width(kit, "Place here", Button.Size.SM, null, false);
        drawButton("here", "Place here", null, gx - pw, by, pw, Button.Size.SM, PRIMARY, client != null && client.player != null, b -> {
            if (client != null && client.player != null) m.moveTo(client.player.getBlockPos());
        });
        gx += Tokens.S6;
        int rx = cluster("ROTATE", gx, ly);
        glyphButton("rot-ccw", rx - 48, by - 2, 24, "rotate_ccw", "Turn left (anticlockwise)", true, false, b -> m.rotate(false));
        glyphButton("rot-cw", rx - 24, by - 2, 24, "rotate_cw", "Turn right (clockwise)", true, false, b -> m.rotate(true));
        gx = Math.max(rx, gx + 48) + Tokens.S6;
        int mxp = cluster("MIRROR", gx, ly);
        glyphButton("mirror", mxp - 24, by - 2, 24, "mirror", "Mirror: " + Placement.mirrorName(p.mirror()) + " (click for the next)",
                true, p.mirror() != Placement.MIRROR_NONE, b -> m.setMirror((p.mirror() + (b == 1 ? 2 : 1)) % 3));
        gx = Math.max(mxp, gx + 24) + Tokens.S5;
        // divider
        Paint.rect(ctx, gx, ly + 2, gx + 1, y2 - 10, 0, Theme.alpha(0xFFFFFFFF, 0.06f));
        gx += Tokens.S5;
        cluster("LAYERS", gx, ly);
        ModeSettingRef layers = modeSetting(m, "Layers");
        int dw = 104;
        if (layers != null) {
            Dropdown.draw(kit, "layers", gx, by - 2, dw, layers.modes(), layers.value(), true, layers.set());
        }
        gx += dw + Tokens.S5;
        cluster("VERIFIER", gx, ly);
        Toggle.draw(kit, "verify", gx, by + 3, false, setting(m, "Verifier"), true, () -> toggle(m, "Verifier"));
        gx += Toggle.MD_W + Tokens.S6;
        // Materials and progress, on the right.
        int matW = Button.width(kit, "Materials", Button.Size.SM, "list", false);
        int mx2 = x2 - 12;
        drawButton("mats", "Materials", "list", mx2 - matW, by, matW, Button.Size.SM, SECONDARY, true, b -> MaterialListScreen.open(this));
        int px2 = mx2 - matW - Tokens.S5, px1 = Math.max(gx, px2 - 140);
        if (px2 - px1 >= 60) {
            List<Materials.Entry> mats = m.materials();
            int need = 0, placed = 0;
            for (Materials.Entry e : mats) { need += e.needed(); placed += e.placed(); }
            float v = need == 0 ? 0f : placed / (float) need;
            Progress.linear(kit, px1, ly - 2, px2 - px1, v, "Built");
        }
    }

    /** A cluster's CAPS label at (x, y); returns the x where its controls end (at least the label's width). */
    private int cluster(String label, int x, int y) {
        UiFont.caps(kit.ctx, textRenderer, label, x, y, Tokens.N600, Role.CAPS, 0.9f, smooth);
        return x + UiFont.capsWidth(textRenderer, label, Role.CAPS, 0.9f, smooth);
    }

    private void renderRow(DrawContext ctx, Schematics m, String f, int y, int mx, int my, boolean primaryLoad) {
        boolean loaded = m != null && m.placement() != null && f.equals(m.placement().file);
        int y2 = y + ROW_H - 2;
        String id = "row:" + f;
        boolean hv = mouseIn(mx, my, cx, y, cx + cw, y2);
        boolean focus = kit.isFocused(id) || kit.isFocused("load:" + f);
        float h = hover(id, hv);
        rowSurface(cx, y, cx + cw, y2, h, false);
        float mid = (y + y2) / 2f;
        int tx = cx + 8;
        Paint.rect(ctx, tx, mid - 11, tx + 22, mid + 11, Tokens.R_XS, loaded ? Theme.alpha(Tokens.A900, 0.7f) : Tokens.N250);
        Icons.draw(ctx, "schematic", tx + 4, Math.round(mid - 7), 14, loaded ? Tokens.A400 : Tokens.N800);
        tx += 22 + Tokens.S4;
        String base = ellipsize(SchematicIO.baseName(f), Role.STRONG, cw / 2 - 40);
        kit.textCentredY(base, tx, mid, Tokens.N950, Role.STRONG);
        int dx = tx + width(base, Role.STRONG) + Tokens.S4;
        if (loaded) dx += Badge.draw(kit, dx, Math.round(mid - Badge.H / 2f), "Loaded", Badge.Tone.ACCENT) + Tokens.S4;
        kit.textCentredY(ellipsize(describe(f), Role.SMALL, cx + cw - 110 - dx), dx, mid, Tokens.N700, Role.SMALL);
        contentHit(id, cx, y, cx + cw, y2, b -> load(f));
        // Load shows on hover or keyboard focus only (the row click loads too).
        if (h > 0.02f || focus) {
            float fd = Theme.fade;
            Theme.fade = fd * Math.max(h, focus ? 1f : 0f);
            try {
                button("load:" + f, loaded ? "Reload" : "Load", null, cx + cw - 8, Math.round(mid - 10),
                        loaded ? GHOST : primaryLoad ? PRIMARY : SECONDARY, b -> load(f));
            } finally {
                Theme.fade = fd;
            }
        }
    }

    private record ModeSettingRef(List<String> modes, String value, java.util.function.Consumer<String> set) {}

    private static ModeSettingRef modeSetting(Schematics m, String name) {
        for (var s : m.getSettings())
            if (s.getName().equals(name) && s instanceof net.evadeclient.setting.ModeSetting md)
                return new ModeSettingRef(md.getModes(), md.get(), v -> { md.set(v); });
        return null;
    }

    private static String describe(String f) {
        String n = f.toLowerCase(Locale.ROOT);
        String kind = n.endsWith(".litematic") ? "Litematic" : n.endsWith(".nbt") ? "Structure" : "Sponge schematic";
        Path p = Schematics.folder().resolve(f);
        try {
            long kb = Math.max(1, Files.size(p) / 1024);
            return kind + "  ·  " + (kb >= 1024 ? String.format(Locale.ROOT, "%.1f MB", kb / 1024.0) : kb + " KB") + (f.contains("/") ? "  ·  " + f : "");
        } catch (Exception e) {
            return kind;
        }
    }

    private void load(String f) {
        Schematics m = mod();
        if (m == null) return;
        if (m.blockedByServer()) { Notifications.push("Schematics", "Disabled by this server", false); return; }
        if (client == null || client.world == null) { Notifications.push("Schematics", "Join a world to place a schematic", false); return; }
        if (!m.isEnabled()) m.setEnabled(true);
        if (m.isEnabled()) m.load(f);
    }

    private static boolean setting(Schematics m, String name) {
        for (var s : m.getSettings()) if (s.getName().equals(name) && s.get() instanceof Boolean b) return b;
        return false;
    }

    private static void toggle(Schematics m, String name) {
        for (var s : m.getSettings()) if (s.getName().equals(name) && s instanceof net.evadeclient.setting.BooleanSetting b) b.toggle();
    }

    private static String mode(Schematics m, String name) {
        for (var s : m.getSettings()) if (s.getName().equals(name)) return String.valueOf(s.get());
        return "";
    }

    private static void cycle(Schematics m, String name, boolean back) {
        for (var s : m.getSettings()) if (s.getName().equals(name) && s instanceof net.evadeclient.setting.ModeSetting md) md.cycle(!back);
    }
}
