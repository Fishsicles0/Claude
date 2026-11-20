package net.evadeclient.util;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.Deque;

/** Counts real mouse clicks per second by polling the mouse each frame. */
public final class ClickTracker {
    private static final Deque<Long> left = new ArrayDeque<>(), right = new ArrayDeque<>();
    private static boolean leftDown, rightDown;

    private ClickTracker() {}

    public static void update() {
        MinecraftClient mc = MinecraftClient.getInstance();
        long window = mc.getWindow().getHandle();
        long now = System.currentTimeMillis();
        boolean l = mc.currentScreen == null && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean r = mc.currentScreen == null && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        if (l && !leftDown) left.add(now);
        if (r && !rightDown) right.add(now);
        leftDown = l;
        rightDown = r;
        while (!left.isEmpty() && now - left.peekFirst() > 1000) left.pollFirst();
        while (!right.isEmpty() && now - right.peekFirst() > 1000) right.pollFirst();
    }

    /** Record a click that didn't come from the physical mouse (e.g. AutoClicker). */
    public static void recordLeft() { left.add(System.currentTimeMillis()); }

    public static int leftCps() { return left.size(); }
    public static int rightCps() { return right.size(); }
    public static boolean leftDown() { return leftDown; }
    public static boolean rightDown() { return rightDown; }
}
