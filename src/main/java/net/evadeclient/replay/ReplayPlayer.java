package net.evadeclient.replay;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.evadeclient.EvadeClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.ClientConfigurationNetworkHandler;
import net.minecraft.client.network.ClientConnectionState;
import net.minecraft.client.network.ClientDynamicRegistryType;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.world.ClientChunkLoadProgress;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.listener.ClientConfigurationPacketListener;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.common.CommonPingS2CPacket;
import net.minecraft.network.packet.s2c.common.CookieRequestS2CPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.common.KeepAliveS2CPacket;
import net.minecraft.network.packet.s2c.common.ResourcePackRemoveS2CPacket;
import net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket;
import net.minecraft.network.packet.s2c.common.ServerTransferS2CPacket;
import net.minecraft.network.packet.s2c.common.ShowDialogS2CPacket;
import net.minecraft.network.packet.s2c.common.StoreCookieS2CPacket;
import net.minecraft.network.packet.s2c.config.CodeOfConductS2CPacket;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.network.state.ConfigurationStates;
import net.minecraft.network.state.NetworkState;
import net.minecraft.network.state.PlayStateFactories;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.server.ServerLinks;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Plays a replay back as a world you can fly through. There's no server: a stand-in connection feeds the
 * recorded packets to the game's own network handlers, tick by tick, so everything (chunks, players, mobs,
 * items, particles, sounds) behaves exactly as it did. You watch as a spectator camera; the player who recorded
 * appears as a normal player, driven by their recorded movement.
 * <p>
 * Seeking forward fast-forwards; seeking back reloads and fast-forwards from the start.
 */
public final class ReplayPlayer {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    /** The camera's entity id: never one a server hands out. */
    public static final int CAMERA_ID = -1_700_000_000;
    private static final UUID CAMERA_UUID = UUID.fromString("e0ade000-0000-4000-8000-00000000ca3e");
    private static volatile ReplayPlayer current;

    public final Path file;
    public final ReplayFile.Meta meta;
    public final ReplayProject project;
    private ClientConnection connection;
    private EmbeddedChannel channel;
    private ReplayFile.Reader reader;
    private ReplayFile.Record next;
    /** Stream ticks applied so far (the replay's clock). */
    private int tick;
    private boolean playing;
    private double speed = 1;
    private int seekTarget = -1;
    private boolean restartPending;
    private boolean viewerTookOver;
    private DynamicRegistryManager boundTo;
    private NetworkState<ClientPlayPacketListener> playState;
    private OtherClientPlayerEntity self;
    private final Screen exitScreen;
    /** Tick → seconds shown to the viewer (paused stretches don't count). */
    private boolean closing;
    private int applied, failures;
    public String stats() { return applied + " records applied, " + failures + " skipped"; }

    private ReplayPlayer(Path file, ReplayFile.Meta meta, Screen exitScreen) {
        this.file = file;
        this.meta = meta;
        this.exitScreen = exitScreen;
        this.project = ReplayProject.load(meta, projectFile(file));
    }

    public static ReplayPlayer current() { return current; }

    /** Camera paths and scene settings live next to the replay, so editing never rewrites the recording. */
    public static Path projectFile(Path replay) { return replay.resolveSibling(replay.getFileName() + ".project.json"); }

    public void saveProject() {
        try { java.nio.file.Files.writeString(projectFile(file), ReplayFile.GSON.toJson(project.toJson())); }
        catch (IOException e) { EvadeClient.LOG.warn("Replay: couldn't save the project: {}", e.toString()); }
    }
    public static boolean active() { return current != null; }
    public static boolean isReplayConnection(ClientConnection c) { ReplayPlayer p = current; return p != null && p.connection == c; }

    /** Opens a replay: leaves any world, then loads the recording up to where it starts. */
    public static void open(Path file, Screen exitScreen) throws IOException {
        ReplayFile.Meta meta = ReplayFile.readMeta(file);
        if (current != null) current.close();
        if (mc.world != null) mc.disconnectWithProgressScreen();
        ReplayPlayer p = new ReplayPlayer(file, meta, exitScreen);
        current = p;
        p.connect();
        p.seekTarget = meta.startTick;
        mc.setScreen(new ReplayLoadingScreen(p));
    }

