package net.evadeclient.mods;

import net.evadeclient.mods.ModrinthApi.ContentType;
import net.evadeclient.mods.ModrinthApi.Version;
import net.evadeclient.mods.ModrinthApi.VersionFile;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * Installs the Mod Browser's other kinds of content from Modrinth: resource packs into {@code resourcepacks/}, shader
 * packs into {@code shaderpacks/} (Iris' folder), and data packs into one singleplayer world's {@code datapacks/}
 * folder, picked by the player. Each is a single zip with no dependencies, so there's no plan step: the newest version
 * for this game version is downloaded (SHA-512 checked by {@link ModrinthApi#download}) and dropped in place.
 *
 * <p>Data packs only ever go into a world on this PC: a server's data packs are the server owner's business. All
 * calls block: run them on {@link ModBrowser#EXECUTOR}.
 */
public final class ContentInstaller {
    private ContentInstaller() {}

    /** A singleplayer world folder: its folder name and when it was last played (level.dat's time). */
    public record World(String folder, long lastPlayed) {}

    private static Path gameDir() { return FabricLoader.getInstance().getGameDir(); }

    /** Where {@code type} goes: resourcepacks/, shaderpacks/, or the world's datapacks/ ({@code world} required). */
    public static Path folder(ContentType type, String world) throws IOException {
        return switch (type) {
            case RESOURCE_PACK -> gameDir().resolve("resourcepacks");
            case SHADER -> gameDir().resolve("shaderpacks");
            case DATA_PACK -> {
                if (world == null || !ModManager.safeFileName(world)) throw new IOException("Pick a world for the data pack");
                Path w = gameDir().resolve("saves").resolve(world);
                if (!Files.isRegularFile(w.resolve("level.dat"))) throw new IOException("That world isn't there any more");
                yield w.resolve("datapacks");
            }
            case MOD -> throw new IOException("Mods are installed by the mod manager");
        };
    }

    /** Singleplayer worlds, most recently played first (at most {@code max}). */
    public static List<World> worlds(int max) {
        List<World> out = new ArrayList<>();
        Path saves = gameDir().resolve("saves");
        if (!Files.isDirectory(saves)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(saves)) {
            for (Path d : ds) {
                Path level = d.resolve("level.dat");
                if (!Files.isRegularFile(level)) continue;
                String name = d.getFileName().toString();
                if (!ModManager.safeFileName(name)) continue;
                long t = 0;
                try { t = Files.getLastModifiedTime(level).toMillis(); } catch (IOException ignored) {}
                out.add(new World(name, t));
            }
        } catch (IOException ignored) {
            // no saves folder to read: no worlds
        }
        out.sort(Comparator.comparingLong(World::lastPlayed).reversed());
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** The newest version to install: the newest release, else the newest beta or alpha; null when there's none. */
    public static Version newest(List<Version> versions) {
        List<Version> sorted = new ArrayList<>(versions);
        sorted.removeIf(v -> v.primaryFile() == null);
        sorted.sort(Comparator.comparing((Version v) -> v.published() == null ? "" : v.published()).reversed());
        for (Version v : sorted) if ("release".equals(v.type())) return v;
        return sorted.isEmpty() ? null : sorted.get(0);
    }

    /** Whether {@code v}'s file is already in {@code type}'s folder (for that world, for data packs). */
    public static boolean present(ContentType type, String world, Version v) {
        if (v == null || v.primaryFile() == null || !ModManager.safeFileName(v.primaryFile().filename())) return false;
        try {
            return Files.exists(folder(type, world).resolve(v.primaryFile().filename()));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Download {@code v} into {@code type}'s folder. Returns the file it wrote. A file of the same name already there
     * is kept (nothing is overwritten) and reported as already installed.
     */
    public static Path install(ModrinthApi api, ContentType type, String world, Version v, DoubleConsumer progress) throws IOException {
        if (v == null) throw new IOException("Nothing for Minecraft " + ModrinthApi.GAME_VERSION + " yet");
        VersionFile f = v.primaryFile();
        if (f == null || !ModManager.safeFileName(f.filename()) || !f.filename().toLowerCase(Locale.ROOT).endsWith(".zip"))
            throw new IOException("Modrinth listed an unusable file name");
        Path dir = folder(type, world);
        Files.createDirectories(dir);
        Path target = dir.resolve(f.filename());
        if (Files.exists(target)) throw new IOException("Already installed");
        Path staged = dir.resolve(f.filename() + ".evade-download");
        try {
            api.download(f, staged, progress);
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(staged);
        }
        return target;
    }

    /** Where the player can see it, e.g. "resourcepacks" or "saves/My World/datapacks". */
    public static String describe(ContentType type, String world) {
        return switch (type) {
            case RESOURCE_PACK -> "resourcepacks";
            case SHADER -> "shaderpacks";
            case DATA_PACK -> "saves/" + world + "/datapacks";
            case MOD -> "mods";
        };
    }
}
