package net.evadeclient.installer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Starting points for "New instance": each sets the instance's options (FPS mods, shaders, voice chat, pack features,
 * dynamic lights, optional extras), its memory, and a few Modrinth projects to add straight away (the "shelf").
 * Everything a template adds can be changed afterwards like any other instance. Shelf mods come from Modrinth with
 * their libraries, like any mod installed from the Mods page (Litematica: LGPL-3.0, with MaLiLib).
 */
final class InstanceTemplates {
    private InstanceTemplates() {}

    /**
     * {@code memory}: 0 for this PC's recommendation, else a fixed amount (kept within what the PC can spare);
     * {@code shelf}: Modrinth slugs installed when the instance is made.
     */
    record Template(String key, String name, String sub, String icon, boolean perf, boolean shaders, boolean voice, boolean packs,
                    boolean lights, Set<String> optional, int memory, List<String> shelf, Set<String> shaderPacks) {
        /** {@code shaderPacks}: the {@link ShaderPacks} ticked (null: Photon and Complementary Reimagined). */
        Template {
            shaderPacks = java.util.Collections.unmodifiableSet(ShaderPacks.clean(shaderPacks));
        }
        Template(String key, String name, String sub, String icon, boolean perf, boolean shaders, boolean voice, boolean packs,
                 boolean lights, Set<String> optional, int memory, List<String> shelf) {
            this(key, name, sub, icon, perf, shaders, voice, packs, lights, optional, memory, shelf, null);
        }
        /** The memory this template gives on this PC ({@code fallback} when it follows the default). */
        int memoryGb(int fallback) {
            if (memory > 0) return InstallerCore.clampMemory(memory);
            return fallback;
        }
    }

    static final Template BLANK = new Template("blank", "Blank", "The usual Evade setup: FPS mods, voice chat and resource pack features", "cube",
            true, false, true, true, false, Set.of(), 0, List.of());
    static final Template PVP = new Template("pvp", "PvP (lean)", "FPS mods and faster start-up; no shaders, voice chat or pack extras", "pulse",
            true, false, false, false, false, Set.of(OptionalMods.FAST_START.key(), OptionalMods.MORE_FPS.key()), 3, List.of());
    static final Template SURVIVAL = new Template("survival", "Survival + shaders", "Shaders, dynamic lights, voice chat and Open to Friends", "globe",
            true, true, true, true, true, Set.of(OptionalMods.FAST_START.key(), OptionalMods.FRIENDS.key()), 0, List.of());
    static final Template BUILDING = new Template("building", "Building", "Litematica for schematics, with voice chat and resource pack features", "layers",
            true, false, true, true, false, Set.of(OptionalMods.FAST_START.key()), 0, List.of("litematica"));
    static final Template LAPTOP = new Template("laptop", "Low-end laptop", "The lightest setup: FPS extras on, pack features off, memory sized for shared graphics", "gear",
            true, false, true, false, false, Set.of(OptionalMods.FAST_START.key(), OptionalMods.MORE_FPS.key()), -1, List.of(),
            // If shaders get switched on later: the two packs that suit shared graphics.
            Set.of(ShaderPacks.REIMAGINED.key(), ShaderPacks.SILDURS.key()));

    static final Template[] ALL = {BLANK, PVP, SURVIVAL, BUILDING, LAPTOP};

    static Template byKey(String key) {
        if (key == null) return null;
        String k = key.strip().toLowerCase(Locale.ROOT);
        for (Template t : ALL) if (t.key().equals(k)) return t;
        return null;
    }

    static String keys() {
        List<String> out = new ArrayList<>();
        for (Template t : ALL) out.add(t.key());
        return String.join(", ", out);
    }

    /** Memory for a template on this PC: the laptop one follows {@link MemoryAdvice}, the others their own amount. */
    static int memoryFor(Template t, int defaultGb) {
        if (t.memory() < 0) return MemoryAdvice.forPc(MemoryAdvice.detect()).recommendedGb();
        return t.memoryGb(defaultGb);
    }

    /** Puts a template's options on a new instance (before its first save). */
    static void apply(Template t, Launcher.Instance i, int defaultGb) {
        i.perf = t.perf();
        i.shaders = t.shaders();
        i.voice = t.voice();
        i.packs = t.packs();
        i.lights = t.lights();
        i.optional = OptionalMods.clean(t.optional());
        i.shaderPacks = ShaderPacks.clean(t.shaderPacks());
        i.memoryGb = memoryFor(t, defaultGb);
    }

    /**
     * Makes a new instance from a template: options and memory set, settings copied from {@code copyFrom} like any new
     * instance, then the shelf installed from Modrinth. A shelf mod that can't be installed now is reported, not fatal.
     *
     * @param notes filled with what couldn't be added
     */
    static Launcher.Instance create(InstallerCore core, Launcher.Settings s, List<Launcher.Instance> all, String name, String copyFrom,
                                    Template t, List<String> notes, InstallerCore.Progress p) throws IOException {
        Launcher.Instance i = Launcher.create(s, all, name, copyFrom, s.defaultMemoryGb, t.perf(), t.shaders(), t.voice());
        apply(t, i, s.defaultMemoryGb);
        i.dirty = true;
        Launcher.save(i);
        int k = 0;
        for (String slug : t.shelf()) {
            double a = (double) k / t.shelf().size(), b = (double) (k + 1) / t.shelf().size();
            k++;
            try {
                ContentApi.Project pr = ContentApi.project(slug);
                if (pr == null) { notes.add(slug + " isn't on Modrinth any more"); continue; }
                Mods.install(core, i.dir, new Mods.Hit(pr.id(), pr.slug(), pr.title(), "", "", 0, 0),
                        (f, m) -> { if (p != null) p.update(a + (b - a) * f, m); });
            } catch (Exception e) {
                notes.add(slug + ": " + EvadeInstaller.friendly(e));
            }
        }
        Launcher.refresh(i);
        return i;
    }
}
