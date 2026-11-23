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

    /** {@code v} rounded to the nearest device pixel (see {@link Theme#snap}). */
    public static float snap(float v) {
        return Theme.snap(v);
    }
}
