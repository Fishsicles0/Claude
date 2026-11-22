package net.evadeclient.clips.ui;

import net.evadeclient.clips.core.Clip;
import net.evadeclient.clips.core.EditSpec;
import net.evadeclient.clips.core.Settings;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Renders each page of the real UI to PNG (for the website and for checking layouts).
 * <p>
 * With {@code -Dclips.shots.footage=<dir>} (Minecraft gameplay filmed by scripts/film-footage.sh) the library is
 * built from that footage, with a recording, exports and a montage made from it by the real exporter, and the live
 * preview plays it. Without it, it uses the self-test's library and test-pattern source.
 */
public final class Screenshots {
    private Screenshots() {}

    public static void run(Path out) throws Exception {
        Files.createDirectories(out);
        Path data = Path.of(System.getProperty("clips.shots.data", "build/selftest"));
        String fp = System.getProperty("clips.shots.footage");
        Path footage = fp != null && Files.isDirectory(Path.of(fp)) ? Path.of(fp).toAbsolutePath() : null;
        if (footage != null) {
            data = out.resolve("footage-library").toAbsolutePath();
            deleteTree(data);
            System.setProperty("clips.testVideo", footage.resolve("clutch.mp4").toString());
        }
        Settings.overrideAppDir(out.resolve("appdata"));
        Settings s = new Settings();
        s.clipsFolder = data.resolve("clips").toAbsolutePath().toString();
        s.source = "test";
        s.fps = 30;
        s.resolution = 720;
        s.playSound = false;
        s.showToast = false;
        s.controlServer = false;
        s.storageLimitGb = 0;
        s.watermarkEnabled = true;
        s.watermarkText = "@evadeclips";
        App app = new App(s);
        app.library.load();
        if (footage != null) fillFromFootage(app, footage);
        // Make the sample library look lived-in.
        List<Clip> clips = app.library.query("", "all", null, "newest");
        String[][] names = {{"1v3 clutch at mid", "clutch,1v3"}, {"Crystal PvP final", "crystal"}, {"Bridge fight", ""},
                {"Pearl escape", "movement"}, {"Totem pop chain", "totem"}, {"Montage draft", "montage"}};
        for (int i = 0; i < clips.size() && footage == null; i++) {
            Clip c = clips.get(i);
            if (i < names.length) {
                if (c.kind != Clip.Kind.EXPORT) c.title = names[i][0];
                c.tags.clear();
                for (String t : names[i][1].split(",")) if (!t.isBlank()) c.tags.add(t);
            }
            c.favorite = i == 0 || i == 3;
            c.game = i == 2 ? "Valorant" : "Minecraft";
            c.created = System.currentTimeMillis() - i * 3_700_000L;
        }
        app.library.save();
        app.engine.start();
        long t0 = System.currentTimeMillis();
        while (!app.engine.isLive() && System.currentTimeMillis() - t0 < 15000) Thread.sleep(100);
        Thread.sleep(4000);

        MainWindow[] w = new MainWindow[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new MainWindow(app);
            w[0].setSize(1440, 900);
            w[0].setVisible(true);
        });
        Thread.sleep(1500);
        shot(w[0], out.resolve("library.png"));

        SwingUtilities.invokeAndWait(() -> w[0].show("record"));
        app.engine.startRecording("Minecraft");
        Thread.sleep(2500);
        shot(w[0], out.resolve("record.png"));
        app.engine.stopRecording();

