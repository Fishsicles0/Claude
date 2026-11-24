package net.evadeclient.module.mods;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.evadeclient.EvadeClient;
import net.evadeclient.gui.EvadeMenu;
import net.evadeclient.mixin.InGameHudInvoker;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.module.client.ClientSettings;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Theme;
import net.evadeclient.util.UiFont;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * Hold (or tap, in Toggle mode) a key to zoom. Scroll while zoomed to change the level. Animated in and out in the
 * chosen style, with lower mouse sensitivity while zoomed, and optionally a clean view: no hand, no HUD, a vignette
 * or spyglass overlay. Only the world zooms unless FOV Changer says the hand should too.
 */
public class Zoom extends Module {
    private static final Identifier VIGNETTE = Identifier.ofVanilla("textures/misc/vignette.png");
    /** Smooth: easing rate per second at Animation Speed 1 (most of the way in about 0.2 s). */
    private static final double SMOOTH_RATE = 14;
    /** Linear: natural-log zoom units per second at Animation Speed 1 (a 4x zoom takes about 0.23 s). */
    private static final double LINEAR_RATE = 6;
    /** Ease Out Back: length of one animation at Animation Speed 1, in seconds. */
    private static final double BACK_TIME = 0.3;
    /** Zoom Level Indicator: shown this long after a scroll (ms), fading out over the last part. */
    private static final double INDICATOR_MS = 1000, INDICATOR_FADE_MS = 400;
    /** The zoom never goes further than this either way (ln of 1000x in, 4x out), whatever a setting or bug says. */
    private static final double LOG_MIN = -Math.log(1000), LOG_MAX = Math.log(4);

    private final KeySetting key = add(new KeySetting("Zoom Key", GLFW.GLFW_KEY_C, true));
    private final ModeSetting mode = add(new ModeSetting("Mode", "Hold", "Hold", "Toggle")
            .describe("Hold the key to zoom, or tap it to zoom in and tap again to zoom out."));
    private final NumberSetting level = add(new NumberSetting("Zoom Level", 4, 1.5, 50, 0.5)
            .describe("How far a zoom starts in (4 = four times closer)."));
    private final NumberSetting maxZoom = add(new NumberSetting("Max Zoom", 50, 5, 200, 5)
            .describe("The furthest you can zoom in, scrolling included."));
    private final BooleanSetting scroll = add(new BooleanSetting("Scroll To Zoom", true)
            .describe("Scroll while zoomed to zoom further in or out."));
    private final NumberSetting scrollStep = add(new NumberSetting("Scroll Step %", 15, 5, 50, 5)
            .describe("How much one scroll notch zooms in or out."));
    private final BooleanSetting rememberScroll = add(new BooleanSetting("Remember Scroll Level", false)
            .describe("Start the next zoom at the level you last scrolled to, instead of Zoom Level."));
    private final BooleanSetting smoothScroll = add(new BooleanSetting("Smooth Scroll", true)
            .describe("Ease between levels when you scroll, instead of jumping."));
    private final ModeSetting animation = add(new ModeSetting("Animation", "Smooth", "Instant", "Smooth", "Linear", "Ease Out Back")
            .describe("How the zoom moves in and out: at once, easing out, at a steady pace, or with a slight overshoot."));
    private final NumberSetting animationSpeed = add(new NumberSetting("Animation Speed", 1, 0.25, 4, 0.25)
            .describe("How fast the zoom animation plays (1 = normal)."));
    private final ModeSetting sensitivityMode = add(new ModeSetting("Sensitivity Mode", "Scaled", "Scaled", "Fixed", "Vanilla")
            .describe("Scaled: the mouse slows down the further you zoom. Fixed: one sensitivity while zoomed. "
                    + "Vanilla: unchanged."));
    private final NumberSetting sensitivityStrength = add(new NumberSetting("Sensitivity Strength %", 100, 0, 200, 10)
            .describe("Scaled: how strongly the mouse slows down as you zoom (100 = normal, 0 = not at all)."));
    private final NumberSetting fixedSensitivity = add(new NumberSetting("Fixed Sensitivity %", 50, 5, 100, 5)
            .describe("Fixed: mouse sensitivity while zoomed."));
    private final NumberSetting zoomSensitivity = add(new NumberSetting("Zoom Sensitivity %", 100, 10, 100, 5)
            .describe("Scaled: mouse sensitivity while zoomed, on top of the automatic slowdown."));
    private final BooleanSetting cinematic = add(new BooleanSetting("Cinematic Camera", false)
            .describe("Turn on vanilla's smooth Cinematic Camera while zoomed."));
    private final BooleanSetting hideHand = add(new BooleanSetting("Hide Hand", true)
            .describe("Hide your hand and held item while zoomed."));
    private final BooleanSetting hideHud = add(new BooleanSetting("Hide HUD", false)
            .describe("Hide the HUD while zoomed, like pressing F1."));
    private final ModeSetting overlay = add(new ModeSetting("Overlay", "None", "None", "Vignette", "Spyglass")
            .describe("Darken the screen edges, or look through a spyglass scope (first person), while zoomed."));
    private final BooleanSetting indicator = add(new BooleanSetting("Zoom Level Indicator", true)
            .describe("Show the zoom level for a moment when you scroll."));
    /** The level last scrolled to, for Remember Scroll Level (0 = none yet: start at Zoom Level). */
    private final NumberSetting lastLevel = add(new NumberSetting("Last Scroll Level", 0, 0, 200, 0).hidden());

