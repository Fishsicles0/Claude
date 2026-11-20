package net.evadeclient.module.client;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import org.lwjgl.glfw.GLFW;

/** Client-wide options, shown on the menu's Settings page. */
public class ClientSettings extends Module {
    public final KeySetting menuKey = add(new KeySetting("Menu Key", GLFW.GLFW_KEY_RIGHT_SHIFT));
    public final net.evadeclient.setting.ModeSetting guiStyle =
            add(new net.evadeclient.setting.ModeSetting("GUI Style", "Cards", "Cards", "Compact", "Doomsday"));
    public final BooleanSetting blur = add(new BooleanSetting("Blur Background", true));
    public final BooleanSetting animations = add(new BooleanSetting("Animations", true));
    public final BooleanSetting toggleMessages = add(new BooleanSetting("Keybind Messages", true));

    public ClientSettings() {
        super("Client Settings", "Menu key, blur, animations.", Category.CLIENT);
        setVisible(false);
        setEnabled(true);
    }
}
