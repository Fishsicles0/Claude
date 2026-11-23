package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;
import net.evadeclient.setting.BooleanSetting;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourcePackSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows the resource pack you're using (by its display name), or every pack you've turned on. Only your own packs
 * count: the built-in game, mod and server packs are left out.
 */
public class PackDisplayHud extends TextHudModule {
    private final BooleanSetting showAll = add(new BooleanSetting("Show All", false)
            .describe("List every pack you have on, top pack first, instead of only the top one."));

    /** Names of the enabled user packs, top (highest priority) first, rebuilt only when the enabled list changes. */
    private List<String> names = List.of();
    private List<String> lastIds;

    public PackDisplayHud() { super("Pack Display", "Shows your active resource pack.", 4, 216); }

    private List<String> packs() {
        try {
            var manager = mc.getResourcePackManager();
            List<String> ids = List.copyOf(manager.getEnabledIds());
            if (ids.equals(lastIds)) return names;
            lastIds = ids;
            List<String> out = new ArrayList<>();
            for (ResourcePackProfile p : manager.getEnabledProfiles()) {
                if (p.getSource() != ResourcePackSource.NONE) continue;
                String name = p.getDisplayName().getString().strip();
                out.add(0, name.isEmpty() ? p.getId() : name); // enabled profiles run bottom to top
            }
            names = List.copyOf(out);
        } catch (RuntimeException e) {
            names = List.of();
        }
        return names;
    }

    @Override
    protected List<String> lines(boolean preview) {
        List<String> packs = packs();
        if (packs.isEmpty()) return new ArrayList<>(List.of("Pack: Default"));
        if (!showAll.get()) return new ArrayList<>(List.of("Pack: " + packs.get(0)));
        List<String> out = new ArrayList<>(packs.size());
        for (int i = 0; i < packs.size(); i++) out.add(i == 0 ? "Pack: " + packs.get(i) : packs.get(i));
        return out;
    }
}
