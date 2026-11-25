package net.evadeclient.installer;

import java.io.InputStream;
import java.util.Properties;

/**
 * The Evade Launcher's own version. Set in ONE place, {@code version} in installer/build.gradle: the build stamps it
 * into the jar manifest, the .exe's file info, release list (scripts/build-site.sh) and launcher.properties, which
 * this class reads. Every User-Agent, the About card, "What's new" and the self-update's "never older" check use it.
 */
final class LauncherInfo {
    private LauncherInfo() {}

    /** This launcher's version, or "dev" when run without the build's resources. */
    static final String VERSION = load();

    /** Sent with every request the launcher makes (Modrinth asks API clients to name themselves). */
    static final String USER_AGENT = "EvadeLauncher/" + VERSION + " (+https://evadeclient.com)";

    private static String load() {
        try (InputStream in = LauncherInfo.class.getResourceAsStream("launcher.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version");
                if (v != null && !v.isBlank() && !v.contains("$")) return v.strip();
            }
        } catch (Exception ignored) { }
        return "dev";
    }
}
