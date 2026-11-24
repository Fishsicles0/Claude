package net.evadeclient.module.misc;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import net.evadeclient.EvadeClient;
import net.evadeclient.command.CommandManager;
import net.evadeclient.module.Category;
import net.evadeclient.module.Module;
import net.evadeclient.setting.BooleanSetting;
import net.evadeclient.setting.KeySetting;
import net.evadeclient.setting.ModeSetting;
import net.evadeclient.setting.NumberSetting;
import net.evadeclient.util.Notifications;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.util.math.Vec3d;
import org.jcodec.api.SequenceEncoder;
import org.jcodec.common.Codec;
import org.jcodec.common.Format;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.Rational;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clips: never miss a play. With <b>Evade Clips</b> running (the desktop app), clips go there: with sound,
 * GPU encoding, the editor and share links. Without it, Evade records on its own: it keeps the last N seconds of
 * the screen in memory (JPEG-compressed) and writes them to an MP4 (H.264) in .minecraft/evade/clips.
 * <p>
 * Either way it also clips by itself on kills, melee or ranged (close ones become one "Triple kill" clip), totem
 * pops of players you're fighting, deaths, wins and advancements, and each event is a named marker on Evade Clips'
 * timeline. Evade Clips decides how long its clips are; the length, FPS and quality settings here are for the
 * built-in recorder.
 * <p>
 * The built-in recorder scales each frame down on the GPU and reads it back asynchronously, so the render thread
 * only copies one small finished frame; JPEG and MP4 encoding run on their own background threads.
 */
public class Clips extends Module {
    public final KeySetting saveKey = add(new KeySetting("Save Clip Key", GLFW.GLFW_KEY_F8));
    public final ModeSetting recorder = add(new ModeSetting("Recorder", "Auto", "Auto", "Evade Clips", "Built-in"));
    public final NumberSetting seconds = add(new NumberSetting("Clip Length (s)", 30, 10, 120, 5)
            .describe("Built-in recorder only. Evade Clips uses the replay length set in the app."));
    public final NumberSetting fps = add(new NumberSetting("Clip FPS", 30, 15, 60, 15)
            .describe("Built-in recorder only. Evade Clips records at the frame rate set in the app."));
    public final ModeSetting quality = add(new ModeSetting("Quality", "720p", "480p", "720p", "1080p")
            .describe("Built-in recorder only. Evade Clips records at the quality set in the app."));
    /** While the built-in recorder runs: "● REC" in the window title (and the in-game dot when overlays are shown). */
    public final BooleanSetting indicator = add(new BooleanSetting("Recording Dot", true));
    /**
     * The built-in recorder films the finished frame, HUD included. Off (the default): Evade's recording dot, status
     * banner and pop-ups stay off screen while it records, so they aren't burned into every clip.
     */
    public final BooleanSetting showOverlays = add(new BooleanSetting("Show Overlays In Clips", false));
    private final BooleanSetting kills = add(new BooleanSetting("Auto Clip Kills", true));
    private final BooleanSetting pops = add(new BooleanSetting("Auto Clip Pops", true));
    private final BooleanSetting deaths = add(new BooleanSetting("Auto Clip Deaths", false));
    private final BooleanSetting wins = add(new BooleanSetting("Auto Clip Wins", true)
            .describe("Clip games you win. Other end-of-game lines only drop a marker on Evade Clips' timeline."));
    private final BooleanSetting advancements = add(new BooleanSetting("Auto Clip Advancements", true));
    private final NumberSetting after = add(new NumberSetting("Seconds After", 4, 0, 15, 1)
            .describe("How long an auto clip keeps recording after the moment that triggered it."));
    private final KeySetting openKey = add(new KeySetting("Open Clips App", GLFW.GLFW_KEY_UNKNOWN));
    private final BooleanSetting launchApp = add(new BooleanSetting("Start Evade Clips With Game", true));
    private final NumberSetting port = add(new NumberSetting("App Port", 47821, 1024, 65535, 1)
            .describe("Evade Clips' control port. Read from the app when it's installed; only used if it can't be."));

    /** The replay buffer's cap: 600 MB, or a sixth of the game's memory when it has less than 3.6 GB. */
    private static final long MAX_BYTES = Math.min(600L << 20, Runtime.getRuntime().maxMemory() / 6);
    /** Frames between capture and the buffer (GPU readback plus JPEG) at once; more are skipped, not queued. */
    private static final int MAX_PENDING = 3;
    /** Clips encoding or waiting to encode at once: the one being written and two more. */
    private static final int MAX_SAVES = 3;
    /** Auto-clips never reach further back than this past {@link #after} (a long streak keeps the end). */
    private static final int MAX_SPAN_EXTRA = 15;

    private static Clips instance;

    /** One captured frame. {@code buffered} and {@code holders} are guarded by {@link #frames}. */
    private static final class Frame {
        final long nanos;
        final byte[] jpeg;
        final int w, h;
        /** Still in the replay buffer. */
        boolean buffered = true;
        /** Clips waiting for (or being written with) this frame. */
        int holders;

        Frame(long nanos, byte[] jpeg, int w, int h) {
            this.nanos = nanos;
            this.jpeg = jpeg;
            this.w = w;
            this.h = h;
        }
    }

    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    /**
     * Bytes of every frame still referenced: by the buffer, or by a clip that hasn't been written yet. A clip waiting
     * to encode keeps its frames alive after the buffer moves on, so they count against the cap until they're written.
     */
    private long heldBytes;
    /** The oldest buffered frame is the oldest only because the byte cap pushed earlier ones out. */
    private boolean capCut;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger saves = new AtomicInteger();
    private final Set<String> reservedNames = ConcurrentHashMap.newKeySet();
    private long nextCapture, pendingSince;
    private boolean keyWasDown, openWasDown, launchChecked, cpuWarned, frameErrorLogged;
    private volatile String status;
    private volatile long statusUntil;

    private final ExecutorService jpegPool = Executors.newSingleThreadExecutor(r -> daemon(r, "Evade Clips capture"));
    private final ExecutorService mp4Pool = Executors.newSingleThreadExecutor(r -> daemon(r, "Evade Clips encoder"));

    // GPU capture (render thread only): the frame is scaled down in one or more blit passes, then read back
    // into a pooled buffer without stalling the render thread.
    private GpuTexture[] stageTex;
    private GpuTextureView[] stageView;
    private int stageSrcW, stageSrcH, stageOutW, stageOutH;
    /** Bumped whenever the stages are rebuilt, so readback buffers of an older size are closed rather than reused. */
    private int gpuGeneration;
    private final ArrayDeque<GpuBuffer> spareReadback = new ArrayDeque<>();
    private boolean gpuFailed;
    /** Pixel arrays handed from the render thread to the JPEG thread, reused instead of allocated per frame. */
    private final ConcurrentLinkedQueue<int[]> sparePixels = new ConcurrentLinkedQueue<>();

    // JPEG thread only
    private BufferedImage jpegCanvas;
    private ImageWriter jpegWriter;
    private ImageWriteParam jpegParam;
    private final ByteArrayOutputStream jpegOut = new ByteArrayOutputStream(256 * 1024);

    // window title (render thread only): the built-in recorder's state shows there instead of in the frame
    private String titleShown, titleBase;
    private long titleCheckedAt, titleSetAt;

    // the Evade Clips app
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build();
    private volatile boolean appRunning;
    /**
     * The app's own global save hotkey (e.g. "F8"), or "" while its keyboard hook isn't running (or the app isn't):
     * when it matches ours, the app already caught the key press.
     */
    private volatile String appHotkey = "";
    /** The newest clip in the app's library as /status reports it; null when the app doesn't report it (older apps). */
    private volatile String appLastClip;
    private long lastPing, lastWarn, lastLaunch;
    /** Clips already announced in game (by a /clip reply or an F8 confirmation), so none is announced twice. Client thread. */
    private final LinkedHashSet<String> announcedClips = new LinkedHashSet<>();
    /** The control port Evade Clips recorded in location.json (0: none), re-read every few seconds. */
    private int locationPort;
    private long locationCheckedAt;

