package net.evadeclient.cosmetics;

import java.util.Locale;

/** How rare an item is: sets its colour in the Wardrobe. */
public enum Rarity {
    COMMON("Common", 0xFFB8B8C0),
    RARE("Rare", 0xFF3D9BFF),
    EPIC("Epic", 0xFFB45CFF),
    LEGENDARY("Legendary", 0xFFFFB020);

    public final String label;
    public final int color;

    Rarity(String label, int color) {
        this.label = label;
        this.color = color;
    }

    public static Rarity parse(String s) {
        if (s == null) return COMMON;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return COMMON;
        }
    }
}
