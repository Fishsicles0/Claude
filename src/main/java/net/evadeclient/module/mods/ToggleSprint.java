package net.evadeclient.module.mods;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.TextHudModule;
import net.evadeclient.module.hud.CompassHud;
import net.evadeclient.setting.BooleanSetting;
import net.minecraft.client.option.KeyBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * Tap your sprint/sneak key to toggle instead of holding it. Shows the state on screen.
 *
 * <p>Nothing presses the game's keys: {@code ToggleSprintInputMixin} adds the toggled state to the movement input
 * the game builds each tick, exactly as if the key were held. So the key bindings always reflect the real keys,
 * turning the module off lets go at once, and the game's own "Sprint: Toggle" / "Sneak: Toggle" options win:
 * while one of them is on, this module leaves that key alone (and only shows its state).
 */
public class ToggleSprint extends TextHudModule {
    /** Default x: centred on screen (see {@link #resolveX}); dragging it in the HUD editor stores a real position. */
    private static final int CENTRED = Integer.MIN_VALUE;
    /** Default y: just under the Direction HUD. */
    private static final int DEFAULT_Y = 26;

    private final BooleanSetting sneak = add(new BooleanSetting("Toggle Sneak", false)
            .describe("Tap sneak to stay sneaking, tap again to stand. Off while the game's own Sneak: Toggle is on."));
    private final BooleanSetting showHud = add(new BooleanSetting("Show Status", true));

    private boolean sprinting = true, sneaking;
    private final Tap sprintTap = new Tap(), sneakTap = new Tap();

    public ToggleSprint() {
        super("ToggleSprint", "Tap sprint or sneak to toggle them.", Category.MODS, CENTRED, DEFAULT_Y);
        setEnabled(true);
    }

    @Override
    public void onTick() {
        boolean screen = mc.currentScreen != null;
        if (managesSprint()) {
            if (sprintTap.poll(mc.options.sprintKey, screen)) sprinting = !sprinting;
        } else {
            sprintTap.reset();
        }
        if (managesSneak()) {
            if (sneakTap.poll(mc.options.sneakKey, screen)) sneaking = !sneaking;
        } else {
            sneakTap.reset();
            // Turning Toggle Sneak off (or the game's own toggle on) stands you up.
            sneaking = false;
        }
    }

    @Override
    protected void onEnable() {
        sprintTap.reset();
        sneakTap.reset();
    }

    @Override
    protected void onDisable() {
        // Nothing to release: the keys were never pressed for you. Leaving a world also lands here.
        sneaking = false;
        sprintTap.reset();
        sneakTap.reset();
    }

    /** Whether this module toggles sprint (not while the game's own Sprint: Toggle option is on). */
    private boolean managesSprint() {
        return !mc.options.getSprintToggled().getValue();
    }

    /** Whether this module toggles sneak (Toggle Sneak on, and the game's own Sneak: Toggle option off). */
    private boolean managesSneak() {
        return sneak.get() && !mc.options.getSneakToggled().getValue();
    }

    private boolean holdsSprint() {
        return isEnabled() && hooksActive() && sprinting && managesSprint();
    }

    private boolean holdsSneak() {
        return isEnabled() && hooksActive() && sneaking && managesSneak();
    }

    /** Sprint is toggled on, by this module or by the game's own toggle option. */
    private boolean sprintToggled() {
        return managesSprint() ? sprinting : mc.options.sprintKey.isPressed();
    }

    /** Sneak is toggled on, by this module or by the game's own toggle option. */
    private boolean sneakToggled() {
        if (mc.options.getSneakToggled().getValue()) return mc.options.sneakKey.isPressed();
        return sneak.get() && sneaking;
    }

    // ------------------------------------------------------------------ input hook

    private static ToggleSprint instance() {
        return EvadeClient.modules == null ? null : EvadeClient.modules.get(ToggleSprint.class);
    }

    /** The sprint input for this tick: the real key, or held for you while toggled on. Called by the input mixin. */
    public static boolean sprintInput(boolean keyDown) {
        if (keyDown) return true;
        ToggleSprint t = instance();
        return t != null && t.holdsSprint();
    }

    /** The sneak input for this tick: the real key, or held for you while toggled on. Called by the input mixin. */
    public static boolean sneakInput(boolean keyDown) {
        if (keyDown) return true;
        ToggleSprint t = instance();
        return t != null && t.holdsSneak();
    }

    /**
     * Turns one key binding into taps: one per press made in game, however short. A tap shorter than a tick still
     * counts (the binding's press counter catches it), holding the key down counts once (key repeat is ignored),
     * and a key already held when a screen closes is not a tap.
     */
    private static final class Tap {
        private boolean wasDown, screenWas, primed;

        /** @return true once for each new press since the last poll */
        boolean poll(KeyBinding bind, boolean screen) {
            int presses = 0;
            while (bind.wasPressed()) presses++;
            boolean down = bind.isPressed();
            boolean tap = primed && !screen && !screenWas && !wasDown && (down || presses > 0);
            wasDown = down;
            screenWas = screen;
            primed = true;
            return tap;
        }

        /** Forget the key's history: the next poll only catches up with it (stale presses aren't taps). */
        void reset() {
            primed = false;
        }
    }

    // ------------------------------------------------------------------ HUD

    @Override
    public int resolveX(int screenW) {
        // Centred by default; once dragged it behaves like any element.
        if (x != CENTRED) return super.resolveX(screenW);
        return Math.max(0, (screenW - scaledWidth()) / 2);
    }

    @Override
    public int resolveY(int screenH) {
        // At its default spot it stays under a centred Direction HUD, wherever that is pushed (e.g. by boss bars).
        if (x == CENTRED && y == DEFAULT_Y && EvadeClient.modules != null) {
            CompassHud compass = EvadeClient.modules.get(CompassHud.class);
            if (compass != null && compass.isEnabled() && compass.shouldShow()) {
                int below = compass.resolveY(screenH) + compass.scaledHeight() + 2;
                if (below < screenH / 3) return Math.max(DEFAULT_Y, below);
            }
        }
        return super.resolveY(screenH);
    }

    @Override
    protected List<String> lines(boolean preview) {
        if (!showHud.get() && !preview) return new ArrayList<>();
        if (sneakToggled()) return new ArrayList<>(List.of("Sneaking (Toggled)"));
        if (mc.player != null && mc.player.isSneaking()) return new ArrayList<>(List.of("Sneaking (Key Held)"));
        if (sprintToggled()) return new ArrayList<>(List.of("Sprinting (Toggled)"));
        return preview ? new ArrayList<>(List.of("Sprinting (Toggled)")) : new ArrayList<>();
    }
}
