package net.evadeclient.installer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The tested add-ons: their definitions, the GPU and Sodium checks, and the Mod check rules made for them. */
class AddonsTest {
    private static Path jar(Path dir, String file, String json) throws Exception {
        Path p = dir.resolve(file);
        Files.createDirectories(dir);
        try (OutputStream o = Files.newOutputStream(p); ZipOutputStream z = new ZipOutputStream(o)) {
            z.putNextEntry(new ZipEntry("fabric.mod.json"));
            z.write(json.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return p;
    }

    @Test
    void definitions() {
        Set<String> keys = new HashSet<>(), ids = new HashSet<>();
        for (Addons.Addon a : Addons.ALL) {
            assertTrue(keys.add(a.key()), "duplicate key " + a.key());
            assertTrue(ids.add(a.projectId()), "duplicate project " + a.projectId());
            assertTrue(a.projectId().matches("[A-Za-z0-9]{8}"), a.projectId());
            assertTrue(a.url().startsWith("https://modrinth.com/mod/"), a.url());
            assertFalse(a.tested().isEmpty(), a.name());
            assertFalse(a.licence().isBlank(), a.name());
            // Evade manages its own options' mods: an add-on is never one of them.
            assertNull(InstallerCore.optionForSlug(a.slug()), a.slug());
            assertSame(a, Addons.byKey(a.key()));
            assertSame(a, Addons.byProject(a.projectId()));
        }
        // Closed licences are only ever downloaded, never shared.
        assertTrue(Addons.FLASHBACK.closed());
        assertTrue(Addons.AXIOM.closed());
        assertFalse(Addons.DISTANT_HORIZONS.closed());
        assertTrue(Addons.NVIDIUM.nvidiaOnly());
        assertEquals("Replay", Addons.FLASHBACK.evadeModule());
        assertSame(Addons.DISTANT_HORIZONS, Addons.find("distant horizons"));
        // Coleweight can't run on this version: it's listed as not offered, with why.
        boolean cole = false;
        for (String[] s : Addons.NOT_OFFERED) if (s[0].equals("Coleweight")) cole = s[2].contains("Forge 1.8.9");
        assertTrue(cole);
    }

    @Test
    void nvidiaDetection() {
        assertEquals(Addons.Gpu.OK, Addons.nvidia(List.of("NVIDIA GeForce RTX 4060 Laptop GPU")));
        assertEquals(Addons.Gpu.OK, Addons.nvidia(List.of("Intel(R) UHD Graphics", "NVIDIA GeForce GTX 1650")));
        assertEquals(Addons.Gpu.OLD, Addons.nvidia(List.of("NVIDIA GeForce GTX 1080 Ti")));
        assertEquals(Addons.Gpu.NONE, Addons.nvidia(List.of("AMD Radeon RX 7800 XT")));
        assertEquals(Addons.Gpu.NONE, Addons.nvidia(List.of("Apple M2")));
        assertEquals(Addons.Gpu.UNKNOWN, Addons.nvidia(List.of()));
    }

    @Test
    void decisions() {
        // No Fabric 1.21.11 build: never reach for another version.
        Addons.Status s = Addons.decide(Addons.LITEMATICA, null, Addons.Gpu.OK, List.of(), null, null);
        assertEquals(Addons.State.NOT_FOR_VERSION, s.state());
        assertTrue(s.line().contains("Not available for 1.21.11 yet"));
        assertFalse(s.installable());
        assertTrue(Addons.decide(Addons.LITEMATICA, "0.26.16", Addons.Gpu.NONE, List.of("AMD"), null, null).installable());
        // Nvidium: NVIDIA only, and only for the Sodium it was made for.
        assertEquals(Addons.State.NEEDS_NVIDIA, Addons.decide(Addons.NVIDIUM, "0.4.3", Addons.Gpu.NONE, List.of("AMD Radeon RX 6600"), "0.8.12", "0.8.12").state());
        assertEquals(Addons.State.OLD_NVIDIA, Addons.decide(Addons.NVIDIUM, "0.4.3", Addons.Gpu.OLD, List.of("GTX 1060"), "0.8.12", "0.8.12").state());
        assertEquals(Addons.State.GPU_UNKNOWN, Addons.decide(Addons.NVIDIUM, "0.4.3", Addons.Gpu.UNKNOWN, List.of(), "0.8.12", "0.8.12").state());
        assertEquals(Addons.State.SODIUM_MISMATCH, Addons.decide(Addons.NVIDIUM, "0.4.3", Addons.Gpu.OK, List.of("RTX 3070"), "0.8.12", "0.8.14").state());
        assertEquals(Addons.State.NEEDS_FPS_MODS, Addons.decide(Addons.NVIDIUM, "0.4.3", Addons.Gpu.OK, List.of("RTX 3070"), "0.8.12", null).state());
        assertTrue(Addons.decide(Addons.NVIDIUM, "0.4.3", Addons.Gpu.OK, List.of("RTX 3070"), "0.8.12", "0.8.12").installable());
    }

    @Test
    void sodiumVersions() {
        assertEquals("0.8.12", Addons.plainVersion("mc1.21.11-0.8.12-fabric"));
        assertEquals("0.8.14", Addons.plainVersion("0.8.14+mc1.21.11"));
        assertEquals("0.8.15-beta.2", Addons.plainVersion("mc1.21.11-0.8.15-beta.2-fabric"));
        assertNull(Addons.plainVersion(null));
    }

    @Test
    void instanceSodium(@TempDir Path dir) throws Exception {
        assertNull(Addons.instanceSodium(dir));
        jar(dir.resolve("mods"), "sodium-fabric-0.8.14+mc1.21.11.jar", "{\"id\":\"sodium\",\"version\":\"0.8.14+mc1.21.11\"}");
        jar(dir.resolve("mods"), "sodium-extra-0.7.jar", "{\"id\":\"sodium-extra\",\"version\":\"0.7.0\"}");
        assertEquals("0.8.14", Addons.instanceSodium(dir));
    }

    @Test
    void switchOffRequest(@TempDir Path dir) throws Exception {
        assertTrue(Addons.requestedOff(dir).isEmpty());
        Addons.requestOff(dir, "Replay");
        Addons.requestOff(dir, "Replay");
        Addons.requestOff(dir, "Zoom");
        assertEquals(List.of("Replay", "Zoom"), List.copyOf(Addons.requestedOff(dir)));
        assertTrue(Files.exists(dir.resolve(".evade").resolve("modules-off.json")));
    }

    // ------------------------------------------------------------------ Mod check rules

    private static Map<Path, ModChecks.Meta> read(Path... jars) {
        Map<Path, ModChecks.Meta> m = new LinkedHashMap<>();
        for (Path p : jars) m.put(p, ModChecks.read(p));
        return m;
    }

    @Test
    void nvidiumNeedsItsSodium(@TempDir Path dir) throws Exception {
        // As Nvidium 0.4.3-beta13's fabric.mod.json says.
        Path nv = jar(dir, "nvidium.jar", "{\"id\":\"nvidium\",\"name\":\"Nvidium\",\"version\":\"0.4.3-beta13-1.21.11\","
                + "\"depends\":{\"fabricloader\":\">=0.17.2\",\"minecraft\":[\"1.21.11\"],\"sodium\":[\"=0.8.11\",\"=0.8.12\"]}}");
        Path s14 = jar(dir, "sodium14.jar", "{\"id\":\"sodium\",\"name\":\"Sodium\",\"version\":\"0.8.14+mc1.21.11\"}");
        var ps = ModChecks.check(read(nv, s14), Set.of(nv, s14), Map.of());
        var p = ps.stream().filter(x -> x.kind() == ModChecks.Kind.BREAKS).findFirst().orElseThrow(() -> new AssertionError(ps.toString()));
        assertEquals("Nvidium needs Sodium 0.8.11 or 0.8.12", p.title());
        assertTrue(p.detail().contains("0.8.14"), p.detail());

        Path s12 = jar(dir, "sodium12.jar", "{\"id\":\"sodium\",\"name\":\"Sodium\",\"version\":\"0.8.12+mc1.21.11\"}");
        assertTrue(ModChecks.check(read(nv, s12), Set.of(nv, s12), Map.of()).isEmpty());
    }

    @Test
    void maLiLibRangesPass(@TempDir Path dir) throws Exception {
        // Litematica 0.26.16 with MaLiLib 0.27.20, next to Evade's Sodium 0.8.14 and Iris 1.10.8: nothing to report.
        Path lit = jar(dir, "litematica.jar", "{\"id\":\"litematica\",\"name\":\"Litematica\",\"version\":\"0.26.16\","
                + "\"depends\":{\"minecraft\":\"1.21.11\",\"malilib\":\">=0.27.19- <0.28.0-\"},\"breaks\":{\"malilib\":\"<0.27.19-\"}}");
        Path mali = jar(dir, "malilib.jar", "{\"id\":\"malilib\",\"name\":\"MaLiLib\",\"version\":\"0.27.20\","
                + "\"breaks\":{\"sodium\":\"<0.8.7-\",\"iris\":\"<1.10.7-\",\"litematica\":\"<0.26.13-\"}}");
        Path sod = jar(dir, "sodium.jar", "{\"id\":\"sodium\",\"name\":\"Sodium\",\"version\":\"0.8.14+mc1.21.11\"}");
        Path iris = jar(dir, "iris.jar", "{\"id\":\"iris\",\"name\":\"Iris\",\"version\":\"1.10.8+mc1.21.11\",\"depends\":{\"sodium\":\"*\"}}");
        assertTrue(ModChecks.check(read(lit, mali, sod, iris), Set.of(lit, mali, sod, iris), Map.of()).isEmpty());

        // An old MaLiLib is out of Litematica's range.
        Path old = jar(dir, "malilib-old.jar", "{\"id\":\"malilib\",\"name\":\"MaLiLib\",\"version\":\"0.27.10\"}");
        var ps = ModChecks.check(read(lit, old), Set.of(lit, old), Map.of());
        assertTrue(ps.stream().anyMatch(p -> p.kind() == ModChecks.Kind.BREAKS && p.title().equals("Litematica needs MaLiLib 0.27.19 or newer, older than 0.28.0")), ps.toString());
    }

    @Test
    void flashbackOffersEvadeReplayOff(@TempDir Path dir) throws Exception {
        Path fb = jar(dir, "flashback.jar", "{\"id\":\"flashback\",\"name\":\"Flashback\",\"version\":\"0.39.10\",\"depends\":{\"fabric-api\":\">=0.107.0\"}}");
        Path ev = jar(dir, "evade.jar", "{\"id\":\"evade\",\"name\":\"Evade Client\",\"version\":\"2.3.0\"}");
        Path api = jar(dir, "fabric-api.jar", "{\"id\":\"fabric-api\",\"name\":\"Fabric API\",\"version\":\"0.141.6+1.21.11\"}");
        var ps = ModChecks.check(read(fb, ev, api), Set.of(fb, ev, api), Map.of());
        var p = ps.stream().filter(x -> x.kind() == ModChecks.Kind.OVERLAP).findFirst().orElseThrow(() -> new AssertionError(ps.toString()));
        assertTrue(p.warning());
        assertEquals("Replay", p.evadeModule());
        assertEquals(1, ps.size(), ps.toString()); // its Fabric API range is met
    }

    @Test
    void shaderNotices(@TempDir Path dir) throws Exception {
        Path nv = jar(dir, "nvidium.jar", "{\"id\":\"nvidium\",\"name\":\"Nvidium\",\"version\":\"0.4.3\"}");
        Path dh = jar(dir, "dh.jar", "{\"id\":\"distanthorizons\",\"name\":\"Distant Horizons\",\"version\":\"3.3.3\"}");
        Path iris = jar(dir, "iris.jar", "{\"id\":\"iris\",\"name\":\"Iris\",\"version\":\"1.10.8\"}");
        var ps = ModChecks.check(read(nv, dh, iris), Set.of(nv, dh, iris), Map.of());
        assertEquals(2, ps.size(), ps.toString());
        assertTrue(ps.stream().allMatch(ModChecks.Problem::warning));
        // Without Iris, nothing to say.
        assertTrue(ModChecks.check(read(nv, dh), Set.of(nv, dh), Map.of()).isEmpty());
    }

    @Test
    void oddVersionsNeverInventProblems() {
        assertTrue(ModChecks.accepts(List.of(">=1.0"), "${version}"));
        assertTrue(ModChecks.accepts(List.of("*"), "0.1"));
        assertTrue(ModChecks.accepts(List.of("=0.8.11", "=0.8.12"), "0.8.12+mc1.21.11"));
        assertFalse(ModChecks.accepts(List.of("=0.8.11", "=0.8.12"), "0.8.14+mc1.21.11"));
        assertEquals("0.8.11 or 0.8.12", ModChecks.rangeText(List.of("=0.8.11", "=0.8.12")));
    }
}
