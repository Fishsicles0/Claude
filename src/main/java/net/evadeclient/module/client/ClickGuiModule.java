package net.evadeclient.module.client;

import net.evadeclient.gui.ClickGuiScreen;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import org.lwjgl.glfw.GLFW;

/** Opens the ClickGUI. Acts as a button rather than a persistent toggle. */
public class ClickGuiModule extends Module {
    public ClickGuiModule() {
        super("ClickGUI", "Opens the module menu.", Category.CLIENT, GLFW.GLFW_KEY_RIGHT_SHIFT);
        setVisible(false);
    }

    @Override
    protected boolean alwaysRunHooks() { return true; }

    @Override
    protected void onEnable() {
        mc.setScreen(ClickGuiScreen.get());
        setEnabled(false);
    }
}
