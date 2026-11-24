package net.evadeclient.replay;

import com.google.gson.JsonObject;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What you build in the replay editor, saved inside the replay: camera keyframes (a smooth path through them),
 * speed keyframes (slow motion and time-lapse), who the camera looks at, scene overrides and render settings.
 */
public final class ReplayProject {
    /** A camera keyframe at a replay (stream) tick. */
    public static final class CamKey {
        public double tick;
        public double x, y, z;
        public float yaw, pitch, roll;
        public float fov = 70;

        public CamKey() {}
        public CamKey(double tick, double x, double y, double z, float yaw, float pitch, float roll, float fov) {
            this.tick = tick; this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch; this.roll = roll; this.fov = fov;
        }
        public Vec3d pos() { return new Vec3d(x, y, z); }
    }

    /** Playback speed from this tick on (eased into from the previous keyframe). */
    public static final class SpeedKey {
        public double tick;
        public double speed = 1;
        public SpeedKey() {}
        public SpeedKey(double tick, double speed) { this.tick = tick; this.speed = speed; }
    }

    /** Scene overrides: applied while watching and in renders. */
    public static final class Scene {
        /** 0..24000, or -1 to keep the recorded time. */
        public int timeOfDay = -1;
        public boolean clearWeather, noFog, hideNametags, hideHud = true, greenScreen, hideSelf;
        /** UUIDs of players and mobs to hide. */
        public List<String> hidden = new ArrayList<>();
        /** HUD mods (by name) left out while watching this replay and in its videos. */
        public List<String> hiddenHud = new ArrayList<>();
        /** Time-of-day keyframes: the sky moves on from one to the next (always forwards), e.g. a sunset timelapse. */
        public List<TimeKey> timeKeys = new ArrayList<>();

        /** The time of day at replay tick {@code t} from the keyframes, or -1 without any. */
        public double timeAt(double t) {
            if (timeKeys == null || timeKeys.isEmpty()) return -1;
            List<TimeKey> k = new ArrayList<>(timeKeys);
            k.sort(java.util.Comparator.comparingDouble(x -> x.tick));
            if (t <= k.get(0).tick) return k.get(0).time;
            for (int i = 1; i < k.size(); i++) {
                TimeKey a = k.get(i - 1), b = k.get(i);
                if (t <= b.tick) {
                    double to = b.time;
                    while (to < a.time) to += 24000; // forwards round the clock
                    double f = (t - a.tick) / Math.max(1e-6, b.tick - a.tick);
                    return (a.time + (to - a.time) * f) % 24000;
                }
            }
            return k.get(k.size() - 1).time;
        }
    }

    public static final class TimeKey {
        public double tick;
        public int time;
        public TimeKey() {}
        public TimeKey(double tick, int time) { this.tick = tick; this.time = time; }
    }

    public static final class Render {
        public int fps = 60;
        /** 0 = the game window's size. */
        public int width, height;
        /** 14 (near lossless) .. 30 (small). */
        public int quality = 18;
        /** Stream ticks; -1 = the camera path's first / last keyframe (or the whole replay). */
        public int from = -1, to = -1;
        /** "sounds" (game sounds), "music" (game sounds and music) or "off". */
        public String audio = "sounds";
        /** Slow motion slows sound down too (lower and longer, like tape); off keeps sounds at normal speed. */
        public boolean slowSound = true;
    }

    public List<CamKey> camera = new ArrayList<>();
    public List<SpeedKey> speeds = new ArrayList<>();
    /** The camera keeps looking at this entity (UUID), "" for none. */
    public String lookAt = "";
    /** Track entity: the camera follows this player or mob (UUID) at a fixed offset, "" for none. */
    public String follow = "";
    /** The camera's offset from the followed entity's position, taken when following started. */
    public double followX, followY, followZ;
    /** Player POV: the UUID of the player whose eyes the camera sees through, "" for the free camera. */
    public String pov = "";
    /** Play the camera path while watching (otherwise you fly freely). */
    public boolean previewPath = true;
    public Scene scene = new Scene();
    public Render render = new Render();

    static ReplayProject load(ReplayFile.Meta meta, java.nio.file.Path sidecar) {
        ReplayProject p = null;
        if (sidecar != null && java.nio.file.Files.exists(sidecar)) {
            try { p = ReplayFile.GSON.fromJson(java.nio.file.Files.readString(sidecar), ReplayProject.class); } catch (Exception ignored) {}
        }
        if (p == null && meta.project != null) {
            try { p = ReplayFile.GSON.fromJson(meta.project, ReplayProject.class); } catch (Exception ignored) {}
        }
        if (p == null) p = new ReplayProject();
        if (p.camera == null) p.camera = new ArrayList<>();
        if (p.speeds == null) p.speeds = new ArrayList<>();
        if (p.scene == null) p.scene = new Scene();
        if (p.scene.hidden == null) p.scene.hidden = new ArrayList<>();
        if (p.render == null) p.render = new Render();
        if (p.lookAt == null) p.lookAt = "";
        return p;
    }

