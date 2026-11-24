package net.evadeclient.addon;

import net.evadeclient.EvadeClient;
import net.evadeclient.module.Module;
import net.evadeclient.module.ModuleManager;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads Evade addons (the {@code "evade"} Fabric entrypoint) and remembers which mod added each module. Internal:
 * addon authors use {@link EvadeAddon} and {@link AddonRegistry}.
 */
public final class EvadeAddons {
    /** The Fabric entrypoint name addons list their {@link EvadeAddon} under. */
    public static final String ENTRYPOINT = "evade";

    /** Which mod added a module. */
    public record Owner(String modId, String modName) {}

    private static final Map<Module, Owner> OWNERS = Collections.synchronizedMap(new IdentityHashMap<>());
    /** Addons that failed to load, as "mod: reason" (shown in the log; tests check it). */
    private static final List<String> FAILED = Collections.synchronizedList(new ArrayList<>());
    /** Mods that registered at least one module, id to name, in load order. */
    private static final Map<String, String> MODS = Collections.synchronizedMap(new LinkedHashMap<>());

    private EvadeAddons() {}

    /** Runs every addon's {@link EvadeAddon#register} and adds what they register to {@code modules}. Startup only. */
    public static void load(ModuleManager modules) {
        List<EntrypointContainer<EvadeAddon>> found;
        try {
            found = FabricLoader.getInstance().getEntrypointContainers(ENTRYPOINT, EvadeAddon.class);
        } catch (Throwable t) {
            EvadeClient.LOG.error("Evade addons: couldn't read the \"{}\" entrypoints", ENTRYPOINT, t);
            return;
        }
        for (EntrypointContainer<EvadeAddon> c : found) {
            ModMetadata meta = c.getProvider().getMetadata();
            String id = meta.getId(), name = meta.getName() == null || meta.getName().isBlank() ? id : meta.getName();
            if (id.equals("evade")) continue;
            Registry r = new Registry(modules, new Owner(id, name));
            try {
                c.getEntrypoint().register(r);
            } catch (Throwable t) {
                FAILED.add(name + ": " + t);
                EvadeClient.LOG.error("Evade addon from {} failed to register", id, t);
            } finally {
                r.open = false;
            }
            if (r.count > 0) {
                MODS.put(id, name);
                EvadeClient.LOG.info("Evade addon {} ({}) added {} module(s)", name, id, r.count);
            }
        }
    }

    /** The mod that added {@code m}, or null for Evade's own modules. */
    public static Owner owner(Module m) { return m == null ? null : OWNERS.get(m); }

    /** The name of the mod that added {@code m}, or null for Evade's own modules. */
    public static String ownerName(Module m) {
        Owner o = owner(m);
        return o == null ? null : o.modName();
    }

    public static boolean isAddon(Module m) { return owner(m) != null; }

    /** Mods that added modules, id to name. */
    public static Map<String, String> mods() { synchronized (MODS) { return Map.copyOf(MODS); } }

    /** Addons that failed to register, as "mod: reason". */
    public static List<String> failures() { synchronized (FAILED) { return List.copyOf(FAILED); } }

    private static final class Registry implements AddonRegistry {
        private final ModuleManager modules;
        private final Owner owner;
        volatile boolean open = true;
        int count;

        Registry(ModuleManager modules, Owner owner) {
            this.modules = modules;
            this.owner = owner;
        }

        @Override public void module(AddonModule module) { add(module); }
        @Override public void hud(AddonHudElement widget) { add(widget); }
        @Override public void hud(AddonTextHud widget) { add(widget); }
        @Override public String modId() { return owner.modId(); }
        @Override public String evadeVersion() { return EvadeClient.VERSION; }

        private void add(Module m) {
            if (!open) throw new IllegalStateException("Register Evade addon modules inside EvadeAddon.register");
            if (m == null) return;
            String why = check(m);
            if (why != null) {
                FAILED.add(owner.modName() + ": " + why);
                EvadeClient.LOG.error("Evade addon {}: {}", owner.modId(), why);
                return;
            }
            // Addon modules always start off, whatever the constructor did.
            if (m.isEnabled()) m.setEnabled(false);
            OWNERS.put(m, owner);
            modules.registerAddon(m);
            count++;
        }

        /** Why {@code m} can't be added, or null when it can. */
        private String check(Module m) {
            String name = m.getName();
            if (name == null || name.isBlank() || name.length() > 40 || name.startsWith("_") || !name.strip().equals(name))
                return "\"" + name + "\" isn't a usable module name (1-40 characters, no leading underscore or spaces)";
            if (m.getDescription() == null || m.getDescription().isBlank()) return name + " needs a description";
            if (OWNERS.containsKey(m)) return name + " was registered twice";
            String n = ModuleManager.normalize(name);
            if (n.isEmpty()) return "\"" + name + "\" needs letters or digits";
            for (Module other : modules.all())
                if (ModuleManager.normalize(other.getName()).equals(n))
                    return name + " clashes with " + (isAddon(other) ? ownerName(other) + "'s " : "Evade's ") + other.getName();
            return null;
        }
    }
}
