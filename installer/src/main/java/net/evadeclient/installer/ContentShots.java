package net.evadeclient.installer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * --screenshots for the Mods page (installed list, browse, worlds, modpacks), with real data: a throwaway Minecraft
 * folder gets a real Modrinth modpack imported into a new instance, a few extra mods and packs installed, and some
 * sample worlds, so the update chips, sources and checks are the ones a player would see. Needs the network, so it
 * only runs with -Devade.shots.live=true (or -Devade.shots.only=content).
 */
final class ContentShots {
    private ContentShots() {}

    /** A small Fabric 1.21.11 modpack on Modrinth (its mods are a few versions old, so updates show). */
    private static final String PACK_PROJECT = "nJ2Rn3SV";

    static void render(Path out) throws Exception {
        if (!Boolean.getBoolean("evade.shots.live") && !"content".equals(System.getProperty("evade.shots.only"))) {
            System.err.println("Mods page screenshots skipped (they need -Devade.shots.live=true)");
            return;
        }
        Path mc = out.resolve("content-mc").toAbsolutePath();
        InstallerCore.deleteTree(mc);
        Files.createDirectories(mc);
        LauncherView v = new LauncherView();
        v.version = EvadeInstaller.launcherVersion();
        v.settings.minecraftDir = mc.toString();
        v.settings.defaultMemoryGb = 4;

        // A real modpack from Modrinth, imported the way the launcher does it.
        ContentApi.Version pv = ContentApi.newest(PACK_PROJECT, List.of("fabric"));
        Path pack = out.resolve("pack.mrpack");
        ContentApi.download(pv.url(), pack, pv.sha512(), java.util.Set.of("cdn.modrinth.com"), null);
        Mrpack.Result r = Mrpack.importPack(v.settings, Launcher.listInstances(v.settings), pack, 4, (f, m) -> { });
        Files.deleteIfExists(pack);
        Launcher.Instance inst = r.instance();
        inst.name = "Survival";
        Launcher.save(inst);
        Path dir = inst.dir;
        InstallerCore core = new InstallerCore();
        // What Evade's install would add (a stand-in Evade jar, real Fabric API), so the managed rows and checks show.
        fakeEvadeJar(dir.resolve("mods").resolve("evade-client-2.3.0.jar"));
        try {
            ContentApi.Version api = ContentApi.newest("P7dR8mSH", List.of("fabric"));
            ContentApi.download(api.url(), dir.resolve("mods").resolve(api.filename()), api.sha512(), java.util.Set.of("cdn.modrinth.com"), null);
        } catch (Exception e) { System.err.println("Fabric API skipped: " + e); }
        for (String slug : new String[]{"zoomify", "appleskin"}) {
            try {
                ContentApi.Project p = ContentApi.project(slug);
                Mods.install(core, dir, new Mods.Hit(p.id(), p.slug(), p.title(), "", "", 0, 0), (f, m) -> { });
            } catch (Exception e) { System.err.println(slug + " skipped: " + e); }
        }
        addonNeedingLibrary(dir.resolve("mods").resolve("my-addon-1.0.jar"));
        for (String slug : new String[]{"fresh-animations", "low-on-fire"}) {
            try {
                ContentApi.Project p = ContentApi.project(slug);
                Content.installPack(dir, Content.Type.RESOURCEPACK, new Mods.Hit(p.id(), p.slug(), p.title(), "", "", 0, 0), null, (f, m) -> { });
            } catch (Exception e) { System.err.println(slug + " skipped: " + e); }
        }
        try {
            ContentApi.Project p = ContentApi.project("complementary-reimagined");
            Content.installPack(dir, Content.Type.SHADER, new Mods.Hit(p.id(), p.slug(), p.title(), "", "", 0, 0), null, (f, m) -> { });
        } catch (Exception e) { System.err.println("shader skipped: " + e); }
        sampleWorlds(dir);

        v.instances = Launcher.listInstances(v.settings);
        v.settings.selected = inst.id;
        for (Launcher.Instance i : v.instances) if (i.id.equals(inst.id)) { i.installed = new InstallerCore.Installed("2.3.0", "0.18.6", "0.141.6+1.21.11"); i.dirty = false; }
        v.mods = Mods.list(dir);
        v.installedProjects = Mods.installedProjects(dir);
        v.page = LauncherView.Page.MODS;
        ContentPage c = v.content;

        // Installed: wait for the checks and the Modrinth lookups.
        settle(v, () -> !c.checking && !c.metas.isEmpty());
        for (Mods.Entry e : v.mods) ModIcons.get(e.file(), null);
        waitIcons();
        save(v, out, "30-mods-installed");
        c.selected.clear();
        for (Mods.Entry e : v.mods) if (!e.managed() && c.selected.size() < 3) c.selected.add(e.file());
        save(v, out, "31-mods-multi-select");
        c.selected.clear();
        c.sheet = "problems";
        save(v, out, "32-mods-check");
        int upd = -1;
        for (int i = 0; i < v.mods.size(); i++) { Updates.Known k = c.known.get(v.mods.get(i).file()); if (k != null && k.hasUpdate()) { upd = i; break; } }
        if (upd >= 0) {
            c.sheet = "mod:" + upd;
            Updates.Known k = c.known.get(v.mods.get(upd).file());
            if (k != null && k.current() != null) {
                c.bodies.remove(k.current().projectId());
                settle(v, () -> c.bodies.containsKey(k.current().projectId()));
            }
            save(v, out, "33-mod-details-update");
        }
        c.sheet = null;
        c.filterField.setText("sodium");
        save(v, out, "34-mods-search");
        c.filterField.setText("");
        c.installedType = Content.Type.RESOURCEPACK;
        settle(v, () -> c.packs != null && !c.packs.isEmpty());
        waitIcons();
        save(v, out, "35-installed-resource-packs");
        c.installedType = Content.Type.SHADER;
        settle(v, () -> c.packs != null && !c.packs.isEmpty());
        waitIcons();
        save(v, out, "36-installed-shaders");
        c.installedType = Content.Type.MOD;

        // Browse: each type.
        c.tab = ContentPage.Tab.BROWSE;
        Content.Type[] types = {Content.Type.MOD, Content.Type.RESOURCEPACK, Content.Type.SHADER, Content.Type.DATAPACK, Content.Type.MODPACK};
        String[] names = {"37-browse-mods", "38-browse-resource-packs", "39-browse-shaders", "40-browse-data-packs", "41-browse-modpacks"};
        for (int i = 0; i < types.length; i++) {
            Content.Type t = types[i];
            if (t == Content.Type.DATAPACK) { c.worlds = Worlds.list(dir); c.datapackWorld = c.worlds.get(0).folder(); }
            javax.swing.SwingUtilities.invokeAndWait(() -> c.click("c-btype:" + t.name()));
            settle(v, () -> !c.searching && !c.hits.isEmpty());
            for (Mods.Hit h : c.hits) ModIcons.url(h.iconUrl(), null);
            waitIcons();
            save(v, out, names[i]);
        }
        javax.swing.SwingUtilities.invokeAndWait(() -> c.click("c-btype:MOD"));
        settle(v, () -> !c.searching && !c.hits.isEmpty());
        c.sheet = "hit:1";
        String pid = c.hits.get(1).projectId();
        settle(v, () -> c.bodies.containsKey(pid));
        waitIcons();
        save(v, out, "42-browse-details");
        c.sheet = null;

        // Worlds.
        c.tab = ContentPage.Tab.WORLDS;
        c.worlds = null;
        settle(v, () -> c.worlds != null);
        for (Worlds.World w : c.worlds) if (w.icon() != null) ModIcons.local(w.icon(), null, Files.getLastModifiedTime(w.icon()).toMillis(), null);
        waitIcons();
        save(v, out, "43-worlds");
        v.openPopupFor("c-wmenu:0");
        save(v, out, "44-worlds-menu");
        v.closeOverlays();
        c.sheet = "datapacks:" + c.worlds.get(0).folder();
        save(v, out, "45-world-datapacks");
        c.sheet = null;

        // Modpacks & sharing, after an export.
        c.tab = ContentPage.Tab.PACKS;
        Mrpack.Result ex = Mrpack.export(inst, out.resolve("content-export.mrpack"), Mrpack.ExportOptions.defaults(), (f, m) -> { });
        c.resultTitle = "Exported Survival.mrpack (" + ex.count() + " files)";
        c.resultNotes = ex.notes();
        save(v, out, "46-modpacks-sharing");
        c.resultTitle = null;
        c.packBusy = "Downloading Cloth Config";
        c.packProgress = 0.46;
        save(v, out, "47-modpack-importing");
        c.packBusy = null;
        c.tab = ContentPage.Tab.INSTALLED;
        v.setUiScale(LauncherView.fitScale(1092, 582));
        save(v, out, "48-small-mods");
        v.setUiScale(1f);
        System.out.println("Mods page screenshots: " + out.toAbsolutePath());
    }

