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
    /** Favourites can't be deleted (bulk or single) until they're unfavourited. */
    public boolean protectFavorites = true;
    /** Hovering a clip in the library scrubs through it. */
    public boolean hoverPreview = true;

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
    /** "auto" (HEVC when a GPU can do it, else H.264), "h264" (plays everywhere) or "hevc" (smallest files). */
    public String codec = "auto";
    /** Instant replay bitrate ceiling in Mbit/s; 0 = automatic from the quality level. */
    public double replayBitrateMbps = 0;
    /** Full recordings: 0 = same as instant replay (no extra cost), else their own bitrate (a second encode while recording). */
    public double recordBitrateMbps = 0;
    /** Blank = best working encoder. */
    public String encoder = "";
    public boolean captureCursor = true;

    // audio
    public boolean recordSystemAudio = true;
    public String systemAudioDevice = "";
    public boolean recordMic = false;
    public String micDevice = "";
    public double micVolume = 1.0;
    /** A third track for voice chat: pick the device your chat app outputs to (e.g. a VB-Cable). */
    public boolean recordVoice = false;
    public String voiceDevice = "";

    // instant replay
    public boolean replayEnabled = true;
    public int replaySeconds = 30;
    /** Keep capturing this many seconds after the hotkey before saving (post-roll). */
    public int replayAfterSeconds = 0;
    /** Scan new recordings for loud moments and mark them. */
    public boolean autoHighlights = true;
    /** Start the replay buffer as soon as the app opens (otherwise only while a game is running). */
    public boolean replayOnLaunch = false;
    /** Start instant replay when a game starts and stop it when the game closes: zero cost when not playing. */
    public boolean autoReplayForGames = true;

    /** Say "clip that" / "start recording" / "mark that". Recognised offline on this PC. */
    public boolean voiceCommands = false;

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

    // your own watermark on exports (Evade Clips never adds one of its own)
    public boolean watermarkEnabled = false;
    public String watermarkText = "";
    /** A PNG logo; used instead of the text when set. */
    public String watermarkImage = "";
    /** "top-left", "top", "top-right", "left", "center", "right", "bottom-left", "bottom", "bottom-right". */
    public String watermarkPosition = "bottom-right";
    /** Text: height in % of the frame; image: width in % of the frame. */
    public double watermarkSize = 6;
    public double watermarkOpacity = 0.8;
    /** Distance from the edge, % of the frame. */
    public double watermarkMargin = 3;

    // sharing (Evade Clips Share server, see clips-share/)
    public String cloudUrl = "https://clips.evadeclient.com";
    /** Only needed if the server is private (REGISTER_SECRET set). */
    public String cloudRegisterSecret = "";
    public String cloudAccount = "", cloudKey = "", cloudViewToken = "", cloudBase = "";
    /** "off", "favorites" (favourites + exports) or "all" new clips go to the phone gallery automatically. */
    public String cloudSync = "off";
    /** New shares are private (only you) instead of anyone-with-the-link. */
    public boolean sharePrivateByDefault = false;
    /** Bring clips uploaded from the phone gallery into the library automatically. */
    public boolean pullPhoneUploads = true;
    /** Folders to import new videos from automatically: console captures on a USB drive, a phone sync folder… */
    public java.util.List<String> watchFolders = new java.util.ArrayList<>();

    // automatic clips from game events
    public int autoClipBefore = 20;
    public int autoClipAfter = 4;
    public boolean cs2Events = true;
    public boolean cs2HeadshotsOnly = false;
    public boolean leagueEvents = true;
    /** Deaths always get a marker on the timeline; with this they also get their own clip. */
    public boolean clipDeaths = false;
    /** Auto-clip loud moments live in games without an event API (The Finals, Valorant, Fortnite, Apex…). */
    public boolean liveHighlights = true;
    /** "low", "medium", "high". */
    public String liveSensitivity = "medium";
    /** CS2: save each whole round as one clip: "off", "kills" (rounds you got a kill in) or "all". */
    public String cs2FullRounds = "off";
    /** Shared secret CS2 sends with every update, so other programs can't fake events. */
    public String gsiToken = java.util.UUID.randomUUID().toString().replace("-", "");
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
        replaySeconds = Math.max(5, Math.min(600, replaySeconds)); // up to 10 minutes
        replayAfterSeconds = Math.max(0, Math.min(30, replayAfterSeconds));
        if (source == null) source = "screen";
        if (encoder == null) encoder = "";
        if (codec == null) codec = "auto";
        if (voiceDevice == null) voiceDevice = "";
        if (cloudUrl == null) cloudUrl = "";
        if (cloudRegisterSecret == null) cloudRegisterSecret = "";
        if (cloudAccount == null) cloudAccount = "";
        if (cloudKey == null) cloudKey = "";
        if (cloudViewToken == null) cloudViewToken = "";
        if (cloudBase == null) cloudBase = "";
        if (cloudSync == null) cloudSync = "off";
        if (watchFolders == null) watchFolders = new java.util.ArrayList<>();
        if (liveSensitivity == null) liveSensitivity = "medium";
        if (cs2FullRounds == null) cs2FullRounds = "off";
        if (watermarkText == null) watermarkText = "";
        if (watermarkImage == null) watermarkImage = "";
        if (watermarkPosition == null) watermarkPosition = "bottom-right";
        if (gsiToken == null || gsiToken.isBlank()) gsiToken = java.util.UUID.randomUUID().toString().replace("-", "");
        if (ffmpegPath == null) ffmpegPath = "";
        return this;
    }
}
