package net.evadeclient.clips.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes sure a capture ffmpeg never outlives the app. If Evade Clips crashes or is killed, an ffmpeg left behind
 * would keep recording the screen and the mic and fill the disk.
 * <ul>
 *   <li><b>Linux / macOS</b>: ffmpeg starts through a tiny {@code /bin/sh} wrapper that {@code exec}s it, so
 *       it is the very same process with the same pipes (stopping it with "q" works as before). Before the
 *       exec the wrapper leaves a watcher behind that checks every 2 s whether ffmpeg's parent is still this
 *       app, and kills ffmpeg once it isn't. The watcher ends with ffmpeg.</li>
 *   <li><b>Windows</b>: a small helper JVM reads ffmpeg process ids from a pipe this app holds. Windows closes
 *       that pipe when the app dies, however it dies, and the helper then ends every ffmpeg it was told about
 *       (checked by process start time, so a reused process id is never touched).</li>
 * </ul>
 * {@link CaptureEngine} also records each ffmpeg in a pid file and ends a leftover one on the next start, in
 * case a guard couldn't run.
 */
public final class CaptureProcessGuard {
    private CaptureProcessGuard() {}

    /**
     * {@code sh -c WATCH guard <app pid> <ffmpeg name> <ffmpeg> <args...>}: forks the watcher, then becomes
     * ffmpeg. The watcher reads ffmpeg's parent from /proc (Linux) or ps (macOS); it closes its copies of the
     * app's pipes so the app still sees end-of-stream when ffmpeg exits.
     */
    private static final String WATCH = String.join("\n",
            "j=$1; n=$2; shift 2; p=$$",
            "(",
            "  while sleep 2; do",
            "    pp=; nm=",
            "    if [ -r /proc/$p/status ]; then",
            "      while read -r k v; do",
            "        case $k in PPid:) pp=$v ;; Name:) nm=$v ;; esac",
            "      done < /proc/$p/status",
            "    else",
            "      pp=$(ps -o ppid= -p $p 2>/dev/null | tr -d ' ')",
            "      [ -n \"$pp\" ] && [ \"$pp\" != \"$j\" ] && nm=$(ps -o comm= -p $p 2>/dev/null)",
            "    fi",
            "    [ -n \"$pp\" ] || exit 0",
            "    [ \"$pp\" = \"$j\" ] && continue",
            "    case $nm in *ffmpeg*|*\"$n\"*) kill -9 $p ;; esac",
            "    exit 0",
            "  done",
            ") </dev/null >/dev/null 2>&1 &",
            "exec \"$@\"");

    /** Starts {@code pb}'s command (an ffmpeg) so it ends when this app does. */
    static Process start(ProcessBuilder pb) throws IOException {
        List<String> cmd = pb.command();
        boolean guard = !Boolean.getBoolean("clips.noProcessGuard") && !cmd.isEmpty();
        if (guard && !Ffmpeg.WINDOWS && Files.isExecutable(Path.of("/bin/sh"))) {
            List<String> wrapped = new ArrayList<>(List.of("/bin/sh", "-c", WATCH, "evade-clips-guard",
                    String.valueOf(ProcessHandle.current().pid()), processName(cmd.get(0))));
            wrapped.addAll(cmd);
            pb.command(wrapped);
            return pb.start();
        }
        Process p = pb.start();
        if (guard && Ffmpeg.WINDOWS) Helper.watch(p);
        return p;
    }

    /** The name the system shows for the program (Linux keeps 15 characters), following a symlink. */
    private static String processName(String exe) {
        String path = exe;
        try { path = Path.of(exe).toRealPath().toString(); } catch (Exception ignored) {}
        path = path.replace('\\', '/');
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.isEmpty() ? "ffmpeg" : name.length() > 15 ? name.substring(0, 15) : name;
    }

    // ------------------------------------------------------------------ Windows helper

    /** The app side of the Windows helper: starts it on first use and tells it which processes to end. */
    private static final class Helper {
        private static Process helper;
        private static OutputStream pipe;
        private static boolean failed;
        private static final Set<Long> watched = new HashSet<>();

        static synchronized void watch(Process p) {
            if (failed) return;
            long pid = p.pid();
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    if (helper == null || !helper.isAlive()) launch();
                    send("add " + pid);
                    watched.add(pid);
                    p.onExit().thenRun(() -> unwatch(pid));
                    return;
                } catch (IOException e) {
                    helper = null;
                    if (attempt == 1) {
                        // Without the helper a crash can leave ffmpeg running until the next start ends it (pid file).
                        System.err.println("[clips] ffmpeg guard unavailable: " + e.getMessage());
                        failed = true;
                    }
                }
            }
        }

        private static synchronized void unwatch(long pid) {
            if (!watched.remove(pid) || helper == null) return;
            try { send("del " + pid); } catch (IOException ignored) {}
        }

        private static void send(String line) throws IOException {
            pipe.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            pipe.flush();
        }

        private static void launch() throws IOException {
            Path bin = Path.of(System.getProperty("java.home"), "bin");
            Path java = bin.resolve(Ffmpeg.WINDOWS ? "javaw.exe" : "java");
            if (!Files.isRegularFile(java)) java = bin.resolve(Ffmpeg.WINDOWS ? "java.exe" : "java");
            String classPath;
            try {
                classPath = Path.of(CaptureProcessGuard.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            } catch (Exception e) {
                classPath = System.getProperty("java.class.path");
            }
            List<String> cmd = List.of(java.toString(), "-Xms4m", "-Xmx16m", "-Xss256k", "-XX:+UseSerialGC",
                    "-XX:TieredStopAtLevel=1", "-XX:-UsePerfData", "-Djava.awt.headless=true",
                    "-cp", classPath, CaptureProcessGuard.class.getName());
            helper = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            pipe = helper.getOutputStream();
            // A new helper (the old one was ended from outside) takes over everything still running.
            watched.removeIf(pid -> ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true));
            for (long pid : watched) send("add " + pid);
        }
    }

    /**
     * The helper process (Windows): reads "add PID" / "del PID" lines until the app's end of the pipe closes,
     * then ends every process still on its list. {@link ProcessHandle} remembers each process's start time, so
     * a process id the system has handed to something else since is left alone.
     */
    public static void main(String[] args) {
        Map<Long, ProcessHandle> watched = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] a = line.trim().split("\\s+");
                if (a.length < 2) continue;
                long pid;
                try { pid = Long.parseLong(a[1]); } catch (NumberFormatException e) { continue; }
                if (a[0].equals("add")) ProcessHandle.of(pid).ifPresent(h -> watched.put(pid, h));
                else if (a[0].equals("del")) watched.remove(pid);
            }
        } catch (IOException ignored) {
            // A broken pipe means the app is gone, the same as end-of-stream.
        }
        for (ProcessHandle h : watched.values()) {
            try { if (h.isAlive()) h.destroyForcibly(); } catch (RuntimeException ignored) {}
        }
    }
}
