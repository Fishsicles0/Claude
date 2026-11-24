package net.evadeclient.installer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Mod checks read fabric.mod.json from real jars and find missing, switched-off, clashing and doubled mods. */
class ModChecksTest {
    private static Path jar(Path dir, String file, String json) throws Exception {
        Path p = dir.resolve(file);
        try (OutputStream o = Files.newOutputStream(p); ZipOutputStream z = new ZipOutputStream(o)) {
            z.putNextEntry(new ZipEntry("fabric.mod.json"));
            z.write(json.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return p;
    }

    @Test
    void readsJars(@TempDir Path dir) throws Exception {
        Path p = jar(dir, "a.jar", "{\"id\":\"alpha\",\"name\":\"Alpha\",\"provides\":[\"alpha-api\"],\"depends\":{\"fabricloader\":\"*\",\"beta\":\"*\"},"
                + "\"breaks\":{\"gamma\":\"*\"},\"license\":[\"MIT\"]}");
        ModChecks.Meta m = ModChecks.read(p);
        assertEquals("alpha", m.id());
        assertEquals("Alpha", m.name());
        assertEquals(Set.of("alpha-api"), m.provides());
        assertEquals(Set.of("fabricloader", "beta"), m.depends());
        assertEquals(Set.of("gamma"), m.breaks());
        assertEquals("MIT", m.license());
        Path notMod = dir.resolve("x.jar");
        Files.writeString(notMod, "not a zip");
        assertNull(ModChecks.read(notMod));
    }

    @Test
    void findsProblems(@TempDir Path dir) throws Exception {
        Map<Path, ModChecks.Meta> metas = new LinkedHashMap<>();
        Path a = jar(dir, "a.jar", "{\"id\":\"alpha\",\"depends\":{\"minecraft\":\"*\",\"beta\":\"*\",\"delta\":\"*\"},\"breaks\":{\"gamma\":\"*\"}}");
        Path b = jar(dir, "b.jar", "{\"id\":\"beta\"}");
        Path g = jar(dir, "g.jar", "{\"id\":\"gamma\"}");
        Path g2 = jar(dir, "g2.jar", "{\"id\":\"gamma\"}");
        for (Path p : List.of(a, b, g, g2)) metas.put(p, ModChecks.read(p));
        // beta is switched off; delta isn't installed; gamma is there twice and alpha breaks with it
        List<ModChecks.Problem> ps = ModChecks.check(metas, Set.of(a, g, g2), Map.of("delta", "DDDDDDDD"));
        assertTrue(ps.stream().anyMatch(p -> p.kind() == ModChecks.Kind.MISSING && "delta".equals(p.needId()) && "DDDDDDDD".equals(p.projectId())), ps.toString());
        assertTrue(ps.stream().anyMatch(p -> p.kind() == ModChecks.Kind.SWITCHED_OFF && "beta".equals(p.needId())), ps.toString());
        assertTrue(ps.stream().anyMatch(p -> p.kind() == ModChecks.Kind.BREAKS), ps.toString());
        assertTrue(ps.stream().anyMatch(p -> p.kind() == ModChecks.Kind.DUPLICATE), ps.toString());
        assertTrue(ps.stream().noneMatch(p -> "minecraft".equals(p.needId())), "built-in ids are never missing");
        // all fine once beta is on, delta is gone and one gamma remains (alpha no longer breaks with it: no gamma)
        Map<Path, ModChecks.Meta> ok = new LinkedHashMap<>();
        Path a2 = jar(dir, "a2.jar", "{\"id\":\"alpha\",\"depends\":{\"beta\":\"*\"}}");
        ok.put(a2, ModChecks.read(a2));
        ok.put(b, metas.get(b));
        assertEquals(List.of(), ModChecks.check(ok, Set.of(a2, b), Map.of()));
    }

    @Test
    void knownConflictsAndOverlaps(@TempDir Path dir) throws Exception {
        Map<Path, ModChecks.Meta> metas = new LinkedHashMap<>();
        Path s = jar(dir, "s.jar", "{\"id\":\"sodium\"}");
        Path o = jar(dir, "o.jar", "{\"id\":\"optifabric\"}");
        Path z1 = jar(dir, "z1.jar", "{\"id\":\"zoomify\"}");
        Path z2 = jar(dir, "z2.jar", "{\"id\":\"ok_zoomer\"}");
        for (Path p : List.of(s, o, z1, z2)) metas.put(p, ModChecks.read(p));
        List<ModChecks.Problem> ps = ModChecks.check(metas, Set.of(s, o, z1, z2), Map.of());
        assertTrue(ps.stream().anyMatch(p -> p.kind() == ModChecks.Kind.BREAKS && p.mods().containsAll(List.of(s, o))), ps.toString());
        assertTrue(ps.stream().anyMatch(p -> p.kind() == ModChecks.Kind.OVERLAP && p.warning()), ps.toString());
    }
}
