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
 * Renders each page of the real UI to PNG (for the website and for checking layouts). Uses a library made by
 * the self-test, with the test-pattern capture source running so the status reads live.
 */
public final class Screenshots {
    private Screenshots() {}

    public static void run(Path out) throws Exception {
        Files.createDirectories(out);
        Path data = Path.of(System.getProperty("clips.shots.data", "build/selftest"));
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
        // Make the sample library look lived-in.
        List<Clip> clips = app.library.query("", "all", null, "newest");
        String[][] names = {{"1v3 clutch at mid", "clutch,1v3"}, {"Crystal PvP final", "crystal"}, {"Bridge fight", ""},
                {"Pearl escape", "movement"}, {"Totem pop chain", "totem"}, {"Montage draft", "montage"}};
        for (int i = 0; i < clips.size(); i++) {
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
        Clip c = app.library.query("", "replays", null, "longest").get(0);
        EditSpec e = EditSpec.whole(c.duration);
        e.splitAt(c.duration * 0.35);
        e.splitAt(c.duration * 0.55);
        e.splitAt(c.duration * 0.8);
        e.segments.remove(1);
        e.segments.get(1).speed = 0.5;
        e.aspect = "9:16";
        e.cropX = 0.5;
        e.titleText = "1v3 CLUTCH";
        Path facecam = out.resolve("facecam.mp4");
        net.evadeclient.clips.core.Ffmpeg.run(List.of("-f", "lavfi", "-i", "smptehdbars=s=640x360:d=3", "-c:v", "libx264", "-preset", "ultrafast", facecam.toString()));
        EditSpec.Overlay ov = new EditSpec.Overlay();
        ov.file = facecam.toAbsolutePath().toString(); ov.start = 0; ov.length = 3; ov.x = 0.82; ov.y = 0.2; ov.scale = 0.3;
        e.overlays.add(ov);
        e.titleSeconds = 0;
        c.edit = e;
        c.markers.clear();
        c.markers.add(c.duration * 0.62);
        c.markerLabels.put(Clip.key(c.duration * 0.62), "Triple kill");
        c.markers.add(c.duration * 0.12);
        c.markerLabels.put(Clip.key(c.duration * 0.12), "Kill Steve");
        app.library.save();
        SwingUtilities.invokeAndWait(() -> app.openEditor.accept(c));
        Thread.sleep(3500);
        shot(w[0], out.resolve("editor.png"));

        SwingUtilities.invokeAndWait(() -> w[0].show("settings"));
        Thread.sleep(800);
        shot(w[0], out.resolve("settings.png"));
        app.shutdown();
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
