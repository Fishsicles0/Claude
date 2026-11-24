package net.evadeclient.clips;

import net.evadeclient.clips.core.Ffmpeg;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers shared by {@link SelfTest} and {@link EditTests} for the regression checks.
 * <p>
 * Some checks cover features that are added by name (a setting, an edit field, a method). They reach those by
 * reflection, so the tests compile and run on every build: a build that has the feature is checked, one that
 * doesn't yet prints a SKIP line naming what is missing.
 */
final class RegressionSupport {
    private RegressionSupport() {}

    // ------------------------------------------------------------------ features reached by name

    /** A field of {@code c} (or its superclasses), accessible, or null when this build doesn't have it. */
    static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    static boolean hasField(Class<?> c, String name) { return field(c, name) != null; }

    /** Reads a field; null when it doesn't exist. */
    static Object get(Object target, String name) {
        Field f = field(target instanceof Class<?> k ? k : target.getClass(), name);
        if (f == null) return null;
        try { return f.get(Modifier.isStatic(f.getModifiers()) ? null : target); } catch (IllegalAccessException e) { return null; }
    }

    /**
     * Sets a field, converting numbers to the field's type (an int setting can be given 30.0). Returns false when
     * the field doesn't exist or can't take the value.
     */
    static boolean set(Object target, String name, Object value) {
        Field f = field(target.getClass(), name);
        if (f == null) return false;
        try {
            Class<?> t = f.getType();
            Object v = value;
            if (value instanceof Number n) {
                if (t == int.class || t == Integer.class) v = n.intValue();
                else if (t == long.class || t == Long.class) v = n.longValue();
                else if (t == double.class || t == Double.class) v = n.doubleValue();
                else if (t == float.class || t == Float.class) v = n.floatValue();
            }
            f.set(target, v);
            return true;
        } catch (IllegalAccessException | IllegalArgumentException e) {
            return false;
        }
    }

