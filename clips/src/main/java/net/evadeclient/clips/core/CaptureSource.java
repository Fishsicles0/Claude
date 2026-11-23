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
 * Builds the ffmpeg input arguments for the screen / window / region and audio devices on each OS:
 * Windows uses Desktop Duplication (ddagrab) or GDI and DirectShow audio, macOS AVFoundation, Linux X11 and
 * PulseAudio/PipeWire.
 */
public final class CaptureSource {
    private CaptureSource() {}

    /** Inputs, the filter graph making [v] and one labelled stream per audio track, and the output size. */
    public record Inputs(List<String> args, String filterComplex, List<String> audioLabels, List<String> audioTitles, int outW, int outH) {
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

    private static Rectangle monitor(Settings s) {
        List<Rectangle> mons = monitors();
        return mons.get(Math.max(0, Math.min(mons.size() - 1, s.monitor)));
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
     * out without opening audio devices or looking for windows. A window's size isn't known up front, so the
     * window source assumes its monitor.
     */
    public static Dimension outputSize(Settings s) {
        Rectangle mon = monitor(s);
        int testH = s.resolution >= 1080 ? 1080 : 720, testW = testH == 1080 ? 1920 : 1280;
        int srcW, srcH;
        if (s.source.startsWith("test")) { srcW = testW; srcH = testH; }
        else if (s.source.equals("region")) { srcW = even(s.regionW); srcH = even(s.regionH); }
        else { srcW = mon.width; srcH = mon.height; }
        srcH = Math.max(2, srcH);
        int oh = s.resolution > 0 && srcH > s.resolution ? s.resolution : srcH;
        int ow = Math.max(2, (int) Math.round(srcW * (oh / (double) srcH) / 2) * 2);
        return new Dimension(ow, oh);
    }

    /** Inputs as capture would use them with the best encoder (for estimates; see {@link #outputSize} too). */
    public static Inputs build(Settings s) { return build(s, "", null); }

    /**
     * Inputs for a capture that encodes with {@code encoder}. {@code windowTitle} is the exact title of the
     * window to grab (from {@link #findWindow}) for the Windows "Game window" source; null uses the setting as is.
     */
    static Inputs build(Settings s, String encoder, String windowTitle) {
        List<String> a = new ArrayList<>();
        boolean vaapi = Ffmpeg.isVaapi(encoder);
        if (vaapi) a.addAll(Ffmpeg.vaapiDeviceArgs());
        int fps = s.fps;
        Rectangle mon = monitor(s);
        String vf = null; // extra filter on the video input
        int srcH;
        int testH = s.resolution >= 1080 ? 1080 : 720, testW = testH == 1080 ? 1920 : 1280;

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
            case "region" -> {
                Rectangle r = new Rectangle(s.regionX, s.regionY, even(s.regionW), even(s.regionH));
                screenInput(a, s, r, fps, null);
                // AVFoundation can only grab whole screens, so cut the region out afterwards.
                if (Ffmpeg.MAC) vf = "crop=" + r.width + ":" + r.height + ":" + r.x + ":" + r.y;
                srcH = r.height;
            }
            case "window" -> {
                if (Ffmpeg.WINDOWS) {
                    a.addAll(List.of("-f", "gdigrab", "-framerate", String.valueOf(fps), "-draw_mouse", s.captureCursor ? "1" : "0",
                            "-i", "title=" + (windowTitle != null ? windowTitle : s.windowTitle)));
                    vf = "crop=trunc(iw/2)*2:trunc(ih/2)*2";
                    srcH = 0;
                } else {
                    // No per-window capture outside Windows; fall back to the monitor.
                    screenInput(a, s, mon, fps, s.monitor);
                    srcH = mon.height;
                }
            }
            default -> {
                screenInput(a, s, mon, fps, s.monitor);
                srcH = mon.height;
            }
        }

        // Video chain: optional crop to even size, then downscale to the chosen resolution.
        List<String> chain = new ArrayList<>();
        if (Ffmpeg.WINDOWS && !s.source.startsWith("test") && !s.source.equals("window") && !s.source.equals("region") && hasDdagrab())
            chain.add("hwdownload,format=bgra");
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
        return new Inputs(a, fc.toString(), labels, titles, out.width, out.height);
    }

    private static Map<String, String> devicesFor(Map<String, String> scanned) {
        if (scanned != null) return scanned;
        // Only Windows and macOS pick defaults from the device list; Linux uses the PulseAudio defaults.
        return Ffmpeg.WINDOWS || Ffmpeg.MAC ? audioDevices() : Map.of();
    }