        // Editor with a few edits applied.
        Clip c = footage != null ? app.library.query("1v3 clutch", "replays", null, "newest").get(0)
                : app.library.query("", "replays", null, "longest").get(0);
        double d = c.duration;
        c.markers.clear();
        c.markerLabels.clear();
        c.markers.add(d * 0.30);
        c.markerLabels.put(Clip.key(d * 0.30), footage != null ? "Kill Zuri" : "Kill Steve");
        c.markers.add(d * 0.62);
        c.markerLabels.put(Clip.key(d * 0.62), "Triple kill");
        EditSpec e = EditSpec.whole(d);
        e.splitAt(d * 0.08);
        e.segments.remove(0);                                  // trim the start
        e.rampAround(d * 0.62, 0.3, 1.0, 0.9);                 // slow-mo ramp into the triple kill
        e.effects.add(new EditSpec.Effect("hitmarker", d * 0.30, "Hitmarker"));
        e.effects.add(new EditSpec.Effect("punch", d * 0.62, "Boom"));
        e.effects.add(new EditSpec.Effect("shake", d * 0.64, ""));
        e.zooms.add(new EditSpec.ZoomKey(d * 0.40, 1, 0.5, 0.5));
        e.zooms.add(new EditSpec.ZoomKey(d * 0.47, 1.5, 0.55, 0.45));
        e.zooms.add(new EditSpec.ZoomKey(d * 0.55, 1, 0.5, 0.5));
        String[] words = {"one", "more", "and", "we", "WIN", "this"};
        for (int i = 0; i < words.length; i++) {
            double ws = d * 0.34 + i * 0.3;
            e.captions.add(new EditSpec.Caption(words[i], ws, ws + 0.32, "center", i % 2 == 1 ? "highlight" : "pop"));
        }
        e.titleText = "";
        c.edit = e;
        app.library.save();
        MainWindow win = w[0];
        SwingUtilities.invokeAndWait(() -> app.openEditor.accept(c));
        Thread.sleep(2500);
        SwingUtilities.invokeAndWait(() -> { win.editor.showTab("Edit"); win.editor.seek(d * 0.34 + 4 * 0.3 + 0.08); });
        Thread.sleep(2500);
        shot(win, out.resolve("editor.png"));
        SwingUtilities.invokeAndWait(() -> { win.editor.showTab("Effects"); win.editor.seek(d * 0.30 + 0.05); });
        Thread.sleep(2000);
        shot(win, out.resolve("editor-effects.png"));
        SwingUtilities.invokeAndWait(() -> { win.editor.showTab("Text"); win.editor.seek(d * 0.34 + 0.08); });
        Thread.sleep(2000);
        shot(win, out.resolve("editor-text.png"));
        // facecam split: the gameplay's facecam corner goes on top
        e.aspect = "9:16";
        e.fitMode = "split";
        SwingUtilities.invokeAndWait(() -> { c.edit = e; app.library.save(); win.editor.reload(); win.editor.showTab("Frame"); win.editor.seek(d * 0.2); });
        Thread.sleep(2000);
        shot(win, out.resolve("editor-frame.png"));
        SwingUtilities.invokeAndWait(() -> win.editor.showTab("Export"));
        Thread.sleep(800);
        shot(win, out.resolve("editor-export.png"));