    private void connect() throws IOException {
        connection = new ClientConnection(NetworkSide.CLIENTBOUND);
        channel = new EmbeddedChannel(connection);
        GameProfile camera = new GameProfile(CAMERA_UUID, "Camera");
        ClientConnectionState state = new ClientConnectionState(new ClientChunkLoadProgress(), camera,
                mc.getTelemetryManager().createWorldSession(false, null, null),
                ClientDynamicRegistryType.createCombinedDynamicRegistries().getCombinedRegistryManager(),
                FeatureFlags.DEFAULT_ENABLED_FEATURES, null, null, exitScreen, new HashMap<>(), null, Map.of(), ServerLinks.EMPTY,
                new HashMap<>(), false);
        connection.transitionInbound(ConfigurationStates.S2C, new ClientConfigurationNetworkHandler(mc, connection, state));
        reader = new ReplayFile.Reader(file);
        next = reader.next();
        tick = 0;
        viewerTookOver = false;
        self = null;
        boundTo = null;
    }

    // ------------------------------------------------------------------ clock

    /** Start of every client tick. */
    public static void clientTick() {
        ReplayPlayer p = current;
        if (p == null || p.closing) return;
        try { p.tickInternal(); } catch (Exception e) {
            EvadeClient.LOG.error("Replay playback failed", e);
            net.evadeclient.util.Notifications.push("Replay", "Playback stopped: " + e.getMessage(), false);
            p.close();
        }
    }

    private void tickInternal() throws IOException {
        if (channel != null) channel.releaseOutbound(); // what the game "sends" goes nowhere
        if (restartPending) {
            restartPending = false;
            if (mc.world != null) mc.disconnect(new ReplayLoadingScreen(this), false);
            connect();
            mc.setScreen(new ReplayLoadingScreen(this));
        }
        PacketListener listener = connection.getPacketListener();
        // before the world exists, nothing else ticks the stand-in connection
        if (!(listener instanceof ClientPlayNetworkHandler) || mc.world == null) connection.tick();
        if (seekTarget >= 0) {
            fastForward();
            return;
        }
        if (mc.world == null) return;
        var tm = mc.world.getTickManager();
        tm.setTickRate((float) (20 * speed));
        tm.setFrozen(!playing);
        if (!playing) return;
        int end = endTick();
        if (tick >= end) { playing = false; return; }
        applyThrough(tick);
        tick++;
        // skip paused stretches
        for (int[] pz : meta.pauses) if (tick >= pz[0] && tick < pz[1]) { seekTarget = pz[1]; break; }
    }

    /** Applies records up to the seek target as fast as it can (a time budget per tick keeps the window alive). */
    private void fastForward() throws IOException {
        long until = System.nanoTime() + 40_000_000L;
        while (tick < seekTarget && System.nanoTime() < until) {
            applyThrough(tick);
            tick++;
            if (next == null) break;
        }
        if (tick >= seekTarget || next == null) {
            seekTarget = -1;
            if (mc.currentScreen instanceof ReplayLoadingScreen) mc.setScreen(null);
            placeCameraNearSelf();
            viewerTookOver = true;
        }
    }

    private void applyThrough(int t) throws IOException {
        while (next != null && next.tick() <= t) {
            ReplayFile.Record r = next;
            next = reader.next();
            try { apply(r); applied++; } catch (Exception e) {
                if (failures++ < 25) EvadeClient.LOG.warn("Replay: skipped a record (kind {}) at tick {}: {}", r.kind(), r.tick(), e.toString());
            }
        }
    }

    // ------------------------------------------------------------------ applying records

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void apply(ReplayFile.Record r) {
        PacketListener listener = connection.getPacketListener();
        switch (r.kind()) {
            case ReplayFile.CONFIG -> {
                if (!(listener instanceof ClientConfigurationPacketListener)) return;
                Packet<?> packet = (Packet<?>) ((NetworkState) ConfigurationStates.S2C).codec().decode(new PacketByteBuf(Unpooled.wrappedBuffer(r.data())));
                if (skipConfig(packet)) return;
                ((Packet) packet).apply(listener);
            }
            case ReplayFile.PLAY -> {
                if (!(listener instanceof ClientPlayNetworkHandler h)) return;
                Packet<?> packet = (Packet<?>) ((NetworkState) playState(h)).codec().decode(Unpooled.wrappedBuffer(r.data()));
                packet = rewrite(packet);
                if (packet == null) return;
                ((Packet) packet).apply(h);
                after(packet);
            }
            case ReplayFile.SELF -> self(r.data());
            case ReplayFile.SWING -> swing(r.data());
            case ReplayFile.EQUIP -> equip(r.data());
            // voices only while actually playing: fast-forwarding through a seek would queue them all at once
            case ReplayFile.VOICE -> { if (seekTarget < 0) ReplayVoice.play(r.data()); }
            default -> {}
        }
    }

    private NetworkState<ClientPlayPacketListener> playState(ClientPlayNetworkHandler h) {
        DynamicRegistryManager reg = h.getRegistryManager();
        if (boundTo != reg || playState == null) {
            playState = PlayStateFactories.S2C.bind(RegistryByteBuf.makeFactory(reg));
            boundTo = reg;
        }
        return playState;
    }