    /** A public or private method by name and parameter types, accessible, or null when this build doesn't have it. */
    static Method method(Class<?> c, String name, Class<?>... params) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /** The first method with this name and parameter count (for features whose exact signature may vary). */
    static Method methodNamed(Class<?> c, String name, int paramCount) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                try { m.setAccessible(true); } catch (RuntimeException e) { return null; }
                return m;
            }
        }
        return null;
    }

    /** Invokes, unwrapping the method's own exception so a check can report it. */
    static Object invoke(Method m, Object target, Object... args) throws Exception {
        try {
            return m.invoke(Modifier.isStatic(m.getModifiers()) ? null : target, args);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception ex) throw ex;
            if (c instanceof Error err) throw err;
            throw e;
        }
    }

    /** A nested class such as {@code EditSpec$Music}, or null. */
    static Class<?> nested(Class<?> outer, String simpleName) {
        for (Class<?> c : outer.getDeclaredClasses()) if (c.getSimpleName().equals(simpleName)) return c;
        return null;
    }

    static void skip(String what, String why) {
        System.out.println("  SKIP  " + what + " (" + why + ")");
    }

    // ------------------------------------------------------------------ media checks

    /** One audio stream as ffprobe reports it. */
    record AudioStream(int index, double duration, boolean isDefault) {}

    /** Every audio stream in a file, with its duration and whether it's the default one players pick. */
    static List<AudioStream> audioStreams(Path file) {
        String out = Ffmpeg.capture(List.of(Ffmpeg.ffprobe(), "-v", "error", "-select_streams", "a", "-show_entries",
                "stream=index,duration:stream_disposition=default", "-of", "csv=p=0", file.toString()), 20);
        List<AudioStream> list = new ArrayList<>();
        for (String line : out.split("\\R")) {
            String[] p = line.trim().split(",");
            if (p.length < 3) continue;
            try {
                list.add(new AudioStream(Integer.parseInt(p[0].trim()), parse(p[1]), p[2].trim().equals("1")));
            } catch (NumberFormatException ignored) {}
        }
        return list;
    }

    /** Integrated loudness (LUFS, EBU R128) of a file's first audio stream; NaN when it can't be measured. */
    static double integratedLoudness(Path file) {
        String out = Ffmpeg.capture(List.of(Ffmpeg.ffmpeg(), "-hide_banner", "-nostats", "-i", file.toString(), "-map", "0:a:0",
                "-af", "ebur128", "-f", "null", "-"), 120);
        Matcher m = Pattern.compile("\\bI:\\s+(-?[0-9.]+) LUFS").matcher(out);
        double last = Double.NaN;
        while (m.find()) last = parse(m.group(1)); // the summary comes last
        return last;
    }

    private static double parse(String s) {
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return Double.NaN; }
    }

    // ------------------------------------------------------------------ recording the ffmpeg commands an export runs

    /**
     * Puts a small wrapper in front of ffmpeg that writes down every command line (and the contents of a filter
     * graph passed as a script file), then runs the real ffmpeg. Checks read the exact command an export ran,
     * whatever code built it. POSIX shells only: on Windows {@link #install} returns null and the checks skip.
     */
    static final class CommandLog implements AutoCloseable {
        private static final char ARG = '\u001f', SCRIPT = '\u001e';
        private final Path logDir;
        private final String restore;

        private CommandLog(Path logDir, String restore) {
            this.logDir = logDir;
            this.restore = restore;
        }

        /** Installs the wrapper; {@code restore} is the ffmpeg setting to go back to on {@link #close()}. */
        static CommandLog install(Path dir, String restore) throws IOException {
            if (Ffmpeg.WINDOWS || Ffmpeg.ffmpeg() == null || Ffmpeg.ffprobe() == null) return null;
            Path real = Path.of(Ffmpeg.ffmpeg()).toAbsolutePath(), realProbe = Path.of(Ffmpeg.ffprobe()).toAbsolutePath();
            Path bin = Files.createDirectories(dir.resolve("ffmpeg-wrapper"));
            Path logs = Files.createDirectories(dir.resolve("ffmpeg-commands"));
            Path wrapper = bin.resolve("ffmpeg"), probe = bin.resolve("ffprobe");
            String script = "#!/bin/sh\n"
                    + "# Evade Clips self-test: write down this ffmpeg command line, then run the real ffmpeg.\n"
                    + "f=$(mktemp " + sh(logs.resolve("cmd.XXXXXXXX").toString()) + ") || exec " + sh(real.toString()) + " \"$@\"\n"
                    + "prev=\n"
                    + "for a in \"$@\"; do\n"
                    + "  printf '%s\\037' \"$a\" >> \"$f\"\n"
                    + "  case \"$prev\" in\n"
                    + "    -filter_complex_script|-/filter_complex) if [ -f \"$a\" ]; then printf '\\036' >> \"$f\"; cat \"$a\" >> \"$f\"; printf '\\037' >> \"$f\"; fi ;;\n"
                    + "  esac\n"
                    + "  prev=$a\n"
                    + "done\n"
                    + "exec " + sh(real.toString()) + " \"$@\"\n";
            Files.writeString(wrapper, script, StandardCharsets.UTF_8);
            if (!wrapper.toFile().setExecutable(true)) return null;
            Files.deleteIfExists(probe);
            Files.createSymbolicLink(probe, realProbe);
            Ffmpeg.configure(wrapper.toString());
            if (!wrapper.toString().equals(Ffmpeg.ffmpeg()) || Ffmpeg.ffprobe() == null) {
                Ffmpeg.configure(restore);
                return null;
            }
            return new CommandLog(logs, restore);
        }

        private static String sh(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

        /** Forgets the commands recorded so far. */
        void clear() throws IOException {
            try (var s = Files.list(logDir)) {
                for (Path p : s.toList()) Files.deleteIfExists(p);
            }
        }

        /** Every recorded command, oldest first; a filter script's contents follow its path as one more argument. */
        List<List<String>> commands() throws IOException {
            List<Path> files;
            try (var s = Files.list(logDir)) {
                files = new ArrayList<>(s.toList());
            }
            files.sort(Comparator.comparingLong(p -> { try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0L; } }));
            List<List<String>> out = new ArrayList<>();
            for (Path f : files) {
                String all = Files.readString(f, StandardCharsets.UTF_8);
                List<String> args = new ArrayList<>();
                int from = 0;
                for (int i = 0; i < all.length(); i++) if (all.charAt(i) == ARG) { args.add(all.substring(from, i)); from = i + 1; }
                if (!args.isEmpty()) out.add(args);
            }
            return out;
        }

        /** The last command that wrote {@code out} (ffmpeg's output file is its last argument), or null. */
        List<String> commandWriting(Path out) throws IOException {
            List<String> found = null;
            String want = out.toString();
            for (List<String> c : commands()) if (!c.isEmpty() && c.get(c.size() - 1).equals(want)) found = c;
            return found;
        }

        /** The filter graph of a command: the -filter_complex text or the script file's contents ("" for none). */
        static String graph(List<String> cmd) {
            if (cmd == null) return "";
            for (int i = 0; i + 1 < cmd.size(); i++) {
                String a = cmd.get(i);
                if (a.equals("-filter_complex")) return cmd.get(i + 1);
                if ((a.equals("-filter_complex_script") || a.equals("-/filter_complex")) && i + 2 < cmd.size()
                        && !cmd.get(i + 2).isEmpty() && cmd.get(i + 2).charAt(0) == SCRIPT) return cmd.get(i + 2).substring(1);
            }
            return "";
        }

        /** Whether the filter graph went in a file instead of on the command line. */
        static boolean usesScript(List<String> cmd) {
            return cmd != null && (cmd.contains("-filter_complex_script") || cmd.contains("-/filter_complex"));
        }

        /**
         * How long the command line would be on Windows (each argument quoted, with room for the path to
         * ffmpeg.exe), not counting a filter script's contents, which aren't on the command line.
         */
        static int windowsLength(List<String> cmd) {
            int n = 60;
            for (String a : cmd) {
                if (!a.isEmpty() && a.charAt(0) == SCRIPT) continue;
                n += a.length() + 3;
            }
            return n;
        }

        /** Index of the ffmpeg input that reads {@code file}, or -1. */
        static int inputIndex(List<String> cmd, Path file) {
            if (cmd == null) return -1;
            int input = 0;
            for (int i = 0; i + 1 < cmd.size(); i++) {
                if (!cmd.get(i).equals("-i")) continue;
                if (cmd.get(i + 1).equals(file.toString())) return input;
                input++;
            }
            return -1;
        }

        @Override public void close() { Ffmpeg.configure(restore); }
    }
}
