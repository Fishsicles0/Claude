package net.evadeclient.replay;

import net.evadeclient.EvadeClient;
import net.evadeclient.mixin.SoundManagerAccessor;
import net.evadeclient.mixin.SoundSystemAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.SoundExecutor;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundCategory;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Simple Voice Chat in replays. While you play, the voice audio you hear (and, optionally, your own) is recorded
 * into the replay as Opus, tagged with who was talking, or where. Watching or rendering, it's played back through
 * the game's own sound engine from that player's position as they move, so it's positional, follows replay speed
 * and pauses, and ends up in rendered videos like every other sound.
 * <p>
 * Simple Voice Chat's API is only touched by {@link VoiceChatPlugin} (loaded by that mod); without it, replays
 * still play, just without voices.
 */
public final class ReplayVoice {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    public static final int RATE = 48_000;
    static final byte ENTITY = 0, LOCATION = 1, STATIC = 2;

    /** Opus encode / decode, one stream per speaker (Opus keeps state between frames). Set by the plugin. */
    public interface Codec {
        byte[] encode(String speaker, short[] pcm);
        short[] decode(String speaker, byte[] opus);
        void resetDecoders();
    }

    private static volatile Codec codec;
    /** Default voice range when the event doesn't say (your own voice): Simple Voice Chat's own default. */
    private static volatile float defaultDistance = 48;

    private ReplayVoice() {}

    public static void setCodec(Codec c, double distance) {
        codec = c;
        if (distance > 0) defaultDistance = (float) distance;
    }

    public static boolean available() { return codec != null; }

    // ------------------------------------------------------------------ recording (Simple Voice Chat's threads)

    private static boolean recordingVoice() {
        var r = EvadeClient.modules == null ? null : EvadeClient.modules.get(net.evadeclient.module.misc.Replay.class);
        return r != null && r.isEnabled() && r.recordVoiceChat();
    }

    /** Someone else talking, heard from an entity (a player). */
    public static void recordEntity(UUID entity, float distance, boolean whisper, short[] pcm) {
        if (recordingVoice()) record(ENTITY, entity, 0, 0, 0, distance, pcm);
    }

    /** Voice from a fixed place (e.g. a plugin's speaker). */
    public static void recordLocation(UUID channel, double x, double y, double z, float distance, short[] pcm) {
        if (recordingVoice()) record(LOCATION, channel, x, y, z, distance, pcm);
    }

    /** Voice heard everywhere (group chat). */
    public static void recordStatic(UUID channel, short[] pcm) {
        if (recordingVoice()) record(STATIC, channel, 0, 0, 0, 0, pcm);
    }

    /** Your own microphone, played back from where you were. */
    public static void recordOwn(boolean whisper, short[] pcm) {
        var r = EvadeClient.modules == null ? null : EvadeClient.modules.get(net.evadeclient.module.misc.Replay.class);
        if (r == null || !r.isEnabled() || !r.recordOwnVoice()) return;
        UUID me = ReplayRecorder.livePlayer();
        if (me != null) record(ENTITY, me, 0, 0, 0, whisper ? defaultDistance / 2 : defaultDistance, pcm);
    }

