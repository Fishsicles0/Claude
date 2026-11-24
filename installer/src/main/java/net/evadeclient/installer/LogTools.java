package net.evadeclient.installer;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Game logs for the Logs view: reading {@code logs/latest.log} as it grows (and older {@code *.log.gz} files), each
 * line's level, and masking what shouldn't leave the computer before a log is shown, copied or shared: sign-in
 * tokens and session ids, IP addresses, the Windows / macOS / Linux user name in paths, and the player's name.
 */
final class LogTools {
    private LogTools() {}

    /** At most this many lines are kept for one log (the newest). */
    static final int MAX_LINES = 20_000;

    enum Level { INFO, WARN, ERROR }

    record Line(String text, Level level) {}

    // ------------------------------------------------------------------ masking

    private static final Pattern[] SECRETS = {
            // command-line style: --accessToken abc, --session xyz, --uuid ..., --xuid ..., --clientId ...
            Pattern.compile("(?i)(--(?:accessToken|session|xuid|clientId|uuid)\\s+)\\S+"),
            // key=value / key: value / "key":"value"
            Pattern.compile("(?i)(\"?(?:access_?token|session_?id|sessionid|auth_?token|refresh_?token|token|xuid|client_?id|session)\"?\\s*[:=]\\s*\"?)[^\\s\",}]+"),
            Pattern.compile("(?i)(Session ID is\\s+)\\S+"),
            Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._\\-]+"),
    };
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?![\\d.])");
    private static final Pattern IPV6 = Pattern.compile("(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{1,4}:){4,7}[0-9A-Fa-f]{1,4}(?![0-9A-Fa-f:])");
    private static final Pattern WIN_USER = Pattern.compile("(?i)([A-Z]:[\\\\/]+(?:Users|Documents and Settings)[\\\\/]+)([^\\\\/:*?\"<>|\\r\\n]+)");
    private static final Pattern UNIX_USER = Pattern.compile("(/(?:home|Users)/)([^/\\s]+)");
    private static final Pattern SETTING_USER = Pattern.compile("(Setting user:\\s*)\\S+");

    /**
     * {@code line} with secrets, IP addresses (but loopback), user names in paths and the {@code player} name (when
     * known) replaced by placeholders.
     */
    static String mask(String line, String player) {
        String s = line;
        for (Pattern p : SECRETS) s = p.matcher(s).replaceAll("$1<hidden>");
        s = JWT.matcher(s).replaceAll("<token>");
        s = SETTING_USER.matcher(s).replaceAll("$1<player>");
        s = WIN_USER.matcher(s).replaceAll("$1<user>");
        s = UNIX_USER.matcher(s).replaceAll("$1<user>");
        Matcher m = IPV4.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            boolean keep = m.group(1).equals("127") || m.group().equals("0.0.0.0") || !validIp(m);
            m.appendReplacement(b, keep ? Matcher.quoteReplacement(m.group()) : "x.x.x.x");
        }
        m.appendTail(b);
        s = b.toString();
        s = IPV6.matcher(s).replaceAll("x:x:x:x");
        if (player != null && player.length() >= 3) s = s.replaceAll("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(player) + "(?![A-Za-z0-9_])", "<player>");
        return s;
    }

    private static boolean validIp(Matcher m) {
        for (int i = 1; i <= 4; i++) if (Integer.parseInt(m.group(i)) > 255) return false;
        return true;
    }

    static String maskAll(List<Line> lines, String player) {
        StringBuilder b = new StringBuilder();
        for (Line l : lines) b.append(mask(l.text(), player)).append('\n');
        return b.toString();
    }

    // ------------------------------------------------------------------ levels

    private static final Pattern LEVEL = Pattern.compile("^\\[[^\\]]*\\]\\s*\\[[^\\]]*/(INFO|WARN|ERROR|FATAL|DEBUG|TRACE)\\]");

    /** The level of a log line, or {@code previous} for a continuation line (a stack trace under an error). */
    static Level level(String line, Level previous) {
        Matcher m = LEVEL.matcher(line);
        if (m.find()) {
            return switch (m.group(1)) {
                case "WARN" -> Level.WARN;
                case "ERROR", "FATAL" -> Level.ERROR;
                default -> Level.INFO;
            };
        }
        if (line.startsWith("\tat ") || line.startsWith("Caused by:") || line.startsWith("\t... ") || (!line.startsWith("[") && previous != Level.INFO))
            return previous;
        return Level.INFO;
    }

    static List<Line> parse(List<String> raw) {
        List<Line> out = new ArrayList<>(raw.size());
        Level prev = Level.INFO;
        for (String r : raw) {
            prev = level(r, prev);
            out.add(new Line(r, prev));
        }
        return out;
    }

    /** filter: 0 all, 1 warnings and errors, 2 errors only; {@code query} matched case-insensitively (blank: all). */
    static List<Line> filter(List<Line> lines, int filter, String query) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<Line> out = new ArrayList<>();
        for (Line l : lines) {
            if (filter == 1 && l.level() == Level.INFO) continue;
            if (filter == 2 && l.level() != Level.ERROR) continue;
            if (!q.isEmpty() && !l.text().toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(l);
        }
        return out;
    }

    // ------------------------------------------------------------------ files

    /** The instance's logs, newest first: latest.log, then the dated *.log.gz files. */
    static List<Path> logFiles(Path instance) {
        Path dir = instance.resolve("logs");
        List<Path> out = new ArrayList<>();
        if (Files.isRegularFile(dir.resolve("latest.log"))) out.add(dir.resolve("latest.log"));
        List<Path> old = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.log.gz")) {
            for (Path p : ds) old.add(p);
        } catch (IOException ignored) { }
        old.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        out.addAll(old);
        return out;
    }

    /** A whole log file (gzip or plain), the newest {@link #MAX_LINES} lines. */
    static List<String> readAll(Path f) throws IOException {
        byte[] data;
        if (f.getFileName().toString().endsWith(".gz")) {
            try (InputStream in = new GZIPInputStream(Files.newInputStream(f))) { data = in.readNBytes(64 * 1024 * 1024); }
        } else data = Files.readAllBytes(f);
        List<String> lines = new ArrayList<>(List.of(new String(data, StandardCharsets.UTF_8).split("\\r?\\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        if (lines.size() > MAX_LINES) lines = new ArrayList<>(lines.subList(lines.size() - MAX_LINES, lines.size()));
        return lines;
    }

    /**
     * Follows one growing file (latest.log while the game runs): {@link #poll} returns the lines added since the last
     * call, starting over when the file was replaced by a new session.
     */
    static final class Tail {
        final Path file;
        private long pos;
        private long created = -1;
        private final StringBuilder partial = new StringBuilder();

        Tail(Path file) { this.file = file; }

        /** New complete lines, or null when the file started over (read it again from the start). */
        List<String> poll() throws IOException {
            if (!Files.isRegularFile(file)) return List.of();
            long size = Files.size(file);
            long c;
            try {
                c = ((java.nio.file.attribute.FileTime) Files.getAttribute(file, "creationTime")).toMillis();
            } catch (Exception e) {
                c = 0;
            }
            if (size < pos || (created >= 0 && c != created && c != 0)) {
                pos = 0;
                partial.setLength(0);
                created = c;
                return null;
            }
            created = c;
            if (size == pos) return List.of();
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(pos);
                long n = Math.min(size - pos, 8L * 1024 * 1024);
                byte[] buf = new byte[(int) n];
                raf.readFully(buf);
                pos += n;
                partial.append(new String(buf, StandardCharsets.UTF_8));
            }
            List<String> out = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < partial.length(); i++) {
                if (partial.charAt(i) == '\n') {
                    int end = i > start && partial.charAt(i - 1) == '\r' ? i - 1 : i;
                    out.add(partial.substring(start, end));
                    start = i + 1;
                }
            }
            partial.delete(0, start);
            return out;
        }

        /** Marks everything up to the end as read (after a full read). */
        void skipTo(long p) { pos = p; }
    }
}
