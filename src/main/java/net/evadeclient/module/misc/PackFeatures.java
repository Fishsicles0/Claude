package net.evadeclient.module.misc;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.texture.NativeImage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads what a resource pack needs beyond vanilla: the extra pack formats many PvP and texture packs ship (connected
 * textures, custom item textures, random and emissive entities, custom entity models, custom skies), which mod reads
 * each one, and whether that mod is loaded. Pack Organizer shows this per pack.
 *
 * <p>Also a warning-only x-ray check: a pack whose stone-type block textures are mostly see-through. Nothing is ever
 * changed or blocked; servers decide what packs they allow.
 *
 * <p>Only file names are read (plus a handful of block textures for the x-ray check), and results are cached by file
 * size and time, so scanning a big pack folder stays cheap. Any thread.
 */
public final class PackFeatures {
    private PackFeatures() {}

    /** A pack format and the mod that reads it (null id: no mod Evade installs reads it). */
    public enum Feature {
        CONNECTED("Connected textures", "continuity", "Continuity"),
        EMISSIVE("Emissive textures", "continuity", "Continuity"),
        CIT("Custom item textures", "citresewn", "CIT Resewn"),
        RANDOM_ENTITIES("Random entity textures", "entity_texture_features", "Entity Texture Features"),
        ENTITY_MODELS("Custom entity models", "entity_model_features", "Entity Model Features"),
        SKY("Custom sky", "skyboxify", "Skyboxify"),
        COLORS("Custom colours", null, null),
        GUIS("Custom GUIs", null, null),
        ANIMATIONS("Extra animations", null, null);

        public final String label, modId, modName;

        Feature(String label, String modId, String modName) {
            this.label = label;
            this.modId = modId;
            this.modName = modName;
        }

        /** Whether the mod that reads it is loaded (false when no mod Evade installs does). */
        public boolean supported() { return modId != null && FabricLoader.getInstance().isModLoaded(modId); }
    }

    /** What a pack uses: its features, and whether it looks like an x-ray pack. */
    public record Report(String name, Set<Feature> features, boolean xray) {
        /** Features it uses that the loaded mods don't read. */
        public List<Feature> missing() {
            List<Feature> out = new ArrayList<>();
            for (Feature f : features) if (!f.supported()) out.add(f);
            return out;
        }

        /** Whether the Resource pack features option (Continuity, CIT, ETF/EMF, Skyboxify) would fix what's missing. */
        public boolean fixableByInstaller() {
            for (Feature f : missing()) if (f.modId != null) return true;
            return false;
        }
    }

    private record Cached(long size, long time, Report report) {}

    private static final Map<Path, Cached> CACHE = new HashMap<>();

    /** Block textures ores sit in: if most of these are see-through, ores show through the ground. */
    private static final String[] ORE_HOSTS = {"stone", "deepslate", "dirt", "netherrack", "granite", "diorite", "andesite", "tuff", "gravel"};
    /** Share of a texture's pixels that must be (nearly) clear for it to count as see-through. */
    static final double CLEAR_SHARE = 0.5;

