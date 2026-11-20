package net.evadeclient.module.movement;

import net.evadeclient.module.Category;
import net.evadeclient.module.Module;

/** Stops you walking off the edge of blocks, like sneaking but at full speed. */
public class SafeWalk extends Module {
    public SafeWalk() { super("SafeWalk", "Can't walk off edges.", Category.MOVEMENT); }
}
