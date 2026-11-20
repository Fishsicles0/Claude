package net.evadeclient.module.mods;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;

/** Removes the camera shake when you take damage. */
public class NoHurtCam extends Module {
    public NoHurtCam() {
        super("No Hurt Cam", "Removes the screen shake when hit.", Category.MODS);
        setEnabled(true);
    }
}
