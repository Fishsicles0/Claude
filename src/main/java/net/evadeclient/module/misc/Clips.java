package net.evadeclient.module.misc;

import net.evadeclient.EvadeClient;
import net.evadeclient.command.CommandManager;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Notifications;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import org.jcodec.api.SequenceEncoder;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.lwjgl.glfw.GLFW;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Clips: never miss a play. With <b>Evade Clips</b> running (the desktop app), clips go there: with sound,
 * GPU encoding, the editor and share links. Without it, Evade records on its own: it keeps the last N seconds of
 * the screen in memory (JPEG-compressed) and writes them to an MP4 (H.264) in .minecraft/evade/clips.
 * <p>
 * Either way it also clips by itself on kills (close ones become one "Triple kill" clip), totem pops of players
 * you're fighting, deaths, wins and advancements, and each event is a named marker on Evade Clips' timeline.
 */
public class Clips extends Module {
    public final KeySetting saveKey = add(new KeySetting("Save Clip Key", GLFW.GLFW_KEY_F8));
    public final ModeSetting recorder = add(new ModeSetting("Recorder", "Auto", "Auto", "Evade Clips", "Built-in"));
    public final NumberSetting seconds = add(new NumberSetting("Clip Length (s)", 30, 10, 120, 5));
    public final NumberSetting fps = add(new NumberSetting("Clip FPS", 30, 15, 60, 15));
    public final ModeSetting quality = add(new ModeSetting("Quality", "720p", "480p", "720p", "1080p"));
    public final BooleanSetting indicator = add(new BooleanSetting("Recording Dot", true));
    private final BooleanSetting kills = add(new BooleanSetting("Auto Clip Kills", true));
    private final BooleanSetting pops = add(new BooleanSetting("Auto Clip Pops", true));
    private final BooleanSetting deaths = add(new BooleanSetting("Auto Clip Deaths", false));
    private final BooleanSetting wins = add(new BooleanSetting("Auto Clip Wins", true));
    private final BooleanSetting advancements = add(new BooleanSetting("Auto Clip Advancements", true));
    private final NumberSetting after = add(new NumberSetting("Seconds After", 4, 0, 15, 1));
    private final KeySetting openKey = add(new KeySetting("Open Clips App", GLFW.GLFW_KEY_UNKNOWN));
    private final BooleanSetting launchApp = add(new BooleanSetting("Start Evade Clips With Game", true));
    private final NumberSetting port = add(new NumberSetting("App Port", 47821, 1024, 65535, 1));

    private static final long MAX_BYTES = 600L * 1024 * 1024;
    private record Frame(long nanos, byte[] jpeg, int w, int h) {}

    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private long bufferedBytes;
    private final AtomicInteger pending = new AtomicInteger();
    private long lastCapture;
    private boolean keyWasDown, openWasDown, launchChecked;
    private volatile boolean saving;
    private volatile String status;
    private volatile long statusUntil;

    private final ExecutorService jpegPool = Executors.newSingleThreadExecutor(r -> daemon(r, "Evade Clips capture"));
    private final ExecutorService mp4Pool = Executors.newSingleThreadExecutor(r -> daemon(r, "Evade Clips encoder"));

    // the Evade Clips app
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build();
    private volatile boolean appRunning;
    /** The app's own global save hotkey (e.g. "F8"): when it matches ours, the app already caught the key press. */
    private volatile String appHotkey = "";
    private long lastPing, lastWarn;

    // auto-clip events (merged while they wait)
    private final Map<UUID, Long> hitAt = new HashMap<>();
    private final Map<UUID, Boolean> wasAlive = new HashMap<>();
    private boolean wasDead;
    private final List<String> events = new ArrayList<>();
    private long firstEvent, fireAt;
    private int killCount;

    public Clips() {
        super("Clips", "Instant replay and auto-clips: in Evade Clips when it's installed, or straight to MP4.", Category.MISC);
        // On by default for people who installed Evade Clips; everyone else turns it on when they want it.
        setEnabled(java.nio.file.Files.exists(appDir().resolve("location.json")));
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    }

    public static Clips active() {
        if (EvadeClient.modules == null) return null;
        Clips c = EvadeClient.modules.get(Clips.class);
        return c != null && c.isEnabled() ? c : null;
    }

    public File folder() { return new File(mc.runDirectory, "clips"); }

