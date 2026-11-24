package net.evadeclient.replay;

import net.evadeclient.EvadeClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Everything a replay changes about how the world looks, read by the render hooks: the camera path (position,
 * rotation, roll, field of view), who the camera looks at, and the scene overrides (time, weather, fog, name
 * tags, HUD, hidden players and mobs, green screen).
 */
public final class ReplayOverrides {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private ReplayOverrides() {}

    /** Set while a camera path is driving the camera this frame. */
    private static volatile float roll, fov;
    private static volatile boolean pathActive;
    private static Boolean hudBefore;
    private static Boolean noFogBefore;

    private static ReplayProject project() {
        ReplayPlayer p = ReplayPlayer.current();
        return p == null ? null : p.project;
    }

    public static boolean active() { return ReplayPlayer.active() && mc.world != null; }

    // ------------------------------------------------------------------ per frame

    /** Every frame, before the world is drawn: move the camera along the path, then apply look-at. */
    public static void onFrame(RenderTickCounter counter) {
        pathActive = false;
        roll = 0;
        fov = 0;
        ReplayPlayer p = ReplayPlayer.current();
        if (p == null || mc.player == null || mc.world == null || p.seeking()) return;
        // Player POV: the camera is that player, so it goes where they went and looks where they looked
        if (applyPov(p) != null) { flyPos = null; return; }
        ReplayProject pr = p.project;
        float delta = counter.getTickProgress(true);
        double t = p.tick() - 1 + delta;
        boolean usePath = pr.hasPath() && (ReplayRender.rendering() || (pr.previewPath && p.playing()));
        float yaw = mc.player.getYaw(), pitch = mc.player.getPitch();
        // the free camera flies at normal speed whatever the replay's speed (the game's own movement slows with it)
        if (!usePath && !ReplayRender.rendering() && Math.abs(p.speed() - 1) > 1e-3) flyCamera();
        else flyPos = null;
        if (usePath) {
            ReplayProject.CamKey k = pr.sample(t);
            setCamera(k.x, k.y, k.z);
            yaw = k.yaw;
            pitch = k.pitch;
            roll = k.roll;
            fov = k.fov;
            pathActive = true;
        }
        // track entity: the camera rides along at the offset it had when following started (a path still wins)
        Entity tracked = usePath ? null : byUuid(pr.follow);
        if (tracked != null) {
            Vec3d at = tracked.getLerpedPos(delta);
            setCamera(at.x + pr.followX, at.y + pr.followY, at.z + pr.followZ);
            flyPos = null;
        }
        Entity target = lookTarget(pr);
        if (target != null) {
            Vec3d eye = target.getLerpedPos(delta).add(0, target.getStandingEyeHeight() * 0.85, 0);
            Vec3d cam = mc.player.getLerpedPos(delta).add(0, mc.player.getStandingEyeHeight(), 0);
            Vec3d d = eye.subtract(cam);
            yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90);
            pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        }
        if (usePath || target != null) {
            mc.player.setYaw(yaw);
            mc.player.setPitch(MathHelper.clamp(pitch, -90, 90));
            mc.player.lastYaw = yaw;
            mc.player.lastPitch = mc.player.getPitch();
            mc.player.setHeadYaw(yaw);
        }
    }

    // ------------------------------------------------------------------ player POV

    /** The player the camera sees through, if POV is on and they're in the world right now. */
    public static net.minecraft.entity.player.PlayerEntity povTarget(ReplayPlayer p) {
        String id = p == null ? null : p.project.pov;
        if (id == null || id.isBlank() || mc.world == null) return null;
        for (var e : mc.world.getPlayers()) if (e != mc.player && e.getUuidAsString().equals(id)) return e;
        return null;
    }

    /**
     * Makes the game's camera entity the POV player (or the free camera again), and returns the POV player. Re-run
     * every frame and tick: seeking back rebuilds the world, so the player is found again by UUID.
     */
    static Entity applyPov(ReplayPlayer p) {
        if (mc.player == null) return null;
        Entity target = p == null || p.seeking() ? null : povTarget(p);
        Entity want = target != null ? target : mc.player;
        if (mc.getCameraEntity() != want) mc.setCameraEntity(want);
        if (target instanceof net.minecraft.entity.player.PlayerEntity t) mirrorHands(t);
        else if (handsMirrored) clearHands();
        return target;
    }

    private static boolean handsMirrored;

    /**
     * The first-person hand is drawn from the game's own player (the replay camera), so in POV it takes on the watched
     * player's held items, swing and where they look; their skin comes from ReplayPovSkinMixin.
     */
    private static void mirrorHands(net.minecraft.entity.player.PlayerEntity t) {
        var me = mc.player;
        for (var slot : new net.minecraft.entity.EquipmentSlot[]{net.minecraft.entity.EquipmentSlot.MAINHAND, net.minecraft.entity.EquipmentSlot.OFFHAND}) {
            var want = t.getEquippedStack(slot);
            if (!net.minecraft.item.ItemStack.areEqual(me.getEquippedStack(slot), want)) me.equipStack(slot, want.copy());
        }
        me.handSwinging = t.handSwinging;
        me.handSwingTicks = t.handSwingTicks;
        me.handSwingProgress = t.handSwingProgress;
        me.lastHandSwingProgress = t.lastHandSwingProgress;
        me.preferredHand = t.preferredHand;
        me.setYaw(t.getYaw());
        me.setPitch(t.getPitch());
        me.lastYaw = t.lastYaw;
        me.lastPitch = t.lastPitch;
        me.renderYaw = me.lastRenderYaw = t.getYaw();
        me.renderPitch = me.lastRenderPitch = t.getPitch();
        // the hand is lit by the light where the player stands
        me.setPosition(t.getX(), t.getY(), t.getZ());
        handsMirrored = true;
    }

    private static void clearHands() {
        if (mc.player != null) {
            mc.player.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, net.minecraft.item.ItemStack.EMPTY);
            mc.player.equipStack(net.minecraft.entity.EquipmentSlot.OFFHAND, net.minecraft.item.ItemStack.EMPTY);
        }
        handsMirrored = false;
    }

    /** The player watched through in first person (hands drawn), or null. */
    public static net.minecraft.client.network.AbstractClientPlayerEntity povPlayer() {
        if (!active() || !mc.options.getPerspective().isFirstPerson()) return null;
        return povTarget(ReplayPlayer.current()) instanceof net.minecraft.client.network.AbstractClientPlayerEntity a ? a : null;
    }

    /** The players you can watch through: whoever recorded first, then everyone else by name. */
    static java.util.List<net.minecraft.entity.player.PlayerEntity> povCandidates(ReplayPlayer p) {
        java.util.List<net.minecraft.entity.player.PlayerEntity> list = new java.util.ArrayList<>();
        if (mc.world == null) return list;
        for (var e : mc.world.getPlayers()) if (e != mc.player) list.add(e);
        Entity self = p.self();
        list.sort(java.util.Comparator.<net.minecraft.entity.player.PlayerEntity>comparingInt(e -> e == self ? 0 : 1)
                .thenComparing(e -> e.getName().getString(), String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    /** Next view: the recorded player's eyes, then each other player's, then back to the free camera. Returns its name. */
    public static String cyclePov(ReplayPlayer p) {
        var list = povCandidates(p);
        int at = -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).getUuidAsString().equals(p.project.pov)) at = i;
        if (at + 1 < list.size()) {
            var next = list.get(at + 1);
            // the camera starts where they are, so leaving POV later carries on from their view
            p.project.pov = next.getUuidAsString();
            mc.player.refreshPositionAndAngles(next.getX(), next.getY(), next.getZ(), next.getYaw(), next.getPitch());
        } else {
            p.project.pov = "";
        }
        applyPov(p);
        return povName(p);
    }

    /** "Steve's eyes", or "Free camera". */
    public static String povName(ReplayPlayer p) {
        if (p == null || p.project.pov == null || p.project.pov.isBlank()) return "Free camera";
        var t = povTarget(p);
        return (t != null ? t.getName().getString() : "Player") + "'s eyes";
    }

    // ------------------------------------------------------------------ free camera

    private static Vec3d flyPos, flyVel = Vec3d.ZERO;
    private static long flyLast;

    /** Spectator-style flying, moved by real time each frame instead of by (slowed or sped-up) game ticks. */
    private static void flyCamera() {
        long now = System.nanoTime();
        if (flyPos == null) {
            flyPos = mc.player.getEyePos();
            flyVel = Vec3d.ZERO;
            flyLast = now;
        }
        double dt = Math.min(0.1, (now - flyLast) / 1e9);
        flyLast = now;
        var o = mc.options;
        boolean keys = mc.currentScreen == null;
        double f = keys ? (o.forwardKey.isPressed() ? 1 : 0) - (o.backKey.isPressed() ? 1 : 0) : 0;
        double s = keys ? (o.leftKey.isPressed() ? 1 : 0) - (o.rightKey.isPressed() ? 1 : 0) : 0;
        double u = keys ? (o.jumpKey.isPressed() ? 1 : 0) - (o.sneakKey.isPressed() ? 1 : 0) : 0;
        double yaw = Math.toRadians(mc.player.getYaw()), sin = Math.sin(yaw), cos = Math.cos(yaw);
        double dx = s * cos - f * sin, dz = f * cos + s * sin;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 1) { dx /= len; dz /= len; }
        // the game's spectator speed: ~11 blocks/s at the default fly speed (scroll changes it), double when sprinting
        // sprinting as the game sees it: the key, or Toggle Sprint (which holds sprint without pressing the key)
        boolean sprint = keys && (o.sprintKey.isPressed() || mc.player.input.playerInput.sprint());
        double speed = mc.player.getAbilities().getFlySpeed() / 0.05 * 10.9 * (sprint ? 2 : 1);
        Vec3d target = new Vec3d(dx * speed, u * speed * 0.7, dz * speed);
        flyVel = flyVel.lerp(target, 1 - Math.exp(-dt * 10));
        flyPos = flyPos.add(flyVel.multiply(dt));
        setCamera(flyPos.x, flyPos.y, flyPos.z);
    }

    /** Puts the camera exactly at a point this frame (no interpolation from last tick). */
    private static void setCamera(double x, double y, double z) {
        double eye = mc.player.getStandingEyeHeight();
        mc.player.setPosition(x, y - eye, z);
        mc.player.lastX = x; mc.player.lastY = y - eye; mc.player.lastZ = z;
        mc.player.lastRenderX = x; mc.player.lastRenderY = y - eye; mc.player.lastRenderZ = z;
        mc.player.setVelocity(Vec3d.ZERO);
    }

    private static Entity byUuid(String id) {
        if (id == null || id.isBlank() || mc.world == null) return null;
        for (Entity e : mc.world.getEntities()) if (e != mc.player && e.getUuidAsString().equals(id)) return e;
        return null;
    }

    /** Starts following {@code e} from where the camera is now (or stops, for null). */
    public static void follow(ReplayProject pr, Entity e) {
        if (e == null || mc.player == null) { pr.follow = ""; return; }
        Vec3d eye = mc.player.getEyePos(), at = e.getEntityPos();
        pr.follow = e.getUuidAsString();
        pr.followX = eye.x - at.x;
        pr.followY = eye.y - at.y;
        pr.followZ = eye.z - at.z;
    }

    private static Entity lookTarget(ReplayProject pr) {
        if (pr.lookAt == null || pr.lookAt.isBlank() || mc.world == null) return null;
        for (Entity e : mc.world.getEntities()) if (e.getUuidAsString().equals(pr.lookAt)) return e;
        return null;
    }

    /** Roll in radians for the camera (0 without a path). */
    public static float roll() { return pathActive ? (float) Math.toRadians(roll) : 0; }

    /** Field of view from the path, or 0 to leave it alone. */
    public static float fov() { return pathActive ? fov : 0; }

    // ------------------------------------------------------------------ per tick

    /** After each replay tick: time of day, weather, fog, HUD. */
    public static void onTick() {
        ReplayProject pr = project();
        if (pr == null || mc.world == null) return;
        ReplayProject.Scene s = pr.scene;
        ReplayPlayer rp = ReplayPlayer.current();
        double keyed = rp == null ? -1 : s.timeAt(rp.tick());
        if (keyed >= 0) mc.world.getLevelProperties().setTimeOfDay((long) keyed);
        else if (s.timeOfDay >= 0) mc.world.getLevelProperties().setTimeOfDay(s.timeOfDay);
        if (s.clearWeather) { mc.world.setRainGradient(0); mc.world.setThunderGradient(0); }
        // the HUD stays while watching (the playback bar lives there); renders leave it out unless asked
        if (ReplayRender.rendering()) {
            if (hudBefore == null) hudBefore = mc.options.hudHidden;
            mc.options.hudHidden = s.hideHud;
        } else if (hudBefore != null) {
            mc.options.hudHidden = hudBefore;
            hudBefore = null;
        }
        var noFog = EvadeClient.modules == null ? null : EvadeClient.modules.get(net.evadeclient.module.visuals.NoFog.class);
        if (noFog != null) {
            if (noFogBefore == null) noFogBefore = noFog.isEnabled();
            boolean want = s.noFog || noFogBefore;
            if (noFog.isEnabled() != want) noFog.setEnabled(want);
        }
    }

    /** Leaving the replay: put the player's own settings back. */
    public static void reset() {
        if (mc.player != null && mc.getCameraEntity() != mc.player) mc.setCameraEntity(mc.player);
        if (hudBefore != null) { mc.options.hudHidden = hudBefore; hudBefore = null; }
        var noFog = EvadeClient.modules == null ? null : EvadeClient.modules.get(net.evadeclient.module.visuals.NoFog.class);
        if (noFog != null && noFogBefore != null && noFog.isEnabled() != noFogBefore) noFog.setEnabled(noFogBefore);
        noFogBefore = null;
        pathActive = false;
    }

    // ------------------------------------------------------------------ read by render hooks

    public static boolean hideNametags() { ReplayProject pr = active() ? project() : null; return pr != null && pr.scene.hideNametags; }
    public static boolean greenScreen() { ReplayProject pr = active() ? project() : null; return pr != null && pr.scene.greenScreen; }

    public static boolean hidden(Entity e) {
        if (!active()) return false;
        ReplayPlayer p = ReplayPlayer.current();
        if (p == null) return false;
        if (p.project.scene.hideSelf && e == p.self()) return true;
        return !p.project.scene.hidden.isEmpty() && p.project.scene.hidden.contains(e.getUuidAsString());
    }
}
