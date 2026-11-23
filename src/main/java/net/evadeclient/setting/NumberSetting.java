package net.evadeclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.Locale;

public class NumberSetting extends Setting<Double> {
    private final double min, max, step;

    public NumberSetting(String name, double value, double min, double max, double step) {
        super(name, value);
        this.min = min;
        this.max = max;
        this.step = step;
    }

    /** Sets the nearest step inside min..max. NaN is ignored. */
    @Override
    public void set(Double v) {
        if (v == null || v.isNaN()) return;
        value = snap(v);
    }

    /**
     * Rounds to a whole number of steps, then drops the floating-point noise of that multiplication
     * (6 * 0.05 is 0.30000000000000004), so values compare and save exactly as they read.
     */
    private double snap(double v) {
        double s = step > 0 ? Math.round(v / step) * step : v;
        if (Math.abs(s) < 1e9) s = Math.round(s * 1e9) / 1e9;
        return Math.max(min, Math.min(max, s));
    }

    /** Back to the exact default, even if it isn't a whole number of steps. */
    @Override public void reset() { value = getDefault(); }

    /** Within a thousandth of a step of the default counts as the default (configs saved before snapping was exact). */
    @Override
    public boolean isDefault() {
        double tolerance = step > 0 ? step / 1000 : 1e-9;
        return Math.abs(value - getDefault()) < tolerance;
    }

    public double getMin() { return min; }
    public double getMax() { return max; }
    /** The size of one step (the slider and the config snap to multiples of it). */
    public double getStep() { return step; }
    public float getFloat() { return value.floatValue(); }
    public int getInt() { return value.intValue(); }

    @Override public JsonElement toJson() { return new JsonPrimitive(value); }

    /** Non-numbers and infinities load as the default; everything else is snapped and clamped like {@link #set}. */
    @Override
    public void fromJson(JsonElement json) {
        double d = json.getAsDouble();
        if (Double.isFinite(d)) set(d); else reset();
    }

    @Override
    public String display() {
        if (step >= 1) return String.valueOf(value.intValue());
        // Two decimals as always; finer steps get as many as they need.
        int decimals = step > 0 && step < 0.01 ? (int) Math.min(6, Math.ceil(-Math.log10(step) - 1e-9)) : 2;
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }
}
