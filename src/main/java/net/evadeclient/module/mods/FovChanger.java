package net.evadeclient.module.mods;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.gui.EvadeMenu;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.Items;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

/**
 * Your field of view, your way. Lock mode keeps speed effects, sprinting and flying from changing it; Custom Effects
 * picks how much each vanilla FOV effect still does. Also: the hand's own FOV, how fast effects ease in, a second FOV
 * on a key, and an offset for third person. Everything here only changes what you see.
 */
public class FovChanger extends Module {
    public final ModeSetting mode = add(new ModeSetting("Mode", "Lock", "Lock", "Custom Effects")
            .describe("Lock: speed, sprinting and flying never change your FOV. Custom Effects: choose how much they do."));
    public final BooleanSetting useGameFov = add(new BooleanSetting("Use Game FOV", true)
            .describe("Keep the FOV from Minecraft's options; turn off to use the FOV below."));
    public final NumberSetting fov = add(new NumberSetting("FOV", 70, 30, 120, 1)
            .describe("The field of view to use when Use Game FOV is off."));
    public final NumberSetting speedEffect = add(new NumberSetting("Speed & Sprint Effect %", 0, 0, 100, 5)
            .describe("Custom Effects: how much sprinting, Speed and Slowness change your FOV (100 = vanilla)."));
    public final BooleanSetting flyingEffect = add(new BooleanSetting("Flying Effect", false)
            .describe("Custom Effects: widen the FOV a little while flying, like vanilla."));
    public final BooleanSetting keepBowZoom = add(new BooleanSetting("Keep Bow Zoom", true)
            .describe("Still zoom in slightly while drawing a bow."));
    public final BooleanSetting spyglassZoom = add(new BooleanSetting("Spyglass Zoom", true)
            .describe("Zoom in while looking through a spyglass."));
    public final BooleanSetting underwaterFov = add(new BooleanSetting("Underwater FOV", true)
            .describe("Narrow the FOV a little underwater and in lava, like vanilla."));
    public final BooleanSetting deathZoom = add(new BooleanSetting("Death Zoom", true)
            .describe("Slowly zoom in when you die, like vanilla."));
    public final NumberSetting transitionSpeed = add(new NumberSetting("Transition Speed", 0.5, 0.05, 1.0, 0.05)
            .describe("How fast FOV effects ease in and out (0.5 = vanilla, 1 = instant)."));
    public final NumberSetting handFov = add(new NumberSetting("Hand FOV", 70, 40, 110, 1)
            .describe("The FOV your hand and held item are drawn with (70 = vanilla). Higher draws them smaller."));
    public final BooleanSetting handIgnoresZoom = add(new BooleanSetting("Hand FOV Ignores Zoom", true)
            .describe("Keep your hand its normal size while you zoom."));
    public final KeySetting altKey = add(new KeySetting("Alt FOV Key", GLFW.GLFW_KEY_UNKNOWN, true)
            .describe("Switches to the Alt FOV below."));
    public final ModeSetting altMode = add(new ModeSetting("Alt FOV Mode", "Hold", "Hold", "Toggle")
            .describe("Hold the key for the Alt FOV, or tap it to switch and tap again to switch back."));
    public final NumberSetting altFov = add(new NumberSetting("Alt FOV", 100, 30, 120, 1)
            .describe("The FOV to use while the Alt FOV Key is active."));
    public final NumberSetting thirdPersonOffset = add(new NumberSetting("Third Person FOV Offset", 0, -30, 30, 1)
            .describe("Added to your FOV in third person, front and back view."));
    public final BooleanSetting smoothSwitch = add(new BooleanSetting("Smooth Switch", true)
            .describe("Ease between FOVs when the Alt FOV Key or the camera view changes."));

    /** Time constant of Smooth Switch, in seconds (about 150 ms to settle). */
    private static final double SWITCH_TAU = 0.05;

    /** The base FOV of this frame in degrees, eased towards the target; NaN while off (the game's FOV is used). */
    private double currentBase = Double.NaN;
    private double lastFrame = EvadeMenu.clock.getAsDouble();
    private boolean altToggled, altWasDown;

    public FovChanger() {
        super("FOV Changer", "Sets your FOV and how speed, sprinting and items change it.", Category.MODS);
    }

    /** The module when it is on, else null. */
    public static FovChanger active() {
        if (EvadeClient.modules == null) return null;
        FovChanger m = EvadeClient.modules.get(FovChanger.class);
        return m != null && m.isEnabled() ? m : null;
    }

    // ------------------------------------------------------------------ base FOV (per frame)

