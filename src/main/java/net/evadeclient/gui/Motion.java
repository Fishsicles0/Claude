package net.evadeclient.gui;

import net.evadeclient.util.Theme;

/**
 * Evade's motion core: a damped spring for values that chase a moving target (highlights, scroll, sliders) and the
 * two cubic easing curves used for timed transitions. Frame-rate independent: springs integrate in fixed substeps,
 * so they behave the same at 30 and 300 fps.
 */
public final class Motion {
    private Motion() {}

    /**
     * A damped harmonic spring. {@code w} is the angular frequency (stiffness, rad/s; higher settles faster) and
     * {@code z} the damping ratio (1 = critically damped, no overshoot; below 1 overshoots a little and settles).
     * The first {@link #step} jumps straight to the target, so a new spring never flies in from 0.
     */
    public static final class Spring {
        /** Fixed integration substep, in seconds. */
        private static final float H = 1f / 240f;
        /** Most substeps per {@link #step}: 0.1 s, the longest frame the menu passes in. */
        private static final int MAX_SUBSTEPS = 24;

        public float x, v;
        public boolean init;

        public Spring() {}

        public Spring(float x) { set(x); }

        /**
         * Advance the spring by {@code dt} seconds (at most 0.1) toward {@code target} and return its position.
         * Semi-implicit Euler in ceil(dt * 240) equal substeps of at most 1/240 s (24 at most): one big step of a
         * stiff spring would diverge. Comes to rest exactly on the target once it is within 1e-3 and slower than
         * 1e-2 per second.
         */
        public float step(float target, float w, float z, float dt) {
            if (!Float.isFinite(target)) return x;
            if (!init || !Float.isFinite(x) || !Float.isFinite(v)) {
                set(target);
                return x;
            }
            if (dt > 0 && Float.isFinite(dt)) {
                // ceil(dt / H) equal substeps, each at most H, covering exactly dt (up to the 24-substep cap):
                // whole H steps alone would run the spring up to 40% fast at 144-165 fps.
                float span = Math.min(dt, MAX_SUBSTEPS * H);
                int n = Math.max(1, Math.min(MAX_SUBSTEPS, (int) Math.ceil(span / H - 1e-3f)));
                float h = span / n, k = w * w, c = 2f * z * w;
                for (int i = 0; i < n; i++) {
                    v += (k * (target - x) - c * v) * h;
                    x += v * h;
                }
            }
            if (!Float.isFinite(x) || !Float.isFinite(v) || (Math.abs(target - x) < 1e-3f && Math.abs(v) < 1e-2f)) {
                x = target;
                v = 0;
            }
            return x;
        }

        /** Jump to {@code x} and stop. */
        public void set(float x) {
            this.x = x;
            this.v = 0;
            this.init = true;
        }

        /** Whether the spring has come to rest on {@code target}. */
        public boolean settled(float target) {
            return init && x == target && v == 0;
        }
    }

    /** Ease out: fast start, gentle stop. {@code t} is clamped to 0..1. */
    public static float cubicOut(float t) {
        float u = 1f - clamp01(t);
        return 1f - u * u * u;
    }

    /** Ease in: gentle start, fast finish. {@code t} is clamped to 0..1. */
    public static float cubicIn(float t) {
        float u = clamp01(t);
        return u * u * u;
    }

    /** {@code t} clamped to 0..1 (NaN becomes 0). */
    public static float clamp01(float t) {
        return t > 0f ? (t < 1f ? t : 1f) : 0f;
    }

    // ------------------------------------------------------------------ motion tokens (GUI 3.0, spec 5.1)

    /** Durations in milliseconds: keyboard focus moves, press and hover, tooltips and toggles, page changes and
     *  dropdowns, windows and modals, and the title / splash hero. Exits run at 0.75x the entrance. */
    public static final int DUR_INSTANT = 0, DUR_FAST = 90, DUR_QUICK = 150, DUR_BASE = 220, DUR_SLOW = 320, DUR_HERO = 600;
    /** Longest transition under Reduced motion: every change becomes an opacity crossfade of at most this. */
    public static final int DUR_REDUCED_MAX = 120;

    /** A spring preset: angular frequency {@code w} (rad/s) and damping ratio {@code z}. */
    public record SpringToken(float w, float z) {}

    /** Hover, highlight and knobs (no overshoot). */
    public static final SpringToken SNAPPY = new SpringToken(30f, 1f);
    /** Scroll, indicator glides, the sidebar highlight. */
    public static final SpringToken SMOOTH = new SpringToken(20f, 0.95f);
    /** Wardrobe and emotes only: a small overshoot. */
    public static final SpringToken PLAYFUL = new SpringToken(22f, 0.72f);
    /** What every spring becomes under Reduced motion: fast and critically damped. */
    public static final SpringToken REDUCED = new SpringToken(40f, 1f);

