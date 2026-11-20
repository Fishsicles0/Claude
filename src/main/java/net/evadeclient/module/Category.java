package net.evadeclient.module;

public enum Category {
    COMBAT("Combat"),
    RENDER("Render"),
    CLIENT("Client");

    public final String displayName;

    Category(String displayName) { this.displayName = displayName; }
}
