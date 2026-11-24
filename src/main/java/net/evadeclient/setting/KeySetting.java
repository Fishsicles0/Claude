package net.evadeclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * A key the user picks in the menu (e.g. "hold to zoom"). Codes are GLFW key codes; codes of -100 and below are
 * mouse buttons ({@code -100 - button}, middle and side buttons only), allowed where {@link #allowMouse} is set.
 */
public class KeySetting extends Setting<Integer> {
    /** Mouse button {@code b} is stored as {@code MOUSE_BASE - b}. */
    public static final int MOUSE_BASE = -100;
    /** Lowest and highest mouse button that can be bound: middle (2) up to GLFW's last button (7). Left/right never. */
    public static final int FIRST_MOUSE_BUTTON = GLFW.GLFW_MOUSE_BUTTON_MIDDLE, LAST_MOUSE_BUTTON = GLFW.GLFW_MOUSE_BUTTON_LAST;

    /** Whether a mouse button may be bound here (true only for hold keys such as Zoom Key and Freelook Key). */
    public final boolean allowMouse;

    public KeySetting(String name, int key) { this(name, key, false); }

    public KeySetting(String name, int key, boolean allowMouse) {
        super(name, key);
        this.allowMouse = allowMouse;
    }

    public boolean isDown() {
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (value == GLFW.GLFW_KEY_UNKNOWN || mc.currentScreen != null) return false;
        if (isMouse(value)) return GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), mouseButton(value)) == GLFW.GLFW_PRESS;
        return InputUtil.isKeyPressed(mc.getWindow(), value);
    }

    /** Whether {@code code} is a value this setting can hold. */
    public boolean accepts(int code) {
        return isValidKey(code) || (allowMouse && isValidMouse(code));
    }

    @Override public JsonElement toJson() { return new JsonPrimitive(value); }

    /** Configs and share codes can hold anything: keep only real keys (and mouse buttons where allowed). */
    @Override
    public void fromJson(JsonElement json) {
        int code;
        try { code = json.getAsInt(); } catch (RuntimeException e) { reset(); return; }
        if (accepts(code)) value = code; else reset();
    }

    @Override public String display() { return keyName(value); }

    /** The stored code for mouse button {@code button}. */
    public static int mouseCode(int button) { return MOUSE_BASE - button; }

    /** The GLFW mouse button of a stored mouse code. */
    public static int mouseButton(int code) { return MOUSE_BASE - code; }

    public static boolean isMouse(int code) { return code <= MOUSE_BASE; }

    /** "None" or a real keyboard key. */
    public static boolean isValidKey(int code) {
        return code == GLFW.GLFW_KEY_UNKNOWN || (code >= GLFW.GLFW_KEY_SPACE && code <= GLFW.GLFW_KEY_LAST);
    }

    /** A bindable mouse button code (middle or side buttons). */
    public static boolean isValidMouse(int code) {
        return code <= mouseCode(FIRST_MOUSE_BUTTON) && code >= mouseCode(LAST_MOUSE_BUTTON);
    }

    public static String keyName(int key) {
        if (key == GLFW.GLFW_KEY_UNKNOWN) return "None";
        if (isMouse(key)) return InputUtil.Type.MOUSE.createFromCode(mouseButton(key)).getLocalizedText().getString();
        return InputUtil.Type.KEYSYM.createFromCode(key).getLocalizedText().getString();
    }
}
