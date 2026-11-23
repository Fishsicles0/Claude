package net.evadeclient.module.mods;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.MathHelper;

/**
 * Locks your field of view so speed effects, sprinting and flying don't change it. Everything else vanilla does to
 * the FOV stays: the spyglass, the underwater and death views, and (optionally) the bow's draw zoom.
 */
public class FovChanger extends Module {
    public final BooleanSetting useGameFov = add(new BooleanSetting("Use Game FOV", true)
            .describe("Keep the FOV from Minecraft's options; turn off to use the FOV below."));
    public final NumberSetting fov = add(new NumberSetting("FOV", 70, 30, 120, 1)
            .describe("The field of view to use when Use Game FOV is off."));
    public final BooleanSetting keepBowZoom = add(new BooleanSetting("Keep Bow Zoom", true)
            .describe("Still zoom in slightly while drawing a bow."));

    public FovChanger() {
        super("FOV Changer", "Locks your FOV so speed and sprinting don't change it.", Category.MODS);
    }

    private static FovChanger active() {
        if (EvadeClient.modules == null) return null;
        FovChanger m = EvadeClient.modules.get(FovChanger.class);
        return m != null && m.isEnabled() ? m : null;
    }

    /** The base FOV in degrees to use instead of the options value, or null to keep the game's. */
    public static Integer baseFov() {
        FovChanger m = active();
        return m == null || m.useGameFov.get() ? null : m.fov.getInt();
    }

    /**
     * The FOV effect multiplier the game eases towards each tick ({@code vanilla} is what the player asked for).
     * While on, speed, sprinting and flying no longer change it; the spyglass always does, the bow when wanted.
     */
    public static float effectMultiplier(float vanilla) {
        FovChanger m = active();
        if (m == null || !(mc.getCameraEntity() instanceof AbstractClientPlayerEntity p) || !p.isUsingItem()) {
            return m == null ? vanilla : 1f;
        }
        if (p.getActiveItem().isOf(Items.BOW)) {
            if (!m.keepBowZoom.get()) return 1f;
            float draw = Math.min(p.getItemUseTime() / 20f, 1f);
            float scale = mc.options.getFovEffectScale().getValue().floatValue();
            return MathHelper.lerp(scale, 1f, 1f - MathHelper.square(draw) * 0.15f);
        }
        // Spyglass (first person): vanilla answers exactly 0.1 for it.
        if (mc.options.getPerspective().isFirstPerson() && p.isUsingSpyglass()) return vanilla;
        return 1f;
    }

    /**
     * Before "Use Game FOV" existed the module always used its own FOV slider. Saved configs and codes from then
     * keep that, unless the slider was still at its default 70 (a value nobody chose on purpose).
     */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        if (settings.has(useGameFov.getName()) || !settings.has(fov.getName())) return;
        JsonElement saved = settings.get(fov.getName());
        try {
            if (Math.abs(saved.getAsDouble() - fov.getDefault()) > 0.5) useGameFov.set(false);
        } catch (RuntimeException ignored) {
            // unreadable: the loader already reset the slider
        }
    }
}
