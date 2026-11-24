package net.evadeclient.cosmetics;

import java.util.Locale;

/**
 * Where a cosmetic goes. Model slots are drawn on the player model; particle slots spawn client-side particles; the
 * effect slots play only for things you did yourself. There is deliberately no nametag slot.
 */
public enum Slot {
    CAPE("Cape", Kind.MODEL),
    WINGS("Wings", Kind.MODEL),
    HAT("Hat", Kind.MODEL),
    MASK("Mask", Kind.MODEL),
    BACK("Back", Kind.MODEL),
    AURA("Aura", Kind.MODEL),
    TRAIL("Trail", Kind.PARTICLES),
    PET("Pet", Kind.MODEL),
    EMOTE("Emote", Kind.EMOTE),
    KILL_EFFECT("Kill Effect", Kind.EFFECT),
    WIN_EFFECT("Win Effect", Kind.EFFECT),
    ITEM_SKIN("Item Skin", Kind.ITEM);

    public enum Kind { MODEL, PARTICLES, EMOTE, EFFECT, ITEM }

    public final String label;
    public final Kind kind;

    Slot(String label, Kind kind) {
        this.label = label;
        this.kind = kind;
    }

    /** A slot by its wire name ("CAPE", "kill_effect"), or null. */
    public static Slot parse(String s) {
        if (s == null) return null;
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
