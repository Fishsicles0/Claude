package net.evadeclient.replay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.evadeclient.EvadeClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientConfigurationNetworkHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.network.state.ConfigurationStates;
import net.minecraft.network.state.NetworkState;
import net.minecraft.network.state.PlayStateFactories;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.util.Hand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Records what's needed to replay a session in 3D: every packet the server sends (encoded exactly as the game
 * does, from the first configuration packet), and the local player's own movement, swings and equipment (the
 * server never sends you your own). Works the same in multiplayer and singleplayer.
 * <p>
 * The whole session since joining is captured to a temporary file while the Replay module is on; "Start
 * recording" marks where the saved replay begins and "Save" writes it, so you can start recording mid-match.
 */
public final class ReplayRecorder {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static volatile Session session;

    private ReplayRecorder() {}

    /** One connection's capture. */
    static final class Session {
        final ClientConnection connection;
        final ReplayFile.StreamWriter writer;
        final long started = System.currentTimeMillis();
        volatile int tick;
        // what the player chose to keep
        volatile boolean recording;
        int startTick, pausedSince = -1;
        final List<int[]> pauses = new ArrayList<>();
        final List<ReplayFile.Marker> markers = new ArrayList<>();
        // encoder for play packets, rebuilt when the registries change (joining, reconfiguring)
        DynamicRegistryManager boundTo;
        NetworkState<ClientPlayPacketListener> playState;
        // last written self state, to only write equipment changes
        final ItemStack[] lastEquip = new ItemStack[6];
        boolean lastSwinging;
        boolean failed;

        Session(ClientConnection connection, Path tmp) throws IOException {
            this.connection = connection;
            this.writer = new ReplayFile.StreamWriter(tmp);
        }
    }

    public static boolean active() { return session != null && !session.failed; }
    public static boolean recording() { Session s = session; return s != null && s.recording; }
    public static boolean paused() { Session s = session; return s != null && s.recording && s.pausedSince >= 0; }
    /** Seconds of the replay being kept so far. */
    public static int recordedTicks() {
        Session s = session;
        if (s == null || !s.recording) return 0;
        int t = s.tick - s.startTick;
        for (int[] p : s.pauses) t -= p[1] - p[0];
        if (s.pausedSince >= 0) t -= s.tick - s.pausedSince;
        return Math.max(0, t);
    }

    static boolean enabled() {
        var r = EvadeClient.modules == null ? null : EvadeClient.modules.get(net.evadeclient.module.misc.Replay.class);
        return r != null && r.isEnabled();
    }

    // ------------------------------------------------------------------ packets (network thread)

    /** From the connection mixin, before a received packet is handled. */
    public static void onPacket(ClientConnection connection, Packet<?> packet) {
        if (ReplayPlayer.isReplayConnection(connection)) return;
        var listener = connection.getPacketListener();
        boolean config = listener instanceof ClientConfigurationNetworkHandler;
        boolean play = listener instanceof ClientPlayNetworkHandler;
        if (!config && !play) return;
        Session s = session;
        if (s == null || s.connection != connection) {
            if (!enabled()) return;
            // a session must start at the beginning (configuration), or there's no world to rebuild
            if (!config) return;
            s = begin(connection);
            if (s == null) return;
        }
        if (s.failed) return;
        try {
            if (config) {
                write(s, ReplayFile.CONFIG, encodeConfig(packet));
            } else {
                ClientPlayNetworkHandler h = (ClientPlayNetworkHandler) listener;
                NetworkState<ClientPlayPacketListener> state = playState(s, h.getRegistryManager());
                if (packet instanceof BundleS2CPacket bundle) {
                    for (Packet<? super ClientPlayPacketListener> p : bundle.getPackets()) write(s, ReplayFile.PLAY, encode(state, p));
                } else write(s, ReplayFile.PLAY, encode(state, packet));
            }
        } catch (Throwable t) {
            // one odd packet (a mod's custom payload the client can't re-encode) shouldn't end the recording
            EvadeClient.LOG.debug("Replay: skipped {}: {}", packet.getClass().getSimpleName(), t.toString());
        }
    }

    private static synchronized Session begin(ClientConnection connection) {
        Session old = session;
        if (old != null && old.connection == connection) return old;
        if (old != null) end(false);
        try {
            Path tmp = ReplayFile.dir().resolve(".capture").resolve("session-" + System.currentTimeMillis() + ".bin");
            Session s = new Session(connection, tmp);
            session = s;
            var r = EvadeClient.modules.get(net.evadeclient.module.misc.Replay.class);
            if (r != null && r.autoRecord()) { s.recording = true; s.startTick = 0; }
            return s;
        } catch (IOException e) {
            EvadeClient.LOG.warn("Replay: can't capture this session: {}", e.toString());
            return null;
        }
    }