    private static boolean skipConfig(Packet<?> p) {
        return p instanceof KeepAliveS2CPacket || p instanceof CommonPingS2CPacket || p instanceof DisconnectS2CPacket
                || p instanceof ResourcePackSendS2CPacket || p instanceof ResourcePackRemoveS2CPacket || p instanceof StoreCookieS2CPacket
                || p instanceof CookieRequestS2CPacket || p instanceof ServerTransferS2CPacket || p instanceof ShowDialogS2CPacket
                || p instanceof CodeOfConductS2CPacket;
    }

    /** Drops what only made sense for the live player (screens, inventory, teleports, death screen...), adapts the rest. */
    private Packet<?> rewrite(Packet<?> p) {
        if (skipConfig(p)) return null;
        if (p instanceof GameJoinS2CPacket j) {
            return new GameJoinS2CPacket(CAMERA_ID, j.hardcore(), j.dimensionIds(), j.maxPlayers(), j.viewDistance(), j.simulationDistance(),
                    j.reducedDebugInfo(), false, j.doLimitedCrafting(), j.commonPlayerSpawnInfo(), false);
        }
        if (p instanceof PlayerPositionLookS2CPacket) return viewerTookOver ? null : p; // only to find the world at the start
        if (p instanceof GameStateChangeS2CPacket g) {
            var reason = g.getReason();
            if (reason == GameStateChangeS2CPacket.GAME_MODE_CHANGED || reason == GameStateChangeS2CPacket.GAME_WON
                    || reason == GameStateChangeS2CPacket.DEMO_MESSAGE_SHOWN) return null;
            return p;
        }
        if (p instanceof HealthUpdateS2CPacket || p instanceof ExperienceBarUpdateS2CPacket || p instanceof UpdateSelectedSlotS2CPacket
                || p instanceof PlayerAbilitiesS2CPacket || p instanceof LookAtS2CPacket || p instanceof SetCameraEntityS2CPacket
                || p instanceof DeathMessageS2CPacket || p instanceof OpenScreenS2CPacket || p instanceof OpenMountScreenS2CPacket
                || p instanceof OpenWrittenBookS2CPacket || p instanceof SignEditorOpenS2CPacket || p instanceof InventoryS2CPacket
                || p instanceof ScreenHandlerSlotUpdateS2CPacket || p instanceof SetPlayerInventoryS2CPacket || p instanceof SetCursorItemS2CPacket
                || p instanceof UpdateTickRateS2CPacket || p instanceof TickStepS2CPacket || p instanceof CloseScreenS2CPacket
                || p instanceof CooldownUpdateS2CPacket || p instanceof ScreenHandlerPropertyUpdateS2CPacket) return null;
        return p;
    }

    /** After joining or respawning, the camera is a spectator that flies through walls. */
    private void after(Packet<?> p) {
        if (p instanceof GameJoinS2CPacket || p instanceof PlayerRespawnS2CPacket) {
            if (mc.interactionManager != null) mc.interactionManager.setGameMode(GameMode.SPECTATOR);
            if (mc.player != null) { mc.player.getAbilities().flying = true; mc.player.setShowsDeathScreen(false); }
            self = null;
        }
    }

    // ------------------------------------------------------------------ the player who recorded

    private GameProfile selfProfile() {
        UUID id;
        try { id = UUID.fromString(meta.playerUuid); } catch (Exception e) { id = UUID.nameUUIDFromBytes(meta.playerName.getBytes()); }
        return new GameProfile(id, meta.playerName.isBlank() ? "Player" : meta.playerName);
    }

    private OtherClientPlayerEntity ensureSelf(int id, double x, double y, double z, float yaw, float pitch) {
        if (mc.world == null) return null;
        if (self != null && !self.isRemoved() && self.getId() == id && self.getEntityWorld() == mc.world) return self;
        if (self != null && !self.isRemoved()) self.discard();
        OtherClientPlayerEntity e = new OtherClientPlayerEntity(mc.world, selfProfile());
        e.setId(id);
        e.refreshPositionAndAngles(x, y, z, yaw, pitch);
        e.lastX = x; e.lastY = y; e.lastZ = z;
        mc.world.addEntity(e);
        self = e;
        return e;
    }

    public OtherClientPlayerEntity self() { return self; }

