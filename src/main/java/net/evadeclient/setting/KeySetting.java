package net.evadeclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** A key the user picks in the menu (e.g. "hold to zoom"). */
public class KeySetting extends Setting<Integer> {
    public KeySetting(String name, int key) { super(name, key); }

    public boolean isDown() {
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (value == GLFW.GLFW_KEY_UNKNOWN || mc.currentScreen != null) return false;
        return InputUtil.isKeyPressed(mc.getWindow(), value);
    }

    @Override public JsonElement toJson() { return new JsonPrimitive(value); }
    @Override public void fromJson(JsonElement json) { value = json.getAsInt(); }
    @Override public String display() { return keyName(value); }

    public static String keyName(int key) {
        if (key == GLFW.GLFW_KEY_UNKNOWN) return "None";
        return InputUtil.Type.KEYSYM.createFromCode(key).getLocalizedText().getString();
    }
}