    private static NetworkState<ClientPlayPacketListener> playState(Session s, DynamicRegistryManager registries) {
        if (s.boundTo != registries || s.playState == null) {
            s.playState = PlayStateFactories.S2C.bind(RegistryByteBuf.makeFactory(registries));
            s.boundTo = registries;
        }
        return s.playState;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static byte[] encodeConfig(Packet<?> packet) {
        ByteBuf buf = Unpooled.buffer();
        try {
            ((NetworkState) ConfigurationStates.S2C).codec().encode(buf, packet);
            return bytes(buf);
        } finally { buf.release(); }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static byte[] encode(NetworkState<ClientPlayPacketListener> state, Packet<?> packet) {
        ByteBuf buf = Unpooled.buffer();
        try {
            ((NetworkState) state).codec().encode(buf, packet);
            return bytes(buf);
        } finally { buf.release(); }
    }

    private static byte[] bytes(ByteBuf buf) {
        byte[] b = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), b);
        return b;
    }

    private static void write(Session s, byte kind, byte[] data) {
        try {
            s.writer.write(kind, s.tick, data);
        } catch (IOException e) {
            s.failed = true;
            EvadeClient.LOG.warn("Replay: recording stopped: {}", e.toString());
        }
    }

    /** Voice chat audio (any thread): goes in at the current tick of the live session, if there is one. */
    static void voice(byte[] data) {
        Session s = session;
        if (s == null || s.failed || ReplayPlayer.active()) return;
        write(s, ReplayFile.VOICE, data);
    }

    /** The local player's UUID while a live session is being captured (for recording your own voice). */
    static java.util.UUID livePlayer() {
        Session s = session;
        return s == null || s.failed || mc.player == null || ReplayPlayer.active() ? null : mc.player.getUuid();
    }

    // ------------------------------------------------------------------ the local player (client thread)

    /** End of every client tick: write where you are, then move the clock on. */
    public static void tick() {
        Session s = session;
        if (s == null) return;
        ClientPlayerEntity p = mc.player;
        if (p != null && p.networkHandler != null && p.networkHandler.getConnection() == s.connection && !s.failed) {
            writeSelf(s, p);
        }
        s.tick++;
    }

