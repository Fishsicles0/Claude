package net.evadeclient.module.mods;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.render.RenderContext;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.util.Notifications;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Hold a key to see yourself from the front (the game's own third-person front view) and let go to get your view
 * back. Only the game's perspective option changes, exactly as pressing F5 would: the player is never turned, and the
 * camera is the vanilla one, so what you can see is what F5 already shows.
 *
 * <p>Like Freelook, the old view is only put back if it is still the one Snaplook switched to: if you change the view
 * yourself (F5) or another mod does while the key is held, yours is left alone.
 */
public class Snaplook extends Module {
    private final KeySetting key = add(new KeySetting("Snaplook Key", GLFW.GLFW_KEY_UNKNOWN, true)
            .describe("Hold this key to look at yourself from the front. Pick a key or a side mouse button."));
    private final ModeSetting mode = add(new ModeSetting("Mode", "Hold", "Hold", "Toggle")
            .describe("Hold the key to see yourself, or tap it to switch and tap again to switch back."));
    private final ModeSetting view = add(new ModeSetting("View", "Front", "Front", "Back")
            .describe("Front: third person looking at your face. Back: third person behind you."));
    private final BooleanSetting endInScreens = add(new BooleanSetting("End In Menus", true)
            .describe("Go back to your own view when a menu, chat or inventory opens."));

    /** Whether Snaplook has switched the view and not yet put it back. */
    private boolean active;
    private boolean toggled, keyWasDown;
    private Perspective saved, applied;
    /** The last time "no key set" was shown, so turning the module on and off doesn't spam it. */
    private long warnedAt;

    public Snaplook() {
        super("Snaplook", "Hold a key to see yourself from the front. Let go to look back.", Category.MODS);
    }

    @Override
    protected void onEnable() {
        toggled = false;
        keyWasDown = physicallyDown();
        if (key.get() == GLFW.GLFW_KEY_UNKNOWN && System.currentTimeMillis() - warnedAt > 10_000) {
            warnedAt = System.currentTimeMillis();
            Notifications.push(getName(), "Set a Snaplook Key in its settings", false);
        }
    }

    @Override
    protected void onDisable() {
        end();
        toggled = false;
        keyWasDown = false;
    }

    @Override
    public void onTick() { update(); }

    /** Polled every frame too, so the view snaps the moment the key goes down or up. */
    @Override
    public void onRender(RenderContext ctx) { update(); }

    private void update() {
        if (mc.player == null || mc.options == null) {
            end();
            return;
        }
        boolean noScreen = mc.currentScreen == null;
        boolean down = physicallyDown();
        // Only a press in game starts it: typing the key in chat or a menu never does.
        boolean pressed = down && !keyWasDown && noScreen;
        keyWasDown = down;
        boolean want;
        if (mode.is("Toggle")) {
            if (pressed) toggled = !toggled;
            if (!noScreen && endInScreens.get()) toggled = false;
            want = toggled;
        } else {
            want = down && (active ? noScreen || !endInScreens.get() : pressed);
        }
        if (want && !active) start();
        else if (!want && active) end();
        // The player pressed F5 (or something else changed the view) meanwhile: it's theirs now, forget ours.
        if (active && mc.options.getPerspective() != applied) {
            active = false;
            toggled = false;
        }
    }

    private void start() {
        Perspective want = view.is("Back") ? Perspective.THIRD_PERSON_BACK : Perspective.THIRD_PERSON_FRONT;
        saved = mc.options.getPerspective();
        if (saved == want) return; // already there: nothing to switch or put back
        mc.options.setPerspective(want);
        applied = want;
        active = true;
    }

    /** Puts the view back, unless it was changed by someone else since we set it. */
    private void end() {
        if (active && mc.options != null && saved != null && mc.options.getPerspective() == applied) {
            mc.options.setPerspective(saved);
        }
        active = false;
    }

    /** Whether the key is physically held, menus open or not. */
    private boolean physicallyDown() {
        int code = key.get();
        if (code == GLFW.GLFW_KEY_UNKNOWN || mc.getWindow() == null) return false;
        if (KeySetting.isMouse(code))
            return GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), KeySetting.mouseButton(code)) == GLFW.GLFW_PRESS;
        return InputUtil.isKeyPressed(mc.getWindow(), code);
    }

    /** Whether Snaplook is showing its view right now (for tests). */
    public boolean isSnapped() { return active; }
}
