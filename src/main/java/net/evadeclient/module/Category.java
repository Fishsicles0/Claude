package net.evadeclient.module;

public enum Category {
    COMBAT("Combat"),
    MOVEMENT("Movement"),
    RENDER("Render"),
    PLAYER("Player"),
    CLIENT("Client");

    public final String displayName;

    Category(String displayName) { this.displayName = displayName; }
}
