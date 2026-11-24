package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.setting.ItemListSetting;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The item picker behind an {@link ItemListSetting}: a search box, the items chosen so far (click one to remove it)
 * and a grid of every item in the game (click to add or remove; for a single-item setting, click to choose). Done
 * saves, Cancel or Escape leaves the setting as it was.
 */
public final class ItemPickerScreen extends Screen {
    private static final int CELL = 18;

    /** Every item once, with what search matches against (its name and id, lower case). */
    private record Entry(Item item, ItemStack stack, String search) {}

    private static List<Entry> catalog;

    private final Screen parent;
    private final ItemListSetting setting;
    private final Set<Item> chosen;
    private TextFieldWidget search;
    private List<Entry> shown = List.of();
    private String shownQuery;
    private int scroll;
    private int px, py, pw, ph, gx, gy, cols, rows;

    public ItemPickerScreen(Screen parent, ItemListSetting setting) {
        super(Text.literal("Pick items"));
        this.parent = parent;
        this.setting = setting;
        this.chosen = new LinkedHashSet<>(setting.items());
    }

    /** Opens the picker for {@code setting} over {@code parent} (back to it when done). */
    public static void open(Screen parent, ItemListSetting setting) {
        MinecraftClient.getInstance().setScreen(new ItemPickerScreen(parent, setting));
    }

    private static List<Entry> catalog() {
        if (catalog == null) {
            List<Entry> out = new ArrayList<>();
            for (Item item : Registries.ITEM) {
                if (item == Items.AIR) continue;
                ItemStack stack = new ItemStack(item);
                String name = stack.getName().getString().toLowerCase(Locale.ROOT);
                out.add(new Entry(item, stack, name + " " + Registries.ITEM.getId(item)));
            }
            catalog = List.copyOf(out);
        }
        return catalog;
    }

    @Override
    protected void init() {
        pw = Math.min(380, width - 20);
        ph = Math.min(320, height - 20);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        String text = search == null ? "" : search.getText();
        search = new TextFieldWidget(textRenderer, px + 14, py + 30, pw - 28, 16, Text.literal("Search"));
        search.setMaxLength(48);
        search.setText(text);
        search.setPlaceholder(Text.literal("Search items by name or id").formatted(Formatting.DARK_GRAY));
        search.setChangedListener(s -> scroll = 0);
        addDrawableChild(search);
        setInitialFocus(search);

        cols = Math.max(1, (pw - 28) / CELL);
        gx = px + 14 + (pw - 28 - cols * CELL) / 2;
        gy = py + 76;
        rows = Math.max(1, (py + ph - 34 - gy) / CELL);

        int bw = (pw - 28 - 2 * 4) / 3, by = py + ph - 28;
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> save()).dimensions(px + 14, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Clear"), b -> chosen.clear()).dimensions(px + 14 + bw + 4, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), b -> close()).dimensions(px + 14 + (bw + 4) * 2, by, bw, 20).build());
    }

    /** The items matching the search box, filtered once per change. */
    private List<Entry> shown() {
        String q = search == null ? "" : search.getText().strip().toLowerCase(Locale.ROOT);
        if (!q.equals(shownQuery)) {
            shownQuery = q;
            if (q.isEmpty()) {
                shown = catalog();
            } else {
                List<Entry> out = new ArrayList<>();
                for (Entry e : catalog()) if (e.search().contains(q)) out.add(e);
                shown = out;
            }
        }
        return shown;
    }

    private int maxScroll() { return Math.max(0, (shown().size() + cols - 1) / cols - rows); }

    private void save() {
        setting.setItems(new ArrayList<>(chosen));
        if (EvadeClient.config != null) EvadeClient.config.save();
        close();
    }

    private void toggle(Item item) {
        if (setting.isSingle()) {
            chosen.clear();
            chosen.add(item);
            save();
            return;
        }
        if (!chosen.remove(item)) chosen.add(item);
    }

