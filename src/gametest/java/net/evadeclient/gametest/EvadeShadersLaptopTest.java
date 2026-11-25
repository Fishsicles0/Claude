package net.evadeclient.gametest;

import net.evadeclient.EvadeClient;
import net.evadeclient.util.GpuInfo;
import net.evadeclient.util.ShaderSwitch;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Evade Shaders in the launcher's pack list and on integrated graphics: the Shaders mode knows it, its file names are
 * recognised, and its LOW level is written to Iris' per-pack settings file only when the player never changed it
 * (checked on a copy of the real pack in a temporary folder, so the game's own shaderpacks/ is never touched). The
 * integrated-graphics hook leaves another active pack alone.
 */
final class EvadeShadersLaptopTest {
    private EvadeShadersLaptopTest() {}

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        EvadeClient.LOG.info("[gametest] PASS: {}", what);
    }

    /** shaders/evade-shaders/shaders in the repository (the run directory is build/run/clientGameTest), or null. */
    private static Path packShaders() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++, p = p.getParent()) {
            Path c = p.resolve("shaders/evade-shaders/shaders");
            if (Files.isRegularFile(c.resolve("shaders.properties"))) return c;
        }
        return null;
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) {
        try {
            runChecks(ctx);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void runChecks(ClientGameTestContext ctx) throws java.io.IOException {
        var perf = ctx.computeOnClient(c -> EvadeClient.modules.get(net.evadeclient.module.mods.Performance.class));
        check(ctx.computeOnClient(c -> perf.shaders.getModes().contains("Evade Shaders")), "Performance > Shaders offers Evade Shaders");
        check(ShaderSwitch.isEvadePack("Evade Shaders v1.0.zip") && ShaderSwitch.isEvadePack("EvadeShaders.zip")
                && !ShaderSwitch.isEvadePack("photon_v1.4.zip"), "Evade Shaders file names are recognised");
        check("Evade Shaders".equals(ShaderSwitch.displayName("Evade Shaders v1.0.zip")), "Evade Shaders shows by its name");

        Path src = packShaders();
        if (src == null) {
            EvadeClient.LOG.info("[gametest] SKIP: Evade Shaders laptop level (pack source not found)");
            return;
        }
        Path dir = Files.createTempDirectory("evade-shaders-laptop");
        try {
            String pack = "Evade Shaders v1.0.zip";
            try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(dir.resolve(pack)));
                 Stream<Path> files = Files.walk(src)) {
                for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    z.putNextEntry(new ZipEntry("shaders/" + src.relativize(f).toString().replace('\\', '/')));
                    z.write(Files.readAllBytes(f));
                    z.closeEntry();
                }
            }
            check(ShaderSwitch.setProfileIfUntouched(dir, pack, "LOW"), "LOW level written for an untouched Evade Shaders");
            Properties p = new Properties();
            try (var in = Files.newInputStream(dir.resolve(pack + ".txt"))) { p.load(in); }
            check("LOW".equals(p.getProperty("profile")) && "1024".equals(p.getProperty("shadowMapResolution"))
                    && "false".equals(p.getProperty("BLOOM")) && "true".equals(p.getProperty("SHADOWS")),
                    "the settings file holds the LOW level's values: " + p);
            Files.writeString(dir.resolve(pack + ".txt"), "BLOOM=true\n");
            check(!ShaderSwitch.setProfileIfUntouched(dir, pack, "LOW") && Files.readString(dir.resolve(pack + ".txt")).equals("BLOOM=true\n"),
                    "the player's own pack settings are never replaced");
            check(!ShaderSwitch.setProfileIfUntouched(dir, "Missing.zip", "LOW"), "no settings for a pack that isn't there");
        } finally {
            try (Stream<Path> s = Files.walk(dir)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }

        // Integrated graphics with some other pack (or none) active: nothing is changed.
        boolean evadeActive = ctx.computeOnClient(c -> ShaderSwitch.selectedPack().map(ShaderSwitch::isEvadePack).orElse(false));
        if (!evadeActive) {
            boolean changed = ctx.computeOnClient(c -> {
                String was = GpuInfo.forcedRenderer;
                GpuInfo.forcedRenderer = "AMD Radeon(TM) 780M Graphics";
                try {
                    return ShaderSwitch.laptopProfile();
                } finally {
                    GpuInfo.forcedRenderer = was;
                }
            });
            check(!changed, "the integrated-graphics level leaves another active pack alone");
        }
    }
}
