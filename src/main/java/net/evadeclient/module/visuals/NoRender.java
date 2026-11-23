package net.evadeclient.module.visuals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.util.Notifications;

/**
 * Hides screen clutter: the fire overlay, pumpkin blur, the totem pop animation, and nausea and portal swirl.
 * The vignette is left to vanilla's own switch (Video Settings, Vignette), which does the same thing.
 */
public class NoRender extends Module {
    public final BooleanSetting fire = add(new BooleanSetting("Fire Overlay", true));
    public final BooleanSetting pumpkin = add(new BooleanSetting("Pumpkin Blur", true));
    public final BooleanSetting totem = add(new BooleanSetting("Totem Animation", false));
    public final BooleanSetting nausea = add(new BooleanSetting("Nausea & Portal", true));

    /** A loaded config still had NoRender's old Vignette switch on: moved to vanilla's option on the next tick. */
    private boolean vignetteMoved;

    public NoRender() {
        super("NoRender", "Hides fire, pumpkin blur, totem animation and other overlays.", Category.VISUALS);
    }

    public static boolean hide(java.util.function.Function<NoRender, BooleanSetting> which) {
        if (EvadeClient.modules == null) return false;
        NoRender nr = EvadeClient.modules.get(NoRender.class);
        return nr != null && nr.isEnabled() && which.apply(nr).get();
    }

    /** NoRender used to have its own Vignette switch (off by default); remember if this config had it on. */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        JsonElement v = settings.get("Vignette");
        vignetteMoved = v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && v.getAsBoolean();
    }

    /**
     * Runs only while NoRender is on, so the old switch still mattered: turn vanilla's Vignette option off instead
     * (it hides the same overlay) and say where it went. Once; the next save no longer has the old key.
     */
    @Override
    public void onTick() {
        if (!vignetteMoved) return;
        vignetteMoved = false;
        var vignette = mc.options.getVignette();
        if (!vignette.getValue()) return;
        vignette.setValue(false);
        mc.options.write();
        Notifications.push("NoRender", "Vignette is now in Video Settings", true);
    }
}
