package net.evadeclient.clips;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import javax.swing.JOptionPane;

/**
 * Entry point of the .jar, compiled for Java 8 so it runs on any Java a computer might open the file with.
 * If that Java is older than 17 (common when an old Java 8 still owns the .jar file type even though a newer
 * Java is installed), it says so and links to a download instead of failing silently. Otherwise it starts ClipsApp.
 */
public final class Boot {
    private static final String GET_JAVA = "https://adoptium.net/temurin/releases/?version=21";

    private Boot() {}

    public static void main(String[] args) throws Throwable {
        int v = javaVersion();
        if (v < 17) {
            tooOld(v);
            return;
        }
        try {
            Class.forName("net.evadeclient.clips.ClipsApp").getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static int javaVersion() {
        String s = System.getProperty("java.specification.version", "1.8");
        if (s.startsWith("1.")) s = s.substring(2);
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static void tooOld(int v) {
        String msg = "Evade Clips needs Java 17 or newer, but this file was opened with Java " + v + ".\n"
                + "(" + System.getProperty("java.home") + ")\n\n"
                + "You may already have a newer Java installed: your computer just opened the file with an older one.\n"
                + "Installing the latest Java from adoptium.net makes it the default, then open the file again.";
        System.err.println(msg);
        if (GraphicsEnvironment.isHeadless()) System.exit(1);
        try {
            int choice = JOptionPane.showOptionDialog(null, msg, "Evade Clips", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE, null, new String[]{"Get Java", "Close"}, "Get Java");
            if (choice == 0 && Desktop.isDesktopSupported()) Desktop.getDesktop().browse(new URI(GET_JAVA));
        } catch (Exception ignored) {
        }
        System.exit(1);
    }
}