    /** Where a manual or automatic save came from: what to say when it fails, and where. */
    private enum Origin { KEY, COMMAND, AUTO }

    private static final String SAVED_IN_APP = "Saved in Evade Clips  ·  .clips last to edit";

    // auto-clip events (merged while they wait)
    private final Map<UUID, Long> hitAt = new HashMap<>();
    private final Map<UUID, Boolean> wasAlive = new HashMap<>();
    private boolean wasDead;
    private ClientWorld tickWorld;
    private final List<String> events = new ArrayList<>();
    /** Tags of the pending events ("kill", "pop", "death", "win", "advancement"), in order. */
    private final LinkedHashSet<String> eventTags = new LinkedHashSet<>();
    private long firstEvent, lastEventAt, fireAt, lastWinAt, lastGameOverAt;
    private int killCount;

    /** One of your arrows or tridents in flight (client thread). */
    private static final class Shot {
        final PersistentProjectileEntity entity;
        Vec3d pos;
        /** How far it moved in the last tick: how far short of its target it may be when it vanishes. */
        double move;
        long flyingTick, goneTick;

        Shot(PersistentProjectileEntity entity, long tick) {
            this.entity = entity;
            this.pos = entity.getEntityPos();
            // One already lying still (stuck in a block when its chunk loads) isn't flying until it moves.
            this.flyingTick = entity.getVelocity().lengthSquared() > 0.04 ? tick : tick - 3;
        }
    }

    private final Map<Integer, Shot> shots = new HashMap<>();
    /** Each player's hurt animation at the last tick, so a hit that just landed shows up as a rise. */
    private final Map<UUID, Integer> lastHurt = new HashMap<>();
    private long shotTick;
    /** Where the pending events happened (the clip is about them, wherever you are when it's saved). */
    private ClientWorld eventWorld;
    private String eventServer;

