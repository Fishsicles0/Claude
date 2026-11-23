package net.evadeclient.clips.core;

import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the ffmpeg input arguments for the Minecraft window and the audio devices on each OS. Evade Clips only
 * records Minecraft: Windows records the monitor the Minecraft window is on with Desktop Duplication (ddagrab:
 * cheap, and it works in fullscreen), falling back to grabbing the Minecraft window itself (GDI) when Desktop
 * Duplication isn't available or fails, with DirectShow audio; Linux grabs the
 * Minecraft window's area of the X11 screen with PulseAudio/PipeWire, and macOS (which can only grab whole
 * screens) the main screen, where Minecraft plays.
 */
public final class CaptureSource {
    private CaptureSource() {}

    /** Inputs, the filter graph making [v] and one labelled stream per audio track, and the output size. */
    /** {@code ddagrab}: the video comes from Desktop Duplication (Windows), so a failure there can fall back to GDI. */
    public record Inputs(List<String> args, String filterComplex, List<String> audioLabels, List<String> audioTitles, int outW, int outH, boolean ddagrab) {
        public boolean hasAudio() { return !audioLabels.isEmpty(); }
        public int audioTracks() { return audioLabels.size(); }
    }

    public static List<Rectangle> monitors() {
        List<Rectangle> out = new ArrayList<>();
        try {
            for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle b = d.getDefaultConfiguration().getBounds();
                // Java reports scaled (logical) sizes on HiDPI Windows; capture wants physical pixels.
                double sx = d.getDefaultConfiguration().getDefaultTransform().getScaleX();
                double sy = d.getDefaultConfiguration().getDefaultTransform().getScaleY();
                out.add(new Rectangle((int) Math.round(b.x * sx), (int) Math.round(b.y * sy),
                        (int) Math.round(b.width * sx), (int) Math.round(b.height * sy)));
            }
        } catch (Exception ignored) {}
        if (out.isEmpty()) out.add(new Rectangle(0, 0, 1920, 1080));
        return out;
    }

    /** The primary screen (where the Minecraft window's size is assumed to be until it's found). */
    private static Rectangle mainScreen() {
        List<Rectangle> mons = monitors();
        return mons.get(Math.max(0, Math.min(mons.size() - 1, primaryMonitorIndex())));
    }

    /**
     * True on a Linux Wayland session, where X11 screen capture only sees black. Capture needs the "on Xorg"
     * session there.
     */
    public static boolean linuxWayland() {
        return !Ffmpeg.WINDOWS && !Ffmpeg.MAC
                && ("wayland".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE")) || System.getenv("WAYLAND_DISPLAY") != null);
    }

    /**
     * The size capture will produce with these settings (for the bitrate ceiling and the Record page), worked
     * out without opening audio devices or looking for windows. The Minecraft window's size isn't known up
     * front, so it's assumed to fill the main screen (as it does when you play fullscreen).
     */
    public static Dimension outputSize(Settings s) {
        Rectangle mon = mainScreen();
        int testH = s.resolution >= 1080 ? 1080 : 720, testW = testH == 1080 ? 1920 : 1280;
        int srcW, srcH;
        if (s.source.startsWith("test")) { srcW = testW; srcH = testH; }
        else { srcW = mon.width; srcH = mon.height; }
        srcH = Math.max(2, srcH);
        int oh = s.resolution > 0 && srcH > s.resolution ? s.resolution : srcH;
        int ow = Math.max(2, (int) Math.round(srcW * (oh / (double) srcH) / 2) * 2);
        return new Dimension(ow, oh);
    }

    /** Inputs as capture would use them with the best encoder (for estimates; see {@link #outputSize} too). */
    public static Inputs build(Settings s) { return build(s, "", null); }

    /**
     * Desktop Duplication failed on this PC (old driver, remote desktop, a hybrid-GPU laptop): capture grabs the
     * Minecraft window with GDI instead until the app restarts.
     */
    private static volatile boolean ddagrabFailed;

    /** Called by the capture engine when a Desktop Duplication capture died: the next start uses GDI. */
    static void ddagrabFailed() { ddagrabFailed = true; }

    /** Whether Windows capture uses Desktop Duplication (true unless it isn't available or has failed). */
    static boolean useDdagrab() { return Ffmpeg.WINDOWS && !ddagrabFailed && hasDdagrab(); }

    /**
     * Inputs for a capture that encodes with {@code encoder}. {@code window} is the Minecraft window on Windows
     * (from {@link #findWindow}): its monitor is recorded with Desktop Duplication, or the window itself with GDI
     * as the fallback. Null records the primary monitor (or, with GDI, the window titled "Minecraft").
     */
    static Inputs build(Settings s, String encoder, Window window) {
        List<String> a = new ArrayList<>();
        boolean vaapi = Ffmpeg.isVaapi(encoder);
        if (vaapi) a.addAll(Ffmpeg.vaapiDeviceArgs());
        int fps = s.fps;
        String vf = null; // extra filter on the video input
        int srcH;
        int testH = s.resolution >= 1080 ? 1080 : 720, testW = testH == 1080 ? 1920 : 1280;
        boolean dda = false;

        switch (s.source) {
            case "test", "test-noise", "test-calm", "test-bursts" -> {
                // Self-test sources: a busy test pattern, the same with heavy grain (the worst case for file
                // size), or a slow, calm scene (menus, building, walking around).
                String size = testW + "x" + testH;
                String src = s.source.equals("test-calm") ? "gradients=s=" + size + ":r=" + fps + ":speed=0.01:n=3"
                        : "testsrc2=s=" + size + ":r=" + fps + (s.source.equals("test-noise") ? ",noise=alls=60:allf=t+u" : "");
                // For screenshots: loop a real gameplay video instead (-Dclips.testVideo=<file>).
                String video = System.getProperty("clips.testVideo");
                if (s.source.equals("test") && video != null && Files.exists(Path.of(video)))
                    src = "movie=" + video.replace("\\", "/").replace(":", "\\:") + ":loop=0,setpts=N/FRAME_RATE/TB,scale=" + testW + ":" + testH + ",fps=" + fps;
                a.addAll(List.of("-re", "-f", "lavfi", "-i", src));
                srcH = testH;
            }
            default -> {
                // The Minecraft window, and nothing else.
                if (Ffmpeg.WINDOWS && useDdagrab()) {
                    // Desktop Duplication of the monitor Minecraft is on: captures fullscreen and is far cheaper than GDI.
                    List<Rectangle> mons = monitors();
                    int idx = monitorIndex(window == null ? null : window.rect(), mons, primaryMonitorIndex());
                    a.addAll(List.of("-f", "lavfi", "-i", "ddagrab=output_idx=" + idx + ":framerate=" + fps
                            + ":draw_mouse=" + (s.captureCursor ? 1 : 0)));
                    vf = "hwdownload,format=bgra";
                    srcH = mons.get(Math.min(idx, mons.size() - 1)).height;
                    dda = true;
                } else if (Ffmpeg.WINDOWS) {
                    // Fallback: the Minecraft window itself, through GDI.
                    a.addAll(List.of("-f", "gdigrab", "-framerate", String.valueOf(fps), "-draw_mouse", s.captureCursor ? "1" : "0",
                            "-i", "title=" + (window != null ? window.title() : MINECRAFT_WINDOW)));
                    vf = "crop=trunc(iw/2)*2:trunc(ih/2)*2";
                    srcH = 0;
                } else if (Ffmpeg.MAC) {
                    // AVFoundation can only grab whole screens: the main one, where Minecraft plays.
                    String fr = String.valueOf(fps);
                    a.addAll(List.of("-f", "avfoundation", "-framerate", fr, "-capture_cursor", s.captureCursor ? "1" : "0",
                            "-pixel_format", "uyvy422", "-i", "Capture screen 0:none"));
                    srcH = mainScreen().height;
                } else {
                    Rectangle r = linuxMinecraftWindow();
                    if (r == null) r = mainScreen();
                    String display = System.getenv("DISPLAY");
                    if (display == null || display.isBlank()) display = ":0";
                    a.addAll(List.of("-f", "x11grab", "-framerate", String.valueOf(fps), "-draw_mouse", s.captureCursor ? "1" : "0",
                            "-video_size", even(r.width) + "x" + even(r.height), "-i", display + "+" + r.x + "," + r.y));
                    srcH = even(r.height);
                }
            }
        }

        // Video chain: optional crop to even size, then downscale to the chosen resolution.
        List<String> chain = new ArrayList<>();
        if (vf != null) chain.add(vf);
        if (s.resolution > 0) {
            // A window's size is only known once it's grabbed: shrink big ones, never blow small ones up.
            if (srcH == 0) chain.add("scale=w=-2:h='min(ih\\," + s.resolution + ")':flags=bilinear");
            else if (srcH > s.resolution) chain.add("scale=-2:" + s.resolution + ":flags=bilinear");
        }
        // VA-API encodes from GPU memory: convert to its format and upload the frames.
        chain.add(vaapi ? "format=nv12,hwupload" : "format=yuv420p");
        StringBuilder fc = new StringBuilder("[0:v]" + String.join(",", chain) + "[v]");

        // Audio: game, mic and voice chat each get their own track, so they can be balanced or muted later
        // in the editor (e.g. turn your mic down, drop your friends' voices) without touching the game sound.
        List<String[]> tracks = new ArrayList<>(); // {input label, title, volume filter}
        int idx = 1;
        if (s.source.startsWith("test")) {
            // "test-bursts": quiet noise with a loud 0.7 s burst every 8 s, like gunfights in a calm match.
            String gameSrc = s.source.equals("test-bursts")
                    ? "anoisesrc=a=0.02:c=pink:r=48000,volume='if(between(mod(t\\,8)\\,5\\,5.7)\\,20\\,1)':eval=frame"
                    : "sine=frequency=440:sample_rate=48000";
            if (s.recordSystemAudio) { a.addAll(List.of("-f", "lavfi", "-i", gameSrc)); tracks.add(new String[]{"[" + idx++ + ":a]", "Game", ""}); }
            if (s.recordMic) { a.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=220:sample_rate=48000")); tracks.add(new String[]{"[" + idx++ + ":a]", "Mic", "volume=" + Library.fmt(s.micVolume) + ","}); }
            if (s.recordVoice) { a.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000")); tracks.add(new String[]{"[" + idx++ + ":a]", "Voice chat", ""}); }
        } else {
            Map<String, String> devices = null; // scanned at most once per build
            if (s.recordSystemAudio) {
                String dev = s.systemAudioDevice;
                if (dev.isBlank()) dev = defaultSystemAudio(devices = devicesFor(devices));
                if (dev != null && audioInput(a, dev)) tracks.add(new String[]{"[" + idx++ + ":a]", "Game", ""});
            }
            if (s.recordMic) {
                String dev = s.micDevice;
                if (dev.isBlank()) dev = defaultMic(devices = devicesFor(devices));
                if (dev != null && audioInput(a, dev)) tracks.add(new String[]{"[" + idx++ + ":a]", "Mic", "volume=" + Library.fmt(s.micVolume) + ","});
            }
            if (s.recordVoice && !s.voiceDevice.isBlank() && audioInput(a, s.voiceDevice))
                tracks.add(new String[]{"[" + idx++ + ":a]", "Voice chat", ""});
        }
        String resample = "aresample=48000:async=1000,aformat=channel_layouts=stereo";
        List<String> labels = new ArrayList<>(), titles = new ArrayList<>();
        for (int i = 0; i < tracks.size(); i++) {
            String[] t = tracks.get(i);
            fc.append(";").append(t[0]).append(t[2]).append(resample).append("[a").append(i).append("]");
            labels.add("[a" + i + "]");
            titles.add(t[1]);
        }
        Dimension out = outputSize(s);
        return new Inputs(a, fc.toString(), labels, titles, out.width, out.height, dda);
    }

    private static Map<String, String> devicesFor(Map<String, String> scanned) {
        if (scanned != null) return scanned;
        // Only Windows and macOS pick defaults from the device list; Linux uses the PulseAudio defaults.
        return Ffmpeg.WINDOWS || Ffmpeg.MAC ? audioDevices() : Map.of();
    }

    private static boolean audioInput(List<String> a, String device) {
        if (Ffmpeg.WINDOWS) a.addAll(List.of("-f", "dshow", "-audio_buffer_size", "50", "-i", "audio=" + device));
        else if (Ffmpeg.MAC) a.addAll(List.of("-f", "avfoundation", "-i", ":" + device));
        else a.addAll(List.of("-f", "pulse", "-fragment_size", "1024", "-i", device));
        return true;
    }

    private static int even(int v) { return Math.max(2, v - (v % 2)); }

    private static Boolean ddagrab;

    /** Whether this ffmpeg has Desktop Duplication (Windows 8+ builds do). */
    static synchronized boolean hasDdagrab() {
        if (ddagrab == null) {
            String exe = Ffmpeg.ffmpeg();
            if (exe == null) return false;
            ddagrab = Ffmpeg.WINDOWS && Ffmpeg.capture(List.of(exe, "-hide_banner", "-filters"), 10).contains("ddagrab");
        }
        return ddagrab;
    }

    // ------------------------------------------------------------------ the Minecraft window

    /** What the Minecraft window's title starts with ("Minecraft 1.21.4 - Multiplayer (3rd-party Server)"). */
    public static final String MINECRAFT_WINDOW = "Minecraft";

    /**
     * The Minecraft window's area on the X11 screen (from {@code xwininfo}), clipped to the screen, or null when
     * it can't be found (no xwininfo, or Minecraft has no window yet).
     */
    public static Rectangle linuxMinecraftWindow() {
        String out;
        try { out = Ffmpeg.capture(List.of("xwininfo", "-root", "-tree"), 5); } catch (RuntimeException e) { return null; }
        // 0x3a00007 "Minecraft 1.21.4 - Singleplayer": ("Minecraft" "Minecraft")  1280x720+0+0  +320+180
        Pattern line = Pattern.compile("0x[0-9a-fA-F]+ \"([^\"]*)\".*?(\\d+)x(\\d+)[+-]-?\\d+[+-]-?\\d+\\s+\\+?(-?\\d+)\\+?(-?\\d+)");
        Rectangle best = null;
        for (String l : out.split("\r?\n")) {
            Matcher m = line.matcher(l.trim());
            if (!m.find() || !m.group(1).startsWith(MINECRAFT_WINDOW)) continue;
            Rectangle r = new Rectangle(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            if (r.width < 64 || r.height < 64) continue;
            if (best == null || r.width * (long) r.height > best.width * (long) best.height) best = r;
        }
        if (best == null) return null;
        Rectangle screen = new Rectangle();
        for (Rectangle mon : monitors()) screen = screen.isEmpty() ? new Rectangle(mon) : screen.union(mon);
        Rectangle r = best.intersection(screen);
        return r.width < 64 || r.height < 64 ? null : new Rectangle(r.x, r.y, even(r.width), even(r.height));
    }

    /** The Minecraft window on Windows: its exact title (what GDI capture needs) and its area in pixels, if known. */
    public record Window(String title, Rectangle rect) {}

    /**
     * Lists the visible top-level windows as "pid TAB exe path TAB left,top,right,bottom TAB title", without any
     * user text in the command. The area is the window's visible frame in physical pixels (DWM), or its window
     * rectangle when DWM can't say. The leading "#ok" tells a working PowerShell apart from one that couldn't run.
     */
    private static final String WINDOW_LIST = "$ErrorActionPreference='SilentlyContinue'; "
            + "[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false; '#ok'; "
            + "Add-Type -Namespace EvadeClips -Name Win -MemberDefinition '"
            + "[DllImport(\"dwmapi.dll\")] public static extern int DwmGetWindowAttribute(System.IntPtr h, int a, out RECT r, int s); "
            + "[DllImport(\"user32.dll\")] public static extern bool GetWindowRect(System.IntPtr h, out RECT r); "
            + "[DllImport(\"user32.dll\")] public static extern bool SetProcessDPIAware(); "
            + "public struct RECT { public int L, T, R, B; }'; "
            + "[void][EvadeClips.Win]::SetProcessDPIAware(); "
            + "Get-Process | Where-Object { $_.MainWindowTitle } | ForEach-Object { "
            + "$r = New-Object EvadeClips.Win+RECT; "
            + "if ([EvadeClips.Win]::DwmGetWindowAttribute($_.MainWindowHandle, 9, [ref]$r, 16) -ne 0) { [void][EvadeClips.Win]::GetWindowRect($_.MainWindowHandle, [ref]$r) }; "
            + "[string]$_.Id + [char]9 + [string]$_.Path + [char]9 + [string]$r.L + ',' + [string]$r.T + ',' + [string]$r.R + ',' + [string]$r.B + [char]9 + $_.MainWindowTitle }";

    /**
     * The open window whose title contains {@code text} (ignoring case; normally "Minecraft"), with its exact
     * title and area. Prefers Minecraft's own (Java) window, then an exact match, then one that starts with the
     * text, so "Minecraft" finds "Minecraft 1.21.11 - Multiplayer" rather than a browser tab about it.
     *
     * @throws IOException when no such window is open
     */
    static Window findWindow(String text) throws IOException {
        String want = text == null || text.isBlank() ? MINECRAFT_WINDOW : text.trim();
        // Encoded, so no quoting of the script can go wrong on the way to PowerShell.
        String encoded = java.util.Base64.getEncoder().encodeToString(WINDOW_LIST.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        String out = Ffmpeg.capture(List.of(powershell(), "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded), 15);
        if (!out.contains("#ok")) return new Window(want, null); // PowerShell couldn't run: GDI tries the text as the title
        Window w = pickWindow(out, want, ProcessHandle.current().pid());
        if (w == null) throw new IOException("Open Minecraft with Evade Client to record.");
        return w;
    }

    /** The best window for {@code want} in {@link #WINDOW_LIST}'s output, skipping {@code self}; null if none. */
    public static Window pickWindow(String listing, String want, long self) {
        String w = want.toLowerCase(Locale.ROOT);
        Window best = null;
        int bestScore = -1;
        for (String line : listing.split("\r?\n")) {
            String[] p = line.replace("﻿", "").split("\t", 4);
            if (p.length < 4 || p[3].isBlank()) continue;
            long pid;
            try { pid = Long.parseLong(p[0].trim()); } catch (NumberFormatException e) { continue; }
            if (pid == self) continue; // our own window
            String title = p[3].strip();
            String low = title.toLowerCase(Locale.ROOT);
            if (!low.contains(w)) continue;
            String path = p[1].trim();
            String exe = path.replace('\\', '/');
            exe = exe.substring(exe.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            int score = 0;
            if (exe.startsWith("java") || (!path.isEmpty() && GameDetector.identify(path, "") != null)) score += 4;
            if (low.equals(w)) score += 2;
            else if (low.startsWith(w)) score += 1;
            if (score > bestScore) { best = new Window(title, rect(p[2])); bestScore = score; }
        }
        return best;
    }

    /** "left,top,right,bottom" → a rectangle, or null when it's missing or empty (a minimised window). */
    private static Rectangle rect(String ltrb) {
        String[] v = ltrb.trim().split(",");
        if (v.length != 4) return null;
        try {
            int l = Integer.parseInt(v[0].trim()), t = Integer.parseInt(v[1].trim()), r = Integer.parseInt(v[2].trim()), b = Integer.parseInt(v[3].trim());
            return r - l < 64 || b - t < 64 ? null : new Rectangle(l, t, r - l, b - t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Index of the primary monitor in {@link #monitors()}. */
    static int primaryMonitorIndex() {
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice[] all = ge.getScreenDevices();
            GraphicsDevice primary = ge.getDefaultScreenDevice();
            for (int i = 0; i < all.length; i++) if (all[i] == primary) return i;
        } catch (Exception ignored) {}
        return 0;
    }

    /**
     * The monitor showing most of {@code window} (by overlap, then by its centre), or {@code primary} when the
     * window's area isn't known or is off every monitor.
     */
    public static int monitorIndex(Rectangle window, List<Rectangle> monitors, int primary) {
        int fallback = Math.max(0, Math.min(monitors.size() - 1, primary));
        if (window == null || monitors.isEmpty()) return fallback;
        int best = -1;
        long bestArea = 0;
        for (int i = 0; i < monitors.size(); i++) {
            Rectangle x = monitors.get(i).intersection(window);
            long area = x.isEmpty() ? 0 : (long) x.width * x.height;
            if (area > bestArea) { bestArea = area; best = i; }
        }
        if (best >= 0) return best;
        java.awt.Point c = new java.awt.Point((int) window.getCenterX(), (int) window.getCenterY());
        for (int i = 0; i < monitors.size(); i++) if (monitors.get(i).contains(c)) return i;
        return fallback;
    }

    private static String powershell() {
        String root = System.getenv("SystemRoot");
        if (root != null) {
            Path p = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            if (Files.isRegularFile(p)) return p.toString();
        }
        return "powershell";
    }

    // ------------------------------------------------------------------ audio devices

    private static final Object DEVICE_SCAN = new Object();
    private static volatile Map<String, String> deviceCache;
    private static volatile long deviceCacheAt;

    /**
     * Audio devices by display name → ffmpeg device id. Scanning (DirectShow especially) takes a while, so the
     * list is reused for 30 s; {@link #audioDevices(boolean)} forces a fresh scan.
     */
    public static Map<String, String> audioDevices() { return audioDevices(false); }

    /** Audio devices, scanned again when {@code refresh} (e.g. after plugging in a headset). */
    public static Map<String, String> audioDevices(boolean refresh) {
        Map<String, String> c = deviceCache;
        if (!refresh && c != null && System.currentTimeMillis() - deviceCacheAt < 30_000) return c;
        synchronized (DEVICE_SCAN) {
            // Someone else may have just scanned while we waited.
            c = deviceCache;
            if (!refresh && c != null && System.currentTimeMillis() - deviceCacheAt < 30_000) return c;
            Map<String, String> fresh = scanAudioDevices();
            if (!Ffmpeg.available()) return fresh; // don't remember "nothing" from a missing ffmpeg
            deviceCache = c = Collections.unmodifiableMap(fresh);
            deviceCacheAt = System.currentTimeMillis();
            return c;
        }
    }

    private static Map<String, String> scanAudioDevices() {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Ffmpeg.available()) return out;
        if (Ffmpeg.WINDOWS) {
            String log = Ffmpeg.capture(List.of(Ffmpeg.ffmpeg(), "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy"), 10);
            Matcher m = Pattern.compile("\"([^\"]+)\"\\s*\\(audio\\)").matcher(log);
            while (m.find()) out.put(m.group(1), m.group(1));
        } else if (Ffmpeg.MAC) {
            String log = Ffmpeg.capture(List.of(Ffmpeg.ffmpeg(), "-hide_banner", "-f", "avfoundation", "-list_devices", "true", "-i", ""), 10);
            int audioAt = log.indexOf("audio devices");
            if (audioAt >= 0) {
                Matcher m = Pattern.compile("\\[(\\d+)] (.+)").matcher(log.substring(audioAt));
                while (m.find()) out.put(m.group(2).trim(), m.group(1));
            }
        } else {
            String log = Ffmpeg.capture(List.of("pactl", "list", "short", "sources"), 5);
            for (String line : log.split("\n")) {
                String[] p = line.split("\t");
                if (p.length > 1) out.put(p[1].endsWith(".monitor") ? "Desktop audio: " + p[1] : "Mic: " + p[1], p[1]);
            }
            if (out.isEmpty()) {
                out.put("Desktop audio (default)", "@DEFAULT_MONITOR@");
                out.put("Microphone (default)", "default");
            }
        }
        return out;
    }

    /** A device that records what you hear, or null when this machine has none set up. */
    public static String defaultSystemAudio() {
        return defaultSystemAudio(Ffmpeg.WINDOWS || Ffmpeg.MAC ? audioDevices() : Map.of());
    }

    private static String defaultSystemAudio(Map<String, String> devices) {
        if (Ffmpeg.WINDOWS || Ffmpeg.MAC) {
            for (Map.Entry<String, String> e : devices.entrySet()) {
                String n = e.getKey().toLowerCase(Locale.ROOT);
                if (n.contains("stereo mix") || n.contains("what u hear") || n.contains("virtual-audio-capturer")
                        || n.contains("cable output") || n.contains("blackhole") || n.contains("loopback") || n.contains("wave out"))
                    return e.getValue();
            }
            return null;
        }
        return "@DEFAULT_MONITOR@";
    }

    public static String defaultMic() {
        return defaultMic(Ffmpeg.WINDOWS || Ffmpeg.MAC ? audioDevices() : Map.of());
    }

    private static String defaultMic(Map<String, String> devices) {
        if (Ffmpeg.WINDOWS || Ffmpeg.MAC) {
            for (Map.Entry<String, String> e : devices.entrySet()) {
                String n = e.getKey().toLowerCase(Locale.ROOT);
                if (n.contains("microphone") || n.contains("mic")) return e.getValue();
            }
            return devices.isEmpty() ? null : devices.values().iterator().next();
        }
        return "default";
    }
}