    /** Frames currently held in the built-in replay buffer. */
    public int bufferedFrames() { synchronized (frames) { return frames.size(); } }

    /** Clips go to the Evade Clips app (Auto: whenever it's running). */
    public boolean useApp() {
        return switch (recorder.get()) {
            case "Evade Clips" -> true;
            case "Built-in" -> false;
            default -> appRunning;
        };
    }

    @Override
    protected void onDisable() {
        synchronized (frames) { frames.clear(); bufferedBytes = 0; }
    }

    @Override
    public void onAttack(Entity target) {
        if (target instanceof PlayerEntity p) hitAt.put(p.getUuid(), System.currentTimeMillis());
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        if (!launchChecked) { launchChecked = true; ensureAppRunning(); }
        if (now - lastPing > 5000) { lastPing = now; ping(); }

        boolean down = saveKey.isDown() && mc.currentScreen == null;
        if (down && !keyWasDown) {
            // Evade Clips listens for its hotkey everywhere; if it's the same key it has already saved this one.
            boolean appHandled = useApp() && appHotkey.equalsIgnoreCase(KeySetting.keyName(saveKey.get()));
            if (!appHandled) saveNow("Clip", "manual");
        }
        keyWasDown = down;
        boolean open = openKey.isDown();
        if (open && !openWasDown) openApp(null);
        openWasDown = open;

        // Kills: a player we hit in the last 10 s just died.
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            boolean alive = p.isAlive() && !p.isDead();
            Boolean prev = wasAlive.put(p.getUuid(), alive);
            Long hit = hitAt.get(p.getUuid());
            if (Boolean.TRUE.equals(prev) && !alive && hit != null && now - hit < 10_000 && kills.get()) {
                killCount++;
                event("Kill " + p.getName().getString());
            }
        }
        hitAt.values().removeIf(t -> now - t > 15_000);
        if (wasAlive.size() > 256) wasAlive.clear();

        boolean dead = mc.player.isDead();
        if (dead && !wasDead) { if (deaths.get()) event("Death"); else mark("Death"); }
        wasDead = dead;