    public Clips() {
        super("Clips", "Instant replay and auto-clips: in Evade Clips when it's installed, or straight to MP4.", Category.MISC);
        instance = this;
        // On by default for people who installed Evade Clips; everyone else turns it on when they want it.
        setEnabled(java.nio.file.Files.exists(appDir().resolve("location.json")));
        // Leaving a server: save what's pending (it happened there) and forget it, so it never turns up later as a
        // stale, wrongly titled clip on the next server.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (client.isOnThread()) onDisconnect();
            else client.execute(this::onDisconnect);
        });
        // Players who had Evade Client before they installed Evade Clips: their saved config keeps Clips off, so turn
        // it on once (after the config and profiles have loaded), and again check on each join in case the app was
        // installed while the game was open.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> autoEnableForApp());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(this::autoEnableForApp));
        // Your own arrows and tridents, so ranged kills count too (see trackShots).
        ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof PersistentProjectileEntity shot) onShotLoaded(shot);
        });
    }

    /** The marker that says Clips was already turned on once for an installed Evade Clips (global, not per profile). */
    private static java.nio.file.Path autoEnabledFlag() {
        return FabricLoader.getInstance().getConfigDir().resolve("evade").resolve("clips-auto-enabled");
    }

    /**
     * Turns Clips on, once, when Evade Clips is installed. The flag is written first and only when the app is there,
     * so turning Clips off afterwards sticks, and installing the app later still turns it on.
     */
    private void autoEnableForApp() {
        try {
            if (!java.nio.file.Files.exists(appDir().resolve("location.json"))) return;
            java.nio.file.Path flag = autoEnabledFlag();
            if (java.nio.file.Files.exists(flag)) return;
            java.nio.file.Files.createDirectories(flag.getParent());
            java.nio.file.Files.writeString(flag, "Evade Clips was found, so Clips was turned on once.\n");
            if (!isEnabled()) {
                setEnabled(true);
                if (EvadeClient.config != null) EvadeClient.config.save(); // kept even if the game doesn't close cleanly
                Notifications.push("Clips", "Evade Clips found: Clips is on", true);
            }
        } catch (Exception e) {
            EvadeClient.LOG.warn("Clips: couldn't check for Evade Clips", e);
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    }

    /** Its hooks only reset its own state, so they run in menus too (turning it off there still frees the buffer). */
    @Override
    protected boolean alwaysRunHooks() { return true; }

    public static Clips active() {
        Clips c = instance;
        return c != null && c.isEnabled() ? c : null;
    }

    /**
     * True while the built-in recorder is filming the screen and "Show Overlays In Clips" is off: Evade's own pop-ups
     * and banners should stay off screen then, or they end up in every clip. (Evade Clips records the window itself
     * and handles its own feedback, so this is false when clips go there.)
     */
    public static boolean hideOverlays() {
        Clips c = instance;
        return c != null && c.isEnabled() && !c.useApp() && !c.showOverlays.get();
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
        clearBuffer();
        sparePixels.clear();
        jpegPool.execute(() -> jpegCanvas = null);
        resetEvents();
        // GPU resources and the window title are released on the render thread by the next onFrameEnd.
    }

    @Override
    public void onAttack(Entity target) {
        if (target instanceof PlayerEntity p) hitAt.put(p.getUuid(), System.currentTimeMillis());
    }

    /** Kills and pops are only worth tracking hits for while one of them is clipped. */
    private boolean tracksHits() {
        return kills.get() || pops.get();
    }

    /**
     * Who you've just hit, as the server tells it: the damage a player takes names its attacker (you, for your
     * sword, arrows, crossbow bolts and tridents alike), and your bow "dings" when an arrow of yours hits a player.
     * Only ever used to credit your own kills and pops for a clip.
     */
    @Override
    public void onServerPacket(Packet<?> packet) {
        if (!isEnabled() || mc.player == null || mc.world == null || !tracksHits()) return;
        if (packet instanceof EntityDamageS2CPacket damage) {
            if (damage.sourceCauseId() != mc.player.getId()) return;
            if (mc.world.getEntityById(damage.entityId()) instanceof PlayerEntity p && p != mc.player) {
                hitAt.put(p.getUuid(), System.currentTimeMillis());
            }
        } else if (packet instanceof GameStateChangeS2CPacket state
                && state.getReason() == GameStateChangeS2CPacket.PROJECTILE_HIT_PLAYER) {
            // Servers that leave the attacker out of damage still ding: credit the player who was just hurt next to
            // one of your shots (the damage came just before the ding, so the hurt animation has just started).
            PlayerEntity best = null;
            double bestDist = Double.MAX_VALUE;
            for (PlayerEntity p : mc.world.getPlayers()) {
                if (p == mc.player || p.hurtTime < 8) continue;
                double d = shotDistance(p, 3.0);
                if (d < bestDist) { bestDist = d; best = p; }
            }
            if (best != null) hitAt.put(best.getUuid(), System.currentTimeMillis());
        }
    }

    /** An arrow or trident just appeared: keep an eye on it if it's yours (its owner comes with its spawn packet). */
    private void onShotLoaded(PersistentProjectileEntity shot) {
        if (!isEnabled() || mc.player == null || !tracksHits() || shots.size() >= 64) return;
        if (shot.getOwner() == mc.player) shots.put(shot.getId(), new Shot(shot, shotTick));
    }

    /**
     * Ranged hits without help from the server: a player whose hurt animation just started (it only restarts on a
     * hit) right next to one of your arrows or tridents that was flying, or had just vanished into them.
     */
    private void trackShots(long now) {
        shotTick++;
        if (shots.isEmpty()) {
            if (!lastHurt.isEmpty()) lastHurt.clear();
            return;
        }
        List<PlayerEntity> hurt = null;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            Integer before = lastHurt.put(p.getUuid(), p.hurtTime);
            if (before != null && p.hurtTime > before) {
                if (hurt == null) hurt = new ArrayList<>(2);
                hurt.add(p);
            }
        }
        if (lastHurt.size() > 256) lastHurt.clear();
        for (Iterator<Shot> it = shots.values().iterator(); it.hasNext(); ) {
            Shot s = it.next();
            if (s.goneTick == 0 && !s.entity.isRemoved() && s.entity.getEntityWorld() == mc.world) {
                Vec3d pos = s.entity.getEntityPos();
                s.move = pos.distanceTo(s.pos);
                s.pos = pos;
                if (s.move > 0.2 || s.entity.getVelocity().lengthSquared() > 0.04) s.flyingTick = shotTick;
            } else if (s.goneTick == 0) {
                s.goneTick = shotTick; // keep its last spot a moment: the hit can arrive a tick after it vanished
            }
            // Gone for a few ticks, or lying still (stuck in a block or a player) for 5 s: no hit to come.
            if ((s.goneTick != 0 && shotTick - s.goneTick > 3) || shotTick - s.flyingTick > 100) it.remove();
        }
        if (hurt == null) return;
        for (PlayerEntity p : hurt) if (shotDistance(p, 1.0) < Double.MAX_VALUE) hitAt.put(p.getUuid(), now);
    }

    /**
     * How close the nearest of your shots that was flying in the last two ticks came to {@code p}, if within
     * {@code slack} blocks plus how far it moved in a tick (it can vanish up to a tick short of where it hit);
     * {@link Double#MAX_VALUE} if none did.
     */
    private double shotDistance(PlayerEntity p, double slack) {
        var box = p.getBoundingBox();
        double best = Double.MAX_VALUE;
        for (Shot s : shots.values()) {
            if (shotTick - s.flyingTick > 2) continue;
            double reach = slack + Math.min(4.0, s.move);
            double d = box.squaredMagnitude(s.pos);
            if (d <= reach * reach && d < best) best = d;
        }
        return best;
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        if (!launchChecked) { launchChecked = true; ensureAppRunning(); }
        if (now - lastPing > 5000) { lastPing = now; ping(); }

        boolean down = saveKey.isDown() && mc.currentScreen == null;
        if (down && !keyWasDown) {
            // Evade Clips listens for its hotkey everywhere; if it's the same key it has already saved this one, and
            // we only confirm it in game. Only while its keyboard hook is really running (it reports the key then),
            // so the key still saves a clip from here if that hook failed.
            boolean appHandled = useApp() && appRunning && !appHotkey.isEmpty()
                    && appHotkey.equalsIgnoreCase(KeySetting.keyName(saveKey.get()));
            if (appHandled) confirmAppSave();
            else saveNow("Clip", "manual", Origin.KEY);
        }
        keyWasDown = down;
        boolean open = openKey.isDown();
        if (open && !openWasDown) openApp(null);
        openWasDown = open;

        // A new world (dimension change, server switch behind a proxy): the players and our own life start over.
        if (mc.world != tickWorld) {
            tickWorld = mc.world;
            wasAlive.clear();
            hitAt.clear();
            shots.clear();
            lastHurt.clear();
            wasDead = false;
        }

        // Ranged hits first, so an arrow that kills is credited before the kill check below.
        if (tracksHits()) trackShots(now);
        else if (!shots.isEmpty() || !lastHurt.isEmpty()) { shots.clear(); lastHurt.clear(); }

        // Kills: a player we hit in the last 10 s just died.
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            boolean alive = p.isAlive() && !p.isDead();
            Boolean prev = wasAlive.put(p.getUuid(), alive);
            Long hit = hitAt.get(p.getUuid());
            if (Boolean.TRUE.equals(prev) && !alive && hit != null && now - hit < 10_000 && kills.get()) {
                event("Kill " + p.getName().getString(), "kill"); // first: it may save the previous world's events
                killCount++;
            }
        }
        hitAt.values().removeIf(t -> now - t > 15_000);
        if (wasAlive.size() > 256) wasAlive.clear();

        boolean dead = mc.player.isDead();
        if (dead && !wasDead) { if (deaths.get()) event("Death", "death"); else mark("Death"); }
        wasDead = dead;

        // Save when the moment is over, or right away if the world changed under it (it's still in the replay).
        if (!events.isEmpty() && (now >= fireAt || eventWorld != mc.world)) flush();
    }

    /** Saves a clip now: in Evade Clips (at the app's own replay length), or with the built-in recorder. */
    public void saveNow(String title, String tags) {
        saveNow(title, tags, Origin.KEY);
    }

    private void saveNow(String title, String tags, Origin origin) {
        if (useApp()) send("seconds=0", title, tags, serverLabel(), origin);
        else save();
    }

    /** From the network mixin: someone popped a totem. Clip it if it's a player we've been fighting. */
    public static void onPop(Entity e) {
        Clips c = get();
        if (c == null || !c.pops.get() || !(e instanceof PlayerEntity p) || p == EvadeClient.mc().player) return;
        Long hit = c.hitAt.get(p.getUuid());
        if (hit != null && System.currentTimeMillis() - hit < 10_000) c.event("Popped " + p.getName().getString(), "pop");
    }

    private static final Pattern ADVANCEMENT = Pattern.compile(
            "^(\\S+) has (made the advancement|completed the challenge|reached the goal) \\[(.+)]$");
    /** "You won!", "You won the game", "VICTORY!" (not "You won 50 coins": that's a prize, not a game). */
    private static final Pattern YOU_WON = Pattern.compile(
            "^\\W*(?:you (?:won|win)(?:\\W*$|[!.]| the (?:game|match|round|duel|fight|battle)\\b| against\\b| vs\\b)|victory\\W*$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern YOU_WON_SOMETHING = Pattern.compile("^\\W*you (?:won|win)\\b", Pattern.CASE_INSENSITIVE);
    /** "Winner: Steve", "Winners - Red Team": a win only when it names you. */
    private static final Pattern WINNERS = Pattern.compile("\\bwinners?\\s*[:\\-»]\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    /** "Steve has won the game!", "Red Team won the match": a win only when it names you. */
    private static final Pattern WON_THE_GAME = Pattern.compile(
            "^\\W*(.+?)\\s+(?:has\\s+)?won the (?:game|match|round)\\b", Pattern.CASE_INSENSITIVE);
    /** Player chat that servers relay as system messages: "<Steve> gg", "[MVP+] Steve: you won", "Steve » victory". */
    private static final Pattern CHAT_ANGLE = Pattern.compile("^(?:\\[[^\\]]{1,32}]\\s*){0,3}<[A-Za-z0-9_]{1,16}>\\s");
    private static final Pattern CHAT_NAMED = Pattern.compile(
            "^(?:\\[[^\\]]{1,32}]\\s*){0,3}([A-Za-z0-9_]{1,16})\\s*(?::|»|>>|->|>)\\s");

    /**
     * From chat: your own advancements, and end-of-game messages. Only a win (a line that says you won, or names you
     * as the winner) is clipped; any other end of a game ("Game over", top killers, a lost game) only drops a marker
     * on Evade Clips' timeline.
     */
    public static void onMessage(String text) {
        Clips c = get();
        var player = EvadeClient.mc().player;
        if (c == null || player == null || text == null) return;
        String me = player.getName().getString();
        var m = ADVANCEMENT.matcher(text.trim());
        if (m.matches()) {
            if (c.advancements.get() && m.group(1).equals(me)) c.event("Advancement: " + m.group(3), "advancement");
            return;
        }
        if (!c.wins.get()) return;
        String end = endOfGame(text, me);
        if ("Win".equals(end)) c.win();
        else if (end != null) c.gameOver();
    }

    /**
     * "Win" when the line says you won (or names you as the winner), "Game end" for any other end-of-game line,
     * null for everything else, including player chat that only looks like one.
     */
    public static String endOfGame(String text, String me) {
        String line = text.strip();
        if (line.isEmpty() || line.length() > 200 || looksLikePlayerChat(line)) return null;
        if (YOU_WON.matcher(line).find()) return "Win";
        Matcher m = WINNERS.matcher(line);
        if (m.find()) return names(m.group(1), me) ? "Win" : "Game end";
        m = WON_THE_GAME.matcher(line);
        if (m.find()) return names(m.group(1), me) ? "Win" : "Game end";
        if (YOU_WON_SOMETHING.matcher(line).find()) return null;
        return AutoGG.matches(line) ? "Game end" : null;
    }

    private static boolean names(String text, String me) {
        return me != null && !me.isEmpty()
                && Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(me) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE)
                .matcher(text).find();
    }

    /** Words servers start end-of-game lines with ("Winner: Steve"), which look like "Name: text" chat. */
    private static final Set<String> HEADINGS = Set.of("winner", "winners", "victory", "result", "results", "game", "match", "round", "mvp");

    /**
     * A line that starts like a player talking: "<Name> ..." or "Name: ...". "Winner: Steve" and the like are the
     * server's own headings, unless someone online really is called that.
     */
    private static boolean looksLikePlayerChat(String line) {
        if (CHAT_ANGLE.matcher(line).find()) return true;
        Matcher m = CHAT_NAMED.matcher(line);
        if (!m.find()) return false;
        String name = m.group(1);
        // Anyone else: chat, even from someone on another server of the network (not in the tab list).
        if (!HEADINGS.contains(name.toLowerCase(Locale.ROOT))) return true;
        var handler = EvadeClient.mc().getNetworkHandler();
        if (handler == null) return false;
        for (var entry : handler.getPlayerList()) {
            if (entry.getProfile() != null && name.equalsIgnoreCase(entry.getProfile().name())) return true;
        }
        return false;
    }

    private static Clips get() {
        return active();
    }

    /** End-of-game lines come in bursts ("You won!", "Winner: you", top killers, ...): one clip per won game. */
    private void win() {
        long now = System.currentTimeMillis();
        if ((events.contains("Win") && eventWorld == mc.world) || now - lastWinAt < 20_000) return;
        lastWinAt = now;
        event("Win", "win");
    }

    /** Any other end of a game: a marker on Evade Clips' timeline (once per game), never a clip of its own. */
    private void gameOver() {
        long now = System.currentTimeMillis();
        if (now - lastWinAt < 20_000 || now - lastGameOverAt < 20_000) return;
        lastGameOverAt = now;
        mark("Game end");
    }

    /** An auto-clip moment: {@code title} names it ("Kill Steve"), {@code tag} is its library tag ("kill"). */
    private void event(String title, String tag) {
        // Events from a world we've left are saved now, before this one starts a new clip.
        if (!events.isEmpty() && eventWorld != mc.world) flush();
        mark(title);
        long now = System.currentTimeMillis();
        if (events.isEmpty()) {
            firstEvent = now;
            eventWorld = mc.world;
            eventServer = serverLabel();
        }
        if (events.size() < 64) events.add(title);
        eventTags.add(tag);
        lastEventAt = now;
        fireAt = now + after.getInt() * 1000L;
    }

    private void flush() {
        long now = System.currentTimeMillis();
        // Long overdue (the game stopped ticking under it): it would be a clip of something else by now.
        if (events.isEmpty() || now - fireAt > 10_000) { clearPending(); return; }
        long span = Math.min((now - firstEvent) / 1000, after.getInt() + MAX_SPAN_EXTRA);
        String title = killCount >= 2 ? multi(killCount) : events.contains("Win") ? "Win" : events.get(0);
        List<String> tags = new ArrayList<>(eventTags);
        if (killCount >= 2) tags.add("multikill");
        String server = eventServer != null ? eventServer : serverLabel();
        clearPending();
        // Evade Clips decides how much comes before and after the moments (span: how long they lasted).
        if (useApp()) send("seconds=0&span=" + span + "&auto=1", title, String.join(",", tags), server, Origin.AUTO);
        else save();
    }

    /** Forgets pending auto-clip events. */
    private void clearPending() {
        events.clear();
        eventTags.clear();
        killCount = 0;
        fireAt = 0;
        firstEvent = 0;
        lastEventAt = 0;
        eventWorld = null;
        eventServer = null;
    }

    /** Forgets pending events and everything the kill/death tracking remembers. */
    private void resetEvents() {
        clearPending();
        hitAt.clear();
        wasAlive.clear();
        shots.clear();
        lastHurt.clear();
        wasDead = false;
        tickWorld = null;
    }

    private void onDisconnect() {
        if (isEnabled() && !events.isEmpty()) {
            try { flush(); } catch (Exception e) { EvadeClient.LOG.warn("Clips: couldn't save the pending clip", e); }
        }
        resetEvents();
    }

    private static String multi(int n) {
        return switch (n) { case 2 -> "Double kill"; case 3 -> "Triple kill"; case 4 -> "Quad kill"; default -> n + "-kill streak"; };
    }

    private String serverLabel() {
        return mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "Singleplayer";
    }

    /**
     * Asks Evade Clips to save a clip. {@code length} is the length part of the query: "seconds=0" (the app's own
     * replay length) for a manual save, "seconds=0&span=N&auto=1" for auto-clip moments that lasted N seconds.
     */
    private void send(String length, String title, String tags, String server, Origin origin) {
        String url = api("/clip?" + length + "&game=Minecraft&title=" + enc(title + " · " + server) + "&tag=" + enc(tags));
        // Long enough for the app to record the seconds after the press and write the clip before it answers.
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(45))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> mc.execute(() -> {
            if (err != null) {
                if (err instanceof java.net.http.HttpTimeoutException || err.getCause() instanceof java.net.http.HttpTimeoutException) {
                    // It's there but slow (still capturing the seconds after, or encoding): it reports the clip itself.
                    if (origin != Origin.AUTO) Notifications.push("Clips", "Evade Clips is still saving the clip", true);
                    return;
                }
                appDown();
                if (origin == Origin.AUTO) {
                    if (System.currentTimeMillis() - lastWarn > 60_000) {
                        lastWarn = System.currentTimeMillis();
                        Notifications.push("Clips", "Open Evade Clips to save clips", false);
                    }
                } else {
                    manualSaveFailed(origin);
                }
            } else if (res.statusCode() == 200) {
                String id = jsonString(res.body(), "id");
                if (id != null) announced(id);
                Notifications.push("Clip saved", origin == Origin.AUTO ? title + "  ·  .clips last to edit" : SAVED_IN_APP, true);
                if (origin == Origin.COMMAND) CommandManager.print("§aClip saved§7 in Evade Clips. Type §d.clips last§7 to edit it.");
            } else {
                String why = appError(res);
                Notifications.push("Clip not saved", why, false);
                if (origin == Origin.COMMAND) CommandManager.print("§cClip not saved§7: " + why);
            }
        }));
    }

    /**
     * A clip you asked for (key or command) couldn't reach Evade Clips: say so every time, and start the app (at most
     * every 30 s) so the next press works.
     */
    private void manualSaveFailed(Origin origin) {
        long now = System.currentTimeMillis();
        String why;
        if (now - lastLaunch < 30_000) {
            why = "Evade Clips is still starting: try again in a moment";
        } else if (launch(true)) {
            lastLaunch = now;
            why = "Evade Clips isn't running: starting it now";
        } else {
            why = "Evade Clips isn't installed: get it from evadeclient.com, or set Recorder to Built-in";
        }
        Notifications.push("Clip not saved", why, false);
        if (origin == Origin.COMMAND) CommandManager.print("§cClip not saved§7: " + why + ".");
    }

    /** Remembers a clip that has been announced in game (client thread), keeping the last few dozen. */
    private boolean announced(String id) {
        boolean fresh = announcedClips.add(id);
        if (announcedClips.size() > 32) {
            Iterator<String> it = announcedClips.iterator();
            it.next();
            it.remove();
        }
        return fresh;
    }

    /**
     * The app's own save key (F8 by default) saved this clip. Confirm it in game once its newest clip changes
     * (checked every 1.5 s, for as long as it may take to record the seconds after and write the clip).
     */
    private void confirmAppSave() {
        pollAppSave(appLastClip, System.currentTimeMillis() + 45_000, true);
    }

    private void pollAppSave(String before, long deadline, boolean first) {
        CompletableFuture.delayedExecutor(1500, TimeUnit.MILLISECONDS).execute(() -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(api("/status"))).timeout(Duration.ofSeconds(2)).GET().build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
                AppStatus s = err == null && res.statusCode() == 200 ? AppStatus.parse(res.body()) : null;
                applyStatus(s);
                mc.execute(() -> onAppSavePoll(s, before, deadline, first));
            });
        });
    }

    private void onAppSavePoll(AppStatus s, String before, long deadline, boolean first) {
        if (!isEnabled()) return;
        if (s == null) {
            Notifications.push("Clip not saved", "Evade Clips stopped responding", false);
            return;
        }
        if (s.lastClip() == null) {
            // An Evade Clips that doesn't report its newest clip: go by whether instant replay is on.
            if (s.live()) Notifications.push("Clip saved", SAVED_IN_APP, true);
            else Notifications.push("Clip not saved", "Instant replay is off in Evade Clips", false);
            return;
        }
        if (!s.lastClip().isEmpty() && !s.lastClip().equals(before) && announced(s.lastClip())) {
            Notifications.push("Clip saved", SAVED_IN_APP, true);
            return;
        }
        if (first && !s.live()) {
            Notifications.push("Clip not saved", "Instant replay is off in Evade Clips", false);
        } else if (System.currentTimeMillis() < deadline) {
            pollAppSave(before, deadline, false);
        } else {
            Notifications.push("Clip not saved", "Evade Clips didn't save it: open the app to see why", false);
        }
    }

    /**
     * What Evade Clips' /status says. {@code hotkey} is "" while its keyboard hook isn't running; {@code lastClip}
     * is null when the app doesn't report its newest clip.
     */
    private record AppStatus(String hotkey, String lastClip, String state) {
        /** Instant replay is on (or starting), so a save can work. Unknown states count as on. */
        boolean live() {
            return !state.equals("off") && !state.equals("error");
        }

        static AppStatus parse(String body) {
            try {
                var json = com.google.gson.JsonParser.parseString(body);
                if (!json.isJsonObject()) return null;
                var o = json.getAsJsonObject();
                if (!"Evade Clips".equals(string(o, "app"))) return null;
                String hotkey = string(o, "hotkey");
                // Newer apps also say outright whether their keyboard hook is running.
                for (String key : new String[]{"hotkeysActive", "hotkeyActive"}) {
                    if (o.get(key) instanceof com.google.gson.JsonPrimitive p && p.isBoolean() && !p.getAsBoolean()) hotkey = "";
                }
                return new AppStatus(hotkey == null ? "" : hotkey, o.has("lastClip") ? Objects.requireNonNullElse(string(o, "lastClip"), "") : null,
                        Objects.requireNonNullElse(string(o, "state"), "").toLowerCase(Locale.ROOT));
            } catch (Exception e) {
                return null; // not Evade Clips (something else on the port)
            }
        }

        private static String string(com.google.gson.JsonObject o, String key) {
            return o.get(key) instanceof com.google.gson.JsonPrimitive p ? p.getAsString() : null;
        }
    }

    /** A string field of a JSON reply, or null. */
    private static String jsonString(String body, String key) {
        try {
            var json = com.google.gson.JsonParser.parseString(body);
            if (json.isJsonObject() && json.getAsJsonObject().get(key) instanceof com.google.gson.JsonPrimitive p) return p.getAsString();
        } catch (Exception ignored) {
            // not JSON
        }
        return null;
    }

    /** The app's own reason ({"error": "..."}), never a raw response body. */
    private static String appError(HttpResponse<String> res) {
        try {
            var json = com.google.gson.JsonParser.parseString(res.body());
            if (json.isJsonObject() && json.getAsJsonObject().get("error") instanceof com.google.gson.JsonPrimitive p) {
                String error = p.getAsString().strip();
                if (!error.isEmpty()) return error;
            }
        } catch (Exception ignored) {
            // not JSON
        }
        return "Evade Clips error (HTTP " + res.statusCode() + ")";
    }

    /** Checks whether Evade Clips is running (and which key it saves on). */
    private void ping() {
        HttpRequest req = HttpRequest.newBuilder(URI.create(api("/status"))).timeout(Duration.ofSeconds(2)).GET().build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) ->
                applyStatus(err == null && res.statusCode() == 200 ? AppStatus.parse(res.body()) : null));
    }

    /** What the app last said about itself; null: it isn't running (any thread). */
    private void applyStatus(AppStatus s) {
        appRunning = s != null;
        appHotkey = s != null ? s.hotkey() : "";
        appLastClip = s != null ? s.lastClip() : null;
        if (s != null && useApp() && bufferedFrames() > 0) clearBuffer();
    }

    /** A request to the app just failed to connect: it isn't running (Auto goes back to the built-in recorder). */
    private void appDown() {
        applyStatus(null);
    }

    /**
     * A URL of Evade Clips' local control API, on the port the app recorded in location.json (or the App Port
     * setting), saying it's Evade Client asking (so the app leaves Minecraft's events to us).
     */
    private String api(String pathAndQuery) {
        return "http://127.0.0.1:" + appPort() + pathAndQuery + (pathAndQuery.indexOf('?') < 0 ? "?" : "&") + "client=evade";
    }

    private synchronized int appPort() {
        long now = System.currentTimeMillis();
        if (now - locationCheckedAt > 5000) {
            locationCheckedAt = now;
            int read = readLocationPort();
            if (read >= 0) locationPort = read;
        }
        return locationPort > 0 ? locationPort : port.getInt();
    }

    /**
     * The control port in appDir()/location.json (written by Evade Clips when it starts): 0 if it names none, -1 if
     * the file can't be read right now (being rewritten), so the last port read is kept.
     */
    private static int readLocationPort() {
        try {
            java.nio.file.Path loc = appDir().resolve("location.json");
            if (!java.nio.file.Files.isRegularFile(loc)) return 0;
            var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(loc));
            if (json.isJsonObject() && json.getAsJsonObject().get("port") instanceof com.google.gson.JsonPrimitive p && p.isNumber()) {
                int value = p.getAsInt();
                return value > 0 && value <= 65535 ? value : 0;
            }
            return 0;
        } catch (Exception e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ the Evade Clips app

    /**
     * {@code .clip} (or {@code .clips save}) saves a clip now. {@code .clips} opens Evade Clips, or the clips
     * folder without it; {@code .clips last} opens your newest clip in Evade Clips' editor.
     */
    @Override
    public void command(String[] a) {
        Clips c = EvadeClient.modules == null ? null : EvadeClient.modules.get(Clips.class);
        if (c == null) return;
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        if (a[0].equalsIgnoreCase("clip") || sub.equals("save")) {
            if (!c.isEnabled()) { CommandManager.print("§7Turn on §cClips§7 first; it keeps the last seconds while it's on."); return; }
            c.saveNow("Clip", "manual", Origin.COMMAND);
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
        String url = api("/open" + (clip == null ? "" : "?clip=" + enc(clip)));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.noBody()).build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete((res, err) -> mc.execute(() -> {
            if (err == null) return;
            if (launch(false)) lastLaunch = System.currentTimeMillis();
            else Notifications.push("Evade Clips", "Not installed: get it from evadeclient.com", false);
        }));
    }

    /** Starts Evade Clips in the tray when the game starts, so instant replay is already running when you play. */
    private void ensureAppRunning() {
        if (!launchApp.get() || "Built-in".equals(recorder.get())) return;
        HttpRequest req = HttpRequest.newBuilder(URI.create(api("/status"))).timeout(Duration.ofSeconds(2)).GET().build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
            if (err != null || res.statusCode() != 200) mc.execute(() -> {
                if (launch(true)) {
                    lastLaunch = System.currentTimeMillis();
                    Notifications.push("Evade Clips", "Instant replay is starting", true);
                }
            });
        });
    }

    /**
     * Starts the installed app (location recorded by the app / installer), in the tray when {@code minimized}.
     * Returns false if it isn't installed. Callers say what's happening.
     */
    private static boolean launch(boolean minimized) {
        try {
            java.nio.file.Path loc = appDir().resolve("location.json");
            if (!java.nio.file.Files.exists(loc)) return false;
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(loc)).getAsJsonObject();
            // Evade Installer leaves a newer Evade Clips next to the old one when the app was running during an
            // update ("pendingUpdate"). It isn't running now, so swap it in before starting it.
            if (o.has("pendingUpdate") && (o.has("exe") || o.has("jar"))) {
                java.nio.file.Path pendingUpdate = java.nio.file.Path.of(o.get("pendingUpdate").getAsString());
                java.nio.file.Path app = java.nio.file.Path.of(o.get(o.has("exe") ? "exe" : "jar").getAsString());
                if (java.nio.file.Files.isRegularFile(pendingUpdate) && pendingUpdate.getParent() != null
                        && pendingUpdate.getParent().equals(app.getParent())
                        && pendingUpdate.getFileName().toString().equals(app.getFileName() + ".new")) {
                    try {
                        java.nio.file.Files.move(pendingUpdate, app, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        o.remove("pendingUpdate");
                        java.nio.file.Files.writeString(loc, o.toString());
                    } catch (Exception ignored) {
                        // Still locked (it is running after all): start the current version and try again next time.
                    }
                }
            }
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
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Same folder Evade Clips uses for its settings. */
    static java.nio.file.Path appDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win") && System.getenv("APPDATA") != null) return java.nio.file.Path.of(System.getenv("APPDATA"), "EvadeClips");
        return java.nio.file.Path.of(System.getProperty("user.home"), ".evade-clips");
    }

    /** Drops a named marker in Evade Clips right away (shows on the recording's timeline), while it's running. */
    private void mark(String label) {
        if (!appRunning) return;
        HttpRequest req = HttpRequest.newBuilder(URI.create(api("/marker?label=" + enc(label))))
                .timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.noBody()).build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding());
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    // ------------------------------------------------------------------ built-in recorder (when Evade Clips isn't running)

    /** Called on the render thread after every frame is fully drawn (world, HUD and any open screen). */
    public static void onFrameEnd() {
        Clips c = instance;
        if (c == null) return;
        try {
            c.frameEnd();
        } catch (Throwable e) {
            // Never take the frame down with us (and never report the same failure every frame).
            if (c.isEnabled()) net.evadeclient.module.ModuleManager.crashed(c, e, "recorder");
            else if (!c.frameErrorLogged) {
                c.frameErrorLogged = true;
                EvadeClient.LOG.error("Clips: recorder cleanup failed", e);
            }
        }
    }

    private void frameEnd() {
        boolean recording = isEnabled() && !useApp();
        if (recording) capture();
        else if (stageTex != null || !spareReadback.isEmpty()) releaseGpu();
        updateTitle(recording);
    }

    private void capture() {
        long now = System.nanoTime();
        long step = (long) (1e9 / Math.max(1, fps.get()));
        // A steady clock (not "time since the last grab"), so 60 FPS on a 60 Hz screen really is 60, not 30.
        if (now + 1_000_000 < nextCapture) return;
        if (pending.get() >= MAX_PENDING) {
            if (pendingSince == 0) pendingSince = now;
            else if (now - pendingSince > 10_000_000_000L) {
                // Readbacks stopped coming back (driver trouble): start over, on the slower but simpler path.
                EvadeClient.LOG.warn("Clips: captured frames stopped coming back{}", gpuFailed ? "" : ", switching to the CPU capture path");
                if (!gpuFailed) {
                    gpuFailed = true;
                    releaseGpu();
                }
                pending.set(0);
                pendingSince = 0;
            }
            return;
        }
        pendingSince = 0;
        Framebuffer fb = mc.getFramebuffer();
        if (fb == null || fb.textureWidth < 2 || fb.textureHeight < 2 || fb.getColorAttachment() == null) return;
        nextCapture = now - nextCapture > step ? now + step : nextCapture + step;
        int target = switch (quality.get()) { case "480p" -> 480; case "1080p" -> 1080; default -> 720; };
        int outH = Math.max(2, Math.min(target, fb.textureHeight) & ~1);
        int outW = Math.max(2, (int) Math.round(outH * (double) fb.textureWidth / fb.textureHeight) & ~1);
        if (!gpuFailed) {
            try {
                captureGpu(fb, now, outW, outH);
                return;
            } catch (Throwable e) {
                gpuFailed = true;
                EvadeClient.LOG.warn("Clips: GPU downscale unavailable, using the CPU capture path", e);
                releaseGpu();
            }
        }
        captureCpu(fb, now, outW, outH);
    }

    /** Scales the frame to the clip size on the GPU and queues an asynchronous readback of just that. */
    private void captureGpu(Framebuffer fb, long nanos, int outW, int outH) {
        GpuTextureView source = fb.getColorAttachmentView();
        if (source == null) return;
        GpuDevice device = RenderSystem.getDevice();
        prepareStages(device, fb.textureWidth, fb.textureHeight, outW, outH);
        CommandEncoder encoder = device.createCommandEncoder();
        var sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR);
        for (GpuTextureView target : stageView) {
            try (RenderPass pass = encoder.createRenderPass(() -> "Evade Clips downscale", target, OptionalInt.empty())) {
                pass.setPipeline(RenderPipelines.TRACY_BLIT);
                pass.bindTexture("InSampler", source, sampler);
                pass.draw(0, 3);
            }
            source = target;
        }
        GpuBuffer buffer = spareReadback.poll();
        if (buffer == null) {
            buffer = device.createBuffer(() -> "Evade Clips readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                    (long) outW * outH * 4);
        }
        GpuBuffer buf = buffer;
        int generation = gpuGeneration;
        pending.incrementAndGet();
        try {
            encoder.copyTextureToBuffer(stageTex[stageTex.length - 1], buf, 0L,
                    () -> onReadback(buf, generation, nanos, outW, outH), 0);
        } catch (RuntimeException e) {
            frameDone();
            buf.close();
            throw e;
        }
    }

    /**
     * The blit chain from the framebuffer to the clip size: plain halvings (an exact 2x2 average with linear
     * filtering) while the frame is more than twice the target, then one last scale to the exact size.
     */
    private void prepareStages(GpuDevice device, int srcW, int srcH, int outW, int outH) {
        if (stageTex != null && srcW == stageSrcW && srcH == stageSrcH && outW == stageOutW && outH == stageOutH) return;
        releaseGpu();
        List<int[]> sizes = new ArrayList<>();
        int w = srcW, h = srcH;
        while (h > outH * 2 && w > outW * 2) {
            w = (w + 1) / 2;
            h = (h + 1) / 2;
            sizes.add(new int[]{w, h});
        }
        sizes.add(new int[]{outW, outH});
        GpuTexture[] tex = new GpuTexture[sizes.size()];
        GpuTextureView[] views = new GpuTextureView[sizes.size()];
        stageTex = tex;
        stageView = views;
        for (int i = 0; i < sizes.size(); i++) {
            boolean last = i == sizes.size() - 1;
            int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | (last ? GpuTexture.USAGE_COPY_SRC : GpuTexture.USAGE_TEXTURE_BINDING);
            tex[i] = device.createTexture("Evade Clips frame " + i, usage, TextureFormat.RGBA8, sizes.get(i)[0], sizes.get(i)[1], 1, 1);
            views[i] = device.createTextureView(tex[i]);
        }
        stageSrcW = srcW;
        stageSrcH = srcH;
        stageOutW = outW;
        stageOutH = outH;
    }

    /** Frees the GPU side of the recorder (render thread). Readbacks still in flight close their own buffers. */
    private void releaseGpu() {
        gpuGeneration++;
        if (stageView != null) for (GpuTextureView v : stageView) if (v != null) v.close();
        if (stageTex != null) for (GpuTexture t : stageTex) if (t != null) t.close();
        stageView = null;
        stageTex = null;
        for (GpuBuffer b; (b = spareReadback.poll()) != null; ) b.close();
    }

    /** Render thread, once the GPU has copied the frame: one bulk copy out, the rest happens on the JPEG thread. */
    private void onReadback(GpuBuffer buf, int generation, long nanos, int w, int h) {
        if (buf.isClosed()) return; // its copy failed and was already accounted for
        int[] px = null;
        boolean handedOff = false;
        try {
            if (isEnabled() && !useApp()) {
                int n = w * h;
                px = pixels(n);
                try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder().mapBuffer(buf, true, false)) {
                    view.data().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(px, 0, n);
                }
                int[] frame = px;
                jpegPool.execute(() -> encodeFrame(frame, w, h, nanos, true));
                handedOff = true;
            }
        } catch (Throwable e) {
            EvadeClient.LOG.warn("Clips: frame readback failed", e);
        } finally {
            if (generation == gpuGeneration && spareReadback.size() < MAX_PENDING) spareReadback.push(buf);
            else buf.close();
            if (!handedOff) {
                if (px != null) sparePixels.offer(px);
                frameDone();
            }
        }
    }

    /** Fallback without the GPU blit: the game's own screenshot readback, scaled the rest of the way off-thread. */
    private void captureCpu(Framebuffer fb, long nanos, int outW, int outH) {
        int fw = fb.textureWidth, fh = fb.textureHeight, factor = 1;
        // The biggest whole factor the game can average by itself without going under the clip size.
        for (int k = 2; fh / k >= outH; k++) if (fw % k == 0 && fh % k == 0) factor = k;
        pending.incrementAndGet();
        try {
            ScreenshotRecorder.takeScreenshot(fb, factor, img -> {
                try {
                    jpegPool.execute(() -> encodeImage(img, outW, outH, nanos));
                } catch (Throwable e) {
                    img.close();
                    frameDone();
                }
            });
        } catch (Throwable e) {
            frameDone();
            if (!cpuWarned) {
                cpuWarned = true;
                EvadeClient.LOG.warn("Clips: capture failed", e);
            }
        }
    }

    /** JPEG thread: scales a screenshot to the clip size if needed, then encodes it like a GPU frame. */
    private void encodeImage(NativeImage img, int w, int h, long nanos) {
        int[] px = null;
        try (img) {
            NativeImage scaled = null;
            try {
                NativeImage src = img;
                if (img.getWidth() != w || img.getHeight() != h) {
                    scaled = new NativeImage(w, h, false);
                    img.resizeSubRectTo(0, 0, img.getWidth(), img.getHeight(), scaled);
                    src = scaled;
                }
                int n = w * h;
                px = pixels(n);
                MemoryUtil.memIntBuffer(src.imageId(), n).get(px, 0, n);
            } finally {
                if (scaled != null) scaled.close();
            }
        } catch (Throwable e) {
            EvadeClient.LOG.warn("Clips: frame scale failed", e);
            if (px != null) sparePixels.offer(px);
            frameDone();
            return;
        }
        encodeFrame(px, w, h, nanos, false);
    }

    /** JPEG thread: RGBA pixels (as little-endian ints) into a JPEG in the replay buffer. Always settles the frame. */
    private void encodeFrame(int[] abgr, int w, int h, long nanos, boolean bottomUp) {
        try {
            if (jpegCanvas == null || jpegCanvas.getWidth() != w || jpegCanvas.getHeight() != h) {
                jpegCanvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            }
            int[] rgb = ((DataBufferInt) jpegCanvas.getRaster().getDataBuffer()).getData();
            for (int y = 0; y < h; y++) {
                int src = (bottomUp ? h - 1 - y : y) * w, dst = y * w;
                for (int x = 0; x < w; x++) {
                    int p = abgr[src + x];
                    rgb[dst + x] = (p & 0xFF) << 16 | (p & 0xFF00) | (p >> 16) & 0xFF;
                }
            }
            byte[] jpeg = writeJpeg(jpegCanvas);
            if (isEnabled() && !useApp()) store(nanos, jpeg, w, h);
        } catch (Throwable e) {
            EvadeClient.LOG.warn("Clips: frame encode failed", e);
            if (jpegWriter != null) jpegWriter.dispose();
            jpegWriter = null; // a fresh one next frame
        } finally {
            if (sparePixels.size() <= MAX_PENDING) sparePixels.offer(abgr);
            frameDone();
        }
    }

    private int[] pixels(int n) {
        for (int[] a; (a = sparePixels.poll()) != null; ) if (a.length == n) return a;
        return new int[n];
    }

    private void frameDone() {
        pending.updateAndGet(v -> v > 0 ? v - 1 : 0);
    }

    /** JPEG thread. In-memory streams: ImageIO's defaults would go through a temp file for every frame. */
    private byte[] writeJpeg(BufferedImage img) throws Exception {
        if (jpegWriter == null) {
            jpegWriter = ImageIO.getImageWritersByFormatName("jpeg").next();
            jpegParam = jpegWriter.getDefaultWriteParam();
            jpegParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            jpegParam.setCompressionQuality(0.85f);
        }
        jpegOut.reset();
        try (var ios = new MemoryCacheImageOutputStream(jpegOut)) {
            jpegWriter.setOutput(ios);
            jpegWriter.write(null, new IIOImage(img, null, null), jpegParam);
        } finally {
            jpegWriter.setOutput(null);
        }
        return jpegOut.toByteArray();
    }

    private void store(long nanos, byte[] jpeg, int w, int h) {
        synchronized (frames) {
            frames.addLast(new Frame(nanos, jpeg, w, h));
            heldBytes += jpeg.length;
            long keep = (long) ((seconds.get() + 1) * 1e9);
            while (frames.size() > 1) {
                Frame first = frames.peekFirst();
                boolean old = nanos - first.nanos > keep;
                if (!old && heldBytes <= MAX_BYTES) break;
                frames.removeFirst();
                first.buffered = false;
                if (first.holders == 0) heldBytes -= first.jpeg.length;
                capCut = !old;
            }
        }
    }

    private void clearBuffer() {
        synchronized (frames) {
            for (Frame f : frames) {
                f.buffered = false;
                if (f.holders == 0) heldBytes -= f.jpeg.length;
            }
            frames.clear();
            capCut = false;
        }
    }

    /** A clip is done with this frame: once nothing holds it, it stops counting against the cap. */
    private void release(Frame f) {
        synchronized (frames) {
            if (--f.holders == 0 && !f.buffered) heldBytes -= f.jpeg.length;
        }
    }

    // ------------------------------------------------------------------ save

    /**
     * Write the buffered seconds to an MP4 in the background. Completes with the file, or exceptionally. While a clip
     * is being written, the next ones wait their turn (up to {@link #MAX_SAVES} in all).
     */
    public CompletableFuture<File> save() {
        if (saves.get() >= MAX_SAVES) {
            CommandManager.print("§7Clip queue full: wait for the clips that are saving.");
            setStatus("Clip queue full", 2500);
            return CompletableFuture.failedFuture(new IllegalStateException("queue full"));
        }
        List<Frame> clip;
        boolean bufferFull;
        synchronized (frames) {
            int start = frames.size() - 1;
            List<Frame> all = new ArrayList<>(frames);
            if (start >= 0) {
                // only the trailing run of same-size frames (the window may have been resized)
                Frame last = all.get(start);
                while (start > 0 && all.get(start - 1).w == last.w && all.get(start - 1).h == last.h) start--;
            }
            clip = start < 0 ? new ArrayList<>() : new ArrayList<>(all.subList(start, all.size()));
            // Held from now on: the buffer may move on, these stay until they've been written.
            if (clip.size() >= 2) for (Frame f : clip) f.holders++;
            bufferFull = capCut && start == 0;
        }
        if (clip.size() < 2) {
            CommandManager.print("§7Nothing recorded yet. Keep §cClips§7 on for a few seconds first.");
            return CompletableFuture.failedFuture(new IllegalStateException("empty"));
        }
        int ahead = saves.getAndIncrement();
        if (ahead > 0) {
            setStatus("Clip queued", 2500);
            CommandManager.print("§7Clip queued: it saves after the " + (ahead == 1 ? "one" : ahead + " clips") + " in progress.");
        } else {
            status = null; // "Saving clip..." while saves are running (liveStatus)
        }
        File file = reserveFile();
        int outFps = fps.getInt(), wanted = seconds.getInt();
        CompletableFuture<File> done = new CompletableFuture<>();
        try {
            mp4Pool.execute(() -> writeClip(clip, file, outFps, wanted, bufferFull, done));
        } catch (Throwable e) {
            for (Frame f : clip) release(f);
            reservedNames.remove(file.getName());
            saves.decrementAndGet();
            done.completeExceptionally(e);
        }
        return done;
    }

    /** A clip-date.mp4 name that neither exists nor is promised to a clip still being written. */
    private File reserveFile() {
        File dir = folder();
        dir.mkdirs();
        String base = "clip-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        for (int i = 1; ; i++) {
            File f = new File(dir, base + (i == 1 ? "" : "-" + i) + ".mp4");
            if (!f.exists() && reservedNames.add(f.getName())) return f;
        }
    }

    /** Encoder thread. */
    private void writeClip(List<Frame> clip, File file, int outFps, int wanted, boolean bufferFull, CompletableFuture<File> done) {
        long t0 = System.currentTimeMillis();
        double secs = (clip.get(clip.size() - 1).nanos - clip.get(0).nanos) / 1e9;
        int count = clip.size();
        try {
            encode(clip, file, outFps);
            EvadeClient.LOG.info("Clips: wrote {} ({} frames, {}s, {} KB) in {} ms", file.getName(), count,
                    String.format("%.1f", secs), file.length() / 1024, System.currentTimeMillis() - t0);
            // The cap cut it short (not a recorder that just started): say why, and what gives longer clips.
            boolean cut = bufferFull && secs < wanted - 2;
            mc.execute(() -> {
                CommandManager.print("§aClip saved§7: " + file.getName() + " (" + String.format("%.0f", secs) + "s"
                        + (cut ? ", §ebuffer full: lower Quality/FPS for longer clips§7" : "")
                        + "). §7Type §d.clips§7 to open the folder, or get Evade Clips to edit and share.");
                Notifications.push("Clip saved", String.format("%.0fs · %d MB", secs, Math.max(1, file.length() >> 20))
                        + (cut ? " · buffer full" : ""), true);
            });
            setStatus("Clip saved", 3000);
            done.complete(file);
        } catch (Throwable e) {
            EvadeClient.LOG.error("Clips: saving failed", e);
            file.delete();
            mc.execute(() -> CommandManager.print("§cCouldn't save the clip: " + e.getMessage()));
            setStatus("Clip failed", 3000);
            done.completeExceptionally(e);
        } finally {
            for (Frame f : clip) if (f != null) release(f);
            reservedNames.remove(file.getName());
            saves.decrementAndGet();
        }
    }

    /**
     * Lay the captured frames on a constant-rate timeline (so playback speed is right) and encode H.264. Frames are
     * let go as soon as the timeline has passed them, so a long clip doesn't hold the whole buffer until the end.
     */
    private void encode(List<Frame> clip, File file, int outFps) throws Exception {
        ImageReader reader = ImageIO.getImageReadersByFormatName("jpeg").next();
        SeekableByteChannel out = NIOUtils.writableChannel(file);
        try {
            SequenceEncoder enc = new SequenceEncoder(out, Rational.R(outFps, 1), Format.MOV, Codec.H264, null);
            long start = clip.get(0).nanos, end = clip.get(clip.size() - 1).nanos, step = (long) (1e9 / outFps);
            int idx = 0, shown = -1, freed = 0;
            Picture pic = null;
            for (long t = start; t <= end; t += step) {
                while (idx + 1 < clip.size() && clip.get(idx + 1).nanos <= t) idx++;
                if (idx != shown) {
                    pic = toPicture(reader, clip.get(idx), pic);
                    shown = idx;
                    for (; freed < idx; freed++) {
                        release(clip.get(freed));
                        clip.set(freed, null);
                    }
                }
                enc.encodeNativeFrame(pic);
            }
            enc.finish();
        } finally {
            NIOUtils.closeQuietly(out);
            reader.dispose();
        }
    }

    /** One JPEG frame as jcodec's signed RGB, reusing {@code reuse}'s pixels when it's the same size. */
    private static Picture toPicture(ImageReader reader, Frame f, Picture reuse) throws Exception {
        BufferedImage img;
        try (var in = new MemoryCacheImageInputStream(new ByteArrayInputStream(f.jpeg))) {
            reader.setInput(in, true, true);
            img = reader.read(0);
        } finally {
            reader.setInput(null);
        }
        int w = img.getWidth(), h = img.getHeight();
        Picture pic = reuse != null && reuse.getWidth() == w && reuse.getHeight() == h ? reuse : Picture.create(w, h, ColorSpace.RGB);
        byte[] d = pic.getPlaneData(0);
        if (img.getType() == BufferedImage.TYPE_3BYTE_BGR && img.getRaster().getDataBuffer() instanceof DataBufferByte db
                && db.getData().length == w * h * 3) {
            byte[] s = db.getData();
            for (int i = 0; i < s.length; i += 3) {
                d[i] = (byte) (s[i + 2] ^ 0x80);
                d[i + 1] = (byte) (s[i + 1] ^ 0x80);
                d[i + 2] = (byte) (s[i] ^ 0x80);
            }
        } else {
            int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
            for (int i = 0, o = 0; i < rgb.length; i++) {
                int p = rgb[i];
                d[o++] = (byte) (((p >> 16) & 255) - 128);
                d[o++] = (byte) (((p >> 8) & 255) - 128);
                d[o++] = (byte) ((p & 255) - 128);
            }
        }
        return pic;
    }

    // ------------------------------------------------------------------ status: window title, optional HUD

    private void setStatus(String s, long ms) { status = s; statusUntil = System.currentTimeMillis() + ms; }

    /** The latest status while it's live, else "Saving clip..." while clips are being written, else null. */
    private String liveStatus() {
        String s = status;
        if (s != null && System.currentTimeMillis() < statusUntil) return s;
        return saves.get() > 0 ? "Saving clip..." : null;
    }

    /**
     * The built-in recorder's state goes in the window title ("● REC", "Saving clip..."), which is never part of the
     * clip. The game's own title is rebuilt around it, and restored as soon as the recorder stops.
     */
    private void updateTitle(boolean recording) {
        String prefix = null;
        if (recording) {
            prefix = liveStatus();
            if (prefix == null && indicator.get()) prefix = "● REC";
        }
        if (prefix == null) {
            if (titleShown != null) {
                titleShown = null;
                mc.updateWindowTitle();
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now - titleCheckedAt < 250) return;
        titleCheckedAt = now;
        String title = prefix + "  ·  " + gameTitle();
        // Re-applied now and then: the game resets its title on its own (joining, leaving, language changes).
        if (!title.equals(titleShown) || now - titleSetAt > 3000) {
            mc.getWindow().setTitle(title);
            titleShown = title;
            titleSetAt = now;
        }
    }

    /** The title the game itself would show ("Minecraft* 1.21.11 - Multiplayer (3rd-party Server)"). */
    private String gameTitle() {
        if (titleBase == null) {
            titleBase = "Minecraft" + (MinecraftClient.getModStatus().isModded() ? "*" : "") + " " + SharedConstants.getGameVersion().name();
        }
        var handler = mc.getNetworkHandler();
        if (handler == null || !handler.getConnection().isOpen()) return titleBase;
        var entry = mc.getCurrentServerEntry();
        var server = mc.getServer();
        String where;
        if (server != null && !server.isRemote()) where = I18n.translate("title.singleplayer");
        else if (entry != null && entry.isRealm()) where = I18n.translate("title.multiplayer.realms");
        else if (server == null && (entry == null || !entry.isLocal())) where = I18n.translate("title.multiplayer.other");
        else where = I18n.translate("title.multiplayer.lan");
        return titleBase + " - " + where;
    }

    @Override
    public void onHud(DrawContext ctx, float tickDelta) {
        // Built-in clips are filmed from this very frame: nothing here unless overlays were asked for.
        if (useApp() || !showOverlays.get()) return;
        var tr = mc.textRenderer;
        if (indicator.get()) {
            boolean blink = (System.currentTimeMillis() / 600) % 2 == 0 || saves.get() > 0;
            ctx.fill(4, 4, 8, 8, blink ? 0xFFE11D2E : 0x80E11D2E);
        }
        String s = liveStatus();
        if (s != null) {
            int w = tr.getWidth(s);
            int x = (ctx.getScaledWindowWidth() - w) / 2;
            ctx.fill(x - 6, 22, x + w + 6, 36, 0xC0000000);
            ctx.fill(x - 6, 22, x + w + 6, 23, 0xFFE11D2E);
            ctx.drawTextWithShadow(tr, s, x, 26, 0xFFFFFFFF);
        }
    }
}
