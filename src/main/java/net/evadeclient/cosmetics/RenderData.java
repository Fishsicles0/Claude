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

    // ---- motion state, kept per player between frames by CosmeticMotion (false in the Wardrobe: time-based there)
    public boolean motion;
    long lastNanos;
    /** Cape spring: current lift, forward swing and side swing (degrees, vanilla's three cape values) and their speeds. */
    public float capeLift, capeSwing, capeSide;
    float capeLiftV, capeSwingV, capeSideV;
    /** Wing beat clock (beats at speed 1), current beat-rate and amplitude multipliers, fold 0..1 (sneaking). */
    public float wingClock, wingRate = 1f, wingAmp = 1f, fold;
    /**
     * Pet: how far it trails your head's turn (degrees, clamped small so it never swings into your head), its eased
     * look (degrees, relative to your body), and how fast it is being turned (degrees per second, for a little lean).
     */
    public float petLag, petLookYaw, petLookPitch, petYawV;
    float petYaw;
    /** Pet, set per frame from the posed model: your head's turn (radians) and how far your shoulders dropped (sneaking). */
    public float petHeadYaw, petDrop;
    boolean petInit;
    /** A chestplate (or other body armour model) is worn: back items sit a little further out. */
    public boolean chestArmour;

    public boolean shows(Slot s) { return (hidden & (1 << s.ordinal())) == 0 && loadout.get(s) != null; }

    public RenderData hide(Slot s) { hidden |= 1 << s.ordinal(); return this; }
}
