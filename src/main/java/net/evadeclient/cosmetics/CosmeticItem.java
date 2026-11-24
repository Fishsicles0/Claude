package net.evadeclient.cosmetics;

import com.google.gson.JsonObject;

import java.util.Map;

/**
 * One item in the catalog. {@code files} maps each of its files (item.json, model.json, texture.png, thumb.png) to
 * the sha256 the catalog lists; nothing is used until its bytes match. {@code base} is where its files download from
 * (null for the bundled starter set, which is read from the jar).
 */
public final class CosmeticItem {
    public final String id, name, description;
    public final Slot slot;
    public final Rarity rarity;
    public final boolean colourable;
    /** RGB, or -1 when the item has no default colour. */
    public final int defaultColor;
    /** A partner's name for partner items (shown in the Wardrobe), or null. */
    public final String partner;
    public final int priceCents;
    public final String currency;
    public final String base;
    public final Map<String, String> files;
    public final boolean bundled;
    /** Particle settings (aura, trail, kill and win effects), from item.json; null until loaded or for none. */
    volatile JsonObject fx;
    /** Emote keyframes, from item.json; null until loaded or for none. */
    volatile Emotes.Animation emote;

    CosmeticItem(String id, String name, String description, Slot slot, Rarity rarity, boolean colourable, int defaultColor,
                 String partner, int priceCents, String currency, String base, Map<String, String> files, boolean bundled) {
        this.id = id;
        this.name = name;
        this.description = description == null ? "" : description;
        this.slot = slot;
        this.rarity = rarity;
        this.colourable = colourable;
        this.defaultColor = defaultColor;
        this.partner = partner;
        this.priceCents = priceCents;
        this.currency = currency == null ? "USD" : currency;
        this.base = base;
        this.files = Map.copyOf(files);
        this.bundled = bundled;
    }

    public JsonObject fx() { return fx; }
    public Emotes.Animation emote() { return emote; }
    public boolean hasModel() { return files.containsKey("model.json"); }

    /** The colour to draw it in: the wearer's pick for colourable items, else its default (-1: none). */
    public int colorFor(Loadout.Worn worn) {
        if (colourable && worn != null && worn.color() >= 0) return worn.color();
        return defaultColor;
    }

    @Override public String toString() { return id; }
}