    /** FOV multiplier (1 = no zoom), its natural log (what animates), and how far zoomed in we are (0..1). */
    private double current = 1, logCurrent = 0, amount = 0;
    /** Zoom Level is multiplied by this while scrolling (back to 1 after each zoom). */
    private double scrollMult = 1;
    /** The log target the animation was heading for last frame. */
    private double lastTarget = 0;
    /** Ease Out Back: where the running animation started and how far along it is (0..1). */
    private double backFrom, backFromAmount, backT = 1;
    /** A scroll changed the target since the last frame / an Instant zoom is easing a scroll step. */
    private boolean scrolled, easingScroll;
    private double lastFrame = EvadeMenu.clock.getAsDouble();
    /** When the last scroll happened ({@link EvadeMenu#clock} ms) and the level it went to, for the indicator. */
    private double lastScrollMs = -1e9, indicatorLevel = 1;
    /** Zoom Level as last seen: changing it makes remembered zooms start from the new level. */
    private double seenLevel;
    /** The spyglass overlay's scale, growing in like vanilla's. */
    private float spyglassScale = 0.5f;
    /** Cinematic Camera and the hidden HUD as they were before we changed them (only valid while applied). */
    private boolean cinematicApplied, savedSmooth, hudApplied, savedHud;
    private boolean wasZooming, toggled, keyWasDown;

    public Zoom() {
        super("Zoom", "Hold a key to zoom in.", Category.MODS);
        seenLevel = level.get();
        setEnabled(true);
    }

    public boolean zooming() {
        if (!isEnabled()) return false;
        return mode.is("Toggle") ? toggled : key.isDown();
    }

    /**
     * Advances the zoom once per frame (GameRendererMixin calls it before the world is drawn, enabled or not, so a
     * zoom that was cut off by disabling the module is always cleaned up).
     */
    public void frame() {
        double now = EvadeMenu.clock.getAsDouble(); // ms, sub-ms precise; tests swap in a fixed-step clock
        double dt = Math.max(0, Math.min(0.1, (now - lastFrame) / 1000.0)); // never negative if the clock jumps back
        lastFrame = now;
        if (!isEnabled()) {
            if (wasZooming || toggled || cinematicApplied || hudApplied || current != 1) reset();
            return;
        }
        syncLevels();

        boolean down = key.isDown(); // false while a screen is open
        boolean screen = mc.currentScreen != null;
        if (mode.is("Toggle")) {
            if (screen || mc.world == null) toggled = false;
            else if (down && !keyWasDown) toggled = !toggled;
        } else {
            toggled = false;
        }
        // A key still held when a screen closes is not a new press.
        keyWasDown = down || screen;

        boolean z = zooming();
        if (z != wasZooming) {
            if (!z) scrollMult = 1;
            wasZooming = z;
        }
        setCinematic(z && cinematic.get());
        setHudHidden(z && hideHud.get());
        animate(z, dt);
    }

