package net.evadeclient.gui;

import net.evadeclient.module.misc.Schematics;
import net.evadeclient.schematic.Materials;
import net.evadeclient.schematic.SchematicVerifier;
import net.evadeclient.util.Notifications;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.Dropdown;
import net.evadeclient.gui.kit.EmptyState;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.Progress;
import net.evadeclient.util.Icons;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
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
    private static final int ROW_H = 30;
    private static final String[] SORTS = {"Left", "Still to get", "Name", "Needed"};
    private static int sort;

    public MaterialListScreen(Screen parent) {
        super("Material List", parent);
    }

    public static void open(Screen parent) {
        MinecraftClient.getInstance().setScreen(new MaterialListScreen(parent));
    }

    @Override protected int prefWidth() { return 680; }
    @Override protected int prefHeight() { return 460; }
    @Override protected boolean hasSearch() { return true; }
    @Override protected String searchHint() { return "Search items"; }
    @Override protected String headerIcon() { return "schematic"; }

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
        String s = m.placement().schematic.name + "  ·  what the build needs, what's placed and what you carry";
        var r = m.verifier().last();
        if (r != null && r.count(SchematicVerifier.UNLOADED) > 0) s = m.placement().schematic.name + "  ·  part of it isn't loaded (not counted as placed)";
        return s;
    }

    @Override
    protected void renderHeaderRight(DrawContext ctx, int right, int y, int mx, int my) {
        boolean any = mod() != null && mod().placement() != null;
        int w = Button.width(kit, "Copy list", Button.Size.SM, "copy", false);
        drawButton("copy", "Copy list", "copy", right - w, y, w, Button.Size.SM, SECONDARY, any, b -> copy());
        headerReach(right - w);
    }

    @Override
    protected void renderToolbar(DrawContext ctx, int x, int y, int right, int mx, int my) {
        int dw = 136;
        String lbl = "Sort";
        kit.textCentredY(lbl, right - dw - width(lbl, Role.LABEL) - Tokens.S3, y + 12, Tokens.N700, Role.LABEL);
        Dropdown.draw(kit, "sort", right - dw, y, dw, List.of(SORTS), SORTS[sort], true, o -> {
            for (int i = 0; i < SORTS.length; i++) if (SORTS[i].equals(o)) sort = i;
        });
    }

    /** Copies the list as plain text. */
    public void copy() {
        Schematics m = mod();
        if (m == null || m.placement() == null || client == null) return;
        client.keyboard.setClipboard(Materials.text(m.placement().schematic.name, rows()));
        Notifications.push("Material List", "Copied to the clipboard", true);
    }

    private boolean doneOpen;

    @Override
    protected int renderContent(DrawContext ctx, int top, int mx, int my) {
        List<Materials.Entry> list = rows();
        Schematics m = mod();
        if (m == null || m.placement() == null) {
            return EmptyState.draw(kit, "empty", cx + cw / 2, top + 30, "schematic", "No schematic loaded",
                    "Load one from Schematics in the menu, or with .schem in chat.", "Open Schematics",
                    () -> SchematicBrowserScreen.open(this)) + 50;
        }
        int y = top;
        y += summary(ctx, m.materials(), y) + Tokens.S5;
        if (list.isEmpty()) {
            return y - top + EmptyState.draw(kit, "empty", cx + cw / 2, y + 6, "search", "Nothing matches", "Try another item name.", null, null) + 30;
        }
        // Column right edges (numbers are right-aligned, tabular).
        int colNeeded = cx + cw - 300, colPlaced = cx + cw - 222, colLeft = cx + cw - 128, colHave = cx + cw - 14;
        if (inView(y, 16)) {
            caps("ITEM", cx + 40, y);
            capsR("NEEDED", colNeeded, y);
            capsR("PLACED", colPlaced, y);
            capsR("LEFT", colLeft, y);
            capsR("YOU HAVE", colHave, y);
        }
        y += 18;
        List<Materials.Entry> todo = new ArrayList<>(), done = new ArrayList<>();
        for (Materials.Entry e : list) (e.left() == 0 ? done : todo).add(e);
        int i = 0;
        for (Materials.Entry e : todo) {
            if (inView(y, ROW_H)) {
                float a = enter(i), f = Theme.fade;
                Theme.fade = f * a;
                try { row(ctx, e, y + Math.round((1 - a) * 6), mx, my, colNeeded, colPlaced, colLeft, colHave); } finally { Theme.fade = f; }
            }
            i++;
            y += ROW_H;
        }
        if (!done.isEmpty()) {
            y += Tokens.S3;
            String id = "done";
            boolean hv = mouseIn(mx, my, cx, y, cx + cw, y + 22);
            float h = hover(id, hv);
            if (inView(y, 22)) {
                if (h > 0.01f) Paint.rect(ctx, cx, y, cx + cw, y + 22, Tokens.R_SM, Theme.alpha(Tokens.N250, 0.5f * h));
                var mm = ctx.getMatrices();
                mm.pushMatrix();
                mm.translate(cx + 10, y + 11);
                mm.rotate((float) Math.toRadians(kit.anim("done#rot", doneOpen ? 90 : 0, Motion.SMOOTH)));
                Icons.draw(ctx, "chevron_r", -4, -4, 8, Tokens.N700);
                mm.popMatrix();
                Icons.draw(ctx, "check", cx + 22, y + 5, 12, Tokens.OK);
                UiFont.caps(ctx, textRenderer, "DONE", cx + 40, y + 7, Tokens.N700, Role.CAPS, 0.9f, smooth);
                int dw = UiFont.capsWidth(textRenderer, "DONE", Role.CAPS, 0.9f, smooth);
                kit.textCentredY(String.valueOf(done.size()), cx + 46 + dw, y + 11, Tokens.N600, Role.SMALL);
            }
            contentHit(id, cx, y, cx + cw, y + 22, b -> doneOpen = !doneOpen);
            y += 24;
            if (doneOpen) {
                for (Materials.Entry e : done) {
                    if (inView(y, ROW_H)) row(ctx, e, y, mx, my, colNeeded, colPlaced, colLeft, colHave);
                    y += ROW_H;
                }
            }
        }
        return y - top + 6;
    }

    /** The totals card: blocks left, the build's progress, and how many items you have all / some / none of. */
    private int summary(DrawContext ctx, List<Materials.Entry> all, int y) {
        int h = 58;
        if (!inView(y, h)) return h;
        int need = 0, placed = 0, ok = 0, part = 0, none = 0, missing = 0;
        for (Materials.Entry e : all) {
            need += e.needed();
            placed += e.placed();
            missing += e.missing();
            if (e.left() == 0) continue;
            if (e.missing() == 0) ok++; else if (e.inventory() > 0) part++; else none++;
        }
        int left = Materials.totalLeft(all);
        Paint.surface(ctx, cx, y, cx + cw, y + h, Tokens.R_MD, Tokens.E2.noAmbient(), 0, kit.mx - cx);
        String big = String.valueOf(left);
        int bx = cx + 14;
        UiFont.drawTabular(ctx, textRenderer, big, bx, Math.round(UiFont.centredY(Role.HEADLINE, y + 22, smooth)), Tokens.N950, Role.HEADLINE, smooth);
        int bw = UiFont.tabularWidth(textRenderer, big, Role.HEADLINE, smooth);
        kit.textCentredY(left == 1 ? "block left to place" : "blocks left to place", bx + bw + Tokens.S3, y + 23, Tokens.N800, Role.BODY);
        float v = need == 0 ? 0 : placed / (float) need;
        int px1 = bx, px2 = cx + cw / 2 + 40;
        Progress.linear(kit, px1, y + 38, px2 - px1, v, null);
        String pct = Math.round(v * 100) + "% built  ·  " + missing + " still to get";
        kit.textCentredY(pct, px2 + Tokens.S4, y + 40, Tokens.N700, Role.SMALL);
        // Status counts on the right, icon plus number (never colour alone).
        int rx = cx + cw - 14;
        rx = statusCount(ctx, rx, y + 20, "error", Tokens.DANGER, none, "none") - Tokens.S5;
        rx = statusCount(ctx, rx, y + 20, "warn", Tokens.WARN, part, "some") - Tokens.S5;
        statusCount(ctx, rx, y + 20, "check", Tokens.OK, ok, "all");
        return h;
    }

    private int statusCount(DrawContext ctx, int right, float mid, String icon, int color, int n, String label) {
        String t = n + " " + label;
        int tw = width(t, Role.LABEL);
        int x = right - tw;
        kit.textCentredY(t, x, mid, n == 0 ? Tokens.N600 : Tokens.N900, Role.LABEL);
        Icons.draw(ctx, icon, x - 15, Math.round(mid - 6), 12, n == 0 ? Theme.alpha(color, 0.4f) : color);
        return x - 15;
    }

    private void caps(String s, int x, int y) { UiFont.caps(kit.ctx, textRenderer, s, x, y + 4, Tokens.N600, Role.CAPS, 0.9f, smooth); }

    private void capsR(String s, int right, int y) { caps(s, right - UiFont.capsWidth(textRenderer, s, Role.CAPS, 0.9f, smooth), y); }

    private void num(DrawContext ctx, String s, int right, float mid, int color) {
        int w = UiFont.tabularWidth(textRenderer, s, Role.BODY, smooth);
        UiFont.drawTabular(ctx, textRenderer, s, right - w, Math.round(UiFont.centredY(Role.BODY, mid, smooth)), color, Role.BODY, smooth);
    }

    private void row(DrawContext ctx, Materials.Entry e, int y, int mx, int my, int colNeeded, int colPlaced, int colLeft, int colHave) {
        int y2 = y + ROW_H - 2;
        boolean hv = mouseIn(mx, my, cx, y, cx + cw, y2);
        float h = hover("mrow:" + e.item(), hv);
        rowSurface(cx, y, cx + cw, y2, h, false);
        float mid = (y + y2) / 2f;
        boolean done = e.left() == 0;
        Paint.rect(ctx, cx + 6, mid - 11, cx + 28, mid + 11, Tokens.R_XS, Tokens.N250);
        ctx.drawItem(new ItemStack(e.item()), cx + 9, Math.round(mid - 8));
        kit.textCentredY(ellipsize(Materials.name(e.item()), Role.BODY, colNeeded - cx - 100), cx + 40, mid, done ? Tokens.N700 : Tokens.N950, Role.BODY);
        num(ctx, String.valueOf(e.needed()), colNeeded, mid, Tokens.N800);
        num(ctx, String.valueOf(e.placed()), colPlaced, mid, Tokens.N800);
        num(ctx, String.valueOf(e.left()), colLeft, mid - 2, done ? Tokens.N700 : Tokens.N950);
        // A mini progress bar under Left: what of this item is already placed.
        int bw = 56;
        float v = e.needed() == 0 ? 1f : e.placed() / (float) e.needed();
        Paint.pill(ctx, colLeft - bw, y2 - 6, colLeft, y2 - 4, Tokens.N250);
        if (v > 0) Paint.pill(ctx, colLeft - bw, y2 - 6, colLeft - bw + Math.max(2, bw * v), y2 - 4, done ? Theme.alpha(Tokens.OK, 0.8f) : Tokens.A500);
        // You have: number and a status icon (all / some / none of what's left).
        String have = String.valueOf(e.inventory());
        String icon = done ? "check" : e.missing() == 0 ? "check" : e.inventory() > 0 ? "warn" : "error";
        int ic = done ? Theme.alpha(Tokens.OK, 0.5f) : e.missing() == 0 ? Tokens.OK : e.inventory() > 0 ? Tokens.WARN : Tokens.DANGER;
        int hw = UiFont.tabularWidth(textRenderer, have, Role.BODY, smooth);
        num(ctx, have, colHave, mid, done ? Tokens.N700 : Tokens.N900);
        Icons.draw(ctx, icon, colHave - hw - 16, Math.round(mid - 6), 12, ic);
        if (hv && !done) {
            String tip = e.missing() == 0 ? "You carry enough" : "Still to get: " + e.missing() + (Materials.stacks(e.missing()).isEmpty() ? "" : " (" + Materials.stacks(e.missing()) + ")");
            kit.tooltip("mrow:" + e.item(), tip, null, colHave - hw - 18, y, colHave, y2);
            contentHit("mrow:" + e.item(), colHave - hw - 18, y, colHave, y2, b -> {});
        }
    }
}