    /** The user's motion level (Client Settings: Animations). */
    public enum Level { FULL, REDUCED, OFF }

    /**
     * The current motion level: Client Settings' Animations (Full / Reduced / Off), with the title screen's own
     * Reduce Motion switch counting as Reduced. Components and screens ask this instead of reading the setting.
     */
    public static Level level() {
        var mods = net.evadeclient.EvadeClient.modules;
        var cs = mods == null ? null : mods.get(net.evadeclient.module.client.ClientSettings.class);
        if (cs != null && cs.animations.is(net.evadeclient.module.client.AnimationsSetting.OFF)) return Level.OFF;
        if (cs != null && cs.animations.is(net.evadeclient.module.client.AnimationsSetting.REDUCED)) return Level.REDUCED;
        try {
            if (net.evadeclient.module.misc.TitleScreenModule.reduceMotion()) return Level.REDUCED;
        } catch (RuntimeException ignored) {
            // not loaded yet
        }
        return Level.FULL;
    }

    /** Whether decorative motion (parallax, tilt, stagger, breathing, shared-element flights) may run. */
    public static boolean decorative() { return level() == Level.FULL; }

    /**
     * Step {@code s} toward {@code target} with the preset {@code t}, honouring the motion level: Reduced swaps in
     * {@link #REDUCED}, Off jumps straight to the target.
     */
    public static float spring(Spring s, float target, SpringToken t, float dt) {
        Level l = level();
        if (l == Level.OFF) { s.set(target); return target; }
        SpringToken u = l == Level.REDUCED ? REDUCED : t;
        return s.step(target, u.w(), u.z(), dt);
    }

    /** A timed transition's length for the motion level: {@code ms} at Full, at most 120 ms at Reduced, 0 at Off. */
    public static int duration(int ms) {
        return switch (level()) {
            case FULL -> ms;
            case REDUCED -> Math.min(ms, DUR_REDUCED_MAX);
            case OFF -> 0;
        };
    }

    /** ease.standard: cubic-bezier(0.2, 0, 0, 1), for entrances and changes. */
    public static float standard(float t) { return STANDARD.at(t); }

    /** ease.exit: cubic-bezier(0.4, 0, 1, 1), for exits. */
    public static float exit(float t) { return EXIT.at(t); }

    private static final Bezier STANDARD = new Bezier(0.2f, 0f, 0f, 1f), EXIT = new Bezier(0.4f, 0f, 1f, 1f);

    /** A CSS-style cubic-bezier(x1, y1, x2, y2) timing curve, evaluated through a 64-sample lookup table. */
    public static final class Bezier {
        private final float[] lut = new float[65];

        public Bezier(float x1, float y1, float x2, float y2) {
            // Sample the curve densely in its parameter and resample y at even x steps.
            int n = 512;
            float[] xs = new float[n + 1], ys = new float[n + 1];
            for (int i = 0; i <= n; i++) {
                float u = i / (float) n, v = 1 - u;
                xs[i] = 3 * v * v * u * x1 + 3 * v * u * u * x2 + u * u * u;
                ys[i] = 3 * v * v * u * y1 + 3 * v * u * u * y2 + u * u * u;
            }
            int j = 0;
            for (int i = 0; i <= 64; i++) {
                float x = i / 64f;
                while (j < n && xs[j + 1] < x) j++;
                float x0 = xs[j], x1v = xs[Math.min(n, j + 1)];
                float f = x1v > x0 ? (x - x0) / (x1v - x0) : 0;
                lut[i] = ys[j] + (ys[Math.min(n, j + 1)] - ys[j]) * clamp01(f);
            }
            lut[0] = 0;
            lut[64] = 1;
        }

        /** The eased value at {@code t} (clamped to 0..1). */
        public float at(float t) {
            float x = clamp01(t) * 64f;
            int i = Math.min(63, (int) x);
            return lut[i] + (lut[i + 1] - lut[i]) * (x - i);
        }
    }

    /** cubic-bezier(x1, y1, x2, y2) at {@code t}. Builds a table each call: keep a {@link Bezier} for hot paths. */
    public static float bezier(float x1, float y1, float x2, float y2, float t) {
        return new Bezier(x1, y1, x2, y2).at(t);
    }

    /** {@code v} rounded to the nearest device pixel (see {@link Theme#snap}). */
    public static float snap(float v) {
        return Theme.snap(v);
    }
}