    private static void record(byte type, UUID id, double x, double y, double z, float distance, short[] pcm) {
        Codec c = codec;
        if (c == null || pcm == null || pcm.length == 0 || id == null) return;
        try {
            byte[] opus = c.encode("rec:" + id, pcm);
            if (opus == null || opus.length == 0) return;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(opus.length + 48);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(type);
            out.writeLong(id.getMostSignificantBits());
            out.writeLong(id.getLeastSignificantBits());
            if (type == LOCATION) { out.writeDouble(x); out.writeDouble(y); out.writeDouble(z); }
            out.writeFloat(distance);
            out.write(opus);
            ReplayRecorder.voice(bytes.toByteArray());
            framesRecorded++;
        } catch (Exception e) {
            EvadeClient.LOG.debug("Replay: couldn't record a voice frame: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ playback (client thread, AL on the sound thread)

    /** One person (or place) talking: an OpenAL source fed with queued 20 ms buffers. */
    private static final class Speaker {
        final byte type;
        final UUID id;
        double x, y, z;
        float distance;
        /** Whether an entity speaker has been found in the world (until then it plays from the camera). */
        volatile boolean located;
        int source;
        long context;
        int queued, waitTicks, idleTicks;
        final ArrayDeque<Integer> buffers = new ArrayDeque<>();

        Speaker(byte type, UUID id) { this.type = type; this.id = id; }
    }

    private static final Map<String, Speaker> speakers = new HashMap<>();
    private static int decodeFailures;

    private static SoundExecutor executor() {
        var sys = (SoundSystemAccessor) ((SoundManagerAccessor) mc.getSoundManager()).evade$soundSystem();
        return sys.evade$started() ? sys.evade$taskQueue() : null;
    }

    /** A recorded voice frame reached the playhead. */
    static void play(byte[] data) {
        Codec c = codec;
        if (c == null) return;
        try {
            DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(data));
            byte type = in.readByte();
            UUID id = new UUID(in.readLong(), in.readLong());
            double x = 0, y = 0, z = 0;
            if (type == LOCATION) { x = in.readDouble(); y = in.readDouble(); z = in.readDouble(); }
            float distance = in.readFloat();
            byte[] opus = in.readAllBytes();
            short[] pcm = c.decode("play:" + id, opus);
            if (pcm == null || pcm.length == 0) return;
            String key = type + ":" + id;
            Speaker s = speakers.computeIfAbsent(key, k -> new Speaker(type, id));
            s.distance = distance > 0 ? distance : defaultDistance;
            if (type == LOCATION) { s.x = x; s.y = y; s.z = z; }
            else if (type == ENTITY) locate(s);
            s.idleTicks = 0;
            framesPlayed++;
            lastSpeaker = String.format(java.util.Locale.ROOT, "%s at %.1f, %.1f, %.1f, range %.0f, volume %.2f", type == ENTITY ? "player" : type == LOCATION ? "place" : "group", s.x, s.y, s.z, s.distance, volume);
            ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(pcm.length * 2);
            buf.asShortBuffer().put(pcm);
            SoundExecutor exec = executor();
            if (exec != null) exec.execute(() -> queue(s, buf));
        } catch (Exception e) {
            if (decodeFailures++ < 5) EvadeClient.LOG.warn("Replay: couldn't play a voice frame: {}", e.toString());
        }
    }

    private static void locate(Speaker s) {
        if (mc.world == null) return;
        for (Entity e : mc.world.getEntities()) {
            if (e.getUuid().equals(s.id)) {
                s.x = e.getX();
                s.y = e.getEyeY();
                s.z = e.getZ();
                s.located = true;
                return;
            }
        }
        // not (yet) in the world: keep the last place they were seen; never found, they're heard from the camera
        // rather than from the world's origin, which would usually be out of range and silent
        if (!s.located && unlocatedWarnings++ < 5) EvadeClient.LOG.info("Replay: voice from {} plays from the camera (no such entity in view)", s.id);
    }

    private static int unlocatedWarnings;

    // --- sound thread ---

    private static boolean ensureSource(Speaker s) {
        long ctx = ALC10.alcGetCurrentContext();
        if (ctx == 0L) return false;
        if (s.source != 0 && s.context == ctx) return true;
        // a new context (the sound engine restarted): the old source and buffers went with the old one
        s.buffers.clear();
        s.queued = 0;
        s.context = ctx;
        s.source = AL10.alGenSources();
        if (AL10.alGetError() != AL10.AL_NO_ERROR || s.source == 0) { s.source = 0; return false; }
        if (s.type == STATIC) {
            AL10.alSourcei(s.source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(s.source, AL10.AL_POSITION, 0, 0, 0);
            AL10.alSourcef(s.source, AL10.AL_ROLLOFF_FACTOR, 0);
        } else {
            // the same linear fall-off the game uses for its own sounds, out to the voice range
            AL10.alSourcei(s.source, AL10.AL_DISTANCE_MODEL, 53251 /* AL_LINEAR_DISTANCE */);
            AL10.alSourcef(s.source, AL10.AL_ROLLOFF_FACTOR, 1);
            AL10.alSourcef(s.source, AL10.AL_REFERENCE_DISTANCE, 0);
        }
        return true;
    }

    private static void update(Speaker s) {
        if (s.type == ENTITY && !s.located) {
            AL10.alSourcei(s.source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(s.source, AL10.AL_POSITION, 0, 0, 0);
        } else if (s.type != STATIC) {
            AL10.alSourcei(s.source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            AL10.alSource3f(s.source, AL10.AL_POSITION, (float) s.x, (float) s.y, (float) s.z);
            AL10.alSourcef(s.source, AL10.AL_MAX_DISTANCE, s.distance);
        }
        AL10.alSourcef(s.source, AL10.AL_GAIN, volume);
        AL10.alSourcef(s.source, AL10.AL_PITCH, ReplayAudio.timeScale());
    }

    private static void reclaim(Speaker s) {
        int done = AL10.alGetSourcei(s.source, AL10.AL_BUFFERS_PROCESSED);
        for (int i = 0; i < done; i++) {
            int b = AL10.alSourceUnqueueBuffers(s.source);
            AL10.alDeleteBuffers(b);
            s.buffers.remove((Integer) b);
            s.queued--;
        }
    }

    private static void queue(Speaker s, ByteBuffer pcm) {
        if (!ensureSource(s)) return;
        reclaim(s);
        int b = AL10.alGenBuffers();
        AL10.alBufferData(b, AL10.AL_FORMAT_MONO16, pcm, RATE);
        AL10.alSourceQueueBuffers(s.source, b);
        s.buffers.add(b);
        s.queued++;
        update(s);
        // a little cushion before starting, so frames that arrived unevenly still play back to back
        if (!paused && AL10.alGetSourcei(s.source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING && s.queued >= 3) {
            AL10.alSourcePlay(s.source);
            s.waitTicks = 0;
        }
    }

    private static void tickSource(Speaker s) {
        if (!ensureSource(s)) return;
        reclaim(s);
        update(s);
        int state = AL10.alGetSourcei(s.source, AL10.AL_SOURCE_STATE);
        if (paused) {
            if (state == AL10.AL_PLAYING) AL10.alSourcePause(s.source);
        } else if (state != AL10.AL_PLAYING && s.queued > 0 && (state == AL10.AL_PAUSED || ++s.waitTicks >= 2)) {
            AL10.alSourcePlay(s.source);
            s.waitTicks = 0;
        }
    }

    private static void delete(Speaker s) {
        if (s.source == 0 || s.context != ALC10.alcGetCurrentContext()) return;
        AL10.alSourceStop(s.source);
        reclaim(s);
        AL10.alDeleteSources(s.source);
        for (int b : s.buffers) AL10.alDeleteBuffers(b);
        s.buffers.clear();
        s.source = 0;
    }

    private static volatile float volume = 1;
    private static volatile boolean paused;

    /** Every client tick while a replay is open: follow the speakers, apply speed and pause, drop the silent. */
    static void tick(boolean replayPaused) {
        paused = replayPaused;
        volume = mc.options.getSoundVolume(SoundCategory.MASTER);
        if (speakers.isEmpty()) return;
        SoundExecutor exec = executor();
        for (Iterator<Speaker> it = speakers.values().iterator(); it.hasNext(); ) {
            Speaker s = it.next();
            if (s.type == ENTITY) locate(s);
            boolean drop = !replayPaused && ++s.idleTicks > 100;
            if (drop) it.remove();
            if (exec != null) exec.execute(drop ? () -> delete(s) : () -> tickSource(s));
        }
    }

    /** Seeking or leaving: silence everyone and start the Opus streams afresh. */
    static void clear() {
        Codec c = codec;
        if (c != null) c.resetDecoders();
        if (speakers.isEmpty()) return;
        SoundExecutor exec = executor();
        for (Speaker s : speakers.values()) if (exec != null) exec.execute(() -> delete(s));
        speakers.clear();
    }

    /** Voice frames written to the replay, and played back from one (for tests and diagnostics). */
    public static volatile int framesRecorded, framesPlayed;
    /** Where the last speaker played back was, and how far the voice carries (for tests and diagnostics). */
    public static volatile String lastSpeaker = "";

    /** Speakers currently known (for tests). */
    public static int speakerCount() { return speakers.size(); }
}