    /**
     * Advances the base FOV once per frame. GameRendererMixin calls it before the world is drawn, enabled or not,
     * so turning the module off always drops what it held.
     */
    public void frame() {
        double now = EvadeMenu.clock.getAsDouble(); // ms; tests swap in a fixed-step clock
        double dt = Math.max(0, Math.min(0.1, (now - lastFrame) / 1000.0));
        lastFrame = now;
        if (!isEnabled() || mc.options == null) {
            currentBase = Double.NaN;
            altToggled = false;
            altWasDown = false;
            return;
        }

        boolean down = altKey.isDown(); // false while a screen is open
        if (altMode.is("Toggle")) {
            if (down && !altWasDown) altToggled = !altToggled;
        } else {
            altToggled = false;
        }
        // A key still held when a screen closes is not a new press.
        altWasDown = down || mc.currentScreen != null;
        boolean alt = altMode.is("Toggle") ? altToggled : down;

        double target = alt ? altFov.get() : useGameFov.get() ? mc.options.getFov().getValue() : fov.get();
        if (!mc.options.getPerspective().isFirstPerson()) target += thirdPersonOffset.get();
        target = MathHelper.clamp(target, 1, 179);

        if (!smoothSwitch.get() || !Double.isFinite(currentBase)) {
            currentBase = target;
        } else {
            currentBase += (target - currentBase) * (1 - Math.exp(-dt / SWITCH_TAU));
            if (Math.abs(target - currentBase) < 0.01 || !Double.isFinite(currentBase)) currentBase = target;
        }
    }

    /** The base FOV in degrees for this frame, or NaN to keep the game's (the module is off). */
    public static float baseFov() {
        FovChanger m = active();
        return m == null ? Float.NaN : (float) m.currentBase;
    }

    // ------------------------------------------------------------------ FOV effects (per tick)

    /**
     * The FOV effect multiplier the game eases towards each tick, worked out like vanilla's
     * {@code AbstractClientPlayerEntity.getFovMultiplier} with each effect weighted by the settings.
     */
    public float multiplier(AbstractClientPlayerEntity p, boolean firstPerson, float effectScale) {
        boolean custom = mode.is("Custom Effects");
        float speedShare = custom ? (float) (speedEffect.get() / 100.0) : 0f;
        float m = 1f;
        if (custom && flyingEffect.get() && p.getAbilities().flying) m *= 1.1f;
        float walk = p.getAbilities().getWalkSpeed();
        if (speedShare > 0f && walk != 0f) {
            float ratio = (float) p.getAttributeValue(EntityAttributes.MOVEMENT_SPEED) / walk;
            m *= 1f + ((ratio + 1f) / 2f - 1f) * speedShare;
        }
        if (p.isUsingItem()) {
            if (p.getActiveItem().isOf(Items.BOW)) {
                if (keepBowZoom.get()) {
                    float draw = Math.min(p.getItemUseTime() / 20f, 1f);
                    m *= 1f - MathHelper.square(draw) * 0.15f;
                }
            } else if (firstPerson && p.isUsingSpyglass() && spyglassZoom.get()) {
                return 0.1f; // vanilla's spyglass ignores FOV Effects too
            }
        }
        if (!Float.isFinite(m)) m = 1f;
        return MathHelper.lerp(effectScale, 1f, m);
    }

    /** How far the FOV effect eases towards its target each tick ({@code vanilla} is 0.5). */
    public static float transition(float vanilla) {
        FovChanger m = active();
        return m == null ? vanilla : m.transitionSpeed.getFloat();
    }

    /** Whether the underwater/lava FOV change applies. */
    public static boolean underwater() {
        FovChanger m = active();
        return m == null || m.underwaterFov.get();
    }

    /** Whether the death zoom applies ({@code dead} is the camera entity's real state). */
    public static boolean deathZoom(boolean dead) {
        if (!dead) return false;
        FovChanger m = active();
        return m == null || m.deathZoom.get();
    }

    /**
     * The FOV the first-person hand is drawn with. {@code fov} is vanilla's (70, with the death and underwater
     * changes); {@code zoom} the world zoom multiplier (1 = none), which only reaches the hand when wanted.
     */
    public static float handFov(float fov, double zoom) {
        FovChanger m = active();
        if (m == null) return fov;
        fov *= m.handFov.getFloat() / 70f;
        if (!m.handIgnoresZoom.get()) fov *= (float) zoom;
        return fov;
    }

    @Override
    protected void onDisable() {
        altToggled = false;
        altWasDown = false;
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
