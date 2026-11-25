package net.evadeclient.cosmetics;

/**
 * What one player's render state carries for the cosmetics layer this frame (Fabric render-state data): their loadout,
 * how faded they are near the distance limit, which slots are held back (a helmet hides hats and masks, an elytra hides
 * capes, wings and back items), and your emote.
 */
public final class RenderData {
    public Loadout loadout = Loadout.EMPTY;
    public float alpha = 1f;
    public boolean self;
    /** Bit per {@link Slot#ordinal()} that is not drawn this frame. */
    public int hidden;
    public Emotes.Playing emote;
    /** Seconds for animations (bob, spin, wing beat). */
    public float time;

    public boolean shows(Slot s) { return (hidden & (1 << s.ordinal())) == 0 && loadout.get(s) != null; }

    public RenderData hide(Slot s) { hidden |= 1 << s.ordinal(); return this; }
}