    /** Every resource pack in resourcepacks/ (zips and folders with a pack.mcmeta), sorted by name. */
    public static List<Report> scanAll(Path dir) {
        List<Report> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                boolean zip = Files.isRegularFile(p) && n.toLowerCase(Locale.ROOT).endsWith(".zip");
                boolean folder = Files.isDirectory(p) && Files.isRegularFile(p.resolve("pack.mcmeta"));
                if (!zip && !folder) continue;
                Report r = scan(p);
                if (r != null) out.add(r);
            }
        } catch (IOException ignored) {
            // nothing readable: no packs
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    /** One pack (zip or folder), cached; null if it can't be read. */
    public static synchronized Report scan(Path pack) {
        try {
            long size = Files.isDirectory(pack) ? -1 : Files.size(pack), time = Files.getLastModifiedTime(pack).toMillis();
            Cached c = CACHE.get(pack);
            if (c != null && c.size == size && c.time == time) return c.report;
            Report r = Files.isDirectory(pack) ? scanFolder(pack) : scanZip(pack);
            CACHE.put(pack, new Cached(size, time, r));
            return r;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Report scanZip(Path zip) throws IOException {
        Set<Feature> found = EnumSet.noneOf(Feature.class);
        int clear = 0, seen = 0;
        try (ZipFile z = new ZipFile(zip.toFile())) {
            var en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                classify(e.getName(), found);
            }
            for (String host : ORE_HOSTS) {
                ZipEntry e = z.getEntry("assets/minecraft/textures/block/" + host + ".png");
                if (e == null) continue;
                seen++;
                try (InputStream in = z.getInputStream(e)) {
                    if (mostlyClear(in)) clear++;
                }
            }
        }
        return new Report(zip.getFileName().toString(), found, xray(clear, seen));
    }

    private static Report scanFolder(Path dir) throws IOException {
        Set<Feature> found = EnumSet.noneOf(Feature.class);
        try (Stream<Path> s = Files.walk(dir, 12)) {
            s.limit(200_000).forEach(p -> classify(dir.relativize(p).toString().replace('\\', '/') + (Files.isDirectory(p) ? "/" : ""), found));
        }
        int clear = 0, seen = 0;
        for (String host : ORE_HOSTS) {
            Path png = dir.resolve("assets/minecraft/textures/block/" + host + ".png");
            if (!Files.isRegularFile(png)) continue;
            seen++;
            try (InputStream in = Files.newInputStream(png)) {
                if (mostlyClear(in)) clear++;
            }
        }
        return new Report(dir.getFileName().toString(), found, xray(clear, seen));
    }

    /** X-ray: at least two ore-host textures replaced and most of the replaced ones see-through. */
    static boolean xray(int clear, int seen) {
        return clear >= 2 && clear * 2 >= seen;
    }

    /** Which feature a path inside a pack belongs to (OptiFine and MCPatcher folder layouts), added to {@code out}. */
    static void classify(String path, Set<Feature> out) {
        String p = path.toLowerCase(Locale.ROOT);
        int i = p.indexOf("/optifine/");
        if (i < 0) i = p.indexOf("/mcpatcher/");
        if (i < 0) {
            if (p.contains("/citresewn/cit/")) out.add(Feature.CIT);
            return;
        }
        String rest = p.substring(p.indexOf('/', i + 1) + 1);
        if (rest.startsWith("ctm/")) out.add(Feature.CONNECTED);
        else if (rest.startsWith("cit/") || rest.equals("cit.properties")) out.add(Feature.CIT);
        else if (rest.startsWith("random/") || rest.startsWith("mob/")) out.add(Feature.RANDOM_ENTITIES);
        else if (rest.startsWith("cem/")) out.add(Feature.ENTITY_MODELS);
        else if (rest.startsWith("sky/")) out.add(Feature.SKY);
        else if (rest.equals("emissive.properties")) out.add(Feature.EMISSIVE);
        else if (rest.equals("color.properties") || rest.startsWith("colormap/")) out.add(Feature.COLORS);
        else if (rest.startsWith("gui/container/")) out.add(Feature.GUIS);
        else if (rest.startsWith("anim/")) out.add(Feature.ANIMATIONS);
    }

    /** Whether at least {@link #CLEAR_SHARE} of a PNG's pixels are nearly transparent (alpha under 10%). */
    static boolean mostlyClear(InputStream png) {
        try (NativeImage img = NativeImage.read(png)) {
            int w = img.getWidth(), h = img.getHeight();
            if (w <= 0 || h <= 0 || (long) w * h > 4096L * 4096L) return false;
            long clear = 0, total = (long) w * h;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) if (((img.getColorArgb(x, y) >>> 24) & 0xFF) < 26) clear++;
            }
            return clear >= total * CLEAR_SHARE;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
