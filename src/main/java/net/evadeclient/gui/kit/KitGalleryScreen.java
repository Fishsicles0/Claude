package net.evadeclient.gui.kit;

import net.evadeclient.render.ui.UiShape;
import net.evadeclient.util.Notifications;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Every kit component in every state on one screen, for screenshots and visual review (chat: {@code .kitgallery}).
 * The page is laid out in 1280 x 760 design pixels and scaled to fit the window, so it shows everything at any GUI
 * scale. Hover, press, focus and disabled are pinned with {@link Kit#force}; the right column's controls are live.
 * D toggles the dialog, Escape closes.
 */
public final class KitGalleryScreen extends Screen {
    public static final int DW = 1280, DH = 760;

    private final Screen parent;
    private final Kit kit = new Kit();
    private float scale = 1f, ox, oy;
    /** Frames drawn and the shader shapes they submitted (tests). */
    public int frames;
    public long shapesLastFrame;
    /** Tests: the components drawn last frame, by kind. */
    public final java.util.Set<String> drawn = new java.util.TreeSet<>();

    // live demo state
    private boolean liveToggle = true;
    private double liveSlider = 0.6;
    private String liveDrop = "Hold", liveSeg = "All", liveTab = "Friends", liveNav = "mods";
    private int liveColor = 0xFFE11D2E;
    public boolean dialog;
    private long dialogAt;

    public KitGalleryScreen(Screen parent) {
        super(Text.literal("Evade kit gallery"));
        this.parent = parent;
    }

    @Override public boolean shouldPause() { return false; }

    @Override public void close() { if (client != null) client.setScreen(parent); }

    /** Show or hide the dialog demo. */
    public void dialog(boolean open) { dialog = open; dialogAt = System.currentTimeMillis(); }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        long before = UiShape.submitted;
        drawn.clear();
        scale = Math.min(width / (float) DW, height / (float) DH);
        ox = (width - DW * scale) / 2f;
        oy = (height - DH * scale) / 2f;
        Paint.scrim(ctx, width, height, 1f);
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(ox, oy);
        m.scale(scale, scale);
        kit.begin(ctx, Math.round((mouseX - ox) / scale), Math.round((mouseY - oy) / scale));
        kit.instantTooltips = true;
        try {
            page();
            if (dialog) {
                Dialog.draw(kit, "dlg", "Delete this profile?", "\"PvP\" and its settings will be removed from this computer. This can't be undone.",
                        List.of(new Dialog.Action("Cancel", Button.Variant.GHOST, () -> dialog = false),
                                new Dialog.Action("Delete", Button.Variant.DANGER, () -> dialog = false)), dialogAt);
                drawn.add("dialog");
            }
            kit.end();
        } finally {
            m.popMatrix();
        }
        frames++;
        shapesLastFrame = UiShape.submitted - before;
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // A plain dark backdrop: the page draws its own scrim.
        ctx.fill(0, 0, width, height, 0xFF000000 | (Tokens.N0 & 0xFFFFFF));
    }

    private static final int[] STATES = {0, Kit.HOVER, Kit.PRESSED, Kit.FOCUSED, Kit.DISABLED};
    private static final String[] STATE_NAMES = {"Idle", "Hover", "Pressed", "Focus", "Disabled"};

    private void label(String s, int x, int y) { kit.text(s, x, y, Tokens.N700, Role.LABEL); }

    private void page() {
        Kit k = kit;
        Surface.window(k, 8, 8, DW - 8, DH - 8);
        Surface.header(k, "gal", 8, 8, DW - 8, "Kit gallery", "Every component in every state  ·  " + (Paint.gpu() ? "shader shapes" : "texture fallback"),
                null, this::close);
        drawn.add("window");

        // ---------------------------------------------------------------- column 1: buttons, toggles, sliders
        int x = 28, y = 60;
        y = Surface.section(k, "Buttons", "4 variants × 5 states", x, y);
        for (int s = 0; s < 5; s++) label(STATE_NAMES[s], x + 56 + s * 54, y);
        y += 12;
        Button.Variant[] vs = Button.Variant.values();
        for (Button.Variant v : vs) {
            label(v.name().charAt(0) + v.name().substring(1).toLowerCase(), x, y + 8);
            for (int s = 0; s < 5; s++) {
                k.force(STATES[s] & ~Kit.DISABLED);
                Button.draw(k, "b" + v + s, x + 56 + s * 54, y, 50, Button.Size.MD, v, v == Button.Variant.DANGER ? "Delete" : "Save",
                        null, STATES[s] != Kit.DISABLED, () -> {});
            }
            y += 30;
        }
        drawn.add("button");
        int bx = x;
        for (Button.Size sz : Button.Size.values()) {
            bx += Button.draw(k, "bs" + sz, bx, y + (36 - sz.h) / 2, 0, sz, sz == Button.Size.XL ? Button.Variant.PRIMARY : Button.Variant.SECONDARY,
                    sz.name(), sz == Button.Size.LG ? "plus" : null, sz == Button.Size.XL, true, () -> {}) + 6;
        }
        y += 42;
        label("Icon buttons", x, y + 8);
        String[] icons = {"close", "settings", "chevron_l", "search", "reset"};
        for (int s = 0; s < 5; s++) {
            k.force(STATES[s] & ~Kit.DISABLED);
            Button.icon(k, "bi" + s, x + 56 + s * 54, y, 24, icons[s], null, STATES[s] != Kit.DISABLED, () -> {});
        }
        drawn.add("iconButton");
        y += 36;

        y = Surface.section(k, "Toggles", null, x, y);
        int[] tst = {0, Kit.HOVER, Kit.FOCUSED, Kit.DISABLED};
        String[] tn = {"Idle", "Hover", "Focus", "Disabled"};
        for (int s = 0; s < 4; s++) label(tn[s], x + 62 + s * 56, y);
        y += 12;
        for (int on = 0; on < 2; on++) {
            label(on == 1 ? "On" : "Off", x, y + 4);
            for (int s = 0; s < 4; s++) {
                k.force(tst[s] & ~Kit.DISABLED);
                Toggle.draw(k, "t" + on + s, x + 62 + s * 56, y, true, on == 1, tst[s] != Kit.DISABLED, () -> {});
            }
            y += 24;
        }
        label("Medium", x, y + 2);
        Toggle.draw(k, "tm0", x + 62, y, false, false, true, () -> {});
        Toggle.draw(k, "tm1", x + 118, y, false, true, true, () -> {});
        drawn.add("toggle");
        y += 26;

        y = Surface.section(k, "Sliders", null, x, y);
        String[] sn = {"Idle", "Hover", "Dragging", "Focus", "Disabled"};
        for (int s = 0; s < 5; s++) {
            label(sn[s], x, y + 4);
            k.force(STATES[s] & ~Kit.DISABLED);
            double v = 0.2 + s * 0.15;
            Slider.draw(k, "sl" + s, x + 62, y, 230, v * 100, 0, 100, s == 4 ? 10 : 0, String.valueOf(Math.round(v * 100)), STATES[s] != Kit.DISABLED, d -> {});
            y += 22;
        }
        label("Ticks", x, y + 4);
        Slider.draw(k, "slt", x + 62, y, 230, 4, 0, 8, 1, "4", true, d -> {});
        drawn.add("slider");
        y += 26;

        y = Surface.section(k, "Progress", null, x, y);
        Progress.linear(k, x, y, 140, 0.35f, "Downloading");
        Progress.linear(k, x + 150, y, 140, Float.NaN, "Indeterminate");
        y += 24;
        Progress.circle(k, x + 12, y + 12, 11, 0.25f);
        Progress.circle(k, x + 42, y + 12, 11, 0.7f);
        Progress.circle(k, x + 72, y + 12, 11, Float.NaN);
        drawn.add("progress");

        // ---------------------------------------------------------------- column 2: selects, chips, fields, tooltip
        x = 360;
        y = 60;
        y = Surface.section(k, "Dropdown", null, x, y);
        List<String> modes = List.of("Toggle", "Hold", "Double tap");
        int[] dst = {0, Kit.HOVER, Kit.FOCUSED, Kit.DISABLED};
        for (int s = 0; s < 4; s++) {
            k.force(dst[s] & ~Kit.DISABLED);
            Dropdown.draw(k, "dd" + s, x + (s % 2) * 140, y + (s / 2) * 30, 130, modes, tn[s], dst[s] != Kit.DISABLED, o -> {});
        }
        y += 60;
        label("Open (live)", x, y + 8);
        if (!"ddl".equals(k.popup()) && k.mem("ddlOnce", null) == null) { k.popup("ddl"); k.remember("ddlOnce", true); k.remember("ddl#openedAt", 0L); }
        Dropdown.draw(k, "ddl", x + 70, y, 130, modes, liveDrop, true, o -> liveDrop = o);
        drawn.add("dropdown");
        y += 100;

        y = Surface.section(k, "Segmented and tabs", null, x, y);
        Segmented.draw(k, "seg", x, y, List.of("All", "Enabled", "Favourites"), liveSeg, true, o -> liveSeg = o);
        k.force(Kit.FOCUSED);
        Segmented.draw(k, "segf", x + 170, y, List.of("List", "Cards"), "Cards", true, o -> {});
        drawn.add("segmented");
        y += 30;
        Tabs.draw(k, "tabs", x, y, 280, List.of("Friends", "Requests", "Party", "Chat"), liveTab, o -> liveTab = o);
        y += 32;
        Tabs.draw(k, "tabso", x, y, 200, List.of("Hats", "Capes", "Wings", "Pets", "Item Skins", "Auras"), "Pets", o -> {});
        drawn.add("tabs");
        y += 36;

        y = Surface.section(k, "Key chips", null, x, y);
        int cx = x;
        cx += KeyChip.draw(k, "kc0", cx, y, KeyChip.Mode.BOUND, KeyChip.label("Ctrl+Shift+F"), null, () -> {}) + 6;
        cx += KeyChip.draw(k, "kc1", cx, y, KeyChip.Mode.NONE, "", null, () -> {}) + 6;
        cx += KeyChip.draw(k, "kc2", cx, y, KeyChip.Mode.LISTENING, "", null, () -> {}) + 6;
        k.force(Kit.HOVER);
        cx += KeyChip.draw(k, "kc3", cx, y, KeyChip.Mode.CONFLICT, "R", "Also bound to: Zoom, Replay marker", () -> {}) + 6;
        k.force(Kit.FOCUSED);
        KeyChip.draw(k, "kc4", cx, y, KeyChip.Mode.BOUND, "G", null, () -> {});
        drawn.add("keyChip");
        drawn.add("tooltip");
        y += 30;

        y = Surface.section(k, "Search", null, x, y);
        SearchField.draw(k, "sr0", x, y, 280, "Search modules", "Ctrl F", true);
        y += 30;
        if (SearchField.text(k, "sr1").isEmpty()) SearchField.set(k, "sr1", "zoom");
        k.force(Kit.HOVER);
        SearchField.draw(k, "sr1", x, y, 280, "Search modules", "Ctrl F", true);
        y += 30;
        k.force(Kit.FOCUSED);
        SearchField.draw(k, "sr2", x, y, 280, "Server address", null, false);
        drawn.add("search");
        y += 34;

        y = Surface.section(k, "Badges", null, x, y);
        int bxx = x;
        for (Badge.Tone t : Badge.Tone.values()) bxx += Badge.draw(k, bxx, y, t == Badge.Tone.NEUTRAL ? "15" : t.name().charAt(0) + t.name().substring(1).toLowerCase(), t) + 6;
        drawn.add("badge");
        y += 24;

        y = Surface.section(k, "Scrollbar", null, x, y);
        Paint.sunken(k.ctx, x, y, x + 130, y + 64, Tokens.R_SM);
        for (int i = 0; i < 6; i++) k.text("Row " + (i + 1), x + 8, y + 4 + i * 11, Tokens.N800, Role.SMALL);
        Scrollbar.edgeFades(k, x + 1, y + 1, x + 129, y + 63, Tokens.N150, false, true);
        Scrollbar.draw(k, "scr", x + 130, y + 2, y + 62, 40, 120, f -> {});
        Paint.sunken(k.ctx, x + 150, y, x + 280, y + 64, Tokens.R_SM);
        k.force(Kit.HOVER);
        Scrollbar.draw(k, "scr2", x + 280, y + 2, y + 62, 0, 60, f -> {});
        drawn.add("scrollbar");

        // ---------------------------------------------------------------- column 3: navigation, rows, cards
        x = 668;
        y = 60;
        y = Surface.section(k, "Sidebar", null, x, y);
        Paint.rect(k.ctx, x - 6, y - 4, x + 190, y + 6 * Sidebar.ITEM_H + 24, Tokens.R_MD, Tokens.N50);
        Sidebar.header(k, "Gameplay", x + 6, y);
        int sy = y + 14;
        Sidebar.highlight(k, "nav", x, 184);
        String[][] nav = {{"mods", "all", "All Mods", null}, {"controls", "controls", "Controls", "15"}, {"server", "server", "Server-facing", "5"},
                {"friends", "friends", "Friends", null}, {"hud", "hud", "HUD", "45"}, {"keys", "keyboard", "Keybinds", null}};
        for (int i = 0; i < nav.length; i++) {
            String[] n = nav[i];
            if (i == 2) k.force(Kit.HOVER);
            if (i == 5) k.force(Kit.FOCUSED);
            Sidebar.item(k, "nav", "nav" + n[0], x, sy, 184, n[1], n[2], n[3], n[0].equals(liveNav), n[0].equals("friends"), () -> liveNav = n[0]);
            sy += Sidebar.ITEM_H;
        }
        drawn.add("sidebar");
        y = sy + 16;

        y = Surface.section(k, "List rows", ListRow.height() == Tokens.ROW_DENSE ? "dense" : "comfortable", x, y);
        ListRow.draw(k, "lr0", x, y, 290, "visuals", "NoFog", "Clears distance fog. Water, lava and Blindness fog stay.", false, () -> {}, () -> {}, () -> {});
        y += ListRow.height() + 2;
        k.force(Kit.HOVER);
        ListRow.draw(k, "lr1", x, y, 290, "camera", "Zoom", "Hold a key to zoom in.", true, () -> {}, () -> {}, () -> {});
        y += ListRow.height() + 2;
        ListRow.draw(k, "lr2", x, y, 290, "hud", "Armor Status", "Shows your armour durability.", liveToggle, () -> liveToggle = !liveToggle, () -> {}, () -> {});
        y += ListRow.height() + 2;
        k.force(Kit.FOCUSED);
        ListRow.draw(k, "lr3", x, y, 290, "map", "World Map", null, null, null, null, () -> {});
        drawn.add("listRow");
        y += ListRow.height() + 12;

        y = Surface.section(k, "Cards", null, x, y);
        String[] cn = {"Idle", "Hover", "Selected", "Focus"};
        int[] cst = {0, Kit.HOVER, 0, Kit.FOCUSED};
        for (int i = 0; i < 4; i++) {
            int x1 = x + (i % 2) * 148, y1 = y + (i / 2) * 64;
            k.force(cst[i]);
            Surface.card(k, "card" + i, x1, y1, x1 + 140, y1 + 56, i == 2, true, () -> {});
            Icons(k, i == 2 ? "star" : "wardrobe", x1 + 12, y1 + 12, i == 2);
            k.text(cn[i], x1 + 40, y1 + 14, Tokens.N950, Role.STRONG);
            k.text("Card body text", x1 + 40, y1 + 28, Tokens.N700, Role.SMALL);
            Surface.endCard(k);
        }
        drawn.add("card");
        y += 136;

        y = Surface.section(k, "Type", "display, headline, tabular digits", x, y);
        UiFont.draw(k.ctx, k.tr, "Obsidian", x, y - 4, Tokens.N950, Role.DISPLAY, k.smooth);
        UiFont.draw(k.ctx, k.tr, "Headline", x + 150, y + 4, Tokens.N900, Role.HEADLINE, k.smooth);
        y += 30;
        UiFont.drawTabular(k.ctx, k.tr, "FPS 1440  \u00B7  X -73.5  Y 64  Z 1108", x, y, Tokens.N800, Role.BODY, k.smooth);
        drawn.add("type");
        y += 18;
        label("Colour swatch", x, y + 4);
        ColorPicker.swatch(k, "cp", x + 90, y, 40, 18, liveColor, c -> liveColor = c);
        k.text(String.format(java.util.Locale.ROOT, "#%06X", liveColor & 0xFFFFFF), x + 138, y + 5, Tokens.N800, Role.LABEL);

        // ---------------------------------------------------------------- column 4: colour, toasts, empty, dialog
        x = 976;
        y = 60;
        y = Surface.section(k, "Colour picker", null, x, y);
        ColorPicker.popover(k, "cpi", x, y, liveColor, c -> liveColor = c);
        drawn.add("colorPicker");
        y += ColorPicker.height() + 10;

        y = Surface.section(k, "Toasts", null, x, y);
        Notifications.preview(k.ctx, x, y, 276, Notifications.Level.SUCCESS, "Zoom enabled", "Hold C to zoom", 1);
        y += 48;
        Notifications.preview(k.ctx, x, y, 276, Notifications.Level.WARN, "Server rules", "This server turns Freelook off", 3);
        y += 48;
        Notifications.preview(k.ctx, x, y, 276, Notifications.Level.INFO, "World invite", "Dex invited you to \"Island\"", 1);
        drawn.add("toast");
        y += 50;

        y = Surface.section(k, "Empty state", null, x, y);
        EmptyState.draw(k, "empty", x + 138, y, "friends", "No friends yet", "Add someone by name to see when they're online.", "Add friend", () -> {});
        drawn.add("emptyState");

    }

    private static void Icons(Kit k, String icon, int x, int y, boolean lit) {
        Paint.rect(k.ctx, x, y, x + 24, y + 24, Tokens.R_XS, Tokens.N250);
        net.evadeclient.util.Icons.draw(k.ctx, icon, x + 5, y + 5, 14, lit ? Tokens.A400 : Tokens.N800);
    }

    // ---------------------------------------------------------------- input (screen px → design px)

    private double dx(double x) { return (x - ox) / scale; }

    private double dy(double y) { return (y - oy) / scale; }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        return kit.mouseClicked(dx(click.x()), dy(click.y()), click.button()) || super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseReleased(Click click) {
        return kit.mouseReleased(dx(click.x()), dy(click.y()), click.button()) || super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        return kit.mouseDragged(dx(click.x()), dy(click.y()), click.button()) || super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        return kit.mouseScrolled(dx(mouseX), dy(mouseY), v) || super.mouseScrolled(mouseX, mouseY, h, v);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_D && kit.focused() == null) { dialog(!dialog); return true; }
        return kit.keyPressed(input.key(), input.modifiers()) || super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        return kit.charTyped(input.codepoint()) || super.charTyped(input);
    }

    /** The kit (tests). */
    public Kit kit() { return kit; }

    /** Whether this frame drew through the shader (tests). */
    public static boolean gpu() { return Paint.gpu(); }

}
