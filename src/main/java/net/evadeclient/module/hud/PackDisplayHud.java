package net.evadeclient.module.hud;

import net.evadeclient.module.TextHudModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shows the name of your active resource pack on the HUD. */
public class PackDisplayHud extends TextHudModule {
    public PackDisplayHud() { super("Pack Display", "Shows your active resource pack.", 4, 244); }

    @Override
    protected List<String> lines(boolean preview) {
        if (preview) return new ArrayList<>(List.of("Pack: Evade"));
        String name = "Default";
        try {
            for (String id : mc.getResourcePackManager().getEnabledIds()) {
                String low = id.toLowerCase(Locale.ROOT);
                if (low.equals("vanilla") || low.startsWith("fabric") || low.contains("programmer_art")) continue;
                name = id;
            }
        } catch (Exception ignored) {}
        // tidy "file/My Pack.zip" -> "My Pack"
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) name = name.substring(0, name.length() - 4);
        return new ArrayList<>(List.of("Pack: " + name));
    }
}
