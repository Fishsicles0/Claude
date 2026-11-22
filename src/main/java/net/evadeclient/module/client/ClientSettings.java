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
            add(new net.evadeclient.setting.ModeSetting("GUI Style", "List", "List", "Cards", "Compact"));
    public final net.evadeclient.setting.ModeSetting theme =
            add(new net.evadeclient.setting.ModeSetting("Theme", "Modern", net.evadeclient.util.Theme.PRESETS));
    public final net.evadeclient.setting.ModeSetting accent =
            add(new net.evadeclient.setting.ModeSetting("Accent Color", "Red", net.evadeclient.util.Theme.ACCENTS));
    public final BooleanSetting smoothFont = add(new BooleanSetting("Smooth Font", true));
    public final BooleanSetting hudGrid = add(new BooleanSetting("HUD Editor Grid", false));
    public final BooleanSetting blur = add(new BooleanSetting("Blur Background", true));
    public final BooleanSetting animations = add(new BooleanSetting("Animations", true));
    public final BooleanSetting toggleMessages = add(new BooleanSetting("Keybind Messages", true));

    public ClientSettings() {
        super("Client Settings", "Menu key, theme, accent colour, blur, animations.", Category.CLIENT);
        setVisible(false);
        setEnabled(true);
    }

    /** Called every tick and by the menu each frame so a new theme shows up immediately. */
    public void applyTheme() {
        net.evadeclient.util.Theme.apply(theme.get(), accent.get());
    }

    @Override
    public void onTick() { applyTheme(); }
}