    /** Moves the zoom one frame towards where it should be, in the chosen animation style. */
    private void animate(boolean z, double dt) {
        double lvl = effectiveLevel();
        double target = z ? -Math.log(lvl) : 0;
        double targetAmount = z ? 1 : 0;
        double speed = animationSpeed.get();

        if (Math.abs(target - lastTarget) > 1e-9) {
            if (scrolled && !smoothScroll.get()) {
                // The scroll step lands at once; an animation that is still running carries on from the new level.
                double shift = target - lastTarget;
                logCurrent += shift;
                backFrom += shift;
            } else {
                backFrom = logCurrent;
                backFromAmount = amount;
                backT = 0;
                easingScroll = scrolled;
            }
            lastTarget = target;
        }
        scrolled = false;
        // Only Ease Out Back keeps a running animation; another style picked mid-zoom never resumes a stale one.
        if (!animation.is("Ease Out Back")) backT = 1;

        switch (animation.get()) {
            case "Instant" -> {
                if (easingScroll) {
                    // Smooth Scroll still eases scroll steps when the zoom itself is instant.
                    logCurrent += (target - logCurrent) * (1 - Math.exp(-SMOOTH_RATE * speed * dt));
                    if (Math.abs(target - logCurrent) < 1e-4) easingScroll = false;
                }
                if (!easingScroll) logCurrent = target;
                amount = targetAmount;
            }
            case "Linear" -> {
                logCurrent = approach(logCurrent, target, LINEAR_RATE * speed * dt);
                // The zoom amount keeps pace with a full zoom to this level.
                double span = Math.max(0.05, Math.log(lvl));
                amount = approach(amount, targetAmount, LINEAR_RATE * speed * dt / span);
            }
            case "Ease Out Back" -> {
                backT = Math.min(1, backT + dt * speed / BACK_TIME);
                double e = easeOutBack(backT);
                logCurrent = backFrom + (target - backFrom) * e;
                amount = MathHelper.clamp(backFromAmount + (targetAmount - backFromAmount) * e, 0, 1);
                if (backT >= 1) {
                    logCurrent = target;
                    amount = targetAmount;
                }
            }
            default -> { // Smooth
                double k = 1 - Math.exp(-SMOOTH_RATE * speed * dt);
                logCurrent += (target - logCurrent) * k;
                amount += (targetAmount - amount) * k;
            }
        }
        if (!Double.isFinite(logCurrent)) logCurrent = target;
        if (!Double.isFinite(amount)) amount = targetAmount;
        logCurrent = MathHelper.clamp(logCurrent, LOG_MIN, LOG_MAX);
        amount = MathHelper.clamp(amount, 0, 1);
        // Snap the last sliver so "not zoomed" is exactly 1 (sensitivity and the FOV are then untouched). Ease Out
        // Back passes through 1 on its way out, so it only snaps once its animation is over.
        if (!z && Math.abs(logCurrent) < 1e-4 && (!animation.is("Ease Out Back") || backT >= 1)) {
            logCurrent = 0;
            amount = 0;
            easingScroll = false;
        }
        current = Math.exp(logCurrent);
    }

    private static double approach(double from, double to, double step) {
        if (!(step > 0)) return from;
        return from < to ? Math.min(to, from + step) : Math.max(to, from - step);
    }

    /** Ease Out Back: overshoots a little past 1 and settles back (t in 0..1). */
    private static double easeOutBack(double t) {
        double c1 = 1.70158, c3 = c1 + 1, u = t - 1;
        return 1 + c3 * u * u * u + c1 * u * u;
    }

    /** The zoom level a zoom goes to right now: Zoom Level with scrolling (or the remembered level), within limits. */
    private double effectiveLevel() {
        double start = rememberScroll.get() && lastLevel.get() >= 1 ? lastLevel.get() : level.get() * scrollMult;
        double l = MathHelper.clamp(start, 1, maxZoom.get());
        return Double.isFinite(l) ? l : 1;
    }