    /** The grid item under the mouse, or null. */
    private Entry gridAt(double mx, double my) {
        if (mx < gx || my < gy || mx >= gx + cols * CELL || my >= gy + rows * CELL) return null;
        int index = (scroll + (int) ((my - gy) / CELL)) * cols + (int) ((mx - gx) / CELL);
        List<Entry> list = shown();
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    /** The chosen item under the mouse in the "chosen" row, or null. */
    private Item chosenAt(double mx, double my) {
        int y = py + 52;
        if (my < y || my >= y + CELL || mx < px + 14) return null;
        int i = (int) ((mx - px - 14) / CELL);
        int max = (pw - 28) / CELL;
        if (i >= max) return null;
        List<Item> list = new ArrayList<>(chosen);
        return i < list.size() ? list.get(i) : null;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0) {
            Entry e = gridAt(click.x(), click.y());
            if (e != null) {
                toggle(e.item());
                return true;
            }
            Item c = chosenAt(click.x(), click.y());
            if (c != null) {
                chosen.remove(c);
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll - (int) Math.signum(v) * 2));
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if ((input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) && !setting.isSingle()) {
            save();
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ClientSettings s = EvadeClient.modules == null ? null : EvadeClient.modules.get(ClientSettings.class);
        if (s == null || s.blur.get()) super.renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fillGradient(0, 0, width, height, 0xB0000000, 0xD0000000);
        Theme.shadow(ctx, Theme.SH_WINDOW, px, py, px + pw, py + ph, 0x90000000);
        Theme.round(ctx, px, py, px + pw, py + ph, Theme.BG, Theme.R_WIN);
        Theme.stroke(ctx, px, py, px + pw, py + ph, Theme.BORDER, Theme.R_WIN);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        ClientSettings s = EvadeClient.modules == null ? null : EvadeClient.modules.get(ClientSettings.class);
        boolean smooth = s == null || s.smoothFont.get();
        UiFont.draw(ctx, textRenderer, setting.getName(), px + 14, py + 12, Theme.TEXT, Role.TITLE, smooth);
        String sub = setting.isSingle() ? "Click an item to choose it" : chosen.size() + " chosen · click to add or remove";
        UiFont.draw(ctx, textRenderer, sub, px + pw - 14 - UiFont.width(textRenderer, sub, Role.SMALL, smooth), py + 14,
                Theme.TEXT_DIM, Role.SMALL, smooth);

        // chosen row
        int cy = py + 52, max = (pw - 28) / CELL, i = 0;
        Item hoverChosen = chosenAt(mx, my);
        if (chosen.isEmpty()) {
            UiFont.draw(ctx, textRenderer, "Nothing chosen yet", px + 14, cy + 5, Theme.TEXT_FAINT, Role.SMALL, smooth);
        }
        for (Item item : chosen) {
            if (i >= max) break;
            int x = px + 14 + i * CELL;
            ctx.fill(x, cy, x + CELL - 1, cy + CELL - 1, item == hoverChosen ? 0x60FF4D5E : 0x40FFFFFF);
            ctx.drawItem(new ItemStack(item), x + 1, cy + 1);
            i++;
        }
        ctx.fill(px + 14, gy - 4, px + pw - 14, gy - 3, Theme.DIVIDER);

        // grid
        List<Entry> list = shown();
        scroll = Math.min(scroll, maxScroll());
        Entry hover = gridAt(mx, my);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int index = (scroll + r) * cols + c;
                if (index >= list.size()) break;
                Entry e = list.get(index);
                int x = gx + c * CELL, y = gy + r * CELL;
                boolean on = chosen.contains(e.item());
                if (on) ctx.fill(x, y, x + CELL - 1, y + CELL - 1, Theme.alpha(Theme.RED, 0.55f));
                else if (e == hover) ctx.fill(x, y, x + CELL - 1, y + CELL - 1, 0x40FFFFFF);
                ctx.drawItem(e.stack(), x + 1, y + 1);
            }
        }
        if (list.isEmpty()) {
            UiFont.draw(ctx, textRenderer, "No item matches", gx, gy + 4, Theme.TEXT_FAINT, Role.SMALL, smooth);
        }
        if (maxScroll() > 0) {
            int trackH = rows * CELL, x = px + pw - 10;
            int thumbH = Math.max(12, trackH * rows / (rows + maxScroll()));
            int thumbY = gy + (trackH - thumbH) * scroll / maxScroll();
            ctx.fill(x, gy, x + 2, gy + trackH, Theme.TRACK);
            ctx.fill(x, thumbY, x + 2, thumbY + thumbH, Theme.TEXT_FAINT);
        }
        if (hover != null) {
            ctx.drawTooltip(textRenderer, List.of(hover.stack().getName(),
                    Text.literal(Registries.ITEM.getId(hover.item()).toString()).formatted(Formatting.DARK_GRAY)), mx, my);
        } else if (hoverChosen != null) {
            ctx.drawTooltip(textRenderer, List.of(new ItemStack(hoverChosen).getName(),
                    Text.literal("Click to remove").formatted(Formatting.GRAY)), mx, my);
        }
    }

    @Override public boolean shouldPause() { return false; }

    @Override public void close() { if (client != null) client.setScreen(parent); }

    /** Test hook: how many items the search box currently shows. */
    public int shownForTest() { return shown().size(); }

    /** Test hook: types into the search box. */
    public void searchForTest(String text) { search.setText(text); }
}
