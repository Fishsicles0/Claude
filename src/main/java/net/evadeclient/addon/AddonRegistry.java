package net.evadeclient.addon;

/**
 * Handed to {@link EvadeAddon#register}: adds your modules and widgets to Evade. Only usable during that call.
 * A name that clashes with a built-in module or another addon's is refused (logged, the rest still load).
 */
public interface AddonRegistry {
    /** Adds a module to the menu. */
    void module(AddonModule module);

    /** Adds a HUD widget to the menu's HUD page and the HUD editor. */
    void hud(AddonHudElement widget);

    /** Adds a text HUD widget to the menu's HUD page and the HUD editor. */
    void hud(AddonTextHud widget);

    /** Your mod's id, as in its fabric.mod.json. */
    String modId();

    /** The Evade version running, e.g. "2.4.0". */
    String evadeVersion();
}
