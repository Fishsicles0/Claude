package net.evadeclient.clips.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Everything the user can change, saved to {@code ~/.evade-clips/settings.json}. */
public final class Settings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path appDirOverride;

    // storage
    public String clipsFolder = defaultClipsFolder().toString();
    /** Oldest non-favourite clips are deleted when the library goes over this. 0 = no limit. */
    public double storageLimitGb = 50;

    // capture
    /** "screen", "window" or "region" ("test" is the built-in pattern used by the self-test). */
    public String source = "screen";
    public int monitor = 0;
    /** Window title to capture when source = window (Windows only; e.g. "Minecraft"). */
    public String windowTitle = "Minecraft";
    public int regionX, regionY, regionW = 1280, regionH = 720;
    public int fps = 60;
    /** Output height: 0 = native, otherwise 1080 / 720 / 1440 ... (width follows the aspect ratio). */
    public int resolution = 1080;
    /** 1 = small files, 2 = balanced, 3 = best. */
    public int quality = 2;
    /** Blank = best working encoder. */
    public String encoder = "";
    public boolean captureCursor = true;

    // audio
    public boolean recordSystemAudio = true;
    public String systemAudioDevice = "";
    public boolean recordMic = false;
    public String micDevice = "";
    public double micVolume = 1.0;

    // instant replay
    public boolean replayEnabled = true;
    public int replaySeconds = 30;
    /** Keep capturing this many seconds after the hotkey before saving (post-roll). */
    public int replayAfterSeconds = 0;
    /** Scan new recordings for loud moments and mark them. */
    public boolean autoHighlights = true;
    /** Start the replay buffer as soon as the app opens. */
    public boolean replayOnLaunch = true;

    // hotkeys (JNativeHook key text, e.g. "F8", "Alt+F10")
    public String hotkeySaveClip = "F8";
    public String hotkeyRecord = "F9";
    public String hotkeyMarker = "F7";

    // behaviour
    public boolean playSound = true;
    public boolean showToast = true;
    public boolean minimizeToTray = true;
    /** Lets Evade Client (and other local tools) save clips: http://127.0.0.1:{port}. */
    public boolean controlServer = true;
    public int controlPort = 47821;
    public String ffmpegPath = "";
    public String defaultGame = "Minecraft";

    public static Path appDir() {
        if (appDirOverride != null) return appDirOverride;
        String home = System.getProperty("user.home");
        if (Ffmpeg.WINDOWS && System.getenv("APPDATA") != null) return Path.of(System.getenv("APPDATA"), "EvadeClips");
        return Path.of(home, ".evade-clips");
    }

    /** Used by the self-test so it never touches the real settings or library. */
    public static void overrideAppDir(Path dir) { appDirOverride = dir; }

    public static Path defaultClipsFolder() {
        Path videos = Path.of(System.getProperty("user.home"), Ffmpeg.MAC ? "Movies" : "Videos");
        return videos.resolve("Evade Clips");
    }

    public Path clipsDir() { return Path.of(clipsFolder); }

    public static Settings load() {
        Path f = appDir().resolve("settings.json");
        try {
            if (Files.exists(f)) {
                Settings s = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Settings.class);
                if (s != null) return s.sanitize();
            }
        } catch (Exception e) {
            System.err.println("[clips] settings unreadable, using defaults: " + e.getMessage());
        }
        return new Settings();
    }

    public synchronized void save() {
        try {
            Path f = appDir().resolve("settings.json");
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling("settings.json.tmp");
            Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("[clips] could not save settings: " + e.getMessage());
        }
    }

    private Settings sanitize() {
        if (clipsFolder == null || clipsFolder.isBlank()) clipsFolder = defaultClipsFolder().toString();
        fps = Math.max(10, Math.min(240, fps));
        quality = Math.max(1, Math.min(3, quality));
        replaySeconds = Math.max(5, Math.min(600, replaySeconds));
        replayAfterSeconds = Math.max(0, Math.min(30, replayAfterSeconds));
        if (source == null) source = "screen";
        if (encoder == null) encoder = "";
        if (ffmpegPath == null) ffmpegPath = "";
        return this;
    }
}
