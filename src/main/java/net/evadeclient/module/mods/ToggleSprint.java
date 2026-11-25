package net.evadeclient.module.mods;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.TextHudModule;
import net.evadeclient.module.hud.CompassHud;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.StringSetting;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Tap your sprint/sneak key to toggle instead of holding it. Shows the state on screen.
 *
 * <p>Nothing presses the game's keys: {@code ToggleSprintInputMixin} adds the toggled state to the movement input
 * the game builds each tick, exactly as if the key were held. So the key bindings always reflect the real keys,
 * turning the module off lets go at once, and the game's own "Sprint: Toggle" / "Sneak: Toggle" options win:
 * while one of them is on, this module leaves that key alone (and only shows its state). Only the input is held, so
 * the game's own rules (hunger, moving forward, blindness) still decide whether you actually sprint, at vanilla speed.
 */
public class ToggleSprint extends TextHudModule {
    /** Default x: centred on screen (see {@link #resolveX}); dragging it in the HUD editor stores a real position. */
    private static final int CENTRED = Integer.MIN_VALUE;
    /** Default y: just under the Direction HUD. */
    private static final int DEFAULT_Y = 26;
    private static final String REMEMBER = "Remember", ON = "On", OFF = "Off";

    // ---- Sprint
    private final ModeSetting sprintMode = add(new ModeSetting("Sprint Mode", "Toggle", "Toggle", "Hold", "Always")
            .describe("Toggle: tap to start or stop sprinting. Hold: sprint only while the key is held. Always: always sprint when you can."));
    private final KeySetting toggleKey = add(new KeySetting("Toggle Key", GLFW.GLFW_KEY_UNKNOWN, true)
            .describe("A separate key that toggles sprint, leaving the game's sprint key as a normal hold. None uses the sprint key itself."));
    private final ModeSetting sprintOnJoin = add(new ModeSetting("Sprint On Join", REMEMBER, REMEMBER, ON, OFF)
            .describe("Whether sprint starts toggled on when you join a world or server. Remember keeps it as you left it."));

    // ---- Sneak
    private final BooleanSetting sneak = add(new BooleanSetting("Toggle Sneak", false)
            .describe("Tap sneak to stay sneaking, tap again to stand. Off while the game's own Sneak: Toggle is on."));
    private final ModeSetting sneakOnJoin = add(new ModeSetting("Sneak On Join", OFF, REMEMBER, ON, OFF)
            .describe("Whether a toggled sneak starts on when you join a world or server. Remember keeps it as you left it."));
    private final BooleanSetting releaseMounting = add(new BooleanSetting("Release Sneak When Mounting", true)
            .describe("Stand up when you get on a horse, boat or minecart, so a toggled sneak doesn't get you straight off again."));
    private final BooleanSetting releaseFlying = add(new BooleanSetting("Release Sneak When Flying", true)
            .describe("Stand up when you start flying or gliding, so you don't keep sinking. Tap sneak again to toggle it back."));
    private final BooleanSetting releaseWater = add(new BooleanSetting("Release Sneak In Water", false)
            .describe("Stand up when you go into water, so you don't keep sinking. Tap sneak again to toggle it back."));
    private final BooleanSetting pauseInMenus = add(new BooleanSetting("Pause In Menus", true)
            .describe("Let go of a toggled sprint and sneak while a menu or chat is open; they come back when it closes."));

    // ---- Status line
    private final BooleanSetting showHud = add(new BooleanSetting("Show Status", true)
            .describe("Shows a line of text on screen saying whether you're sprinting or sneaking, and why."));
    private final StringSetting sprintToggledText = add(label("Sprint Toggled Text", "Sprinting (Toggled)")
            .describe("What the status line says while sprint is toggled on. Leave blank to hide that line."));
    private final StringSetting sprintHeldText = add(label("Sprint Held Text", "Sprinting (Key Held)")
            .describe("What the status line says while you hold the sprint key. Leave blank to hide that line."));
    private final StringSetting sprintVanillaText = add(label("Sprint Vanilla Text", "Sprinting (Vanilla)")
            .describe("What the status line says while you sprint the normal way (double-tap forward). Leave blank to hide that line."));
    private final StringSetting sneakToggledText = add(label("Sneak Toggled Text", "Sneaking (Toggled)")
            .describe("What the status line says while sneak is toggled on. Leave blank to hide that line."));
    private final StringSetting sneakHeldText = add(label("Sneak Held Text", "Sneaking (Key Held)")
            .describe("What the status line says while you hold the sneak key. Leave blank to hide that line."));
    private final BooleanSetting showWhenIdle = add(new BooleanSetting("Show When Idle", false)
            .describe("Show a line even when you're neither sprinting nor sneaking."));
    private final StringSetting idleText = add(label("Idle Text", "Walking")
            .describe("What the status line says while you're neither sprinting nor sneaking, with Show When Idle on."));

    // ---- Remembered between sessions (never shown)
    private final BooleanSetting lastSprint = add(new BooleanSetting("Last Sprint", true).hidden());
    private final BooleanSetting lastSneak = add(new BooleanSetting("Last Sneak", false).hidden());