    public JsonObject toJson() { return ReplayFile.GSON.toJsonTree(this).getAsJsonObject(); }

    public void sort() {
        camera.sort(Comparator.comparingDouble(k -> k.tick));
        speeds.sort(Comparator.comparingDouble(k -> k.tick));
    }

    public boolean hasPath() { return camera.size() >= 2; }

    // ------------------------------------------------------------------ camera path

    /**
     * The camera at a (fractional) tick: a Catmull-Rom curve through the keyframes, so the path is smooth through
     * every one of them; angles take the short way round. Holds at the ends. Null without keyframes.
     */
    public CamKey sample(double t) {
        if (camera.isEmpty()) return null;
        sort();
        if (camera.size() == 1 || t <= camera.get(0).tick) return copy(camera.get(0));
        CamKey last = camera.get(camera.size() - 1);
        if (t >= last.tick) return copy(last);
        int i = 0;
        while (i < camera.size() - 2 && t > camera.get(i + 1).tick) i++;
        CamKey p1 = camera.get(i), p2 = camera.get(i + 1);
        CamKey p0 = i > 0 ? camera.get(i - 1) : p1, p3 = i + 2 < camera.size() ? camera.get(i + 2) : p2;
        double u = (t - p1.tick) / Math.max(1e-6, p2.tick - p1.tick);
        // unwrap yaw so each step takes the short way round
        float y0 = p0.yaw, y1 = unwrap(y0, p1.yaw), y2 = unwrap(y1, p2.yaw), y3 = unwrap(y2, p3.yaw);
        CamKey out = new CamKey();
        out.tick = t;
        out.x = cr(p0.x, p1.x, p2.x, p3.x, u);
        out.y = cr(p0.y, p1.y, p2.y, p3.y, u);
        out.z = cr(p0.z, p1.z, p2.z, p3.z, u);
        out.yaw = (float) cr(y0, y1, y2, y3, u);
        out.pitch = (float) MathHelper.clamp(cr(p0.pitch, p1.pitch, p2.pitch, p3.pitch, u), -90, 90);
        out.roll = (float) cr(p0.roll, p1.roll, p2.roll, p3.roll, u);
        out.fov = (float) MathHelper.clamp(cr(p0.fov, p1.fov, p2.fov, p3.fov, u), 5, 170);
        return out;
    }

    private static CamKey copy(CamKey k) { return new CamKey(k.tick, k.x, k.y, k.z, k.yaw, k.pitch, k.roll, k.fov); }

    private static float unwrap(float prev, float a) {
        return prev + MathHelper.wrapDegrees(a - prev);
    }

    /** Uniform Catmull-Rom between p1 and p2. */
    static double cr(double p0, double p1, double p2, double p3, double u) {
        double u2 = u * u, u3 = u2 * u;
        return 0.5 * ((2 * p1) + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u2 + (-p0 + 3 * p1 - 3 * p2 + p3) * u3);
    }

    /** Keyframes circling a point: an orbit of one full turn (or {@code degrees}) over {@code ticks}, looking at the centre. */
    public void orbit(Vec3d center, double radius, double height, double startTick, double ticks, double degrees, float startAngle, float fov) {
        int n = Math.max(4, (int) Math.ceil(Math.abs(degrees) / 45));
        camera.removeIf(k -> k.tick >= startTick - 1e-6 && k.tick <= startTick + ticks + 1e-6);
        for (int i = 0; i <= n; i++) {
            double a = Math.toRadians(startAngle + degrees * i / n);
            double x = center.x - Math.sin(a) * radius, z = center.z + Math.cos(a) * radius, y = center.y + height;
            double dx = center.x - x, dy = center.y - y, dz = center.z - z;
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            camera.add(new CamKey(startTick + ticks * i / n, x, y, z, yaw, pitch, 0, fov));
        }
        sort();
    }

    // ------------------------------------------------------------------ speed

    /** Playback speed at a tick (1 without speed keyframes), eased between keyframes. */
    public double speedAt(double t) {
        if (speeds.isEmpty()) return 1;
        sort();
        if (t <= speeds.get(0).tick) return speeds.get(0).speed;
        for (int i = 1; i < speeds.size(); i++) {
            SpeedKey a = speeds.get(i - 1), b = speeds.get(i);
            if (t <= b.tick) {
                double u = (t - a.tick) / Math.max(1e-6, b.tick - a.tick);
                u = u * u * (3 - 2 * u);
                return a.speed + (b.speed - a.speed) * u;
            }
        }
        return speeds.get(speeds.size() - 1).speed;
    }

    /** The replay tick reached after {@code seconds} of video starting at {@code from}, following the speed keyframes. */
    public double advance(double from, double seconds) {
        double t = from, left = seconds;
        double step = 1 / 240.0;
        while (left > 1e-9) {
            double dt = Math.min(step, left);
            t += 20 * speedAt(t) * dt;
            left -= dt;
        }
        return t;
    }
}
