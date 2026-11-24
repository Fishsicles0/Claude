package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/**
 * Counts real mouse clicks per second for the CPS counter and Keystrokes. Every press GLFW reports is counted (fed
 * from MouseMixin's button hook), so clicks shorter than a frame are never missed. Only clicks made in game count:
 * presses while a menu or the loading overlay is open are ignored. Times are on the game's monotonic clock
 * ({@link Util#getMeasuringTimeMs()}), so changing the system clock never adds or drops clicks.
 */
public final class ClickTracker {
    /** Presses are kept this long: the longest CPS sample window. */
    private static final long KEEP_MS = 3000;
    private static final long SECOND_MS = 1000;
    private static final Deque<Long> left = new ArrayDeque<>(), right = new ArrayDeque<>();
    private static boolean leftDown, rightDown;
    /** When the last counted press happened, or {@link Long#MIN_VALUE} if none yet. */
    private static long lastPress = Long.MIN_VALUE;

    private ClickTracker() {}

    /** A mouse button event straight from GLFW ({@code action} is GLFW_PRESS or GLFW_RELEASE). Client thread. */
    public static void onButton(int button, int action) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;
        boolean isLeft = button == GLFW.GLFW_MOUSE_BUTTON_LEFT;
        if (action == GLFW.GLFW_RELEASE) {
            if (isLeft) leftDown = false; else rightDown = false;
            return;
        }
        if (action != GLFW.GLFW_PRESS) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen != null || mc.getOverlay() != null) return;
        press(isLeft);
    }

    /** Counts one press of the left or right button now (the in-game checks are the caller's). */
    public static void press(boolean isLeft) {
        long now = Util.getMeasuringTimeMs();
        prune(now);
        if (isLeft) {
            left.addLast(now);
            leftDown = true;
        } else {
            right.addLast(now);
            rightDown = true;
        }
        lastPress = now;
    }

    /** Once per frame (from the HUD): drop clicks older than the longest window; nothing is held while a menu is open. */
    public static void update() {
        if (MinecraftClient.getInstance().currentScreen != null) {
            leftDown = false;
            rightDown = false;
        }
        prune(Util.getMeasuringTimeMs());
    }

    private static void prune(long now) {
        while (!left.isEmpty() && now - left.peekFirst() > KEEP_MS) left.pollFirst();
        while (!right.isEmpty() && now - right.peekFirst() > KEEP_MS) right.pollFirst();
    }

    /** Presses in {@code clicks} newer than {@code windowMs} before {@code now} (the deque is oldest first). */
    private static int count(Deque<Long> clicks, long now, double windowMs) {
        int n = 0;
        for (Iterator<Long> it = clicks.descendingIterator(); it.hasNext(); ) {
            if (now - it.next() >= windowMs) break;
            n++;
        }
        return n;
    }

    /** Left clicks in the last second (Keystrokes' CPS). */
    public static int leftCps() { return count(left, Util.getMeasuringTimeMs(), SECOND_MS); }

    /** Right clicks in the last second (Keystrokes' CPS). */
    public static int rightCps() { return count(right, Util.getMeasuringTimeMs(), SECOND_MS); }

    /**
     * Clicks per second of one button averaged over the last {@code windowMs} (at most 3000): the presses in that
     * window divided by its length in seconds. A one-second window gives the plain count.
     */
    public static float cps(boolean isLeft, double windowMs) {
        if (!(windowMs > 0)) return 0f;
        double window = Math.min(windowMs, KEEP_MS);
        return (float) (count(isLeft ? left : right, Util.getMeasuringTimeMs(), window) / (window / 1000.0));
    }

    /** When the last press counted ({@link Util#getMeasuringTimeMs()} ms), or {@link Long#MIN_VALUE} if none yet. */
    public static long lastPress() { return lastPress; }

    /** Milliseconds since the last counted press, or {@link Long#MAX_VALUE} if there has been none. */
    public static long msSinceLastPress() {
        return lastPress == Long.MIN_VALUE ? Long.MAX_VALUE : Math.max(0, Util.getMeasuringTimeMs() - lastPress);
    }

    public static boolean leftDown() { return leftDown; }
    public static boolean rightDown() { return rightDown; }

    /** Forget every click and held button (tests). */
    public static void clear() {
        left.clear();
        right.clear();
        leftDown = false;
        rightDown = false;
        lastPress = Long.MIN_VALUE;
    }
}
