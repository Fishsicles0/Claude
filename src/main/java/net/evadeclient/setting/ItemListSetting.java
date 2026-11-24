package net.evadeclient.setting;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A list of items (or a single item), saved as item ids separated by commas so it reads like the text settings it
 * replaces: configs, profile codes and {@code .set} keep working. In the menu the row shows the items' icons, and a
 * click opens the searchable item picker ({@link net.evadeclient.gui.ItemPickerScreen}) instead of a text box.
 * Ids without a namespace mean {@code minecraft:}; unknown ids are kept but ignored.
 */
public class ItemListSetting extends StringSetting {
    private final boolean single;
    private String parsedKey;
    private List<Item> parsed = List.of();
    private Set<Item> parsedSet = Set.of();

    /** A list of any number of items. */
    public ItemListSetting(String name, String value, String hint) {
        this(name, value, hint, false);
    }

    /** {@code single}: exactly one item (picking one replaces it). */
    public ItemListSetting(String name, String value, String hint, boolean single) {
        super(name, value, hint);
        this.single = single;
        maxLength(single ? 96 : 1024);
        onClick(() -> net.evadeclient.gui.ItemPickerScreen.open(MinecraftClient.getInstance().currentScreen, this));
    }

    public boolean isSingle() { return single; }

    /** The items, in order, without unknown ids, air or repeats (re-read only when the value changes). */
    public List<Item> items() {
        String v = get() == null ? "" : get();
        if (!v.equals(parsedKey)) {
            Set<Item> out = new LinkedHashSet<>();
            for (String part : v.split("[,|\\s]+")) {
                Item item = parse(part);
                if (item != null) out.add(item);
                if (single && !out.isEmpty()) break;
            }
            parsed = List.copyOf(out);
            parsedSet = Set.copyOf(out);
            parsedKey = v;
        }
        return parsed;
    }

    public boolean contains(Item item) {
        items();
        return item != null && parsedSet.contains(item);
    }

    /** Stores {@code items} (just the first for a single-item setting) as ids. */
    public void setItems(List<Item> items) {
        List<String> ids = new ArrayList<>();
        for (Item i : items) {
            if (i == null || i == Items.AIR) continue;
            ids.add(Registries.ITEM.getId(i).toString());
            if (single) break;
        }
        set(String.join(",", ids));
    }

    /** The item an id names ("diamond", "minecraft:diamond"), or null for an unknown id or air. */
    public static Item parse(String id) {
        if (id == null) return null;
        String s = id.strip();
        if (s.isEmpty()) return null;
        Identifier key = Identifier.tryParse(s.contains(":") ? s : "minecraft:" + s);
        if (key == null || !Registries.ITEM.containsId(key)) return null;
        Item item = Registries.ITEM.get(key);
        return item == Items.AIR ? null : item;
    }
}