    /** A new Zoom Level replaces the remembered one; a lower Max Zoom pulls the remembered one in. */
    private void syncLevels() {
        double l = level.get();
        if (l != seenLevel) {
            seenLevel = l;
            if (lastLevel.get() != 0) lastLevel.set(0.0);
        }
        if (lastLevel.get() > maxZoom.get()) lastLevel.set(maxZoom.get());
    }

    /** FOV multiplier for this frame (1 = no zoom). */
    public double fovMultiplier() { return current; }

    /** Whether the first-person hand is hidden right now (Hide Hand while zoomed). */
    public boolean hidesHand() {
        return hideHand.get() && zooming();
    }

    /**
     * Mouse sensitivity scale so aiming stays controllable while zoomed. It follows the animated zoom, so it is
     * exactly 1 once a zoom has eased all the way out.
     */
    public double sensitivity() {
        if (!isEnabled()) return 1;
        double a = MathHelper.clamp(amount, 0, 1);
        return switch (sensitivityMode.get()) {
            case "Vanilla" -> 1;
            case "Fixed" -> 1 + (fixedSensitivity.get() / 100.0 - 1) * a;
            default -> {
                double s = 1 - (1 - Math.min(1, current * 1.2)) * sensitivityStrength.get() / 100.0;
                s = MathHelper.clamp(s, 0.02, 1);
                double extra = zoomSensitivity.get() / 100.0;
                yield s * (1 + (extra - 1) * a);
            }
        };
    }

    /** @return true if the scroll was used for zoom. */
    public boolean onScroll(double amount) {
        if (mc.currentScreen != null || !zooming() || !scroll.get()) return false;
        double steps = mc.options.getDiscreteMouseScroll().getValue() ? Math.signum(amount) : amount;
        steps = MathHelper.clamp(steps, -10, 10);
        double from = effectiveLevel();
        double to = MathHelper.clamp(from * Math.pow(1 + scrollStep.get() / 100.0, steps), 1, maxZoom.get());
        if (!Double.isFinite(to)) return true;
        if (rememberScroll.get()) lastLevel.set(to);
        else scrollMult = to / level.get();
        scrolled = true;
        indicatorLevel = to;
        lastScrollMs = EvadeMenu.clock.getAsDouble();
        return true;
    }

    // ------------------------------------------------------------------ overlay and indicator

    /**
     * The Overlay, drawn under the HUD. GameRendererMixin calls it on every frame with an in-game HUD, even with the
     * HUD hidden (F1 or Hide HUD), enabled or not.
     */
    public void renderOverlay(DrawContext ctx, float deltaTicks) {
        boolean spyglass = isEnabled() && overlay.is("Spyglass") && zooming() && mc.player != null
                && mc.options.getPerspective().isFirstPerson();
        if (!spyglass) spyglassScale = 0.5f;
        if (!isEnabled() || mc.player == null) return;
        if (overlay.is("Vignette")) {
            float strength = (float) MathHelper.clamp(1 - current, 0, 1);
            if (strength < 0.004f) return;
            int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
            // The vignette pipeline darkens by the colour, as vanilla's own vignette does.
            ctx.drawTexture(RenderPipelines.VIGNETTE, VIGNETTE, 0, 0, 0f, 0f, w, h, w, h,
                    ColorHelper.fromFloats(1f, strength, strength, strength));
        } else if (spyglass) {
            float d = Float.isFinite(deltaTicks) ? Math.max(0f, deltaTicks) : 1f;
            spyglassScale = MathHelper.lerp(Math.min(1f, 0.5f * d), spyglassScale, 1.125f);
            // Vanilla already draws the scope while you really look through a spyglass with the HUD shown.
            if (mc.player.isUsingSpyglass() && !mc.options.hudHidden) return;
            ((InGameHudInvoker) mc.inGameHud).evade$renderSpyglassOverlay(ctx, spyglassScale);
        }
    }

