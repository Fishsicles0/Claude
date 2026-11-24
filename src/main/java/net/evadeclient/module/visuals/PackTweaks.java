package net.evadeclient.module.visuals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.ColorSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Notifications;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.item.FilledMapItem;
import net.minecraft.util.Identifier;

/**
 * Screen tweaks texture packs usually make: lower and fade the fire on your screen, fade (or drop) the pumpkin and
 * powder-snow overlays, hold the shield lower and shrink a totem held in the off hand. The overlay bundle adds clear
 * glass (the streaks inside plain glass removed, the frame kept), a held map that stays still while you walk, arrows
 * stuck in your own model hidden, a fishing line colour and a fainter spyglass view. All visual.
 * (NoRender's Pumpkin Blur still hides the pumpkin view outright, whatever Pumpkin Overlay % is.)
 *
 * <p>Hooks: InGameOverlayRendererMixin (fire), InGameHudMixin (pumpkin and freezing overlays), HeldItemRendererMixin
 * (shield, off-hand totem), ClearGlassMixin, MapBobbingMixin, StuckArrowsMixin, FishingLineMixin, SpyglassOverlayMixin.
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
    // -- overlay bundle
    public final BooleanSetting clearGlass = add(new BooleanSetting("Clear Glass", false)
            .describe("Plain glass loses the white streaks inside its frame, so it looks perfectly clear. Reloads textures."));
    public final BooleanSetting noMapBobbing = add(new BooleanSetting("No Map Bobbing", false)
            .describe("A map in your hand stays still while you walk, so it's easy to read. View Bobbing is kept for everything else."));
    public final BooleanSetting hideStuckArrows = add(new BooleanSetting("Hide Arrows Stuck In You", false)
            .describe("Arrows and bee stingers stuck in your own player aren't drawn (in third person and the inventory)."));
    public final ColorSetting fishingLine = add(new ColorSetting("Fishing Line Color", 0xFF000000).opaque()
            .describe("Colour of fishing lines. Black is vanilla."));
    public final NumberSetting spyglassOverlay = add(new NumberSetting("Spyglass Overlay %", 100, 0, 100, 5)
            .describe("How solid the dark ring around the spyglass view is. 0 hides it, 100 is vanilla."));

    /** Whether the block textures were last built with Clear Glass (null: no glass texture loaded yet). */
    private static Boolean glassBuiltClear;
    private static boolean reloadQueued;

    public PackTweaks() {
        super("Pack Tweaks", "Lower fire and shield, faded overlays, clear glass, still maps and a fishing line colour.", Category.VISUALS);
        // Clear Glass is baked into the block textures: rebuild them when it (or the module) is switched.
        ClientTickEvents.END_CLIENT_TICK.register(client -> checkGlass());
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

    // ------------------------------------------------------------------ overlay bundle

    /** Whether glass textures should be built clear now. */
    private static boolean wantsClearGlass() {
        PackTweaks pt = active();
        return pt != null && pt.clearGlass.get();
    }

    /** Reloads resources once when Clear Glass no longer matches the block textures (never during a load). */
    private void checkGlass() {
        if (glassBuiltClear == null || reloadQueued || mc.getOverlay() != null) return;
        if (glassBuiltClear == wantsClearGlass()) return;
        reloadQueued = true;
        Notifications.push("Pack Tweaks", "Reloading textures for Clear Glass", true);
        mc.reloadResources().whenComplete((v, e) -> reloadQueued = false);
    }

    /**
     * From ClearGlassMixin for each texture loaded into an atlas: with Clear Glass on, plain glass (block/glass and
     * glass panes' faces, which use it too) keeps only its frame, one sprite pixel in from each edge (scaled for HD
     * packs); everything inside becomes fully clear.
     */
    public static void onSpriteLoaded(Identifier id, NativeImage image) {
        if (id == null || image == null || !"minecraft".equals(id.getNamespace()) || !"block/glass".equals(id.getPath())) return;
        boolean clear = wantsClearGlass();
        glassBuiltClear = clear;
        if (clear) clearInside(image);
    }

    /** Makes every pixel inside the frame of each 16-unit tile of {@code image} transparent (public for the tests). */
    public static void clearInside(NativeImage image) {
        int w = image.getWidth(), h = image.getHeight();
        int frame = Math.max(1, w / 16);
        // Animated glass stacks frames vertically; each square frame keeps its own border.
        int tile = Math.min(w, h);
        for (int y = 0; y < h; y++) {
            int ty = y % tile;
            for (int x = 0; x < w; x++) {
                if (x < frame || x >= w - frame || ty < frame || ty >= tile - frame) continue;
                image.setColorArgb(x, y, 0);
            }
        }
    }

    /** Whether the hand should skip view bobbing this frame: No Map Bobbing with a filled map in either hand. */
    public static boolean stillMap() {
        PackTweaks pt = active();
        if (pt == null || !pt.noMapBobbing.get() || mc.player == null) return false;
        return mc.player.getMainHandStack().getItem() instanceof FilledMapItem
                || mc.player.getOffHandStack().getItem() instanceof FilledMapItem;
    }

    /** Whether arrows and stingers stuck in {@code entity} are hidden: only ever your own player. */
    public static boolean hidesStuckIn(Object entity) {
        PackTweaks pt = active();
        return pt != null && pt.hideStuckArrows.get() && entity != null && entity == mc.player;
    }

    /** The fishing line colour ({@code original}, black, while off). */
    public static int fishingLineColor(int original) {
        PackTweaks pt = active();
        return pt == null ? original : pt.fishingLine.get() | 0xFF000000;
    }

    /** Spyglass Overlay % (100 while off). */
    public static int spyglassPercent() {
        PackTweaks pt = active();
        return pt == null ? 100 : pt.spyglassOverlay.getInt();
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
