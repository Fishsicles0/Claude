package net.evadeclient.gui;

import net.evadeclient.module.misc.Schematics;
import net.evadeclient.schematic.Placement;
import net.evadeclient.schematic.SchematicIO;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
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
    private static final int ROW_H = 30, STRIP_H = 58;
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

    @Override protected int prefWidth() { return 620; }
    @Override protected int prefHeight() { return 420; }
    @Override protected boolean hasSearch() { return true; }

    @Override
    protected String subtitle() {
        Schematics m = mod();
        if (m != null && m.blockedByServer()) return "This server has switched Schematics off";
        return files.size() + " file" + (files.size() == 1 ? "" : "s") + " in .minecraft/schematics  ·  .litematic, .schem, .nbt";
    }

    @Override
    protected void renderHeaderRight(DrawContext ctx, int right, int y, int mx, int my) {
        int x = button(ctx, "folder", "Open folder", right, y, mx, my, 0, b -> Schematics.openFolder());
        button(ctx, "refresh", "Refresh", x - 6, y, mx, my, 0, b -> listedAt = 0);
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
        if (m != null && m.placement() != null) {
            if (inView(y, STRIP_H)) renderLoaded(ctx, m, y, mx, my);
            y += STRIP_H + 6;
        }
        List<String> list = shown();
        if (list.isEmpty()) {
            String msg = search.isBlank() ? "No schematics yet. Put .litematic, .schem or .nbt files in the folder (Open folder)."
                    : "No files match \"" + search.strip() + "\"";
            text(ctx, msg, cx + (cw - width(msg, Role.BODY)) / 2, y + 24, Theme.TEXT_DIM, Role.BODY);
            return y - top + 60;
        }
        for (String f : list) {
            if (inView(y, ROW_H)) renderRow(ctx, m, f, y, mx, my);
            y += ROW_H;
        }
        return y - top + 6;
    }

    private void renderLoaded(DrawContext ctx, Schematics m, int y, int mx, int my) {
        Placement p = m.placement();
        Theme.round(ctx, cx, y, cx + cw, y + STRIP_H, Theme.tint(0.08f));
        Theme.round(ctx, cx, y + 6, cx + 2, y + STRIP_H - 6, Theme.RED, 1);
        var o = p.origin();
        text(ctx, ellipsize(p.schematic.name, Role.STRONG, cw / 2), cx + 12, y + 6, Theme.TEXT, Role.STRONG);
        String sub = p.schematic.format + "  ·  " + p.sizeX() + " x " + p.sizeY() + " x " + p.sizeZ() + "  ·  at " + o.getX() + ", " + o.getY() + ", " + o.getZ()
                + "  ·  " + p.rotation() * 90 + "°" + (p.mirror() != Placement.MIRROR_NONE ? "  ·  mirror " + Placement.mirrorName(p.mirror()) : "");
        text(ctx, ellipsize(sub, Role.SMALL, cw - 24), cx + 12, y + 19, Theme.TEXT_DIM, Role.SMALL);
        int right = cx + cw - 6, by = y + STRIP_H - 22;
        right = button(ctx, "unload", "Unload", right, by, mx, my, 2, b -> m.unload()) - 4;
        right = button(ctx, "mats", "Materials", right, by, mx, my, 1, b -> MaterialListScreen.open(this)) - 4;
        right = button(ctx, "verify", "Verifier: " + (setting(m, "Verifier") ? "On" : "Off"), right, by, mx, my, 0,
                b -> toggle(m, "Verifier")) - 4;
        right = button(ctx, "layers", "Layers: " + mode(m, "Layers"), right, by, mx, my, 0, b -> cycle(m, "Layers", b == 1)) - 4;
        right = button(ctx, "mirror", "Mirror", right, by, mx, my, 0, b -> m.setMirror((p.mirror() + 1) % 3)) - 4;
        right = button(ctx, "rotate", "Rotate", right, by, mx, my, 0, b -> m.rotate(b != 1)) - 4;
        button(ctx, "here", "Place here", right, by, mx, my, 0, b -> {
            if (client != null && client.player != null) m.moveTo(client.player.getBlockPos());
        });
    }

    private void renderRow(DrawContext ctx, Schematics m, String f, int y, int mx, int my) {
        boolean loaded = m != null && m.placement() != null && f.equals(m.placement().file);
        boolean hv = mouseIn(mx, my, cx, y, cx + cw, y + ROW_H - 3);
        float h = hover("row:" + f, hv);
        Theme.round(ctx, cx, y, cx + cw, y + ROW_H - 3, loaded ? Theme.tint(0.06f) : Theme.lerpColor(Theme.ROW, Theme.ROW_HOVER, h));
        text(ctx, ellipsize(SchematicIO.baseName(f), Role.STRONG, cw - 140), cx + 10, y + 5, Theme.TEXT, Role.STRONG);
        text(ctx, ellipsize(describe(f), Role.SMALL, cw - 140), cx + 10, y + 17, Theme.TEXT_DIM, Role.SMALL);
        contentHit(cx, y, cx + cw, y + ROW_H - 3, b -> load(f));
        button(ctx, "load:" + f, loaded ? "Reload" : "Load", cx + cw - 6, y + 5, mx, my, loaded ? 0 : 1, b -> load(f));
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
