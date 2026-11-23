package net.evadeclient.module.visuals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Notifications;

/**
 * A screen tweak texture packs usually do: lower the fire on your screen, and fade it.
 * (Pumpkin blur is hidden by NoRender.)
 */
public class PackTweaks extends Module {
    public final BooleanSetting lowFire = add(new BooleanSetting("Low Fire", true));
    public final NumberSetting fireHeight = add(new NumberSetting("Fire Lowered By", 0.3, 0.05, 0.6, 0.05));
    public final NumberSetting fireOpacity = add(new NumberSetting("Fire Opacity", 90, 10, 90, 5)
            .describe("How solid the fire on your screen is, in percent. Vanilla draws it at 90."));

    /** A loaded config had the old Hide Pumpkin Blur switch on: say where it went (once, in game). */
    private boolean pumpkinMoved;

    public PackTweaks() {
        super("Pack Tweaks", "Lowers and fades the fire on your screen.", Category.VISUALS);
    }

    public static PackTweaks active() {
        if (EvadeClient.modules == null) return null;
        PackTweaks p = EvadeClient.modules.get(PackTweaks.class);
        return p != null && p.isEnabled() ? p : null;
    }

    /** Pack Tweaks used to hide pumpkin blur too, the same as NoRender's Pumpkin Blur. */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        JsonElement v = settings.get("Hide Pumpkin Blur");
        pumpkinMoved = v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && v.getAsBoolean();
    }

    /** Runs only while Pack Tweaks is on: if NoRender isn't hiding the pumpkin blur, point there. */
    @Override
    public void onTick() {
        if (!pumpkinMoved) return;
        pumpkinMoved = false;
        if (!NoRender.hide(n -> n.pumpkin)) Notifications.push("Pack Tweaks", "Pumpkin Blur moved to NoRender", true);
    }
}