        if (!events.isEmpty() && now >= fireAt) flush();
    }

    /** Saves a clip now: in Evade Clips, or with the built-in recorder. */
    public void saveNow(String title, String tags) {
        if (useApp()) send(seconds.getInt(), title, tags);
        else save();
    }

    /** From the network mixin: someone popped a totem. Clip it if it's a player we've been fighting. */
    public static void onPop(Entity e) {
        Clips c = get();
        if (c == null || !c.pops.get() || !(e instanceof PlayerEntity p) || p == EvadeClient.mc().player) return;
        Long hit = c.hitAt.get(p.getUuid());
        if (hit != null && System.currentTimeMillis() - hit < 10_000) c.event("Popped " + p.getName().getString());
    }

    private static final java.util.regex.Pattern ADVANCEMENT = java.util.regex.Pattern.compile(
            "^(\\S+) has (made the advancement|completed the challenge|reached the goal) \\[(.+)]$");

    /** From chat: end-of-game messages (as AutoGG recognises them) and your own advancements. */
    public static void onMessage(String text) {
        Clips c = get();
        if (c == null || EvadeClient.mc().player == null) return;
        var m = ADVANCEMENT.matcher(text.trim());
        if (m.matches()) {
            if (c.advancements.get() && m.group(1).equals(EvadeClient.mc().player.getName().getString())) c.event(m.group(3));
            return;
        }
        if (c.wins.get() && !EvadeClient.mc().player.isDead() && AutoGG.matches(text)) c.event("Win");
    }

    private static Clips get() {
        if (EvadeClient.modules == null) return null;
        Clips c = EvadeClient.modules.get(Clips.class);
        return c != null && c.isEnabled() ? c : null;
    }

    private void event(String what) {
        mark(what);
        long now = System.currentTimeMillis();
        if (events.isEmpty()) firstEvent = now;
        events.add(what);
        fireAt = now + after.getInt() * 1000L;
    }

    private void flush() {
        long span = (System.currentTimeMillis() - firstEvent) / 1000;
        String title = killCount >= 2 ? multi(killCount) : events.get(0);
        List<String> tags = new ArrayList<>();
        for (String e : events) {
            String tag = e.split(" ")[0].toLowerCase();
            if (!tags.contains(tag)) tags.add(tag);
        }
        if (killCount >= 2) tags.add("multikill");
        events.clear();
        killCount = 0;
        if (useApp()) send((int) Math.min(600, seconds.getInt() + span), title, String.join(",", tags));
        else save();
    }

    private static String multi(int n) {
        return switch (n) { case 2 -> "Double kill"; case 3 -> "Triple kill"; case 4 -> "Quad kill"; default -> n + "-kill streak"; };
    }

    private void send(int seconds, String title, String tags) {
        String server = mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "Singleplayer";
        String url = "http://127.0.0.1:" + port.getInt() + "/clip?seconds=" + seconds + "&game=Minecraft"
                + "&title=" + enc(title + " · " + server) + "&tag=" + enc(tags);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> mc.execute(() -> {
            if (err != null) {
                if (System.currentTimeMillis() - lastWarn > 60_000) {
                    lastWarn = System.currentTimeMillis();
                    Notifications.push("Clips", "Open Evade Clips to save clips", false);
                }
            } else if (res.statusCode() == 200) {
                Notifications.push("Clip saved", title + "  ·  .clips last to edit", true);
            } else {
                Notifications.push("Clip not saved", res.body().replaceAll(".*\"error\":\"([^\"]*)\".*", "$1"), false);
            }
        }));
    }

    /** Checks whether Evade Clips is running (and which key it saves on). */
    private void ping() {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port.getInt() + "/status")).timeout(Duration.ofSeconds(2)).GET().build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
            boolean up = err == null && res.statusCode() == 200 && res.body().contains("Evade Clips");
            appRunning = up;
            if (up) {
                var m = java.util.regex.Pattern.compile("\"hotkey\":\"([^\"]*)\"").matcher(res.body());
                appHotkey = m.find() ? m.group(1) : "";
                synchronized (frames) { if (!frames.isEmpty() && useApp()) { frames.clear(); bufferedBytes = 0; } }
            }
        });
    }

    // ------------------------------------------------------------------ the Evade Clips app

    /**
     * {@code .clip} (or {@code .clips save}) saves a clip now. {@code .clips} opens Evade Clips, or the clips
     * folder without it; {@code .clips last} opens your newest clip in Evade Clips' editor.
     */
    public static void command(String[] a) {
        Clips c = EvadeClient.modules == null ? null : EvadeClient.modules.get(Clips.class);
        if (c == null) return;
        String sub = a.length > 1 ? a[1].toLowerCase(java.util.Locale.ROOT) : "";
        if (a[0].equalsIgnoreCase("clip") || sub.equals("save")) {
            if (!c.isEnabled()) { CommandManager.print("§7Turn on §cClips§7 first; it keeps the last seconds while it's on."); return; }
            c.saveNow("Clip", "manual");
        } else if (sub.equals("last") || c.appRunning || java.nio.file.Files.exists(appDir().resolve("location.json"))) {
            c.openApp(sub.equals("last") ? "last" : null);
        } else {
            File dir = c.folder();
            dir.mkdirs();
            net.minecraft.util.Util.getOperatingSystem().open(dir);
            CommandManager.print("§7Opening " + dir.getAbsolutePath());
        }
    }

    /** Brings Evade Clips forward (starting it if needed), optionally opening a clip in its editor. */
    void openApp(String clip) {
        String url = "http://127.0.0.1:" + port.getInt() + "/open" + (clip == null ? "" : "?clip=" + enc(clip));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.noBody()).build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete((res, err) -> mc.execute(() -> {
            if (err == null) return;
            if (!launch(false)) Notifications.push("Evade Clips", "Not installed: get it from evadeclient.com", false);
        }));
    }

    /** Starts Evade Clips in the tray when the game starts, so instant replay is already running when you play. */
    private void ensureAppRunning() {
        if (!launchApp.get() || "Built-in".equals(recorder.get())) return;
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port.getInt() + "/status")).timeout(Duration.ofSeconds(2)).GET().build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
            if (err != null || res.statusCode() != 200) mc.execute(() -> launch(true));
        });
    }

    /** Starts the installed app (location recorded by the app / installer). Returns false if it isn't installed. */
    private static boolean launch(boolean minimized) {
        try {
            java.nio.file.Path loc = appDir().resolve("location.json");
            if (!java.nio.file.Files.exists(loc)) return false;
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(loc)).getAsJsonObject();
            List<String> cmd = new ArrayList<>();
            if (o.has("exe") && java.nio.file.Files.exists(java.nio.file.Path.of(o.get("exe").getAsString()))) {
                cmd.add(o.get("exe").getAsString());
            } else if (o.has("jar") && java.nio.file.Files.exists(java.nio.file.Path.of(o.get("jar").getAsString()))) {
                String javaBin = o.has("java") && java.nio.file.Files.exists(java.nio.file.Path.of(o.get("java").getAsString()))
                        ? o.get("java").getAsString() : java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
                cmd.add(javaBin);
                cmd.add("-jar");
                cmd.add(o.get("jar").getAsString());
            } else return false;
            if (minimized) cmd.add("--minimized");
            new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (minimized) Notifications.push("Evade Clips", "Instant replay is starting", true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Same folder Evade Clips uses for its settings. */
    static java.nio.file.Path appDir() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win") && System.getenv("APPDATA") != null) return java.nio.file.Path.of(System.getenv("APPDATA"), "EvadeClips");
        return java.nio.file.Path.of(System.getProperty("user.home"), ".evade-clips");
    }

    /** Drops a named marker in Evade Clips right away (shows on the recording's timeline). */
    private void mark(String label) {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port.getInt() + "/marker?label=" + enc(label)))
                .timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.noBody()).build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding());
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    // ------------------------------------------------------------------ built-in recorder (when Evade Clips isn't running)

    /** Called after every frame is fully drawn (world, HUD and any open screen). */
    public static void onFrameEnd() {
        Clips c = active();
        if (c != null && !c.useApp()) c.capture();
    }

    private void capture() {
        long now = System.nanoTime();
        if (now - lastCapture < (long) (1e9 / fps.get()) || pending.get() >= 3) return;
        var fb = mc.getFramebuffer();
        if (fb == null || fb.textureWidth <= 0 || fb.textureHeight <= 0) return;
        lastCapture = now;
        int target = switch (quality.get()) { case "480p" -> 480; case "1080p" -> 1080; default -> 720; };
        int outH = Math.min(target, fb.textureHeight) & ~1;
        int outW = (int) Math.round(outH * (double) fb.textureWidth / fb.textureHeight) & ~1;
        pending.incrementAndGet();
        try {
            ScreenshotRecorder.takeScreenshot(fb, img -> {
                boolean handedOff = false;
                try (img) {
                    NativeImage small = new NativeImage(outW, outH, false);
                    img.resizeSubRectTo(0, 0, img.getWidth(), img.getHeight(), small);
                    int[] argb = small.copyPixelsArgb();
                    small.close();
                    jpegPool.execute(() -> {
                        try { store(now, toJpeg(argb, outW, outH), outW, outH); }
                        catch (Exception e) { EvadeClient.LOG.warn("Clips: frame encode failed", e); }
                        finally { pending.decrementAndGet(); }
                    });
                    handedOff = true;
                } catch (Exception e) {
                    EvadeClient.LOG.warn("Clips: capture failed", e);
                } finally {
                    if (!handedOff) pending.decrementAndGet();
                }
            });
        } catch (Exception e) {
            pending.decrementAndGet();
        }
    }

    private void store(long nanos, byte[] jpeg, int w, int h) {
        synchronized (frames) {
            frames.addLast(new Frame(nanos, jpeg, w, h));
            bufferedBytes += jpeg.length;
            long keep = (long) ((seconds.get() + 1) * 1e9);
            while (!frames.isEmpty() && (nanos - frames.peekFirst().nanos > keep || bufferedBytes > MAX_BYTES)) {
                bufferedBytes -= frames.removeFirst().jpeg.length;
            }
        }
    }

    private static byte[] toJpeg(int[] argb, int w, int h) throws Exception {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        bi.setRGB(0, 0, w, h, argb, 0, w);
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var ios = ImageIO.createImageOutputStream(out)) {
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.85f);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(bi, null, null), p);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ save

    /** Write the buffered seconds to an MP4 in the background. Completes with the file, or exceptionally. */
    public CompletableFuture<File> save() {
        if (saving) { CommandManager.print("§7Still saving the last clip..."); return CompletableFuture.failedFuture(new IllegalStateException("busy")); }
        List<Frame> snap;
        synchronized (frames) { snap = new ArrayList<>(frames); }
        // only the trailing run of same-size frames (the window may have been resized)
        if (!snap.isEmpty()) {
            Frame last = snap.get(snap.size() - 1);
            int i = snap.size() - 1;
            while (i > 0 && snap.get(i - 1).w == last.w && snap.get(i - 1).h == last.h) i--;
            snap = snap.subList(i, snap.size());
        }
        if (snap.size() < 2) {
            CommandManager.print("§7Nothing recorded yet. Keep §cClips§7 on for a few seconds first.");
            return CompletableFuture.failedFuture(new IllegalStateException("empty"));
        }
        saving = true;
        setStatus("Saving clip...", 600_000);
        folder().mkdirs();
        File file = new File(folder(), "clip-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".mp4");
        int outFps = fps.getInt();
        List<Frame> clip = snap;
        CompletableFuture<File> done = new CompletableFuture<>();
        mp4Pool.execute(() -> {
            long t0 = System.currentTimeMillis();
            try {
                encode(clip, file, outFps);
                double secs = (clip.get(clip.size() - 1).nanos - clip.get(0).nanos) / 1e9;
                EvadeClient.LOG.info("Clips: wrote {} ({} frames, {}s, {} KB) in {} ms", file.getName(), clip.size(),
                        String.format("%.1f", secs), file.length() / 1024, System.currentTimeMillis() - t0);
                mc.execute(() -> {
                    CommandManager.print("§aClip saved§7: " + file.getName() + " (" + String.format("%.0f", secs) + "s). §7Type §d.clips§7 to open the folder, or get Evade Clips to edit and share.");
                    Notifications.push("Clip saved", String.format("%.0fs · %d MB", secs, Math.max(1, file.length() >> 20)), true);
                });
                setStatus("Clip saved", 3000);
                done.complete(file);
            } catch (Exception e) {
                EvadeClient.LOG.error("Clips: saving failed", e);
                mc.execute(() -> CommandManager.print("§cCouldn't save the clip: " + e.getMessage()));
                setStatus("Clip failed", 3000);
                done.completeExceptionally(e);
            } finally {
                saving = false;
            }
        });
        return done;
    }

    /** Lay the captured frames on a constant-rate timeline (so playback speed is right) and encode H.264. */
    private static void encode(List<Frame> clip, File file, int outFps) throws Exception {
        SequenceEncoder enc = SequenceEncoder.createSequenceEncoder(file, outFps);
        long start = clip.get(0).nanos, end = clip.get(clip.size() - 1).nanos, step = (long) (1e9 / outFps);
        int idx = 0, shown = -1;
        Picture pic = null;
        for (long t = start; t <= end; t += step) {
            while (idx + 1 < clip.size() && clip.get(idx + 1).nanos <= t) idx++;
            if (idx != shown) { pic = toPicture(clip.get(idx)); shown = idx; }
            enc.encodeNativeFrame(pic);
        }
        enc.finish();
    }

    private static Picture toPicture(Frame f) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(f.jpeg));
        int w = img.getWidth(), h = img.getHeight();
        int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
        Picture pic = Picture.create(w, h, ColorSpace.RGB);
        byte[] d = pic.getPlaneData(0);
        for (int i = 0, o = 0; i < rgb.length; i++) {
            int p = rgb[i];
            d[o++] = (byte) (((p >> 16) & 255) - 128);
            d[o++] = (byte) (((p >> 8) & 255) - 128);
            d[o++] = (byte) ((p & 255) - 128);
        }
        return pic;
    }

    // ------------------------------------------------------------------ HUD

    private void setStatus(String s, long ms) { status = s; statusUntil = System.currentTimeMillis() + ms; }

    @Override
    public void onHud(DrawContext ctx, float tickDelta) {
        var tr = mc.textRenderer;
        if (indicator.get() && !useApp()) {
            boolean blink = (System.currentTimeMillis() / 600) % 2 == 0 || saving;
            ctx.fill(4, 4, 8, 8, blink ? 0xFFE11D2E : 0x80E11D2E);
        }
        String s = status;
        if (s != null && System.currentTimeMillis() < statusUntil) {
            int w = tr.getWidth(s);
            int x = (ctx.getScaledWindowWidth() - w) / 2;
            ctx.fill(x - 6, 22, x + w + 6, 36, 0xC0000000);
            ctx.fill(x - 6, 22, x + w + 6, 23, 0xFFE11D2E);
            ctx.drawTextWithShadow(tr, s, x, 26, 0xFFFFFFFF);
        }
    }
}
