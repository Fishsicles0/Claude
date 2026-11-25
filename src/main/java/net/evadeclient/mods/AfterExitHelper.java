package net.evadeclient.mods;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Windows only: a jar the game has loaded can't be deleted until the game exits. {@link ModManager} then starts this
 * class in a small separate Java process, with everything it does in plain view on its command line:
 * <pre>javaw -Xmx32m -cp &lt;Evade jar&gt; net.evadeclient.mods.AfterExitHelper &lt;game pid&gt; &lt;mods folder&gt; (&lt;delete&gt; &lt;from&gt; &lt;to&gt;)...</pre>
 * It waits for the game to close, then deletes / moves those files inside the mods folder ("-" stands for none) and
 * exits. Pure JDK (no Minecraft or Fabric classes), no hidden PowerShell, no encoded commands. Whatever it can't do,
 * {@link ModManager#processPending} retries on the next start.
 */
public final class AfterExitHelper {
    private AfterExitHelper() {}

    /** One change: delete a file, and/or move {@code from} to {@code to} (once {@code delete} is gone). */
    public record Op(String delete, String from, String to) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) return;
        long pid = Long.parseLong(args[0]);
        Path mods = Path.of(args[1]);
        ProcessHandle.of(pid).ifPresent(h -> h.onExit().join());
        Thread.sleep(500);
        List<Op> ops = new ArrayList<>();
        for (int i = 2; i + 2 < args.length; i += 3) ops.add(new Op(arg(args[i]), arg(args[i + 1]), arg(args[i + 2])));
        for (Op op : ops) apply(mods, op, 20);
    }

    /** The command-line arguments for {@code ops} after the pid and folder ("-" for a missing name). */
    public static List<String> args(List<Op> ops) {
        List<String> out = new ArrayList<>();
        for (Op op : ops) {
            out.add(op.delete() == null ? "-" : op.delete());
            out.add(op.from() == null ? "-" : op.from());
            out.add(op.to() == null ? "-" : op.to());
        }
        return out;
    }

    /**
     * Does one change in {@code mods}, retrying a locked delete up to {@code tries} times. Names must be plain file
     * names (no folders); anything else is skipped. Returns true when it is done.
     */
    public static boolean apply(Path mods, Op op, int tries) {
        for (String n : new String[]{op.delete(), op.from(), op.to()}) if (n != null && !safeFileName(n)) return false;
        if ((op.from() == null) != (op.to() == null)) return false;
        if (op.delete() != null) {
            Path d = mods.resolve(op.delete());
            for (int i = 0; i < tries && Files.exists(d); i++) {
                try { Files.deleteIfExists(d); } catch (IOException e) { sleep(); }
            }
            if (Files.exists(d)) return false;
        }
        if (op.from() != null) {
            Path f = mods.resolve(op.from()), t = mods.resolve(op.to());
            try {
                if (Files.exists(f) && !Files.exists(t)) Files.move(f, t);
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    /** A plain file name that stays in the mods folder: no separators, no "..", no control characters. */
    public static boolean safeFileName(String n) {
        if (n == null || n.isEmpty() || n.length() > 200 || n.equals(".") || n.equals("..")) return false;
        if (n.startsWith(".") || n.contains("..")) return false;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c < 32 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') return false;
        }
        return true;
    }

    private static String arg(String s) { return s == null || s.equals("-") ? null : s; }

    private static void sleep() {
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
