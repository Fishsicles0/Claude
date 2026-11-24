/**
 * The <b>Evade Addon API</b>: lets other client-side Fabric mods add their own modules, settings and HUD widgets to
 * Evade's menu and HUD editor.
 *
 * <p>Not to be confused with {@code net.evadeclient.api}, which is the <i>Server API</i> (plugin messages a Minecraft
 * server sends to Evade players). This package is for mods running in the same game as Evade.
 *
 * <h2>Getting started</h2>
 * <ol>
 *   <li>Compile against the Evade Client jar ({@code modCompileOnly}); don't bundle it.</li>
 *   <li>Implement {@link net.evadeclient.addon.EvadeAddon} and list it under the {@code "evade"} entrypoint in your
 *       {@code fabric.mod.json}: {@code "entrypoints": {"evade": ["com.example.MyEvadeAddon"]}}. Add
 *       {@code "suggests": {"evade": "*"}} (or {@code depends}) so your mod still loads without Evade.</li>
 *   <li>In {@link net.evadeclient.addon.EvadeAddon#register}, register subclasses of
 *       {@link net.evadeclient.addon.AddonModule}, {@link net.evadeclient.addon.AddonHudElement} or
 *       {@link net.evadeclient.addon.AddonTextHud}. Their settings come from the {@code toggle / slider / mode /
 *       color / key / text} helpers.</li>
 * </ol>
 *
 * <h2>What Evade does for addon modules</h2>
 * They appear in the menu (with an "Addon: &lt;your mod's name&gt;" tag), search, profiles, share codes, keybinds,
 * {@code .toggle} / {@code .set}, and (for widgets) the HUD editor, saved in Evade's config like built-in modules.
 * Server rules ({@code evade:rules}) apply to them by name, and a server can switch off every addon module at once with
 * {@code "@addons"} or one mod's with {@code "@addon:<modid>"}. An addon module that throws is switched off.
 *
 * <h2>Responsibility</h2>
 * Evade can't review other mods' code. An addon module is the addon author's responsibility, including whether it is
 * fair on the servers people play on; Evade always shows which mod added it. Keep to Evade's rules: nothing that
 * shows what the game hides, and no automation of combat, movement or inventory.
 *
 * <p>Stability: the types in this package (and the setting classes they return from {@code net.evadeclient.setting})
 * keep their signatures across Evade 2.x; {@link net.evadeclient.addon.EvadeAddon#API_VERSION} goes up when something
 * is added.
 */
package net.evadeclient.addon;
