package net.evadeclient.module.visuals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Notifications;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.toast.AdvancementToast;
import net.minecraft.client.toast.RecipeToast;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.TutorialToast;
import net.minecraft.entity.effect.StatusEffects;

/**
 * Hides screen clutter: the fire overlay (always, or only while fire can't hurt you), pumpkin blur, the nausea and
 * portal overlays and the screen wobble they bring, the totem pop animation (or just makes it smaller), and the
 * advancement, recipe and tutorial pop-ups. System toasts (world backups, realms, narrator) always stay.
 * The vignette is left to vanilla's own switch (Video Settings, Vignette), which does the same thing.
 *
 * <p>Hooks: InGameOverlayRendererMixin (fire, totem size), InGameHudMixin (pumpkin, nausea and portal overlays),
 * NoRenderGameRendererMixin (wobble), GameRendererMixin (totem animation), ToastManagerMixin (toasts).
 */
public class NoRender extends Module {
    public final BooleanSetting fire = add(new BooleanSetting("Fire Overlay", true)
            .describe("Hide the flames on your screen while you're burning."));
    public final BooleanSetting fireOnlyImmune = add(new BooleanSetting("Fire: Only When Immune", false)
            .describe("Only hide the flames while fire can't hurt you: Fire Resistance, Creative, or a fire-proof body."));
    public final BooleanSetting pumpkin = add(new BooleanSetting("Pumpkin Blur", true)
            .describe("Hide the carved pumpkin view while you wear one."));
    public final BooleanSetting nausea = add(new BooleanSetting("Nausea & Portal", true)
            .describe("Hide the green nausea and purple portal overlays on your screen."));
    public final BooleanSetting wobble = add(new BooleanSetting("Screen Wobble", true)
            .describe("Stop the screen warping from Nausea and nether portals."));
    public final BooleanSetting totem = add(new BooleanSetting("Totem Animation", false)
            .describe("Hide the big totem that pops up when one saves you."));
    public final NumberSetting totemSize = add(new NumberSetting("Totem Size %", 100, 20, 100, 5)
            .describe("Size of the totem pop animation while it isn't hidden. 100 is vanilla."));
    public final BooleanSetting advancementToasts = add(new BooleanSetting("Advancement Toasts", false)
            .describe("Hide the advancement pop-ups in the top-right corner. The chat message stays."));
    public final BooleanSetting recipeToasts = add(new BooleanSetting("Recipe Toasts", true)
            .describe("Hide the \"New Recipes Unlocked!\" pop-ups."));
    public final BooleanSetting tutorialToasts = add(new BooleanSetting("Tutorial Toasts", true)
            .describe("Hide the tutorial hints for new players (move, look around, punch a tree)."));

    /** A loaded config still had NoRender's old Vignette switch on: moved to vanilla's option on the next tick. */
    private boolean vignetteMoved;

    public NoRender() {
        super("NoRender", "Hides fire, pumpkin blur, screen wobble, totem animation, toasts and other overlays.", Category.VISUALS);
    }

    public static boolean hide(java.util.function.Function<NoRender, BooleanSetting> which) {
        NoRender nr = active();
        return nr != null && which.apply(nr).get();
    }

    /** NoRender when it is on, else null. */
    public static NoRender active() {
        if (EvadeClient.modules == null) return null;
        NoRender nr = EvadeClient.modules.get(NoRender.class);
        return nr != null && nr.isEnabled() ? nr : null;
    }

    /**
     * Whether the first-person fire overlay is hidden: Fire Overlay on and, with Fire: Only When Immune, only while
     * fire can't hurt you (Fire Resistance, an invulnerable/Creative player or a fire-immune body).
     */
    public static boolean hideFire() {
        NoRender nr = active();
        if (nr == null || !nr.fire.get()) return false;
        if (!nr.fireOnlyImmune.get()) return true;
        ClientPlayerEntity p = mc.player;
        return p != null && (p.hasStatusEffect(StatusEffects.FIRE_RESISTANCE) || p.getAbilities().invulnerable || p.isFireImmune());
    }

    /** Totem Size % as a scale for the totem pop animation (1 when NoRender is off). */
    public static float totemScale() {
        NoRender nr = active();
        return nr == null ? 1f : nr.totemSize.getFloat() / 100f;
    }

    /**
     * Whether {@code toast} is filtered out before it is queued: advancement, recipe and tutorial toasts, each on its
     * own switch. Every other toast (system messages, world backups, Evade's own) always shows.
     */
    public static boolean hidesToast(Toast toast) {
        NoRender nr = active();
        if (nr == null || toast == null) return false;
        if (toast instanceof AdvancementToast) return nr.advancementToasts.get();
        if (toast instanceof RecipeToast) return nr.recipeToasts.get();
        if (toast instanceof TutorialToast) return nr.tutorialToasts.get();
        return false;
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
