package net.evadeclient.module;

public enum Category {
    SERVER("Server-facing"),
    VISUALS("Visuals"),
    HUD("HUD"),
    MODS("Controls"),
    MISC("Misc"),
    /** Hidden from the module grid; shown on the Settings page. */
    CLIENT("Client");

    public final String displayName;

    Category(String displayName) { this.displayName = displayName; }
}
