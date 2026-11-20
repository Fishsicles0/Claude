package net.evadeclient.module;

public enum Category {
    COMBAT("Combat"),
    VISUALS("Visuals"),
    HUD("HUD"),
    MODS("Mods"),
    /** Hidden from the module grid; shown on the Settings page. */
    CLIENT("Client");

    public final String displayName;

    Category(String displayName) { this.displayName = displayName; }
}
