package net.evadeclient.clips;

import net.evadeclient.clips.core.Settings;
import net.evadeclient.clips.ui.App;
import net.evadeclient.clips.ui.MainWindow;
import net.evadeclient.clips.ui.Screenshots;
import net.evadeclient.clips.ui.Theme;
import net.evadeclient.clips.ui.Toast;

import javax.swing.SwingUtilities;
import java.nio.file.Path;

/**
 * Evade Clips: records gameplay (always-on instant replay + full recordings), keeps a searchable clip library
 * and edits/exports clips. Flags: {@code --selftest DIR}, {@code --screenshots DIR}, {@code --minimized}.
 */
public final class ClipsApp {
    /** True if another Evade Clips answers on the control port; asks it to show its window unless minimized. */
    static boolean alreadyRunning(int port, boolean minimized) {
        try {
            var http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofMillis(700)).build();
            var status = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/status"))
                    .timeout(java.time.Duration.ofSeconds(2)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            if (status.statusCode() != 200 || !status.body().contains("Evade Clips")) return false;
            if (!minimized) http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/open"))
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Records where this copy is installed (appdir/location.json), so Evade Client can start it when Minecraft
     * launches.
     */
    static void writeLocation() {
        try {
            String exe = System.getProperty("launch4j.exefile");
            java.nio.file.Path jar = java.nio.file.Path.of(ClipsApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            String javaBin = java.nio.file.Path.of(System.getProperty("java.home"), "bin", Ffmpeg_WINDOWS() ? "javaw.exe" : "java").toString();
            var o = new com.google.gson.JsonObject();
            if (exe != null) o.addProperty("exe", exe);
            o.addProperty("jar", jar.toString());
            o.addProperty("java", javaBin);
            java.nio.file.Path f = Settings.appDir().resolve("location.json");
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.writeString(f, o.toString());
        } catch (Exception e) {
            System.err.println("[clips] couldn't record install location: " + e.getMessage());
        }
    }

    private static boolean Ffmpeg_WINDOWS() { return net.evadeclient.clips.core.Ffmpeg.WINDOWS; }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--selftest")) {
            System.exit(SelfTest.run(Path.of(args.length > 1 ? args[1] : "build/selftest")));
        }
        if (args.length > 0 && args[0].equals("--sizetest")) {
            System.exit(SizeTest.run(Path.of(args.length > 1 ? args[1] : "build/sizetest")));
        }
        System.setProperty("flatlaf.uiScale.allowScaleDown", "true");
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        if (args.length > 0 && args[0].equals("--screenshots")) {
            Theme.install();
            Screenshots.run(Path.of(args.length > 1 ? args[1] : "build/screenshots"));
            System.exit(0);
        }
        boolean minimized = java.util.Arrays.asList(args).contains("--minimized");
        Settings settings = Settings.load();
        // One copy at a time: if Evade Clips is already running (e.g. started with Minecraft), bring it forward.
        if (alreadyRunning(settings.controlPort, minimized)) System.exit(0);
        writeLocation();
        App app = new App(settings);
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            app.toast = new Toast();
            MainWindow w = new MainWindow(app);
            if (!minimized) w.setVisible(true);
        });
        new Thread(app::start, "startup").start();
        Runtime.getRuntime().addShutdownHook(new Thread(app::shutdown, "shutdown"));
    }
}
