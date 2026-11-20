package net.evadeclient.module;

public enum Category {
    COMBAT("Combat"),
    MOVEMENT("Movement"),
    VISUALS("Visuals"),
    WORLD("World"),
    PLAYER("Player"),
    HUD("HUD"),
    MODS("Mods"),
    MISC("Misc"),
    /** Hidden from the module grid; shown on the Settings page. */
    CLIENT("Client");

    public final String displayName;

    Category(String displayName) { this.displayName = displayName; }
}