    private static void settle(LauncherView v, java.util.function.BooleanSupplier done) throws Exception {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 60_000) {
            javax.swing.SwingUtilities.invokeAndWait(() -> v.snapshot(1f));
            boolean[] ok = new boolean[1];
            javax.swing.SwingUtilities.invokeAndWait(() -> ok[0] = done.getAsBoolean());
            if (ok[0]) return;
            Thread.sleep(200);
        }
        System.err.println("(gave up waiting)");
    }

    private static void waitIcons() throws InterruptedException {
        for (int i = 0; i < 150 && ModIcons.busy(); i++) Thread.sleep(200);
        Thread.sleep(300);
    }

    private static void save(LauncherView v, Path dir, String name) throws Exception {
        BufferedImage[] img = new BufferedImage[1];
        for (int i = 0; i < 40; i++) javax.swing.SwingUtilities.invokeAndWait(() -> v.snapshot(1f));
        javax.swing.SwingUtilities.invokeAndWait(() -> img[0] = v.snapshot(2f));
        ImageIO.write(img[0], "png", dir.resolve(name + ".png").toFile());
    }

    private static void fakeEvadeJar(Path f) throws Exception {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(f))) {
            z.putNextEntry(new ZipEntry("fabric.mod.json"));
            z.write(("{\"schemaVersion\":1,\"id\":\"evade\",\"version\":\"2.3.0\",\"name\":\"Evade Client\",\"license\":\"ARR\","
                    + "\"description\":\"Server-safe quality-of-life client: HUD, zoom, freelook, waypoints and more.\"}").getBytes());
            z.closeEntry();
        }
    }

    /** A made-up mod that needs a library that isn't installed, so the check has something to find. */
    private static void addonNeedingLibrary(Path f) throws Exception {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(f))) {
            z.putNextEntry(new ZipEntry("fabric.mod.json"));
            z.write(("{\"schemaVersion\":1,\"id\":\"my_addon\",\"version\":\"1.0\",\"name\":\"My Addon\",\"license\":\"MIT\","
                    + "\"description\":\"A small mod added by hand.\",\"depends\":{\"fabricloader\":\"*\",\"midnightlib\":\"*\"}}").getBytes());
            z.closeEntry();
        }
    }

    /** Three worlds with level.dat, an icon and some size (made here, no real world art). */
    private static void sampleWorlds(Path dir) throws Exception {
        String[][] w = {{"New World", "Our Survival World", "0", "-4172144997902289642"}, {"Creative build", "Creative build", "1", "8091867987493326313"},
                {"Hardcore attempt 3", "Hardcore attempt 3", "0", "1234567890"}};
        long now = System.currentTimeMillis();
        int[] colors = {0x3DDC84, 0x3D8BFF, 0xE11D2E};
        for (int i = 0; i < w.length; i++) {
            Path d = dir.resolve("saves").resolve(w[i][0]);
            Files.createDirectories(d.resolve("region"));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("LevelName", w[i][1]);
            data.put("LastPlayed", new ServersDat.Tagged(4, now - (i == 0 ? 3_600_000L : i == 1 ? 3 * 86_400_000L : 26 * 86_400_000L)));
            data.put("GameType", new ServersDat.Tagged(3, Integer.parseInt(w[i][2])));
            data.put("hardcore", new ServersDat.Tagged(1, (byte) (i == 2 ? 1 : 0)));
            data.put("allowCommands", new ServersDat.Tagged(1, (byte) (i == 1 ? 1 : 0)));
            Map<String, Object> wg = new LinkedHashMap<>();
            wg.put("seed", new ServersDat.Tagged(4, Long.parseLong(w[i][3])));
            data.put("WorldGenSettings", wg);
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("Data", data);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(b)) { gz.write(ServersDat.write(root)); }
            Files.write(d.resolve("level.dat"), b.toByteArray());
            Files.write(d.resolve("region").resolve("r.0.0.mca"), new byte[(3 - i) * 4_000_000]);
            BufferedImage icon = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = icon.createGraphics();
            Color c = new Color(colors[i]);
            g.setPaint(new GradientPaint(0, 0, c.brighter(), 64, 64, c.darker().darker()));
            g.fillRect(0, 0, 64, 64);
            g.setColor(new Color(255, 255, 255, 60));
            for (int k = 0; k < 8; k++) g.fillRect((k * 23) % 56, 40 + (k * 7) % 20, 8, 8);
            g.dispose();
            ImageIO.write(icon, "png", d.resolve("icon.png").toFile());
            if (i == 0) {
                Worlds.World wo = Worlds.read(d);
                Worlds.backup(dir, wo, null);
                Path dp = d.resolve("datapacks");
                Files.createDirectories(dp);
                try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(dp.resolve("Graves.zip")))) {
                    z.putNextEntry(new ZipEntry("pack.mcmeta"));
                    z.write("{\"pack\":{\"pack_format\":81,\"description\":\"Keeps your items in a grave where you died\"}}".getBytes());
                    z.closeEntry();
                }
            }
        }
    }
}
