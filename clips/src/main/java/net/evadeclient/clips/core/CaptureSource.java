package net.evadeclient.clips.core;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.ArrayList;
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

    /** Inputs and the filter graph that produces the final [v] and (optionally) [a] streams. */
    public record Inputs(List<String> args, String filterComplex, boolean hasAudio) {}

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

    public static Inputs build(Settings s) {
        List<String> a = new ArrayList<>();
        int fps = s.fps;
        List<Rectangle> mons = monitors();
        Rectangle mon = mons.get(Math.max(0, Math.min(mons.size() - 1, s.monitor)));
        String vf = null; // extra filter on the video input
        int srcH;

        switch (s.source) {
            case "test" -> {
                a.addAll(List.of("-re", "-f", "lavfi", "-i", "testsrc2=s=1280x720:r=" + fps));
                srcH = 720;
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
                            "-i", "title=" + s.windowTitle));
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
        if (Ffmpeg.WINDOWS && !s.source.equals("test") && !s.source.equals("window") && !s.source.equals("region") && hasDdagrab())
            chain.add("hwdownload,format=bgra");
        if (vf != null) chain.add(vf);
        if (s.resolution > 0 && (srcH == 0 || srcH > s.resolution)) chain.add("scale=-2:" + s.resolution + ":flags=bilinear");
        chain.add("format=yuv420p");
        StringBuilder fc = new StringBuilder("[0:v]" + String.join(",", chain) + "[v]");

        // Audio inputs.
        List<String> audioLabels = new ArrayList<>();
        int idx = 1;
        if (s.source.equals("test") && s.recordSystemAudio) {
            a.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000"));
            audioLabels.add("[" + idx++ + ":a]");
        } else {
            if (s.recordSystemAudio) {
                String dev = s.systemAudioDevice.isBlank() ? defaultSystemAudio() : s.systemAudioDevice;
                if (dev != null && audioInput(a, dev)) audioLabels.add("[" + idx++ + ":a]");
            }
            if (s.recordMic) {
                String dev = s.micDevice.isBlank() ? defaultMic() : s.micDevice;
                if (dev != null && audioInput(a, dev)) {
                    fc.append(";[").append(idx).append(":a]volume=").append(Library.fmt(s.micVolume)).append("[mic]");
                    audioLabels.add("[mic]");
                    idx++;
                }
            }
        }
        boolean audio = !audioLabels.isEmpty();
        if (audioLabels.size() == 1) {
            fc.append(";").append(audioLabels.get(0)).append("aresample=48000:async=1000[a]");
        } else if (audioLabels.size() > 1) {
            fc.append(";").append(String.join("", audioLabels))
                    .append("amix=inputs=").append(audioLabels.size()).append(":duration=longest:normalize=0,aresample=48000:async=1000[a]");
        }
        return new Inputs(a, fc.toString(), audio);
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
            ddagrab = Ffmpeg.WINDOWS && Ffmpeg.capture(List.of(Ffmpeg.ffmpeg(), "-hide_banner", "-filters"), 10).contains("ddagrab");
        }
        return ddagrab;
    }

    // ------------------------------------------------------------------ audio devices

    /** Audio devices by display name → ffmpeg device id. */
    public static Map<String, String> audioDevices() {
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
        if (Ffmpeg.WINDOWS || Ffmpeg.MAC) {
            for (Map.Entry<String, String> e : audioDevices().entrySet()) {
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
        if (Ffmpeg.WINDOWS || Ffmpeg.MAC) {
            for (Map.Entry<String, String> e : audioDevices().entrySet()) {
                String n = e.getKey().toLowerCase(Locale.ROOT);
                if (n.contains("microphone") || n.contains("mic")) return e.getValue();
            }
            var all = audioDevices().values();
            return all.isEmpty() ? null : all.iterator().next();
        }
        return "default";
    }
}
