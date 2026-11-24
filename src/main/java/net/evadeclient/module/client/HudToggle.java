package net.evadeclient.module.client;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.util.Notifications;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.util.function.Consumer;

/**
 * Hides only Evade's HUD (its widgets and world labels; the game's own HUD, chat and the crosshair stay) with a key,
 * and can keep Evade's HUD out of F2 screenshots: the screenshot is then taken one frame later, from a frame drawn
 * without it. Nothing is sent anywhere; it only changes what Evade draws.
 */
public class HudToggle extends Module {
    public final KeySetting toggleKey = add(new KeySetting("Toggle Key", GLFW.GLFW_KEY_UNKNOWN)
            .describe("Press to hide or show Evade's HUD widgets. The game's own HUD stays; F1 still hides everything."));
    public final BooleanSetting hideInScreenshots = add(new BooleanSetting("Hide In Screenshots", false)
            .describe("F2 screenshots are taken without Evade's widgets (the game's own HUD is kept). They vanish for one frame while the shot is taken."));
    public final BooleanSetting announce = add(new BooleanSetting("Show Toast", true)
            .describe("A short note when the toggle key hides or shows the HUD."));

    /** Hidden with the toggle key (this session only). */
    private boolean hidden;
    private boolean keyWasDown;

    /** A screenshot waiting for a frame drawn without Evade's HUD. */
    private static File shotDir;
    private static Consumer<Text> shotReceiver;
    private static boolean shotPending;
    /** Frames drawn while a screenshot waited (tests). */
    private static int shotFramesWithoutHud;

    public HudToggle() {
        super("HUD Toggle", "A key that hides only Evade's HUD widgets, and an option to keep them out of screenshots.", Category.MODS);
        setEnabled(true);
    }

    private static HudToggle get() {
        return EvadeClient.modules == null ? null : EvadeClient.modules.get(HudToggle.class);
    }

    /** Whether Evade's HUD is hidden on this frame: by the toggle key, or for a screenshot being taken. */
    public static boolean hiddenNow() {
        if (shotPending) return true;
        HudToggle t = get();
        return t != null && t.isEnabled() && t.hidden;
    }

    /** Whether the toggle key has hidden the HUD. */
    public boolean isHidden() { return hidden; }

    public void setHidden(boolean hide) {
        if (hide == hidden) return;
        hidden = hide;
        if (announce.get()) {
            String key = toggleKey.get() == GLFW.GLFW_KEY_UNKNOWN ? "the toggle key" : KeySetting.keyName(toggleKey.get());
            Notifications.push("Evade HUD", hide ? "Hidden. Press " + key + " to show it." : "Shown", false);
        }
    }

    @Override
    public void onTick() {
        boolean down = toggleKey.isDown();
        if (down && !keyWasDown) setHidden(!hidden);
        keyWasDown = down;
    }

    @Override
    protected void onDisable() {
        hidden = false;
    }

    // ------------------------------------------------------------------ screenshots (Keyboard mixin, frame end)

    /**
     * From F2: with Hide In Screenshots on (and Evade's HUD showing), hold the screenshot for one frame drawn without
     * Evade's HUD and return true; otherwise false, and the game takes it as usual.
     */
    public static boolean deferScreenshot(File dir, Consumer<Text> receiver) {
        HudToggle t = get();
        if (t == null || !t.isEnabled() || !t.hideInScreenshots.get() || t.hidden || shotPending) return false;
        shotDir = dir;
        shotReceiver = receiver;
        shotPending = true;
        shotFramesWithoutHud = 0;
        return true;
    }

    /** End of every frame: a held screenshot is taken now, from the frame just drawn without Evade's HUD. */
    public static void onFrameEnd(Framebuffer framebuffer) {
        if (!shotPending) return;
        shotPending = false;
        shotFramesWithoutHud++;
        try {
            ScreenshotRecorder.saveScreenshot(shotDir, framebuffer, shotReceiver);
        } catch (RuntimeException e) {
            EvadeClient.LOG.warn("HUD Toggle: couldn't take the screenshot", e);
        } finally {
            shotDir = null;
            shotReceiver = null;
        }
    }

    /** Test hook: whether a screenshot is waiting, and how many were taken from a frame without Evade's HUD. */
    public static boolean shotPendingForTest() { return shotPending; }

    public static int shotsWithoutHudForTest() { return shotFramesWithoutHud; }
}
