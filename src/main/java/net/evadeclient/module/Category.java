package net.evadeclient.module;

public enum Category {
    COMBAT("Combat"),
    PVP("PvP macros"),
    MOVEMENT("Movement"),
    SERVER("Server-facing"),
    VISUALS("Visuals"),
    WORLD("World"),
    UTILITY("Utility"),
    HUD("HUD"),
    MODS("Controls"),
    MISC("Misc"),
    /** Hidden from the module grid; shown on the Settings page. */
    CLIENT("Client");

    public final String displayName;

    Category(String displayName) { this.displayName = displayName; }
}
