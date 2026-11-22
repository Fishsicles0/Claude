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
        boolean minimized = args.length > 0 && args[0].equals("--minimized");
        Settings settings = Settings.load();
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
