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

    public static Inputs build(Settings s) {
        List<String> a = new ArrayList<>();
        int fps = s.fps;
        List<Rectangle> mons = monitors();
        Rectangle mon = mons.get(Math.max(0, Math.min(mons.size() - 1, s.monitor)));
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
        if (Ffmpeg.WINDOWS && !s.source.startsWith("test") && !s.source.equals("window") && !s.source.equals("region") && hasDdagrab())
            chain.add("hwdownload,format=bgra");
        if (vf != null) chain.add(vf);
        if (s.resolution > 0 && (srcH == 0 || srcH > s.resolution)) chain.add("scale=-2:" + s.resolution + ":flags=bilinear");
        chain.add("format=yuv420p");
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
            if (s.recordSystemAudio) {
                String dev = s.systemAudioDevice.isBlank() ? defaultSystemAudio() : s.systemAudioDevice;
                if (dev != null && audioInput(a, dev)) tracks.add(new String[]{"[" + idx++ + ":a]", "Game", ""});
            }
            if (s.recordMic) {
                String dev = s.micDevice.isBlank() ? defaultMic() : s.micDevice;
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
        // Output size, for the bitrate ceiling (a window's size isn't known up front: assume its monitor).
        int sw = s.source.startsWith("test") ? testW : s.source.equals("region") ? s.regionW : mon.width;
        int sh = srcH > 0 ? srcH : mon.height;
        int oh = s.resolution > 0 && sh > s.resolution ? s.resolution : sh;
        int ow = (int) Math.round(sw * (oh / (double) sh));
        return new Inputs(a, fc.toString(), labels, titles, ow, oh);
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
