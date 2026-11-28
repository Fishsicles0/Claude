package net.evadeclient.gui.menu;

import net.evadeclient.module.Category;

/** The pages of the Evade menu: the module lists (all, one category, pinned) and the other hub pages. */
public enum MenuPage {
    ALL("All mods", null), MODS("Controls", Category.MODS), SERVER("Server-facing", Category.SERVER),
    MISC("Misc", Category.MISC), HUD("HUD", Category.HUD), VISUALS("Visuals", Category.VISUALS),
    PROFILES("Profiles", null), FRIENDS("Friends", null), SETTINGS("Settings", Category.CLIENT), PINNED("Pinned", null);

    public final String label;
    public final Category category;

    MenuPage(String label, Category category) { this.label = label; this.category = category; }

    /** Whether this page is a module list (searchable, filterable). */
    public boolean isList() { return this != PROFILES && this != FRIENDS && this != SETTINGS; }
}