    private boolean sprinting = true, sneaking;
    private final Tap sprintTap = new Tap(), sneakTap = new Tap();

    /** The separate Toggle Key's state at the last poll (it's polled every frame and every tick). */
    private boolean keyWasDown, keyPrimed;
    /** Context for "Release Sneak When ...": what the player was doing at the last check. */
    private boolean wasRiding, wasFlying, wasInWater, contextPrimed;
    /** The connection the join state was last applied for (a new one is a new world or server). */
    private ClientPlayNetworkHandler joinedHandler;
    /** Client tick at which to save the remembered state (-1: nothing pending). Saving is batched, not per tap. */
    private long saveAt = -1;

    public ToggleSprint() {
        super("ToggleSprint", "Tap sprint or sneak to toggle them.", Category.MODS, CENTRED, DEFAULT_Y);
        setEnabled(true);
    }

    private static StringSetting label(String name, String text) {
        return new StringSetting(name, text, "text, blank hides it").maxLength(48);
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.options == null) return;
        applyJoinState();
        updateContext();
        boolean screen = mc.currentScreen != null;
        if (managesSprint() && sprintMode.is("Toggle")) {
            if (separateKey()) {
                sprintTap.reset();
                pollToggleKey();
            } else {
                keyPrimed = false;
                if (sprintTap.poll(mc.options.sprintKey, screen)) flipSprint();
            }
        } else {
            sprintTap.reset();
            keyPrimed = false;
        }
        if (managesSneak()) {
            if (sneakTap.poll(mc.options.sneakKey, screen)) {
                sneaking = !sneaking;
                remember();
            }
        } else {
            sneakTap.reset();
            // Turning Toggle Sneak off (or the game's own toggle on) stands you up.
            sneaking = false;
        }
        if (saveAt >= 0 && EvadeClient.ticks >= saveAt) flushSave();
    }

    /** The separate Toggle Key is also polled every frame, so a tap shorter than a tick still counts. */
    @Override
    public void onRender(RenderContext ctx) {
        if (mc.player != null && managesSprint() && sprintMode.is("Toggle") && separateKey()) pollToggleKey();
    }

    @Override
    protected void onEnable() {
        sprintTap.reset();
        sneakTap.reset();
        keyPrimed = false;
        contextPrimed = false;
    }

    @Override
    protected void onDisable() {
        // Nothing to release: the keys were never pressed for you, so the real keys take over at once.
        sneaking = false;
        sprintTap.reset();
        sneakTap.reset();
        keyPrimed = false;
        contextPrimed = false;
        if (saveAt >= 0) flushSave();
    }

    /** Whether this module toggles sprint (not while the game's own Sprint: Toggle option is on). */
    private boolean managesSprint() {
        return !mc.options.getSprintToggled().getValue();
    }

    /** Whether this module toggles sneak (Toggle Sneak on, and the game's own Sneak: Toggle option off). */
    private boolean managesSneak() {
        return sneak.get() && !mc.options.getSneakToggled().getValue();
    }

    /** Whether a separate Toggle Key is bound (otherwise the game's sprint key toggles). */
    private boolean separateKey() {
        return toggleKey.get() != GLFW.GLFW_KEY_UNKNOWN;
    }

    /** Pause In Menus: toggled states are let go while a screen is open, and apply again as soon as it closes. */
    private boolean pausedForMenu() {
        return pauseInMenus.get() && mc.currentScreen != null;
    }

    /** Sprint held for you this tick: toggled on (Toggle) or always (Always); never in Hold mode. */
    private boolean holdsSprint() {
        if (!isEnabled() || !hooksActive() || !managesSprint() || pausedForMenu()) return false;
        return sprintMode.is("Always") || (sprintMode.is("Toggle") && sprinting);
    }

    private boolean holdsSneak() {
        return isEnabled() && hooksActive() && sneaking && managesSneak() && !pausedForMenu();
    }

    /** Sprint is toggled on, by this module or by the game's own toggle option. */
    private boolean sprintToggled() {
        if (!managesSprint()) return mc.options.sprintKey.isPressed();
        return sprintMode.is("Always") || (sprintMode.is("Toggle") && sprinting);
    }

    /** Sneak is toggled on, by this module or by the game's own toggle option. */
    private boolean sneakToggled() {
        if (mc.options.getSneakToggled().getValue()) return mc.options.sneakKey.isPressed();
        return sneak.get() && sneaking;
    }

    private void flipSprint() {
        sprinting = !sprinting;
        remember();
    }

    /** Test hook: the module's own sprint toggle (what Toggle mode holds), whatever the mode. */
    public boolean sprintingForTest() { return sprinting; }

    /** Test hook: the module's own sneak toggle. */
    public boolean sneakingForTest() { return sneaking; }

    // ------------------------------------------------------------------ remembered state and join

    /** Note the current toggles as the ones to Remember, and save them shortly (taps in a row save once). */
    private void remember() {
        lastSprint.set(sprinting);
        lastSneak.set(sneaking);
        if (saveAt < 0) saveAt = EvadeClient.ticks + 20;
    }

    private void flushSave() {
        saveAt = -1;
        if (EvadeClient.config == null) return;
        try {
            EvadeClient.config.save();
        } catch (RuntimeException e) {
            EvadeClient.LOG.warn("ToggleSprint: couldn't save the toggle state", e);
        }
    }

    /**
     * On joining a world or server (a new connection, not a dimension change), start sprint and sneak as Sprint On
     * Join / Sneak On Join say: as you left them, on, or off.
     */
    private void applyJoinState() {
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        if (handler == null || mc.world == null || mc.player == null || handler == joinedHandler) return;
        joinedHandler = handler;
        sprinting = startState(sprintOnJoin, lastSprint.get());
        sneaking = startState(sneakOnJoin, lastSneak.get());
        sprintTap.reset();
        sneakTap.reset();
        keyPrimed = false;
        contextPrimed = false;
    }

    private static boolean startState(ModeSetting mode, boolean last) {
        if (mode.is(ON)) return true;
        if (mode.is(OFF)) return false;
        return last;
    }

    // ------------------------------------------------------------------ context release

    /**
     * Release Sneak When Mounting / Flying / In Water: a toggled sneak is let go the moment you get on something,
     * start flying or gliding, or go into water. Only on that edge, so tapping sneak again toggles it back on.
     */
    private void updateContext() {
        ClientPlayerEntity p = mc.player;
        if (p == null) {
            contextPrimed = false;
            return;
        }
        boolean riding = p.hasVehicle();
        boolean flying = p.getAbilities().flying || p.isGliding();
        boolean water = p.isTouchingWater();
        if (contextPrimed && sneaking && managesSneak()
                && ((releaseMounting.get() && riding && !wasRiding)
                || (releaseFlying.get() && flying && !wasFlying)
                || (releaseWater.get() && water && !wasInWater))) {
            sneaking = false;
            remember();
        }
        wasRiding = riding;
        wasFlying = flying;
        wasInWater = water;
        contextPrimed = true;
    }

    // ------------------------------------------------------------------ separate toggle key

    /** Rising edge of the Toggle Key made in game: a key held while a screen closes is not a tap. */
    private void pollToggleKey() {
        boolean screen = mc.currentScreen != null;
        boolean down = physicallyDown(toggleKey.get());
        if (keyPrimed && !screen && !keyWasDown && down) flipSprint();
        keyWasDown = down;
        keyPrimed = true;
    }

    /** The real state of a key or mouse button, whatever screen is open. */
    private static boolean physicallyDown(int code) {
        if (code == GLFW.GLFW_KEY_UNKNOWN) return false;
        try {
            if (KeySetting.isMouse(code))
                return GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), KeySetting.mouseButton(code)) == GLFW.GLFW_PRESS;
            return InputUtil.isKeyPressed(mc.getWindow(), code);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ input hook

    private static ToggleSprint instance() {
        return EvadeClient.modules == null ? null : EvadeClient.modules.get(ToggleSprint.class);
    }

    /** The sprint input for this tick: the real key, or held for you while toggled on. Called by the input mixin. */
    public static boolean sprintInput(boolean keyDown) {
        if (keyDown) return true;
        ToggleSprint t = instance();
        return t != null && mc.player != null && t.holdsSprint();
    }

    /**
     * The sneak input for this tick: the real key, or held for you while toggled on. Called by the input mixin,
     * right before the game uses the input, so a toggled sneak is already let go in the tick you mount or take off.
     */
    public static boolean sneakInput(boolean keyDown) {
        ToggleSprint t = instance();
        if (t != null && t.isEnabled() && t.hooksActive() && mc.player != null) {
            t.applyJoinState();
            t.updateContext();
        }
        if (keyDown) return true;
        return t != null && mc.player != null && t.holdsSneak();
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

    /**
     * One line, by priority: sneak toggled, sneak held, sprint toggled, sprint key held, sprinting the vanilla way
     * (double-tap forward), then idle (only with Show When Idle). A state whose text is blank shows nothing.
     */
    @Override
    protected List<String> lines(boolean preview) {
        if (!showHud.get() && !preview) return new ArrayList<>();
        String text = statusText(preview);
        List<String> out = new ArrayList<>(1);
        if (text != null && !text.isBlank()) out.add(text);
        else if (preview) out.add(sprintToggledText.getDefault()); // every label blanked: still something to place
        return out;
    }

    private String statusText(boolean preview) {
        if (mc.options == null) return preview ? sprintToggledText.get() : null;
        ClientPlayerEntity p = mc.player;
        if (sneakToggled()) return sneakToggledText.get();
        if (p != null && p.isSneaking()) return sneakHeldText.get();
        if (sprintToggled()) return sprintToggledText.get();
        if (mc.options.sprintKey.isPressed()) return sprintHeldText.get();
        if (p != null && p.isSprinting()) return sprintVanillaText.get();
        if (showWhenIdle.get()) return idleText.get();
        // The editor's sample when nothing is active: the line you'll usually see.
        return preview ? sprintToggledText.get() : null;
    }
}