        SwingUtilities.invokeAndWait(() -> w[0].show("settings"));
        Thread.sleep(800);
        shot(w[0], out.resolve("settings.png"));
        app.shutdown();
    }

    /** Replays, a session recording, exports and a montage, all from the filmed Minecraft scenes. */
    private static void fillFromFootage(App app, Path footage) throws Exception {
        var lib = app.library;
        String[][] scenes = {
                {"clutch", "1v3 clutch at mid", "clutch,1v3", "Kill Zuri", "Kill Kai", "Triple kill"},
                {"totem", "Totem pop chain", "totem", "Totem pop", "Totem pop", "Kill Noor"},
                {"crystal", "Crystal PvP final", "crystal", "Totem pop", "Kill Steve"},
                {"bridge", "Bridge fight", "bridge", "Kill Alex"},
                {"pearl", "Pearl escape", "movement"},
                {"sunset", "Sunset duel", "duel", "Kill Ari", "Double kill"}};
        long now = System.currentTimeMillis();
        java.util.Map<String, Clip> byName = new java.util.HashMap<>();
        int n = 0;
        for (String[] sc : scenes) {
            Path src = footage.resolve(sc[0] + ".mp4");
            if (!Files.exists(src)) continue;
            Path dest = net.evadeclient.clips.core.Library.unique(lib.folder(Clip.Kind.REPLAY), sc[0] + ".mp4");
            Files.createDirectories(dest.getParent());
            Files.copy(src, dest);
            Clip c = lib.addExisting(dest, Clip.Kind.REPLAY, sc[1], "Minecraft");
            for (String t : sc[2].split(",")) c.tags.add(t);
            for (int k = 3; k < sc.length; k++) {
                double at = c.duration * (0.25 + 0.6 * (k - 3) / Math.max(1, sc.length - 3));
                c.markers.add(at);
                c.markerLabels.put(Clip.key(at), sc[k]);
            }
            c.favorite = n == 0 || n == 2;
            c.created = now - (n == 0 ? 60_000 : n * 2_900_000L);
            byName.put(sc[0], c);
            n++;
        }
        // A full-session recording: every scene back to back.
        Path list = Files.createTempFile("footage", ".txt");
        StringBuilder sb = new StringBuilder();
        for (String[] sc : scenes) if (Files.exists(footage.resolve(sc[0] + ".mp4"))) sb.append("file '").append(footage.resolve(sc[0] + ".mp4")).append("'\n");
        Files.writeString(list, sb);
        Path rec = net.evadeclient.clips.core.Library.unique(lib.folder(Clip.Kind.RECORDING), "ranked-session.mp4");
        Files.createDirectories(rec.getParent());
        net.evadeclient.clips.core.Ffmpeg.run(List.of("-y", "-f", "concat", "-safe", "0", "-i", list.toString(), "-c", "copy", rec.toString()));
        Clip session = lib.addExisting(rec, Clip.Kind.RECORDING, "Ranked session", "Minecraft");
        session.created = now - 9_000_000L;
        double t = 0;
        for (String[] sc : scenes) {
            Clip c = byName.get(sc[0]);
            if (c == null) continue;
            for (int k = 3; k < sc.length; k++) { double at = t + c.markers.get(k - 3); session.markers.add(at); session.markerLabels.put(Clip.key(at), sc[k]); }
            t += c.duration;
        }
        // Exports made by the real exporter: a vertical cut with a caption, a watermarked one, and a montage.
        var o = new net.evadeclient.clips.core.Exporter.Options();
        o.gpu = false;
        Clip clutch = byName.get("clutch"), totem = byName.get("totem");
        if (clutch != null) {
            EditSpec v = EditSpec.whole(clutch.duration);
            v.aspect = "9:16";
            v.fitMode = "crop";
            v.cropX = 0.5;
            EditSpec.Caption cap = new EditSpec.Caption();
            cap.text = "1v3 CLUTCH"; cap.start = 0; cap.end = clutch.duration; cap.position = "top"; cap.style = "meme";
            v.captions.add(cap);
            Path out = net.evadeclient.clips.core.Library.unique(lib.folder(Clip.Kind.EXPORT), "1v3-clutch-vertical.mp4");
            net.evadeclient.clips.core.Exporter.export(clutch.path(lib.root()), clutch, v, o, out, null, null);
            Clip e = lib.addExisting(out, Clip.Kind.EXPORT, "1v3 clutch (vertical)", "Minecraft");
            e.tags.add("shorts"); e.created = now - 1_200_000L; e.sourceId = clutch.id;
        }
        if (totem != null) {
            var wo = new net.evadeclient.clips.core.Exporter.Options();
            wo.gpu = false;
            wo.watermark = new net.evadeclient.clips.core.Exporter.Watermark("@evadeclips", "", "bottom-right", 6, 0.85, 3);
            Path out = net.evadeclient.clips.core.Library.unique(lib.folder(Clip.Kind.EXPORT), "totem-pop-chain.mp4");
            net.evadeclient.clips.core.Exporter.export(totem.path(lib.root()), totem, EditSpec.whole(totem.duration), wo, out, null, null);
            Clip e = lib.addExisting(out, Clip.Kind.EXPORT, "Totem pop chain (watermarked)", "Minecraft");
            e.created = now - 4_000_000L; e.sourceId = totem.id;
        }
        List<Clip> mclips = new java.util.ArrayList<>();
        for (String k : new String[]{"clutch", "crystal", "sunset"}) if (byName.get(k) != null) mclips.add(byName.get(k));
        if (mclips.size() > 1) {
            Path out = net.evadeclient.clips.core.Library.unique(lib.folder(Clip.Kind.EXPORT), "montage.mp4");
            net.evadeclient.clips.core.Exporter.montage(mclips, lib.root(), o, out, null, null);
            Clip e = lib.addExisting(out, Clip.Kind.EXPORT, "Weekend montage", "Minecraft");
            e.tags.add("montage"); e.created = now - 6_500_000L;
        }
        lib.save();
        Thread.sleep(3000); // thumbnails
    }

    private static void deleteTree(Path p) throws java.io.IOException {
        if (!Files.exists(p)) return;
        try (var w = Files.walk(p)) {
            for (Path f : w.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(f);
        }
    }

    private static void shot(MainWindow w, Path file) throws Exception {
        BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            var root = w.getContentPane();
            img[0] = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img[0].createGraphics();
            root.paint(g);
            g.dispose();
        });
        ImageIO.write(img[0], "png", file.toFile());
        System.out.println("wrote " + file);
    }
}
