package net.evadeclient.module.visuals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.util.Identifier;

/**
 * Screen tweaks texture packs usually make: lower and fade the fire on your screen, fade (or drop) the pumpkin and
 * powder-snow overlays, hold the shield lower and shrink a totem held in the off hand. All first person, all visual.
 * (NoRender's Pumpkin Blur still hides the pumpkin view outright, whatever Pumpkin Overlay % is.)
 *
 * <p>Hooks: InGameOverlayRendererMixin (fire), InGameHudMixin (pumpkin and freezing overlays), HeldItemRendererMixin
 * (shield, off-hand totem).
 */
public class PackTweaks extends Module {
    /** Vanilla's powder snow frost texture (InGameHud.POWDER_SNOW_OUTLINE). */
    private static final String FREEZING_TEXTURE = "textures/misc/powder_snow_outline.png";

    public final BooleanSetting lowFire = add(new BooleanSetting("Low Fire", true)
            .describe("Draw the flames on your screen lower, so they cover less of your view."));
    public final NumberSetting fireHeight = add(new NumberSetting("Fire Lowered By", 0.3, 0.05, 0.6, 0.05)
            .describe("How far the on-fire overlay is moved down, so it covers less of your view."));
    public final NumberSetting fireOpacity = add(new NumberSetting("Fire Opacity %", 70, 10, 100, 5)
            .describe("How solid the flames on your screen are. Vanilla draws them at 90."));
    public final NumberSetting pumpkinOverlay = add(new NumberSetting("Pumpkin Overlay %", 0, 0, 100, 5)
            .describe("How solid the carved pumpkin view is while you wear one. 0 hides it, 100 is vanilla."));
    public final NumberSetting freezingOverlay = add(new NumberSetting("Freezing Overlay %", 100, 0, 100, 5)
            .describe("How solid the frost around your screen is in powder snow. 0 hides it, 100 is vanilla."));
    public final BooleanSetting lowShield = add(new BooleanSetting("Low Shield", true)
            .describe("Hold your shield lower in first person, so it covers less of your view."));
    public final NumberSetting shieldLower = add(new NumberSetting("Shield Lowered By", 0.15, 0.05, 0.4, 0.05)
            .describe("How far Low Shield moves the shield down."));
    public final NumberSetting offhandTotem = add(new NumberSetting("Off-Hand Totem Size %", 60, 40, 100, 5)
            .describe("Size of a totem held in your off hand, in first person. 100 is vanilla."));

    public PackTweaks() {
        super("Pack Tweaks", "Lower fire and shield, faded screen overlays and a smaller off-hand totem.", Category.VISUALS);
    }

    public static PackTweaks active() {
        if (EvadeClient.modules == null) return null;
        PackTweaks p = EvadeClient.modules.get(PackTweaks.class);
        return p != null && p.isEnabled() ? p : null;
    }

    /**
     * How solid a full-screen overlay {@code texture} is drawn, in percent of vanilla: Pumpkin Overlay % for the
     * carved pumpkin view, Freezing Overlay % for the powder snow frost, 100 for anything else or while off.
     */
    public static int overlayPercent(Identifier texture) {
        PackTweaks pt = active();
        if (pt == null || texture == null) return 100;
        String path = texture.getPath();
        if (path.contains("pumpkin")) return pt.pumpkinOverlay.getInt();
        if (FREEZING_TEXTURE.equals(path)) return pt.freezingOverlay.getInt();
        return 100;
    }

    /**
     * Older configs: "Fire Opacity" (no %) is now "Fire Opacity %", and the "Hide Pumpkin Blur" switch is now
     * Pumpkin Overlay % (on: 0, hidden; off: 100, vanilla). Only when the new key isn't saved yet.
     */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        JsonElement oldOpacity = settings.get("Fire Opacity");
        if (oldOpacity != null && !settings.has(fireOpacity.getName())) {
            try {
                fireOpacity.set(oldOpacity.getAsDouble());
            } catch (RuntimeException ignored) {
                // unreadable: keep the default
            }
        }
        JsonElement oldPumpkin = settings.get("Hide Pumpkin Blur");
        if (oldPumpkin != null && !settings.has(pumpkinOverlay.getName())) {
            try {
                pumpkinOverlay.set(oldPumpkin.getAsBoolean() ? 0.0 : 100.0);
            } catch (RuntimeException ignored) {
                // unreadable: keep the default
            }
        }
    }
}
