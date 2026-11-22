package net.evadeclient.config;

import net.evadeclient.EvadeClient;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Named config presets, like Lunar's profiles. Each profile is a full snapshot of every module's
 * state, settings, keybinds and HUD layout. The active profile stays in sync with your live edits,
 * and switching saves the current one before loading the next.
 */
public class ProfileManager {
    private final ConfigManager config;
    private final Path dir = FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("profiles");
    private final Path pointer = dir.resolve("active.txt");

    public ProfileManager(ConfigManager config) { this.config = config; }

    /** After config.json has loaded, seed a Default profile or apply the active one over it. */
    public void init() {
        try { Files.createDirectories(dir); } catch (Exception ignored) {}
        if (list().isEmpty()) {
            config.saveTo(file("Default"));
            setActive("Default");
        } else {
            String a = active();
            if (Files.exists(file(a))) config.loadFrom(file(a));
        }
    }

    /** Profile names, Default first, then alphabetical. */
    public List<String> list() {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.toString().endsWith(".json"))
             .map(p -> p.getFileName().toString())
             .map(n -> n.substring(0, n.length() - 5))
             .sorted(String.CASE_INSENSITIVE_ORDER)
             .forEach(out::add);
        } catch (Exception ignored) {}
        out.remove("Default");
        if (Files.exists(file("Default"))) out.add(0, "Default");
        return out;
    }

    public String active() {
        try { if (Files.exists(pointer)) return Files.readString(pointer).trim(); } catch (Exception ignored) {}
        return "Default";
    }

    private void setActive(String name) {
        try { Files.createDirectories(dir); Files.writeString(pointer, name); } catch (Exception ignored) {}
    }

    public Path file(String name) { return dir.resolve(sanitize(name) + ".json"); }

    public boolean exists(String name) { return Files.exists(file(name)); }

    /** Keep the active profile in step with every live save (called from ConfigManager.save). */
    public void mirrorFrom(Path live) {
        try { Files.copy(live, file(active()), java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignored) {}
    }

    /** Persist the current profile, then load {@code name} and make it active. */
    public void switchTo(String name) {
        if (name == null || name.equals(active()) || !exists(name)) return;
        config.saveTo(file(active()));
        setActive(name);
        config.loadFrom(file(name));
        config.save();
    }

    /** Save the current state as a new profile and switch to it. Returns false if the name is taken or blank. */
    public boolean create(String name) {
        String clean = sanitize(name);
        if (clean.isEmpty() || exists(clean)) return false;
        config.saveTo(file(clean));
        setActive(clean);
        return true;
    }

    /** Delete a profile. The active profile and the last remaining profile can't be deleted. */
    public boolean delete(String name) {
        if (name.equals(active()) || list().size() <= 1) return false;
        try { Files.deleteIfExists(file(name)); return true; } catch (Exception e) { return false; }
    }

    /** A filesystem-safe profile name: letters, digits, spaces, dashes and underscores, trimmed. */
    public static String sanitize(String name) {
        if (name == null) return "";
        String s = name.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        return s.length() > 24 ? s.substring(0, 24) : s;
    }
}