    private void self(byte[] data) {
        ClientPlayNetworkHandler h = mc.getNetworkHandler();
        if (h == null) return;
        var buf = new PacketByteBuf(Unpooled.wrappedBuffer(data));
        int id = buf.readVarInt();
        double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
        float yaw = buf.readFloat(), pitch = buf.readFloat(), headYaw = buf.readFloat();
        buf.readFloat(); // body yaw follows from movement
        buf.readVarInt(); // pose comes with the entity data the server sent
        int flags = buf.readVarInt();
        OtherClientPlayerEntity e = ensureSelf(id, x, y, z, yaw, pitch);
        if (e == null) return;
        e.updateTrackedPositionAndAngles(new Vec3d(x, y, z), yaw, pitch);
        e.updateTrackedHeadRotation(headYaw, 3);
        e.setSneaking((flags & 1) != 0);
        e.setSprinting((flags & 2) != 0);
        e.setOnGround((flags & 4) != 0);
    }

    private void swing(byte[] data) {
        var buf = new PacketByteBuf(Unpooled.wrappedBuffer(data));
        int id = buf.readVarInt();
        boolean off = buf.readBoolean();
        if (self != null && self.getId() == id) self.swingHand(off ? Hand.OFF_HAND : Hand.MAIN_HAND);
    }

    private void equip(byte[] data) {
        ClientPlayNetworkHandler h = mc.getNetworkHandler();
        if (h == null) return;
        var buf = new RegistryByteBuf(Unpooled.wrappedBuffer(data), h.getRegistryManager());
        int id = buf.readVarInt();
        int slot = buf.readVarInt();
        ItemStack stack = ItemStack.OPTIONAL_PACKET_CODEC.decode(buf);
        EquipmentSlot[] slots = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        if (self != null && self.getId() == id && slot >= 0 && slot < slots.length) self.equipStack(slots[slot], stack);
    }

    /** After loading or seeking: the camera just behind the recorded player, looking at them. */
    private void placeCameraNearSelf() {
        if (mc.player == null) return;
        if (self != null && !self.isRemoved()) {
            Vec3d look = Vec3d.fromPolar(0, self.getYaw());
            Vec3d at = self.getEntityPos().subtract(look.multiply(3)).add(0, 1.4, 0);
            mc.player.refreshPositionAndAngles(at.x, at.y, at.z, self.getYaw(), 15);
            mc.player.lastX = at.x; mc.player.lastY = at.y; mc.player.lastZ = at.z;
        }
        if (mc.interactionManager != null) mc.interactionManager.setGameMode(GameMode.SPECTATOR);
        mc.player.getAbilities().flying = true;
    }

    // ------------------------------------------------------------------ controls

    public int tick() { return tick; }
    public int startTick() { return meta.startTick; }
    public int endTick() { return meta.endTick > 0 ? meta.endTick : meta.durationTicks; }
    public boolean playing() { return playing; }
    public double speed() { return speed; }
    public boolean seeking() { return seekTarget >= 0 || restartPending; }
    public int seekTarget() { return seekTarget; }

    public void setPlaying(boolean play) {
        if (play && tick >= endTick()) seek(startTick());
        playing = play;
    }

    public void togglePlay() { setPlaying(!playing); }

    public void setSpeed(double s) { speed = Math.max(0.05, Math.min(16, s)); }

    /** Jumps to a stream tick: forward by fast-forwarding, back by reloading. */
    public void seek(int target) {
        target = Math.max(startTick(), Math.min(endTick(), target));
        if (target == tick) return;
        if (target < tick) restartPending = true;
        seekTarget = target;
        ReplayVoice.clear();
        viewerTookOver = false;
    }

    /** Seconds into the kept part (paused stretches don't count). */
    public double seconds(int streamTick) {
        int t = streamTick - startTick();
        for (int[] pz : meta.pauses) t -= Math.max(0, Math.min(pz[1], streamTick) - Math.max(pz[0], startTick()));
        return Math.max(0, t) / 20.0;
    }

    /** Leaves the replay (from the pause menu or when it fails). */
    public void close() {
        if (closing) return;
        closing = true;
        current = null;
        try { if (reader != null) reader.close(); } catch (IOException ignored) {}
        if (mc.world != null && mc.getNetworkHandler() != null && mc.getNetworkHandler().getConnection() == connection) {
            mc.disconnect(exitScreen != null ? exitScreen : new TitleScreen(), false);
        }
        if (channel != null) channel.close();
        ReplayOverrides.reset();
        ReplayVoice.clear();
    }

    /** The game left the world itself (pause menu "Disconnect"). */
    public static void onDisconnect(ClientConnection c) {
        ReplayPlayer p = current;
        if (p != null && p.connection == c && !p.restartPending && !p.closing) {
            p.closing = true;
            current = null;
            try { if (p.reader != null) p.reader.close(); } catch (IOException ignored) {}
            ReplayOverrides.reset();
            ReplayVoice.clear();
        }
    }
}
