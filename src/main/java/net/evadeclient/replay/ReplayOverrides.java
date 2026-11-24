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
        if (s.timeOfDay >= 0) mc.world.getLevelProperties().setTimeOfDay(s.timeOfDay);
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
