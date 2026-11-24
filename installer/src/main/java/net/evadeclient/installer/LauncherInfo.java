package net.evadeclient.installer;

/**
 * The Evade Launcher's own version, in one place. {@code installer/build.gradle} sets the same number for the jar
 * manifest and the .exe (its {@code checkLauncherVersion} task fails the build when the two differ), and every
 * User-Agent, the About card and the "What's new" sheet read it from here.
 */
final class LauncherInfo {
    private LauncherInfo() {}

    /** Set once per release, together with {@code version} in installer/build.gradle. */
    static final String VERSION = "2.5.0";

    /** Sent with every request the launcher makes (Modrinth asks API clients to name themselves). */
    static final String USER_AGENT = "EvadeLauncher/" + VERSION + " (+https://evadeclient.com)";
}
