package net.evadeclient.addon;

import net.evadeclient.module.Category;

/** Where an addon module is listed in the Evade menu. HUD widgets are always listed under HUD. */
public enum AddonCategory {
    /** Visual changes (Visuals page). */
    VISUALS(Category.VISUALS),
    /** Controls and camera (Controls page). */
    CONTROLS(Category.MODS),
    /** Chat, interface and convenience (Misc page). */
    MISC(Category.MISC),
    /** Anything that sends something to the server you're on (Server-facing page; these start off). */
    SERVER_FACING(Category.SERVER);

    final Category category;

    AddonCategory(Category category) { this.category = category; }
}
