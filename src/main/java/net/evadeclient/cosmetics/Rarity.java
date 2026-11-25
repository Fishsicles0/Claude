package net.evadeclient.cosmetics;

import java.util.Locale;

/**
 * An item's tier, which sets its colour in the Wardrobe (and on the shop). Regular items are not animated (a cape's
 * sway, wing beats and particle effects don't count); special items are animated (animated textures and the like);
 * partner items come from a partner server.
 */
public enum Rarity {
    REGULAR("Regular", 0xFFC3C7D1),
    SPECIAL("Special", 0xFFFFC23D),
    PARTNER("Partner", 0xFF2EDBC0);

    public final String label;
    public final int color;

    Rarity(String label, int color) {
        this.label = label;
        this.color = color;
    }

    /** A tier by its catalog name. The old names (common, rare, epic, legendary) and anything unknown are regular. */
    public static Rarity parse(String s) {
        if (s == null) return REGULAR;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return REGULAR;
        }
    }
}
