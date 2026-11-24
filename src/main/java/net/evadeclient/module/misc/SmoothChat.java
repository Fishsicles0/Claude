package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.gui.Motion;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.NumberSetting;

/**
 * Chat that moves: new lines slide up into place instead of appearing at once, the chat box can stay open after you
 * send a message, and the chat and input backgrounds take their own opacity. Display only.
 *
 * <p>Hooks: ChatHudV25Mixin (slide and background), ChatScreenV25Mixin (keep open, input background).
 */
public class SmoothChat extends Module {
    /** At most this many lines of slide at once, so a burst of messages doesn't fly in from below the screen. */
    private static final float MAX_LINES = 3f;

    private final BooleanSetting slide = add(new BooleanSetting("Slide In", true)
            .describe("New lines slide up into place instead of appearing at once."));
    private final NumberSetting slideTime = add(new NumberSetting("Slide Time", 180, 60, 500, 20)
            .describe("How long the slide takes, in milliseconds."));
    private final BooleanSetting keepOpen = add(new BooleanSetting("Keep Open After Sending", false)
            .describe("Pressing Enter sends your message and leaves the chat box open for the next one. Enter on an empty box closes it."));
    private final NumberSetting background = add(new NumberSetting("Background Opacity %", 50, 0, 100, 5)
            .describe("How dark the background behind chat lines is. 0 hides it."));
    private final NumberSetting input = add(new NumberSetting("Input Opacity %", 50, 0, 100, 5)
            .describe("How dark the box you type in is. 0 hides it."));

    /** Lines of slide left when the current slide started, and when that was. */
    private float startOffset;
    private long startAt;

    public SmoothChat() {
        super("Smooth Chat", "New chat lines slide in, chat can stay open after sending, and chat backgrounds fade.", Category.MISC);
    }

    public static SmoothChat active() {
        if (EvadeClient.modules == null) return null;
        SmoothChat s = EvadeClient.modules.get(SmoothChat.class);
        return s != null && s.isEnabled() ? s : null;
    }

    /** From ChatLines when a new message took {@code lines} screen lines at the bottom of chat. */
    public static void linesAdded(int lines) {
        SmoothChat s = active();
        if (s == null || !s.slide.get() || lines <= 0) return;
        long now = System.currentTimeMillis();
        s.startOffset = Math.min(MAX_LINES, s.offsetLines(now) + lines);
        s.startAt = now;
    }

    /** How many lines below their place chat lines are drawn at {@code now} (0 when settled). */
    private float offsetLines(long now) {
        float d = slideTime.getFloat();
        float t = d <= 0 ? 1f : (now - startAt) / d;
        if (t >= 1f || startOffset <= 0f) return 0f;
        return startOffset * (1f - Motion.cubicOut(Motion.clamp01(t)));
    }

    /** Pixels (before chat's own scale) to move chat down this frame; 0 when settled or off. */
    public static float slideOffset(double lineHeight) {
        SmoothChat s = active();
        if (s == null || !s.slide.get()) return 0f;
        return (float) (s.offsetLines(System.currentTimeMillis()) * lineHeight);
    }

    /** The chat line background opacity (0..1) to use instead of vanilla's {@code original}. */
    public static float backgroundOpacity(float original) {
        SmoothChat s = active();
        return s == null ? original : s.background.getFloat() / 100f;
    }

    /** The colour of the box you type in, instead of vanilla's {@code original}. */
    public static int inputColor(int original) {
        SmoothChat s = active();
        if (s == null) return original;
        int a = Math.round(s.input.getFloat() / 100f * 255f);
        return (a << 24) | (original & 0xFFFFFF);
    }

    /** Whether Enter leaves the chat box open after sending. */
    public static boolean keepsOpen() {
        SmoothChat s = active();
        return s != null && s.keepOpen.get();
    }

    /** Test hook: the slide still to go, in lines. */
    public float offsetForTest() { return offsetLines(System.currentTimeMillis()); }
}
