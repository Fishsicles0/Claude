package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.module.misc.Schematics;
import net.evadeclient.schematic.Materials;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;

import java.util.ArrayList;
import java.util.List;

/**
 * A small line for the Schematics mod: how many blocks of the loaded schematic are still to place, and optionally
 * how many you still have to gather and the top few items. Hidden while no schematic is loaded, and whenever
 * Schematics is off (including when a server has switched it off).
 */
public class SchematicMaterialsHud extends TextHudModule {
    private final BooleanSetting showMissing = add(new BooleanSetting("Show Still To Get", true)
            .describe("Adds how many blocks you still need beyond what's in your inventory."));
    private final NumberSetting topItems = add(new NumberSetting("Top Items", 0, 0, 5, 1)
            .describe("Also lists this many of the items with the most left to place."));

    public SchematicMaterialsHud() {
        super("Schematic Materials", "How many blocks of your schematic are left to place.", 4, 262);
    }

    @Override
    protected List<String> lines(boolean preview) {
        Schematics s = Schematics.get();
        if (preview && (s == null || s.placement() == null)) return List.of("Materials left: 1,284", "Still to get: 320");
        if (s == null || !s.isEnabled() || s.blockedByServer() || s.placement() == null) return List.of();
        List<Materials.Entry> list = s.materials();
        int left = Materials.totalLeft(list), missing = 0;
        for (Materials.Entry e : list) missing += e.missing();
        List<String> out = new ArrayList<>();
        out.add(String.format("Materials left: %,d", left));
        if (showMissing.get()) out.add(String.format("Still to get: %,d", missing));
        int n = topItems.getInt();
        for (int i = 0; i < list.size() && i < n; i++) {
            Materials.Entry e = list.get(i);
            if (e.left() == 0) break;
            out.add(Materials.name(e.item()) + ": " + e.left());
        }
        return out;
    }
}
