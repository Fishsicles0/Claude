package net.evadeclient.module.visuals;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;

/** Hides screen clutter: the fire overlay, pumpkin blur, totem pop animation, nausea and portal swirl, and the vignette. */
public class NoRender extends Module {
    public final BooleanSetting fire = add(new BooleanSetting("Fire Overlay", true));
    public final BooleanSetting pumpkin = add(new BooleanSetting("Pumpkin Blur", true));
    public final BooleanSetting totem = add(new BooleanSetting("Totem Animation", false));
    public final BooleanSetting nausea = add(new BooleanSetting("Nausea & Portal", true));
    public final BooleanSetting vignette = add(new BooleanSetting("Vignette", false));

    public NoRender() {
        super("NoRender", "Hides fire, pumpkin blur, totem animation and other overlays.", Category.VISUALS);
    }

    public static boolean hide(java.util.function.Function<NoRender, BooleanSetting> which) {
        if (EvadeClient.modules == null) return false;
        NoRender nr = EvadeClient.modules.get(NoRender.class);
        return nr != null && nr.isEnabled() && which.apply(nr).get();
    }
}
