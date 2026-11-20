package net.evadeclient.module.visuals;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;

/** Removes distance fog so you can see to the edge of your render distance. */
public class NoFog extends Module {
    public NoFog() { super("NoFog", "Removes distance fog.", Category.VISUALS); }
}
