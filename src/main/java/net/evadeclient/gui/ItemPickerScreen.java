package net.evadeclient.gui;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.setting.ItemListSetting;
import net.evadeclient.gui.kit.Button;
import net.evadeclient.gui.kit.EmptyState;
import net.evadeclient.gui.kit.Paint;
import net.evadeclient.gui.kit.Scrollbar;
import net.evadeclient.gui.kit.SearchField;
import net.evadeclient.util.Theme;
import net.evadeclient.util.Tokens;
import net.evadeclient.util.UiFont;
import net.evadeclient.util.UiFont.Role;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
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
public final class ItemPickerScreen extends EvadeDialog {
    private static final int CELL = 22;
    private static final String SEARCH = "search";

    /** Every item once, with what search matches against (its name and id, lower case). */
    private record Entry(Item item, ItemStack stack, String search) {}

    private static List<Entry> catalog;

    private final ItemListSetting setting;
    private final Set<Item> chosen;
    private List<Entry> shown = List.of();
    private String shownQuery;
    private int scroll;
    private int gx, gy, cols, rows;

    public ItemPickerScreen(Screen parent, ItemListSetting setting) {
        super("Pick items", parent);
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

    @Override protected int prefWidth() { return 480; }
    @Override protected int prefHeight() { return 420; }
    @Override protected String titleIcon() { return "all"; }
    @Override protected String heading() { return setting.getName(); }

    @Override
    protected void init() {
        frame();
        if (!setting.isSingle()) action("Clear", Button.Variant.GHOST, chosen::clear).left();
        action("Cancel", Button.Variant.GHOST, this::close);
        if (!setting.isSingle()) action("Done", Button.Variant.PRIMARY, this::save);
        kit.focus(SEARCH, false);
    }

    /** The items matching the search box, filtered once per change. */
    private List<Entry> shown() {
        String q = SearchField.text(kit, SEARCH).strip().toLowerCase(Locale.ROOT);
        if (!q.equals(shownQuery)) {
            shownQuery = q;
            scroll = 0;
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

    private int maxScroll() { return Math.max(0, (shown().size() + cols - 1) / Math.max(1, cols) - rows); }

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

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (super.mouseScrolled(mx, my, h, v)) return true;
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
    protected void renderBody(DrawContext ctx, int mx, int my) {
        String sub = setting.isSingle() ? "Click an item to choose it" : chosen.size() + " chosen · click to add or remove";
        kit.text(sub, px + pw - PAD - kit.width(sub, Role.SMALL), py + PAD + 6, Tokens.N700, Role.SMALL);
        int y = by;
        SearchField.draw(kit, SEARCH, bx, y, bw, "Search items by name or id", "Ctrl F", true);
        y += SearchField.H + Tokens.S4;

        // The chosen tray: a sunken strip of chips; click one to take it out.
        int trayH = CELL + 8;
        Paint.surface(ctx, bx, y, bx + bw, y + trayH, Tokens.R_SM, Tokens.SUNKEN.withFill(Tokens.N150), 0, Float.NaN);
        if (chosen.isEmpty()) kit.textCentredY("Nothing chosen yet", bx + 10, y + trayH / 2f, Tokens.N600, Role.SMALL);
        int max = (bw - 8) / CELL, i = 0;
        for (Item item : new ArrayList<>(chosen)) {
            if (i >= max) break;
            int x = bx + 4 + i * CELL, cy0 = y + 4;
            String id = "ch:" + Registries.ITEM.getId(item);
            boolean hot = kit.hovered(id);
            Paint.rect(ctx, x + 1, cy0, x + CELL - 1, cy0 + CELL, Tokens.R_XS, hot ? Theme.alpha(Tokens.DANGER, 0.22f) : Tokens.N250);
            ctx.drawItem(new ItemStack(item), x + 3, cy0 + 3);
            kit.hit(id, x, cy0, x + CELL, cy0 + CELL, () -> chosen.remove(item));
            kit.tooltip(id, new ItemStack(item).getName().getString(), "Click to remove", x, cy0, x + CELL, cy0 + CELL);
            i++;
        }
        if (chosen.size() > max) kit.textCentredY("+" + (chosen.size() - max), bx + bw - 24, y + trayH / 2f, Tokens.N700, Role.SMALL);
        y += trayH + Tokens.S4;

        // The grid.
        int gridH = py + ph - PAD - Button.Size.MD.h - Tokens.S5 - y;
        cols = Math.max(1, (bw - 10) / CELL);
        rows = Math.max(1, gridH / CELL);
        gx = bx + (bw - 10 - cols * CELL) / 2;
        gy = y;
        List<Entry> list = shown();
        scroll = Math.min(scroll, maxScroll());
        if (list.isEmpty()) {
            EmptyState.draw(kit, "empty", bx + bw / 2, gy + 10, "search", "No item matches", "Try a name like \"pearl\" or an id like minecraft:apple.", null, null);
        }
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int index = (scroll + r) * cols + c;
                if (index >= list.size()) break;
                Entry e = list.get(index);
                int x = gx + c * CELL, yy = gy + r * CELL;
                String id = "g:" + index;
                boolean on = chosen.contains(e.item()), hot = kit.hovered(id);
                if (on) {
                    Paint.rect(ctx, x + 1, yy + 1, x + CELL - 1, yy + CELL - 1, Tokens.R_XS, Tokens.ACCENT_SURFACE);
                    Paint.ring(ctx, x + 1, yy + 1, x + CELL - 1, yy + CELL - 1, Tokens.R_XS, Theme.alpha(Tokens.A500, 0.8f), 1);
                } else if (hot) Paint.rect(ctx, x + 1, yy + 1, x + CELL - 1, yy + CELL - 1, Tokens.R_XS, Tokens.N300);
                ctx.drawItem(e.stack(), x + 3, yy + 3);
                kit.hit(id, x, yy, x + CELL, yy + CELL, () -> toggle(e.item()));
                kit.tooltip(id, e.stack().getName().getString(), Registries.ITEM.getId(e.item()).toString(), x, yy, x + CELL, yy + CELL);
            }
        }
        int ms = maxScroll();
        if (ms > 0) Scrollbar.draw(kit, "bar", bx + bw + 4, gy, gy + rows * CELL, scroll * CELL, ms * CELL,
                v -> scroll = Math.round(v / CELL));
    }

    /** Test hook: how many items the search box currently shows. */
    public int shownForTest() { return shown().size(); }

    /** Test hook: types into the search box. */
    public void searchForTest(String text) { SearchField.set(kit, SEARCH, text); }
}