    private static void writeSelf(Session s, ClientPlayerEntity p) {
        DynamicRegistryManager reg = p.networkHandler.getRegistryManager();
        RegistryByteBuf buf = new RegistryByteBuf(Unpooled.buffer(64), reg);
        try {
            buf.writeVarInt(p.getId());
            buf.writeDouble(p.getX());
            buf.writeDouble(p.getY());
            buf.writeDouble(p.getZ());
            buf.writeFloat(p.getYaw());
            buf.writeFloat(p.getPitch());
            buf.writeFloat(p.getHeadYaw());
            buf.writeFloat(p.getBodyYaw());
            buf.writeVarInt(p.getPose().ordinal());
            int flags = (p.isSneaking() ? 1 : 0) | (p.isSprinting() ? 2 : 0) | (p.isOnGround() ? 4 : 0) | (p.isUsingItem() ? 8 : 0)
                    | (p.getActiveHand() == Hand.OFF_HAND ? 16 : 0) | (p.isGliding() ? 32 : 0) | (p.isSwimming() ? 64 : 0);
            buf.writeVarInt(flags);
            write(s, ReplayFile.SELF, bytes(buf));
        } finally { buf.release(); }
        // swings start the arm animation on the recorded player
        if (p.handSwinging && !s.lastSwinging && p.handSwingTicks <= 1) {
            RegistryByteBuf sb = new RegistryByteBuf(Unpooled.buffer(4), reg);
            try {
                sb.writeVarInt(p.getId());
                sb.writeBoolean(p.preferredHand == Hand.OFF_HAND);
                write(s, ReplayFile.SWING, bytes(sb));
            } finally { sb.release(); }
        }
        s.lastSwinging = p.handSwinging;
        // equipment, only when it changes
        EquipmentSlot[] slots = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < slots.length; i++) {
            ItemStack now = p.getEquippedStack(slots[i]);
            if (s.lastEquip[i] != null && ItemStack.areEqual(s.lastEquip[i], now)) continue;
            s.lastEquip[i] = now.copy();
            RegistryByteBuf eb = new RegistryByteBuf(Unpooled.buffer(32), reg);
            try {
                eb.writeVarInt(p.getId());
                eb.writeVarInt(i);
                ItemStack.OPTIONAL_PACKET_CODEC.encode(eb, now);
                write(s, ReplayFile.EQUIP, bytes(eb));
            } catch (Exception e) {
                // an item the client can't re-encode: skip it
            } finally { eb.release(); }
        }
    }

    // ------------------------------------------------------------------ what the player controls

    /** Starts keeping the replay from now (the session since joining is already captured). */
    public static boolean start() {
        Session s = session;
        if (s == null || s.failed) return false;
        if (s.recording) return true;
        s.recording = true;
        s.startTick = s.tick;
        s.pauses.clear();
        s.markers.clear();
        s.pausedSince = -1;
        return true;
    }

    public static void togglePause() {
        Session s = session;
        if (s == null || !s.recording) return;
        if (s.pausedSince >= 0) { s.pauses.add(new int[]{s.pausedSince, s.tick}); s.pausedSince = -1; }
        else s.pausedSince = s.tick;
    }

    public static void marker(String label) {
        Session s = session;
        if (s == null || !s.recording) return;
        s.markers.add(new ReplayFile.Marker(s.tick, label == null || label.isBlank() ? "Marker" : label));
    }

    /** Saves the kept part as a replay file (in the background). Recording stops; the capture carries on. */
    public static Path save() {
        Session s = session;
        if (s == null || !s.recording) return null;
        s.recording = false;
        if (s.pausedSince >= 0) { s.pauses.add(new int[]{s.pausedSince, s.tick}); s.pausedSince = -1; }
        ReplayFile.Meta meta = new ReplayFile.Meta();
        meta.minecraft = net.minecraft.SharedConstants.getGameVersion().name();
        meta.evade = EvadeClient.VERSION;
        var entry = mc.getCurrentServerEntry();
        meta.server = entry != null ? entry.address : "";
        meta.world = mc.isInSingleplayer() && mc.getServer() != null ? mc.getServer().getSaveProperties().getLevelName() : "";
        String where = !meta.server.isBlank() ? meta.server : !meta.world.isBlank() ? meta.world : "Replay";
        meta.name = where + " " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm"));
        meta.recordedAt = System.currentTimeMillis();
        meta.durationTicks = s.tick;
        meta.startTick = s.startTick;
        meta.endTick = s.tick;
        meta.markers.addAll(s.markers);
        meta.pauses.addAll(s.pauses);
        if (mc.player != null) {
            meta.playerName = mc.player.getGameProfile().name();
            meta.playerUuid = mc.player.getUuidAsString();
        }
        Path target = uniqueFile(meta.name);
        try { s.writer.sync(); } catch (IOException e) { return null; }
        // copy what's on disk now, off the game thread
        Path snapshot;
        try {
            snapshot = s.writer.file().resolveSibling(s.writer.file().getFileName() + ".save-" + System.nanoTime());
            Files.copy(s.writer.file(), snapshot);
        } catch (IOException e) {
            EvadeClient.LOG.warn("Replay: couldn't save: {}", e.toString());
            return null;
        }
        Thread t = new Thread(() -> {
            try {
                ReplayFile.save(target, meta, snapshot);
                mc.execute(() -> net.evadeclient.util.Notifications.push("Replay saved", target.getFileName().toString().replace("." + ReplayFile.EXT, ""), true));
            } catch (IOException e) {
                mc.execute(() -> net.evadeclient.util.Notifications.push("Replay not saved", String.valueOf(e.getMessage()), false));
            } finally {
                try { Files.deleteIfExists(snapshot); } catch (IOException ignored) {}
            }
        }, "Evade replay save");
        t.setDaemon(false);
        t.start();
        lastSaved = target;
        return target;
    }

    /** The last replay saved this session (tests and the "open last" button). */
    public static volatile Path lastSaved;

    private static Path uniqueFile(String name) {
        String safe = name.replaceAll("[\\\\/:*?\"<>|]+", "_").trim();
        Path p = ReplayFile.dir().resolve(safe + "." + ReplayFile.EXT);
        for (int i = 2; Files.exists(p); i++) p = ReplayFile.dir().resolve(safe + " (" + i + ")." + ReplayFile.EXT);
        return p;
    }

    /** The connection closed (or a new one started): save if recording, drop the capture. */
    public static synchronized void end(boolean saveIfRecording) {
        Session s = session;
        if (s == null) return;
        if (saveIfRecording && s.recording) save();
        session = null;
        try { s.writer.close(); } catch (IOException ignored) {}
        // the save copies the stream first, so the capture file can go now
        try { Files.deleteIfExists(s.writer.file()); } catch (IOException ignored) {}
    }

    /** Leftover captures from a crash. */
    public static void cleanUp() {
        try (var st = Files.list(ReplayFile.dir().resolve(".capture"))) {
            st.forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}