    private static void screenInput(List<String> a, Settings s, Rectangle r, int fps, Integer monitorIndex) {
        String fr = String.valueOf(fps);
        if (Ffmpeg.WINDOWS) {
            if (monitorIndex != null && hasDdagrab()) {
                // Desktop Duplication: captures fullscreen games and is far cheaper than GDI.
                a.addAll(List.of("-f", "lavfi", "-i", "ddagrab=output_idx=" + monitorIndex + ":framerate=" + fr
                        + ":draw_mouse=" + (s.captureCursor ? 1 : 0)));
            } else {
                a.addAll(List.of("-f", "gdigrab", "-framerate", fr, "-draw_mouse", s.captureCursor ? "1" : "0",
                        "-offset_x", String.valueOf(r.x), "-offset_y", String.valueOf(r.y),
                        "-video_size", even(r.width) + "x" + even(r.height), "-i", "desktop"));
            }
        } else if (Ffmpeg.MAC) {
            int screen = monitorIndex == null ? 0 : monitorIndex;
            a.addAll(List.of("-f", "avfoundation", "-framerate", fr, "-capture_cursor", s.captureCursor ? "1" : "0",
                    "-pixel_format", "uyvy422", "-i", "Capture screen " + screen + ":none"));
        } else {
            String display = System.getenv("DISPLAY");
            if (display == null || display.isBlank()) display = ":0";
            a.addAll(List.of("-f", "x11grab", "-framerate", fr, "-draw_mouse", s.captureCursor ? "1" : "0",
                    "-video_size", even(r.width) + "x" + even(r.height), "-i", display + "+" + r.x + "," + r.y));
        }
    }

    private static boolean audioInput(List<String> a, String device) {
        if (Ffmpeg.WINDOWS) a.addAll(List.of("-f", "dshow", "-audio_buffer_size", "50", "-i", "audio=" + device));
        else if (Ffmpeg.MAC) a.addAll(List.of("-f", "avfoundation", "-i", ":" + device));
        else a.addAll(List.of("-f", "pulse", "-fragment_size", "1024", "-i", device));
        return true;
    }

    private static int even(int v) { return Math.max(2, v - (v % 2)); }

    private static Boolean ddagrab;

    static synchronized boolean hasDdagrab() {
        if (ddagrab == null) {
            String exe = Ffmpeg.ffmpeg();
            if (exe == null) return false;
            ddagrab = Ffmpeg.WINDOWS && Ffmpeg.capture(List.of(exe, "-hide_banner", "-filters"), 10).contains("ddagrab");
        }
        return ddagrab;
    }

    // ------------------------------------------------------------------ game window (Windows)

    /**
     * Lists the visible top-level windows as "pid TAB exe path TAB title", without any user text in the
     * command. The leading "#ok" tells a working PowerShell apart from one that couldn't run.
     */
    private static final String WINDOW_LIST = "$ErrorActionPreference='SilentlyContinue'; "
            + "[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false; '#ok'; "
            + "Get-Process | Where-Object { $_.MainWindowTitle } | ForEach-Object { [string]$_.Id + [char]9 + [string]$_.Path + [char]9 + $_.MainWindowTitle }";

    /**
     * The exact title of the open window whose title contains {@code text} (ignoring case), which is what
     * gdigrab needs. Prefers a game's window, then an exact match, then one that starts with the text, so
     * "Minecraft" finds "Minecraft 1.21.11 - Multiplayer" rather than a browser tab about Minecraft.
     *
     * @throws IOException when no such window is open
     */
    static String findWindow(String text) throws IOException {
        String want = text == null ? "" : text.trim();
        if (want.isEmpty()) throw new IOException("Enter the game window's title on the Record page");
        String out = Ffmpeg.capture(List.of(powershell(), "-NoProfile", "-NonInteractive", "-Command", WINDOW_LIST), 10);
        if (!out.contains("#ok")) return want; // PowerShell couldn't run: let gdigrab try the text as the exact title
        String w = want.toLowerCase(Locale.ROOT);
        long self = ProcessHandle.current().pid();
        String best = null;
        int bestScore = -1;
        for (String line : out.split("\r?\n")) {
            String[] p = line.replace("﻿", "").split("\t", 3);
            if (p.length < 3 || p[2].isBlank()) continue;
            long pid;
            try { pid = Long.parseLong(p[0].trim()); } catch (NumberFormatException e) { continue; }
            if (pid == self) continue; // our own window
            String title = p[2].strip();
            String low = title.toLowerCase(Locale.ROOT);
            if (!low.contains(w)) continue;
            String path = p[1].trim();
            String exe = path.replace('\\', '/');
            exe = exe.substring(exe.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            int score = 0;
            if (exe.startsWith("java") || (!path.isEmpty() && GameDetector.identify(path, "") != null)) score += 4;
            if (low.equals(w)) score += 2;
            else if (low.startsWith(w)) score += 1;
            if (score > bestScore) { best = title; bestScore = score; }
        }
        if (best == null) throw new IOException("No window containing \"" + want + "\" is open");
        return best;
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
