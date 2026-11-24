package net.evadeclient.util;

import net.evadeclient.EvadeClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

/**
 * Borderless fullscreen (windowed fullscreen): the normal game window, without its title bar and frame, stretched over
 * the monitor it's on. Alt-Tab and a second monitor work instantly, unlike the game's exclusive fullscreen, and the
 * desktop's own refresh rate and VSync behaviour apply. Done with plain GLFW window attributes, so the game's
 * framebuffer simply follows the new window size.
 *
 * <p>While the game's own fullscreen (F11) is on, borderless waits and takes over again once it's off. The window's
 * size and position before borderless are put back when it's turned off. Render thread only.
 */
public final class Borderless {
    private Borderless() {}

    private static boolean applied;
    /** The window before borderless: x, y, width, height. */
    private static int[] saved;

    public static boolean applied() { return applied; }

    /** Called every client tick with whether borderless is wanted. */
    public static void follow(boolean want) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Window w = mc.getWindow();
        if (w == null) return;
        if (w.isFullscreen()) return; // the game's fullscreen is in charge: wait
        if (want == applied) {
            // Something (a resize, the OS) gave the window its frame back: put borderless back.
            if (applied && GLFW.glfwGetWindowAttrib(w.getHandle(), GLFW.GLFW_DECORATED) == GLFW.GLFW_TRUE) applied = false;
            else return;
        }
        try {
            if (want) enter(w.getHandle());
            else leave(w.getHandle());
        } catch (Throwable t) {
            EvadeClient.LOG.warn("Borderless fullscreen: couldn't {}", want ? "switch on" : "switch off", t);
            applied = want; // don't retry every tick
        }
    }

    private static void enter(long handle) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.mallocInt(1), y = stack.mallocInt(1), ww = stack.mallocInt(1), wh = stack.mallocInt(1);
            GLFW.glfwGetWindowPos(handle, x, y);
            GLFW.glfwGetWindowSize(handle, ww, wh);
            if (saved == null) saved = new int[]{x.get(0), y.get(0), ww.get(0), wh.get(0)};
            long monitor = monitorFor(handle, x.get(0) + ww.get(0) / 2, y.get(0) + wh.get(0) / 2);
            if (monitor == 0) return;
            GLFWVidMode mode = GLFW.glfwGetVideoMode(monitor);
            if (mode == null) return;
            IntBuffer mx = stack.mallocInt(1), my = stack.mallocInt(1);
            GLFW.glfwGetMonitorPos(monitor, mx, my);
            GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
            GLFW.glfwSetWindowMonitor(handle, 0, mx.get(0), my.get(0), mode.width(), mode.height(), GLFW.GLFW_DONT_CARE);
        }
        applied = true;
    }

    private static void leave(long handle) {
        GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
        if (saved != null) {
            GLFW.glfwSetWindowMonitor(handle, 0, saved[0], saved[1], Math.max(320, saved[2]), Math.max(240, saved[3]), GLFW.GLFW_DONT_CARE);
            saved = null;
        }
        applied = false;
    }

    /** The monitor holding the point (window centre), else the primary one. */
    private static long monitorFor(long handle, int cx, int cy) {
        PointerBuffer monitors = GLFW.glfwGetMonitors();
        if (monitors != null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer mx = stack.mallocInt(1), my = stack.mallocInt(1);
                for (int i = 0; i < monitors.limit(); i++) {
                    long m = monitors.get(i);
                    GLFWVidMode mode = GLFW.glfwGetVideoMode(m);
                    if (mode == null) continue;
                    GLFW.glfwGetMonitorPos(m, mx, my);
                    if (cx >= mx.get(0) && cx < mx.get(0) + mode.width() && cy >= my.get(0) && cy < my.get(0) + mode.height()) return m;
                }
            }
        }
        return GLFW.glfwGetPrimaryMonitor();
    }
}
