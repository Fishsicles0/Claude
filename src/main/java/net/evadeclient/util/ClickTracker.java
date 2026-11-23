package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Counts real mouse clicks per second for the CPS counter and Keystrokes. Every press GLFW reports is counted (fed
 * from MouseMixin's button hook), so clicks shorter than a frame are never missed. Only clicks made in game count:
 * presses while a menu or the loading overlay is open are ignored.
 */
public final class ClickTracker {
    private static final long WINDOW_MS = 1000;
    private static final Deque<Long> left = new ArrayDeque<>(), right = new ArrayDeque<>();
    private static boolean leftDown, rightDown;

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
        long now = System.currentTimeMillis();
        prune(now);
        if (isLeft) {
            left.addLast(now);
            leftDown = true;
        } else {
            right.addLast(now);
            rightDown = true;
        }
    }

    /** Once per frame (from the HUD): drop clicks older than a second; nothing is held while a menu is open. */
    public static void update() {
        if (MinecraftClient.getInstance().currentScreen != null) {
            leftDown = false;
            rightDown = false;
        }
        prune(System.currentTimeMillis());
    }

    private static void prune(long now) {
        while (!left.isEmpty() && now - left.peekFirst() > WINDOW_MS) left.pollFirst();
        while (!right.isEmpty() && now - right.peekFirst() > WINDOW_MS) right.pollFirst();
    }

    public static int leftCps() { return left.size(); }
    public static int rightCps() { return right.size(); }
    public static boolean leftDown() { return leftDown; }
    public static boolean rightDown() { return rightDown; }
}
