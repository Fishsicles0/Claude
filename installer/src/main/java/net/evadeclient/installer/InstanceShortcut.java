package net.evadeclient.installer;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * A desktop shortcut per instance, and what it runs: {@code EvadeInstaller --play <id>} updates that instance if
 * needed, hands it to the official Minecraft Launcher (which signs in and runs the game) and closes again.
 * <ul>
 *   <li>Windows: "Evade - &lt;name&gt;.lnk" on the desktop, made through the Windows shell (PowerShell's WScript.Shell).</li>
 *   <li>Linux: an "evade-&lt;id&gt;.desktop" entry in the applications menu, and on the desktop when there is one.</li>
 *   <li>macOS: "Evade - &lt;name&gt;.command" on the desktop.</li>
 * </ul>
 * A launcher that's already open gets the request through a small file in its data folder ({@value #REQUEST}) and plays
 * the instance itself; otherwise the new copy starts, plays and closes.
 */
final class InstanceShortcut {
    private InstanceShortcut() {}

    static final String FLAG = "--play", REQUEST = "play-request.json";
    /** A request older than this is stale (the launcher was closed when the shortcut was used, and opened much later). */
    static final long REQUEST_TTL_MS = 2 * 60_000L;

    // ------------------------------------------------------------------ making shortcuts

    /** The desktop folder ({@code -Devade.desktopDir} overrides it, for tests). */
    static Path desktop() {
        String forced = System.getProperty("evade.desktopDir");
        if (forced != null) return Paths.get(forced);
        String home = System.getProperty("user.home");
        if (InstallerCore.WINDOWS) {
            String profile = System.getenv("USERPROFILE");
            Path od = profile == null ? null : Paths.get(profile, "OneDrive", "Desktop");
            if (od != null && Files.isDirectory(od)) return od; // OneDrive moves the desktop here
            return Paths.get(profile != null ? profile : home, "Desktop");
        }
        return Paths.get(home, "Desktop");
    }

    /** A file name made safe for every OS ("PvP: Ranked" -> "PvP Ranked"). */
    static String safeName(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", " ").replaceAll("\\s{2,}", " ").strip();
        if (s.isEmpty() || s.equals(".") || s.equals("..")) s = "Instance";
        return "Evade - " + (s.length() > 60 ? s.substring(0, 60).strip() : s);
    }

    /** The command a shortcut runs: the .exe itself, or Java with the .jar. */
    static String[] command(Path self, String id) {
        String n = self.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".exe")) return new String[]{self.toString(), FLAG, id};
        String java = Paths.get(System.getProperty("java.home"), "bin", InstallerCore.WINDOWS ? "javaw.exe" : "java").toString();
        return new String[]{java, "-jar", self.toString(), FLAG, id};
    }

    /** A Linux .desktop entry for the instance (pure, so it can be tested). */
    static String desktopEntry(String name, String[] cmd, Path icon) {
        StringBuilder exec = new StringBuilder();
        for (String c : cmd) {
            if (exec.length() > 0) exec.append(' ');
            exec.append('"').append(c.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`")).append('"');
        }
        return "[Desktop Entry]\nType=Application\nName=" + name.replace('\n', ' ') + "\nComment=Play this Evade instance through the Minecraft Launcher\n"
                + "Exec=" + exec + "\n" + (icon != null ? "Icon=" + icon + "\n" : "") + "Terminal=false\nCategories=Game;\n";
    }

    /** A macOS .command script (pure, so it can be tested). */
    static String commandScript(String[] cmd) {
        StringBuilder b = new StringBuilder("#!/bin/sh\n# Evade Launcher: play this instance through the Minecraft Launcher\nexec");
        for (String c : cmd) b.append(" '").append(c.replace("'", "'\\''")).append('\'');
        return b.append(" >/dev/null 2>&1 &\n").toString();
    }

    /**
     * Makes the shortcut for {@code i} on the desktop (and the Linux applications menu). Returns the file made.
     *
     * @param self the launcher's own .exe or .jar (null when it runs from classes: no shortcut can point at it)
     */
    static Path create(Launcher.Instance i, Path self) throws IOException {
        if (self == null) throw new IOException("Shortcuts need the installed launcher (Evade-Installer.exe or EvadeInstaller.jar)");
        String tmp = System.getProperty("java.io.tmpdir", "");
        if (!tmp.isBlank() && self.startsWith(Paths.get(tmp).toAbsolutePath()) && System.getProperty("evade.desktopDir") == null)
            throw new IOException("The launcher is running from a temporary folder. Save it somewhere lasting first, then make the shortcut");
        String[] cmd = command(self, i.id);
        String name = safeName(i.name);
        Path desk = desktop();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win") && System.getProperty("evade.desktopDir") == null) {
            Files.createDirectories(desk);
            Path lnk = desk.resolve(name + ".lnk");
            String args = String.join(" ", java.util.Arrays.copyOfRange(cmd, 1, cmd.length)).replace(self.toString(), "\"" + self + "\"");
            String ps = "$w=New-Object -ComObject WScript.Shell;$s=$w.CreateShortcut('" + q(lnk.toString()) + "');"
                    + "$s.TargetPath='" + q(cmd[0]) + "';$s.Arguments='" + q(args) + "';"
                    + "$s.WorkingDirectory='" + q(self.getParent().toString()) + "';"
                    + "$s.IconLocation='" + q(self.toString().toLowerCase(Locale.ROOT).endsWith(".exe") ? self + ",0" : cmd[0] + ",0") + "';"
                    + "$s.Description='Play " + q(i.name) + " with Evade';$s.Save()";
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", ps).redirectErrorStream(true).start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            try { p.waitFor(20, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (!Files.exists(lnk)) throw new IOException("Windows didn't make the shortcut");
            return lnk;
        }
        if (os.contains("mac") && System.getProperty("evade.desktopDir") == null) {
            Files.createDirectories(desk);
            Path f = desk.resolve(name + ".command");
            Files.writeString(f, commandScript(cmd));
            executable(f);
            return f;
        }
        // Linux (and the self-test): an applications-menu entry, copied to the desktop when there is one.
        Path icon = icon(i);
        String entry = desktopEntry(name, cmd, icon);
        Path out = null;
        if (System.getProperty("evade.desktopDir") == null) {
            Path apps = Paths.get(System.getProperty("user.home"), ".local", "share", "applications");
            Files.createDirectories(apps);
            out = apps.resolve("evade-" + i.id + ".desktop");
            Files.writeString(out, entry);
            executable(out);
        }
        if (Files.isDirectory(desk) || System.getProperty("evade.desktopDir") != null) {
            Files.createDirectories(desk);
            Path f = desk.resolve("evade-" + i.id + ".desktop");
            Files.writeString(f, entry);
            executable(f);
            out = f;
        }
        return out;
    }

    /** The instance's icon as a PNG in the launcher's data folder (its picked icon, else Evade's), or null. */
    private static Path icon(Launcher.Instance i) {
        try {
            InstanceExtras x = InstanceExtras.load(i.dir);
            Path dir = Launcher.dataDir().resolve("icons");
            Files.createDirectories(dir);
            Path f = dir.resolve(i.id + ".png");
            if (x.glyph >= 0) javax.imageio.ImageIO.write(InstanceExtras.renderIcon(x.glyph, x.color, 128), "png", f.toFile());
            else try (var in = InstanceShortcut.class.getResourceAsStream("/evade/icon-128.png")) {
                if (in == null) return null;
                Files.copy(in, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return f;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void executable(Path f) {
        try {
            var perms = EnumSet.copyOf(Files.getPosixFilePermissions(f));
            perms.addAll(EnumSet.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE));
            Files.setPosixFilePermissions(f, perms);
        } catch (IOException | UnsupportedOperationException ignored) { }
    }

    private static String q(String s) { return s.replace("'", "''"); }

    // ------------------------------------------------------------------ the --play request

    /** The instance id after {@code --play}, or null. */
    static String playArg(java.util.List<String> args) {
        int k = args.indexOf(FLAG);
        if (k < 0 || k + 1 >= args.size()) return null;
        String id = args.get(k + 1);
        return id.matches("[a-z0-9-]{1,40}|default") ? id : null;
    }

    /** Leaves the request for whichever launcher picks it up first (this one at start, or one already open). */
    static void request(String id) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("at", System.currentTimeMillis());
        try {
            Files.createDirectories(Launcher.dataDir());
            InstallerCore.writeAtomic(Launcher.dataDir().resolve(REQUEST), InstallerCore.GSON.toJson(o));
        } catch (IOException e) {
            System.err.println("Play request: " + e.getMessage());
        }
    }

    /** Takes a waiting request (once: the file is removed), or null when there is none or it's stale. */
    static String take() {
        Path f = Launcher.dataDir().resolve(REQUEST);
        if (!Files.exists(f)) return null;
        JsonObject o = InstallerCore.readJsonObject(f);
        try { Files.deleteIfExists(f); } catch (IOException ignored) { }
        String id = InstallerCore.str(o, "id");
        long at;
        try { at = o.get("at").getAsLong(); } catch (RuntimeException e) { return null; }
        return id != null && System.currentTimeMillis() - at < REQUEST_TTL_MS ? id : null;
    }
}
