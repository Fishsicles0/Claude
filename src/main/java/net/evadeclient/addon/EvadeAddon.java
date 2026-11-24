package net.evadeclient.addon;

/**
 * The entrypoint of an Evade addon. List the implementing class under {@code "evade"} in your mod's
 * {@code fabric.mod.json}; Evade calls {@link #register} once, at startup, before it loads its config (so saved
 * settings and positions of your modules are applied to them).
 *
 * <pre>{@code
 * public final class MyEvadeAddon implements EvadeAddon {
 *     public void register(AddonRegistry registry) {
 *         registry.module(new MyModule());
 *         registry.hud(new MyWidget());
 *     }
 * }
 * }</pre>
 */
public interface EvadeAddon {
    /** The version of this API. 1: modules, HUD widgets, text widgets and the six setting types. */
    int API_VERSION = 1;

    /** Add your modules and widgets. Runs on the client thread during Evade's startup; keep it quick. */
    void register(AddonRegistry registry);
}
