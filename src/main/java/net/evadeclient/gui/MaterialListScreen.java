package net.evadeclient.gui;

import net.evadeclient.module.misc.Schematics;
import net.evadeclient.schematic.Materials;
import net.evadeclient.schematic.SchematicVerifier;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The material list for the loaded schematic: each item with how many the build needs, how many are already in
 * place (blocks the client can see), how many are left and how many you carry. Sort it, search it, copy it as text.
 */
public final class MaterialListScreen extends EvadePanelScreen {
    private static final int ROW_H = 24;
    private static final String[] SORTS = {"Left", "Still to get", "Name", "Needed"};
    private static int sort;

    public MaterialListScreen(Screen parent) {
        super("Material List", parent);
    }

    public static void open(Screen parent) {
        MinecraftClient.getInstance().setScreen(new MaterialListScreen(parent));
    }

    @Override protected int prefWidth() { return 620; }
    @Override protected int prefHeight() { return 420; }
    @Override protected boolean hasSearch() { return true; }

    private static Schematics mod() { return Schematics.get(); }

    /** The list as shown: searched and sorted. */
    public List<Materials.Entry> rows() {
        Schematics m = mod();
        if (m == null || m.placement() == null) return List.of();
        List<Materials.Entry> list = new ArrayList<>(m.materials());
        String q = search.strip().toLowerCase(Locale.ROOT);
        if (!q.isEmpty()) list.removeIf(e -> !Materials.name(e.item()).toLowerCase(Locale.ROOT).contains(q));
        Comparator<Materials.Entry> byName = Comparator.comparing(e -> Materials.name(e.item()));
        switch (sort) {
            case 1 -> list.sort(Comparator.comparingInt(Materials.Entry::missing).reversed().thenComparing(byName));
            case 2 -> list.sort(byName);
            case 3 -> list.sort(Comparator.comparingInt(Materials.Entry::needed).reversed().thenComparing(byName));
            default -> { /* already most left first */ }
        }
        return list;
    }

    @Override
    protected String subtitle() {
        Schematics m = mod();
        if (m == null || m.placement() == null) return "No schematic loaded";
        List<Materials.Entry> all = m.materials();
        int left = Materials.totalLeft(all), missing = 0;
        for (Materials.Entry e : all) missing += e.missing();
        String s = m.placement().schematic.name + "  ·  " + left + " blocks left to place  ·  " + missing + " still to get";
        var r = m.verifier().last();
        if (r != null && r.count(SchematicVerifier.UNLOADED) > 0) s += "  ·  part of it isn't loaded (not counted as placed)";
        return s;
    }

    @Override
    protected void renderHeaderRight(DrawContext ctx, int right, int y, int mx, int my) {
        int x = button(ctx, "copy", "Copy", right, y, mx, my, 1, b -> copy());
        button(ctx, "sort", "Sort: " + SORTS[sort], x - 6, y, mx, my, 0, b -> sort = (sort + (b == 1 ? SORTS.length - 1 : 1)) % SORTS.length);
    }

    /** Copies the list as plain text. */
    public void copy() {
        Schematics m = mod();
        if (m == null || m.placement() == null || client == null) return;
        client.keyboard.setClipboard(Materials.text(m.placement().schematic.name, rows()));
        Notifications.push("Material List", "Copied to the clipboard", true);
    }

    @Override
    protected int renderContent(DrawContext ctx, int top, int mx, int my) {
        List<Materials.Entry> list = rows();
        if (list.isEmpty()) {
            String msg = mod() == null || mod().placement() == null ? "Load a schematic first (Schematics in the menu, or .schem list)." : "Nothing matches.";
            text(ctx, msg, cx + (cw - width(msg, Role.BODY)) / 2, top + 30, Theme.TEXT_DIM, Role.BODY);
            return 60;
        }
        int colNeeded = cx + cw - 300, colPlaced = cx + cw - 230, colLeft = cx + cw - 160, colHave = cx + cw - 90;
        int y = top;
        text(ctx, "ITEM", cx + 30, y + 2, Theme.TEXT_HEADER, Role.SMALL);
        text(ctx, "NEEDED", colNeeded, y + 2, Theme.TEXT_HEADER, Role.SMALL);
        text(ctx, "PLACED", colPlaced, y + 2, Theme.TEXT_HEADER, Role.SMALL);
        text(ctx, "LEFT", colLeft, y + 2, Theme.TEXT_HEADER, Role.SMALL);
        text(ctx, "YOU HAVE", colHave, y + 2, Theme.TEXT_HEADER, Role.SMALL);
        y += 14;
        for (Materials.Entry e : list) {
            if (inView(y, ROW_H)) {
                boolean hv = mouseIn(mx, my, cx, y, cx + cw, y + ROW_H - 2);
                float h = hover("mrow:" + e.item(), hv);
                Theme.round(ctx, cx, y, cx + cw, y + ROW_H - 2, Theme.lerpColor(Theme.ROW, Theme.ROW_HOVER, h));
                ctx.drawItem(new ItemStack(e.item()), cx + 6, y + 3);
                boolean done = e.left() == 0;
                text(ctx, ellipsize(Materials.name(e.item()), Role.BODY, colNeeded - cx - 40), cx + 30, y + 7, done ? Theme.TEXT_FAINT : Theme.TEXT, Role.BODY);
                text(ctx, count(e.needed()), colNeeded, y + 7, Theme.TEXT_SOFT, Role.BODY);
                text(ctx, count(e.placed()), colPlaced, y + 7, Theme.TEXT_SOFT, Role.BODY);
                text(ctx, count(e.left()), colLeft, y + 7, done ? Theme.OK : Theme.TEXT, Role.BODY);
                int haveColor = e.left() == 0 ? Theme.TEXT_FAINT : e.missing() == 0 ? Theme.OK : e.inventory() > 0 ? Theme.WARN : Theme.DANGER;
                text(ctx, count(e.inventory()), colHave, y + 7, haveColor, Role.BODY);
            }
            y += ROW_H;
        }
        return y - top + 6;
    }

    private static String count(int n) {
        String st = Materials.stacks(n);
        return st.isEmpty() ? String.valueOf(n) : n + " (" + (n / 64) + "s)";
    }
}