    /**
     * The Zoom Level Indicator, drawn over the HUD at the bottom centre (above the hotbar when the HUD shows). Called
     * like {@link #renderOverlay}.
     */
    public void renderIndicator(DrawContext ctx) {
        if (!isEnabled() || !indicator.get() || !zooming()) return;
        double elapsed = EvadeMenu.clock.getAsDouble() - lastScrollMs;
        if (!(elapsed >= 0 && elapsed < INDICATOR_MS)) return;
        float alpha = (float) MathHelper.clamp((INDICATOR_MS - elapsed) / INDICATOR_FADE_MS, 0, 1);
        String text = String.format(Locale.ROOT, "%.1fx", indicatorLevel);
        var tr = mc.textRenderer;
        boolean smooth = smoothFont();
        int tw = UiFont.width(tr, text, UiFont.Role.STRONG, smooth);
        int w = tw + 12, h = 14;
        int x = (ctx.getScaledWindowWidth() - w) / 2;
        // Clear of the hotbar, status bars, held item name and action bar; near the bottom edge with the HUD hidden.
        int y = ctx.getScaledWindowHeight() - h - (mc.options.hudHidden ? 12 : 74);
        float was = Theme.fade;
        Theme.fade = was * alpha;
        try {
            Theme.round(ctx, x, y, x + w, y + h, Theme.HUD_BG, Theme.R_HUD);
            Theme.fill(ctx, x + 2, y, x + w - 2, y + 1, Theme.RED | 0xFF000000);
            UiFont.draw(ctx, tr, text, x + (w - tw) / 2, y + 3, Theme.TEXT, UiFont.Role.STRONG, smooth);
        } finally {
            Theme.fade = was;
        }
    }

    private static boolean smoothFont() {
        if (EvadeClient.modules == null) return false;
        ClientSettings cs = EvadeClient.modules.get(ClientSettings.class);
        return cs != null && cs.smoothFont.get();
    }

    // ------------------------------------------------------------------ state

    @Override
    protected void onDisable() {
        reset();
    }

    /** Back to no zoom at once, handing Cinematic Camera and the HUD back if we changed them. */
    private void reset() {
        restoreCinematic();
        restoreHud();
        wasZooming = false;
        toggled = false;
        keyWasDown = false;
        scrollMult = 1;
        current = 1;
        logCurrent = 0;
        amount = 0;
        lastTarget = 0;
        backT = 1;
        scrolled = false;
        easingScroll = false;
        spyglassScale = 0.5f;
    }

    private void setCinematic(boolean want) {
        if (want == cinematicApplied || mc.options == null) return;
        if (want) {
            savedSmooth = mc.options.smoothCameraEnabled;
            mc.options.smoothCameraEnabled = true;
            cinematicApplied = true;
        } else {
            restoreCinematic();
        }
    }

    /** Undo our Cinematic Camera switch, unless the player turned it off themselves in the meantime. */
    private void restoreCinematic() {
        if (!cinematicApplied) return;
        cinematicApplied = false;
        if (mc.options != null && mc.options.smoothCameraEnabled) mc.options.smoothCameraEnabled = savedSmooth;
    }

    private void setHudHidden(boolean want) {
        if (want == hudApplied || mc.options == null) return;
        if (want) {
            savedHud = mc.options.hudHidden;
            mc.options.hudHidden = true;
            hudApplied = true;
        } else {
            restoreHud();
        }
    }

    /** Show the HUD again as it was before the zoom, unless the player brought it back themselves (F1). */
    private void restoreHud() {
        if (!hudApplied) return;
        hudApplied = false;
        if (mc.options != null && mc.options.hudHidden) mc.options.hudHidden = savedHud;
    }

    /**
     * "Smooth Zoom" (on/off) became Animation: a config that had it off keeps an instant zoom. Also takes the loaded
     * Zoom Level as seen, so loading a config never clears the remembered scroll level.
     */
    @Override
    public void onConfigLoaded(JsonObject settings) {
        seenLevel = level.get();
        JsonElement old = settings.get("Smooth Zoom");
        if (old == null || settings.has(animation.getName())) return;
        try {
            if (!old.getAsBoolean()) animation.set("Instant");
        } catch (RuntimeException ignored) {
            // unreadable: keep the default
        }
    }
}
